package smallthingz.ui.scroll

/** Geometry in dp for a scrollbar on the physical right edge. */
data class NativeScrollIndicatorStyle(
    val widthDp: Float = 2f,
    val rightInsetDp: Float = 2f,
    val minThumbHeightDp: Float = 24f,
) {
    /** Physical-right clear content gutter, including one pixel outside the thumb's inward AA edge.
     * Call again when density/style changes; apply as right (not end) padding with child clipping.
     * Invalid geometry has no indicator and requires no gutter, matching the drawing contract.
     */
    fun contentGutterPx(density: Float): Int {
        if (!density.isFinite() || density <= 0f || !widthDp.isFinite() || widthDp <= 0f ||
            !rightInsetDp.isFinite() || rightInsetDp < 0f ||
            !minThumbHeightDp.isFinite() || minThumbHeightDp < 0f) return 0
        val widthPx = widthDp * density
        val insetPx = rightInsetDp * density
        val minimumPx = minThumbHeightDp * density
        if (!widthPx.isFinite() || !insetPx.isFinite() || !minimumPx.isFinite()) return 0
        val span = widthPx.toDouble() + insetPx.toDouble()
        return (kotlin.math.ceil(span) + 1.0).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }

    companion object {
        val Default = NativeScrollIndicatorStyle()
    }
}

internal inline fun withNativeScrollIndicatorThumb(
    viewportWidth: Int,
    viewportHeight: Int,
    scrollRange: Int,
    scrollExtent: Int,
    scrollOffset: Int,
    widthPx: Float,
    rightInsetPx: Float,
    minThumbHeightPx: Float,
    drawThumb: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
) {
    if (viewportWidth <= 0 || viewportHeight <= 0 || scrollExtent <= 0 || scrollRange <= scrollExtent) return
    if (!widthPx.isFinite() || widthPx <= 0f ||
        !rightInsetPx.isFinite() || rightInsetPx < 0f ||
        !minThumbHeightPx.isFinite() || minThumbHeightPx < 0f
    ) return
    val right = viewportWidth - rightInsetPx
    val left = (right - widthPx).coerceAtLeast(0f)
    if (right <= left) return

    val height = viewportHeight.toDouble()
    val minimum = minThumbHeightPx.toDouble().coerceAtMost(height)
    val thumbHeight = (height * scrollExtent / scrollRange).coerceIn(minimum, height)
    val overflow = scrollRange - scrollExtent
    val fraction = scrollOffset.coerceIn(0, overflow).toDouble() / overflow
    val top = (height - thumbHeight) * fraction
    val topPx = top.toFloat()
    val bottomPx = (top + thumbHeight).toFloat()
    if (bottomPx > topPx) drawThumb(left, topPx, right, bottomPx)
}
