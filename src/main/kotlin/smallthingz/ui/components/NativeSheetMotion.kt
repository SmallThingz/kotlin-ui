// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components

import android.os.Looper
import android.view.Choreographer
import android.view.View
import androidx.annotation.MainThread
import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.SpringSpec
import smallthingz.ui.animation.core.TargetBasedAnimation
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.view.readAnimatorDurationScale

/**
 * One frame owner for a sheet's offset. Gesture, anchor and dismissal decisions stay in the host.
 * The spring tokens follow the declared Material3 1.4.0 StandardMotion reference; this is not a
 * claim that an old APK's rendered timing has been reproduced. No window-position animator runs
 * alongside this owner.
 *
 * The host calls [stop] on hide, detach and presentation replacement. A frame also checks the
 * actual View lifetime, so an abandoned frame cannot publish a hidden sheet or complete dismissal.
 */
@MainThread
internal class NativeSheetMotion(
    private val view: View,
    private val onValue: (Float) -> Unit,
) {
    var value: Float = 0f
        private set
    var velocity: Float = 0f
        private set
    val isRunning: Boolean get() = animation != null

    private val choreographer: Choreographer
    private var animation: TargetBasedAnimation<Float, AnimationVector1D>? = null
    private var completion: (() -> Unit)? = null
    private var generation = 0L
    private var frameCallback: Choreographer.FrameCallback? = null
    private var framePosted = false
    private var lastFrameNanos = 0L
    private var hasLastFrame = false
    private var playTimeNanos = 0.0

    init {
        requireMainThread()
        choreographer = Choreographer.getInstance()
    }

    fun start(
        from: Float,
        target: Float,
        initialVelocity: Float,
        hide: Boolean,
        onFinished: () -> Unit,
    ) {
        requireMainThread()
        require(from.isFinite() && target.isFinite() && initialVelocity.isFinite())
        stop()
        val owner = generation
        val next = TargetBasedAnimation(
            animationSpec = if (hide) HideSpring else SettleSpring,
            typeConverter = Float.VectorConverter,
            initialValue = from,
            targetValue = target,
            initialVelocity = initialVelocity,
        )
        animation = next
        completion = onFinished
        frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
            doFrame(owner, next, frameTimeNanos)
        }
        value = from
        velocity = initialVelocity
        if (!canPublish()) {
            stop()
            return
        }
        try {
            if (readAnimatorDurationScale(view.context) == 0f || next.durationNanos == 0L) {
                finish(owner, next)
                return
            }
            publish(owner, from)
            postFrameIfOwned(owner, next)
        } catch (failure: Throwable) {
            if (generation == owner) stop()
            throw failure
        }
    }

    /** Retire this motion without publishing a terminal value or calling its completion. */
    fun stop() {
        requireMainThread()
        generation++
        frameCallback?.let(choreographer::removeFrameCallback)
        frameCallback = null
        framePosted = false
        animation = null
        completion = null
        velocity = 0f
        hasLastFrame = false
        playTimeNanos = 0.0
    }

    /** Immediate host positioning, including while preparing an unattached sheet for first draw. */
    fun snapTo(value: Float) {
        requireMainThread()
        require(value.isFinite())
        stop()
        this.value = value
        publish(generation, value)
    }

    private fun doFrame(
        owner: Long,
        current: TargetBasedAnimation<Float, AnimationVector1D>,
        frameTimeNanos: Long,
    ) {
        // Choreographer can already have extracted a callback when stop removes its queue entry.
        // Such a callback must not clear the replacement motion's posted-frame bookkeeping.
        if (!owns(owner, current)) return
        framePosted = false
        if (!canPublish()) {
            stop()
            return
        }
        try {
            val scale = readAnimatorDurationScale(view.context)
            if (scale == 0f) {
                finish(owner, current)
                return
            }
            val previous = lastFrameNanos
            if (!hasLastFrame) {
                lastFrameNanos = frameTimeNanos
                hasLastFrame = true
            } else if (frameTimeNanos >= previous) {
                // Integrating each monotonic interval prevents a live duration-scale change
                // from rewinding the sheet. The spring itself remains the shared sampler.
                playTimeNanos += (frameTimeNanos - previous).toDouble() / scale
                lastFrameNanos = frameTimeNanos
            }
            val elapsed = playTimeNanos.toLong()
            if (current.isFinishedFromNanos(elapsed)) {
                finish(owner, current)
                return
            }
            value = current.getValueFromNanos(elapsed)
            velocity = current.getVelocityVectorFromNanos(elapsed).value
            publish(owner, value)
            postFrameIfOwned(owner, current)
        } catch (failure: Throwable) {
            if (generation == owner) stop()
            throw failure
        }
    }

    private fun finish(owner: Long, current: TargetBasedAnimation<Float, AnimationVector1D>) {
        if (!owns(owner, current)) return
        if (!canPublish()) {
            stop()
            return
        }
        val callback = completion
        // Commit retirement before either external callback: both can start another presentation.
        animation = null
        completion = null
        frameCallback?.let(choreographer::removeFrameCallback)
        frameCallback = null
        framePosted = false
        hasLastFrame = false
        playTimeNanos = 0.0
        value = current.targetValue
        velocity = 0f
        publish(owner, value)
        if (generation == owner && animation == null && canPublish()) callback?.invoke()
    }

    private fun publish(owner: Long, next: Float) {
        try {
            onValue(next)
        } catch (failure: Throwable) {
            // A callback may have installed a newer motion before it failed. Retire only ours.
            if (generation == owner) stop()
            throw failure
        }
    }

    private fun postFrameIfOwned(owner: Long, current: TargetBasedAnimation<Float, AnimationVector1D>) {
        if (!owns(owner, current)) return
        if (!canPublish()) {
            stop()
            return
        }
        if (!framePosted) {
            val callback = frameCallback ?: return
            framePosted = true
            choreographer.postFrameCallback(callback)
        }
    }

    private fun owns(owner: Long, current: TargetBasedAnimation<Float, AnimationVector1D>): Boolean =
        generation == owner && animation === current

    private fun canPublish(): Boolean =
        view.isAttachedToWindow && view.windowVisibility == View.VISIBLE && view.isShown

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Sheet motion belongs to the main thread" }
    }

    private companion object {
        val SettleSpring = SpringSpec<Float>(dampingRatio = .9f, stiffness = 700f, visibilityThreshold = .01f)
        val HideSpring = SpringSpec<Float>(dampingRatio = 1f, stiffness = 3800f, visibilityThreshold = .01f)
    }
}
