package smallthingz.ui.reverb

import smallthingz.ui.animation.AnimatedValue
import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.tween

/** Finite presentation alpha only. Input and content ownership never wait for this channel. */
class ReverbFadeMotion(initialAlpha: Float = 0f) {
    private val value = AnimatedValue(initialAlpha, Float.VectorConverter,
        tween<Float>(160), { AnimationVector1D(0f) })
    val alpha: Float get() = value.value
    val running: Boolean get() = value.running
    fun target(visible: Boolean, durationScale: Float) {
        val target = if (visible) 1f else 0f
        if (durationScale == 0f) value.snap(target) else value.target(target, durationScale)
    }
    fun frame(timeNanos: Long, durationScale: Float) = value.frame(timeNanos, durationScale)
    fun settle(visible: Boolean) = value.snap(if (visible) 1f else 0f)
}
