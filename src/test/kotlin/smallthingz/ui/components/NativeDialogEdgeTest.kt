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

    @Test fun bottomMirrorsTheSameFloatingVisibilityAndBackMotionSamples() {
        assertEquals(-60f, NativeDialogEdge.TOP.orientTopOffset(-60f))
        assertEquals(60f, NativeDialogEdge.BOTTOM.orientTopOffset(-60f))
    }
}
