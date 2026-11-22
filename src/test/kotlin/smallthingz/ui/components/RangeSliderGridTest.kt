package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class RangeSliderGridTest {
    @Test fun nonDivisibleMaximumStopsAtLastTickAnchoredAtMin() {
        val grid = RangeSliderGrid(min = -7, max = 12, step = 5, minGap = 0)

        assertEquals(8, grid.last)
        assertEquals(-7, grid.snap(-Double.MAX_VALUE))
        assertEquals(-2, grid.snap(0.25))
        assertEquals(3, grid.snap(0.75))
        assertEquals(8, grid.snap(12.0))
        assertEquals(8, grid.snap(Double.MAX_VALUE))
        assertEquals(-7..8, grid.bind(Int.MIN_VALUE, Int.MAX_VALUE))
        assertEquals(3..3, grid.bind(3, -7))
    }

    @Test fun unitStepsReachBothIntExtrema() {
        val grid = RangeSliderGrid(Int.MIN_VALUE, Int.MAX_VALUE, step = 1, minGap = 0)

        assertEquals(Int.MAX_VALUE, grid.last)
        for (tick in listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE)) {
            assertEquals(tick, grid.snap(tick.toDouble()))
            assertEquals(tick..tick, grid.bind(tick, tick))
        }
    }

    @Test fun gapRoundsUpOnceAndBindPreservesLowerWhenFeasible() {
        for (requestedGap in listOf(5, 8)) {
            val grid = RangeSliderGrid(min = 3, max = 26, step = 4, minGap = requestedGap)

            assertEquals(8L, grid.gap)
            assertEquals(11..19, grid.bind(12, 4))
            assertEquals(11..23, grid.bind(11, 25))
            assertEquals(15..23, grid.bind(Int.MAX_VALUE, Int.MIN_VALUE))
        }
    }

    @Test fun roundedGapCanExceedIntMaxAcrossEntireIntDomain() {
        val step = 1 shl 30
        val grid = RangeSliderGrid(Int.MIN_VALUE, Int.MAX_VALUE, step, minGap = Int.MAX_VALUE)

        assertEquals(1L shl 31, grid.gap)
        assertEquals(step, grid.last)
        assertEquals(Int.MIN_VALUE..0, grid.bind(Int.MIN_VALUE, Int.MIN_VALUE))
        assertEquals((-step)..step, grid.bind(Int.MAX_VALUE, Int.MIN_VALUE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun reversedBoundsAreRejected() {
        RangeSliderGrid(10, 0, step = 1, minGap = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroStepIsRejected() {
        RangeSliderGrid(0, 10, step = 0, minGap = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeStepIsRejected() {
        RangeSliderGrid(0, 10, step = -1, minGap = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeGapIsRejected() {
        RangeSliderGrid(0, 10, step = 1, minGap = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun gapThatFitsRawBoundsButExceedsReachableSpanIsRejected() {
        RangeSliderGrid(0, 10, step = 4, minGap = 9)
    }
}
