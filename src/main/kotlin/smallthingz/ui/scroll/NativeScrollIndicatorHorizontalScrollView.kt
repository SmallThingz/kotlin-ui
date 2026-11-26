package smallthingz.ui.scroll

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.widget.HorizontalScrollView
import smallthingz.ui.graphics.noiseSurfaceRangeOver
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.components.NativeForegroundGrain
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.controls.textSurfaceForeground

/** Platform horizontal scrolling, focus and accessibility with a passive shared bottom indicator.
 * [NativeScrollIndicatorStyle] is rotated: widthDp is thickness, rightInsetDp is bottom inset,
 * and minThumbHeightDp is minimum horizontal length. Offsets remain physical platform offsets;
 * RTL does not mirror the geometry a second time. No scroll listener or touch handler is replaced.
 * Reserve [horizontalContentGutterPx] as physical bottom padding and enable
 * [clipIndicatorContentToBottomPadding] when content could paint into the indicator underlay.
 */
open class NativeScrollIndicatorHorizontalScrollView(context: Context) : HorizontalScrollView(context) {
    private var palette: ControlPalette? = null
    private var indicatorStyle = NativeScrollIndicatorStyle.Default
    private var underlay = 0L
    private var sourceUnderlay = 0L
    private var underlayOverlay = Color.TRANSPARENT

    /** Clip children at the bottom padding only, retaining the caller's horizontal padding/bleed.
     * Does not change measurement, offsets, hit testing, focus or inherited scroll accessibility.
     * The caller reserves sufficient padding; this property never changes padding automatically.
     */
    var clipIndicatorContentToBottomPadding: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    private val grain = NativeForegroundGrain()
    private val bounds = RectF()
    private val contentClip = Rect()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlay = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
    private val grainMask = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

    init {
        // Only the platform artwork is suppressed; the visible shared indicator is drawn below.
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }

    fun bindIndicator(palette: ControlPalette, style: NativeScrollIndicatorStyle = NativeScrollIndicatorStyle.Default) {
        if (this.palette == palette && indicatorStyle == style) return
        this.palette = palette
        indicatorStyle = style
        grain.bind(palette)
        invalidate()
    }

    /** Actual opaque document range, optionally behind a translucent rounded background.
     * The overlay includes every coverage alpha from zero to its own alpha (rounded-edge AA).
     * Reuses the shared premultiplied raster bounds; no document texture or paint is created.
     */
    fun bindIndicatorUnderlay(paintedSurface: Long, translucentOverlay: Int = Color.TRANSPARENT) {
        if (sourceUnderlay == paintedSurface && underlayOverlay == translucentOverlay) return
        sourceUnderlay = paintedSurface
        underlayOverlay = translucentOverlay
        val next = horizontalIndicatorUnderlay(paintedSurface, translucentOverlay)
        if (underlay == next) return
        underlay = next
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!clipIndicatorContentToBottomPadding) {
            super.dispatchDraw(canvas)
            return
        }
        val saved = canvas.save()
        try {
            // Child drawing is in content coordinates. Convert the viewport bottom to that space.
            // Keep the current clip's other three edges, even with clipToPadding=false.
            canvas.getClipBounds(contentClip)
            val clip = contentClip
            val bottom = scrollY + (height - paddingBottom).coerceAtLeast(0)
            canvas.clipRect(clip.left, clip.top, clip.right, minOf(clip.bottom, bottom))
            super.dispatchDraw(canvas)
        } finally { canvas.restoreToCount(saved) }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val colors = palette ?: return
        val density = resources.displayMetrics.density
        val style = indicatorStyle
        val saved = canvas.save()
        try {
            // ViewGroup's child drawing translates by -scrollX/-scrollY. Paint in viewport space.
            canvas.translate(scrollX.toFloat(), scrollY.toFloat())
            canvas.clipRect(0, 0, width, height)
            withNativeHorizontalScrollIndicatorThumb(width, height, computeHorizontalScrollRange(), computeHorizontalScrollExtent(),
                computeHorizontalScrollOffset(), style.widthDp * density, style.rightInsetDp * density,
                style.minThumbHeightDp * density) { left, top, right, bottom ->
                bounds.set(left, top, right, bottom)
                val radius = (bottom - top) / 2f
                fill.color = textSurfaceForeground(underlay, colors.muted)
                canvas.drawRoundRect(bounds, radius, radius, fill)
                if ((colors.surfacePreset?.foregroundGrain ?: 0f) > 0f && fill.color ushr 24 != 0) {
                    // Keep the direct fill's exact alpha/AA silhouette, including translucent ink.
                    val layer = canvas.saveLayer(bounds, overlay)
                    try {
                        canvas.drawRoundRect(bounds, radius, radius, fill)
                        if (!grain.draw(canvas, this, bounds, radius, Color.WHITE, grainMask))
                            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    } finally { canvas.restoreToCount(layer) }
                }
            }
        } finally { canvas.restoreToCount(saved) }
    }
}

/** Physical-bottom clear gutter for the rotated shared recipe, including its one-pixel AA margin.
 * Recompute on style/density changes independently of palette equality.
 */
fun NativeScrollIndicatorStyle.horizontalContentGutterPx(density: Float): Int = contentGutterPx(density)

internal inline fun withNativeHorizontalScrollIndicatorThumb(
    viewportWidth: Int,
    viewportHeight: Int,
    scrollRange: Int,
    scrollExtent: Int,
    scrollOffset: Int,
    thicknessPx: Float,
    bottomInsetPx: Float,
    minThumbWidthPx: Float,
    drawThumb: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
) = withNativeScrollIndicatorThumb(
    viewportHeight, viewportWidth, scrollRange, scrollExtent, scrollOffset,
    thicknessPx, bottomInsetPx, minThumbWidthPx,
) { left, top, right, bottom -> drawThumb(top, left, bottom, right) }

internal fun horizontalIndicatorUnderlay(surface: Long, overlay: Int): Long {
    var darkest = (surface ushr 32).toInt()
    var lightest = surface.toInt()
    if (darkest ushr 24 != 255 || lightest ushr 24 != 255) return 0L
    // Bounding only base/full-fill endpoints misses intermediate independently rounded terms.
    // At most 255 evaluations, only when the bound surface/overlay pair actually changes.
    for (alpha in 1..(overlay ushr 24)) {
        val range = noiseSurfaceRangeOver(surface, (overlay and 0x00ffffff) or (alpha shl 24), 0)
        val low = (range ushr 32).toInt()
        val high = range.toInt()
        var nextLow = -0x1000000
        var nextHigh = -0x1000000
        for (shift in 0..16 step 8) {
            nextLow = nextLow or (minOf(darkest ushr shift and 255, low ushr shift and 255) shl shift)
            nextHigh = nextHigh or (maxOf(lightest ushr shift and 255, high ushr shift and 255) shl shift)
        }
        darkest = nextLow
        lightest = nextHigh
    }
    return textSurfaceRange(darkest, lightest)
}
