package smallthingz.ui.gallery

import smallthingz.ui.animation.AnimatedValue
import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.Spring
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.spring

/** Page-width fraction only. The host owns frames, views and retirement via [reset]. */
class GalleryPhotoPagingMotion {
    private val value = AnimatedValue(0f, Float.VectorConverter,
        spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow, visibilityThreshold = .001f),
        { AnimationVector1D(0f) })

    val fraction: Float get() = value.value
    val running: Boolean get() = value.running

    /** A live drag replaces any settling motion and its velocity. */
    fun drag(fraction: Float) {
        require(fraction.isFinite()) { "fraction must be finite" }
        value.snap(fraction.coerceIn(-1f, 1f))
    }

    fun settle(targetFraction: Float, durationScale: Float) {
        require(targetFraction == -1f || targetFraction == 0f || targetFraction == 1f) {
            "targetFraction must be -1, 0 or 1"
        }
        checkScale(durationScale)
        if (durationScale == 0f) value.snap(targetFraction)
        else value.target(targetFraction, durationScale)
    }

    fun frame(timeNanos: Long, durationScale: Float) {
        checkScale(durationScale)
        value.frame(timeNanos, durationScale)
    }

    fun reset() = value.snap(0f)

    private fun checkScale(scale: Float) {
        require(scale.isFinite() && scale >= 0f) { "durationScale must be finite and nonnegative" }
    }
}
