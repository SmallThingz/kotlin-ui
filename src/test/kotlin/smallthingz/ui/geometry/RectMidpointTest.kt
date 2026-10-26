package smallthingz.ui.geometry

import java.math.BigDecimal
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import smallthingz.ui.unit.IntOffset
import smallthingz.ui.unit.IntRect

class RectMidpointTest {
    @Test fun wideIntegerBoundsKeepAllFiveMidpointsInsideTheRectangle() {
        assertIntMidpoints(IntRect(-2_000_000_000, -1_900_000_000, 2_000_000_000, 1_900_000_000), 0, 0)
        assertIntMidpoints(IntRect(2_000_000_000, 1_900_000_000, -2_000_000_000, -1_900_000_000), 0, 0)
    }

    @Test fun fullIntegerRangeRetainsDirectionDependentOddDeltaBias() {
        assertIntMidpoints(IntRect(Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE), -1, 0)
        assertIntMidpoints(IntRect(Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE), 0, -1)
    }

    @Test fun negativeOddAndInvertedIntegerIntervalsKeepLegacyRounding() {
        val cases = arrayOf(intArrayOf(-4, -1, -3), intArrayOf(-1, -4, -2),
            intArrayOf(1, 4, 2), intArrayOf(4, 1, 3), intArrayOf(-2, 1, -1), intArrayOf(1, -2, 0))
        for ((start, end, expected) in cases) {
            assertIntMidpoints(IntRect(start, 7, end, 12), expected, 9)
            assertIntMidpoints(IntRect(7, start, 12, end), 9, expected)
        }
    }

    @Test fun equalIntegerEdgesRemainAtTheOriginalCoordinate() {
        for (value in intArrayOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) {
            assertIntMidpoints(IntRect(value, value, value, value), value, value)
        }
    }

    @Test fun finiteFloatExtremeEndpointsHaveFiniteCentersInBothOrders() {
        val max = Float.MAX_VALUE
        assertFloatMidpoints(Rect(-max, -max, max, max), 0f, 0f)
        assertFloatMidpoints(Rect(max, max, -max, -max), 0f, 0f)
    }

    @Test fun unequalFiniteOverflowSpansKeepTheirActualMidpoint() {
        val max = Float.MAX_VALUE
        assertFloatMidpoints(Rect(-max, -max / 2f, max / 2f, max), -max / 4f, max / 4f)
        assertFloatMidpoints(Rect(max / 2f, max, -max, -max / 2f), -max / 4f, max / 4f)
    }

    @Test fun overflowFallbackMatchesAnExactDecimalMeanAndStaysBounded() {
        val random = Random(0x4d1fL)
        repeat(32) {
            val start = -Float.MAX_VALUE * (.6f + .4f * random.nextFloat())
            val end = Float.MAX_VALUE * (.6f + .4f * random.nextFloat())
            assertTrue((end - start).isInfinite())
            // BigDecimal is test-only; these constructors capture the exact Float values via exact Double conversion.
            val expected = BigDecimal(start.toDouble()).add(BigDecimal(end.toDouble()))
                .divide(BigDecimal.valueOf(2L)).toFloat()
            assertTrue(expected.isFinite() && expected >= start && expected <= end)
            assertFloatMidpoints(Rect(start, end, end, start), expected, expected)
        }
    }

