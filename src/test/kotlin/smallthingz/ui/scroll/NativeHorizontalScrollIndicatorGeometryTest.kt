package smallthingz.ui.scroll

import org.junit.Assert.*
import org.junit.Test
import smallthingz.ui.controls.textSurfaceRange

class NativeHorizontalScrollIndicatorGeometryTest {
    private fun bounds(offset: Int, range: Int = 600, extent: Int = 120,
        width: Int = 120, height: Int = 80, thickness: Float = 2f,
        inset: Float = 2f, minimum: Float = 24f): FloatArray? {
        var result: FloatArray? = null
        withNativeHorizontalScrollIndicatorThumb(width, height, range, extent, offset,
            thickness, inset, minimum) { l, t, r, b -> result = floatArrayOf(l, t, r, b) }
        return result
    }

    @Test fun horizontalMetricsUsePhysicalLeftMiddleRightAndClampOverscroll() {
        assertArrayEquals(floatArrayOf(0f, 76f, 24f, 78f), bounds(0)!!, 0f)
        assertArrayEquals(floatArrayOf(48f, 76f, 72f, 78f), bounds(240)!!, 0f)
        assertArrayEquals(floatArrayOf(96f, 76f, 120f, 78f), bounds(480)!!, 0f)
        assertArrayEquals(bounds(0)!!, bounds(Int.MIN_VALUE)!!, 0f)
        assertArrayEquals(bounds(480)!!, bounds(Int.MAX_VALUE)!!, 0f)
        assertArrayEquals(floatArrayOf(0f, 76f, 120f, 78f), bounds(240, minimum = 200f)!!, 0f)
    }

    @Test fun fittingEmptyAndInvalidGeometryCannotPaint() {
        assertNull(bounds(0, range = 120))
        assertNull(bounds(0, range = 100))
        assertNull(bounds(0, extent = 0))
        assertNull(bounds(0, width = 0))
        assertNull(bounds(0, height = 0))
        assertNull(bounds(0, thickness = Float.NaN))
        assertNull(bounds(0, thickness = 0f))
        assertNull(bounds(0, inset = -1f))
        assertNull(bounds(0, inset = 90f))
        assertNull(bounds(0, minimum = Float.POSITIVE_INFINITY))
    }

    @Test fun rotatedGutterBoundsActualFloatRasterProductsAtFractionalDensities() {
        for (density in listOf(180f/160f, 213f/160f, 420f/160f)) {
            for (style in listOf(NativeScrollIndicatorStyle.Default,
                NativeScrollIndicatorStyle(2.75f, 1.125f, 31.5f))) {
                val gutter = style.horizontalContentGutterPx(density)
                for (offset in listOf(0, 240, 480)) {
                    val thumb = bounds(offset, thickness = style.widthDp*density,
                        inset = style.rightInsetDp*density, minimum = style.minThumbHeightDp*density)!!
                    assertTrue("One clear AA pixel must separate content and thumb", 80-gutter+1 <= thumb[1])
                    assertTrue(thumb[3] <= 80)
                }
            }
        }
        assertEquals(0, NativeScrollIndicatorStyle.Default.horizontalContentGutterPx(Float.NaN))
    }

    @Test fun roundedOverlayIncludesIntermediatePremultiplicationExtrema() {
        val gray = 0xff111111.toInt()
        // alpha=0 and alpha=15 have exact 17-channel endpoints; intermediate raster terms
        // independently round to 16/18. Endpoint-only union would incorrectly return 17/17.
        val range = horizontalIndicatorUnderlay(textSurfaceRange(gray), 0x0f111111)
        assertEquals(0xff101010.toInt(), (range ushr 32).toInt())
        assertEquals(0xff121212.toInt(), range.toInt())
        assertEquals(textSurfaceRange(gray), horizontalIndicatorUnderlay(textSurfaceRange(gray), 0))
        assertEquals(0L, horizontalIndicatorUnderlay(0L, 0x0f111111))
    }

    @Test fun overlayBoundsEncloseIndependentFusedAndPremultipliedIntegerModels() {
        val low = 0xff11273b.toInt()
        val high = 0xffa3c5e7.toInt()
        for (fill in listOf(0x0f0715f1, 0x7f99cc22, 0xff008080.toInt())) {
            val result = horizontalIndicatorUnderlay(textSurfaceRange(low, high), fill)
            for (alpha in 0..(fill ushr 24)) for (shift in listOf(0,8,16)) {
                val source = fill ushr shift and 255
                val min = (result ushr 32).toInt() ushr shift and 255
                val max = result.toInt() ushr shift and 255
                for (background in (low ushr shift and 255)..(high ushr shift and 255)) {
                    val numerator = source*alpha+background*(255-alpha)
                    val fused = (numerator+127)/255
                    val premultLow = source*alpha/255+background*(255-alpha)/255
                    val premultHigh = ((source*alpha+254)/255+(background*(255-alpha)+254)/255).coerceAtMost(255)
                    assertTrue(fused in min..max)
                    assertTrue(premultLow in min..max)
                    assertTrue(premultHigh in min..max)
                }
            }
        }
    }
}
