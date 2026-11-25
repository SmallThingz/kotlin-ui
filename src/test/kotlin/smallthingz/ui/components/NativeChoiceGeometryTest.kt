package smallthingz.ui.components

import org.junit.Assert.*
import org.junit.Test

class NativeChoiceGeometryTest {
    @Test fun fractionalLabelAdvanceCannotLoseItsLastPixel() {
        assertEquals(105, width(84.01f))
        assertEquals(104, width(84f))
    }

    @Test fun stackedAndInlineIconsKeepTheirOwnSpaceAndGap() {
        assertEquals(105, width(84.01f))
        assertEquals(131, width(84.01f, above = false))
        assertEquals(129, width(84.01f, trailing = 18))
        assertEquals(155, width(84.01f, above = false, trailing = 18))
        assertEquals(70, wholeLabelChoiceCellWidth(48, 20, 12f, 50, 0, 6, true))
    }

    @Test fun absentTextOrIconsDoNotIntroducePhantomGapsOrLoseTouchMinimum() {
        assertEquals(48, width(0f, above = false))
        assertEquals(48, wholeLabelChoiceCellWidth(48, 20, 0f, 0, 18, 6, false))
        assertEquals(64, width(0f, above = false, trailing = 18))
        assertEquals(60, wholeLabelChoiceCellWidth(48, 20, 40f, 0, 0, 6, false))
    }

    @Test fun allDocNarrowViewportBudgetsReduceColumnsAtTheActualBoundary() {
        // Synthetic measured label, not an assertion about a particular device font.
        val minimum = width(84.01f)
        assertEquals(1, choiceColumnsThatFit(240 - 28, 4, minimum, 3))
        assertEquals(2, choiceColumnsThatFit(280 - 28, 4, minimum, 3))
        assertEquals(2, choiceColumnsThatFit(320 - 28, 4, minimum, 3))
        assertEquals(3, choiceColumnsThatFit(411 - 28, 4, minimum, 3))
        assertEquals(1, choiceColumnsThatFit(213, 4, minimum, 3))
        assertEquals(2, choiceColumnsThatFit(214, 4, minimum, 3))
        assertEquals(2, choiceColumnsThatFit(322, 4, minimum, 3))
        assertEquals(3, choiceColumnsThatFit(323, 4, minimum, 3))
    }

    @Test fun selectedTypographyGrowthCanReduceColumnsAndShrinkCanRestoreThem() {
        assertEquals(3, choiceColumnsThatFit(212, 4, width(48f), 3))
        assertEquals(2, choiceColumnsThatFit(212, 4, width(48.01f), 3))
        assertEquals(1, choiceColumnsThatFit(212, 4, width(84.01f), 3))
        assertEquals(3, choiceColumnsThatFit(212, 4, width(48f), 3))
    }

    @Test fun fittingColumnsAlwaysHaveRoomForTheirMinimumAndNeverExceedTheCap() {
        for (minimum in listOf(48, 68, 105, 131)) for (available in 0..800) {
            val columns = choiceColumnsThatFit(available, 4, minimum, 3)
            assertTrue(columns in 1..3)
            if (available >= minimum) assertTrue(columns * minimum + (columns - 1) * 4 <= available)
            if (columns < 3) assertTrue((columns + 1) * minimum + columns * 4 > available)
        }
    }

    @Test fun enormousMeasurementsCannotOverflowIntoASmallCellOrFalseFit() {
        assertEquals(Int.MAX_VALUE, wholeLabelChoiceCellWidth(48, 20, Float.MAX_VALUE,
            Int.MAX_VALUE, Int.MAX_VALUE, 6, false))
        assertEquals(1, choiceColumnsThatFit(Int.MAX_VALUE, 4, Int.MAX_VALUE, 3))
        assertEquals(1, choiceColumnsThatFit(0, 4, 105, 3))
    }

    private fun width(label: Float, above: Boolean = true, trailing: Int = 0) =
        wholeLabelChoiceCellWidth(48, 20, label, 20, trailing, 6, above)
}
