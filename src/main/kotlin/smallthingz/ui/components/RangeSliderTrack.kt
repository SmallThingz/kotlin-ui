package smallthingz.ui.components

import android.graphics.Canvas
import android.view.View
import smallthingz.ui.controls.ControlPalette
import kotlin.math.ceil

/** Draws the track and both thumbs in the caller's physical canvas coordinates. */
internal class RangeSliderTrack(
    private val owner: View,
    private var style: NativeSliderStyle,
    palette: ControlPalette,
    grid: RangeSliderGrid,
) {
    private val intervals = grid.intervals
    private val paint = SliderPaint(owner, palette, style.colors)

    fun applyPalette(palette: ControlPalette) = paint.applyPalette(palette)

    fun applyStyle(style: NativeSliderStyle) {
        if (this.style == style) return
        this.style = style
        paint.applyColors(style.colors)
        owner.invalidate()
    }

    fun onEnabledChanged() = paint.onEnabledChanged()

    fun draw(
        canvas: Canvas,
        left: Float,
        right: Float,
        centerY: Float,
        lowerX: Float,
        upperX: Float,
        lowerPressed: Boolean,
        upperPressed: Boolean,
    ) {
        if (right <= left) return
        val density = owner.resources.displayMetrics.density
        val enabled = owner.isEnabled
        val trackHeight = style.trackHeightDp * density
        val trackRadius = trackHeight / 2f
        val insideRadius = style.trackInsideCornerDp * density
        val thumbHalfHeight = style.thumbHeightDp * density / 2f
        val thumbHalfWidth = style.thumbWidthDp * density / 2f
        val pressedThumbHalfWidth = style.pressedThumbWidthDp * density / 2f
        val thumbGap = style.thumbTrackGapDp * density
        val tickRadius = style.tickSizeDp * density / 2f
        val stopRadius = if (style.showStopIndicator) style.stopIndicatorSizeDp * density / 2f else 0f
        val lowerHalfWidth = if (enabled && lowerPressed) pressedThumbHalfWidth else thumbHalfWidth
        val upperHalfWidth = if (enabled && upperPressed) pressedThumbHalfWidth else thumbHalfWidth
        val lowerGap = if (thumbGap > 0f) thumbGap + lowerHalfWidth else 0f
        val upperGap = if (thumbGap > 0f) thumbGap + upperHalfWidth else 0f

        val lowerOnLeft = lowerX <= upperX
        val firstX = if (lowerOnLeft) lowerX else upperX
        val lastX = if (lowerOnLeft) upperX else lowerX
        val firstGap = if (lowerOnLeft) lowerGap else upperGap
        val lastGap = if (lowerOnLeft) upperGap else lowerGap
        val leftEnd = (firstX - firstGap).coerceIn(left, right)
        val activeStart = (firstX + firstGap).coerceIn(left, right)
        val activeEnd = (lastX - lastGap).coerceIn(left, right)
        val rightStart = (lastX + lastGap).coerceIn(left, right)

        if (trackHeight > 0f) {
            paint.drawSegment(canvas, left, leftEnd, centerY, trackHeight, trackRadius, insideRadius,
                paint.inactiveTrack)
            paint.drawSegment(canvas, activeStart, activeEnd, centerY, trackHeight, insideRadius, insideRadius,
                paint.activeTrack)
            paint.drawSegment(canvas, rightStart, right, centerY, trackHeight, insideRadius, trackRadius,
                paint.inactiveTrack)

            if (intervals > 1L && tickRadius > 0f) {
                val width = right - left
                // Sample only actual grid indices, coalescing subpixel pins for very dense ranges.
                val stride = ceil(intervals.toDouble() / width.coerceAtLeast(1f)).toLong().coerceAtLeast(1L)
                val rtl = lowerX > upperX || (lowerX == upperX && owner.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                var index = (stride / 2L).coerceAtLeast(1L)
                while (index < intervals) {
                    val fraction = index.toDouble() / intervals
                    val x = (left + width * if (rtl) 1.0 - fraction else fraction).toFloat()
                    if (x > activeStart && x < activeEnd) {
                        paint.drawDot(canvas, x, centerY, tickRadius, paint.activeTick)
                    } else if (x < leftEnd || x > rightStart) {
                        paint.drawDot(canvas, x, centerY, tickRadius, paint.inactiveTick)
                    }
                    index += stride
                }
            }

            if (stopRadius > 0f) {
                val inset = maxOf(trackRadius, stopRadius)
                if (left + inset + stopRadius <= leftEnd) {
                    paint.drawDot(canvas, left + inset, centerY, stopRadius, paint.activeTrack)
                }
                if (right - inset - stopRadius >= rightStart) {
                    paint.drawDot(canvas, right - inset, centerY, stopRadius, paint.activeTrack)
                }
            }
        }

        if (lowerX == upperX) {
            paint.drawThumb(canvas, lowerX, centerY, minOf(lowerHalfWidth, upperHalfWidth), thumbHalfHeight)
        } else {
            paint.drawThumb(canvas, lowerX, centerY, lowerHalfWidth, thumbHalfHeight)
            paint.drawThumb(canvas, upperX, centerY, upperHalfWidth, thumbHalfHeight)
        }
    }
}
