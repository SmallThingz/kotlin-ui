/*
 * Default indeterminate LinearProgressIndicator from AndroidX Material3 1.4.0.
 * Copyright The Android Open Source Project. Apache-2.0.
 */
package smallthingz.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.RequiresApi
import smallthingz.ui.animation.core.withInfiniteAnimationFrameNanos
import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Indeterminate linear progress driven by the supplied window clock and animation scope.
 * Offscreen clipping and alpha leave clock admission to the host.
 */
open class NativeIndeterminateLinearProgress(
    context: Context,
    indicatorColor: Int,
    trackColor: Int,
    density: Float,
    private val frames: MonotonicFrameClock,
    private val scope: CoroutineScope,
    private val durationScale: () -> Float,
) : NativeLinearProgressView(context, indicatorColor, trackColor, density) {
    private val motion = NativeIndeterminateLinearMotion()
    private var frameJob: Job? = null
    private var scaleChanges: Channel<Unit>? = null
    private var scaleListener: AutoCloseable? = null
    override val accessibilityProgress: Float? get() = null

    init {
        isFocusable = false
    }

    fun bindColors(indicatorColor: Int, trackColor: Int, density: Float) {
        if (bindAppearance(indicatorColor, trackColor, density)) requestLayout()
        invalidate()
    }

    /** Required wake-up for a caller-supplied duration-scale change not backed by ValueAnimator. */
    fun motionScaleChanged() { scaleChanges?.trySend(Unit) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        motion.reset()
        val wake = Channel<Unit>(Channel.CONFLATED)
        scaleChanges = wake
        try {
            scaleListener = if (Build.VERSION.SDK_INT >= 33) {
                LinearDurationScaleListenerApi33.register { wake.trySend(Unit) }
            } else {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) { wake.trySend(Unit) }
                }
                context.contentResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
                AutoCloseable { context.contentResolver.unregisterContentObserver(observer) }
            }
            durationScale()
            frameJob = scope.launch(frames) {
                while (isActive) {
                    var sampledScale = 1f
                    // Reuse the inherited clock and any installed infinite-animation policy.
                    withInfiniteAnimationFrameNanos { time ->
                        sampledScale = durationScale()
                        motion.frame(time, sampledScale)
                        invalidate()
                    }
                    if (sampledScale == 0f) {
                        // InfiniteTransition suspends after drawing all four target values.
                        // No busy loop, per-view timer or unscaled platform animator is introduced.
                        while (durationScale() == 0f) wake.receive()
                    }
                }
            }
        } catch (failure: Throwable) {
            try { releaseAnimation() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    override fun onDetachedFromWindow() {
        try { releaseAnimation() } finally { super.onDetachedFromWindow() }
    }

    private fun releaseAnimation() {
        frameJob?.cancel()
        frameJob = null
        scaleChanges?.close()
        scaleChanges = null
        val listener = scaleListener
        scaleListener = null
        motion.reset()
        listener?.close()
    }

    override fun onDraw(canvas: Canvas) {
        nativeIndeterminateLinearSegments(width.toFloat(), height.toFloat(), density,
            motion.firstHead, motion.firstTail, motion.secondHead, motion.secondTail) { start, end, active ->
            drawProgressLine(canvas, start, end, colors.color(if (active) 0 else 1))
        }
    }
}

@RequiresApi(33)
private object LinearDurationScaleListenerApi33 {
    fun register(wake: () -> Unit): AutoCloseable {
        val listener = ValueAnimator.DurationScaleChangeListener { wake() }
        ValueAnimator.registerDurationScaleChangeListener(listener)
        return AutoCloseable { ValueAnimator.unregisterDurationScaleChangeListener(listener) }
    }
}
