package smallthingz.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.injector.InjectPathChartPainter

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativePathChartPainterHostTest {
    @Test fun rasterMatchesPriorAppRecipeWithoutChangingPathsOrCanvasState() {
        val paths = linkedMapOf(
            "secondary" to Path().apply {
                moveTo(-80f, 0f)
                cubicTo(-20f, 70f, 35f, -20f, 65f, 40f)
                lineTo(160f, 40f)
            },
            "accent" to Path().apply {
                moveTo(-80f, 40f)
                lineTo(160f, 40f)
            },
        )
        val originalPaths = paths.mapValues { it.value.approximate(.01f) }
        for (density in listOf(1f, 1.5f)) for (ink in listOf(0xff336699.toInt(), 0x80336699.toInt())) {
            val painter = InjectPathChartPainter(ink, density)
            val priorPen = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
            // Reuse both paint owners across frames, including empty and reversed-series frames.
            for ((frame, shift) in listOf(0f, -9.25f, 37.5f, 0f).withIndex()) {
                val series = when (frame) {
                    2 -> paths.entries.reversed().associateTo(linkedMapOf()) { it.key to it.value }
                    3 -> emptyMap()
                    else -> paths
                }
                val expected = bitmap { canvas ->
                    priorRecipe(canvas, priorPen, ink, density, series, shift)
                }
                val actual = bitmap { canvas ->
                    val saves = canvas.saveCount
                    val clip = canvas.clipBounds
                    painter.drawGrid(canvas, 80, 60)
                    painter.drawPaths(canvas, 80, 60, series, shift) { it == "accent" }
                    assertEquals(saves, canvas.saveCount)
                    assertEquals(clip, canvas.clipBounds)
                }
                try {
                    assertTrue("The host must rasterize the grid", actual.getPixel(50, 40) ushr 24 > 0)
                    assertTrue("Raster changed: density=$density ink=$ink frame=$frame", expected.sameAs(actual))
                } finally {
                    expected.recycle()
                    actual.recycle()
                }
            }
        }
        for ((key, path) in paths) assertArrayEquals(originalPaths.getValue(key), path.approximate(.01f), 0f)
    }

    private fun bitmap(draw: (Canvas) -> Unit): Bitmap =
        Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.translate(10f, 10f)
            draw(canvas)
            // Outside the chart clip: a leaked clip or translation changes this marker's pixels.
            canvas.drawRect(85f, 65f, 89f, 69f, Paint().apply { color = 0xffff00ff.toInt() })
        }

    /** Frozen visual oracle from CpuPanel.kt at 5c18e990c860f4af50c17a34da433fe8a405028e.
     * Only its Canvas/Paint sequence is retained; no CPU history or scheduling enters this test. */
    private fun priorRecipe(canvas: Canvas, pen: Paint, ink: Int, density: Float,
        paths: Map<String, Path>, translation: Float) {
        pen.color = ink
        pen.alpha = 26
        pen.strokeWidth = density
        for (quarter in 1..3) {
            val y = 60 * quarter / 4f
            canvas.drawLine(0f, y, 80f, y, pen)
        }
        canvas.save()
        canvas.clipRect(0f, 0f, 80f, 60f)
        canvas.translate(translation, 0f)
        pen.style = Paint.Style.STROKE
        for ((key, path) in paths) {
            pen.alpha = if (key == "accent") 255 else 90
            pen.strokeWidth = density * if (key == "accent") 2.5f else 1f
            canvas.drawPath(path, pen)
        }
        canvas.restore()
    }
}
