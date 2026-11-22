package smallthingz.ui.input.phase

import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.NativeAndroidInteropRequests
import smallthingz.ui.input.pointer.PointerType

class NativeAndroidInteropNodeCloseTest {
    @Test fun closeDuringDownCancelsAfterViewReturns() = checkClose(
        MotionEvent.ACTION_DOWN, listOf("down", "returned", "cancel"),
    )

    @Test fun closeDuringUpDoesNotCancelCompletedStream() = checkClose(
        MotionEvent.ACTION_UP, listOf("down", "up", "returned"),
    )

    private fun checkClose(closeOn: Int, expected: List<String>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            lateinit var node: NativeAndroidInteropNode
            val calls = mutableListOf<String>()
            val view = object : View(instrumentation.targetContext) {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    calls += when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> "down"
                        MotionEvent.ACTION_UP -> "up"
                        MotionEvent.ACTION_CANCEL -> "cancel"
                        else -> error("Unexpected action")
                    }
                    if (event.actionMasked == closeOn) {
                        node.close()
                        node.close()
                        // An enclosing Android ViewGroup still uses this local event after the click.
                        event.offsetLocation(1f, 0f)
                        assertEquals(closeOn, event.actionMasked)
                        calls += "returned"
                    }
                    return true
                }
            }
            node = NativeAndroidInteropNode(view, NativeAndroidInteropRequests())
            val coordinates = NativePointerCoordinates(Matrix(), Matrix(), Offset.Zero)
            fun send(action: Int) {
                val down = action == MotionEvent.ACTION_DOWN
                val frame = NativeDragPointerEvent(listOf(NativeDragPointerChange(
                    1, 10, Offset.Zero, Offset.Zero, down, !down, PointerType.Touch, Offset.Zero,
                )), Offset.Zero)
                val motion = MotionEvent.obtain(1, 10, action, 0f, 0f, 0)
                try {
                    node.bindMotion(motion, coordinates, 1)
                    NativePointerPass.entries.forEach { node.dispatch(frame, it) }
                } finally {
                    node.bindMotion(null, null, 0)
                    motion.recycle()
                }
            }
            try {
                send(MotionEvent.ACTION_DOWN)
                if (closeOn == MotionEvent.ACTION_UP) send(closeOn)
            } finally {
                node.close()
            }
            assertEquals(expected, calls)
        }
    }
}
