package smallthingz.ui.gallery

import org.junit.Assert.*
import org.junit.Test

class GalleryVideoEditorLayoutTest {
    @Test fun headerOnlyStaysPinnedWhenACompleteMeasuredActionFits() {
        assertNull(galleryVideoEditorBodyViewport(96, 80, 56))
        assertNull(galleryVideoEditorBodyViewport(112, 48, 80))
        assertEquals(80, galleryVideoEditorBodyViewport(128, 48, 80))
        assertNull(galleryVideoEditorBodyViewport(48, 48, 56))
        assertNull(galleryVideoEditorBodyViewport(null, 48, 56))
    }

    @Test fun fittingBodyPreservesPreferredPortraitHeight() {
        assertEquals(GalleryVideoEditorPlan(251, 449, false), plan(700, preferred = 251))
    }

    @Test fun veryShortBodyKeepsTransportPlusUsableImageAndFullControls() {
        for (height in listOf(0, 1, 48, 96, 159)) {
            val short = plan(height)
            assertTrue(short.overflow)
            assertEquals(48 + 56, short.previewHeight)
            assertEquals(900, short.controlsHeight)
            assertTrue(short.contentHeight > height)
        }
    }

    @Test fun exactMinimumBoundaryFitsWithoutOverflow() {
        val boundary = plan(160, preferred = 250)
        assertFalse(boundary.overflow)
        assertEquals(104, boundary.previewHeight)
        assertEquals(56, boundary.controlsHeight)
    }

    @Test fun measuredLargeTransportChangesThresholdWithoutFontGuessing() {
        assertFalse(plan(220).overflow)
        assertTrue(plan(220, transport = 120).overflow)
        assertEquals(176, plan(96, transport = 120).previewHeight)
    }

    @Test fun normalOverflowNormalIsStatelessAndRestoresBothSlots() {
        val normal = plan(700)
        assertTrue(plan(96).overflow)
        assertEquals(normal, plan(700))
    }

    @Test fun hiddenPreviewDoesNotReserveTransportOrImage() {
        assertEquals(GalleryVideoEditorPlan(0, 96, false),
            galleryVideoEditorPlan(96, 250, 48, 56, 56, 900, previewVisible = false))
        assertEquals(GalleryVideoEditorPlan(0, 900, true),
            galleryVideoEditorPlan(48, 250, 48, 56, 56, 900, previewVisible = false))
    }

    @Test fun shortIntrinsicControlsNeedOnlyTheirActualHeight() {
        assertFalse(galleryVideoEditorPlan(128, 251, 48, 56, 56, 24).overflow)
        assertTrue(galleryVideoEditorPlan(127, 251, 48, 56, 56, 24).overflow)
    }

    @Test fun unboundedBodyHasNoNestedControlsRange() {
        assertEquals(GalleryVideoEditorPlan(104, 900, false), plan(null))
    }

    private fun plan(height: Int?, preferred: Int = 251, transport: Int = 48) =
        galleryVideoEditorPlan(height, preferred, transport, 56, 56, 900)
}
