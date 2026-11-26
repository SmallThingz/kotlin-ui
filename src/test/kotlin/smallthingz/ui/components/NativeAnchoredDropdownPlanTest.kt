package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAnchoredDropdownPlanTest {
    @Test
    fun singleOptionAndSmallListsStayInOriginalOrder() {
        assertPlan(1, 0, 4, 4, listOf(0), 0, 0)
        assertPlan(3, 0, 4, 4, listOf(0, 1, 2), 0, 2)
        assertPlan(3, 1, 4, 4, listOf(0, 1, 2), 1, 1)
        assertPlan(3, 2, 4, 4, listOf(0, 1, 2), 2, 0)
    }

    @Test
    fun clippedOptionsNeverFillSpaceOnTheOtherSideOfTheAnchor() {
        assertPlan(3, 0, 1, 1, listOf(0, 1), 0, 1)
        assertPlan(3, 1, 0, 2, listOf(1, 2), 0, 1)
        assertPlan(3, 1, 2, 0, listOf(0, 1), 1, 0)
        assertPlan(3, 2, 1, 1, listOf(1, 2), 1, 0)
        assertPlan(3, 0, 2, 0, listOf(0), 0, 0)
        assertPlan(3, 2, 0, 2, listOf(2), 0, 0)
    }

    @Test
    fun limitedViewportsRetainAllOptionsInTheirScrollableOrder() {
        assertPlan(9, 4, 2, 1, listOf(2, 3, 4, 5), 2, 1)
        assertPlan(9, 4, 0, 0, listOf(4), 0, 0)
        assertPlan(9, 0, 3, 1, listOf(0, 1), 0, 1)
        assertPlan(9, 8, 1, 3, listOf(7, 8), 1, 0)
    }

    @Test
    fun mountedRowsCoverFractionalDragsWithinTheViewportBudget() {
        val plan = NativeAnchoredDropdownPlan(10_000, 7_500, 2, 3)
        assertEquals(7_500, plan.selectedPosition)
        assertEquals(7_500, plan.optionAt(plan.selectedPosition))
        assertEquals(0, plan.firstMounted(0f))
        assertEquals(4, plan.lastMounted(0f))
        assertEquals(9_996, plan.firstMounted(9_999f))
        assertEquals(9_999, plan.lastMounted(9_999f))
        for (position in listOf(0f, .25f, 13.5f, 7_500f, 9_998.75f, 9_999f)) {
            val first = plan.firstMounted(position)
            val last = plan.lastMounted(position)
            assertTrue(first in 0..last && last < plan.count)
            assertTrue(last - first + 1 <= 2 + 1 + 3 + 2 + 1)
            assertEquals((first..last).toList(), (first..last).map(plan::optionAt))
            for (row in 0 until plan.count) {
                // A row overlaps [position - 2, position + 1 + 3) by any positive amount.
                if (row + 1 > position - 2 && row < position + 4) assertTrue(row in first..last)
            }
        }
        val oneSlot = NativeAnchoredDropdownPlan(4, 2, 0, 0)
        assertEquals(1, oneSlot.firstMounted(2f))
        assertEquals(3, oneSlot.lastMounted(2f))
    }

    @Test
    fun everyPositionMapsMonotonicallyAndSelectionKeepsItsAnchor() {
        for (count in 1..20) for (selected in 0 until count) {
            for (spaceAbove in 0..12) for (spaceBelow in 0..12) {
                val plan = NativeAnchoredDropdownPlan(count, selected, spaceAbove, spaceBelow)
                for (position in 0 until count) assertEquals(position, plan.optionAt(position))
                assertEquals(selected, plan.selectedPosition)
                assertTrue(plan.initialScrollRows in 0..count - plan.visibleRows)
                assertEquals(maxOf(0, selected - spaceAbove), plan.initialScrollRows)
                assertEquals(minOf(count - 1, selected + spaceBelow), plan.initialScrollRows + plan.visibleRows - 1)
                assertEquals(selected, plan.optionAt(plan.initialScrollRows + plan.above))
            }
        }
    }

    private fun assertPlan(
        count: Int,
        selected: Int,
        spaceAbove: Int,
        spaceBelow: Int,
        expectedVisible: List<Int>,
        expectedAbove: Int,
        expectedBelow: Int,
    ) {
        val plan = NativeAnchoredDropdownPlan(count, selected, spaceAbove, spaceBelow)
        assertEquals(expectedAbove, plan.above)
        assertEquals(expectedBelow, plan.below)
        assertEquals(selected, plan.selectedPosition)
        val visiblePositions = plan.initialScrollRows until plan.initialScrollRows + plan.visibleRows
        assertEquals(expectedVisible, visiblePositions.map(plan::optionAt))
    }
}
