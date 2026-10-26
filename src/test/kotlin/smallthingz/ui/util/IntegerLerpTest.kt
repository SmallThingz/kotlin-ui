package smallthingz.ui.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Random
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegerLerpTest {
    @Test
    fun fullIntSpanRetainsEndpointsAndInteriorValues() {
        assertEquals(Int.MIN_VALUE, lerp(Int.MIN_VALUE, Int.MAX_VALUE, 0f))
        assertEquals(Int.MAX_VALUE, lerp(Int.MIN_VALUE, Int.MAX_VALUE, 1f))
        assertEquals(-1073741824, lerp(Int.MIN_VALUE, Int.MAX_VALUE, .25f))
        assertEquals(0, lerp(Int.MIN_VALUE, Int.MAX_VALUE, .5f))
        assertEquals(1073741823, lerp(Int.MIN_VALUE, Int.MAX_VALUE, .75f))
        assertEquals(1073741823, lerp(Int.MAX_VALUE, Int.MIN_VALUE, .25f))
        assertEquals(0, lerp(Int.MAX_VALUE, Int.MIN_VALUE, .5f))
        assertEquals(-1073741824, lerp(Int.MAX_VALUE, Int.MIN_VALUE, .75f))
    }

    @Test
    fun intInterpolationMatchesWideReferenceAcrossTheRange() {
        val random = Random(0x51c0ffee)
        repeat(160) {
            val start = random.nextInt()
            val stop = random.nextInt()
            var previous = start
            for (step in 0..64) {
                val fraction = step / 64f
                val actual = lerp(start, stop, fraction)
                val expected = roundedReference(start.toLong(), stop.toLong(), fraction).intValueExact()
                assertEquals(expected, actual)
                assertTrue(actual >= minOf(start, stop) && actual <= maxOf(start, stop))
                assertTrue(if (start <= stop) actual >= previous else actual <= previous)
                previous = actual
            }
        }
    }

    @Test
    fun intSafeSpansRetainOriginalRoundingAndExtrapolation() {
        val random = Random(0x1a2b3c)
        val fractions = floatArrayOf(-2f, -.5f, -0f, 0f, .25f, .5f, .9f, 1f, 1.5f,
            Float.MIN_VALUE, Float.MAX_VALUE, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
        repeat(2000) {
            val start = random.nextInt()
            val stop = random.nextInt()
            val wide = stop.toLong() - start.toLong()
            if (wide < Int.MIN_VALUE || wide > Int.MAX_VALUE) return@repeat
            for (fraction in fractions) {
                val expected = start + Math.round((stop - start) * fraction.toDouble()).toInt()
                assertEquals(expected, lerp(start, stop, fraction))
            }
        }
    }

    @Test
    fun longEndpointsAreExactEvenWhenDoubleOffsetWouldRoundPastThem() {
        for ((start, stop) in listOf(
            Long.MIN_VALUE to Long.MAX_VALUE,
            Long.MAX_VALUE to Long.MIN_VALUE,
            10L to Long.MAX_VALUE,
            -10L to Long.MIN_VALUE,
            Long.MAX_VALUE - 7L to Long.MAX_VALUE,
            Long.MIN_VALUE + 7L to Long.MIN_VALUE,
        )) {
            assertEquals(start, lerp(start, stop, 0f))
            assertEquals(start, lerp(start, stop, -0f))
            assertEquals(stop, lerp(start, stop, 1f))
        }
    }

    @Test
    fun longWideSpansRemainBoundedMonotonicAndApproximatelySymmetric() {
        val spans = listOf(
            Long.MIN_VALUE to Long.MAX_VALUE,
            (Long.MIN_VALUE + 9999L) to (Long.MAX_VALUE - 5555L),
            -5000000000000000123L to 6000000000000000456L,
        )
        val fractions = floatArrayOf(0f, Float.MIN_VALUE, .0001f, .25f, .5f, .75f, .9999f,
            java.lang.Math.nextDown(1f), 1f)
        for ((low, high) in spans) {
            var previousUp = low
            var previousDown = high
            for (fraction in fractions) {
                val up = lerp(low, high, fraction)
                val down = lerp(high, low, fraction)
                assertTrue(up in low..high)
                assertTrue(down in low..high)
                assertTrue(up >= previousUp)
                assertTrue(down <= previousDown)
                assertWidePrecision(low, high, fraction, up)
                assertWidePrecision(high, low, fraction, down)
                previousUp = up
                previousDown = down
            }
            // Complementary dyadic fractions avoid rounding the Float complement itself.
            for (fraction in floatArrayOf(.25f, .5f, .75f)) {
                assertEquals(lerp(low, high, fraction), lerp(high, low, 1f - fraction))
            }
        }
        assertEquals(0L, lerp(Long.MIN_VALUE, Long.MAX_VALUE, .5f))
        assertEquals(-(1L shl 61), lerp(-(1L shl 62), 1L shl 62, .25f))
        assertEquals(1L shl 61, lerp(-(1L shl 62), 1L shl 62, .75f))
    }

    @Test
    fun safeLongSpansRetainOriginalInteriorAndExtrapolationArithmetic() {
        val random = Random(0x7e57)
        val fractions = floatArrayOf(-2f, -.5f, .125f, .5f, .9f, 1.5f,
            Float.MIN_VALUE, Float.MAX_VALUE, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
        repeat(2000) {
            val start = random.nextLong()
            val stop = random.nextLong()
            val difference = try {
                Math.subtractExact(stop, start)
            } catch (_: ArithmeticException) {
                return@repeat
            }
            for (fraction in fractions) {
                val expected = start + (difference * fraction.toDouble()).roundToLong()
                assertEquals(expected, lerp(start, stop, fraction))
            }
        }
    }

    @Test
    fun wideLongExtrapolationRetainsLegacyOverflowBehavior() {
        for ((start, stop) in listOf(Long.MIN_VALUE to Long.MAX_VALUE, Long.MAX_VALUE to Long.MIN_VALUE)) {
            for (fraction in floatArrayOf(-2f, -.5f, 1.5f, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
                val expected = start + ((stop - start) * fraction.toDouble()).roundToLong()
                assertEquals(expected, lerp(start, stop, fraction))
            }
        }
    }

    @Test
    fun nanKeepsEachOverloadsExistingContract() {
        assertEquals(37, lerp(37, -19, Float.NaN))
        assertEquals(Int.MIN_VALUE, lerp(Int.MIN_VALUE, Int.MAX_VALUE, Float.NaN))
        for ((start, stop) in listOf(37L to -19L, Long.MIN_VALUE to Long.MAX_VALUE)) {
            var rejected = false
            try {
                lerp(start, stop, Float.NaN)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
        }
    }

    private fun roundedReference(start: Long, stop: Long, fraction: Float): BigDecimal =
        BigDecimal.valueOf(start).add(
            BigDecimal.valueOf(stop).subtract(BigDecimal.valueOf(start))
                .multiply(BigDecimal.valueOf(fraction.toDouble()))
        ).add(BigDecimal("0.5")).setScale(0, RoundingMode.FLOOR)

    private fun assertWidePrecision(start: Long, stop: Long, fraction: Float, actual: Long) {
        val error = BigDecimal.valueOf(actual).subtract(roundedReference(start, stop, fraction)).abs()
        assertTrue("Large-span Double rounding error: $error", error <= BigDecimal.valueOf(4096L))
    }
}
