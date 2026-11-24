package smallthingz.ui.unit

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUnitDoubleScalingTest {
    @Test fun largeDoubleTimesSmallUnitHasARepresentableProduct() {
        assertProduct(Math.scalb(1.0, 150), Math.scalb(1f, -100), Math.scalb(1f, 50))
        assertProduct(-Math.scalb(1.0, 150), Math.scalb(1f, -100), -Math.scalb(1f, 50))
    }

    @Test fun tinyDoubleTimesLargeUnitDoesNotRoundTheScalarToZero() {
        assertProduct(Math.scalb(1.0, -160), Math.scalb(1f, 120), Math.scalb(1f, -40))
        assertProduct(-Math.scalb(1.0, -160), Math.scalb(1f, 120), -Math.scalb(1f, -40))
    }

    @Test fun ordinaryScalarsRoundOnlyAfterMultiplication() {
        for (scalar in doubleArrayOf(1.00000006, -1.00000006, .1, 1.5, 1000.00003)) {
            for (value in floatArrayOf(-31f, -.3f, .3f, 3f, 31f)) {
                val expected = BigDecimal(scalar).multiply(BigDecimal(value.toDouble())).toFloat()
                assertProduct(scalar, value, expected)
            }
        }
    }

    @Test fun bothOrdersKeepSignedZerosAndNonfiniteCategories() {
        val scalars = doubleArrayOf(-0.0, 0.0, -1.0, 1.0, Double.MAX_VALUE,
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN)
        val values = floatArrayOf(-0f, 0f, -1f, 1f, Float.MAX_VALUE,
            Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NaN)
        for (scalar in scalars) for (value in values) {
            assertProduct(scalar, value, (scalar * value).toFloat())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unspecifiedStillRejectsScalarMultiplication() {
        2.0 * TextUnit.Unspecified
    }

    private fun assertProduct(scalar: Double, value: Float, expected: Float) {
        for (type in arrayOf(TextUnitType.Sp, TextUnitType.Em)) {
            val unit = TextUnit(value, type)
            val left = scalar * unit
            val right = unit * scalar
            assertEquals(type, left.type)
            assertEquals(type, right.type)
            for (actual in arrayOf(left, right)) {
                if (expected.isNaN()) assertTrue(actual.value.isNaN())
                else assertEquals(expected.toRawBits(), actual.value.toRawBits())
            }
        }
    }
}
