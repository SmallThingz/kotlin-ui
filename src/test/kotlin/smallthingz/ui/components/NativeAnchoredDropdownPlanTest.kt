package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAnchoredDropdownPlanTest {
    @Test
    fun singleOptionAndNaturalOrder() {
        assertPlan(1, 0, 4, 4, listOf(0), listOf(0), 0, 0)
        assertPlan(3, 0, 4, 4, listOf(0, 1, 2), listOf(0, 1, 2), 0, 2)
        assertPlan(3, 1, 4, 4, listOf(0, 1, 2), listOf(0, 1, 2), 1, 1)
        assertPlan(3, 2, 4, 4, listOf(0, 1, 2), listOf(0, 1, 2), 2, 0)
    }

    @Test
    fun overflowWrapsOntoTheOppositeSideExactlyOnce() {
        assertPlan(3, 0, 1, 1, listOf(2, 0, 1), listOf(2, 0, 1), 1, 1)
        assertPlan(3, 1, 0, 2, listOf(1, 2, 0), listOf(1, 2, 0), 0, 2)
        assertPlan(3, 1, 2, 0, listOf(2, 0, 1), listOf(2, 0, 1), 2, 0)
        assertPlan(3, 2, 0, 2, listOf(2, 0, 1), listOf(2, 0, 1), 0, 2)
    }

    @Test
    fun limitedViewportsRetainAllOptionsInTheirScrollableOrder() {
        val natural = (0..8).toList()
        assertPlan(9, 4, 2, 1, natural, listOf(2, 3, 4, 5), 2, 1)
        assertPlan(9, 4, 0, 0, natural, listOf(4), 0, 0)
        assertPlan(9, 0, 3, 1, listOf(6, 7, 8, 0, 1, 2, 3, 4, 5), listOf(6, 7, 8, 0, 1), 3, 1)
        assertPlan(9, 8, 1, 3, listOf(3, 4, 5, 6, 7, 8, 0, 1, 2), listOf(7, 8, 0, 1, 2), 1, 3)
    }

    @Test
    fun mountedRowsCoverFractionalDragsWithinTheViewportBudget() {
        val plan = NativeAnchoredDropdownPlan(10_000, 7_500, 2, 3)
        for (position in listOf(0f, .25f, 13.5f, 7_500f, 9_998.75f, 9_999f)) {
            val first = plan.firstMounted(position)
            val last = plan.lastMounted(position)
            assertTrue(first >= 0 && last < plan.count)
            assertTrue(last - first + 1 <= 2 + 1 + 3 + 2 + 1)
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
    fun everyOptionAppearsExactlyOnceAndSelectionKeepsItsAnchor() {
        for (count in 1..20) for (selected in 0 until count) {
            for (spaceAbove in 0..12) for (spaceBelow in 0..12) {
                val plan = NativeAnchoredDropdownPlan(count, selected, spaceAbove, spaceBelow)
                val order = List(count, plan::optionAt)
                assertEquals((0 until count).toList(), order.sorted())
                assertTrue(order.zipWithNext().all { (a, b) -> (a + 1) % count == b })
                assertTrue(plan.above in 0..spaceAbove)
                assertTrue(plan.below in 0..spaceBelow)
                assertEquals(minOf(count, spaceAbove + 1 + spaceBelow), plan.visibleRows)
                assertTrue(plan.initialScrollRows in 0..count - plan.visibleRows)
                assertEquals(selected, order[plan.initialScrollRows + plan.above])
            }
        }
    }

    private fun assertPlan(
        count: Int,
        selected: Int,
        spaceAbove: Int,
        spaceBelow: Int,
        expectedOrder: List<Int>,
        expectedVisible: List<Int>,
        expectedAbove: Int,
        expectedBelow: Int,
    ) {
        val plan = NativeAnchoredDropdownPlan(count, selected, spaceAbove, spaceBelow)
        val order = List(count, plan::optionAt)
        assertEquals(expectedOrder, order)
        assertEquals(expectedAbove, plan.above)
        assertEquals(expectedBelow, plan.below)
        assertEquals(expectedOrder.first(), plan.firstOptionIndex)
        assertEquals(expectedOrder.indexOf(selected), plan.selectedPosition)
        assertEquals(expectedVisible, order.drop(plan.initialScrollRows).take(plan.visibleRows))
    }
}
