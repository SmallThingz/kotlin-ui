package smallthingz.ui.gallery

import org.junit.Assert.*
import org.junit.Test

class GalleryEditorLayoutTest {
    @Test fun narrowLandscapeStacksInsteadOfSqueezingTwoPanes() {
        val plan = galleryEditorPlan(400, 280, 250, 120, 1f)
        assertFalse(plan.sideBySide)
        assertEquals(400, plan.controls.width)
        assertEquals(56, plan.preview.height)
        assertEquals(224, plan.controls.height)
    }

    @Test fun usableWidthIncludesPhotoEdgesAndBoundsSidebarGrowth() {
        assertEquals(0, galleryEditorPanelWidth(487, 280, 1f))
        assertEquals(240, galleryEditorPanelWidth(488, 280, 1f))
        assertEquals(320, galleryEditorPanelWidth(1600, 700, 1f))
        assertEquals(0, galleryEditorPanelWidth(800, 1200, 1f))
    }

    @Test fun resizingSameHostRecomputesArrangementInBothDirections() {
        val wide = galleryEditorPlan(800, 320, 300, 160, 1f)
        val narrow = galleryEditorPlan(360, 320, 300, 160, 1f)
        assertTrue(wide.sideBySide)
        assertFalse(narrow.sideBySide)
        assertEquals(wide, galleryEditorPlan(800, 320, 300, 160, 1f))
    }

    @Test fun expandedFooterTakesPriorityOverPhotoInVeryShortHost() {
        val plan = galleryEditorPlan(360, 180, 300, 160, 1f)
        assertEquals(20, plan.preview.height)
        assertEquals(160, plan.controls.height)
        val shorter = galleryEditorPlan(360, 100, 300, 160, 1f)
        assertEquals(0, shorter.preview.height)
        assertEquals(100, shorter.controls.height)
    }

    @Test fun shortControlsReturnUnusedSpaceToPreview() {
        val plan = galleryEditorPlan(360, 600, 100, 100, 1f)
        assertEquals(500, plan.preview.height)
        assertEquals(100, plan.controls.height)
    }

    @Test fun logicalEndSidebarMirrorsWithoutChangingPaneSizes() {
        val ltr = galleryEditorPlan(800, 320, 300, 100, 1f)
        val rtl = galleryEditorPlan(800, 320, 300, 100, 1f, rtl = true)
        assertEquals(ltr.controls.width, rtl.controls.width)
        assertEquals(ltr.preview.width, rtl.preview.width)
        assertEquals(0, rtl.controls.left)
        assertEquals(rtl.controls.right, rtl.preview.left)
        assertEquals(800, rtl.preview.right)
    }

    @Test fun fixedSidebarDoesNotMoveWhenHealControlsChangeHeight() {
        assertEquals(galleryEditorPlan(800, 320, 300, 100, 1f),
            galleryEditorPlan(800, 320, 100, 100, 1f))
    }

    @Test fun densityAndDegenerateHostsKeepBoundsInsideAvailableSpace() {
        for (density in listOf(1f, 1.5f, 3f)) for (width in listOf(0, 1, 360, 800, 2400)) {
            for (height in listOf(0, 1, 180, 600)) for (rtl in listOf(false, true)) {
                val plan = galleryEditorPlan(width, height, 600, 200, density, rtl)
                for (bounds in listOf(plan.preview, plan.controls)) {
                    assertTrue(bounds.left >= 0 && bounds.top >= 0)
                    assertTrue(bounds.width >= 0 && bounds.height >= 0)
                    assertTrue(bounds.right <= width && bounds.bottom <= height)
                }
                assertEquals(width.toLong() * height,
                    plan.preview.width.toLong() * plan.preview.height + plan.controls.width.toLong() * plan.controls.height)
            }
        }
        assertEquals(608, galleryEditorPlan(1600, 640, 600, 200, 2f).controls.width)
    }
}
