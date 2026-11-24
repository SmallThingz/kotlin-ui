package smallthingz.ui.reverb

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeActionSheet
import smallthingz.ui.components.NativeActionSheetState
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.NativeSheetValue
import smallthingz.ui.components.SheetRoot
import smallthingz.ui.components.SheetScrim
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Local dispatch through actual owned windows. No injected global input, touch-mode changes, or IME
 * composition. Set reverb_sensitive_modal_exclude_ime=true for the isolated IME-excluded runner.
 * The request panel uses an Activity-owned NOT_FOCUSABLE subwindow, not an application-overlay grant.
 */
@RunWith(AndroidJUnit4::class)
class ReverbSensitiveModalIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val excludeIme = when (InstrumentationRegistry.getArguments().getString("reverb_sensitive_modal_exclude_ime")) {
        null, "false" -> false
        "true" -> true
        else -> error("reverb_sensitive_modal_exclude_ime must be true or false")
    }

    @Test fun acceptedDismissalRejectsConfirmationBeforeItsAnimationCompletes() = withConfirmation { f ->
        main {
            f.dialog.requestAnimatedDismiss()
            assertFalse(f.sheet.canRespond)
            assertFalse(f.sheet.respond(true))
            f.confirm.performClick()
            f.confirm.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null)
            assertFalse(f.responses.contains(true))
        }
        await("Accepted cancellation did not retire the route") { f.responses==listOf(false) }
        main { assertRetired(f,false) }
    }

    @Test fun confirmationRejectsObscuredGestureThenAcceptsCleanGestureOnce() {
        for (flag in obscurationFlags()) withConfirmation { f ->
            main {
                val pointer = Pointer(f.decor, f.confirm, f.dialog::dispatchTouchEvent)
                pointer.send(MotionEvent.ACTION_DOWN)
                pointer.send(MotionEvent.ACTION_MOVE, flag)
                pointer.send(MotionEvent.ACTION_UP)
                assertTrue(f.responses.isEmpty())
                assertTrue(f.sheet.canRespond)
                pointer.send(MotionEvent.ACTION_DOWN)
                pointer.send(MotionEvent.ACTION_UP)
                assertRetired(f, true)
            }
        }
    }

    @Test fun confirmationRetiresPointerAcrossWindowFocusLoss() = withConfirmation { f ->
        lateinit var pointer: Pointer
        main {
            pointer = Pointer(f.decor, f.confirm, f.dialog::dispatchTouchEvent)
            pointer.send(MotionEvent.ACTION_DOWN)
        }
        changeFocus(f.decor, false, requireNotNull(f.cover.window).decorView) { show(f.cover) }
        changeFocus(f.decor, true) { f.cover.dismiss() }
        main {
            pointer.send(MotionEvent.ACTION_UP)
            assertTrue(f.responses.isEmpty())
            pointer.send(MotionEvent.ACTION_DOWN)
            pointer.send(MotionEvent.ACTION_UP)
            assertRetired(f, true)
        }
    }

    @Test fun confirmationRetiresActivationKeysAcrossWindowFocusLoss() {
        for (key in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE)) {
            withConfirmation { f ->
                main {
                    assertTrue(f.confirm.requestFocusFromTouch())
                    assertTrue(f.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
                }
                changeFocus(f.decor, false, requireNotNull(f.cover.window).decorView) { show(f.cover) }
                main { assertTrue("Local button focus must survive window focus loss", f.confirm.isFocused) }
                changeFocus(f.decor, true) { f.cover.dismiss() }
                main {
                    assertTrue(f.confirm.isFocused)
                    f.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                    assertTrue(f.responses.isEmpty())
                    assertTrue(f.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
                    assertTrue(f.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)))
                    assertRetired(f, true)
                }
            }
        }
    }

    @Test fun confirmationRequiresFocusedProgrammaticAndAccessibleResponse() {
        for (accessible in listOf(false, true)) withConfirmation { f ->
            changeFocus(f.decor, false, requireNotNull(f.cover.window).decorView) { show(f.cover) }
            main {
                assertFalse(f.sheet.canRespond)
                assertFalse(f.sheet.respond(true))
                assertFalse(f.sheet.respond(false))
                for (button in listOf(f.confirm, f.cancel)) {
                    button.performClick()
                    button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                    // Exercise the retained semantic callback as well as the View entry point.
                    button.mergedRowAccessibility.performAccessibilityAction(button,
                        AccessibilityNodeInfo.ACTION_CLICK, null)
                }
                assertTrue(f.dialog.isShowing)
                assertTrue(f.responses.isEmpty())
            }
            changeFocus(f.decor, true) { f.cover.dismiss() }
            main {
                if (accessible) assertTrue(f.confirm.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                else assertTrue(f.sheet.respond(true))
                assertRetired(f, true)
            }
        }
    }

    @Test fun unfocusedBackdropAccessibilityCannotHideConfirmation() = withConfirmation { f ->
        lateinit var root: SheetRoot
        lateinit var scrim: SheetScrim
        main {
            root = f.dialog.contentView.parent as SheetRoot
            scrim = descendants(f.decor).filterIsInstance<SheetScrim>().single()
        }
        changeFocus(f.decor, false, requireNotNull(f.cover.window).decorView) { show(f.cover) }
        main {
            assertTrue(f.dialog.contentContext.accessibility.sourceNode(scrim).present)
            scrim.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
        }
        // Zero responses alone would miss a rejected callback which already hid the whole sheet.
        assertForFrames(f.decor) {
            assertTrue(f.responses.isEmpty())
            assertTrue(f.dialog.isShowing)
            assertEquals(root.sourceSemanticsHeight, f.dialog.contentView.bottom)
        }
        changeFocus(f.decor, true) { f.cover.dismiss() }
        main {
            val pointer = Pointer(f.decor, f.confirm, f.dialog::dispatchTouchEvent)
            pointer.send(MotionEvent.ACTION_DOWN)
            pointer.send(MotionEvent.ACTION_UP)
            assertRetired(f, true)
        }
    }

    @Test fun confirmationEscapeCannotCrossFocusAndFreshEscapeCancelsOnce() = withConfirmation { f ->
        main { assertTrue(f.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))) }
        changeFocus(f.decor, false, requireNotNull(f.cover.window).decorView) { show(f.cover) }
        changeFocus(f.decor, true) { f.cover.dismiss() }
        main {
            f.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
            assertTrue(f.responses.isEmpty())
            f.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
            f.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
            assertRetired(f, false)
        }
    }

    @Test fun confirmationRetiresBeforeCallbackAndOwnerCloseIsSilent() {
        withConfirmation { f ->
            main {
                val failure = IllegalStateException("synthetic confirmation callback failure")
                f.onConfirm = {
                    assertFalse(f.sheet.canRespond)
                    assertFalse(f.decor.isAttachedToWindow)
                    assertFalse(f.sheet.respond(false))
                    throw failure
                }
                assertSame(failure, assertThrows(IllegalStateException::class.java) { f.sheet.respond(true) })
                assertRetired(f, true)
            }
        }
        withConfirmation { f ->
            main {
                f.sheet.close()
                f.sheet.close()
                assertFalse(f.sheet.respond(true))
                f.confirm.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                assertFalse(f.decor.isAttachedToWindow)
                assertTrue(f.responses.isEmpty())
            }
        }
    }

    @Test fun requestPanelRejectsObscurationAndAcceptsCleanTouchWithoutWindowFocus() {
        for (flag in obscurationFlags()) withPanel { f ->
            main {
                assertTrue(f.anchor.hasWindowFocus())
                assertFalse(f.panel.view.hasWindowFocus())
                val confirm = button(f.panel.view, "Confirm fixture")
                val pointer = Pointer(f.panel.view, confirm, f.panel.view::dispatchTouchEvent)
                pointer.send(MotionEvent.ACTION_DOWN)
                pointer.send(MotionEvent.ACTION_MOVE, flag)
                pointer.send(MotionEvent.ACTION_UP)
                assertTrue(f.responses.isEmpty())
                pointer.send(MotionEvent.ACTION_DOWN)
                pointer.send(MotionEvent.ACTION_UP)
                assertEquals(listOf(true), f.responses)
                assertFalse(f.panel.view.hasWindowFocus())
                confirm.performClick()
                confirm.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                button(f.panel.view, "Dismiss fixture").performClick()
                assertEquals(listOf(true), f.responses)
            }
        }
    }

    @Test fun requestPanelNoticeRespondsOnceWithoutFocusAndCloseRejectsRetainedActions() {
        withPanel(confirmLabel = null) { f ->
            main {
                assertFalse(f.panel.view.hasWindowFocus())
                assertEquals(1, descendants(f.panel.view).filterIsInstance<NativeMaterialButtonView>().count())
                val dismiss = button(f.panel.view, "Dismiss fixture")
                val pointer = Pointer(f.panel.view, dismiss, f.panel.view::dispatchTouchEvent)
                pointer.send(MotionEvent.ACTION_DOWN)
                pointer.send(MotionEvent.ACTION_UP)
                dismiss.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                assertEquals(listOf(false), f.responses)
            }
        }
        withPanel { f ->
            main {
                val confirm = button(f.panel.view, "Confirm fixture")
                f.panel.close()
                f.panel.close()
                confirm.performClick()
                confirm.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                assertTrue(f.responses.isEmpty())
            }
        }
    }

    private class Confirmation(val sheet: ReverbConfirmationSheet, val dialog: NativeActionSheet,
        val cover: Dialog, val responses: MutableList<Boolean>) {
        val decor: View = requireNotNull(dialog.window).decorView
        lateinit var confirm: NativeMaterialButtonView
        lateinit var cancel: NativeMaterialButtonView
        var onConfirm: () -> Unit = {}
    }

    private fun withConfirmation(test: (Confirmation) -> Unit) = withActivity { activity, anchor ->
        var fixture: Confirmation? = null
        var owned: ReverbConfirmationSheet? = null
        try {
            main {
                val responses = mutableListOf<Boolean>()
                val sheet = ReverbConfirmationSheet(anchor, ReverbDesign.theme(activity, false),
                    "Fixture authorization", "Synthetic request", "Cancel fixture", "Confirm fixture",
                    onCancel = { responses += false }, onConfirm = { responses += true; fixture?.onConfirm?.invoke() })
                owned = sheet
                val dialog = ReverbConfirmationSheet::class.java.getDeclaredField("dialog")
                    .apply { isAccessible = true }.get(sheet) as NativeActionSheet
                val cover = Dialog(activity).apply {
                    setContentView(View(activity), ViewGroup.LayoutParams(100, 100))
                }
                fixture = Confirmation(sheet, dialog, cover, responses)
                prepareDialog(requireNotNull(sheet.window))
                sheet.show()
                assertDialog(requireNotNull(sheet.window))
            }
            val f = requireNotNull(fixture)
            changeFocus(f.decor, true) {}
            awaitConfirmationReady(f)
            main {
                assertTrue("Settled confirmation must admit a focused response", f.sheet.canRespond)
                assertTrue(requireNotNull(f.sheet.window).attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                f.confirm = button(f.decor, "Confirm fixture")
                f.cancel = button(f.decor, "Cancel fixture")
            }
            test(f)
        } finally {
            main { try { fixture?.cover?.dismiss() } finally { owned?.close() } }
        }
    }

    private fun awaitConfirmationReady(f: Confirmation) {
        lateinit var state: NativeActionSheetState
        lateinit var content: View
        lateinit var root: SheetRoot
        main {
            state = NativeActionSheet::class.java.getDeclaredField("state")
                .apply { isAccessible = true }.get(f.dialog) as NativeActionSheetState
            content = f.dialog.contentView
            root = content.parent as SheetRoot
        }
        // placeSheet rounds the animated offset to pixels. Its bottom can reach the anchor while
        // show() still has currentValue=HIDDEN; visible becomes true only in finishClosest().
        // Wait for the real opening operation and admission, without advancing or mutating state.
        await("Confirmation did not finish opening within 5 seconds", diagnostics = {
            "current=${state.currentValue}, target=${state.targetValue}, animating=${state.animationRunning}, " +
                "offset=${state.offset}, anchors=${state.anchors}, showing=${f.dialog.isShowing}, " +
                "attached=${root.isAttachedToWindow}, focus=${f.dialog.hasCurrentWindowFocus}, " +
                "canAccept=${f.dialog.canAcceptSensitiveAction}, canRespond=${f.sheet.canRespond}, " +
                "laidOut=${content.isLaidOut}, height=${content.height}, " +
                "bottom=${content.bottom}, anchor=${root.sourceSemanticsHeight}"
        }) {
            state.currentValue == NativeSheetValue.EXPANDED &&
                state.targetValue == NativeSheetValue.EXPANDED && !state.animationRunning &&
                f.sheet.canRespond && content.isLaidOut && content.height > 0 &&
                content.bottom == root.sourceSemanticsHeight
        }
    }

    private data class Panel(val panel: ReverbRequestPanel, val anchor: View, val responses: MutableList<Boolean>)

    private fun withPanel(confirmLabel: String? = "Confirm fixture", test: (Panel) -> Unit) = withActivity { activity, anchor ->
        val manager = requireNotNull(activity.getSystemService(WindowManager::class.java))
        var panel: ReverbRequestPanel? = null
        var attached = false
        try {
            lateinit var fixture: Panel
            main {
                val responses = mutableListOf<Boolean>()
                val owned = ReverbRequestPanel(activity, ReverbDesign.theme(activity, false), "Fixture", "Review",
                    "Synthetic request", "Choose once", "Dismiss fixture", confirmLabel,
                    onDismiss = { responses += false }, onConfirm = { responses += true })
                panel = owned
                val width = minOf(anchor.width, (320 * activity.resources.displayMetrics.density).roundToInt())
                val params = WindowManager.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT).apply {
                    token = requireNotNull(anchor.windowToken)
                    gravity = Gravity.TOP or Gravity.START
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    title = "Owned request-panel fixture"
                }
                // NOT_FOCUSABLE already excludes IME participation. ALT_FOCUSABLE_IM would invert
                // that policy here; this is deliberately a subwindow, not a focusable Dialog.
                assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                manager.addView(owned.view, params)
                attached = true
                fixture = Panel(owned, anchor, responses)
            }
            await("Owned non-focusable request panel did not lay out") {
                fixture.panel.view.isAttachedToWindow && fixture.panel.view.isLaidOut && fixture.panel.view.height > 0
            }
            main {
                val params = fixture.panel.view.layoutParams as WindowManager.LayoutParams
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                assertEquals(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
                    params.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE)
                assertFalse(fixture.panel.view.hasWindowFocus())
                assertTrue(anchor.hasWindowFocus())
            }
            test(fixture)
        } finally {
            main {
                try { if (attached) manager.removeViewImmediate(requireNotNull(panel).view) }
                finally { panel?.close() }
            }
        }
    }

    private fun assertRetired(f: Confirmation, response: Boolean) {
        assertEquals(listOf(response), f.responses)
        assertFalse(f.sheet.canRespond)
        assertFalse(f.decor.isAttachedToWindow)
        assertFalse(f.sheet.respond(true))
        assertFalse(f.sheet.respond(false))
        f.confirm.performClick()
        f.cancel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
        f.sheet.close()
        f.sheet.close()
        assertEquals(listOf(response), f.responses)
    }

    private fun prepareDialog(window: Window) {
        if (!excludeIme) return
        check(window.peekDecorView()?.isAttachedToWindow != true)
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.setSoftInputMode((window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        assertDialog(window)
    }

    private fun assertDialog(window: Window) {
        if (!excludeIme) return
        val attrs = window.attributes
        assertTrue(attrs.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM != 0)
        assertEquals(0, attrs.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
            attrs.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE)
    }

    private fun show(dialog: Dialog) {
        prepareDialog(requireNotNull(dialog.window))
        dialog.show()
        assertDialog(requireNotNull(dialog.window))
    }

    private fun withActivity(test: (Activity, FrameLayout) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            lateinit var anchor: FrameLayout
            main { anchor = FrameLayout(activity); activity.setContentView(anchor) }
            await("Fixture anchor did not attach and acquire focus") {
                anchor.isAttachedToWindow && anchor.isLaidOut && anchor.width > 0 && anchor.hasWindowFocus()
            }
            test(activity, anchor)
        } finally { main { activity.finish() } }
    }

    private fun changeFocus(view: View, focused: Boolean, gainingFocus: View? = null, change: () -> Unit) {
        val changed = CountDownLatch(1)
        fun ready() = view.hasWindowFocus() == focused &&
            (gainingFocus == null || (gainingFocus.isAttachedToWindow && gainingFocus.hasWindowFocus()))
        // Both sides must complete: window focus loss can be delivered before the cover gains it.
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { if (ready()) changed.countDown() }
        main {
            view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            gainingFocus?.viewTreeObserver?.addOnWindowFocusChangeListener(listener)
        }
        try {
            main { change(); if (ready()) changed.countDown() }
            if (!changed.await(5, TimeUnit.SECONDS)) {
                var detail = ""
                main {
                    detail = "sheet(attached=${view.isAttachedToWindow}, shown=${view.isShown}, " +
                        "focus=${view.hasWindowFocus()}); cover(attached=${gainingFocus?.isAttachedToWindow}, " +
                        "shown=${gainingFocus?.isShown}, focus=${gainingFocus?.hasWindowFocus()})"
                }
                fail("Owned-window focus handoff did not reach sheet=$focused within 5 seconds: $detail")
            }
            instrumentation.waitForIdleSync()
            main {
                assertEquals(focused, view.hasWindowFocus())
                gainingFocus?.let {
                    assertTrue("Interrupting window must be attached", it.isAttachedToWindow)
                    assertTrue("Interrupting window must own focus", it.hasWindowFocus())
                }
            }
        } finally {
            main {
                view.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
                gainingFocus?.viewTreeObserver?.removeOnWindowFocusChangeListener(listener)
            }
        }
    }

    private fun assertForFrames(view: View, assertion: () -> Unit) {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        var remaining = 8
        val frame = object : Runnable {
            override fun run() {
                try { assertion() } catch (error: Throwable) { failure.set(error); done.countDown(); return }
                if (--remaining == 0) done.countDown() else view.postOnAnimation(this)
            }
        }
        try {
            main { view.postOnAnimation(frame) }
            assertTrue("Owned-window frame assertions timed out", done.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
        } finally { main { view.removeCallbacks(frame) } }
    }

    private fun await(message: String, diagnostics: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(16)
        } while (SystemClock.uptimeMillis() < deadline)
        var detail = ""
        main { detail = diagnostics() }
        fail(if (detail.isEmpty()) message else "$message: $detail")
    }

    private class Pointer(private val root: View, private val target: View, private val dispatch: (MotionEvent) -> Boolean) {
        private var downTime = 0L
        fun send(action: Int, flags: Int = 0) {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val origin = IntArray(2).also(root::getLocationInWindow)
            val at = IntArray(2).also(target::getLocationInWindow)
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
                arrayOf(MotionEvent.PointerCoords().apply {
                    x = at[0] - origin[0] + target.width / 2f
                    y = at[1] - origin[1] + target.height / 2f
                    pressure = 1f; size = 1f
                }), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
            try { dispatch(event) } finally { event.recycle() }
        }
    }

    private fun obscurationFlags() = listOf(MotionEvent.FLAG_WINDOW_IS_OBSCURED) +
        if (Build.VERSION.SDK_INT >= 29) listOf(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) else emptyList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun button(root: View, label: String): NativeMaterialButtonView =
        descendants(root).filterIsInstance<NativeMaterialButtonView>().single { it.label.getText() == label }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
