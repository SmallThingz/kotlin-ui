package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeResponsiveChoiceLayoutTest {
    @Test fun widestFieldSetsTheEqualColumnBreakpoint() {
        assertEquals(NativeResponsiveChoiceLayout.COMBINED, responsiveChoiceLayout(455, listOf(224, 202), 8))
        assertEquals(NativeResponsiveChoiceLayout.PAIRED, responsiveChoiceLayout(456, listOf(224, 202), 8))
        assertEquals(NativeResponsiveChoiceLayout.COMBINED, responsiveChoiceLayout(391, listOf(192, 192), 8))
        assertEquals(NativeResponsiveChoiceLayout.PAIRED, responsiveChoiceLayout(392, listOf(192, 192), 8))
    }

    @Test fun absentAndSingleFieldsDoNotForceASummary() {
        assertEquals(NativeResponsiveChoiceLayout.HIDDEN, responsiveChoiceLayout(0, emptyList(), 8))
        assertEquals(NativeResponsiveChoiceLayout.SINGLE, responsiveChoiceLayout(0, listOf(224), 8))
    }

    @Test fun multipleFieldsAndLargeGapsCannotOverflowIntoPairedMode() {
        assertEquals(NativeResponsiveChoiceLayout.COMBINED, responsiveChoiceLayout(687, listOf(224, 200, 210), 8))
        assertEquals(NativeResponsiveChoiceLayout.PAIRED, responsiveChoiceLayout(688, listOf(224, 200, 210), 8))
        assertEquals(NativeResponsiveChoiceLayout.COMBINED,
            responsiveChoiceLayout(Int.MAX_VALUE, listOf(192, 192, 192), Int.MAX_VALUE))
    }
}
