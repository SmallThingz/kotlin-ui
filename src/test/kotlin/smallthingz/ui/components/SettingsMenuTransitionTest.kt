package smallthingz.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMenuTransitionTest {
    @Test fun appearanceSuppliesTheCollapsedScaleToTheSharedTransition() {
        val original = NativeUiAppearance.Default
        val modal = original.modals
        val menu = modal.anchoredPanel.copy(motion = modal.anchoredPanel.motion.copy(collapsedScale = .65f))
        val appearance = original.copy(modals = modal.copy(anchoredPanel = menu))
        val motion = NativeMenuTransition(appearance.modals.anchoredPanel.motion)
        assertEquals(.65f, motion.scale, 0f)
        motion.retarget(true)
        motion.frame(100L, 0f)
        assertEquals(1f, motion.scale, 0f)
        motion.retarget(false)
        motion.frame(200L, 0f)
        assertEquals(.65f, motion.scale, 0f)
        assertEquals(0f, motion.alpha, 0f)
        assertFalse(motion.shouldExist)
    }

    @Test fun collapsedHasNoWindow() {
        val motion=NativeMenuTransition()
        assertFalse(motion.shouldExist)
        assertFalse(motion.isRunning)
    }

    @Test fun earlyAndMidEntryCloseRetainsPresentedPoseUntilExitSettles() {
        for (entryMillis in listOf(8L, 40L, 80L)) {
            val motion = NativeMenuTransition()
            motion.retarget(true)
            motion.frame(0L, 1f)
            val now = entryMillis * 1_000_000L
            motion.frame(now, 1f)
            assertTrue(motion.alpha > 0f)
            assertTrue(motion.isRunning)
            val alpha = motion.alpha
            val scale = motion.scale
            motion.retarget(false)
            assertTrue(motion.shouldExist)
            assertEquals(alpha, motion.alpha, 0f)
            assertEquals(scale, motion.scale, 0f)
            motion.frame(now, 1f)
            assertEquals(alpha, motion.alpha, .00001f)
            assertEquals(scale, motion.scale, .00001f)
            motion.frame(now + 16_000_000L, 1f)
            assertTrue(motion.shouldExist)
            assertTrue(motion.isRunning)
            settle(motion, now + 16_000_000L)
            assertFalse(motion.shouldExist)
            assertEquals(0f, motion.alpha, 0f)
            assertEquals(.8f, motion.scale, 0f)
        }
    }

    @Test fun closingBeforeFirstFrameSettlesWithNoResidualMotion() {
        val motion = NativeMenuTransition()
        motion.retarget(true)
        motion.retarget(false)
        settle(motion, 0L)
        assertFalse(motion.shouldExist)
        assertEquals(0f, motion.alpha, 0f)
        assertEquals(.8f, motion.scale, 0f)
    }

    @Test fun zeroScaleSettlesInterruptedEntryAndRepeatedReversal() {
        val motion = NativeMenuTransition()
        motion.retarget(true)
        motion.frame(0L, 1f)
        motion.frame(40_000_000L, 1f)
        motion.retarget(false)
        motion.frame(56_000_000L, 1f)
        motion.retarget(true)
        motion.frame(56_000_000L, 0f)
        assertTrue(motion.currentExpanded)
        assertFalse(motion.isRunning)
        assertEquals(1f, motion.alpha, 0f)
        motion.retarget(false)
        motion.frame(72_000_000L, 0f)
        assertFalse(motion.shouldExist)
        assertFalse(motion.isRunning)
        assertEquals(0f, motion.alpha, 0f)
    }

    @Test fun completedEntryRetainsWindowUntilExitSettlesEvenWithAnimationsOff() {
        val motion=NativeMenuTransition()
        motion.retarget(true)
        motion.frame(100L,0f)
        assertTrue(motion.currentExpanded)
        motion.retarget(false)
        assertTrue(motion.shouldExist)
        motion.frame(200L,0f)
        assertFalse(motion.shouldExist)
    }

    @Test fun reopeningExitKeepsPoseAndCanCloseAgainBeforeSettling() {
        val motion = NativeMenuTransition()
        motion.retarget(true)
        motion.frame(0L, 1f)
        motion.frame(40_000_000L, 1f)
        motion.retarget(false)
        motion.frame(72_000_000L, 1f)
        val scale = motion.scale
        val alpha = motion.alpha
        motion.retarget(true)
        assertEquals(scale, motion.scale, 0f)
        assertEquals(alpha, motion.alpha, 0f)
        assertTrue(motion.shouldExist)
        motion.frame(88_000_000L, 1f)
        motion.retarget(false)
        assertTrue(motion.shouldExist)
        settle(motion, 88_000_000L)
        assertFalse(motion.shouldExist)
        // Reusing a settled owner must start a new origin and reach the expanded endpoint.
        motion.retarget(true)
        settle(motion, 10_000_000_000L)
        assertTrue(motion.currentExpanded)
        assertEquals(1f, motion.alpha, 0f)
        assertEquals(1f, motion.scale, 0f)
    }

    private fun settle(motion: NativeMenuTransition, from: Long) {
        var time = from
        repeat(320) {
            if (!motion.isRunning) return
            motion.frame(time, 1f)
            time += 16_000_000L
        }
        assertFalse("Transition exceeded the bounded five-second test window", motion.isRunning)
    }
}
