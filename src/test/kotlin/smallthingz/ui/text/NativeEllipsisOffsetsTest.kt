package smallthingz.ui.text

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure UTF-16 mapping contracts; these tests do not construct or simulate Android text layout. */
class NativeEllipsisOffsetsTest {
    @Test fun middleEllipsisPreservesPrefixSuffixAndSourceEnd() {
        // Original abcDEFGHIjkl becomes abc…jkl: offsets 3 through 9 surround the removed text.
        val offsets = NativeEllipsisOffsets(3, 9)
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 4, 4, 4, 4, 4, 5, 6, 7),
            IntArray(13) { offsets.toLayoutOffset(it) })
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 9, 10, 11, 12),
            IntArray(8) { offsets.toSourceOffset(it) })
    }

    @Test fun hiddenBidiQueriesAddressEllipsisRatherThanSuffixOrLayoutEnd() {
        val offsets = NativeEllipsisOffsets(3, 9)
        for (source in 3 until 9) assertEquals(3, offsets.toLayoutCharacter(source))
        assertEquals(2, offsets.toLayoutCharacter(2))
        assertEquals(4, offsets.toLayoutCharacter(9))
        assertEquals(6, offsets.toLayoutCharacter(11))
        assertEquals(7, offsets.toLayoutCharacter(12)) // Native end-offset behavior remains available.
    }

    @Test fun onlyFirstHiddenCodeUnitOwnsTheReplacementGlyph() {
        val offsets = NativeEllipsisOffsets(3, 9)
        assertEquals(3, offsets.toLayoutOffset(3))
        assertEquals(4, offsets.toLayoutOffset(4))
        for (source in 4 until 9) {
            assertEquals(4, offsets.toLayoutOffset(source))
            assertEquals(4, offsets.toLayoutOffset(source + 1))
        }
        // Paragraph uses equal caret endpoints as an explicit zero-width box, even at bidi/line edges.
        assertEquals(4, offsets.toLayoutOffset(9))
        assertEquals(5, offsets.toLayoutOffset(10))
    }

    @Test fun startEllipsisHasNoPrefixAndRetainsOriginalSuffixOffsets() {
        val offsets = NativeEllipsisOffsets(0, 5)
        assertEquals(0, offsets.toSourceOffset(0))
        assertEquals(5, offsets.toSourceOffset(1))
        assertEquals(8, offsets.toSourceOffset(4))
        assertEquals(1, offsets.toLayoutOffset(5))
        assertEquals(4, offsets.toLayoutOffset(8))
        for (source in 0 until 5) assertEquals(0, offsets.toLayoutCharacter(source))
    }

    @Test fun missingSuffixAndEntirelyHiddenTextKeepOriginalLineEnd() {
        val noSuffix = NativeEllipsisOffsets(3, 9)
        assertEquals(4, noSuffix.toLayoutOffset(9))
        assertEquals(9, noSuffix.toSourceOffset(4)) // Native line/visible end immediately after ellipsis.
        val allHidden = NativeEllipsisOffsets(0, 9)
        assertEquals(0, allHidden.toSourceOffset(0))
        assertEquals(9, allHidden.toSourceOffset(1))
        assertEquals(1, allHidden.toLayoutOffset(9))
        for (source in 1 until 9) assertEquals(1, allHidden.toLayoutOffset(source))
    }

    @Test fun oneReplacedCodeUnitPreservesEveryOffset() {
        val offsets = NativeEllipsisOffsets(2, 3)
        for (source in 0..5) {
            assertEquals(source, offsets.toLayoutOffset(source))
            assertEquals(source, offsets.toSourceOffset(source))
            assertEquals(source, offsets.toLayoutCharacter(source))
        }
    }

    @Test fun utf16SurrogatesCombiningMarksBidiAndZwjSuffixRemainReversible() {
        val prefix = "A\uD83D\uDE80"
        val hidden = "e\u0301\u05D0\u05D1"
        val suffix = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67Z"
        val source = prefix + hidden + suffix
        val rendered = prefix + "\u2026" + suffix
        val start = prefix.length
        val end = start + hidden.length
        val offsets = NativeEllipsisOffsets(start, end)
        for (index in source.indices) {
            val mapped = offsets.toLayoutCharacter(index)
            assertTrue(mapped in rendered.indices)
            if (index !in start until end) {
                assertEquals(source[index], rendered[mapped])
                assertEquals(index, offsets.toSourceOffset(mapped))
            } else {
                assertEquals('\u2026', rendered[mapped])
            }
        }
        assertEquals(rendered.length, offsets.toLayoutOffset(source.length))
        assertEquals(source.length, offsets.toSourceOffset(rendered.length))
    }

    @Test fun nativeHitAndLineBoundariesExpandIncludingTrimmedVisibleEnd() {
        // A native result before trailing spaces is expanded exactly like every other line/hit result.
        val offsets = NativeEllipsisOffsets(2, 7)
        assertEquals(0, offsets.toSourceOffset(0))
        assertEquals(2, offsets.toSourceOffset(2)) // Hit on leading side of ellipsis.
        assertEquals(7, offsets.toSourceOffset(3)) // Hit on trailing side of ellipsis.
        assertEquals(9, offsets.toSourceOffset(5)) // Native trimmed visible end.
        assertEquals(11, offsets.toSourceOffset(7)) // Native logical line end.
        assertEquals(12, offsets.toSourceOffset(8)) // Next line start after a retained newline.
    }

    @Test fun everySmallIntervalIsBoundedMonotoneAndRoundTripsRetainedOffsets() {
        for (length in 1..20) for (start in 0 until length) for (end in start + 1..length) {
            val offsets = NativeEllipsisOffsets(start, end)
            val renderedLength = length - (end - start) + 1
            var previous = -1
            for (source in 0..length) {
                val mapped = offsets.toLayoutOffset(source)
                assertTrue(mapped in 0..renderedLength)
                assertTrue(mapped >= previous)
                previous = mapped
                if (source <= start || source >= end) {
                    assertEquals(source, offsets.toSourceOffset(mapped))
                } else {
                    assertEquals(end, offsets.toSourceOffset(mapped))
                }
                if (source < length) assertTrue(offsets.toLayoutCharacter(source) in 0 until renderedLength)
            }
            previous = -1
            for (rendered in 0..renderedLength) {
                val source = offsets.toSourceOffset(rendered)
                assertTrue(source in 0..length)
                assertTrue(source >= previous)
                previous = source
                assertEquals(rendered, offsets.toLayoutOffset(source))
            }
        }
    }
}
