package smallthingz.ui.geometry

import java.util.Random
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OffsetDistanceBoundaryTest {
    @Test fun axisDistancesSurviveIntermediateOverflowAndUnderflow() {
        for (magnitude in floatArrayOf(Float.MAX_VALUE, 1e20f, 1e-20f, 1e-30f, Float.MIN_VALUE)) {
            for (value in floatArrayOf(magnitude, -magnitude)) {
                assertBits(magnitude, Offset(value, 0f).getDistance())
                assertBits(magnitude, Offset(0f, value).getDistance())
            }
        }
    }

    @Test fun scaledPythagoreanTriplesSurviveBothSquareLimits() {
        for (exponent in intArrayOf(-149, -100, -70, 70, 120)) {
            val unit = Math.scalb(1f, exponent)
            for (signX in floatArrayOf(-1f, 1f)) for (signY in floatArrayOf(-1f, 1f)) {
                assertBits(5f * unit, Offset(signX * 3f * unit, signY * 4f * unit).getDistance())
            }
        }
    }

    @Test fun exceptionalFiniteSquaresMatchIndependentHypotReference() {
        val random = Random(0x0ff5e7L)
        var checked = 0
        repeat(10_000) {
            val x = Float.fromBits(random.nextInt())
            val y = Float.fromBits(random.nextInt())
            if (!x.isFinite() || !y.isFinite()) return@repeat
            val squared = x * x + y * y
            if (squared >= java.lang.Float.MIN_NORMAL && squared.isFinite()) return@repeat
            val expected = Math.hypot(x.toDouble(), y.toDouble()).toFloat()
            val actual = Offset(x, y).getDistance()
            if (expected.isInfinite()) assertBits(expected, actual)
            else assertEquals(expected, actual, Math.ulp(expected))
            checked++
        }
        assertTrue(checked > 1_000)
    }

    @Test fun ordinaryDistancesKeepExactLegacyBits() {
        val random = Random(0x51abL)
        var checked = 0
        repeat(10_000) {
            val x = Float.fromBits(random.nextInt())
            val y = Float.fromBits(random.nextInt())
            val squared = x * x + y * y
            if (!squared.isFinite() || squared < java.lang.Float.MIN_NORMAL) return@repeat
            assertBits(sqrt(squared), Offset(x, y).getDistance())
            checked++
        }
        assertTrue(checked > 1_000)
    }

    @Test fun nonfiniteInputsKeepLegacyCategoriesAndZeroStaysPositive() {
        val values = floatArrayOf(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.fromBits(0x7fc01234), Float.fromBits(0xffc05678.toInt()), -0f, 0f, -1f, 1f)
        for (x in values) for (y in values) {
            if (x.isFinite() && y.isFinite()) continue
            val expected = sqrt(x * x + y * y)
            val actual = Offset(x, y).getDistance()
            if (expected.isNaN()) assertTrue(actual.isNaN()) else assertBits(expected, actual)
        }
        for (x in floatArrayOf(-0f, 0f)) for (y in floatArrayOf(-0f, 0f)) {
            assertBits(0f, Offset(x, y).getDistance())
        }
    }

    @Test fun unrepresentableDistanceAndSquaredDistanceKeepTheirLimits() {
        val huge = Offset(Float.MAX_VALUE, Float.MAX_VALUE)
        assertBits(Float.POSITIVE_INFINITY, huge.getDistance())
        assertBits(Float.POSITIVE_INFINITY, huge.getDistanceSquared())
        assertBits(0f, Offset(Float.MIN_VALUE, 0f).getDistanceSquared())
    }

    private fun assertBits(expected: Float, actual: Float) =
        assertEquals(expected.toRawBits(), actual.toRawBits())
}
