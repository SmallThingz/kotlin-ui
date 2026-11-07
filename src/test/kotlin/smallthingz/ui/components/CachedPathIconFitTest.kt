package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CachedPathIconFitTest {
    @Test
    fun fractionalFitKeepsFloatViewportAndCeilsOnlyTheCache() {
        // Released 25 dp Icon at densityDpi 280: measured width is 44 px.
        // The original integer-only proposal incorrectly used viewport/cache 44.
        val fit = fitCachedPathIcon(density = 280 / 160f, width = 44, height = 44)
        assertEquals(44.000003814697266f.toBits(), fit.drawWidth.toBits())
        assertEquals(fit.drawWidth.toBits(), fit.drawHeight.toBits())
        assertEquals(45, fit.cacheWidth)
        assertEquals(45, fit.cacheHeight)
        assertEquals(0, fit.left)
        assertEquals(0, fit.top)
    }

    @Test
    fun centerRoundsTheRemainingHalfPixelUpWithoutStretchingTheGlyph() {
        val fit = fitCachedPathIcon(density = 1f, width = 24, height = 25)
        assertEquals(24f.toBits(), fit.drawWidth.toBits())
        assertEquals(24f.toBits(), fit.drawHeight.toBits())
        assertEquals(24, fit.cacheWidth)
        assertEquals(24, fit.cacheHeight)
        assertEquals(0, fit.left)
        assertEquals(1, fit.top)
    }

    @Test
    fun emptyBoundsAllocateNoMask() {
        assertEquals(CachedPathIconFit(0f, 0f, 0, 0, 0, 0), fitCachedPathIcon(1f, 0, 24))
    }
}
