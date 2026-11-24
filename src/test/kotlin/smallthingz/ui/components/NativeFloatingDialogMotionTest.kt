package smallthingz.ui.components

import smallthingz.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFloatingDialogMotionTest {
    @Test fun closeBeforeFirstMeasureStillSchedulesOneTerminalDelivery() {
        val motion = NativeFloatingVisibilityMotion()
        motion.requestExit()
        motion.setHeight(503)
        assertTrue(motion.finished)
        assertTrue(motion.needsFrame(predictiveContinuation = false, exitDelivered = false))
        motion.frame(0, 1f)
        assertTrue(motion.exiting && motion.finished)
        assertEquals(0f, motion.alpha, 0f)
        assertFalse(motion.needsFrame(predictiveContinuation = false, exitDelivered = true))
    }

    @Test fun settledOpenThenLaterCloseStartsANewClock() {
        val motion = NativeFloatingVisibilityMotion()
        motion.setHeight(503)
        motion.frame(1_000_000_000L, 1f)
        motion.frame(1_220_000_000L, 1f)
        assertTrue(motion.finished)
        assertEquals(IntOffset.Zero, motion.translation)
        assertEquals(1f, motion.alpha, 0f)

        // Five seconds of user reading must not consume the closing animation.
        motion.requestExit()
        motion.frame(6_220_000_000L, 1f)
        assertFalse(motion.finished)
        assertEquals(IntOffset.Zero, motion.translation)
        assertEquals(1f, motion.alpha, 0f)
        motion.frame(6_315_000_000L, 1f)
        assertTrue(motion.translation.y in -502..-1)
        motion.frame(6_410_000_000L, 1f)
        assertTrue(motion.finished)
        assertEquals(IntOffset(0, -503), motion.translation)
        assertEquals(0f, motion.alpha, 0f)
    }

    @Test fun closeDuringEntranceRetainsLastPresentedPositionAndAlpha() {
        val motion = NativeFloatingVisibilityMotion()
        motion.setHeight(503)
        motion.frame(0, 1f)
        motion.frame(80_000_000, 1f)
        val shownOffset = motion.translation
        val shownAlpha = motion.alpha
        motion.requestExit()
        motion.frame(80_000_000, 1f)
        assertEquals(shownOffset, motion.translation)
        assertEquals(shownAlpha, motion.alpha, 0f)
        motion.frame(10_000_000_000, 1f)
        assertTrue(motion.finished)
        assertEquals(IntOffset(0, -503), motion.translation)
        assertEquals(0f, motion.alpha, 0f)
    }

    @Test fun reducedMotionSettlesEachRequestedTransition() {
        val motion = NativeFloatingVisibilityMotion()
        motion.setHeight(503)
        motion.frame(0, 0f)
        assertTrue(motion.finished)
        assertEquals(0f, motion.translation.y.toFloat(), 0f)
        motion.requestExit()
        motion.frame(10_000_000, 0f)
        assertTrue(motion.finished)
        assertEquals(-503, motion.translation.y)
        assertEquals(0f, motion.alpha, 0f)
    }

    @Test fun predictiveCommitOnlyDeliversAfterRemainingDistanceCompletes() {
        val motion = NativeFloatingPredictiveMotion()
        motion.started()
        motion.progressed(.75f)
        motion.committed()
        assertFalse(motion.frame(0, 1f))
        assertFalse(motion.frame(54_000_000, 1f))
        assertTrue(motion.frame(55_000_000, 1f))
        assertEquals(1f, motion.progress, 0f)
        assertTrue(motion.active) // The callback observes the terminal pose.
        motion.finishCommitCallback()
        assertFalse(motion.active)
        assertFalse(motion.frame(80_000_000, 1f)) // No duplicate terminal delivery.
    }

    @Test fun smoothCancellationReturnsThroughIntermediatePoseWithoutBackOrIdleFrames() {
        val motion = NativeFloatingPredictiveMotion(animateCancellation = true)
        val visibility = NativeFloatingVisibilityMotion().apply { setHeight(503); frame(0, 0f) }
        motion.started()
        motion.progressed(.6f)
        motion.cancelled()
        assertTrue(motion.active)
        assertEquals(.6f, motion.progress, 0f)
        assertTrue(visibility.needsFrame(motion.hasContinuation, false))
        assertFalse(motion.frame(0, 1f))
        assertEquals(.6f, motion.progress, 0f)
        assertFalse(motion.frame(80_000_000, 1f))
        assertTrue(motion.progress > 0f && motion.progress < .6f)
        motion.cancelled() // Must not restart the clock.
        assertFalse(motion.frame(160_000_000, 1f))
        assertEquals(0f, motion.progress, 0f)
        assertFalse(motion.active)
        assertFalse(motion.commitCompleted)
        assertFalse(motion.hasContinuation)
        assertFalse(visibility.needsFrame(motion.hasContinuation, false))
        assertFalse(motion.frame(10_000_000_000, 1f))
    }

    @Test fun smoothCancellationRestartKeepsPoseAndRebasesBothDirections() {
        val motion = NativeFloatingPredictiveMotion(animateCancellation = true)
        motion.started()
        motion.progressed(.6f)
        motion.cancelled()
        motion.frame(0, 1f)
        motion.frame(80_000_000, 1f)
        val held = motion.progress
        motion.started(.4f)
        assertEquals(held, motion.progress, 0f)
        assertFalse(motion.hasContinuation)
        motion.progressed(.4f)
        assertEquals(held, motion.progress, 0f)
        motion.progressed(.7f)
        assertEquals(held + (1f - held) * .5f, motion.progress, .0001f)
        motion.progressed(.2f)
        assertEquals(held * .5f, motion.progress, .0001f)
        assertFalse(motion.frame(1_000_000_000, 1f)) // Retired return cannot publish.
        assertEquals(held * .5f, motion.progress, .0001f)
        motion.progressed(1f)
        assertTrue(motion.committed())
        assertFalse(motion.hasContinuation)
    }

    @Test fun smoothRestartAtRawEndpointStaysFinite() {
        val motion = NativeFloatingPredictiveMotion(animateCancellation = true)
        motion.started()
        motion.progressed(.6f)
        motion.cancelled()
        motion.started(1f)
        motion.progressed(1f)
        assertEquals(.6f, motion.progress, 0f)
        motion.progressed(.5f)
        assertEquals(.3f, motion.progress, .0001f)
        motion.cancelled()
        assertFalse(motion.frame(0, 0f))
        assertEquals(0f, motion.progress, 0f)
        assertFalse(motion.hasContinuation)
    }

    @Test fun smoothCancellationZeroScaleSettlesAndDisableRetiresReturn() {
        val motion = NativeFloatingPredictiveMotion(animateCancellation = true)
        for (progress in listOf(0f, .6f, 1f)) {
            motion.started()
            motion.progressed(progress)
            motion.cancelled()
            assertFalse(motion.frame(0, 0f))
            assertEquals(0f, motion.progress, 0f)
            assertFalse(motion.active)
            assertFalse(motion.hasContinuation)
            assertFalse(motion.commitCompleted)
        }
        motion.started()
        motion.progressed(.6f)
        motion.cancelled()
        motion.frame(0, 1f)
        motion.disable()
        assertFalse(motion.hasContinuation)
        assertFalse(motion.active)
        assertEquals(0f, motion.progress, 0f)
        assertFalse(motion.frame(10_000_000_000, 1f))
    }

    @Test fun smoothCancelRevokesCommitAndOrdinaryBackReversesReturnOnce() {
        val motion = NativeFloatingPredictiveMotion(animateCancellation = true)
        motion.started()
        motion.progressed(.6f)
        assertFalse(motion.committed())
        motion.frame(0, 1f)
        motion.cancelled()
        assertFalse(motion.frame(40_000_000, 1f))
        assertFalse(motion.frame(120_000_000, 1f))
        val held = motion.progress
        assertTrue(held > 0f && held < .6f)
        assertFalse(motion.ordinaryBack())
        assertEquals(held, motion.progress, 0f)
        assertFalse(motion.frame(120_000_000, 1f))
        assertTrue(motion.frame(340_000_000, 1f))
        assertTrue(motion.commitCompleted)
        motion.finishCommitCallback()
        assertFalse(motion.frame(500_000_000, 1f))
        assertFalse(motion.hasContinuation)
    }

    @Test fun smoothRepeatedOrdinaryBackStillReplacesCommitWithEmptyStream() {
        val motion = NativeFloatingPredictiveMotion(animateCancellation = true)
        assertFalse(motion.ordinaryBack())
        motion.frame(0, 1f)
        motion.frame(110_000_000, 1f)
        assertTrue(motion.progress > 0f)
        assertFalse(motion.ordinaryBack())
        assertEquals(0f, motion.progress, 0f)
        assertFalse(motion.frame(500_000_000, 1f))
        assertTrue(motion.frame(720_000_000, 1f))
    }

    @Test fun dispatcherCancellationRetiresGestureImmediatelyWithoutReturnTween() {
        val motion = NativeFloatingPredictiveMotion()
        motion.started()
        motion.progressed(.6f)
        motion.cancelled()
        assertFalse(motion.active)
        assertFalse(motion.hasContinuation)
        assertEquals(.6f, motion.progress, 0f) // Source retains value, but inactive projection is neutral.
        assertFalse(motion.frame(1_000_000_000, 1f))
        assertFalse(motion.commitCompleted)
    }

    @Test fun repeatedOrdinaryBackStartsAFreshEmptyStreamAtZero() {
        val motion = NativeFloatingPredictiveMotion()
        assertFalse(motion.ordinaryBack())
        assertFalse(motion.frame(0, 1f))
        assertFalse(motion.frame(110_000_000, 1f))
        assertTrue(motion.progress > 0f)
        assertFalse(motion.ordinaryBack())
        assertEquals(0f, motion.progress, 0f)
        assertFalse(motion.frame(500_000_000, 1f))
        assertFalse(motion.frame(719_000_000, 1f))
        assertTrue(motion.frame(720_000_000, 1f))
    }

    @Test fun scaleRewindDoesNotResampleAlreadyFinishedAlphaChannel() {
        val motion = NativeFloatingVisibilityMotion()
        motion.setHeight(503)
        motion.frame(0, 1f)
        motion.frame(160_000_000, 1f) // Fade has finished; offset remains in flight.
        assertEquals(1f, motion.alpha, 0f)
        motion.frame(170_000_000, 10f)
        assertEquals(1f, motion.alpha, 0f)
    }

    @Test fun interruptedChannelHoldsItsStartWhenScaleRewindsBehindStartDelay() {
        val motion = NativeFloatingVisibilityMotion()
        motion.setHeight(503)
        motion.frame(0, 1f)
        motion.frame(80_000_000, 1f)
        val offset = motion.translation
        val alpha = motion.alpha
        motion.requestExit()
        motion.frame(90_000_000, 10f) // 9ms global play time is earlier than the 80ms interruption.
        assertEquals(offset, motion.translation)
        assertEquals(alpha, motion.alpha, 0f)
    }

    @Test fun explicitCloseRetiresPredictiveContinuation() {
        val motion = NativeFloatingPredictiveMotion()
        motion.started()
        motion.progressed(.6f)
        motion.committed()
        motion.frame(0, 1f)
        motion.disable()
        assertFalse(motion.active)
        assertEquals(0f, motion.progress, 0f)
        assertFalse(motion.frame(10_000_000_000, 1f))
    }

    @Test fun keyboardBackUsesTheSameFullCommitDuration() {
        val motion = NativeFloatingPredictiveMotion()
        motion.committed()
        assertFalse(motion.frame(0, 1f))
        assertFalse(motion.frame(219_000_000, 1f))
        assertTrue(motion.frame(220_000_000, 1f))
    }
    @Test fun terminalPredictiveProgressCommitsWithoutRequestingAnotherFrame() {
        val motion = NativeFloatingPredictiveMotion()
        motion.started()
        motion.progressed(1f)
        assertTrue(motion.committed())
        assertTrue(motion.commitCompleted)
        assertFalse(motion.hasContinuation)
        assertEquals(1f, motion.progress, 0f)
        assertFalse(motion.frame(0, 1f))
    }

    @Test fun positiveRemainingProgressRetainsAtLeastOneMillisecond() {
        val motion = NativeFloatingPredictiveMotion()
        motion.started()
        motion.progressed(.999f)
        assertFalse(motion.committed())
        assertFalse(motion.frame(0, 1f))
        assertTrue(motion.frame(1_000_000, 1f))
    }

    @Test fun predictiveClockRetainsAnimatableFloatScaleArithmetic() {
        val motion = NativeFloatingPredictiveMotion()
        motion.started()
        motion.progressed(.75f)
        assertFalse(motion.committed())
        assertFalse(motion.frame(0, 1f))
        // Pinned SuspendAnimation converts Long/Float before toLong: this rounds to55,000,000.
        assertTrue(motion.frame(54_999_999, 1f))
    }

}
