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
    converter: TwoWayConverter<T, V>,
    spec: SpringSpec<T>,
    zeroVector: () -> V,
) {
    private val motion = AnimatedValue(initialValue, converter, spec, zeroVector)
    var value: T
        get() = motion.value
        private set(next) { motion.setValueOnly(next) }
    var targetValue: T
        get() = motion.target
        private set(next) { motion.setTargetOnly(next) }
    val isRunning: Boolean get() = motion.running

    /**
     * Retarget from the last delivered value/frame, not an extrapolation to event wall time.
     * Equal targets do not restart, matching animateValueAsState.
     */
    fun retarget(next: T, durationScale: Float = 1f): Boolean {
        if (next == targetValue) return false
        motion.target(next, durationScale)
        return true
    }

    /** Sample using the Choreographer/MonotonicFrameClock nanosecond timebase. */
    fun frame(frameTimeNanos: Long, durationScale: Float = 1f) = motion.frame(frameTimeNanos, durationScale)

    /** Explicit host disposal/stop preserves the last value and retires its momentum. */
    fun stop() = motion.stop()

    fun snapTo(next: T) {
        motion.stop()
        value = next
        targetValue = next
    }

    companion object {
        /** Explicit component motion tokens, with the same interruption and frame semantics. */
        fun float(initialValue: Float, spec: SpringSpec<Float>): SpringValue<Float, AnimationVector1D> =
            SpringValue(initialValue, Float.VectorConverter, spec, { AnimationVector1D(0f) })

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

