package smallthingz.ui.animation.core

import kotlin.math.ln
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpringDurationLimitTest {
    private val maxDuration = (Long.MAX_VALUE / MillisToNanos) * MillisToNanos

    @Test fun finitePositiveSpringsCannotWrapTheirNanosecondDuration() {
        for (damping in floatArrayOf(Float.MIN_VALUE, 1e-20f, 1e-12f, .5f, 1f)) {
            for (stiffness in floatArrayOf(Float.MIN_VALUE, 1e-30f, 1e-20f, 1f)) {
                val spec = FloatSpringSpec(damping, stiffness, visibilityThreshold = 1f)
                val duration = spec.getDurationNanos(100f, 0f, 0f)
                assertTrue("damping=$damping stiffness=$stiffness duration=$duration", duration > 0L)
                assertTrue(duration <= maxDuration)
            }
        }
    }

    @Test fun overflowingFiniteSpringUsesTheUndampedDurationLimit() {
        val undamped = FloatSpringSpec(dampingRatio = 0f, stiffness = 1f)
        assertEquals(maxDuration, undamped.getDurationNanos(100f, 0f, 0f))
        for (damping in floatArrayOf(Float.MIN_VALUE, 1e-20f, 1e-12f)) {
            val spec = FloatSpringSpec(damping, stiffness = 1f, visibilityThreshold = 1f)
            assertEquals(maxDuration, spec.getDurationNanos(100f, 0f, 0f))
        }
    }

    @Test fun targetAnimationDoesNotFinishAtItsFirstFrameAfterDurationOverflow() {
        val animation = TargetBasedAnimation(
            spring<Float>(dampingRatio = 1e-20f, stiffness = 1f, visibilityThreshold = 1f),
            Float.VectorConverter,
            initialValue = 100f,
            targetValue = 0f,
        )
        assertEquals(maxDuration, animation.durationNanos)
        assertFalse(animation.isFinishedFromNanos(0L))
        assertEquals(100f, animation.getValueFromNanos(0L), 0f)
        assertFalse(animation.isFinishedFromNanos(1_000_000_000L))
        assertTrue(animation.getValueFromNanos(1_000_000_000L).isFinite())
    }

    @Test fun representableUnderdampedDurationsMatchTheAnalyticEnvelope() {
        // For x(0)=100, v(0)=0 and unit threshold, the underdamped envelope is
        // A*exp(-zeta*omega*t), A=100/sqrt(1-zeta^2). Its crossing is analytic.
        for (damping in floatArrayOf(.2f, .5f, .75f)) {
            for (stiffness in floatArrayOf(1f, 50f, 1500f)) {
                val zeta = damping.toDouble()
                val amplitude = 100.0 / sqrt(1.0 - zeta * zeta)
                val millis = (1000.0 * ln(amplitude) / (zeta * sqrt(stiffness.toDouble()))).toLong()
                val duration = FloatSpringSpec(damping, stiffness, visibilityThreshold = 1f)
                    .getDurationNanos(100f, 0f, 0f)
                assertEquals(millis * MillisToNanos, duration)
            }
        }
    }
}
