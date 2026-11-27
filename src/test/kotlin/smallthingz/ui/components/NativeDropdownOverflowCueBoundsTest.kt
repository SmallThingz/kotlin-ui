package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDropdownOverflowCueBoundsTest {
    @Test fun fullTargetsAreCenteredAndRoundedInwardFromTheRevealClip() {
        val top = NativeDropdownOverflowCueBounds(-1, 48)
        val bottom = NativeDropdownOverflowCueBounds(1, 48)
        assertTrue(top.update(151, 8.25f, 167.75f, slotCenter = 88f, slotHeight = 72, bothVisible = true, visible = true))
        assertTrue(bottom.update(151, 8.25f, 167.75f, slotCenter = 88f, slotHeight = 72, bothVisible = true, visible = true))
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
        assertTrue(top.update(36, 8.4f, 52.7f, slotCenter = 30.5f, slotHeight = 44, bothVisible = true, visible = true))
        assertTrue(bottom.update(36, 8.4f, 52.7f, slotCenter = 30.5f, slotHeight = 44, bothVisible = true, visible = true))
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
            assertTrue(cue.update(48, 0f, 43f, slotCenter = 21.5f, slotHeight = 43, bothVisible = false, visible = true))
            assertFalse(cue.contains(24f, 21f))
            assertTrue(cue.update(48, 0f, 96f, slotCenter = 48f, slotHeight = 48, bothVisible = false, visible = true))
            assertFalse(cue.contains(24f, 48f))
            assertEquals(40, cue.bottom - cue.top)
            assertTrue(cue.update(48, 0f, 144f, slotCenter = 72f, slotHeight = 48, bothVisible = false, visible = true))
            assertEquals(48, cue.bottom - cue.top)
        }
    }

    @Test fun asymmetricEdgeSlotsKeepTheirCenterLaneClearWhileArrowsRemainUsable() {
        for (direction in intArrayOf(-1, 1)) for (both in listOf(false, true)) for (fraction in listOf(0f, .25f)) {
            val center = if (direction < 0) 36f + fraction else 180f - fraction
            val cue = NativeDropdownOverflowCueBounds(direction, 48)
            assertTrue(cue.update(150, 1f + fraction, 215f - fraction, center, 72, both, visible = true))
            for (y in listOf(center - 12f, center, center + 11.9f)) {
                assertFalse("Fixed option lane is not a cue", cue.contains(75f, y))
            }
            assertTrue(cue.contains(75f, (cue.top + cue.bottom) / 2f))
            assertEquals(if (fraction == 0f) 23 else 22, cue.bottom - cue.top)
            assertTrue(cue.top >= 1 && cue.bottom <= 215)
        }
    }

    @Test fun invisibleOrEmptyTargetsRejectHitsAfterBeingPlaced() {
        val cue = NativeDropdownOverflowCueBounds(-1, 48)
        assertTrue(cue.update(48, 0f, 100f, slotCenter = 50f, slotHeight = 48, bothVisible = false, visible = true))
        assertTrue(cue.contains(24f, 4f))
        assertFalse(cue.update(48, 0f, 100f, slotCenter = 50f, slotHeight = 48, bothVisible = false, visible = false))
        assertFalse(cue.contains(24f, 4f))
        assertFalse(cue.update(0, 0f, 100f, slotCenter = 50f, slotHeight = 48, bothVisible = false, visible = true))
        assertFalse(cue.update(48, 3.2f, 5.8f, slotCenter = 4.5f, slotHeight = 2, bothVisible = true, visible = true))
        assertFalse(cue.update(48, 3f, Float.NaN, slotCenter = 50f, slotHeight = 48, bothVisible = false, visible = true))
        assertFalse(cue.contains(24f, 4f))
    }
}
