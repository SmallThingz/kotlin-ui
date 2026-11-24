package smallthingz.ui.reverb

import android.view.Gravity
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test

class ReverbRequestWindowPlacementTest {
    @Test fun narrowFramesRemainPositiveAndWithinTheAvailableWidth() {
        for (density in listOf(.75f, 1f, 2.625f, 4f)) {
            for (width in listOf(1, 20, 96, 240, 320, 1080, 10000)) {
                val placement = ReverbRequestWindowPlacement.forAvailableWidth(width, density)
                assertTrue(placement.width in 1..width)
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, placement.height)
                assertEquals(Gravity.TOP or Gravity.CENTER_HORIZONTAL, placement.gravity)
                assertTrue(placement.y >= 0)
            }
        }
    }

    @Test fun exteriorInsetsAndWidthCapFollowTheSharedPermissionRecipe() {
        val recipe = ReverbDesign.appearance.modals.permissionDialog
        val narrow = ReverbRequestWindowPlacement.forAvailableWidth(320, 1f)
        assertEquals(320 - 2 * recipe.windowInsetDp, narrow.width)
        assertEquals(recipe.windowInsetDp, narrow.y)
        val wide = ReverbRequestWindowPlacement.forAvailableWidth(Int.MAX_VALUE, 1f)
        assertEquals(recipe.maxWidthDp, wide.width)
    }

    @Test fun oversizedDensityDoesNotWrapTheWidthNegative() {
        val placement = ReverbRequestWindowPlacement.forAvailableWidth(320, Float.MAX_VALUE)
        assertEquals(1, placement.width)
        assertTrue(placement.y >= 0)
    }

    @Test fun unavailableFrameOrInvalidDensityIsRejectedBeforeWindowAdmission() {
        for (width in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) {
                ReverbRequestWindowPlacement.forAvailableWidth(width, 1f)
            }
        }
        for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                ReverbRequestWindowPlacement.forAvailableWidth(320, density)
            }
        }
    }
}
