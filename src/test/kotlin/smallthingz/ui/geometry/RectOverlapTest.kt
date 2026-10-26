package smallthingz.ui.geometry

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test
import smallthingz.ui.unit.IntRect

class RectOverlapTest {
    @Test fun zeroWidthInsideAnotherRectangleHasNoArea() {
        assertIntegerAndFloatOverlap(false, IntRect(2, 1, 2, 3), IntRect(0, 0, 4, 4))
    }

    @Test fun zeroHeightInsideAnotherRectangleHasNoArea() {
        assertIntegerAndFloatOverlap(false, IntRect(1, 2, 3, 2), IntRect(0, 0, 4, 4))
    }

    @Test fun pointsAndZeroRectanglesHaveNoArea() {
        assertIntegerAndFloatOverlap(false, IntRect(2, 2, 2, 2), IntRect(0, 0, 4, 4))
        assertIntegerAndFloatOverlap(false, IntRect.Zero, IntRect(-1, -1, 1, 1))
        assertIntegerAndFloatOverlap(false, IntRect.Zero, IntRect.Zero)
    }

    @Test fun invertedAxesDoNotBecomePositiveAreaWhenContained() {
        val outer = IntRect(0, 0, 4, 4)
        assertIntegerAndFloatOverlap(false, IntRect(3, 1, 1, 3), outer)
        assertIntegerAndFloatOverlap(false, IntRect(1, 3, 3, 1), outer)
        assertIntegerAndFloatOverlap(false, IntRect(3, 3, 1, 1), outer)
    }

    @Test fun touchingEdgesAndCornersDoNotOverlap() {
        val first = IntRect(0, 0, 2, 2)
        assertIntegerAndFloatOverlap(false, first, IntRect(2, 0, 4, 2))
        assertIntegerAndFloatOverlap(false, first, IntRect(0, 2, 2, 4))
        assertIntegerAndFloatOverlap(false, first, IntRect(2, 2, 4, 4))
    }

    @Test fun containmentPartialOverlapIdentityAndDisjointRemainCorrect() {
        val first = IntRect(0, 0, 4, 4)
        assertIntegerAndFloatOverlap(true, first, IntRect(1, 1, 3, 3))
        assertIntegerAndFloatOverlap(true, first, IntRect(3, 3, 5, 5))
        assertIntegerAndFloatOverlap(true, first, first)
        assertIntegerAndFloatOverlap(false, first, IntRect(5, 1, 6, 3))
        assertIntegerAndFloatOverlap(false, first, IntRect(1, 5, 3, 6))
    }

