package smallthingz.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.controls.ControlPalette
import kotlin.math.roundToInt

/** Retains the drawing scratch and animated color roles used by both native sliders. */
internal class SliderPaint(
    private val owner: View,
    private var palette: ControlPalette,
    private var colors: NativeSliderColors,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val bounds = RectF()
    private val cornerRadii = FloatArray(8)
    private val motion = ViewColorTransition(owner, resolveColors(), owner::invalidate)

    val activeTrack: Int get() = motion.color(0)
    val inactiveTrack: Int get() = motion.color(1)
    val activeTick: Int get() = motion.color(2)
    val inactiveTick: Int get() = motion.color(3)
    val thumb: Int get() = motion.color(4)

    fun applyPalette(palette: ControlPalette) {
        if (this.palette == palette) return
        this.palette = palette
        onEnabledChanged()
    }

    fun applyColors(colors: NativeSliderColors) {
        if (this.colors == colors) return
        this.colors = colors
        onEnabledChanged()
    }

    fun onEnabledChanged() = motion.retarget(*resolveColors())

    fun drawSegment(
        canvas: Canvas,
        start: Float,
        end: Float,
        centerY: Float,
        height: Float,
        startRadius: Float,
        endRadius: Float,
        color: Int,
    ) {
        if (end <= start || height <= 0f) return
        val halfHeight = height / 2f
        // Path normalizes the radii when the segment is too narrow for both corners.
        val first = startRadius.coerceIn(0f, halfHeight)
        val last = endRadius.coerceIn(0f, halfHeight)
        cornerRadii.fill(first, 0, 2)
        cornerRadii.fill(last, 2, 6)
        cornerRadii.fill(first, 6, 8)
        bounds.set(start, centerY - halfHeight, end, centerY + halfHeight)
        path.rewind()
        path.addRoundRect(bounds, cornerRadii, Path.Direction.CW)
        paint.color = color
        canvas.drawPath(path, paint)
    }

    fun drawDot(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        paint.color = color
        canvas.drawCircle(x, y, radius, paint)
    }

    fun drawThumb(canvas: Canvas, x: Float, y: Float, halfWidth: Float, halfHeight: Float) {
        paint.color = thumb
        bounds.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
        canvas.drawRoundRect(bounds, halfWidth, halfWidth, paint)
    }

    private fun resolveColors(): IntArray {
        if (!owner.isEnabled) {
            val mutedInk = withAlpha(palette.content, .38f)
            val faintInk = withAlpha(palette.content, .12f)
            return intArrayOf(
                colors.disabledActiveTrackColor ?: mutedInk,
                colors.disabledInactiveTrackColor ?: faintInk,
                colors.disabledActiveTickColor ?: faintInk,
                colors.disabledInactiveTickColor ?: mutedInk,
                colors.disabledThumbColor ?: composite(mutedInk, palette.surface),
            )
        }
        val active = colors.activeTrackColor ?: palette.accent
        return intArrayOf(
            active,
            colors.inactiveTrackColor ?: composite(withAlpha(Color.BLACK, .18f), palette.selection),
            colors.activeTickColor ?: colors.inactiveTrackColor ?: palette.selection,
            colors.inactiveTickColor ?: active,
            colors.thumbColor ?: palette.accent,
        )
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00ffffff) or ((255f * alpha).roundToInt() shl 24)

    private fun composite(foreground: Int, background: Int): Int {
        val a = Color.alpha(foreground) / 255f
        val b = Color.alpha(background) / 255f
        val out = a + b * (1f - a)
        if (out <= 0f) return Color.TRANSPARENT
        fun channel(front: Int, back: Int) = ((front * a + back * b * (1f - a)) / out).roundToInt()
        return Color.argb(
            (out * 255f).roundToInt(),
            channel(Color.red(foreground), Color.red(background)).coerceIn(0, 255),
            channel(Color.green(foreground), Color.green(background)).coerceIn(0, 255),
            channel(Color.blue(foreground), Color.blue(background)).coerceIn(0, 255),
        )
    }
}
