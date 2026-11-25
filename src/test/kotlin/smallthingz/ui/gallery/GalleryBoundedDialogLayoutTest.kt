package smallthingz.ui.gallery

import org.junit.Assert.*
import org.junit.Test

class GalleryBoundedDialogLayoutTest {
    @Test fun exactParentAllocationIsNotReducedOnRepeatedMeasurement() {
        assertEquals(160, galleryDialogExtent(160, exact = true, fraction = .9f))
        assertEquals(800, galleryDialogExtent(800, exact = true, maximum = 560))
        val first = galleryDialogExtent(160, exact = true, fraction = .9f)
        assertEquals(first, galleryDialogExtent(first, exact = true, fraction = .9f))
    }

    @Test fun atMostRetainsPreferredModalBounds() {
        assertEquals(144, galleryDialogExtent(160, exact = false, fraction = .9f))
        assertEquals(560, galleryDialogExtent(800, exact = false, maximum = 560))
        assertEquals(320, galleryDialogExtent(320, exact = false, maximum = 560))
    }

    @Test fun shortWindowKeepsBodyUsableAndMakesWholePanelScrollable() {
        val plan = galleryBoundedDialogPlan(144, 180, 96)
        assertEquals(96, plan.bodyHeight)
        assertEquals(276, plan.contentHeight)
        assertTrue(plan.contentHeight > 144)
    }

    @Test fun ampleWindowAllocatesRemainderWithoutOuterScroll() {
        val plan = galleryBoundedDialogPlan(640, 180, 96)
        assertEquals(460, plan.bodyHeight)
        assertEquals(640, plan.contentHeight)
    }

    @Test fun exactFitAndOnePixelBelowMinimumSwitchWithoutCollapsingBody() {
        assertEquals(GalleryBoundedDialogPlan(96, 276), galleryBoundedDialogPlan(276, 180, 96))
        assertEquals(GalleryBoundedDialogPlan(96, 276), galleryBoundedDialogPlan(275, 180, 96))
    }

    @Test fun wrappedFooterChangesContentHeightInsteadOfClippingActions() {
        assertEquals(276, galleryBoundedDialogPlan(144, 180, 96).contentHeight)
        assertEquals(348, galleryBoundedDialogPlan(144, 252, 96).contentHeight)
    }

    @Test fun unboundedOrZeroViewportStillHasFiniteBody() {
        assertEquals(GalleryBoundedDialogPlan(96, 276), galleryBoundedDialogPlan(0, 180, 96))
    }

    @Test fun resizingBackRestoresBoundedBodyBudget() {
        val first = galleryBoundedDialogPlan(640, 180, 96)
        galleryBoundedDialogPlan(144, 252, 96)
        assertEquals(first, galleryBoundedDialogPlan(640, 180, 96))
    }
}
