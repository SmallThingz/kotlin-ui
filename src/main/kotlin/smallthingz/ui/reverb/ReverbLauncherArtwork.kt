package smallthingz.ui.reverb

/**
 * Opt-in ink bounds within the supplied canonical drawable, normalized to its full viewport.
 * Supply the original foreground resource, not an adaptive wrapper whose inset is platform-owned.
 * No path, color, or tint is supplied here; all artwork remains owned by that resource.
 */
data class ReverbLauncherArtworkBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f && left < right && top < bottom)
    }
}

internal data class ReverbLauncherArtworkTransform(val scale: Float, val translateX: Float, val translateY: Float)

/** Uniform fit of the declared ink, centered in the existing identity slot. */
internal fun launcherArtworkTransform(bounds: ReverbLauncherArtworkBounds, drawableWidth: Int,
    drawableHeight: Int, width: Int, height: Int): ReverbLauncherArtworkTransform? {
    if (drawableWidth <= 0 || drawableHeight <= 0 || width <= 0 || height <= 0) return null
    val left = bounds.left * drawableWidth
    val top = bounds.top * drawableHeight
    val inkWidth = (bounds.right - bounds.left) * drawableWidth
    val inkHeight = (bounds.bottom - bounds.top) * drawableHeight
    val scale = minOf(width / inkWidth, height / inkHeight)
    if (!scale.isFinite()) return null
    return ReverbLauncherArtworkTransform(scale,
        (width - inkWidth * scale) / 2f - left * scale,
        (height - inkHeight * scale) / 2f - top * scale)
}
