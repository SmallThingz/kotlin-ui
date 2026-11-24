package smallthingz.ui.reverb

import org.junit.Assert.*
import org.junit.Test

class ReverbBrowserPanelSwipeTest {
    @Test fun verticalScrollAndLongPressDoNotCloseTabs() {
        val pending = ReverbBrowserPanelSwipePhase.PENDING
        assertEquals(ReverbBrowserPanelSwipePhase.IDLE, pending.move(12f, 30f, 8f, true, true))
        assertEquals(ReverbBrowserPanelSwipePhase.IDLE, pending.move(50f, 0f, 8f, false, true))
        assertEquals(0, pending.release(500f, 1000f, 0f, 100f, 8f, 350f, true))
    }
    @Test fun retiredGestureCannotResumeAfterReadmission() {
        val dragging = ReverbBrowserPanelSwipePhase.PENDING.move(30f, 1f, 8f, true, true)
        assertEquals(ReverbBrowserPanelSwipePhase.DRAGGING, dragging)
        val retired = dragging.move(50f, 1f, 8f, true, false)
        assertTrue(retired.ownsTouch)
        assertEquals(retired, retired.move(200f, 1f, 8f, true, true))
        assertEquals(0, retired.release(200f, 1000f, 0f, 100f, 8f, 350f, true))
    }
    @Test fun closeRequiresDistanceOrAnIntentionalHorizontalFling() {
        val phase = ReverbBrowserPanelSwipePhase.DRAGGING
        assertEquals(1, phase.release(100f, 0f, 0f, 100f, 8f, 350f, true))
        assertEquals(-1, phase.release(-30f, -500f, 10f, 100f, 8f, 350f, true))
        assertEquals(0, phase.release(30f, -500f, 10f, 100f, 8f, 350f, true))
        assertEquals(0, phase.release(30f, 500f, 500f, 100f, 8f, 350f, true))
        assertEquals(0, phase.release(10f, 1000f, 0f, 100f, 8f, 350f, true))
    }
    @Test fun releaseRechecksAdmissionEvenAfterSuccessfulDrag() {
        assertEquals(0, ReverbBrowserPanelSwipePhase.DRAGGING.release(200f, 1000f, 0f, 100f, 8f, 350f, false))
    }
}
