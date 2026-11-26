package smallthingz.ui.components

import org.junit.Assert.*
import org.junit.Test

class NativeApplicationGridRecipeTest {
    @Test fun phoneWidthsRestoreFourColumnsAndOriginalRowHeight() {
        for (width in listOf(320f, 328f, 388f)) {
            assertEquals(4, NativeApplicationGridSizing.columns(width, 1f))
        }
        assertEquals(88f, NativeApplicationGridSizing.heightDp(1f), 0f)
    }

    @Test fun compactWidthsRespectMinimumCellWidthAndTouchTargets() {
        for ((width, expected) in listOf(48f to 1, 127f to 1, 128f to 1, 159f to 1, 160f to 2,
                192f to 2, 240f to 3, 256f to 3, 319f to 3, 320f to 4,
                399f to 4, 400f to 5, 448f to 5, 480f to 6)) {
            val columns = NativeApplicationGridSizing.columns(width, 1f)
            assertEquals(expected, columns)
            assertTrue(width / columns >= 48f)
            if (columns > 1) assertTrue(width / columns >= NativeApplicationGridSizing.cellMinWidthDp)
        }
        assertEquals(1, NativeApplicationGridSizing.columns(0f, 1f))
        assertEquals(6, NativeApplicationGridSizing.columns(1200f, 1f))
    }

    @Test fun largeFontsTradeColumnsForLabelRoomAndIncreaseHeight() {
        assertEquals(2, NativeApplicationGridSizing.columns(328f, 2f))
        assertEquals(2, NativeApplicationGridSizing.columns(388f, 2f))
        assertEquals(1, NativeApplicationGridSizing.columns(128f, 2f))
        assertEquals(176f, NativeApplicationGridSizing.heightDp(2f), 0f)
        for (width in listOf(320f, 328f, 388f)) {
            assertTrue(width / NativeApplicationGridSizing.columns(width, 2f) >= 160f)
        }
    }

    @Test fun smallFontsDoNotShrinkTheTouchOrLabelBudget() {
        for (width in listOf(128f, 328f, 388f)) {
            assertEquals(NativeApplicationGridSizing.columns(width, 1f),
                NativeApplicationGridSizing.columns(width, .85f))
        }
        assertEquals(88f, NativeApplicationGridSizing.heightDp(.85f), 0f)
    }
}
