package smallthingz.ui.components

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeDurationWheelMathTest {
    @Test
    fun splitAndCompose_preserveDurations() {
        val values = listOf(0, 1, 59, 60, 3_599, 3_600, 86_400, 172_815, Int.MAX_VALUE)
        values.forEach { seconds ->
            val parts = splitRangeDurationWheelSeconds(seconds)
            assertEquals(
                seconds,
                composeRangeDurationWheelSeconds(parts.hours, parts.minutes, parts.seconds),
            )
        }
    }

    @Test
    fun profileSteps_matchPickerContract() {
        assertEquals(1, rangeDurationWheelProfileStep(0))
        assertEquals(5, rangeDurationWheelProfileStep(1))
        assertEquals(15, rangeDurationWheelProfileStep(2))
    }

    @Test
    fun hoursAreNotClockBounded() {
        val parts = splitRangeDurationWheelSeconds(86_400)
        assertEquals(24, parts.hours)
        assertEquals(0, parts.minutes)
        assertEquals(0, parts.seconds)
    }
    @Test
    fun normalProfileRing_doesNotInventFiftyNineAsAStep() {
        assertEquals(
            listOf(0, 15, 30, 45),
            rangeDurationWheelValues(
                step = 15,
                maxInclusive = 59,
                currentValue = 0,
                includeMaximumBoundary = false,
            ).toList(),
        )
    }

    @Test
    fun reachableTenMinuteBoundary_doesNotExposeElevenMinutes() {
        val values = rangeDurationWheelValues(
            step = 1,
            maxInclusive = 10,
            currentValue = 10,
        )
        assertEquals((0..10).toList(), values.toList())
    }

    @Test
    fun limitAwareRings_includeExactBoundaryRegardlessOfProfile() {
        assertEquals(
            listOf(0, 15, 24),
            rangeDurationWheelValues(step = 15, maxInclusive = 24, currentValue = 24).toList(),
        )
        assertEquals(
            listOf(0, 15, 16),
            rangeDurationWheelValues(step = 15, maxInclusive = 16, currentValue = 16).toList(),
        )
    }

    @Test
    fun limitAwareRings_preserveAbnormalCurrentValueUntilUserLeavesIt() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6, 13),
            rangeDurationWheelValues(step = 1, maxInclusive = 6, currentValue = 13).toList(),
        )
        assertEquals(
            listOf(0, 15, 24, 25),
            rangeDurationWheelValues(step = 15, maxInclusive = 24, currentValue = 25).toList(),
        )
    }

    @Test
    fun fractionalLimitDisplay_neverShowsValidOverflowAsSafe() {
        val max = 6 * 3_600.0 + 24 * 60.0 + 16.75
        assertEquals(6 * 3_600 + 24 * 60 + 16, rangeDurationWheelWholeLimitSeconds(max))
        assertEquals(6 * 3_600 + 24 * 60 + 16, rangeDurationWheelDisplaySeconds(max, max))
        assertEquals(6 * 3_600 + 24 * 60 + 17, rangeDurationWheelDisplaySeconds(max + 0.01, max))
    }

    @Test
    fun candidateFaceRedStateReflectsResultOfSelectingThatFace() {
        val maximum = 6 * 3_600.0 + 24 * 60.0 + 16.75

        // With an abnormal hour selected, moving the hour back into range can clear overage.
        assertEquals(false, rangeDurationWheelCandidateIsOverLimit(6, 0, 0, maximum))
        assertEquals(false, rangeDurationWheelCandidateIsOverLimit(5, 59, 59, maximum))
        assertEquals(true, rangeDurationWheelCandidateIsOverLimit(13, 0, 0, maximum))

        // Changing another field cannot clear an hour overage.
        assertEquals(true, rangeDurationWheelCandidateIsOverLimit(13, 15, 0, maximum))
        assertEquals(true, rangeDurationWheelCandidateIsOverLimit(13, 0, 15, maximum))

        // At the maximum hour, only candidates whose resulting full time still exceeds M are red.
        assertEquals(false, rangeDurationWheelCandidateIsOverLimit(6, 24, 16, maximum))
        assertEquals(true, rangeDurationWheelCandidateIsOverLimit(6, 24, 17, maximum))
        assertEquals(true, rangeDurationWheelCandidateIsOverLimit(6, 25, 0, maximum))
    }

    @Test
    fun errorMask_isHierarchicalAtExportBoundary() {
        val max = 6 * 3_600 + 24 * 60 + 16
        assertEquals(0, rangeDurationWheelErrorMask(5, 59, 59, max))
        assertEquals(0, rangeDurationWheelErrorMask(6, 24, 16, max))
        assertEquals(
            RANGE_DURATION_WHEEL_ERROR_SECOND,
            rangeDurationWheelErrorMask(6, 24, 17, max),
        )
        assertEquals(
            RANGE_DURATION_WHEEL_ERROR_MINUTE or RANGE_DURATION_WHEEL_ERROR_SECOND,
            rangeDurationWheelErrorMask(6, 25, 0, max),
        )
        assertEquals(
            RANGE_DURATION_WHEEL_ERROR_HOUR or
                RANGE_DURATION_WHEEL_ERROR_MINUTE or
                RANGE_DURATION_WHEEL_ERROR_SECOND,
            rangeDurationWheelErrorMask(13, 0, 0, max),
        )
    }

    @Test
    fun wheelValues_matchLegacySortedDistinctForAllSmallRingsAndBoundedHugeInputs() {
        for (maximum in -2..80) for (step in 1..20) for (current in -2..100) {
            for (includeMaximum in booleanArrayOf(false, true)) {
                assertArrayEquals(legacyWheel(step, maximum, current, includeMaximum),
                    rangeDurationWheelValues(step, maximum, current, includeMaximum))
            }
        }
        val random = Random(26026)
        repeat(2_000) {
            val maximum = random.nextInt(Int.MAX_VALUE)
            val step = random.nextInt(maxOf(1, maximum / 4096 + 1), Int.MAX_VALUE)
            val current = random.nextInt()
            val includeMaximum = random.nextBoolean()
            assertArrayEquals(legacyWheel(step, maximum, current, includeMaximum),
                rangeDurationWheelValues(step, maximum, current, includeMaximum))
        }
        for (step in intArrayOf(Int.MAX_VALUE / 2, Int.MAX_VALUE - 1, Int.MAX_VALUE)) {
            for (current in intArrayOf(0, 1, Int.MAX_VALUE - 1, Int.MAX_VALUE)) {
                assertArrayEquals(legacyWheel(step, Int.MAX_VALUE, current, true),
                    rangeDurationWheelValues(step, Int.MAX_VALUE, current, true))
            }
        }
    }

    @Test
    fun wheelOffProfileMaximumAndCurrent_remainOrderedAndUnique() {
        assertArrayEquals(intArrayOf(0, 1, 2), rangeDurationWheelValues(5, 1, 2))
        assertArrayEquals(intArrayOf(0, 4, 5), rangeDurationWheelValues(5, 5, 4))
        assertArrayEquals(intArrayOf(0, 1, 5), rangeDurationWheelValues(1, 1, 5))
        assertArrayEquals(intArrayOf(0, 5, 7), rangeDurationWheelValues(5, 7, 7))
        assertArrayEquals(intArrayOf(0, 5, 7), rangeDurationWheelValues(5, 7, 7, false))
    }

    @Test
    fun wheelInvalidStepAndOverflowingCapacity_preserveRejectionWithoutLargeAllocation() {
        for (step in intArrayOf(Int.MIN_VALUE, -1, 0)) {
            assertThrows(IllegalArgumentException::class.java) { legacyWheel(step, 59, 0, true) }
            assertThrows(IllegalArgumentException::class.java) { rangeDurationWheelValues(step, 59, 0) }
        }
        for (maximum in intArrayOf(Int.MAX_VALUE - 2, Int.MAX_VALUE - 1, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { legacyWheel(1, maximum, 0, true) }
            assertThrows(IllegalArgumentException::class.java) { rangeDurationWheelValues(1, maximum, 0) }
        }
    }

    private fun legacyWheel(step: Int, maxInclusive: Int, currentValue: Int, includeMaximum: Boolean): IntArray {
        require(step > 0)
        val max = maxInclusive.coerceAtLeast(0)
        val current = currentValue.coerceAtLeast(0)
        val values = ArrayList<Int>(max / step + 3)
        var value = 0
        while (value <= max) {
            values += value
            if (value > Int.MAX_VALUE - step) break
            value += step
        }
        if (includeMaximum && values.lastOrNull() != max) values += max
        if (current !in values) values += current
        values.sort()
        return values.distinct().toIntArray()
    }
}
