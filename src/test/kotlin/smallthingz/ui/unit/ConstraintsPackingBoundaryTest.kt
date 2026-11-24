package smallthingz.ui.unit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ConstraintsPackingBoundaryTest {
    @Test fun everyPackingTransitionRoundTripsOrRejectsWithoutLaneSpill() {
        val values = intArrayOf(0, 1, 8190, 8191, 32766, 32767, 65534, 65535,
            262142, 262143, Int.MAX_VALUE)
        for (width in values) for (height in values) {
            for (unboundedWidth in listOf(false, true)) for (unboundedHeight in listOf(false, true)) {
                val maxWidth = if (unboundedWidth) Constraints.Infinity else width
                val maxHeight = if (unboundedHeight) Constraints.Infinity else height
                if (!fits(width, height)) {
                    assertThrows(IllegalArgumentException::class.java) {
                        Constraints(width, maxWidth, height, maxHeight)
                    }
                } else {
                    val constraints = Constraints(width, maxWidth, height, maxHeight)
                    assertEquals(width, constraints.minWidth)
                    assertEquals(height, constraints.minHeight)
                    assertEquals(maxWidth, constraints.maxWidth)
                    assertEquals(maxHeight, constraints.maxHeight)
                    assertEquals(!unboundedWidth, constraints.hasBoundedWidth)
                    assertEquals(!unboundedHeight, constraints.hasBoundedHeight)
                }
            }
        }
    }

    @Test fun distinctMinimumAndMaximumFieldsConstrainBothAxes() {
        for ((maxWidth, maxHeight) in capacities) {
            val constraints = Constraints(7, maxWidth, 11, maxHeight)
            assertEquals(7, constraints.minWidth)
            assertEquals(11, constraints.minHeight)
            assertEquals(maxWidth, constraints.maxWidth)
            assertEquals(maxHeight, constraints.maxHeight)
            assertEquals(IntSize(7, 11), constraints.constrain(IntSize(Int.MIN_VALUE, Int.MIN_VALUE)))
            assertEquals(IntSize(maxWidth, maxHeight),
                constraints.constrain(IntSize(Int.MAX_VALUE, Int.MAX_VALUE)))
            assertEquals(IntSize(100, 200), constraints.constrain(IntSize(100, 200)))
        }
    }

    @Test fun unboundedMaximumsRemainSentinelsInsteadOfFiniteZero() {
        val constraints = Constraints()
        assertFalse(constraints.hasBoundedWidth)
        assertFalse(constraints.hasBoundedHeight)
        assertEquals(Constraints.Infinity, constraints.maxWidth)
        assertEquals(Constraints.Infinity, constraints.maxHeight)
        assertEquals(IntSize(Int.MAX_VALUE, Int.MAX_VALUE),
            constraints.constrain(IntSize(Int.MAX_VALUE, Int.MAX_VALUE)))
        val zero = Constraints(0, 0, 0, 0)
        assertEquals(0, zero.maxWidth)
        assertEquals(0, zero.maxHeight)
        assertEquals(IntSize.Zero, zero.constrain(IntSize(1, 1)))
    }

    @Test fun widthPriorityClampsToTheMatchingHeightCapacity() {
        val cases = arrayOf(8190 to 262142, 8191 to 65534, 32766 to 65534,
            32767 to 32766, 65534 to 32766, 65535 to 8190, 262142 to 8190,
            (Int.MAX_VALUE - 1) to 8190)
        for ((width, heightCapacity) in cases) {
            val constraints = Constraints.fitPrioritizingWidth(0, width, 0, Int.MAX_VALUE - 1)
            assertEquals(minOf(width, 262142), constraints.maxWidth)
            assertEquals(heightCapacity, constraints.maxHeight)
        }
        val unbounded = Constraints.fitPrioritizingWidth(65535, Constraints.Infinity,
            0, Constraints.Infinity)
        assertEquals(65535, unbounded.minWidth)
        assertEquals(Constraints.Infinity, unbounded.maxWidth)
        assertEquals(Constraints.Infinity, unbounded.maxHeight)
    }

    @Test fun negativeAndReversedBoundsAreRejectedBeforePacking() {
        for (bounds in arrayOf(intArrayOf(-1, 0, 0, 0), intArrayOf(0, -1, 0, 0),
            intArrayOf(0, 0, -1, 0), intArrayOf(0, 0, 0, -1),
            intArrayOf(2, 1, 0, 0), intArrayOf(0, 0, 2, 1))) {
            assertThrows(IllegalArgumentException::class.java) {
                Constraints(bounds[0], bounds[1], bounds[2], bounds[3])
            }
        }
    }

    // Independent capacity table: a finite maximum reserves one encoding for infinity.
    private val capacities = arrayOf(8190 to 262142, 32766 to 65534,
        65534 to 32766, 262142 to 8190)

    private fun fits(width: Int, height: Int): Boolean =
        capacities.any { (maxWidth, maxHeight) -> width <= maxWidth && height <= maxHeight }
}
