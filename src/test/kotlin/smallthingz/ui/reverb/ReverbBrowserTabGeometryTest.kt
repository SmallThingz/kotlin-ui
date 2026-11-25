package smallthingz.ui.reverb

import org.junit.Assert.*
import org.junit.Test

class ReverbBrowserTabGeometryTest {
    @Test fun shortViewportKeepsTheWholeFirstCardAboveTheDock() {
        // Pixel fixture at 421dpi. Top bar/footer/header are supplied measured dimensions,
        // not an assertion that these host values reproduce Android font measurement.
        val viewport = 782 - 154 - 168 - 42 - 20
        val header = 168
        val height = browserTabPreviewHeight(459, 526, viewport, header)
        assertEquals(230, height)
        assertEquals(viewport, header + height)
        assertTrue("Old width-only preview exceeds this viewport", header + minOf(459, 526) > viewport)
    }

    @Test fun heightOnlyResizeRestoresTallPreviewWithoutAWidthChange() {
        val heights = listOf(1216, 398, 1216).map { browserTabPreviewHeight(459, 526, it, 168) }
        assertEquals(listOf(459, 230, 459), heights)
    }

    @Test fun bottomInsetReducesTheAlreadyAllocatedViewportExactlyOnce() {
        assertEquals(230, browserTabPreviewHeight(459, 526, 398, 168))
        assertEquals(199, browserTabPreviewHeight(459, 526, 367, 168))
    }

    @Test fun largerMeasuredHeaderLeavesLessPreviewWithoutShrinkingActions() {
        assertEquals(130, browserTabPreviewHeight(459, 526, 398, 268))
        assertEquals(0, browserTabPreviewHeight(459, 526, 120, 168))
        assertEquals(0, browserTabPreviewHeight(459, 526, 0, 168))
        // The caller keeps the measured 168px header, making it scrollable in a 120px viewport.
    }

    @Test fun unboundedAndTallHostsRetainTheExistingPreviewLimit() {
        assertEquals(200, browserTabPreviewHeight(360, 200, null, 64))
        assertEquals(144, browserTabPreviewHeight(144, 200, null, 64))
        assertEquals(200, browserTabPreviewHeight(360, 200, 900, 64))
    }

    @Test fun eachGridRowGetsTheViewportBudgetRatherThanDividingItByTabCount() {
        val heights = List(7) { browserTabPreviewHeight(180, 200, 160, 64) }
        assertEquals(List(7) { 96 }, heights)
        assertTrue("Four two-column rows still scroll", 4 * (64 + heights.first()) > 160)
    }

    @Test fun boundedPreviewNeverOverflowsAtOtherDensitiesOrZeroWidth() {
        for (scale in listOf(1, 2, 3)) {
            for (width in listOf(0, 144, 224, 360)) {
                for (viewport in listOf(0, 40, 160, 300, 900)) {
                    for (header in listOf(64, 104)) {
                        val result = browserTabPreviewHeight(width * scale, 200 * scale, viewport * scale, header * scale)
                        assertTrue(result >= 0)
                        assertTrue(result <= minOf(width, 200) * scale)
                        if (viewport >= header) assertTrue(header * scale + result <= viewport * scale)
                        else assertEquals(0, result)
                    }
                }
            }
        }
    }
}
