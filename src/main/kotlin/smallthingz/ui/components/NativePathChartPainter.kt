package smallthingz.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * Quarter-grid and clipped, horizontally translated traces using caller-owned paths.
 * The caller supplies viewport coordinates, series order and accent identity. This painter
 * neither mutates paths nor owns samples, time, interpolation, invalidation or accessibility.
 * Use one instance per chart on its drawing thread.
 */
class NativePathChartPainter(private val ink: Int, private val density: Float) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        strokeCap = Paint.Cap.ROUND
    }

    /** Draw separately so the caller retains the ordering of its time/path preparation. */
    fun drawGrid(canvas: Canvas, width: Int, height: Int) {
        pen.color = ink
        pen.alpha = 26
        pen.strokeWidth = density
        for (quarter in 1..3) {
            val y = height * quarter / 4f
            canvas.drawLine(0f, y, width.toFloat(), y, pen)
        }
    }

    /** Clip before translating; iterate in caller order so its accent can be painted last. */
    fun <K> drawPaths(canvas: Canvas, width: Int, height: Int, paths: Map<K, Path>,
        translationX: Float, isAccent: (K) -> Boolean) {
        canvas.save()
        try {
            canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
            canvas.translate(translationX, 0f)
            pen.style = Paint.Style.STROKE
            for ((series, path) in paths) {
                val accent = isAccent(series)
                pen.alpha = if (accent) 255 else 90
                pen.strokeWidth = density * if (accent) 2.5f else 1f
                canvas.drawPath(path, pen)
            }
        } finally {
            canvas.restore()
        }
    }
}
