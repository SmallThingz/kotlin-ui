package smallthingz.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.ceil

internal fun NativeColorScheme.modalEdgeWidthDp(): Float =
    surfacePreset?.borderWidthDp ?: NativeDefaultColorScheme.THIN_OUTLINE_WIDTH_DP

/** Inset stroke along the exposed contour, with no paint along the anchored edge. */
internal class NativeModalEdgeHighlight {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun update(width: Int, height: Int, radius: Float, density: Float, entryEdge: NativeDialogEdge,
               color: Int, widthDp: Float) {
        path.rewind()
        paint.color = color
        paint.strokeWidth = 0f
        if (Color.alpha(color) == 0 || width <= 0 || height <= 0 ||
            !widthDp.isFinite() || widthDp <= 0f || !density.isFinite() || density <= 0f ||
            !radius.isFinite()) return

        // Match the single-stroke, whole-pixel rounding used by rounded control borders.
        val stroke = minOf(ceil(widthDp * density), ceil(minOf(width, height) / 2f))
        val inset = stroke / 2f
        val exposedTop = entryEdge == NativeDialogEdge.BOTTOM
        val edgeY = if (exposedTop) inset else height - inset
        val outerRadius = radius.coerceIn(0f, minOf(width / 2f, height.toFloat()))
        val innerRadius = (outerRadius - inset).coerceAtLeast(0f)
        if (innerRadius == 0f) {
            path.moveTo(outerRadius, edgeY)
            path.lineTo(width - outerRadius, edgeY)
        } else {
            val diameter = 2f * innerRadius
            val left = inset
            val right = width - inset
            val top = if (exposedTop) edgeY else edgeY - diameter
            val bottom = top + diameter
            val turn = if (exposedTop) 90f else -90f
            path.arcTo(left, top, left + diameter, bottom, 180f, turn, true)
            path.lineTo(right - innerRadius, edgeY)
            path.arcTo(right - diameter, top, right, bottom, if (exposedTop) 270f else 90f, turn, false)
        }
        paint.strokeWidth = stroke
    }

    fun draw(canvas: Canvas) {
        if (paint.strokeWidth > 0f) canvas.drawPath(path, paint)
    }
}
