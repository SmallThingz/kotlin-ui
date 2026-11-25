package smallthingz.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pixel checks on the shared renderer, including its unchanged non-incident defaults. */
@RunWith(AndroidJUnit4::class)
class JoinedGroupShapeIntegrationTest {
    @Test fun defaultFirstMiddleLastAndSingletonKeepTheirExistingOutlineAndSeparator() {
        val shape = JoinedGroupShape()
        for (density in listOf(1, 2, 3)) for (first in listOf(false, true)) for (last in listOf(false, true)) {
            val edges = NativeGroupEdges(first, last)
            assertFalse(edges.squareTop)
            assertFalse(edges.squareBottom)
            // Reuse a previously capped renderer: ordinary recording rows must regain the old outline.
            shape.update(WIDTH.toFloat(), HEIGHT.toFloat(), density.toFloat(),
                NativeGroupEdges(false, false, squareTop = true, squareBottom = true))
            render(shape, density, edges) { bitmap ->
                for (y in 0 until density) {
                    assertEquals("Default top first=$first density=$density y=$y",
                        if (first) Color.WHITE else if (y == 0) Color.RED else Color.TRANSPARENT,
                        bitmap.getPixel(WIDTH / 2, y))
                    assertEquals("Default bottom last=$last density=$density y=$y",
                        if (last) Color.WHITE else Color.TRANSPARENT,
                        bitmap.getPixel(WIDTH / 2, HEIGHT - 1 - y))
                }
                assertEquals(Color.WHITE, bitmap.getPixel(0, HEIGHT / 2))
                assertEquals(Color.WHITE, bitmap.getPixel(WIDTH - 1, HEIGHT / 2))
                if (first) assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
                if (last) assertEquals(Color.TRANSPARENT, bitmap.getPixel(WIDTH - 1, HEIGHT - 1))
            }
        }
    }

    @Test fun squareCapsAreContinuousAcrossTheFullWidthIncludingBothCornerJoins() {
        val shape = JoinedGroupShape()
        for (density in listOf(1, 2, 3)) for ((top, bottom) in listOf(true to false, false to true, true to true)) {
            render(shape, density, NativeGroupEdges(false, false, squareTop = top, squareBottom = bottom)) { bitmap ->
                for (y in 0 until density) for (x in 0 until WIDTH) {
                    if (top) assertEquals("Solid square top density=$density at ($x,$y)",
                        Color.WHITE, bitmap.getPixel(x, y))
                    if (bottom) assertEquals("Solid square bottom density=$density at ($x,$y)",
                        Color.WHITE, bitmap.getPixel(x, HEIGHT - 1 - y))
                }
                // Side strokes stay joined to the caps rather than stopping at the new gap boundary.
                for (y in density until HEIGHT - density) {
                    assertEquals(Color.WHITE, bitmap.getPixel(0, y))
                    assertEquals(Color.WHITE, bitmap.getPixel(WIDTH - 1, y))
                }
                if (!bottom) assertEquals(Color.TRANSPARENT, bitmap.getPixel(WIDTH / 2, HEIGHT - 1))
                if (!top) assertEquals(Color.RED, bitmap.getPixel(WIDTH / 2, 0))
            }
        }
    }

    @Test fun squareGapCapPreservesTheOppositeRoundedExternalEdge() {
        val shape = JoinedGroupShape()
        render(shape, 3, NativeGroupEdges(true, false, squareBottom = true)) { bitmap ->
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(WIDTH - 1, 0))
            assertEquals(Color.WHITE, bitmap.getPixel(WIDTH / 2, 0))
            for (x in 0 until WIDTH) assertEquals(Color.WHITE, bitmap.getPixel(x, HEIGHT - 1))
        }
        render(shape, 3, NativeGroupEdges(false, true, squareTop = true)) { bitmap ->
            for (x in 0 until WIDTH) assertEquals(Color.WHITE, bitmap.getPixel(x, 0))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, HEIGHT - 1))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(WIDTH - 1, HEIGHT - 1))
            assertEquals(Color.WHITE, bitmap.getPixel(WIDTH / 2, HEIGHT - 1))
        }
    }

    private fun render(shape: JoinedGroupShape, density: Int, edges: NativeGroupEdges, check: (Bitmap) -> Unit) {
        shape.update(WIDTH.toFloat(), HEIGHT.toFloat(), density.toFloat(), edges)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            shape.drawBorder(Canvas(bitmap), Color.WHITE, Color.RED)
            check(bitmap)
        } finally { bitmap.recycle() }
    }

    companion object {
        private const val WIDTH = 240
        private const val HEIGHT = 120
    }
}
