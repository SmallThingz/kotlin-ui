package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDropdownOverflowCueBoundsTest {
    @Test fun fullTargetsAreCenteredAndRoundedInwardFromTheRevealClip() {
        val top = NativeDropdownOverflowCueBounds(-1, 48)
        val bottom = NativeDropdownOverflowCueBounds(1, 48)
        assertTrue(top.update(151, 8.25f, 167.75f, bothVisible = true, visible = true))
        assertTrue(bottom.update(151, 8.25f, 167.75f, bothVisible = true, visible = true))
        assertEquals(51, top.left)
        assertEquals(99, top.right)
        assertEquals(top.left, bottom.left)
        assertEquals(top.right, bottom.right)
        assertEquals(9, top.top)
        assertEquals(57, top.bottom)
        assertEquals(119, bottom.top)
        assertEquals(167, bottom.bottom)
        assertFalse(top.contains(75f, 8.9f))
        assertFalse(bottom.contains(75f, 167f))
        assertFalse(top.contains(99f, 10f))
        assertTrue(bottom.contains(75f, 166.9f))
    }

    @Test fun narrowShortViewportKeepsDisjointCuesAndAnOptionLane() {
        val top = NativeDropdownOverflowCueBounds(-1, 48)
        val bottom = NativeDropdownOverflowCueBounds(1, 48)
        assertTrue(top.update(36, 8.4f, 52.7f, bothVisible = true, visible = true))
        assertTrue(bottom.update(36, 8.4f, 52.7f, bothVisible = true, visible = true))
        assertEquals(0, top.left)
        assertEquals(36, bottom.right)
        assertTrue(top.bottom < bottom.top)
        assertTrue(top.contains(18f, 12f))
        assertFalse(bottom.contains(18f, 12f))
        assertTrue(bottom.contains(18f, 49f))
        assertFalse(top.contains(18f, 49f))
        assertFalse(top.contains(18f, 30f))
        assertFalse(bottom.contains(18f, 30f))
    }

    @Test fun singleCueKeepsTheShortViewportCenterFreeAndRecoversItsFullTarget() {
        for (direction in intArrayOf(-1, 1)) {
            val cue = NativeDropdownOverflowCueBounds(direction, 48)
            assertTrue(cue.update(48, 0f, 43f, bothVisible = false, visible = true))
            assertFalse(cue.contains(24f, 21f))
            assertTrue(cue.update(48, 0f, 96f, bothVisible = false, visible = true))
            assertEquals(48, cue.bottom - cue.top)
        }
    }

    @Test fun invisibleOrEmptyTargetsRejectHitsAfterBeingPlaced() {
        val cue = NativeDropdownOverflowCueBounds(-1, 48)
        assertTrue(cue.update(48, 0f, 100f, bothVisible = false, visible = true))
        assertTrue(cue.contains(24f, 4f))
        assertFalse(cue.update(48, 0f, 100f, bothVisible = false, visible = false))
        assertFalse(cue.contains(24f, 4f))
        assertFalse(cue.update(0, 0f, 100f, bothVisible = false, visible = true))
        assertFalse(cue.update(48, 3.2f, 5.8f, bothVisible = true, visible = true))
        assertFalse(cue.update(48, 3f, Float.NaN, bothVisible = false, visible = true))
        assertFalse(cue.contains(24f, 4f))
    }
}
