package smallthingz.ui.zfm

import org.junit.Assert.*
import org.junit.Test

class ZfmChooserAnchorTest {
    @Test fun bottomFirstReversesPhysicalRootsWithoutChangingLogicalInput() {
        val logical = listOf("Internal", "Root", "Mounted")
        assertEquals(listOf("Mounted", "Root", "Internal"), ZfmChooserAnchors.physicalOrder(logical, true))
        assertEquals(listOf("Internal", "Root", "Mounted"), ZfmChooserAnchors.physicalOrder(logical, false))
        assertEquals("Internal", logical.first())
    }

    @Test fun warmToFreshPreservesReadingEdgeKeyAndItsPixelOffset() {
        val warm = listOf(ZfmChooserRootBounds("Root", 100, 160), ZfmChooserRootBounds("Internal", 160, 240))
        val anchor = ZfmChooserAnchors.capture(warm, 120, 100, true)
        val fresh = listOf(ZfmChooserRootBounds("Mounted", 100, 170),
            ZfmChooserRootBounds("Root", 170, 230), ZfmChooserRootBounds("Internal", 230, 310))
        assertEquals(190, ZfmChooserAnchors.restore(anchor, fresh, 210))
        assertEquals(40, fresh.last().top - ZfmChooserAnchors.restore(anchor, fresh, 210))
    }

    @Test fun removedPreferredKeyFallsBackToAnotherPreviouslyVisibleSurvivor() {
        val warm = listOf(ZfmChooserRootBounds("Root", 100, 160), ZfmChooserRootBounds("Internal", 160, 240))
        val anchor = ZfmChooserAnchors.capture(warm, 120, 100, true)
        assertEquals(180, ZfmChooserAnchors.restore(anchor, listOf(ZfmChooserRootBounds("Root", 160, 220)), 300))
    }

    @Test fun noVisibleSurvivorClampsExistingPixelPositionRatherThanJumpingToEdge() {
        val anchor = ZfmChooserAnchors.capture(listOf(ZfmChooserRootBounds("old", 100, 200)), 125, 50, false)
        assertEquals(125, ZfmChooserAnchors.restore(anchor, listOf(ZfmChooserRootBounds("new", 0, 500)), 450))
        assertEquals(40, ZfmChooserAnchors.restore(anchor, emptyList(), 40))
        assertEquals(0, ZfmChooserAnchors.restore(anchor, emptyList(), 0))
    }

    @Test fun topDownPreservesPartiallyVisibleTopKeyAcrossManagementHeightChange() {
        val anchor = ZfmChooserAnchors.capture(listOf(ZfmChooserRootBounds("first", 80, 160),
            ZfmChooserRootBounds("second", 160, 240)), 100, 120, false)
        assertEquals(150, ZfmChooserAnchors.restore(anchor, listOf(ZfmChooserRootBounds("first", 130, 210),
            ZfmChooserRootBounds("second", 210, 290)), 500))
    }

    @Test fun emptyAndZeroHeightRowsCannotBecomeAnchorsAndLargeOffsetsDoNotOverflow() {
        assertTrue(ZfmChooserAnchors.capture(listOf(ZfmChooserRootBounds("zero", 100, 100)), 100, 50, true).visible.isEmpty())
        assertTrue(ZfmChooserAnchors.capture(listOf(ZfmChooserRootBounds("row", 0, 50)), 0, 0, true).visible.isEmpty())
        val anchor = ZfmChooserAnchor(listOf(ZfmChooserVisibleRoot("row", -200L)), 0)
        assertEquals(Int.MAX_VALUE, ZfmChooserAnchors.restore(anchor,
            listOf(ZfmChooserRootBounds("row", Int.MAX_VALUE - 1, Int.MAX_VALUE)), Int.MAX_VALUE))
    }

    @Test fun initialPlacementIsConsumedOnceAndInputInvalidatesCapturedTickets() {
        val gate = ZfmChooserPlacementGate(7)
        gate.attach()
        assertTrue(gate.commitInitial(gate.ticket()))
        assertFalse(gate.commitInitial(gate.ticket()))
        val pending = gate.ticket()
        gate.input()
        assertFalse(gate.accepts(pending))
        assertFalse(gate.commitInitial(gate.ticket()))
    }

    @Test fun inputBeforeFirstLayoutWinsAndDetachOrDismissCannotRearm() {
        val gate = ZfmChooserPlacementGate(8)
        gate.attach()
        gate.input()
        assertFalse(gate.commitInitial(gate.ticket()))
        val old = gate.ticket()
        gate.detach(); gate.attach()
        assertFalse(gate.accepts(old))
        assertFalse(gate.commitInitial(gate.ticket()))
        gate.close(); gate.attach()
        assertNull(gate.ticket())
    }

    @Test fun windowLossRetiresInitialAndDifferentOpeningsCannotShareTickets() {
        val first = ZfmChooserPlacementGate(1)
        val second = ZfmChooserPlacementGate(2)
        first.attach(); second.attach()
        assertFalse(second.accepts(first.ticket()))
        val pending = first.ticket()
        first.windowChanged(false); first.windowChanged(true)
        assertFalse(first.accepts(pending))
        assertFalse(first.commitInitial(first.ticket()))
    }

    @Test fun firstCommitWithStaleOrAbsentTicketCannotBeRetriedLater() {
        val stale = ZfmChooserPlacementGate(3)
        stale.attach()
        val ticket = stale.ticket()
        stale.windowChanged(true)
        assertFalse(stale.commitInitial(ticket))
        assertFalse(stale.commitInitial(stale.ticket()))
        val absent = ZfmChooserPlacementGate(4)
        absent.attach()
        assertFalse(absent.commitInitial(null))
        assertFalse(absent.commitInitial(absent.ticket()))
    }

    @Test fun visibilityUsesPhysicalOrderAndExcludesRowsOnlyTouchingViewportEdges() {
        val rows = listOf(ZfmChooserRootBounds("last", 200, 250), ZfmChooserRootBounds("bottom", 150, 200),
            ZfmChooserRootBounds("before", 50, 100), ZfmChooserRootBounds("top", 100, 150))
        assertEquals(listOf("top", "bottom"), ZfmChooserAnchors.capture(rows, 100, 100, false).visible.map { it.key })
        assertEquals(listOf("bottom", "top"), ZfmChooserAnchors.capture(rows, 100, 100, true).visible.map { it.key })
    }

    @Test fun shorterManagementAndNegativeRangeClampToZero() {
        val anchor = ZfmChooserAnchor(listOf(ZfmChooserVisibleRoot("root", 50L)), 10)
        val rows = listOf(ZfmChooserRootBounds("root", 20, 100))
        assertEquals(0, ZfmChooserAnchors.restore(anchor, rows, 100))
        assertEquals(0, ZfmChooserAnchors.restore(anchor, rows, -1))
    }
}
