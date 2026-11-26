package smallthingz.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.SweepGradient
import kotlin.math.ceil

internal fun NativeColorScheme.modalEdgeWidthDp(): Float =
    surfacePreset?.borderWidthDp ?: NativeDefaultColorScheme.THIN_OUTLINE_WIDTH_DP

/** An exterior band on the exposed lip, fading along each rounded shoulder. */
internal class NativeModalEdgeHighlight {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gradientPhase = Matrix()
    private var reflectAtHeight = 0f
    private var cachedWidth = 0
    private var cachedRadius = 0f
    private var cachedBand = 0f
    private var cachedColor = 0

    fun update(width: Int, height: Int, radius: Float, density: Float, entryEdge: NativeDialogEdge,
               color: Int, widthDp: Float) {
        if (Color.alpha(color) == 0 || width <= 0 || height <= 0 ||
            !widthDp.isFinite() || widthDp <= 0f || !density.isFinite() || density <= 0f ||
            !radius.isFinite()) {
            clear()
            return
        }

        val widthPx = width.toFloat()
        val band = minOf(ceil(widthDp * density), ceil(minOf(width, height) / 2f))
        if (band <= 0f) {
            clear()
            return
        }
        val lipRadius = radius.coerceIn(0f, minOf(widthPx / 2f, height.toFloat()))
        reflectAtHeight = if (entryEdge == NativeDialogEdge.TOP) height.toFloat() else 0f
        // Changing only height or orientation can reuse the same top-facing path.
        val sameGradient = width == cachedWidth && lipRadius == cachedRadius && color == cachedColor
        if (sameGradient && band == cachedBand) return
        cachedWidth = width
        cachedRadius = lipRadius
        cachedBand = band
        cachedColor = color
        path.rewind()
        if (lipRadius == 0f) {
            paint.shader = null
            paint.color = color
            path.addRect(0f, -band, widthPx, 0f, Path.Direction.CW)
            return
        }

        // Trace the outside of the band, then the unchanged lip in reverse. One filled
        // path keeps the straight/curved tangency continuous even under fractional transforms.
        val diameter = 2f * lipRadius
        val rightCenter = widthPx - lipRadius
        path.moveTo(lipRadius, -band)
        path.lineTo(rightCenter, -band)
        path.arcTo(widthPx - diameter - band, -band, widthPx + band, diameter + band, 270f, 90f, false)
        path.lineTo(widthPx, lipRadius)
        path.arcTo(widthPx - diameter, 0f, widthPx, diameter, 0f, -90f, false)
        path.lineTo(lipRadius, 0f)
        path.arcTo(0f, 0f, diameter, diameter, 270f, -90f, false)
        path.lineTo(-band, lipRadius)
        path.arcTo(-band, -band, diameter + band, diameter + band, 180f, 90f, false)
        path.close()

        if (sameGradient) return
        val left = SweepGradient(lipRadius, lipRadius,
            intArrayOf(color and 0x00ffffff, color and 0x00ffffff, color, color),
            floatArrayOf(0f, .25f, .5f, 1f))
        val right = SweepGradient(rightCenter, lipRadius,
            intArrayOf(Color.WHITE, Color.WHITE, 0x00ffffff, 0x00ffffff),
            floatArrayOf(0f, .5f, .75f, 1f))
        // Move the sweep seams below the band. The upper half maps to stops .25–.75.
        gradientPhase.setRotate(90f, lipRadius, lipRadius)
        left.setLocalMatrix(gradientPhase)
        gradientPhase.setRotate(90f, rightCenter, lipRadius)
        right.setLocalMatrix(gradientPhase)
        paint.color = Color.WHITE
        paint.shader = ComposeShader(left, right, PorterDuff.Mode.DST_IN)
    }

    private fun clear() {
        if (cachedWidth == 0) return
        cachedWidth = 0
        path.rewind()
        paint.shader = null
    }

    fun draw(canvas: Canvas) {
        if (cachedWidth == 0) return
        if (reflectAtHeight == 0f) {
            canvas.drawPath(path, paint)
            return
        }
        val checkpoint = canvas.save()
        try {
            canvas.translate(0f, reflectAtHeight)
            canvas.scale(1f, -1f)
            canvas.drawPath(path, paint)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }
}
