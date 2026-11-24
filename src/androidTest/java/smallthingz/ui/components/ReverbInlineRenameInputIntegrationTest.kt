package smallthingz.ui.components

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbInlineRecordingRename

/** Real attached editors and a focusable popup. IME visibility/timing is qualified separately
 * through the app's actual Library/chooser path; these assertions cover request ownership. */
@RunWith(AndroidJUnit4::class)
class ReverbInlineRenameInputIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private class Fixture(val activity: Activity, val root: FrameLayout,
        val rename: ReverbInlineRecordingRename, val scope: CoroutineScope) {
        var popup: PopupWindow? = null
    }

    private fun scenario(action: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var fixture: Fixture? = null
        try {
            main {
                val root = FrameLayout(activity).apply {
                    isFocusableInTouchMode = true
                    descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
                }
                activity.setContentView(root)
                root.requestFocus()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
                    AndroidUiFrameClock(Choreographer.getInstance()))
                var name = "Owned rename fixture"
                val rename = ReverbInlineRecordingRename(activity, ReverbEnvironment(activity, false),
                    scope, root, "Rename", "Cancel", "Done", { name }, { name = it }, {}, {})
                fixture = Fixture(activity, root, rename, scope)
                root.addView(rename, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            }
            val f = checkNotNull(fixture)
            await("Attached rename fixture") { f.rename.isLaidOut && f.rename.hasWindowFocus() }
            action(f)
        } finally {
            main {
                try {
                    fixture?.let { f ->
                        f.popup?.dismiss()
                        f.rename.close()
                        f.scope.cancel()
                    }
                } finally { activity.finish() }
            }
        }
    }

    private fun coverWithPopup(f: Fixture) {
        main {
            val content = View(f.activity).apply { isFocusableInTouchMode = true }
            f.popup = PopupWindow(content, 180, 100, true).also {
                it.showAtLocation(f.root, Gravity.CENTER, 0, 0)
                content.requestFocus()
            }
        }
        await("Popup owns window focus") { !f.rename.hasWindowFocus() }
    }

    @Test fun requestSurvivesPopupFocusAndStartsTheSameEditorOnReturn() = scenario { f ->
        coverWithPopup(f)
        main {
            f.rename.startEditing()
            assertTrue(flag(f, "editingRequested"))
            assertFalse(flag(f, "showImePosted"))
            assertFalse(f.rename.editor.isFocused)
            f.popup!!.dismiss()
        }
        await("One pending request completes after window focus") {
            f.rename.editor.isFocused && f.rename.hasWindowFocus() &&
                !flag(f, "editingRequested") && !flag(f, "showImePosted")
        }
        main {
            assertSame(f.rename.editor, f.root.findFocus())
            assertEquals(f.rename.editor.text.length, f.rename.editor.selectionStart)
            assertNotNull(f.rename.editor.onCreateInputConnection(EditorInfo()))
        }
    }

    @Test fun deactivationCancelsPendingShowAndFreshActivationNeedsANewRequest() = scenario { f ->
        coverWithPopup(f)
        main {
            f.rename.startEditing()
            assertTrue(flag(f, "editingRequested"))
            f.rename.setInputActive(false)
            assertFalse(flag(f, "editingRequested"))
            assertFalse(flag(f, "showImePosted"))
            f.popup!!.dismiss()
        }
        await("Window focus returns without restoring inactive editor") { f.rename.hasWindowFocus() }
        main {
            staleShow(f).run()
            assertFalse(f.rename.editor.isFocused)
            assertNull(f.rename.editor.onCreateInputConnection(EditorInfo()))
            f.rename.setInputActive(true)
            assertFalse(flag(f, "editingRequested"))
            f.rename.startEditing()
        }
        await("Fresh request starts active editor") {
            f.rename.editor.isFocused && !flag(f, "editingRequested")
        }
    }

    @Test fun detachRetiresPendingRequestInsteadOfShowingAfterReattachment() = scenario { f ->
        coverWithPopup(f)
        main {
            f.rename.startEditing()
            assertTrue(flag(f, "editingRequested"))
            f.root.removeView(f.rename)
            assertFalse(flag(f, "editingRequested"))
            f.root.addView(f.rename, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            f.popup!!.dismiss()
        }
        await("Reattached editor laid out and window focused") { f.rename.isLaidOut && f.rename.hasWindowFocus() }
        main {
            staleShow(f).run()
            assertFalse(flag(f, "showImePosted"))
            assertFalse(f.rename.editor.isFocused)
        }
    }

    @Test fun popupWindowFocusLossRetiresOldConnectionWithoutBlockingTheReplacement() = scenario { f ->
        main { f.rename.startEditing() }
        await("Editor focused before popup") { f.rename.editor.isFocused && !flag(f, "editingRequested") }
        var previous: android.view.inputmethod.InputConnection? = null
        var original = ""
        main {
            original = f.rename.editor.text.toString()
            previous = checkNotNull(f.rename.editor.onCreateInputConnection(EditorInfo()))
        }
        coverWithPopup(f)
        main {
            assertFalse(checkNotNull(previous).commitText("stale popup", 1))
            assertEquals(original, f.rename.editor.text.toString())
            f.popup!!.dismiss()
        }
        await("Editor window regains focus") { f.rename.hasWindowFocus() }
        main {
            assertTrue(f.rename.editor.isFocused)
            assertNotNull(f.rename.editor.onCreateInputConnection(EditorInfo()))
            assertFalse(checkNotNull(previous).commitText("stale after return", 1))
            assertEquals(original, f.rename.editor.text.toString())
        }
    }

    @Test fun focusTransferAndTerminalReleaseRejectStaleAndReplacementConnections() = scenario { f ->
        main { f.rename.startEditing() }
        await("Editor focused") { f.rename.editor.isFocused && !flag(f, "editingRequested") }
        main {
            val editor = f.rename.editor
            val previous = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            val original = editor.text.toString()
            assertTrue(f.root.requestFocus())
            assertFalse(previous.commitText("stale", 1))
            assertEquals(original, editor.text.toString())
            assertNull(editor.onCreateInputConnection(EditorInfo()))
            f.rename.startEditing()
            val current = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            f.rename.close() // Also cancels the show posted by the fresh request above.
            staleShow(f).run()
            assertFalse(flag(f, "editingRequested"))
            assertFalse(current.commitText("retired", 1))
            assertNull(editor.onCreateInputConnection(EditorInfo()))
            assertFalse(editor.onCheckIsTextEditor())
            assertEquals(original, editor.text.toString())
        }
    }

    private fun field(f: Fixture, name: String): Any? = f.rename.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(f.rename)
    private fun flag(f: Fixture, name: String) = field(f, name) as Boolean
    private fun staleShow(f: Fixture) = field(f, "showIme") as Runnable
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            main { ready = predicate() }
            if (ready) return
            SystemClock.sleep(16)
        }
        fail(message)
    }
}
