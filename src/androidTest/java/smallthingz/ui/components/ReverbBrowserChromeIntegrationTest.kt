package smallthingz.ui.components

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.reverb.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReverbBrowserChromeIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    // Test-only opt-in; normal instrumentation retains the platform's real IME behavior.
    private val excludeIme = when (InstrumentationRegistry.getArguments().getString("reverb_semantic_exclude_ime")) {
        null, "false" -> false
        "true" -> true
        else -> error("reverb_semantic_exclude_ime must be true or false")
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun activity() = instrumentation.startActivitySync(Intent(instrumentation.context,
        NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private class Gate(activity: Activity) : FrameLayout(activity), NativeInputGate {
        var admitted = true
        override val acceptsNativeInput get() = admitted
    }
    private fun content() = ReverbBrowserChromeMenuContent("Menu", navigation = listOf(
        ReverbBrowserChromeAction("back", "Back", ReverbBrowserChromeIcon.Shared(ReverbIcon.Back))), rows = listOf(
        ReverbBrowserChromeRow("open", "Open"), ReverbBrowserChromeRow("disabled", "Disabled", enabled = false)))

    @Test fun popupPivotFollowsTheActualAboveBelowPlacementInRtl() {
        val activity = activity()
        var popup: ReverbBrowserChromePopup? = null
        try {
            for (bottom in listOf(false, true)) {
                lateinit var anchor: View
                main {
                    val host = FrameLayout(activity)
                    host.layoutDirection = View.LAYOUT_DIRECTION_RTL
                    anchor = View(activity).apply { isFocusableInTouchMode = true }
                    val size = (48 * activity.resources.displayMetrics.density).toInt()
                    host.addView(anchor, FrameLayout.LayoutParams(size, size,
                        (if (bottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.END))
                    activity.setContentView(host)
                }
                awaitReady(anchor)
                main {
                    popup = ReverbBrowserChromePopup(anchor, ReverbDesign.theme(activity, false),
                        ReverbBrowserChromeMenuKind.NAVIGATION, content(), { true }, {}, {})
                    assertTrue("Popup show rejected: ${viewState(anchor)}", popup.show())
                }
                awaitPopup(requireNotNull(popup))
                main {
                    val value = popup!!
                    assertEquals(bottom, value.window.isAboveAnchor)
                    assertEquals(if (bottom) value.panel.height.toFloat() else 0f, value.panel.pivotY, .5f)
                    assertTrue(value.panel.pivotX in 0f..value.panel.width.toFloat())
                    value.close()
                }
                awaitReady(anchor)
            }
        } finally { main { popup?.close(); activity.finish() } }
    }

    @Test fun popupRejectsDisabledHiddenDetachedAndClosedOwners() {
        val activity = activity()
        lateinit var gate: Gate
        lateinit var anchor: View
        lateinit var popup: ReverbBrowserChromePopup
        var installed: AutoCloseable? = null
        val events = mutableListOf<String>()
        try {
            main {
                gate = Gate(activity)
                anchor = View(activity)
                gate.addView(anchor, FrameLayout.LayoutParams(100, 100))
                activity.setContentView(gate)
            }
            awaitReady(anchor)
            main {
                popup = ReverbBrowserChromePopup(anchor, ReverbDesign.theme(activity, false),
                    ReverbBrowserChromeMenuKind.HISTORY, content(), { true }, { events.add(it) }, {})
                installed = popup
                assertTrue(popup.show())
            }
            awaitPopup(popup)
            main {
                val rows = popup.panel.descendants().filterIsInstance<ReverbBrowserPanelSettingRow>().toList()
                val open = rows.single { it.titleView.text.toString() == "Open" }
                rows.single { it.titleView.text.toString() == "Disabled" }.performClick()
                gate.admitted = false; open.performClick(); gate.admitted = true
                anchor.visibility = View.GONE; open.performClick(); anchor.visibility = View.VISIBLE
                gate.removeView(anchor); open.performClick()
                popup.close(); open.performClick()
                assertTrue(events.isEmpty())
            }
        } finally { main { installed?.close(); activity.finish() } }
    }

    @Test fun obscuredMiddleOfGestureNeverCompletesItsClickAndFreshActionDeliversAfterDismissal() {
        val activity = activity()
        lateinit var anchor: View
        lateinit var popup: ReverbBrowserChromePopup
        var installed: AutoCloseable? = null
        val delivered = CountDownLatch(1)
        val events = mutableListOf<String>()
        var retired = 0
        try {
            main {
                anchor = View(activity)
                activity.setContentView(FrameLayout(activity).apply { addView(anchor, FrameLayout.LayoutParams(100, 100)) })
            }
            awaitReady(anchor)
            main {
                popup = ReverbBrowserChromePopup(anchor, ReverbDesign.theme(activity, false),
                    ReverbBrowserChromeMenuKind.NAVIGATION, content(), { true }, {
                        assertFalse(popup.window.isShowing)
                        assertTrue(anchor.hasWindowFocus())
                        events.add(it); delivered.countDown()
                    }, { retired++ })
                installed = popup; assertTrue(popup.show())
            }
            awaitPopup(popup)
            main {
                val row = popup.panel.descendants().filterIsInstance<ReverbBrowserPanelSettingRow>().first()
                val scroll = popup.panel.descendants().filterIsInstance<ScrollView>().single()
                assertTrue("Gesture target must be laid out and admitted", ready(row) && row.isEnabled && row.acceptsNativeInput())
                val rect = Rect(0, 0, row.width, row.height)
                scroll.offsetDescendantRectToMyCoords(row, rect)
                val now = SystemClock.uptimeMillis()
                fun touch(action: Int, flags: Int = 0) {
                    val event = MotionEvent.obtain(now, now + 10, action, rect.exactCenterX(), rect.exactCenterY(), 0)
                    // The full factory is used to carry security flags, which are immutable on a MotionEvent.
                    val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
                    val coords = arrayOf(MotionEvent.PointerCoords().apply { x = event.x; y = event.y; pressure = 1f; size = 1f })
                    val flagged = MotionEvent.obtain(now, now + 10, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0,
                        android.view.InputDevice.SOURCE_TOUCHSCREEN, flags)
                    try { scroll.dispatchTouchEvent(flagged) } finally { event.recycle(); flagged.recycle() }
                }
                touch(MotionEvent.ACTION_DOWN)
                touch(MotionEvent.ACTION_MOVE, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
                touch(MotionEvent.ACTION_UP)
                assertTrue(events.isEmpty())
                row.performClick(); row.performClick()
            }
            val completed = delivered.await(5, TimeUnit.SECONDS)
            main {
                assertTrue("Fresh action did not deliver: ${popupState(popup)}; anchor=${viewState(anchor)}; retired=$retired; events=$events", completed)
                assertEquals(listOf("open"), events)
                assertEquals(1, retired)
            }
        } finally { main { installed?.close(); activity.finish() } }
    }

    @Test fun popupFocusLossCancelsPendingKeysBeforeReadmission() {
        val activity = activity()
        lateinit var anchor: View
        lateinit var popup: ReverbBrowserChromePopup
        lateinit var targets: List<View>
        var modal: Dialog? = null
        var installed: AutoCloseable? = null
        val events = mutableListOf<String>()
        try {
            main {
                anchor = View(activity)
                activity.setContentView(FrameLayout(activity).apply { addView(anchor, FrameLayout.LayoutParams(100, 100)) })
            }
            awaitReady(anchor)
            main {
                popup = ReverbBrowserChromePopup(anchor, ReverbDesign.theme(activity, false),
                    ReverbBrowserChromeMenuKind.NAVIGATION, content(), { true }, { events.add(it) }, {})
                installed = popup; assertTrue(popup.show())
            }
            awaitPopup(popup)
            main {
                targets = listOf(popup.panel.descendants().filterIsInstance<ReverbBrowserPanelSettingRow>().first(),
                    popup.panel.descendants().filterIsInstance<ReverbBrowserChromeActionView>().single().accessibilityAction)
            }
            for (target in targets) {
                main {
                    target.isFocusableInTouchMode = true; assertTrue(target.requestFocus())
                    target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    modal = Dialog(activity).apply { setContentView(FrameLayout(activity)) }
                    showFixture(requireNotNull(modal))
                }
                await("Covering dialog owns focus", { "${popupState(popup)}; dialog=${viewState(requireNotNull(modal?.window).decorView)}" }) {
                    requireNotNull(modal?.window).decorView.hasWindowFocus() && !popup.panel.hasWindowFocus()
                }
                main { assertFalse(popup.panel.hasWindowFocus()); target.performClick(); modal?.dismiss() }
                awaitPopup(popup)
                main {
                    assertTrue(popup.panel.hasWindowFocus())
                    target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    assertTrue(events.isEmpty())
                }
            }
            main { targets.first().performClick() }
            await("Fresh action after focus return delivers", { "${popupState(popup)}; events=$events" }) {
                events == listOf("open")
            }
            main { assertEquals(listOf("open"), events) }
        } finally { main { modal?.dismiss(); installed?.close(); activity.finish() } }
    }

    @Test fun narrowRtlChromeRetainsEditorValueSelectionAndRendererHost() {
        val activity = activity()
        var chrome: ReverbBrowserChrome? = null
        try {
            main {
                val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { fontScale = 2f })
                val theme = ReverbDesign.theme(context, false)
                val action = ReverbBrowserChromeAction("site", "Site", ReverbBrowserChromeIcon.Shared(ReverbIcon.Browser))
                val view = ReverbBrowserChrome(context, theme, "Address", action,
                    action.copy(id = "tabs", label = "Tabs"), action.copy(id = "menu", label = "Menu"))
                chrome = view; activity.setContentView(view)
                val text = "https://example.test/" + "long".repeat(4096)
                view.addressEditor.setText(text); view.addressEditor.setSelection(7, 19)
                assertTrue(view.addressEditor.requestFocus())
                val renderer = View(context); view.attachRenderer(renderer)
                for (width in listOf(224, 320)) {
                    view.layoutDirection = View.LAYOUT_DIRECTION_RTL
                    view.bindSiteAction(action, "Unverified")
                    view.bindLoading(.5f, 1L, true, true); view.bindBrowserStatus("Status", true)
                    val pixels = (width * theme.density.density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((800 * theme.density.density).toInt(), View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, pixels, view.measuredHeight)
                    assertEquals(text, view.addressEditor.text.toString())
                    assertTrue(view.addressEditor.hasFocus())
                    assertEquals(7, view.addressEditor.selectionStart); assertEquals(19, view.addressEditor.selectionEnd)
                    assertEquals(view.pageHost.width, renderer.width)
                    val notice = ReverbBrowserChromeUndoNotice(context, theme, "The tab was closed",
                        "Restore the previously closed tab") {}
                    notice.attachToContentHost(view.pageHost, 0)
                    notice.measure(View.MeasureSpec.makeMeasureSpec((pixels - 24 * theme.density.density).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.AT_MOST))
                    notice.layout(0, 0, notice.measuredWidth, notice.measuredHeight)
                    val noticeRow = notice.descendants().filterIsInstance<ReverbBrowserPanelSettingRow>().single()
                    assertTrue(noticeRow.titleView.measuredWidth > 0)
                    val undo = notice.descendants().filterIsInstance<NativeButtonTouchTarget>().single()
                    assertTrue(undo.measuredWidth > 0 && undo.measuredWidth <= notice.width)
                    notice.close()
                    listOf(view.siteAction, view.tabsAction, view.menuAction).forEach {
                        val rect = Rect(0, 0, it.width, it.height); view.offsetDescendantRectToMyCoords(it, rect)
                        assertTrue(rect.left >= 0 && rect.right <= pixels)
                    }
                }
            }
        } finally { main { chrome?.close(); activity.finish() } }
    }

    @Test fun toolbarActionsUseCurrentIdsAndRetireReboundDisabledAndClosedInput() {
        val activity = activity()
        var chrome: ReverbBrowserChrome? = null
        val events = mutableListOf<String>()
        try {
            lateinit var gate: Gate
            lateinit var view: ReverbBrowserChrome
            val site = ReverbBrowserChromeAction("site", "Site", ReverbBrowserChromeIcon.Shared(ReverbIcon.Browser))
            main {
                gate = Gate(activity)
                view = ReverbBrowserChrome(activity, ReverbDesign.theme(activity, false), "Address", site,
                    site.copy(id = "tabs", label = "Tabs"), site.copy(id = "menu", label = "Menu"))
                chrome = view
                view.chromeInputAllowed = { true }
                view.onChromeAction = events::add
                gate.addView(view, FrameLayout.LayoutParams(-1, -1))
                activity.setContentView(gate)
            }
            awaitReady(view)
            main {
                val target = view.siteAction.accessibilityAction
                target.performClick()
                assertEquals(listOf("site"), events)
                view.bindSiteAction(site.copy(enabled = false), "Disabled")
                target.performClick()
                view.bindSiteAction(site, "Site")
                gate.admitted = false; target.performClick(); gate.admitted = true
                view.siteAction.visibility = View.GONE; target.performClick(); view.siteAction.visibility = View.VISIBLE
                view.siteAction.isEnabled = false; target.performClick(); view.siteAction.isEnabled = true
                target.isFocusableInTouchMode = true; assertTrue(target.requestFocus())
                target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                view.bindSiteAction(site.copy(id = "current"), "Current")
                target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                assertEquals(listOf("site"), events)
                target.performClick()
                assertEquals(listOf("site", "current"), events)
                view.close(); target.performClick()
                assertEquals(listOf("site", "current"), events)
            }
        } finally { main { chrome?.close(); activity.finish() } }
    }

    @Test fun toolbarAdmissionCannotDeliverAnActionReboundOrRestoredDuringAdmission() {
        val activity = activity()
        var installed: ReverbBrowserChrome? = null
        val events = mutableListOf<String>()
        try {
            lateinit var view: ReverbBrowserChrome
            val site = ReverbBrowserChromeAction("site", "Site", ReverbBrowserChromeIcon.Shared(ReverbIcon.Browser))
            main {
                view = ReverbBrowserChrome(activity, ReverbDesign.theme(activity, false), "Address", site,
                    site.copy(id = "tabs", label = "Tabs"), site.copy(id = "menu", label = "Menu"))
                installed = view
                view.onChromeAction = events::add
                activity.setContentView(view)
            }
            awaitReady(view)
            for (restoreOriginal in listOf(false, true)) main {
                events.clear()
                view.bindSiteAction(site, "Original")
                var admissions = 0
                view.chromeInputAllowed = {
                    admissions++
                    view.bindSiteAction(site.copy(id = "replacement", enabled = false), "Disabled replacement")
                    // Returning to the exact original object must not revive the older activation.
                    if (restoreOriginal) view.bindSiteAction(site, "Restored original")
                    true
                }
                view.siteAction.accessibilityAction.performClick()
                assertEquals(1, admissions)
                assertTrue("No old action may cross reentrant admission", events.isEmpty())
                view.chromeInputAllowed = { true }
                view.bindSiteAction(site.copy(id = "fresh"), "Fresh enabled action")
                view.siteAction.accessibilityAction.performClick()
                assertEquals(listOf("fresh"), events)
            }
        } finally { main { installed?.close(); activity.finish() } }
    }

    @Test fun throwingPopupRetirementAlwaysDismissesAndPreservesTheAdmissionFailure() {
        val activity = activity()
        var installed: ReverbBrowserChromePopup? = null
        try {
            lateinit var anchor: View
            main {
                anchor = View(activity)
                activity.setContentView(FrameLayout(activity).apply { addView(anchor, FrameLayout.LayoutParams(100, 100)) })
            }
            for (failAdmission in listOf(false, true)) {
                awaitReady(anchor)
                lateinit var popup: ReverbBrowserChromePopup
                var admissionFailure: Throwable? = null
                val primary = IllegalStateException("owner admission")
                val retirement = IllegalArgumentException("retirement callback")
                var retired = 0
                val events = mutableListOf<String>()
                main {
                    popup = ReverbBrowserChromePopup(anchor, ReverbDesign.theme(activity, false),
                        ReverbBrowserChromeMenuKind.NAVIGATION, content(),
                        canUse = { admissionFailure?.let { throw it }; true },
                        onAction = events::add,
                        onRetired = {
                            retired++
                            assertFalse("Dismissal precedes retirement notification", popup.window.isShowing)
                            popup.close() // A reentrant close must not notify twice.
                            throw retirement
                        })
                    installed = popup
                    assertTrue(popup.show())
                }
                awaitPopup(popup)
                main {
                    val row = popup.panel.descendants().filterIsInstance<ReverbBrowserPanelSettingRow>().first()
                    if (failAdmission) admissionFailure = primary
                    val caught = runCatching { if (failAdmission) row.performClick() else popup.close() }.exceptionOrNull()
                    assertSame(if (failAdmission) primary else retirement, caught)
                    if (failAdmission) assertEquals(listOf(retirement), primary.suppressed.toList())
                    assertFalse(popup.window.isShowing)
                    assertEquals(1, retired)
                    popup.close()
                    row.performClick()
                    assertEquals(1, retired)
                    assertTrue(events.isEmpty())
                }
                await("Throwing retirement still detaches the window", { "${popupState(popup)}; anchor=${viewState(anchor)}" }) {
                    !popup.panel.isAttachedToWindow && ready(anchor)
                }
            }
        } finally { main { installed?.close(); activity.finish() } }
    }

    @Test fun addressBindingIsLosslessWhileNativeUserInputRemainsBounded() {
        val activity = activity()
        var installed: ReverbBrowserChrome? = null
        try {
            lateinit var view: ReverbBrowserChrome
            val action = ReverbBrowserChromeAction("site", "Site", ReverbBrowserChromeIcon.Shared(ReverbIcon.Browser))
            main {
                view = ReverbBrowserChrome(activity, ReverbDesign.theme(activity, false), "Address", action,
                    action.copy(id = "tabs", label = "Tabs"), action.copy(id = "menu", label = "Menu"))
                installed = view
                activity.setContentView(view)
            }
            awaitReady(view)
            main {
                val editor = view.addressEditor
                editor.setText("")
                editor.editableText.append("u".repeat(6000))
                assertEquals("Native user-edit limit remains in effect", 5000, editor.length())
                val committed = "https://example.test/" + "path".repeat(4096)
                editor.setText(committed)
                assertEquals(committed, editor.text.toString())
                editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                editor.setSingleLine(true)
                assertEquals("URI/single-line metadata must not truncate a retained value", committed, editor.text.toString())
                assertTrue(editor.requestFocus())
                editor.setSelection(7, 19)
                view.bindSiteAction(action, "Unverified")
                assertEquals(committed, editor.text.toString())
                assertTrue(editor.hasFocus())
                assertEquals(7, editor.selectionStart); assertEquals(19, editor.selectionEnd)
                editor.editableText.delete(editor.length() - 1, editor.length())
                assertEquals("Deletion remains possible above the user-input limit", committed.dropLast(1), editor.text.toString())
                editor.setText("")
                editor.editableText.append("v".repeat(6000))
                assertEquals("Binding must restore user-input admission", 5000, editor.length())
                editor.setText("")
                assertTrue(editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "a".repeat(6000))
                }))
                assertEquals("Accessibility replacement remains a bounded user edit", 5000, editor.length())
            }
        } finally { main { installed?.close(); activity.finish() } }
    }

    private fun ready(view: View) = view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE &&
        view.isLaidOut && !view.isLayoutRequested && view.width > 0 && view.height > 0 && view.hasWindowFocus()

    private fun viewState(view: View) = "attached=${view.isAttachedToWindow}, shown=${view.isShown}, " +
        "windowVisibility=${view.windowVisibility}, laidOut=${view.isLaidOut}, layoutRequested=${view.isLayoutRequested}, " +
        "size=${view.width}x${view.height}, focus=${view.hasWindowFocus()}, enabled=${view.isEnabled}"

    private fun popupState(popup: ReverbBrowserChromePopup) =
        "showing=${popup.window.isShowing}, above=${popup.window.isAboveAnchor}, panel=${viewState(popup.panel)}"

    private fun awaitReady(view: View) = await("Attached owner is ready", { viewState(view) }) { ready(view) }
    private fun awaitPopup(popup: ReverbBrowserChromePopup) = await("Popup is laid out and owns focus", { popupState(popup) }) {
        popup.window.isShowing && ready(popup.panel)
    }

    private fun await(description: String, state: () -> String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        var lastState = "not sampled"
        do {
            var admitted = false
            main { admitted = condition(); lastState = state() }
            if (admitted) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("$description: $lastState")
    }

    private fun showFixture(dialog: Dialog) {
        prepareFixtureWindow(dialog)
        dialog.show()
        assertFixtureWindow(dialog)
    }

    private fun prepareFixtureWindow(dialog: Dialog) {
        if (!excludeIme) return
        val window = requireNotNull(dialog.window)
        check(!dialog.isShowing && window.peekDecorView()?.isAttachedToWindow != true) {
            "IME exclusion must be set before the fixture window is attached"
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.setSoftInputMode((window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        assertFixtureWindow(dialog)
    }

    private fun assertFixtureWindow(dialog: Dialog) {
        if (!excludeIme) return
        val attrs = requireNotNull(dialog.window).attributes
        assertTrue(attrs.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM != 0)
        assertEquals(0, attrs.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
            attrs.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE)
    }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).descendants())
    }
}
