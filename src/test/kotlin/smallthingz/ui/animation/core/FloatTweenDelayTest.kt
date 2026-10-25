package smallthingz.ui.animation.core

import smallthingz.ui.animation.TransitionValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatTweenDelayTest {
    @Test fun delayShiftsBothValueAndVelocityByTheSameAmount() {
        val localTimes = longArrayOf(-1_000_000L, 0L, 1L, 500_000L, 999_999L, 1_000_000L,
            30_000_000L, 100_000_000L, 219_999_999L, 220_000_000L, 220_000_001L, 270_000_000L)
        for (easing in listOf(LinearEasing, FastOutSlowInEasing)) {
            for ((initial, target) in listOf(0f to 1f, 13f to -7f, .5f to .5f)) {
                val immediate = FloatTweenSpec(220, easing = easing)
                for (delay in listOf(0, 70, 300)) {
                    val delayed = FloatTweenSpec(220, delay, easing)
                    for (local in localTimes) {
                        val global = local + delay * 1_000_000L
                        assertBits(immediate.getValueFromNanos(local, initial, target, 2.5f),
                            delayed.getValueFromNanos(global, initial, target, 2.5f))
                        assertBits(immediate.getVelocityFromNanos(local, initial, target, 2.5f),
                            delayed.getVelocityFromNanos(global, initial, target, 2.5f))
                    }
                }
            }
        }
    }

    @Test fun waitingAndExactStartRetainInitialVelocity() {
        val spec = FloatTweenSpec(220, 70, FastOutSlowInEasing)
        for (time in longArrayOf(-1_000_000L, 0L, 50_000_000L, 69_999_999L, 70_000_000L)) {
            assertBits(.25f, spec.getValueFromNanos(time, .25f, .75f, -3f))
            assertBits(-3f, spec.getVelocityFromNanos(time, .25f, .75f, -3f))
        }
    }

    @Test fun firstPartialMillisecondUsesTheExistingOneMillisecondDifferenceConvention() {
        val spec = FloatTweenSpec(100, 70, LinearEasing)
        // Half a millisecond traverses .005 of this tween; the existing estimator multiplies by
        // 1000 rather than dividing by the shorter, clamped observation interval.
        assertEquals(5f, spec.getVelocityFromNanos(70_500_000L, 0f, 1f, 17f), 0f)
        assertEquals(-5f, spec.getVelocityFromNanos(70_500_000L, 0f, -1f, 17f), 0f)
    }

    @Test fun noDelayAndTerminalSamplingKeepTheExistingFiniteDifferenceConvention() {
        for (easing in listOf(LinearEasing, FastOutSlowInEasing)) {
            val spec = FloatTweenSpec(220, easing = easing)
            for (time in longArrayOf(1L, 500_000L, 1_000_000L, 30_000_000L, 220_000_000L, 270_000_000L)) {
                val observed = minOf(time, 220_000_000L)
                val previous = maxOf(0L, observed - 1_000_000L)
                val expected = (spec.getValueFromNanos(observed, 13f, -7f, 4f) -
                    spec.getValueFromNanos(previous, 13f, -7f, 4f)) * 1000f
                assertBits(expected, spec.getVelocityFromNanos(time, 13f, -7f, 4f))
            }
        }
    }

    @Test fun zeroDurationKeepsItsExistingValueAndVelocityBoundaries() {
        for (delay in listOf(0, 70)) {
            val spec = FloatTweenSpec(0, delay)
            for (time in longArrayOf(-1L, 0L, 69_999_999L, 70_000_000L, 100_000_000L)) {
                assertBits(1f, spec.getValueFromNanos(time, 0f, 1f, -2f))
                assertBits(-2f, spec.getVelocityFromNanos(time, 0f, 1f, -2f))
            }
        }
    }

    @Test fun interruptedDelayedFadeCarriesItsVisibleMotionIntoTheSpring() {
        val fadeSpec = tween<Float>(220, delayMillis = 70, easing = FastOutSlowInEasing)
        val interruption = spring<Float>(visibilityThreshold = .01f)
        val fade = TransitionValue(0f, Float.VectorConverter, interruption, { AnimationVector1D(0f) })
        fade.target(1f, fadeSpec, 0L)
        fade.frame(100_000_000L, false)
        val curve = FloatTweenSpec(220, 70, FastOutSlowInEasing)
        val expectedVelocity = (curve.getValueFromNanos(100_000_000L, 0f, 1f, 0f) -
            curve.getValueFromNanos(99_000_000L, 0f, 1f, 0f)) * 1000f
        assertTrue(expectedVelocity > 0f)
        val expected = TargetBasedAnimation(interruption, Float.VectorConverter, fade.value, 0f,
            initialVelocityVector = AnimationVector1D(expectedVelocity))
        fade.target(0f, fadeSpec, 100_000_000L)
        fade.frame(117_000_000L, false)
        assertBits(expected.getValueFromNanos(17_000_000L), fade.value)
    }

    private fun assertBits(expected: Float, actual: Float) =
        assertEquals(expected.toRawBits(), actual.toRawBits())
}
