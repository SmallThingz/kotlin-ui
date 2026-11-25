package smallthingz.ui.zfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ZfmSearchLayoutTest {
    private fun layout(
        width: Int = 360, query: Int = 100, hint: Int = 100,
        actions: Int = 48, status: Int = 80, inline: Boolean = true,
    ) = ZfmSearchLayout.measure(
        availableWidth = width, queryTextWidth = query, hintTextWidth = hint,
        editorPaddingWidth = 24, cursorWidth = 2, closeWidth = 48,
        actionsWidth = actions, inputPaddingWidth = 16, statusWidth = status,
        allowInlineStatus = inline,
    )

    @Test fun phoneQueryUsesAllSpaceAfterCloseAndReservedClear() {
        val result = layout()
        assertEquals(248, result.preferredEditorWidth)
        assertEquals(360, result.inputWidth)
        assertFalse(result.stackActions)
        assertFalse(result.inlineStatus)
    }

    @Test fun typingClearingAndLongHintsCannotMoveControlsOrShrinkEditor() {
        val empty = layout(query = 0)
        for (query in listOf(1, 100, 248, 249, 10000, Int.MAX_VALUE)) {
            assertEquals(empty, layout(query = query))
        }
        assertEquals(empty, layout(query = 0, hint = 10000))
        // The view reserves Clear even while GONE (covered by view measurement tests).
        assertEquals(empty, layout(query = 0, hint = 0))
    }

    @Test fun statusNeverBorrowsQueryWidthEvenOnWideWindows() {
        for (width in listOf(320, 360, 600, 1200)) {
            val baseline = layout(width = width, status = 0, inline = false)
            for (status in listOf(0, 80, width, Int.MAX_VALUE)) {
                assertEquals(baseline, layout(width = width, status = status, inline = true))
            }
        }
    }

    @Test fun scaledEditorMetricsDoNotChangeRowPlacement() {
        assertEquals(layout(), ZfmSearchLayout.measure(
            availableWidth = 360, queryTextWidth = 200, hintTextWidth = 200,
            editorPaddingWidth = 48, cursorWidth = 4, closeWidth = 48,
            actionsWidth = 48, inputPaddingWidth = 16, minimumEditorWidth = 0,
            statusWidth = 500, allowInlineStatus = true,
        ))
    }

    @Test fun resizingOnlyChangesAvailableEditorSpace() {
        for (width in listOf(112, 113, 320, 360, 600)) {
            val result = layout(width = width)
            assertEquals(width - 112, result.preferredEditorWidth)
            assertEquals(width, result.inputWidth)
            assertFalse(result.stackActions)
            assertFalse(result.inlineStatus)
        }
        assertEquals(296, layout(actions = 0).preferredEditorWidth)
    }

    @Test fun zeroAndUndersizedWindowsNeverProduceNegativeBudgets() {
        for (width in listOf(0, 32, 111)) {
            val result = layout(width = width)
            assertEquals(0, result.preferredEditorWidth)
            assertEquals(width, result.inputWidth)
            assertFalse(result.stackActions)
            assertFalse(result.inlineStatus)
        }
    }

    @Test fun narrowViewportRetainsEditorAndScrollableActionTargets() {
        for (width in listOf(0, 80, 112, 159, 160, 360)) {
            val result = ZfmSearchLayout.measure(width, 0, 0, 24, 2, 48, 48,
                inputPaddingWidth = 16, minimumEditorWidth = 48)
            assertEquals(width, result.inputWidth)
            assertEquals(maxOf(width, 160), result.contentWidth)
            assertEquals(maxOf(width - 112, 48), result.preferredEditorWidth)
            assertFalse(result.stackActions)
            assertFalse(result.inlineStatus)
        }
    }

    @Test fun hugeControlExtentsCannotOverflowIntoPositiveEditorSpace() {
        val result = ZfmSearchLayout.measure(
            availableWidth = Int.MAX_VALUE, queryTextWidth = Int.MAX_VALUE,
            hintTextWidth = Int.MAX_VALUE, editorPaddingWidth = Int.MAX_VALUE,
            cursorWidth = Int.MAX_VALUE, closeWidth = Int.MAX_VALUE,
            actionsWidth = Int.MAX_VALUE, inputPaddingWidth = Int.MAX_VALUE,
        )
        assertEquals(0, result.preferredEditorWidth)
        assertEquals(Int.MAX_VALUE, result.inputWidth)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeMeasurementsAreRejected() {
        layout(query = -1)
    }
}
