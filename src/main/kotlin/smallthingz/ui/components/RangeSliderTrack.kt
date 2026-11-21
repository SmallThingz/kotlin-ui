package smallthingz.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import smallthingz.ui.controls.ControlPalette
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Draws the track and both thumbs in the caller's physical canvas coordinates. */
internal class RangeSliderTrack(style: NativeSliderStyle, palette: ControlPalette, density: Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val bounds = RectF()
    private val radii = FloatArray(8)
    private val trackRadius = style.trackHeightDp * density / 2f
    private val insideRadius = (style.trackInsideCornerDp * density).coerceAtMost(trackRadius)
    private val thumbHalfHeight = style.thumbHeightDp * density / 2f
    private val thumbHalfWidth = style.thumbWidthDp * density / 2f
    private val pressedThumbHalfWidth = style.pressedThumbWidthDp * density / 2f
    private val thumbGap = style.thumbTrackGapDp * density
    private val steps = style.steps
    private val tickRadius = style.tickSizeDp * density / 2f
    private val stopRadius = if (style.showStopIndicator) style.stopIndicatorSizeDp * density / 2f else 0f

    private val colors = style.colors
    private val mutedInk = ColorUtils.setAlphaComponent(palette.content, (.38f * 255).roundToInt())
    private val faintInk = ColorUtils.setAlphaComponent(palette.content, (.12f * 255).roundToInt())
    private val activeTrack = colors.activeTrackColor ?: palette.accent
    private val inactiveTrack = colors.inactiveTrackColor
        ?: ColorUtils.compositeColors(ColorUtils.setAlphaComponent(Color.BLACK, (.18f * 255).roundToInt()), palette.selection)
    private val activeTick = colors.activeTickColor ?: colors.inactiveTrackColor ?: palette.selection
    private val inactiveTick = colors.inactiveTickColor ?: activeTrack
    private val thumb = colors.thumbColor ?: palette.accent
    private val disabledActiveTrack = colors.disabledActiveTrackColor ?: mutedInk
    private val disabledInactiveTrack = colors.disabledInactiveTrackColor ?: faintInk
    private val disabledActiveTick = colors.disabledActiveTickColor ?: faintInk
    private val disabledInactiveTick = colors.disabledInactiveTickColor ?: mutedInk
    private val disabledThumb = colors.disabledThumbColor ?: ColorUtils.compositeColors(mutedInk, palette.surface)

    fun draw(
        canvas: Canvas,
        left: Float,
        right: Float,
        centerY: Float,
        lowerX: Float,
        upperX: Float,
        enabled: Boolean,
        lowerPressed: Boolean,
        upperPressed: Boolean,
    ) {
        if (right <= left) return
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

        val activeColor = if (enabled) activeTrack else disabledActiveTrack
        val inactiveColor = if (enabled) inactiveTrack else disabledInactiveTrack
        if (trackRadius > 0f) {
            drawSegment(canvas, left, leftEnd, centerY, trackRadius, insideRadius, inactiveColor)
            drawSegment(canvas, activeStart, activeEnd, centerY, insideRadius, insideRadius, activeColor)
            drawSegment(canvas, rightStart, right, centerY, insideRadius, trackRadius, inactiveColor)

            if (steps > 0 && tickRadius > 0f) {
                val intervals = steps + 1
                val width = right - left
                // Skip subpixel repetitions on dense grids while retaining their original spacing.
                val stride = ceil(intervals.toDouble() / width.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
                val activeTickColor = if (enabled) activeTick else disabledActiveTick
                val inactiveTickColor = if (enabled) inactiveTick else disabledInactiveTick
                var index = 1
                while (index < intervals) {
                    val x = left + width * (index.toDouble() / intervals).toFloat()
                    if (x > activeStart && x < activeEnd) {
                        paint.color = activeTickColor
                        canvas.drawCircle(x, centerY, tickRadius, paint)
                    } else if (x < leftEnd || x > rightStart) {
                        paint.color = inactiveTickColor
                        canvas.drawCircle(x, centerY, tickRadius, paint)
                    }
                    if (intervals - index <= stride) break
                    index += stride
                }
            }

            if (stopRadius > 0f) {
                val inset = maxOf(trackRadius, stopRadius)
                paint.color = activeColor
                if (left + inset + stopRadius <= leftEnd) {
                    canvas.drawCircle(left + inset, centerY, stopRadius, paint)
                }
                if (right - inset - stopRadius >= rightStart) {
                    canvas.drawCircle(right - inset, centerY, stopRadius, paint)
                }
            }
        }

        paint.color = if (enabled) thumb else disabledThumb
        if (lowerX == upperX) {
            drawThumb(canvas, lowerX, centerY, minOf(lowerHalfWidth, upperHalfWidth))
        } else {
            drawThumb(canvas, lowerX, centerY, lowerHalfWidth)
            drawThumb(canvas, upperX, centerY, upperHalfWidth)
        }
    }

    private fun drawSegment(
        canvas: Canvas,
        start: Float,
        end: Float,
        centerY: Float,
        startRadius: Float,
        endRadius: Float,
        color: Int,
    ) {
        if (end <= start) return
        radii.fill(startRadius, 0, 2)
        radii.fill(endRadius, 2, 6)
        radii.fill(startRadius, 6, 8)
        bounds.set(start, centerY - trackRadius, end, centerY + trackRadius)
        path.rewind()
        path.addRoundRect(bounds, radii, Path.Direction.CW)
        paint.color = color
        canvas.drawPath(path, paint)
    }

    private fun drawThumb(canvas: Canvas, x: Float, centerY: Float, halfWidth: Float) {
        bounds.set(x - halfWidth, centerY - thumbHalfHeight, x + halfWidth, centerY + thumbHalfHeight)
        canvas.drawRoundRect(bounds, halfWidth, halfWidth, paint)
    }
}
