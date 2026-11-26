package smallthingz.ui.common

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.components.NativeLabeledTextField
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbStandardIconButtonView

/** Fixture-only rejection at the transport return boundary, on an ordinary MOVE.
 * The runner replaces exactly one dispatch call in its private compiled source copy.
 * No production hook, event flag rewrite, overlay, direct click or controller replacement.
 */
object CredentialRouteTestTransport {
    var rejectNextMove = false
    fun dispatch(view: View, event: MotionEvent): Boolean {
        if (rejectNextMove && event.actionMasked == MotionEvent.ACTION_MOVE) {
            rejectNextMove = false
            return false
        }
        return view.dispatchTouchEvent(event)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CredentialRouteRetirementTest {
    private enum class Field { VALUE }

    @Test fun rejectedMoveRetiresPressAndFreshGestureRecovers() = fixture { f ->
        f.down()
        CredentialRouteTestTransport.rejectNextMove = true
        f.send(MotionEvent.ACTION_MOVE)
        assertFalse(CredentialRouteTestTransport.rejectNextMove)
        f.send(MotionEvent.ACTION_UP)
        f.assertRetiredAndRecover()
    }

    @Test fun throwingMoveRetiresPressAndPreservesPrimaryException() = fixture { f ->
        val expected = IllegalStateException("synthetic ordinary listener failure")
        f.down()
        f.button.visual.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE) throw expected
            false
        }
        val actual = runCatching { f.send(MotionEvent.ACTION_MOVE) }.exceptionOrNull()
        assertSame(expected, actual)
        f.button.visual.setOnTouchListener(null)
        f.send(MotionEvent.ACTION_UP)
        f.assertRetiredAndRecover()
    }

    @Test fun finalReleaseOutsideAllocatedSlotWithoutMoveDoesNotActivate() = fixture { f ->
        f.down()
        val slot = f.bounds(f.button)
        f.send(MotionEvent.ACTION_UP, slot.right + 12f, slot.exactCenterY())
        f.assertRetiredAndRecover()
    }

    @Test fun releaseAtOldLocationAfterLayoutDoesNotActivateMovedOwner() = fixture { f ->
        f.down()
        val oldX = f.x; val oldY = f.y
        // Layout the real wrapper at another ordinary position while the stream is active.
        // There is deliberately no MOVE between DOWN and UP.
        val moved = f.group.view
        moved.layout(moved.left - 80, moved.top, moved.right - 80, moved.bottom)
        assertFalse(f.bounds(f.button).contains(oldX.toInt(), oldY.toInt()))
        f.send(MotionEvent.ACTION_UP, oldX, oldY)
        f.assertRetiredAndRecover()
    }

    @Test fun ordinaryMoveOutAndBackDoesNotRevivePress() = fixture { f ->
        f.down()
        f.send(MotionEvent.ACTION_MOVE, f.bounds(f.button).right + 12f, f.y)
        f.send(MotionEvent.ACTION_UP)
        f.assertRetiredAndRecover()
    }

    @Test fun ordinaryCancelRetiresStreamAndFreshGestureRecovers() = fixture { f ->
        f.down()
        f.send(MotionEvent.ACTION_CANCEL)
        f.assertRetiredAndRecover()
    }

    private class Fixture(val root: FrameLayout, val group: CredentialFieldGroup<Field>,
        val button: ReverbStandardIconButtonView, val field: NativeLabeledTextField,
        val calls: MutableList<CredentialActionTarget<Field>>, val editorEvents: MutableList<Int>) {
        val retained = field.editor.text
        var x = 0f; var y = 0f
        private var downTime = 0L
        fun bounds(view: View) = Rect(0, 0, view.width, view.height).also {
            root.offsetDescendantRectToMyCoords(view, it)
        }
        fun down() {
            val slot = bounds(button); x = slot.exactCenterX(); y = slot.exactCenterY()
            downTime = SystemClock.uptimeMillis()
            assertTrue(send(MotionEvent.ACTION_DOWN))
        }
        fun send(action: Int, x: Float = this.x, y: Float = this.y): Boolean {
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coordinates = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            val originalFlags = event.flags
            var failed = false
            return try { root.dispatchTouchEvent(event) }
            catch (failure: Throwable) { failed = true; throw failure }
            finally {
                // Android ancestors may not unwind their temporary coordinate offsets when a
                // listener throws. Do not mask the original exception with an unrelated assertion.
                try {
                    if (!failed) {
                        assertEquals("Caller action is unchanged", action, event.action)
                        assertEquals("Caller flags are unchanged", originalFlags, event.flags)
                        assertEquals(x, event.x, 0f); assertEquals(y, event.y, 0f)
                    }
                } finally { event.recycle() }
            }
        }
        fun assertRetiredAndRecover() {
            assertTrue("Retired press must not dispatch", calls.isEmpty())
            assertEditorUnchanged()
            down(); send(MotionEvent.ACTION_UP)
            assertEquals(listOf(CredentialActionTarget(Field.VALUE, CredentialFieldAction.REVEAL)), calls)
            assertEditorUnchanged()
        }
        private fun assertEditorUnchanged() {
            assertTrue("No new editor pointer", editorEvents.isEmpty())
            assertSame(retained, field.editor.text)
            assertTrue(field.editor.hasFocus())
            assertEquals(2, field.editor.selectionStart); assertEquals(7, field.editor.selectionEnd)
            assertEquals(40, button.visual.width); assertEquals(24, button.visual.glyph.width)
        }
    }

    private fun fixture(test: (Fixture) -> Unit) {
        RuntimeEnvironment.setQualifiers("en-rUS-w380dp-h800dp-port-mdpi")
        Instrumentation().setInTouchMode(true)
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val calls = mutableListOf<CredentialActionTarget<Field>>()
        val events = mutableListOf<Int>()
        val reveal = CredentialActionTarget(Field.VALUE, CredentialFieldAction.REVEAL)
        val group = CredentialFieldGroup(activity, ReverbDesign.theme(activity, false).controls,
            listOf(CredentialFieldSpec(Field.VALUE, "Synthetic credential")), setOf(reveal),
            onAction = { calls += it }, onEdit = { _, _ -> error("Unexpected edit") })
        CredentialRouteTestTransport.rejectNextMove = false
        try {
            group.bind(listOf(CredentialFieldState(Field.VALUE, "synthetic-value")), listOf(CredentialActionState(reveal, "Show")))
            val root = FrameLayout(activity).apply { addView(group.view, FrameLayout.LayoutParams(-1, -2)) }
            activity.setContentView(root); controller.windowFocusChanged(true)
            root.measure(View.MeasureSpec.makeMeasureSpec(380, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 380, 800)
            val button = descendants(group.view).filterIsInstance<ReverbStandardIconButtonView>().single()
            val field = descendants(group.view).filterIsInstance<NativeLabeledTextField>().single()
            field.editor.showSoftInputOnFocus = false
            field.editor.setOnTouchListener { _, event -> events += event.actionMasked; true }
            assertTrue(field.editor.requestFocus()); field.editor.setSelection(2, 7)
            test(Fixture(root, group, button, field, calls, events))
        } finally {
            CredentialRouteTestTransport.rejectNextMove = false
            group.close(); controller.pause().stop().destroy()
        }
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
