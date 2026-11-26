package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDropdownFlingPathTest {
    @Test fun tinyNaturalCoastNeverRoundsBehindTheReleasePosition() {
        assertEquals(3, nativeDropdownFlingTarget(2.1f, 1, 60, 8, 100f))
        assertEquals(2, nativeDropdownFlingTarget(2.9f, -1, 60, 8, -100f))
        assertEquals(3, nativeDropdownFlingTarget(3f, 1, 60, 8, 100f))
        assertEquals(3, nativeDropdownFlingTarget(3f, -1, 60, 8, -100f))
    }

    @Test fun naturalPredictionUsesFractionalReleaseAndClampsLegalRows() {
        assertEquals(4, nativeDropdownFlingTarget(2.25f, 90, 60, 8, 500f))
        assertEquals(1, nativeDropdownFlingTarget(2.25f, -60, 60, 8, -500f))
        assertEquals(8, nativeDropdownFlingTarget(2.25f, Int.MAX_VALUE, 60, 8, 500f))
        assertEquals(0, nativeDropdownFlingTarget(2.25f, Int.MIN_VALUE, 60, 8, -500f))
        assertEquals(0, nativeDropdownFlingTarget(0f, -500, 60, 8, -500f))
        assertEquals(8, nativeDropdownFlingTarget(8f, 500, 60, 8, 500f))
        assertEquals(0, nativeDropdownFlingTarget(0f, 500, 60, 0, 500f))
        assertEquals(0, nativeDropdownFlingTarget(-2f, 0, 60, 8, 0f))
        assertEquals(8, nativeDropdownFlingTarget(12f, 0, 60, 8, 0f))
        assertEquals(3, nativeDropdownFlingTarget(2.5f, 0, 60, 8, 0f))
    }

    @Test fun invalidGeometryCannotCreateANonFiniteOrUnrepresentableTarget() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull(nativeDropdownFlingTarget(value, 20, 60, 8, 100f))
            assertNull(nativeDropdownFlingTarget(2f, 20, 60, 8, value))
            assertThrows(IllegalArgumentException::class.java) { NativeDropdownFlingPath(value, 3, 0, 20) }
        }
        assertNull(nativeDropdownFlingTarget(2f, 20, 0, 8, 100f))
        assertNull(nativeDropdownFlingTarget(2f, 20, -1, 8, 100f))
        assertNull(nativeDropdownFlingTarget(2f, 20, 60, -1, 100f))
        assertNull(nativeDropdownFlingTarget(0f, Int.MAX_VALUE, 1, Int.MAX_VALUE, 100f))
        assertThrows(IllegalArgumentException::class.java) { NativeDropdownFlingPath(0f, Int.MAX_VALUE, 0, 20) }
        assertThrows(IllegalArgumentException::class.java) { nativeDropdownFlingNeedsScroll(0f, 1, 20, 0) }
    }

    @Test fun forcedExpansionSelectsOneReleaseScrollInsteadOfAmplifyingTinyFling() {
        assertTrue(nativeDropdownFlingNeedsScroll(2.01f, 3, 1, 60))
        assertTrue(nativeDropdownFlingNeedsScroll(2.99f, 2, -1, 60))
        assertTrue(nativeDropdownFlingNeedsScroll(2.25f, 3, 0, 60))
        assertFalse(nativeDropdownFlingNeedsScroll(3f, 3, 0, 60))
        assertFalse(nativeDropdownFlingNeedsScroll(1f, 2, 30, 60)) // Exactly 2x is bounded shaping.
        assertTrue(nativeDropdownFlingNeedsScroll(1f, 2, 29, 60))
        assertFalse(nativeDropdownFlingNeedsScroll(2f, 1, -30, 60))
        assertTrue(nativeDropdownFlingNeedsScroll(2f, 1, -29, 60))
        assertFalse(nativeDropdownFlingNeedsScroll(2f, 0, Int.MIN_VALUE, 60))
    }

    @Test fun mapsOneNativeProfileWithoutLosingFractionalStartOrChangingTarget() {
        val path = NativeDropdownFlingPath(2.25f, 5, 100, 1_100)
        assertEquals(5, path.targetRow)
        assertEquals(2.25f, path.positionAt(100), 0f)
        assertEquals(2.9375f, path.positionAt(350), 0f)
        assertEquals(3.625f, path.positionAt(600), 0f)
        assertEquals(5f, path.positionAt(1_100), 0f)
        assertEquals(5f, path.positionAt(1_200), 0f)
        assertEquals(2.25f, path.positionAt(0), 0f)

        val reverse = NativeDropdownFlingPath(5.75f, 2, 100, -900)
        assertEquals(5.75f, reverse.positionAt(100), 0f)
        assertEquals(3.875f, reverse.positionAt(-400), 0f)
        assertEquals(2f, reverse.positionAt(-900), 0f)
        assertEquals(2f, reverse.positionAt(-1_000), 0f)
    }

    @Test fun everyMonotoneNativeSampleStaysBetweenReleaseAndTheFixedRow() {
        for (start in listOf(.25f, 2.1f, 4.9f)) for (direction in listOf(-1, 1)) {
            val nativeEnd = direction * 1_000
            val target = requireNotNull(nativeDropdownFlingTarget(start, nativeEnd, 60, 6, direction * 500f))
            val path = NativeDropdownFlingPath(start, target, 0, nativeEnd)
            var previous = start
            for (step in 0..100) {
                val next = path.positionAt(direction * step * 10)
                assertTrue(next.isFinite() && next >= minOf(start, target.toFloat()) &&
                    next <= maxOf(start, target.toFloat()))
                assertTrue(if (direction > 0) next >= previous else next <= previous)
                previous = next
            }
            assertEquals(target.toFloat(), previous, 0f)
            assertEquals(target, path.targetRow)
        }
    }

    @Test fun nativeCoordinateSubtractionCannotOverflowAndStationaryPathNeedsNoFrames() {
        val wide = NativeDropdownFlingPath(1f, 7, Int.MIN_VALUE, Int.MAX_VALUE)
        assertEquals(1f, wide.positionAt(Int.MIN_VALUE), 0f)
        assertEquals(4f, wide.positionAt(0), 0f)
        assertEquals(7f, wide.positionAt(Int.MAX_VALUE), 0f)
        val stationary = NativeDropdownFlingPath(3f, 3, 0, 0)
        assertEquals(3f, stationary.positionAt(0), 0f)
        assertThrows(IllegalArgumentException::class.java) { NativeDropdownFlingPath(2.25f, 3, 0, 0) }
    }
}
