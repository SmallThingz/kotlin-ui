package smallthingz.ui.zfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZfmSearchLayoutTest {
    private fun layout(
        width: Int = 400, query: Int = 100, hint: Int = 100,
        actions: Int = 144, status: Int = 80, inline: Boolean = true,
    ) = ZfmSearchLayout.measure(
        availableWidth = width, queryTextWidth = query, hintTextWidth = hint,
        editorPaddingWidth = 24, cursorWidth = 2, closeWidth = 48,
        actionsWidth = actions, inputPaddingWidth = 16, statusWidth = status,
        allowInlineStatus = inline,
    )

    @Test fun queryGrowthMovesStatusThenActionsWithoutReducingTheTextBudget() {
        val short = layout(width = 500)
        assertTrue(short.inlineStatus)
        assertFalse(short.stackActions)
        assertEquals(126, short.preferredEditorWidth)
        assertEquals(420, short.inputWidth)

        val medium = layout(width = 500, query = 220)
        assertFalse(medium.inlineStatus)
        assertFalse(medium.stackActions)
        assertEquals(246, medium.preferredEditorWidth)

        val long = layout(width = 500, query = 400)
        assertFalse(long.inlineStatus)
        assertTrue(long.stackActions)
        assertEquals(426, long.preferredEditorWidth)
    }

    @Test fun doubledMeasuredTextReflowsWithoutAssumingLinearAndroidFontScaling() {
        assertFalse(layout(query = 100, hint = 100).stackActions)
        val large = layout(query = 200, hint = 200)
        assertTrue(large.stackActions)
        assertFalse(large.inlineStatus)
        assertEquals(226, large.preferredEditorWidth)
    }

    @Test fun overflowingQueryIsCappedBeforeActionsOrStatusTakeSpace() {
        val long = layout(query = 10000)
        assertEquals(336, long.preferredEditorWidth)
        assertEquals(400, long.inputWidth)
        assertTrue(long.stackActions)
        assertFalse(long.inlineStatus)
    }

    @Test fun emptyQueryStillBudgetsForTheHintAndCursor() {
        assertEquals(126, layout(query = 0).preferredEditorWidth)
        assertEquals(206, layout(query = 20, hint = 180).preferredEditorWidth)
    }

    @Test fun exactFitSharesStatusAndOnePixelLessMovesOnlyStatus() {
        val exact = layout(width = 414)
        assertTrue(exact.inlineStatus)
        assertFalse(exact.stackActions)
        assertEquals(334, exact.inputWidth)
        val smaller = layout(width = 413)
        assertFalse(smaller.inlineStatus)
        assertFalse(smaller.stackActions)
        assertEquals(exact.preferredEditorWidth, smaller.preferredEditorWidth)
    }

    @Test fun exactActionFitStaysInRowAndOnePixelLessStacks() {
        assertFalse(layout(width = 334).stackActions)
        assertTrue(layout(width = 333).stackActions)
    }

    @Test fun clearActionVisibilityReflowsUsingTheSameQueryBudget() {
        val clearHidden = layout(width = 380, query = 150, actions = 96)
        val clearVisible = layout(width = 380, query = 150, actions = 144)
        assertFalse(clearHidden.stackActions)
        assertTrue(clearVisible.stackActions)
        assertEquals(clearHidden.preferredEditorWidth, clearVisible.preferredEditorWidth)
        assertEquals(clearHidden, layout(width = 380, query = 150, actions = 96))
    }

    @Test fun statusMustBeExplicitlyEligibleAndDoesNotChangeEditorBudget() {
        val enabled = layout(width = 500)
        val disabled = layout(width = 500, inline = false)
        assertFalse(disabled.inlineStatus)
        assertEquals(500, disabled.inputWidth)
        assertEquals(enabled.preferredEditorWidth, disabled.preferredEditorWidth)
    }

    @Test fun zeroAndUndersizedWindowsNeverProduceNegativeEditorWidths() {
        assertEquals(0, layout(width = 0).preferredEditorWidth)
        assertEquals(0, layout(width = 32).preferredEditorWidth)
        assertFalse(layout(width = 0, actions = 0).stackActions)
        assertFalse(layout(width = 0).inlineStatus)
    }

    @Test fun minimumWidthIsAlsoCappedAndHugeExtentsCannotOverflowFitChecks() {
        val result = ZfmSearchLayout.measure(
            availableWidth = 300, queryTextWidth = Int.MAX_VALUE,
            hintTextWidth = 0, editorPaddingWidth = Int.MAX_VALUE,
            cursorWidth = Int.MAX_VALUE, closeWidth = 48, actionsWidth = Int.MAX_VALUE,
            minimumEditorWidth = Int.MAX_VALUE, statusWidth = Int.MAX_VALUE,
            allowInlineStatus = true,
        )
        assertEquals(252, result.preferredEditorWidth)
        assertTrue(result.stackActions)
        assertFalse(result.inlineStatus)
        assertEquals(300, result.inputWidth)
        assertEquals(180, ZfmSearchLayout.measure(300, 1, 1, 0, 0, 48, 48,
            minimumEditorWidth = 180).preferredEditorWidth)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeMeasurementsAreRejected() {
        layout(query = -1)
    }
}