    @Test fun ordinaryIntegerAndFloatPathsMatchPreviousArithmetic() {
        val random = Random(0x4d20L)
        var accepted = 0
        var reorderedDifferences = 0
        while (accepted < 2_000) {
            val start = Float.fromBits(random.nextInt())
            val end = Float.fromBits(random.nextInt())
            if (!start.isFinite() || !end.isFinite() || !(end - start).isFinite()) continue
            val expected = legacyFloatMidpoint(start, end)
            val reverse = legacyFloatMidpoint(end, start)
            if (((start.toDouble() + end.toDouble()) / 2.0).toFloat().toRawBits() != expected.toRawBits()) {
                reorderedDifferences++
            }
            assertFloatMidpoints(Rect(start, end, end, start), expected, reverse)
            var integerStart: Int
            var integerEnd: Int
            do {
                integerStart = random.nextInt()
                integerEnd = random.nextInt()
            } while (integerEnd.toLong() - integerStart.toLong() !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            val integerExpected = integerStart + (integerEnd - integerStart) / 2
            assertIntMidpoints(IntRect(integerStart, integerStart, integerEnd, integerEnd), integerExpected, integerExpected)
            accepted++
        }
        assertTrue("The differential must exercise values changed by an unconditional wider average", reorderedDifferences > 0)
    }

    @Test fun nonfiniteEndpointsKeepLegacyNaNCategoriesAndPayloads() {
        val values = floatArrayOf(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.fromBits(0x7fc01234), Float.fromBits(0xffc05678.toInt()), -1f, -0f, 0f, 1f)
        for (start in values) for (end in values) {
            if (start.isFinite() && end.isFinite()) continue
            val expected = legacyFloatMidpoint(start, end)
            val reverse = legacyFloatMidpoint(end, start)
            assertFloatMidpoints(Rect(start, end, end, start), expected, reverse)
        }
    }

    @Test fun signedZeroAndSubnormalOrdinaryPathsKeepExactBits() {
        val values = floatArrayOf(-0f, 0f, Float.MIN_VALUE, -Float.MIN_VALUE,
            Float.MIN_VALUE * 2f, -Float.MIN_VALUE * 2f)
        for (start in values) for (end in values) {
            assertFloatMidpoints(Rect(start, end, end, start),
                legacyFloatMidpoint(start, end), legacyFloatMidpoint(end, start))
        }
    }

    @Test fun dimensionTypesAndTheirExistingOverflowBehaviorRemainUnchanged() {
        val integer = IntRect(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(-1, integer.width)
        assertEquals(-1, integer.height)
        val floating = Rect(-Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mutable = MutableRect(floating.left, floating.top, floating.right, floating.bottom)
        assertEquals(Float.POSITIVE_INFINITY.toRawBits(), floating.width.toRawBits())
        assertEquals(Float.POSITIVE_INFINITY.toRawBits(), floating.height.toRawBits())
        assertEquals(floating.width.toRawBits(), mutable.width.toRawBits())
        assertEquals(floating.height.toRawBits(), mutable.height.toRawBits())
    }

    private fun legacyFloatMidpoint(start: Float, end: Float): Float = start + (end - start) / 2f

    private fun assertIntMidpoints(rect: IntRect, x: Int, y: Int) {
        assertEquals(IntOffset(x, rect.top), rect.topCenter)
        assertEquals(IntOffset(rect.left, y), rect.centerLeft)
        assertEquals(IntOffset(x, y), rect.center)
        assertEquals(IntOffset(rect.right, y), rect.centerRight)
        assertEquals(IntOffset(x, rect.bottom), rect.bottomCenter)
    }

    private fun assertFloatMidpoints(rect: Rect, x: Float, y: Float) {
        val mutable = MutableRect(rect.left, rect.top, rect.right, rect.bottom)
        assertOffsetBits(x, rect.top, rect.topCenter)
        assertOffsetBits(rect.left, y, rect.centerLeft)
        assertOffsetBits(x, y, rect.center)
        assertOffsetBits(rect.right, y, rect.centerRight)
        assertOffsetBits(x, rect.bottom, rect.bottomCenter)
        assertOffsetBits(x, rect.top, mutable.topCenter)
        assertOffsetBits(rect.left, y, mutable.centerLeft)
        assertOffsetBits(x, y, mutable.center)
        assertOffsetBits(rect.right, y, mutable.centerRight)
        assertOffsetBits(x, rect.bottom, mutable.bottomCenter)
        assertEquals(rect.left.toRawBits(), mutable.left.toRawBits())
        assertEquals(rect.top.toRawBits(), mutable.top.toRawBits())
        assertEquals(rect.right.toRawBits(), mutable.right.toRawBits())
        assertEquals(rect.bottom.toRawBits(), mutable.bottom.toRawBits())
    }

    private fun assertOffsetBits(x: Float, y: Float, actual: Offset) {
        assertEquals("x NaN category", x.isNaN(), actual.x.isNaN())
        assertEquals("y NaN category", y.isNaN(), actual.y.isNaN())
        assertEquals("x raw bits", x.toRawBits(), actual.x.toRawBits())
        assertEquals("y raw bits", y.toRawBits(), actual.y.toRawBits())
    }
}
