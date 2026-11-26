package smallthingz.ui.interaction.view

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** The numeric scale is public on ValueAnimator only from API 33. */
fun readCachedAnimatorDurationScale(context: Context): Float {
    // Initialize the legacy cache on an admission read even while animators are disabled.
    // Enabling animations while the first frame is pending must only read memory on that frame.
    if (Build.VERSION.SDK_INT >= 33) {
        val scale = ValueAnimator.getDurationScale()
        return if (ValueAnimator.areAnimatorsEnabled()) normalizeAnimatorDurationScale(scale) else 0f
    }
    val cached = LegacyAnimatorDurationScale.read(context)
    return legacyFrameScale(cached, ValueAnimator.areAnimatorsEnabled())
}

// A view can receive the enable notification before the cache observer publishes the new scale.
internal fun legacyFrameScale(cached: Float, enabled: Boolean): Float = when {
    !enabled -> 0f
    cached == 0f -> 1f
    else -> cached
}

private fun normalizeAnimatorDurationScale(value: Float): Float =
    if (value.isFinite() && value >= 0f) value else 1f

/** Process lifetime matches the platform animator scale cache; no Activity or View is retained. */
private object LegacyAnimatorDurationScale {
    @Volatile private var cache: CachedAnimatorDurationScale? = null

    fun read(context: Context): Float {
        val state = cache ?: initialize(context)
        return state.value
    }

    @Synchronized private fun initialize(context: Context): CachedAnimatorDurationScale {
        cache?.let { return it }
        val resolver = context.applicationContext.contentResolver
        val handler = Handler(Looper.getMainLooper())
        lateinit var state: CachedAnimatorDurationScale
        val observer = object : ContentObserver(handler) {
            val retry = Runnable { refresh() }

            override fun onChange(selfChange: Boolean) = refresh()

            fun refresh() {
                state.refresh { delay ->
                    handler.removeCallbacks(retry)
                    if (delay != null) handler.postDelayed(retry, delay)
                }
            }
        }
        var observing = false
        state = CachedAnimatorDurationScale(beforeRefresh = {
            if (!observing) {
                try {
                    resolver.registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
                    )
                    observing = true
                } catch (failure: Throwable) {
                    try { resolver.unregisterContentObserver(observer) }
                    catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }
        }) {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
        // Close the read/register gap. Failed setup retries here, never on animation frames.
        observer.refresh()
        cache = state
        return state
    }
}

internal class CachedAnimatorDurationScale(
    private val beforeRefresh: () -> Unit = {},
    private val readSetting: () -> Float,
) {
    private var retryDelayMillis = 1000L
    @Volatile var value: Float = try {
        normalizeAnimatorDurationScale(readSetting())
    } catch (_: SecurityException) {
        // The optional setting may be denied; the scheduled refresh can recover later.
        1f
    }
        private set

    /** Serialize initial setup, observer callbacks and retry scheduling; frames only read [value]. */
    @Synchronized fun refresh(scheduleRetry: (Long?) -> Unit = {}): Long? {
        val delay = try {
            beforeRefresh()
            value = normalizeAnimatorDurationScale(readSetting())
            retryDelayMillis = 1000L
            null
        } catch (_: RuntimeException) {
            retryDelayMillis.also { retryDelayMillis = (it * 2).coerceAtMost(30_000L) }
        }
        scheduleRetry(delay)
        return delay
    }
}
