package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeDialogEdgeTest {
    @Test fun shortFloatingWindowUsesItsOwnScreenOriginAndTopThird() {
        assertEquals(NativeDialogEdge.TOP, nativeDialogEntryEdge(420, 720, 519, 160))
        assertEquals(NativeDialogEdge.BOTTOM, nativeDialogEntryEdge(420, 720, 520, 160))
        assertEquals(NativeDialogEdge.BOTTOM, nativeDialogEntryEdge(420, 720, 419, 160))
    }

    @Test fun tallWindowCapsTheTopBandAndUnavailableViewportsDefaultToBottom() {
        assertEquals(NativeDialogEdge.TOP, nativeDialogEntryEdge(40, 1040, 199, 160))
        assertEquals(NativeDialogEdge.BOTTOM, nativeDialogEntryEdge(40, 1040, 200, 160))
        assertEquals(NativeDialogEdge.BOTTOM, nativeDialogEntryEdge(40, 40, 40, 160))
    }

    @Test fun resizedFreeformAndKeyboardViewportsUseCurrentHostBounds() {
        assertEquals(NativeDialogEdge.TOP, nativeDialogEntryEdge(800, 920, 839, 160))
        assertEquals(NativeDialogEdge.BOTTOM, nativeDialogEntryEdge(800, 920, 840, 160))
        assertEquals(320, nativeDialogBoundedExtent(1080, 320))
        assertEquals(296, nativeDialogBoundedExtent(296, 320))
        assertEquals(640, nativeDialogBoundedExtent(640, 0))
    }

    @Test fun bottomMirrorsTheSameFloatingVisibilityAndBackMotionSamples() {
        assertEquals(-60f, NativeDialogEdge.TOP.orientTopOffset(-60f), 0f)
        assertEquals(60f, NativeDialogEdge.BOTTOM.orientTopOffset(-60f), 0f)
    }
}
