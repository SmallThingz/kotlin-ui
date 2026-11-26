package smallthingz.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import smallthingz.ui.geometry.roundedGroupGeometry

/** Uses the same inset and device-pixel rounding as the shared control border. */
internal class MarkPlateOutline(val color: Int, val widthDp: Float) {
    private val paint = Paint(BRAND_PAINT_FLAGS).apply { this.color = this@MarkPlateOutline.color }
    private val bounds = RectF()
    private var radius = 0f

    fun update(width: Float, height: Float, density: Float, outerRadius: Float) {
        val geometry = roundedGroupGeometry(width, height, density, outerRadius / density, widthDp)
        paint.strokeWidth = geometry.strokeWidth
        bounds.set(0f, 0f, width, height)
        if (geometry.fillBorder) {
            paint.style = Paint.Style.FILL
            radius = geometry.radius
        } else {
            paint.style = Paint.Style.STROKE
            radius = geometry.strokeRadius
            bounds.inset(geometry.strokeWidth / 2f, geometry.strokeWidth / 2f)
        }
    }

    fun draw(canvas: Canvas) {
        if (paint.strokeWidth > 0f) canvas.drawRoundRect(bounds, radius, radius, paint)
    }
}
