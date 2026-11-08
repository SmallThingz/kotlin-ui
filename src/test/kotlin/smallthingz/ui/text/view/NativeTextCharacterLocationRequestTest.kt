package smallthingz.ui.text.view

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeTextCharacterLocationRequestTest {
    @Test fun rejectsMissingNonpositiveAndInvalidStarts() {
        for (length in listOf(-1, 0, Int.MIN_VALUE))
            assertEquals(0, nativeTextCharacterLocationRequestLength(2, 0, length))
        for (start in listOf(-1, 2, Int.MAX_VALUE))
            assertEquals(0, nativeTextCharacterLocationRequestLength(2, start, 3))
        assertEquals(0, nativeTextCharacterLocationRequestLength(0, 0, 1))
    }

    @Test fun preservesRequestedNullTailWithinStandardLimit() {
        assertEquals(3, nativeTextCharacterLocationRequestLength(2, 1, 3))
        assertEquals(20_000, nativeTextCharacterLocationRequestLength(2, 1, 20_000))
    }

    @Test fun boundsAboveStandardLimitWithoutAllocatingCallerLength() {
        assertEquals(20_000, nativeTextCharacterLocationRequestLength(2, 1, 20_001))
        assertEquals(20_000, nativeTextCharacterLocationRequestLength(2, 1, Int.MAX_VALUE))
    }

    @Test fun admitsLastUtf16PositionWithoutAddingIntIndices() {
        assertEquals(20_000, nativeTextCharacterLocationRequestLength(Int.MAX_VALUE,
            Int.MAX_VALUE - 1, Int.MAX_VALUE))
        assertEquals(0, nativeTextCharacterLocationRequestLength(Int.MAX_VALUE,
            Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
