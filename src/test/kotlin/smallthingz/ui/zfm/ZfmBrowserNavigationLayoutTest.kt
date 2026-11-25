package smallthingz.ui.zfm

import org.junit.Assert.*
import org.junit.Test

class ZfmBrowserNavigationLayoutTest {
    @Test fun phoneKeepsLocationAndThreeActionsOnOneRow() {
        val fit = ZfmBrowserNavigationLayout.measure(320, 48, 48, 48, 140)
        assertFalse(fit.stacked)
        assertEquals(320, fit.rowWidth)
    }

    @Test fun largeTitleMovesAboveActionsWithoutShrinkingTheirTargets() {
        val fit = ZfmBrowserNavigationLayout.measure(320, 48, 48, 48, 220)
        assertTrue(fit.stacked)
        assertEquals(320, fit.rowWidth)
        val narrow = ZfmBrowserNavigationLayout.measure(120, 48, 48, 48, 140)
        assertTrue(narrow.stacked)
        assertEquals(144, narrow.rowWidth)
    }

    @Test fun hugeMeasurementsCannotWrapIntoAFalseFit() {
        val fit = ZfmBrowserNavigationLayout.measure(320, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 140)
        assertTrue(fit.stacked)
        assertEquals(Int.MAX_VALUE, fit.rowWidth)
    }
}
