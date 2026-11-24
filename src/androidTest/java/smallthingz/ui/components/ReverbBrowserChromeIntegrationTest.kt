package smallthingz.ui.components

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun idle() = instrumentation.waitForIdleSync()
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
                idle()
                main {
                    popup = ReverbBrowserChromePopup(anchor, ReverbDesign.theme(activity, false),
                        ReverbBrowserChromeMenuKind.NAVIGATION, content(), { true }, {}, {})
                    assertTrue(popup.show())
                }
                idle()
                main {
                    val value = popup!!
                    assertEquals(bottom, value.window.isAboveAnchor)
                    assertEquals(if (bottom) value.panel.height.toFloat() else 0f, value.panel.pivotY, .5f)
                    assertTrue(value.panel.pivotX in 0f..value.panel.width.toFloat())
                    value.close()
                }
                idle()
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
            idle()
            main {
                popup = ReverbBrowserChromePopup(anchor, ReverbDesign.theme(activity, false),
                    ReverbBrowserChromeMenuKind.HISTORY, content(), { true }, { events.add(it) }, {})
                installed = popup
                assertTrue(popup.show())
            }
            idle()
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
        try {
            main {
                anchor = View(activity)
                activity.setContentView(FrameLayout(activity).apply { addView(anchor, FrameLayout.LayoutParams(100, 100)) })
            }
            idle()
            main {
                popup = ReverbBrowserChromePopup(anchor, ReverbDesign.theme(activity, false),
                    ReverbBrowserChromeMenuKind.NAVIGATION, content(), { true }, {
                        assertFalse(popup.window.isShowing)
                        assertTrue(anchor.hasWindowFocus())
                        events.add(it); delivered.countDown()
                    }, {})
                installed = popup; assertTrue(popup.show())
            }
            idle()
            main {
                val row = popup.panel.descendants().filterIsInstance<ReverbBrowserPanelSettingRow>().first()
                val scroll = popup.panel.descendants().filterIsInstance<ScrollView>().single()
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
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            main { assertEquals(listOf("open"), events) }
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
            idle()
            main {
                popup = ReverbBrowserChromePopup(anchor, ReverbDesign.theme(activity, false),
                    ReverbBrowserChromeMenuKind.NAVIGATION, content(), { true }, { events.add(it) }, {})
                installed = popup; assertTrue(popup.show())
            }
            idle()
            main {
                targets = listOf(popup.panel.descendants().filterIsInstance<ReverbBrowserPanelSettingRow>().first(),
                    popup.panel.descendants().filterIsInstance<ReverbBrowserChromeActionView>().single().accessibilityAction)
            }
            for (target in targets) {
                main {
                    target.isFocusableInTouchMode = true; assertTrue(target.requestFocus())
                    target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    modal = Dialog(activity).apply { setContentView(FrameLayout(activity)); show() }
                }
                idle()
                main { assertFalse(popup.panel.hasWindowFocus()); target.performClick(); modal?.dismiss() }
                idle()
                main {
                    assertTrue(popup.panel.hasWindowFocus())
                    target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    assertTrue(events.isEmpty())
                }
            }
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
            idle()
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

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).descendants())
    }
}
