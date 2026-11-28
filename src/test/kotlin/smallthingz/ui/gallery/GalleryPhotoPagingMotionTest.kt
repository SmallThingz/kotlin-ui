package smallthingz.ui.gallery

import org.junit.Assert.*
import org.junit.Test

class GalleryPhotoPagingMotionTest {
    @Test fun dragIsBoundedAndTakesOwnershipFromAnActiveSettle() {
        val motion = GalleryPhotoPagingMotion()
        assertEquals(0f, motion.fraction, 0f)
        assertFalse(motion.running)
        motion.drag(4f)
        assertEquals(1f, motion.fraction, 0f)
        motion.drag(-4f)
        assertEquals(-1f, motion.fraction, 0f)
        motion.settle(1f, 1f)
        motion.frame(0L, 1f)
        motion.frame(50_000_000L, 1f)
        assertTrue(motion.running)
        motion.drag(.25f)
        assertEquals(.25f, motion.fraction, 0f)
        assertFalse(motion.running)
        motion.frame(2_000_000_000L, 1f)
        assertEquals(.25f, motion.fraction, 0f)
        motion.settle(0f, 1f)
        motion.frame(3_000_000_000L, 1f)
        motion.frame(3_050_000_000L, 1f)
        assertTrue(motion.fraction > 0f && motion.fraction < .25f)
    }

    @Test fun bothDirectionsSettleMonotonicallyToExactPageBoundaries() {
        for (direction in listOf(-1f, 1f)) {
            val motion = GalleryPhotoPagingMotion()
            motion.drag(.2f * direction)
            motion.settle(direction, 1f)
            var previous = .2f
            var sampledIntermediate = false
            for (frame in 0..125) {
                motion.frame(frame * 16_000_000L, 1f)
                val progress = motion.fraction * direction
                // The shared spring's initial subtraction can differ by one float ULP.
                assertTrue(progress + .0000001f >= previous && progress <= 1f)
                if (progress > .2f && progress < 1f) sampledIntermediate = true
                previous = progress
            }
            assertTrue(sampledIntermediate)
            assertEquals(direction, motion.fraction, 0f)
            assertFalse(motion.running)
        }
    }

    @Test fun cancellationAndRetargetingKeepThePresentedPosition() {
        val motion = GalleryPhotoPagingMotion()
        motion.drag(.6f)
        motion.settle(1f, 1f)
        motion.frame(0L, 1f)
        motion.frame(60_000_000L, 1f)
        val presented = motion.fraction
        motion.settle(0f, 1f)
        assertEquals(presented, motion.fraction, 0f)
        motion.frame(60_000_000L, 1f)
        assertEquals(presented, motion.fraction, 0f)
        motion.frame(2_000_000_000L, 1f)
        assertEquals(0f, motion.fraction, 0f)
        assertFalse(motion.running)
        motion.settle(0f, 1f)
        assertFalse(motion.running)
    }

    @Test fun rebindingSameTargetDoesNotRestartTheSpring() {
        val uninterrupted = GalleryPhotoPagingMotion()
        val rebound = GalleryPhotoPagingMotion()
        uninterrupted.settle(1f, 1f)
        rebound.settle(1f, 1f)
        for (frame in 0..100) {
            rebound.settle(1f, 1f)
            uninterrupted.frame(frame * 16_000_000L, 1f)
            rebound.frame(frame * 16_000_000L, 1f)
            assertEquals(uninterrupted.fraction, rebound.fraction, 0f)
            assertEquals(uninterrupted.running, rebound.running)
        }
        assertEquals(1f, rebound.fraction, 0f)
        assertFalse(rebound.running)
    }

    @Test fun durationScaleChangesElapsedMotionRatherThanTheTarget() {
        val normal = GalleryPhotoPagingMotion()
        val slower = GalleryPhotoPagingMotion()
        normal.settle(-1f, 1f)
        slower.settle(-1f, 2f)
        normal.frame(0L, 1f)
        slower.frame(0L, 2f)
        normal.frame(100_000_000L, 1f)
        slower.frame(200_000_000L, 2f)
        assertTrue(normal.running && slower.running)
        assertEquals(normal.fraction, slower.fraction, .000001f)
        normal.frame(2_000_000_000L, 1f)
        slower.frame(4_000_000_000L, 2f)
        assertEquals(-1f, normal.fraction, 0f)
        assertEquals(-1f, slower.fraction, 0f)
        assertFalse(normal.running || slower.running)
    }

    @Test fun reducedMotionSettlesImmediatelyIncludingAnAlreadyRunningTarget() {
        val motion = GalleryPhotoPagingMotion()
        for (target in listOf(-1f, 0f, 1f)) {
            motion.settle(target, 0f)
            assertEquals(target, motion.fraction, 0f)
            assertFalse(motion.running)
        }
        motion.drag(.25f)
        motion.settle(1f, 1f)
        motion.frame(0L, 1f)
        motion.frame(30_000_000L, 1f)
        motion.settle(1f, 0f)
        assertEquals(1f, motion.fraction, 0f)
        assertFalse(motion.running)
        motion.settle(-1f, 1f)
        motion.frame(100_000_000L, 0f)
        assertEquals(-1f, motion.fraction, 0f)
        assertFalse(motion.running)
    }

    @Test fun resetRejectsLateFramesAndNextSettleStartsWithAFreshClock() {
        val motion = GalleryPhotoPagingMotion()
        motion.settle(1f, 1f)
        motion.frame(0L, 1f)
        motion.frame(50_000_000L, 1f)
        motion.reset()
        motion.frame(2_000_000_000L, 1f)
        assertEquals(0f, motion.fraction, 0f)
        assertFalse(motion.running)
        motion.settle(-1f, 1f)
        motion.frame(3_000_000_000L, 1f)
        assertEquals(0f, motion.fraction, 0f)
        motion.frame(3_050_000_000L, 1f)
        assertTrue(motion.fraction < 0f && motion.fraction > -1f)
    }

    @Test fun invalidInputsAreRejectedWithoutChangingTheCurrentMotion() {
        val motion = GalleryPhotoPagingMotion()
        motion.drag(.3f)
        motion.settle(1f, 1f)
        motion.frame(0L, 1f)
        motion.frame(50_000_000L, 1f)
        val fraction = motion.fraction
        for (invalid in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { motion.drag(invalid) }
        }
        for (invalid in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -.5f, .5f, -2f, 2f)) {
            assertThrows(IllegalArgumentException::class.java) { motion.settle(invalid, 1f) }
        }
        for (invalid in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -1f)) {
            assertThrows(IllegalArgumentException::class.java) { motion.settle(-1f, invalid) }
            assertThrows(IllegalArgumentException::class.java) { motion.frame(5_000_000_000L, invalid) }
        }
        assertEquals(fraction, motion.fraction, 0f)
        assertTrue(motion.running)
        motion.frame(2_000_000_000L, 1f)
        assertEquals(1f, motion.fraction, 0f)
        assertFalse(motion.running)
    }
}
