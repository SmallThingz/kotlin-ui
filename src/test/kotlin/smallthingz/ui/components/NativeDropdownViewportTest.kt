package smallthingz.ui.components

import org.junit.Assert.*
import org.junit.Test

class NativeDropdownViewportTest {
    @Test fun safeWindowEdgesAreIntersectedWithAlreadyInsetOrResizedRoots() {
        // Window [0,1080], status 24, navigation 48. Full edge-to-edge host.
        assertEquals(24, nativeDropdownLocalEdge(24, 0, 1080))
        assertEquals(1032, nativeDropdownLocalEdge(1032, 0, 1080))
        // Same window with a host already fitted inside the system bars.
        assertEquals(0, nativeDropdownLocalEdge(24, 24, 1008))
        assertEquals(1008, nativeDropdownLocalEdge(1032, 24, 1008))
        // IME top is 499; decor was already resized to end there. Never subtract 581 again.
        assertEquals(475, nativeDropdownLocalEdge(499, 24, 475))
        assertEquals(499, nativeDropdownLocalEdge(499, 0, 1080))
        // Panned/nonzero-screen-origin host and hidden bars in fullscreen.
        assertEquals(299, nativeDropdownLocalEdge(499, 200, 500))
        assertEquals(0, nativeDropdownLocalEdge(200, 200, 800))
        assertEquals(800, nativeDropdownLocalEdge(1000, 200, 800))
    }

    @Test fun longListReachesExactPixelEdgesIncludingFractionalRows() {
        val geometry = NativeDropdownViewport(24, 1032, 451, 80)
        fun assertViewport(selected: Int, top: Float, bottom: Float, above: Boolean, below: Boolean) {
            val plan = NativeAnchoredDropdownPlan(40, selected, geometry.slotsAbove(40), geometry.slotsBelow(40))
            val position = plan.selectedPosition.toFloat()
            assertEquals(selected, plan.optionAt(plan.selectedPosition))
            assertEquals(top, geometry.revealTop(position, 1f), 0f)
            assertEquals(bottom, geometry.revealBottom(position, 39, 1f), 0f)
            assertEquals(above, geometry.overflowsAbove(position))
            assertEquals(below, geometry.overflowsBelow(position, 39))
            assertEquals(451f, geometry.revealTop(position, 0f), 0f)
            assertEquals(531f, geometry.revealBottom(position, 39, 0f), 0f)
        }

        assertViewport(0, top = 451f, bottom = 1032f, above = false, below = true)
        assertViewport(20, top = 24f, bottom = 1032f, above = true, below = true)
        assertViewport(39, top = 24f, bottom = 531f, above = true, below = false)
    }

    @Test fun shortListsStayCompactAndNeverInventOverflow() {
        val geometry = NativeDropdownViewport(24, 1032, 451, 80)
        for (selected in 0..2) {
            val plan = NativeAnchoredDropdownPlan(3, selected, geometry.slotsAbove(3), geometry.slotsBelow(3))
            val position = plan.selectedPosition.toFloat()
            assertEquals(240f, geometry.revealBottom(position, 2, 1f) - geometry.revealTop(position, 1f), 0f)
            assertFalse(geometry.overflowsAbove(position))
            assertFalse(geometry.overflowsBelow(position, 2))
        }
    }

    @Test fun cuesFollowClippedContentAndDisappearAtFiniteEnds() {
        val geometry = NativeDropdownViewport(0, 500, 200, 100)
        assertFalse(geometry.overflowsAbove(0f))
        assertTrue(geometry.overflowsBelow(0f, 9))
        assertFalse(geometry.overflowsAbove(2f)) // exact fit
        assertTrue(geometry.overflowsAbove(2.25f)) // partial row beyond edge
        assertFalse(geometry.overflowsBelow(7f, 9))
        assertTrue(geometry.overflowsAbove(9f))
        assertFalse(geometry.overflowsBelow(9f, 9))
        assertEquals(200f, geometry.revealTop(0f, 1f), 0f)
        assertEquals(300f, geometry.revealBottom(9f, 9, 1f), 0f)
    }

    @Test fun tinyViewportKeepsSelectionAndEveryOptionReachable() {
        val geometry = NativeDropdownViewport(15, 125, 20, 100)
        val plan = NativeAnchoredDropdownPlan(20, 10, geometry.slotsAbove(20), geometry.slotsBelow(20))
        assertEquals((0..19).toList(), List(20, plan::optionAt).sorted())
        assertEquals(15f, geometry.revealTop(plan.selectedPosition.toFloat(), 1f), 0f)
        assertEquals(125f, geometry.revealBottom(plan.selectedPosition.toFloat(), 19, 1f), 0f)
    }
}
