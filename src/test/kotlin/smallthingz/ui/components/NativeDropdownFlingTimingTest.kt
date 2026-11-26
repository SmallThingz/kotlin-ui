package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import smallthingz.ui.animation.AndroidFlingSpline
import smallthingz.ui.animation.FlingCalculator
import smallthingz.ui.unit.Density

class NativeDropdownFlingTimingTest {
    private val second = 1_000_000_000L
    private val fallback = 210_000_000L

    @Test fun clippedDistanceCompressesTheWholeClockProportionatelyInBothDirections() {
        for (direction in listOf(-1, 1)) {
            val full = nativeDropdownFlingDurationNanos(10f, 10 + direction * 10, 60, direction * 600f, second, fallback, false)
            val half = nativeDropdownFlingDurationNanos(10f, 10 + direction * 5, 60, direction * 600f, second, fallback, false)
            val tenth = nativeDropdownFlingDurationNanos(10f, 10 + direction, 60, direction * 600f, second, fallback, false)
            assertEquals(second, full)
            assertEquals(second / 2, half)
            assertEquals(second / 10, tenth)
            val clock = NativeDropdownFlingTiming(tenth, 0)
            assertEquals(AndroidFlingSpline.distanceCoefficient(.5f), clock.sample(tenth / 2, 1f), 0f)
            assertEquals(1f, clock.sample(tenth, 1f), 0f)
            assertTrue(clock.isFinished)
        }
    }

    @Test fun fasterRealSharedSplinePredictionReachesTheSameNearbyEdgeSooner() {
        val calculator = FlingCalculator(.015f, Density(1f))
        var previousDuration = Long.MAX_VALUE
        for (velocity in listOf(2000f, 4000f, 8000f)) {
            val native = calculator.flingInfo(velocity)
            val target = requireNotNull(nativeDropdownFlingTarget(9.25f, native.distance.toInt(), 60, 10, velocity))
            assertEquals(10, target)
            val duration = nativeDropdownFlingDurationNanos(9.25f, target, 60, native.distance,
                native.duration * 1_000_000L, fallback, false)
            assertTrue(duration > 0 && duration < native.duration * 1_000_000L)
            assertTrue(duration < previousDuration)
            previousDuration = duration
        }
    }

    @Test fun numericScalesChangeTimeNotTheFixedPositionOrSplineShape() {
        for (scale in listOf(.5f, 1f, 2f)) {
            val clock = NativeDropdownFlingTiming(second, 0)
            val path = NativeDropdownFlingPath(1.25f, 6, 0, 1)
            assertEquals(1.25f, path.positionAtFraction(clock.sample(0, scale)), 0f)
            assertEquals(AndroidFlingSpline.distanceCoefficient(.5f), clock.sample((second * scale / 2).toLong(), scale), 0f)
            assertFalse(clock.isFinished)
            assertEquals(6f, path.positionAtFraction(clock.sample((second * scale).toLong(), scale)), 0f)
            assertTrue(clock.isFinished)
        }
    }

    @Test fun midFlightScaleChangesIntegrateAcceptedIntervalsWithoutRebasingTime() {
        val clock = NativeDropdownFlingTiming(second, 0)
        assertEquals(AndroidFlingSpline.distanceCoefficient(.25f), clock.sample(second / 4, 1f), 0f)
        assertEquals(AndroidFlingSpline.distanceCoefficient(.375f), clock.sample(second / 2, 2f), 0f)
        assertEquals(AndroidFlingSpline.distanceCoefficient(.875f), clock.sample(3 * second / 4, .5f), 0f)
        assertEquals(1f, clock.sample(7 * second / 8, 1f), 0f)
        assertTrue(clock.isFinished)
    }

    @Test fun zeroScaleAtReleaseOrMidFlightReachesTerminalExactlyOnceAndStaysThere() {
        for (sampleFirst in listOf(false, true)) {
            val clock = NativeDropdownFlingTiming(second, 0)
            if (sampleFirst) assertTrue(clock.sample(second / 4, 1f) < 1f)
            assertEquals(1f, clock.sample(second / 4, 0f), 0f)
            assertTrue(clock.isFinished)
            assertEquals(1f, clock.sample(second / 2, 2f), 0f)
        }
    }

    @Test fun monotoneForwardAndReverseSamplesReachFixedRowWithoutCorrection() {
        for (target in listOf(0, 10)) {
            val path = NativeDropdownFlingPath(5.25f, target, 0, 1)
            val clock = NativeDropdownFlingTiming(second, 0)
            var previous = 5.25f
            for (step in 0..1000) {
                val next = path.positionAtFraction(clock.sample(step * second / 1000, 1f))
                assertTrue(next in minOf(5.25f, target.toFloat())..maxOf(5.25f, target.toFloat()))
                assertTrue(if (target == 0) next <= previous else next >= previous)
                previous = next
            }
            assertEquals(target.toFloat(), previous, 0f)
            assertEquals(target.toFloat(), path.positionAtFraction(clock.sample(2 * second, .5f)), 0f)
        }
        // The entire decelerating tail is retained, not truncated at an edge arrival time.
        assertTrue(AndroidFlingSpline.velocityCoefficient(.99f) < AndroidFlingSpline.velocityCoefficient(.9f))
        assertEquals(0f, AndroidFlingSpline.velocityCoefficient(1f), 0f)
    }

    @Test fun interruptionFreezesOldClockAndNewReleaseHasIndependentOrigin() {
        val old = NativeDropdownFlingTiming(second, 0)
        val stopped = old.sample(second / 4, 1f)
        old.cancel()
        assertTrue(old.isFinished)
        assertEquals(stopped, old.sample(second, 0f), 0f)
        val replacement = NativeDropdownFlingTiming(second / 2, second)
        assertEquals(0f, replacement.sample(second, 1f), 0f)
        assertFalse(replacement.isFinished)
        assertEquals(1f, replacement.sample(3 * second / 2, 1f), 0f)
    }

    @Test fun regressingTimeAndInvalidScaleCannotReverseOrPoisonTheClock() {
        val clock = NativeDropdownFlingTiming(second, 0)
        val previous = clock.sample(second / 4, 1f)
        assertEquals(previous, clock.sample(0, .5f), 0f)
        assertEquals(AndroidFlingSpline.distanceCoefficient(.5f), clock.sample(second / 2, Float.NaN), 0f)
        assertEquals(AndroidFlingSpline.distanceCoefficient(.75f), clock.sample(3 * second / 4, Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, clock.sample(second, -1f), 0f)
    }

    @Test fun forcedTinyCoastKeepsDeliberateSettleDurationAndExtremeDurationsStayFinite() {
        assertTrue(nativeDropdownFlingNeedsScroll(2.01f, 3, 1, 60))
        assertEquals(fallback, nativeDropdownFlingDurationNanos(2.01f, 3, 60, 1f, 1_000L, fallback, true))
        assertEquals(fallback, nativeDropdownFlingDurationNanos(2.01f, 3, 60, 0f, 0L, fallback, true))
        assertEquals(0L, nativeDropdownFlingDurationNanos(3f, 3, 60, 1000f, second, fallback, false))
        assertEquals(1L, nativeDropdownFlingDurationNanos(2.9999998f, 3, 1, Float.MAX_VALUE, 0L, fallback, false))
        val maximum = Int.MAX_VALUE.toLong() * 1_000_000L
        val clock = NativeDropdownFlingTiming(maximum, 0)
        assertTrue(clock.sample(maximum / 2, 1f).isFinite())
        assertEquals(1f, clock.sample(maximum, 1f), 0f)
    }
}
