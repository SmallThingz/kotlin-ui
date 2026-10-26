/*
 * Source-derived bridge for the pinned AndroidX Compose 1.11.2 math.
 * See SOURCE-view-animation.json for the original source and retained motion provenance.
 */
package smallthingz.ui.animation

import androidx.annotation.MainThread
import smallthingz.ui.animation.core.AnimationVector
import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.AnimationVector4D
import smallthingz.ui.animation.core.SpringSpec
import smallthingz.ui.animation.core.TargetBasedAnimation
import smallthingz.ui.animation.core.TwoWayConverter
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.VisibilityThreshold
import smallthingz.ui.graphics.Color
import smallthingz.ui.unit.Dp

/**
 * The released animate*AsState spring math and interruption seam, without Composition,
 * Snapshot state, coroutine ownership, layout traversal or another rendering layer.
 *
 * Calls are serialized by the owning View's main thread. [retarget] means a target was
 * accepted by the UI controller; it does not emulate Compose's SideEffect/channel dispatch.
 * Values retain their original type. In particular, never round a Color to ARGB before
 * storing the next spring's start value, and never convert Dp spring state into pixels.
 */
@MainThread
class SpringValue<T, V : AnimationVector> private constructor(
    initialValue: T,
    private val converter: TwoWayConverter<T, V>,
    private val spec: SpringSpec<T>,
    private val zeroVector: () -> V,
) {
    var value: T = initialValue
        private set
    var targetValue: T = initialValue
        private set
    val isRunning: Boolean get() = animation != null

    private var animation: TargetBasedAnimation<T, V>? = null
    private var velocityVector: V = zeroVector()
    private var startFrameNanos = 0L
    private var lastFrameNanos = 0L
    private var hasStartFrame = false
    private var hasLastFrame = false

    /**
     * Retarget from the last delivered value/frame, not an extrapolation to event wall time.
     * Equal targets do not restart, matching animateValueAsState.
     */
    fun retarget(next: T, durationScale: Float = 1f): Boolean {
        if (next == targetValue) return false
        val previousFrame = lastFrameNanos
        val hadPreviousFrame = hasLastFrame

        // Animatable.animateTo's default initialVelocity is velocity: T, NOT its raw vector.
        // Color.VectorConverter therefore clamps/quantizes velocity through Color on interruption.
        // Passing velocityVector directly would subtly change the released color trajectory.
        val initialVelocity = converter.convertFromVector(velocityVector)
        animation = TargetBasedAnimation(
            animationSpec = spec,
            typeConverter = converter,
            initialValue = value,
            targetValue = next,
            initialVelocity = initialVelocity,
        )
        targetValue = next
        startFrameNanos = previousFrame
        hasStartFrame = hadPreviousFrame

        // A running Animatable reuses lastFrameTimeNanos and delivers its zero-time
        // restarted frame immediately. A stopped one waits for the next frame clock.
        if (hadPreviousFrame) frame(previousFrame, durationScale)
        return true
    }

    /** Sample using the Choreographer/MonotonicFrameClock nanosecond timebase. */
    fun frame(frameTimeNanos: Long, durationScale: Float = 1f) {
        val current = animation ?: return
        require(durationScale >= 0f) { "negative scale factor" }
        val start = if (hasStartFrame) startFrameNanos else frameTimeNanos.also {
            startFrameNanos = it
            hasStartFrame = true
        }
        val playTimeNanos = if (durationScale == 0f) {
            current.durationNanos
        } else {
            // Keep upstream Float division/toLong ordering, including its time quantization.
            ((frameTimeNanos - start) / durationScale).toLong()
        }
        lastFrameNanos = frameTimeNanos
        hasLastFrame = true
        value = current.getValueFromNanos(playTimeNanos)
        velocityVector = current.getVelocityVectorFromNanos(playTimeNanos)
        if (current.isFinishedFromNanos(playTimeNanos)) clearMotion()
    }

    /** Explicit host disposal/stop preserves the last value and retires its momentum. */
    fun stop() = clearMotion()

    fun snapTo(next: T) {
        clearMotion()
        value = next
        targetValue = next
    }

    private fun clearMotion() {
        animation = null
        velocityVector = zeroVector()
        hasLastFrame = false
        hasStartFrame = false
    }

    companion object {
        fun float(initialValue: Float): SpringValue<Float, AnimationVector1D> =
            SpringValue(
                initialValue,
                Float.VectorConverter,
                SpringSpec(visibilityThreshold = 0.01f),
                { AnimationVector1D(0f) },
            )

        fun dp(initialValue: Dp): SpringValue<Dp, AnimationVector1D> =
            SpringValue(
                initialValue,
                Dp.VectorConverter,
                SpringSpec(visibilityThreshold = Dp.VisibilityThreshold),
                { AnimationVector1D(0f) },
            )

        fun color(initialValue: Color): SpringValue<Color, AnimationVector4D> =
            SpringValue(
                initialValue,
                (Color.VectorConverter)(initialValue.colorSpace),
                SpringSpec(),
                { AnimationVector4D(0f, 0f, 0f, 0f) },
            )
    }
}

