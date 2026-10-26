package smallthingz.ui.animation

import smallthingz.ui.unit.Density
import org.junit.Assert.*
import org.junit.Test

class FlingZeroDurationTest {
    @Test fun zeroAndTinyVelocitiesHaveFiniteZeroTerminalVelocity() {
        val calculator = FlingCalculator(0.015f, Density(1f))
        for (velocity in floatArrayOf(0f, -0f, Float.MIN_VALUE, -Float.MIN_VALUE, 0.0001f, -0.0001f)) {
            val info = calculator.flingInfo(velocity)
            assertEquals(0L, info.duration)
            for (time in longArrayOf(-1L, 0L, 1L, 100L)) {
                assertEquals(0f, info.velocity(time), 0f)
                assertTrue(info.position(time).isFinite())
            }
        }
    }

    @Test fun finishedPositiveDurationFlingsHaveZeroVelocity() {
        val calculator = FlingCalculator(0.015f, Density(1f))
        for (velocity in floatArrayOf(-1000f, -10f, 10f, 1000f)) {
            val info = calculator.flingInfo(velocity)
            assertTrue(info.duration > 0L)
            assertEquals(0f, info.velocity(info.duration), 0f)
            assertEquals(0f, info.velocity(info.duration + 100L), 0f)
        }
    }

    @Test fun nonPositiveDurationNeverDividesByZero() {
        for (duration in longArrayOf(Long.MIN_VALUE, -1L, 0L)) {
            assertEquals(0f, FlingCalculator.FlingInfo(3f, 4f, duration).velocity(0L), 0f)
        }
    }
}
