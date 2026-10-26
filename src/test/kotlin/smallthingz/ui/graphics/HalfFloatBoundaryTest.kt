package smallthingz.ui.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HalfFloatBoundaryTest {
    @Test fun finiteOverflowBecomesInfinityWithTheOriginalSign() {
        for (value in floatArrayOf(65536f, 100000f, 1e20f, Float.MAX_VALUE)) {
            assertSignedHalf(0x7c00, value)
            assertEquals(Float.POSITIVE_INFINITY.toRawBits(), halfToFloat(floatToHalf(value)).toRawBits())
            assertEquals(Float.NEGATIVE_INFINITY.toRawBits(), halfToFloat(floatToHalf(-value)).toRawBits())
        }
    }

    @Test fun maximumFiniteAndOverflowRoundingBoundaryRemainCorrect() {
        assertSignedHalf(0x7bff, 65504f)
        assertSignedHalf(0x7bff, Math.nextDown(65520f))
        assertSignedHalf(0x7c00, 65520f)
        assertSignedHalf(0x7c00, Math.nextDown(65536f))
        assertSignedHalf(0x7c00, 65536f)
    }

    @Test fun midpointAndUnderflowRoundingKeepTheExistingHalfUpPolicy() {
        val midpoint = 1.00048828125f
        assertSignedHalf(0x3c00, Math.nextDown(midpoint))
        assertSignedHalf(0x3c01, midpoint)
        assertSignedHalf(0x3c01, Math.nextUp(midpoint))

        val halfMinimumSubnormal = Float.fromBits(0x33000000)
        assertSignedHalf(0x0000, Math.nextDown(halfMinimumSubnormal))
        assertSignedHalf(0x0001, halfMinimumSubnormal)
        assertSignedHalf(0x0001, Float.fromBits(0x33800000))
        assertSignedHalf(0x0400, Float.fromBits(0x38800000))
        assertSignedHalf(0x0000, Float.MIN_VALUE)
    }

    @Test fun zerosInfinitiesAndNaNsKeepTheirSignAndCanonicalEncoding() {
        assertSignedHalf(0x0000, 0f)
        assertSignedHalf(0x7c00, Float.POSITIVE_INFINITY)
        assertEquals(0f.toRawBits(), halfToFloat(0x0000.toShort()).toRawBits())
        assertEquals((-0f).toRawBits(), halfToFloat(0x8000.toShort()).toRawBits())
        for (bits in intArrayOf(0x7f800001, 0x7fc00000, 0x7fffffff)) {
            val positive = Float.fromBits(bits)
            val negative = Float.fromBits(bits or Int.MIN_VALUE)
            assertHalf(0x7e00, positive)
            assertHalf(0xfe00, negative)
            assertTrue(halfToFloat(floatToHalf(positive)).isNaN())
            assertTrue(halfToFloat(floatToHalf(negative)).isNaN())
        }
    }

    private fun assertSignedHalf(positiveBits: Int, value: Float) {
        assertHalf(positiveBits, value)
        assertHalf(positiveBits or 0x8000, -value)
    }

    private fun assertHalf(expectedBits: Int, value: Float) {
        assertEquals("float bits=${value.toRawBits()}", expectedBits,
            floatToHalf(value).toInt() and 0xffff)
    }
}
