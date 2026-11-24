package smallthingz.ui.reverb

import org.junit.Assert.*
import org.junit.Test

class ReverbFadeMotionTest {
    @Test fun reverseAndReopenKeepThePresentedOpacityAndSettleWithoutIdleWork() {
        val motion = ReverbFadeMotion()
        motion.target(true, 1f)
        motion.frame(0L, 1f)
        motion.frame(80_000_000L, 1f)
        val opening = motion.alpha
        assertTrue(opening > 0f && opening < 1f)
        motion.target(false, 1f)
        assertEquals(opening, motion.alpha, 0f)
        motion.frame(120_000_000L, 1f)
        val closing = motion.alpha
        assertTrue(closing < opening)
        motion.target(true, 1f)
        assertEquals(closing, motion.alpha, 0f)
        motion.frame(400_000_000L, 1f)
        assertEquals(1f, motion.alpha, 0f)
        assertFalse(motion.running)
        motion.frame(900_000_000L, 1f)
        assertEquals(1f, motion.alpha, 0f)
        assertFalse(motion.running)
    }

    @Test fun zeroScaleOnsetAndReversalSettleWithoutRequestingAFrame() {
        val motion = ReverbFadeMotion()
        motion.target(true, 0f)
        assertEquals(1f, motion.alpha, 0f)
        assertFalse(motion.running)
        motion.target(false, 1f)
        motion.frame(0L, 1f)
        motion.frame(50_000_000L, 1f)
        motion.target(true, 0f)
        assertEquals(1f, motion.alpha, 0f)
        assertFalse(motion.running)
        motion.target(false, 0f)
        assertEquals(0f, motion.alpha, 0f)
        assertFalse(motion.running)
    }

    @Test fun disposalSettlementRejectsLaterSamples() {
        val motion = ReverbFadeMotion()
        motion.target(true, 1f)
        motion.frame(0L, 1f)
        motion.frame(40_000_000L, 1f)
        motion.settle(false)
        motion.frame(160_000_000L, 1f)
        assertEquals(0f, motion.alpha, 0f)
        assertFalse(motion.running)
    }

    @Test fun rebindingSameTargetDoesNotRestartTheFiniteTransition() {
        val motion = ReverbFadeMotion()
        motion.target(true, 1f)
        motion.frame(0L, 1f)
        motion.frame(80_000_000L, 1f)
        motion.target(true, 1f)
        motion.frame(160_000_000L, 1f)
        assertEquals(1f, motion.alpha, 0f)
        assertFalse(motion.running)
    }
}
