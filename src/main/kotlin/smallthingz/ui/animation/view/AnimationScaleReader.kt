package smallthingz.ui.animation.view

/** Optional settings denial is retried on a new target, never on every animation frame. */
internal class AnimationScaleReader(
    private val suppliedScale: () -> Float,
    private val animationsEnabled: () -> Boolean,
) {
    private var denied = false

    fun retryOnNewTarget() { denied = false }

    fun read(): Float {
        if (!denied) {
            try { return suppliedScale() }
            catch (_: SecurityException) { denied = true }
        }
        // An explicit supplier remains authoritative when readable; denial must not mask scale zero.
        return if (animationsEnabled()) 1f else 0f
    }
}
