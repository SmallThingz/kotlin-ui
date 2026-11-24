package smallthingz.ui.components

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativePermissionDialogTest {
    class LargeTypeActivity : Activity() {
        override fun attachBaseContext(base: Context) {
            super.attachBaseContext(base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                fontScale = 1.8f
            }))
        }
    }

    private val palette = NativeColorScheme.fallback(false)
    private var allows = 0
    private var denies = 0
    private var cancels = 0
    private lateinit var dialog: Dialog
    private lateinit var api: NativePermissionDialog
    private val panel get() = dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup

    private fun open(activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get(),
                     summary: String = "Share the selected items?", scope: View? = null,
                     denyLabel: String = "Deny", allowLabel: String = "Allow") {
        api = NativePermissionDialog(activity, palette, ColorDrawable(Color.BLUE), "Reader", summary,
            denyLabel, allowLabel, scope, { denies++ }, { allows++ }, { cancels++ })
        api.show()
        dialog = ShadowDialog.getLatestDialog()
        shadowOf(Looper.getMainLooper()).idle()
        // Explicit modeled window focus for host tests, not physical-window/IME evidence.
        val decor = dialog.window!!.decorView
        val attachInfo = ReflectionHelpers.getField<Any>(decor, "mAttachInfo")
        ReflectionHelpers.setField(attachInfo, "mHasWindowFocus", true)
        dialog.onWindowFocusChanged(true)
        measure(328, 600)
        check(decor.hasWindowFocus())
    }

    private fun nodes(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(nodes(view.getChildAt(i)))
    }
    private fun buttons(): List<NativeMaterialButtonView> {
        val actions = nodes(panel).filterIsInstance<NativeMaterialButtonView>()
        return listOf(actions.last(), actions.first()) // public decision order: Deny, Allow
    }
    private fun label(value: String) = nodes(panel).filterIsInstance<TextView>().single { it.text.toString() == value }

    private fun measure(width: Int, maxHeight: Int) {
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        panel.layout(0, 0, width, panel.measuredHeight)
    }

    @Test fun shortPromptWrapsAndUsesAnAccessibleCenteredWindow() {
        open()
        val window = dialog.window!!
        assertEquals(Gravity.CENTER, window.attributes.gravity)
        assertTrue(window.attributes.width in 1..400)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        measure(328, 600)
        assertTrue("short height ${panel.height}", panel.height in 1..600)
        assertFalse(nodes(panel).filterIsInstance<ScrollView>().first().canScrollVertically(1))
        assertTrue(label("Reader").isAccessibilityHeading)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, nodes(panel).filterIsInstance<ImageView>().single().importantForAccessibility)
        assertEquals(listOf("Deny", "Allow"), buttons().map { it.label.getText() })
        assertTrue(buttons().all { it.height >= 48 && it.width >= 48 && it.isFocusable })
    }

    @Test fun largeTypeAndLongTextScrollIndependentlyOfBothActions() {
        val activity = Robolectric.buildActivity(LargeTypeActivity::class.java).setup().get()
        val editor = EditText(activity)
        open(activity, "A long description of the requested permissions. ".repeat(40), editor,
            "Reject this request", "Allow selected items")
        for (availableHeight in listOf(900, 310)) {
            measure(280, availableHeight)
            assertTrue(panel.height <= availableHeight)
            val scroll = nodes(panel).filterIsInstance<ScrollView>().first()
            assertTrue(scroll.canScrollVertically(1))
            val footer = nodes(panel).filterIsInstance<ScrollView>().last()
            assertTrue(scroll.bottom <= footer.top && footer.bottom <= panel.height)
            for (button in buttons()) {
                assertTrue(button.height >= 48 && button.width >= 48)
                val paragraph = checkNotNull(button.label.currentParagraph)
                assertFalse(paragraph.didExceedMaxLines)
                assertTrue(button.label.height + 1f >= paragraph.height)
            }
            val oldTop = footer.top
            scroll.scrollTo(0, scroll.getChildAt(0).height)
            assertTrue(scroll.scrollY > 0)
            assertEquals(oldTop, footer.top)
            scroll.scrollTo(0, 0)
        }
        assertTrue(nodes(panel).contains(editor))
    }

    @Test fun failureKeepsStateAndBusySuppressesActionsAndBack() {
        open()
        val (deny, allow) = buttons()
        allow.performClick()
        api.setError("Request expired")
        assertEquals(View.VISIBLE, label("Request expired").visibility)
        assertEquals(1, allows)
        assertTrue(dialog.isShowing)
        api.setBusy(true)
        assertFalse(allow.isEnabled || deny.isEnabled)
        allow.performClick()
        deny.performClick()
        @Suppress("DEPRECATION")
        dialog.onBackPressed()
        assertTrue(dialog.isShowing)
        assertEquals(1, allows)
        assertEquals(0, denies)
        api.setBusy(false)
        val error = label("Request expired")
        api.setError(null)
        assertEquals(View.GONE, error.visibility)
        deny.performClick()
        assertEquals(1, denies)
        assertTrue(dialog.isShowing)
        api.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, cancels)
    }

    @Test fun keyboardAndAccessibilityCanActivateDecisions() {
        open()
        val (deny, allow) = buttons()
        assertTrue(deny.requestFocusFromTouch())
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            dialog.dispatchKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        api.setBusy(false)
        assertTrue(allow.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(1, denies)
        assertEquals(1, allows)
        assertTrue(dialog.isShowing)
    }

    @Test fun terminalErrorKeepsAllowDisabledAcrossBusyTransitionsAndLeavesCancelAvailable() {
        open()
        val (deny, allow) = buttons()
        api.setAllowEnabled(false)
        api.setBusy(true)
        api.setAllowEnabled(true)
        assertFalse(allow.isEnabled)
        api.setAllowEnabled(false)
        api.setError("Request expired")
        api.setBusy(false)
        assertFalse(allow.isEnabled)
        assertTrue(deny.isEnabled)
        allow.performClick()
        assertEquals(0, allows)
        deny.performClick()
        assertEquals(1, denies)
        api.setBusy(false)
        api.setAllowEnabled(true)
        assertTrue(allow.isEnabled)
        api.setAllowEnabled(false)
        @Suppress("DEPRECATION")
        dialog.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertEquals(1, cancels)
    }

    @Test fun backAndEscapeCancelOnceWhileOutsideTapIsIgnored() {
        for (input in listOf("back", "outside", "escape")) {
            open()
            when (input) {
                "back" -> @Suppress("DEPRECATION") dialog.onBackPressed()
                "outside" -> MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, -100f, -100f, 0).also {
                    dialog.dispatchTouchEvent(it)
                    it.recycle()
                }
                "escape" -> for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    dialog.dispatchKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_ESCAPE))
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            if (input == "outside") {
                assertTrue(dialog.isShowing)
                api.dismiss()
            } else assertFalse(input, dialog.isShowing)
        }
        assertEquals(2, cancels)
        assertEquals(0, allows + denies)
    }

    @Test fun obscuredMiddleOfGestureBlocksAllowUntilFreshCleanTap() {
        open()
        measure(328, 600)
        val allow = buttons().last()
        fun touch(action: Int, flags: Int = 0) {
            val properties = MotionEvent.PointerProperties().apply { id = 0 }
            val location = IntArray(2)
            allow.getLocationInWindow(location)
            val coords = MotionEvent.PointerCoords().apply {
                x = location[0] + allow.width / 2f
                y = location[1] + allow.height / 2f
            }
            val event = MotionEvent.obtain(1, 2, action, 1, arrayOf(properties), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, flags)
            dialog.dispatchTouchEvent(event)
            event.recycle()
        }
        for (flag in listOf(MotionEvent.FLAG_WINDOW_IS_OBSCURED, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)) {
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_MOVE, flag)
            touch(MotionEvent.ACTION_UP)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, allows)
        }
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_UP)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, allows)
    }
}
