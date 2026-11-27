package smallthingz.ui.components

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.animation.core.SpringSpec
import smallthingz.ui.scroll.NativeVerticalScrollState
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

/** Actual sub-panel focus handoff, with a deliberately slow but finite visual exit. */
@RunWith(AndroidJUnit4::class)
class NativeMenuFocusHandoffIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun actionReturnsHostFocusBeforePaintDetachesAndReopenRejectsOldBackUp() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var popup: NativePopupMenu<Int>? = null
        var runtime: NativeModalWindowRuntime? = null
        lateinit var editor: EditText
        lateinit var anchor: View
        var rowCallbacks = 0
        var accepts = 0
        var dismisses = 0
        var collapseOnDismiss = false
        var terminals = 0
        val accepted = CountDownLatch(1)
        val retiredFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        var failure: Throwable? = null
        try {
            instrumentation.runOnMainSync {
                editor = EditText(activity).apply { setText("Rename recording") }
                anchor = View(activity)
                activity.setContentView(FrameLayout(activity).apply {
                    addView(editor, FrameLayout.LayoutParams(320, 72))
                    addView(anchor, FrameLayout.LayoutParams(180, 72).apply { topMargin = 100 })
                })
            }
            awaitState(anchor) { anchor.isLaidOut && anchor.hasWindowFocus() }
            instrumentation.runOnMainSync {
                val clock = NativeModalWindowRuntime(activity).also { runtime = it; it.setStarted(true) }
                val palette = NativeColorScheme.fallback(false)
                val icons = object : NativeMenuIcons<Int> {
                    override val check = 0
                    override fun create(context: Context, icon: Int, color: Int) = View(context)
                    override fun bind(view: View, icon: Int, color: Int) = Unit
                }
                val slow = NativeAnchoredMenuRecipe(motion = NativeAnchoredMenuMotionStyle(
                    scaleSpring = SpringSpec(dampingRatio = 1f, stiffness = 16f),
                    alphaSpring = SpringSpec(dampingRatio = 1f, stiffness = 16f)))
                val menu = NativePopupMenu(anchor, Density(1f), NativeTypography.Default, createFontFamilyResolver(activity),
                    palette, NativeVerticalScrollState(), clock.frames, clock.scope, icons, slow,
                    onDismissRequest = {
                        dismisses++
                        if (collapseOnDismiss) popup?.expanded(false)
                    }, onTerminal = { terminals++; popup?.close() })
                popup = menu
                menu.bindActions(listOf(NativeMenuAction("Rename", 0) {
                    rowCallbacks++
                    if (menu.acceptAction()) {
                        // Input authority is retired before the editor's own readiness handling.
                        assertEquals(retiredFlags, (menu.layoutParams as WindowManager.LayoutParams).flags and retiredFlags)
                        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, menu.importantForAccessibility)
                        assertFalse(menu.acceptAction())
                        accepts++
                        editor.requestFocus()
                        accepted.countDown()
                    }
                }))
                menu.show()
            }
            val menu = requireNotNull(popup)
            fun panel() = menu.getChildAt(0) as NativeAnchoredMenuPanel
            awaitState(menu) { menu.hasWindowFocus() && panel().scaleX == 1f && panel().alpha == 1f }
            val oldBack = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
            instrumentation.runOnMainSync {
                assertEquals(0, (menu.layoutParams as WindowManager.LayoutParams).flags and retiredFlags)
                assertTrue(menu.isFocusableInTouchMode)
                assertFalse("Popup focus owner must not tint its content", menu.defaultFocusHighlightEnabled)
                assertEquals(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS, menu.descendantFocusability)
                assertTrue(menu.dispatchKeyEvent(oldBack))
                assertFalse(editor.hasWindowFocus())
            }
            tapFirstLabel(menu)
            assertTrue("Accepted callback did not run", accepted.await(2, TimeUnit.SECONDS))
            // If focus only returns after exit/removeViewImmediate, this condition never succeeds.
            awaitState(editor) { editor.hasWindowFocus() && menu.isAttachedToWindow && panel().alpha > 0f }
            instrumentation.runOnMainSync {
                assertEquals(1, accepts)
                assertEquals(0, terminals)
                assertTrue(menu.isAttachedToWindow)
                assertTrue(panel().alpha > 0f)
                assertTrue(editor.hasFocus())
                assertTrue(editor.hasWindowFocus())
                menu.expanded(true)
                assertEquals(0, (menu.layoutParams as WindowManager.LayoutParams).flags and retiredFlags)
                menu.dispatchKeyEvent(KeyEvent.changeAction(oldBack, KeyEvent.ACTION_UP))
                assertEquals(0, dismisses)
                val freshBack = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
                assertTrue(menu.dispatchKeyEvent(freshBack))
                assertTrue(menu.dispatchKeyEvent(KeyEvent.changeAction(freshBack, KeyEvent.ACTION_UP)))
                assertEquals(1, dismisses)
                val oldEscape = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)
                assertTrue(menu.dispatchKeyEvent(oldEscape))
                assertTrue(menu.acceptAction())
                menu.expanded(true)
                menu.dispatchKeyEvent(KeyEvent.changeAction(oldEscape, KeyEvent.ACTION_UP))
                assertEquals(1, dismisses)
                val freshEscape = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)
                assertTrue(menu.dispatchKeyEvent(freshEscape))
                assertTrue(menu.dispatchKeyEvent(KeyEvent.changeAction(freshEscape, KeyEvent.ACTION_UP)))
                assertEquals(2, dismisses)
            }
            awaitState(menu) { menu.hasWindowFocus() && panel().scaleX == 1f && panel().alpha == 1f }
            instrumentation.runOnMainSync {
                assertEquals(0, terminals) // Old exit work did not close the explicitly reopened window.
                assertNotEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, menu.importantForAccessibility)
                val content = panel().getChildAt(0) as NativeMenuContent<*>
                val row = content.column.rows.single()
                val origin = IntArray(2).also(menu::getLocationOnScreen)
                val label = row.getChildAt(0)
                val point = IntArray(2).also(label::getLocationOnScreen)
                val x = point[0] - origin[0] + label.width / 2f
                val y = point[1] - origin[1] + label.height / 2f
                fun touch(down: Long, action: Int) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                        source = InputDevice.SOURCE_TOUCHSCREEN
                    }
                    try {
                        val handled = menu.dispatchTouchEvent(event)
                        if (action == MotionEvent.ACTION_DOWN) assertTrue("Menu row must receive DOWN", handled)
                    } finally { event.recycle() }
                }
                fun dismissWithBack() {
                    val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
                    assertTrue(menu.dispatchKeyEvent(down))
                    assertTrue(menu.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP)))
                }

                collapseOnDismiss = true
                assertEquals(1, rowCallbacks)
                val heldUntilDismiss = SystemClock.uptimeMillis()
                touch(heldUntilDismiss, MotionEvent.ACTION_DOWN)
                dismissWithBack()
                assertEquals(3, dismisses)
                assertEquals(0, (menu.layoutParams as WindowManager.LayoutParams).flags and retiredFlags)
                assertTrue(menu.isAttachedToWindow && panel().alpha > 0f)
                touch(heldUntilDismiss, MotionEvent.ACTION_UP)
                assertEquals("A release cannot select a dismissed row", 1, rowCallbacks)
                row.performClick()
                assertEquals("Direct row activation cannot bypass dismissal", 1, rowCallbacks)
                assertFalse(menu.acceptAction())

                // Reopen before UP: an admission check alone would let this old press select.
                menu.expanded(true)
                val heldThroughReopen = SystemClock.uptimeMillis()
                touch(heldThroughReopen, MotionEvent.ACTION_DOWN)
                dismissWithBack()
                assertEquals(4, dismisses)
                menu.expanded(true)
                touch(heldThroughReopen, MotionEvent.ACTION_UP)
                assertEquals("The previous press must be canceled", 1, rowCallbacks)

                val fresh = SystemClock.uptimeMillis()
                touch(fresh, MotionEvent.ACTION_DOWN)
                touch(fresh, MotionEvent.ACTION_UP)
                assertEquals(2, rowCallbacks)
                assertEquals(2, accepts)
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            var cleanup: Throwable? = null
            instrumentation.runOnMainSync {
                for (close in listOf<() -> Unit>({ popup?.close() }, { runtime?.close() }, { activity.finish() })) {
                    try { close() } catch (error: Throwable) {
                        val previous = failure ?: cleanup
                        if (previous != null) { if (previous !== error) previous.addSuppressed(error) }
                        else cleanup = error
                    }
                }
            }
            if (failure == null) cleanup?.let { throw it }
        }
    }

    private fun tapFirstLabel(menu: NativePopupMenu<Int>) {
        val location = IntArray(2)
        var x = 0f; var y = 0f
        instrumentation.runOnMainSync {
            val panel = menu.getChildAt(0) as NativeAnchoredMenuPanel
            val content = panel.getChildAt(0) as NativeMenuContent<*>
            val label = content.column.rows.first().getChildAt(0)
            label.getLocationOnScreen(location)
            x = location[0] + label.width / 2f; y = location[1] + label.height / 2f
        }
        val down = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
    }

    private fun awaitState(view: View, ready: () -> Boolean) {
        val latch = CountDownLatch(1)
        val observer = arrayOfNulls<ViewTreeObserver>(1)
        val draw = ViewTreeObserver.OnPreDrawListener { if (ready()) latch.countDown(); true }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { if (ready()) latch.countDown() }
        instrumentation.runOnMainSync {
            observer[0] = view.viewTreeObserver.also {
                it.addOnPreDrawListener(draw); it.addOnWindowFocusChangeListener(focus)
            }
            if (ready()) latch.countDown()
            view.invalidate()
        }
        try { assertTrue("Expected focus/paint state did not occur", latch.await(8, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync {
            observer[0]?.takeIf { it.isAlive }?.let {
                it.removeOnPreDrawListener(draw); it.removeOnWindowFocusChangeListener(focus)
            }
        } }
    }
}
