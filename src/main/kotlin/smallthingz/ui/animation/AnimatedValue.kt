// SPDX-License-Identifier: Apache-2.0
// Source-derived sampling seam; see SOURCE-view-animation.json.
package smallthingz.ui.animation

import smallthingz.ui.animation.core.AnimationVector
import smallthingz.ui.animation.core.FiniteAnimationSpec
import smallthingz.ui.animation.core.SpringSpec
import smallthingz.ui.animation.core.TargetBasedAnimation
import smallthingz.ui.animation.core.TwoWayConverter

/** Animatable clock/velocity semantics used by animateAsState and animateContentSize. */
class AnimatedValue<T, V : AnimationVector>(
    initial: T,
    private val converter: TwoWayConverter<T, V>,
    private val spec: FiniteAnimationSpec<T>,
    private val zero: () -> V,
) {
    var value = initial
        private set
    var target = initial
        private set
    val running get() = animation != null
    private var animation: TargetBasedAnimation<T, V>? = null
    private var velocity = zero()
    private var startFrame = 0L
    private var lastFrame = 0L
    private var hasStartFrame = false
    private var hasLastFrame = false

    fun target(next: T, scale: Float) {
        if (target == next && (running || value == next)) return
        val previousFrame = lastFrame
        val hadPreviousFrame = hasLastFrame
        // Animatable.animateTo defaults initialVelocity to velocity:T. This conversion matters for
        // Color (Oklab -> packed Color -> Oklab) and IntSize (integer quantization) on interruption.
        animation = TargetBasedAnimation(spec, converter, value, next,
            initialVelocity = converter.convertFromVector(velocity))
        target = next
        startFrame = previousFrame
        hasStartFrame = hadPreviousFrame
        if (hadPreviousFrame) frame(previousFrame, scale)
    }

    fun frame(time: Long, scale: Float) {
        val active = animation ?: return
        require(scale >= 0f) { "negative scale factor" }
        val start = if (hasStartFrame) startFrame else time.also {
            startFrame = it
            hasStartFrame = true
        }
        val play = if (scale == 0f) active.durationNanos else ((time - start) / scale).toLong()
        lastFrame = time
        hasLastFrame = true
        value = active.getValueFromNanos(play)
        velocity = active.getVelocityVectorFromNanos(play)
        if (active.isFinishedFromNanos(play)) stop()
    }

    fun stop() {
        animation = null
        velocity = zero()
        hasStartFrame = false
        hasLastFrame = false
    }

    fun snap(next: T) { stop(); value = next; target = next }

}
