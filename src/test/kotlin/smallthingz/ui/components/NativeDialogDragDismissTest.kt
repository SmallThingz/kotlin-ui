package smallthingz.ui.components

import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_UP
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeDialogDragDismissTest {
    private var dismissals = 0
    private val drag = NativeDialogDragDismiss(24f) { dismissals++ }

    @Test fun downwardReleaseCommitsOnce() {
        send(ACTION_DOWN)
        send(ACTION_MOVE, x = 11f, y = 80f)
        assertEquals(0, dismissals)
        send(ACTION_UP, x = 12f, y = 84f)
        send(ACTION_UP, x = 12f, y = 84f)
        assertEquals(1, dismissals)
    }

    @Test fun shortUpwardAndHorizontalReleasesAreIgnored() {
        send(ACTION_DOWN)
        send(ACTION_UP, y = 34f)
        send(ACTION_DOWN)
        send(ACTION_UP, y = -40f)
        send(ACTION_DOWN)
        send(ACTION_UP, x = 110f, y = 60f)
        assertEquals(0, dismissals)
    }

    @Test fun interruptionInvalidatesTheCurrentDragOnly() {
        send(ACTION_DOWN)
        send(ACTION_CANCEL, y = 90f)
        send(ACTION_UP, y = 90f)

        send(ACTION_DOWN)
        send(ACTION_MOVE, y = 40f, obscured = true)
        send(ACTION_UP, y = 90f)

        send(ACTION_DOWN)
        send(ACTION_UP, y = 90f, obscured = true)

        send(ACTION_DOWN)
        send(ACTION_POINTER_DOWN, y = 40f)
        send(ACTION_POINTER_UP, y = 60f)
        send(ACTION_UP, y = 90f)
        assertEquals(0, dismissals)

        send(ACTION_DOWN)
        send(ACTION_UP, y = 90f)
        assertEquals(1, dismissals)
    }

    private fun send(action: Int, x: Float = 10f, y: Float = 15f, obscured: Boolean = false) {
        drag.handle(action, obscured, x, y)
    }
}
