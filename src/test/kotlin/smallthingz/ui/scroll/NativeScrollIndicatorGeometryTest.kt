package smallthingz.ui.scroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import smallthingz.ui.components.NativeUiAppearance

class NativeScrollIndicatorGeometryTest {
    @Test fun emptyFittingAndUnmeasuredContentHaveNoThumb() {
        assertNull(thumb(range = 0, extent = 0))
        assertNull(thumb(range = 72, extent = 96))
        assertNull(thumb(range = 96, extent = 96))
        assertNull(thumb(range = 720, extent = 0))
        assertNull(thumb(range = 720, extent = 96, viewportHeight = 0))
    }

    @Test fun scrollProgressSpansTheViewportAtTopMiddleAndBottom() {
        // Metrics report 80 visible units out of 240 in a 96 px viewport.
        assertEquals(Thumb(116f, 0f, 118f, 32f), thumb(range = 240, extent = 80, offset = 0))
        assertEquals(Thumb(116f, 32f, 118f, 64f), thumb(range = 240, extent = 80, offset = 80))
        assertEquals(Thumb(116f, 64f, 118f, 96f), thumb(range = 240, extent = 80, offset = 160))
    }

    @Test fun outOfRangeOffsetsClampWithTheMinimumThumbHeight() {
        assertEquals(Thumb(116f, 0f, 118f, 24f), thumb(range = 960, offset = Int.MIN_VALUE))
        assertEquals(Thumb(116f, 72f, 118f, 96f), thumb(range = 960, offset = Int.MAX_VALUE))
    }

    @Test fun extremeRangeStillReachesTheMiddleAndBottom() {
        assertEquals(Thumb(116f, 36f, 118f, 60f),
            thumb(range = Int.MAX_VALUE, extent = 127, offset = 1_073_741_760))
        assertEquals(Thumb(116f, 72f, 118f, 96f),
            thumb(range = Int.MAX_VALUE, extent = 127, offset = 2_147_483_520))
    }

    @Test fun narrowShortViewportClampsTheThumbInsideItsBounds() {
        assertEquals(Thumb(0f, 0f, 1f, 15f),
            thumb(range = 384, extent = 15, offset = 100, viewportWidth = 3, viewportHeight = 15))
        assertNull(thumb(range = 384, extent = 15, viewportWidth = 2, viewportHeight = 15))
    }

    @Test fun appearanceCopiesRetainCustomGeometry() {
        val defaults = NativeUiAppearance.Default
        val custom = NativeScrollIndicatorStyle(widthDp = 3f, rightInsetDp = 5f, minThumbHeightDp = 30f)
        assertSame(NativeScrollIndicatorStyle.Default, defaults.scrollIndicator)
        assertSame(custom, NativeUiAppearance(scrollIndicator = custom).copy().scrollIndicator)
        assertSame(custom, defaults.copy(scrollIndicator = custom).copy().scrollIndicator)
    }

    @Test fun contentGutterClearsActualThumbGeometryAtFractionalDensities() {
        for (density in listOf(.75f, 1f, 1.125f, 1.33125f, 2.625f, 3.5f)) {
            for (style in listOf(NativeScrollIndicatorStyle.Default,
                NativeScrollIndicatorStyle(widthDp = 2.3f, rightInsetDp = 1.7f, minThumbHeightDp = 11f))) {
                val gutter = style.contentGutterPx(density)
                for (width in listOf(3, 80, 317)) for (offset in listOf(0, 125, 250)) {
                    withNativeScrollIndicatorThumb(width, 93, 343, 93, offset,
                        style.widthDp * density, style.rightInsetDp * density, style.minThumbHeightDp * density) { left, _, _, _ ->
                        org.junit.Assert.assertTrue("Content must end before the inward AA edge",
                            width - gutter <= left - 1f)
                        if (left > 1f) org.junit.Assert.assertTrue("Gutter is outward-rounded, not an arbitrary large inset",
                            left - (width - gutter) < 2.001f)
                    }
                }
            }
        }
        assertEquals(6, NativeScrollIndicatorStyle.Default.contentGutterPx(1.125f))
        assertEquals(12, NativeScrollIndicatorStyle.Default.contentGutterPx(2.625f))
        assertEquals(0, NativeScrollIndicatorStyle.Default.contentGutterPx(Float.NaN))
        assertEquals(0, NativeScrollIndicatorStyle(widthDp = 0f).contentGutterPx(1f))
    }

    private data class Thumb(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun thumb(
        range: Int,
        extent: Int = 96,
        offset: Int = 0,
        viewportWidth: Int = 120,
        viewportHeight: Int = 96,
    ): Thumb? {
        var result: Thumb? = null
        withNativeScrollIndicatorThumb(
            viewportWidth, viewportHeight, range, extent, offset,
            widthPx = 2f, rightInsetPx = 2f, minThumbHeightPx = 24f,
        ) { left, top, right, bottom ->
            assertNull("The geometry must emit a single thumb", result)
            result = Thumb(left, top, right, bottom)
        }
        return result
    }
}
