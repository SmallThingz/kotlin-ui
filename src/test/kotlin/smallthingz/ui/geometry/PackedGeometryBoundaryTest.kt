package smallthingz.ui.geometry

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import smallthingz.ui.unit.IntOffset
import smallthingz.ui.unit.IntSize
import smallthingz.ui.unit.Velocity
import smallthingz.ui.unit.center

class PackedGeometryBoundaryTest {
    @Test fun floatLaneBoundariesMatchIndependentScalarPredicates() {
        val magnitudes = intArrayOf(0, 1, 0x007fffff, 0x00800000, 0x3f800000,
            0x7f7fffff, 0x7f800000, 0x7f800001, 0x7fbfffff, 0x7fc00000, 0x7fffffff)
        for (first in magnitudes) for (second in magnitudes) {
            for (signX in intArrayOf(0, Int.MIN_VALUE)) for (signY in intArrayOf(0, Int.MIN_VALUE)) {
                assertPredicates(first or signX, second or signY)
            }
        }
    }

    @Test fun randomPackedWordsDoNotCarryClassificationAcrossLanes() {
        val random = Random(0x5a17L)
        repeat(10_000) { assertPredicates(random.nextInt(), random.nextInt()) }
    }

    @Test fun constructorsAndNegationPreserveFiniteBitsAndQuietNanPayloads() {
        val bits = intArrayOf(0, Int.MIN_VALUE, 1, 0x80000001.toInt(), 0x7f7fffff,
            0x7f800000, 0xff800000.toInt(), 0x7fc01234, 0xffc05678.toInt())
        for (first in bits) for (second in bits) {
            val x = Float.fromBits(first)
            val y = Float.fromBits(second)
            val offset = Offset(x, y)
            val size = Size(x, y)
            val velocity = Velocity(x, y)
            assertEquals(first, offset.x.toRawBits())
            assertEquals(second, offset.y.toRawBits())
            assertEquals(first, size.width.toRawBits())
            assertEquals(second, size.height.toRawBits())
            assertEquals(first, velocity.x.toRawBits())
            assertEquals(second, velocity.y.toRawBits())
            assertEquals(first xor Int.MIN_VALUE, (-offset).x.toRawBits())
            assertEquals(second xor Int.MIN_VALUE, (-offset).y.toRawBits())
            assertEquals(first xor Int.MIN_VALUE, (-velocity).x.toRawBits())
            assertEquals(second xor Int.MIN_VALUE, (-velocity).y.toRawBits())
            assertEquals(offset, -(-offset))
            assertEquals(velocity, -(-velocity))
        }
    }

    @Test fun integerLanesAndCentersRemainIndependentAtSignedLimits() {
        val values = intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, -3, -1, 0, 1, 3, Int.MAX_VALUE)
        for (x in values) for (y in values) {
            val offset = IntOffset(x, y)
            val size = IntSize(x, y)
            assertEquals(x, offset.x)
            assertEquals(y, offset.y)
            assertEquals(x, size.width)
            assertEquals(y, size.height)
            assertEquals(Math.floorDiv(x, 2), size.center.x)
            assertEquals(Math.floorDiv(y, 2), size.center.y)
        }
    }

    @Test fun canonicalSentinelsAndFiniteZeroKeepTheirContracts() {
        assertTrue(Offset.Unspecified.isUnspecified)
        assertFalse(Offset.Unspecified.isValid())
        assertFalse(Offset.Unspecified.isFinite)
        assertTrue(Size.Unspecified.isUnspecified)
        assertTrue(Size.Unspecified.isEmpty())
        assertTrue(Offset.Zero.isSpecified)
        assertTrue(Offset.Zero.isFinite)
        assertTrue(Size.Zero.isSpecified)
        assertTrue(Size.Zero.isEmpty())
        assertEquals(Offset.Zero, Offset.Unspecified.takeOrElse { Offset.Zero })
        assertEquals(Size.Zero, Size.Unspecified.takeOrElse { Size.Zero })
    }

    private fun assertPredicates(first: Int, second: Int) {
        val packed = (first.toLong() shl 32) or (second.toLong() and 0xffffffffL)
        val offset = Offset(packed)
        val x = Float.fromBits(first)
        val y = Float.fromBits(second)
        assertEquals(!x.isNaN() && !y.isNaN(), offset.isValid())
        assertEquals(x.isFinite() && y.isFinite(), offset.isFinite)
    }
}
