package smallthingz.ui.graphics

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/** Process-wide grain trim for Activity-hosted UI, including its dialogs and popups. */
object NoiseUiLifecycle {
    private val installed = WeakHashMap<Application, Unit>()

    /**
     * Install from Application.onCreate. All started Activities protect the cache.
     * [prewarmOnStart] can limit eager generation to Activities that actually draw grain.
     * Hosts with independent overlay windows need their own policy.
     */
    @MainThread
    fun install(application: Application, prewarmOnStart: (Activity) -> Boolean = { true }) {
        check(Looper.myLooper() === Looper.getMainLooper())
        if (application in installed) return
        val callbacks = NoiseUiLifecycleCallbacks(
            prewarm = { activity ->
                val requested = prewarmOnStart(activity)
                if (requested) NoiseTexture.prepare()
                requested
            },
            releaseUiResources = { NoiseTexture.releaseUiResources() },
        )
        application.registerActivityLifecycleCallbacks(callbacks)
        application.registerComponentCallbacks(callbacks)
        installed[application] = Unit
    }
}

/** A paused Activity may still have a visible window. Only the last stop arms release. */
internal class NoiseUiLifecycleCallbacks(
    private val prewarm: (Activity) -> Boolean,
    private val releaseUiResources: () -> Unit,
) : Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {
    private val started = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private val main = Handler(Looper.getMainLooper())
    private val releaseAfterGrace = Runnable { releaseIfHidden() }
    private var releasePending = false
    private var prewarmed = false

    override fun onActivityStarted(activity: Activity) {
        if (!started.add(activity)) return
        if (started.size == 1) {
            cancelRelease()
            prewarmed = false
        }
        if (!prewarmed) prewarmed = prewarm(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        if (!started.remove(activity) || started.isNotEmpty()) return
        // Foreground-service processes may not receive UI_HIDDEN. Keep a quick-return grace.
        releasePending = main.postDelayed(releaseAfterGrace, BACKGROUND_GRACE_MILLIS)
    }

    override fun onTrimMemory(level: Int) {
        // UI_HIDDEN alone does not shorten the grace.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) releaseIfHidden()
    }

    @Suppress("DEPRECATION")
    override fun onLowMemory() = releaseIfHidden()

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    private fun releaseIfHidden() {
        if (!releasePending || started.isNotEmpty()) return
        cancelRelease()
        releaseUiResources()
    }

    private fun cancelRelease() {
        if (!releasePending) return
        releasePending = false
        main.removeCallbacks(releaseAfterGrace)
    }

    override fun onActivityDestroyed(activity: Activity) = onActivityStopped(activity)
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private companion object {
        const val BACKGROUND_GRACE_MILLIS = 10_000L
    }
}
