package smallthingz.ui.input.pointer.util

import org.junit.Assert.assertEquals
import org.junit.Test

class VelocityTrackerStorageTest {
    private fun tracker(strategy: VelocityTracker1D.Strategy, differential: Boolean = false) =
        VelocityTracker1D(differential, strategy)

    private fun assertBits(expected: Float, actual: Float) =
        assertEquals(expected.toRawBits(), actual.toRawBits())

    @Test fun resetDiscardsEveryOldSampleBeforeHistoryRefills() {
        for (strategy in VelocityTracker1D.Strategy.values()) {
            for (differential in if (strategy == VelocityTracker1D.Strategy.Impulse) listOf(false, true) else listOf(false)) {
                val reused = tracker(strategy, differential)
                repeat(4) { gesture ->
                    repeat(45) { i -> reused.addDataPoint(i * 4L, (i * i + gesture).toFloat()) }
                    reused.calculateVelocity()
                    reused.resetTracking()
                    assertBits(0f, reused.calculateVelocity())
                    val fresh = tracker(strategy, differential)
                    repeat(25) { i ->
                        val value = (gesture - i * 7).toFloat()
                        reused.addDataPoint(1000L + i * 3L, value)
                        fresh.addDataPoint(1000L + i * 3L, value)
                        assertBits(fresh.calculateVelocity(), reused.calculateVelocity())
                    }
                }
            }
        }
    }

    @Test fun wrappingHistoryMatchesExactlyTheNewestTwentySamples() {
        for (strategy in VelocityTracker1D.Strategy.values()) {
            val reused = tracker(strategy)
            repeat(160) { end ->
                reused.addDataPoint(end * 4L, (end % 11 - 4).toFloat())
                val fresh = tracker(strategy)
                for (i in (end - 19).coerceAtLeast(0)..end) {
                    fresh.addDataPoint(i * 4L, (i % 11 - 4).toFloat())
                }
                assertBits(fresh.calculateVelocity(), reused.calculateVelocity())
            }
        }
    }

    @Test fun reusedLeastSquaresWorkspaceIgnoresLongerPreviousGestures() {
        val reused = tracker(VelocityTracker1D.Strategy.Lsq2)
        for (length in intArrayOf(20, 3, 19, 5, 4, 17, 3, 8)) {
            reused.resetTracking()
            val fresh = tracker(VelocityTracker1D.Strategy.Lsq2)
            repeat(length) { i ->
                val value = (i * i - length * i + 0.125f)
                reused.addDataPoint(100L + i * 4L, value)
                fresh.addDataPoint(100L + i * 4L, value)
            }
            assertBits(fresh.calculateVelocity(), reused.calculateVelocity())
            assertBits(fresh.calculateVelocity(250f), reused.calculateVelocity(250f))
            assertBits(fresh.calculateVelocity(), reused.calculateVelocity())
        }
    }

    @Test fun stoppedMotionAndDuplicateTimesDoNotReusePriorFit() {
        for (strategy in VelocityTracker1D.Strategy.values()) {
            val reused = tracker(strategy)
            repeat(20) { i -> reused.addDataPoint(i * 4L, (i * 8).toFloat()) }
            reused.calculateVelocity()
            reused.addDataPoint(1000L, -6f)
            assertBits(0f, reused.calculateVelocity())
            val fresh = tracker(strategy)
            fresh.addDataPoint(1000L, -6f)
            for (value in floatArrayOf(-6f, 1f, 3f, -9f)) {
                reused.addDataPoint(1000L, value)
                fresh.addDataPoint(1000L, value)
                assertBits(fresh.calculateVelocity(), reused.calculateVelocity())
            }
        }
    }
}
