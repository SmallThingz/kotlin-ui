package smallthingz.ui.components

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Attached real-device coverage. This suite must not run in an emulator or a simulated View host. */
@RunWith(AndroidJUnit4::class)
class NativePermissionDialogIntegrationTest {
    @Test fun silentTeardownPreservesCallerAndMatchedEscapeCancelsOnlyOnce() = withActivity { activity ->
        var cancelled = 0
        val first = prompt(activity, cancel = { cancelled++ })
        first.show()
        first.dismiss()
        assertEquals(0, cancelled)
        val next = prompt(activity, cancel = { cancelled++ })
        next.show()
        val window = platform(next)
        window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
        assertTrue(window.isShowing)
        next.setBusy(true)
        window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
        next.setBusy(false)
        window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
        assertTrue(window.isShowing)
        window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
        window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
        assertEquals(1, cancelled)
        assertFalse(window.isShowing)
        window.cancel()
        assertEquals(1, cancelled)
    }

    @Test fun busyRejectsRepeatGrantAndTerminalErrorKeepsDenyAvailable() = attached { activity ->
        var allows = 0
        var denies = 0
        val prompt = prompt(activity, allow = { allows++ }, deny = { denies++ })
        prompt.show()
        prompt to {
            val buttons = nodes(platform(prompt).window!!.decorView).filterIsInstance<NativeMaterialButtonView>()
            val allow = buttons.single { it.label.getText() == "Allow" }
            val deny = buttons.single { it.label.getText() == "Deny" }
            assertTrue(allow.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            allow.performClick()
            assertEquals(1, allows)
            assertFalse(allow.isEnabled || deny.isEnabled)
            platform(prompt).cancel()
            assertTrue(platform(prompt).isShowing)
            prompt.setAllowEnabled(false)
            prompt.setError("Request expired")
            prompt.setBusy(false)
            assertFalse(allow.isEnabled)
            assertTrue(deny.isEnabled)
            deny.performClick()
            deny.performClick()
            assertEquals(1, denies)
        }
    }

    @Test fun secureNarrowWindowKeepsEditorAndActionsInsideScrollableBounds() = attached { activity ->
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val editor = EditText(activity).apply { setText("retained"); setSelection(3) }
        val prompt = prompt(activity, body = editor, summary = "Allow access? ".repeat(25))
        prompt.show()
        prompt to {
            val dialog = platform(prompt)
            assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val surface = dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            val density = activity.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            for (width in listOf(180, 280)) {
                surface.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(260), View.MeasureSpec.AT_MOST))
                surface.layout(0, 0, surface.measuredWidth, surface.measuredHeight)
                assertTrue(surface.height <= dp(260))
                val buttons = nodes(surface).filterIsInstance<NativeMaterialButtonView>()
                assertEquals(2, buttons.size)
                for (button in buttons) {
                    assertTrue(button.width >= dp(48))
                    assertTrue(button.height >= dp(48))
                    assertTrue(button.isFocusable)
                }
                val scrolls = nodes(surface).filterIsInstance<ScrollView>()
                assertEquals(2, scrolls.size)
                for (scroll in scrolls) {
                    val bounds = Rect(0, 0, scroll.width, scroll.height)
                    surface.offsetDescendantRectToMyCoords(scroll, bounds)
                    assertTrue(bounds.left >= 0 && bounds.right <= surface.width)
                    assertTrue(bounds.bottom <= surface.height)
                }
            }
            assertTrue(nodes(surface).contains(editor))
            assertEquals("retained", editor.text.toString())
            assertEquals(3, editor.selectionStart)
        }
    }

    @Test fun obscuredGestureThroughDialogCannotGrantAndOutsideTapDoesNotDiscardRequest() = attached { activity ->
        var allows = 0
        var cancels = 0
        val prompt = prompt(activity, allow = { allows++ }, cancel = { cancels++ })
        prompt.show()
        prompt to {
            val dialog = platform(prompt)
            val allow = nodes(dialog.window!!.decorView).filterIsInstance<NativeMaterialButtonView>()
                .single { it.label.getText() == "Allow" }
            val target = IntArray(2).also(allow::getLocationInWindow)
            val x = target[0] + allow.width / 2f
            val y = target[1] + allow.height / 2f
            fun touch(action: Int, flags: Int = 0) {
                val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y }
                val event = MotionEvent.obtain(1, 2, action, 1, arrayOf(properties), arrayOf(coords),
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, flags)
                try { dialog.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            for (flag in listOf(MotionEvent.FLAG_WINDOW_IS_OBSCURED, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)) {
                touch(MotionEvent.ACTION_DOWN)
                touch(MotionEvent.ACTION_MOVE, flag)
                touch(MotionEvent.ACTION_UP)
                assertEquals(0, allows)
            }
            val outside = MotionEvent.obtain(1, 2, MotionEvent.ACTION_OUTSIDE, -100f, -100f, 0)
            try { dialog.dispatchTouchEvent(outside) } finally { outside.recycle() }
            assertTrue(dialog.isShowing)
            assertEquals(0, cancels)
            // A clean keyboard path remains available after rejected pointer input.
            assertTrue(allow.requestFocus())
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
            assertEquals(1, allows)
        }
    }

    private fun prompt(activity: Activity, body: View? = null, summary: String = "Allow access to Injector?",
        allow: () -> Unit = {}, deny: () -> Unit = {}, cancel: () -> Unit = {}) =
        NativePermissionDialog(activity, NativeColorScheme.fallback(false), null, "Reader", summary,
            "Deny", "Allow", body, deny, allow, cancel)

    private fun platform(prompt: NativePermissionDialog): Dialog =
        NativePermissionDialog::class.java.getDeclaredField("dialog").apply { isAccessible = true }.get(prompt) as Dialog

    private fun nodes(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(nodes(view.getChildAt(i)))
    }

    private fun withActivity(block: (Activity) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { instrumentation.runOnMainSync { block(activity) } }
        finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun attached(mount: (Activity) -> Pair<NativePermissionDialog, () -> Unit>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var prompt: NativePermissionDialog? = null
        try {
            lateinit var assertions: () -> Unit
            instrumentation.runOnMainSync {
                val result = mount(activity)
                prompt = result.first
                assertions = result.second
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync(assertions)
        } finally {
            instrumentation.runOnMainSync { prompt?.dismiss(); activity.finish() }
        }
    }
}