    @Test fun integerExtremeCoordinatesDoNotOverflowAreaChecks() {
        val outer = IntRect(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        assertIntegerOverlap(true, outer, outer)
        assertIntegerOverlap(true, outer, IntRect(-1, -1, 1, 1))
        assertIntegerOverlap(true, outer, IntRect(Int.MIN_VALUE, 0, Int.MIN_VALUE + 1, 1))
        assertIntegerOverlap(true, outer, IntRect(Int.MAX_VALUE - 1, 0, Int.MAX_VALUE, 1))
        assertIntegerOverlap(false, outer, IntRect(0, Int.MIN_VALUE, 0, Int.MAX_VALUE))
        assertIntegerOverlap(false, outer, IntRect(Int.MAX_VALUE, 0, Int.MIN_VALUE, 1))
        assertIntegerOverlap(false, IntRect(Int.MIN_VALUE, 0, 0, 1), IntRect(0, 0, Int.MAX_VALUE, 1))
    }

    @Test fun boundedGridMatchesSharedUnitCellOccupancy() {
        val rectangles = buildList {
            for (left in -1..1) for (top in -1..1) for (right in -1..1) for (bottom in -1..1) {
                add(IntRect(left, top, right, bottom))
            }
        }
        for (first in rectangles) for (second in rectangles) {
            // Independent discrete oracle: positive overlap exists iff a unit cell belongs to both.
            var sharedCell = false
            for (x in -1..0) for (y in -1..0) {
                if (x >= first.left && x < first.right && y >= first.top && y < first.bottom &&
                    x >= second.left && x < second.right && y >= second.top && y < second.bottom) {
                    sharedCell = true
                }
            }
            assertIntegerAndFloatOverlap(sharedCell, first, second)
        }
    }

    @Test fun positiveFiniteRectanglesKeepPreviousCrossEdgeResults() {
        val random = Random(0x51a7L)
        fun coordinate(): Float = (random.nextFloat() - .5f) * 2_000_000f
        fun rectangle(): Rect {
            var left = coordinate()
            var right = coordinate()
            if (left > right) { val swap = left; left = right; right = swap }
            if (left == right) right = Math.nextUp(right)
            var top = coordinate()
            var bottom = coordinate()
            if (top > bottom) { val swap = top; top = bottom; bottom = swap }
            if (top == bottom) bottom = Math.nextUp(bottom)
            return Rect(left, top, right, bottom)
        }
        repeat(2_000) {
            val first = rectangle()
            val second = rectangle()
            val previous = first.left < second.right && second.left < first.right &&
                first.top < second.bottom && second.top < first.bottom
            assertFloatOverlap(previous, first, second)
        }
    }

    @Test fun nanInEitherRectangleRejectsEveryFloatingOverload() {
        val finite = Rect(-2f, -2f, 2f, 2f)
        for (nan in floatArrayOf(Float.NaN, Float.fromBits(0xffc01234.toInt()))) {
            for (edge in 0..3) {
                val bounds = floatArrayOf(-1f, -1f, 1f, 1f)
                bounds[edge] = nan
                val invalid = Rect(bounds[0], bounds[1], bounds[2], bounds[3])
                assertFloatOverlap(false, invalid, finite)
                assertFloatOverlap(false, invalid, invalid)
            }
        }
    }

    @Test fun orderedInfiniteExtentsRemainSupported() {
        val all = Rect(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY,
            Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        assertFloatOverlap(true, all, all)
        assertFloatOverlap(true, all, Rect(-1f, -1f, 1f, 1f))
        assertFloatOverlap(true, Rect(0f, 0f, Float.POSITIVE_INFINITY, 1f),
            Rect(1f, 0f, Float.POSITIVE_INFINITY, 1f))
        assertFloatOverlap(false, Rect(Float.NEGATIVE_INFINITY, -1f, 0f, 1f),
            Rect(0f, -1f, Float.POSITIVE_INFINITY, 1f))
    }

    @Test fun infiniteEmptyOrInvertedExtentsHaveNoArea() {
        val all = Rect(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY,
            Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        assertFloatOverlap(false, Rect(Float.POSITIVE_INFINITY, -1f, Float.POSITIVE_INFINITY, 1f), all)
        assertFloatOverlap(false, Rect(Float.NEGATIVE_INFINITY, -1f, Float.NEGATIVE_INFINITY, 1f), all)
        assertFloatOverlap(false, Rect(-1f, Float.POSITIVE_INFINITY, 1f, Float.POSITIVE_INFINITY), all)
        assertFloatOverlap(false, Rect(Float.POSITIVE_INFINITY, -1f, Float.NEGATIVE_INFINITY, 1f), all)
    }

    @Test fun signedZeroDoesNotCreateAreaOrSeparateTouchingEdges() {
        val outer = Rect(-1f, -1f, 1f, 1f)
        assertFloatOverlap(false, Rect(-0f, -1f, 0f, 1f), outer)
        assertFloatOverlap(false, Rect(0f, -1f, -0f, 1f), outer)
        assertFloatOverlap(false, Rect(-1f, -0f, 1f, 0f), outer)
        assertFloatOverlap(false, Rect(-1f, -1f, -0f, 1f), Rect(0f, -1f, 1f, 1f))
        assertFloatOverlap(true, Rect(-0f, -0f, 1f, 1f), Rect(0f, 0f, 1f, 1f))
    }

    @Test fun positiveSubnormalExtentsDoNotRequireRepresentableAreaProduct() {
        val tiny = Rect(0f, 0f, Float.MIN_VALUE, Float.MIN_VALUE)
        assertFloatOverlap(true, tiny, tiny)
        assertFloatOverlap(false, tiny, Rect(Float.MIN_VALUE, 0f, Float.MIN_VALUE * 2, Float.MIN_VALUE))
        val huge = Rect(-Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        assertFloatOverlap(true, huge, tiny)
    }

    private fun assertIntegerAndFloatOverlap(expected: Boolean, first: IntRect, second: IntRect) {
        assertIntegerOverlap(expected, first, second)
        assertFloatOverlap(expected,
            Rect(first.left.toFloat(), first.top.toFloat(), first.right.toFloat(), first.bottom.toFloat()),
            Rect(second.left.toFloat(), second.top.toFloat(), second.right.toFloat(), second.bottom.toFloat()))
    }

    private fun assertIntegerOverlap(expected: Boolean, first: IntRect, second: IntRect) {
        assertEquals("IntRect forward: $first / $second", expected, first.overlaps(second))
        assertEquals("IntRect reverse: $second / $first", expected, second.overlaps(first))
    }

    private fun assertFloatOverlap(expected: Boolean, first: Rect, second: Rect) {
        val mutableFirst = MutableRect(first.left, first.top, first.right, first.bottom)
        val mutableSecond = MutableRect(second.left, second.top, second.right, second.bottom)
        assertEquals("Rect forward: $first / $second", expected, first.overlaps(second))
        assertEquals("Rect reverse: $second / $first", expected, second.overlaps(first))
        assertEquals("MutableRect/Rect forward", expected, mutableFirst.overlaps(second))
        assertEquals("MutableRect/Rect reverse", expected, mutableSecond.overlaps(first))
        assertEquals("MutableRect/MutableRect forward", expected, mutableFirst.overlaps(mutableSecond))
        assertEquals("MutableRect/MutableRect reverse", expected, mutableSecond.overlaps(mutableFirst))
        assertUnchanged(first, mutableFirst)
        assertUnchanged(second, mutableSecond)
    }

    private fun assertUnchanged(expected: Rect, actual: MutableRect) {
        assertEquals(expected.left.toRawBits(), actual.left.toRawBits())
        assertEquals(expected.top.toRawBits(), actual.top.toRawBits())
        assertEquals(expected.right.toRawBits(), actual.right.toRawBits())
        assertEquals(expected.bottom.toRawBits(), actual.bottom.toRawBits())
    }
}
