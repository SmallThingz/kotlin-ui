package smallthingz.ui.scroll

import android.graphics.Canvas
import android.graphics.Paint
import smallthingz.ui.controls.textSurfaceForeground

/** Geometry in dp for a passive scrollbar on the physical right edge. */
data class NativeScrollIndicatorStyle(
    val widthDp: Float = 2f,
    val rightInsetDp: Float = 2f,
    val minThumbHeightDp: Float = 24f,
) {
    companion object {
        val Default = NativeScrollIndicatorStyle()
    }
}

internal class NativeScrollIndicator(style: NativeScrollIndicatorStyle, color: Int) {
    private var style = style
    private var preferredColor = color
    private var transitionUnderlay = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color }

    fun bind(style: NativeScrollIndicatorStyle, color: Int): Boolean {
        val styleChanged = this.style != style
        this.style = style
        if (preferredColor == color) return styleChanged
        preferredColor = color
        return updatePaint() || styleChanged
    }

    fun bindTransitionUnderlay(surface: Long): Boolean {
        if (transitionUnderlay == surface) return false
        transitionUnderlay = surface
        return updatePaint()
    }

    private fun updatePaint(): Boolean {
        val color = textSurfaceForeground(transitionUnderlay, preferredColor)
        if (paint.color == color) return false
        paint.color = color
        return true
    }

    fun draw(canvas: Canvas, view: NativeSourceRecyclerView) {
        val extent = view.computeVerticalScrollExtent()
        if (extent <= 0) return
        val range = view.computeVerticalScrollRange()
        if (range <= extent) return
        val density = view.resources.displayMetrics.density
        withNativeScrollIndicatorThumb(
            view.width, view.height, range, extent, view.computeVerticalScrollOffset(),
            style.widthDp * density, style.rightInsetDp * density, style.minThumbHeightDp * density,
        ) { left, top, right, bottom ->
            val radius = (right - left) / 2f
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
        }
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
