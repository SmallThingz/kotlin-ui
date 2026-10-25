// SPDX-License-Identifier: Apache-2.0
// Source-derived sampling seam; see SOURCE-view-animation.json.
package smallthingz.ui.animation

import smallthingz.ui.animation.core.AnimationVector
import smallthingz.ui.animation.core.FiniteAnimationSpec
import smallthingz.ui.animation.core.SpringSpec
import smallthingz.ui.animation.core.TargetBasedAnimation
import smallthingz.ui.animation.core.TwoWayConverter

/** Transition child state: interrupted non-spring specs use the default spring, with raw velocity. */
class TransitionValue<T, V : AnimationVector>(
    initial: T,
    private val converter: TwoWayConverter<T, V>,
    private val interruptionSpec: SpringSpec<T>,
    private val zero: () -> V,
) {
    var value = initial
        private set
    var target = initial
        private set
    val running get() = animation != null
    private var animation: TargetBasedAnimation<T, V>? = null
    private var velocity = zero()
    private var offset = 0L

    fun target(next: T, spec: FiniteAnimationSpec<T>, playTime: Long, force: Boolean = false) {
        if (!force && target == next) return
        val selected = if (running && spec !is SpringSpec<*>) interruptionSpec else spec
        animation = TargetBasedAnimation(selected, converter, value, next, initialVelocityVector = velocity)
        target = next
        // Equivalent to Transition.delayed(spec, playTimeNanos), including a shared global clock.
        offset = playTime.coerceAtLeast(0L)
    }

    fun frame(playTime: Long, toEnd: Boolean) {
        val active = animation ?: return
        val local = if (toEnd) active.durationNanos else (playTime - offset).coerceAtLeast(0L)
        value = active.getValueFromNanos(local)
        velocity = active.getVelocityVectorFromNanos(local)
        if (active.isFinishedFromNanos(local)) animation = null
        // Unlike Animatable, Transition leaves its sampled velocity vector intact at completion.
    }

    fun snap(next: T) { value = next; target = next; animation = null; velocity = zero(); offset = 0L }
}

