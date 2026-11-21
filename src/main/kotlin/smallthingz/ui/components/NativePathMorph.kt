package smallthingz.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.drawable.Drawable

/** Contour interpolation between caller-owned paths in a common square viewport. */
class NativePathMorph(
    fromPath: Path,
    toPath: Path,
    private val endpoint: Drawable,
    private val viewport: Float = 24f,
) {
    private val targetContours = sampleContours(toPath)
    private val target = targetContours.first()
    private val source = alignedSource(fromPath, target, null)
    private val mirroredSource = alignedSource(fromPath, target, viewport)
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var endpointTint = 0
    private var hasEndpointTint = false

    fun draw(canvas: Canvas, bounds: Rect, progress: Float, tint: Int, rtl: Boolean) {
        if (progress >= 1f) {
            endpoint.bounds = bounds
            if (!hasEndpointTint || endpointTint != tint) {
                endpointTint = tint
                endpoint.setTint(tint)
                hasEndpointTint = true
            }
            endpoint.draw(canvas)
            return
        }
        val from = if (rtl) mirroredSource else source
        path.rewind()
        appendContour(from, target, progress)
        // Additional target contours expand from the viewport center.
        for (index in 1 until targetContours.size) appendContour(null, targetContours[index], progress)
        paint.color = tint
        val checkpoint = canvas.save()
        try {
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / viewport, bounds.height() / viewport)
            canvas.drawPath(path, paint)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun appendContour(from: FloatArray?, to: FloatArray, progress: Float) {
        for (index in to.indices step 2) {
            val x = from?.get(index) ?: viewport * .5f
            val y = from?.get(index + 1) ?: viewport * .5f
            val px = x + (to[index] - x) * progress
            val py = y + (to[index + 1] - y) * progress
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
    }

    private companion object {
        const val POINTS = 128
        fun sampleContours(path: Path): List<FloatArray> {
            val measure = PathMeasure(path, true)
            val point = FloatArray(2)
            val result = ArrayList<FloatArray>(3)
            do {
                val contour = FloatArray(POINTS * 2)
                for (index in 0 until POINTS) {
                    measure.getPosTan(measure.length * index / POINTS, point, null)
                    contour[index * 2] = point[0]
                    contour[index * 2 + 1] = point[1]
                }
                result += contour
            } while (measure.nextContour())
            return result
        }
        fun alignedSource(path: Path, target: FloatArray, mirrorViewport: Float?): FloatArray {
            val original = sampleContours(path).first()
            if (mirrorViewport != null) for (index in original.indices step 2) original[index] = mirrorViewport - original[index]
            // Minimize travel in the target's winding direction, including RTL reflection.
            val direction = if (area(original) * area(target) < 0f) -1 else 1
            var bestOffset = 0
            var bestCost = Float.POSITIVE_INFINITY
            for (offset in 0 until POINTS) {
                var cost = 0f
                for (index in 0 until POINTS) {
                    val sourceIndex = ((offset + direction * index + POINTS) % POINTS) * 2
                    val dx = original[sourceIndex] - target[index * 2]
                    val dy = original[sourceIndex + 1] - target[index * 2 + 1]
                    cost += dx * dx + dy * dy
                }
                if (cost < bestCost) { bestCost = cost; bestOffset = offset }
            }
            return FloatArray(original.size) { coordinate ->
                val index = (bestOffset + direction * (coordinate / 2) + POINTS) % POINTS
                original[index * 2 + coordinate % 2]
            }
        }
        fun area(points: FloatArray): Float {
            var result = 0f
            for (index in 0 until POINTS) {
                val next = (index + 1) % POINTS
                result += points[index * 2] * points[next * 2 + 1] - points[next * 2] * points[index * 2 + 1]
            }
            return result
        }
    }
}
