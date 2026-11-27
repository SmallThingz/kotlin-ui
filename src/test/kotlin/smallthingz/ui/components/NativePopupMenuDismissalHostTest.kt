package smallthingz.ui.components

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.scroll.NativeVerticalScrollState
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativePopupMenuDismissalHostTest {
    @Test fun outgoingRowRejectsActivationAndReopenedRowAcceptsOnce() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        try {
            val host = FrameLayout(activity)
            val anchor = View(activity)
            activity.setContentView(host)
            host.addView(anchor, FrameLayout.LayoutParams(180, 48))
            NativeModalWindowRuntime(activity).use { runtime ->
                var dismissRequests = 0
                var actions = 0
                val icons = object : NativeMenuIcons<Int> {
                    override val check = 0
                    override fun create(context: Context, icon: Int, color: Int) = View(context)
                    override fun bind(view: View, icon: Int, color: Int) = Unit
                }
                lateinit var menu: NativePopupMenu<Int>
                menu = NativePopupMenu(anchor, Density(1f), NativeTypography.Default, createFontFamilyResolver(activity),
                    NativeColorScheme.fallback(false), NativeVerticalScrollState(), runtime.frames, runtime.scope, icons,
                    onDismissRequest = { dismissRequests++; menu.expanded(false) }, onTerminal = { menu.close() })
                val menuActions = listOf(
                    NativeMenuAction("Rename", 0) { actions++ },
                    NativeMenuAction("Delete", 0, enabled = false) { error("Disabled action delivered") },
                )
                menu.use {
                    menu.bindActions(menuActions)
                    // Attach the real popup as a child for this input test. Android sub-window routing is device coverage.
                    host.addView(menu, FrameLayout.LayoutParams(-2, -2))
                    host.measure(exact(360), exact(640))
                    host.layout(0, 0, 360, 640)
                    val panel = menu.getChildAt(0) as NativeAnchoredMenuPanel
                    val rows = (panel.getChildAt(0) as NativeMenuContent<*>).column.rows
                    val row = rows[0]
                    val disabled = rows[1]
                    try {
                        menu.expanded(true)
                        panel.advanceMotion(1_000_000_000L, visible = true, durationScale = 1f)
                        panel.advanceMotion(6_000_000_000L, visible = true, durationScale = 1f)
                        assertTrue(row.isAttachedToWindow && row.width > 0 && row.height > 0)
                        assertEquals(1f, panel.alpha, 0f)
                        assertTrue(menu.isFocusableInTouchMode)
                        assertFalse("Popup focus parking must not tint the entire panel",
                            menu.defaultFocusHighlightEnabled)
                        assertRowAdmission(row, admitted = true)
                        assertFalse(disabled.performClick())

                        val held = SystemClock.uptimeMillis()
                        assertTrue(dispatch(menu, row, held, MotionEvent.ACTION_DOWN))
                        assertTrue(dispatch(menu, row, held, MotionEvent.ACTION_OUTSIDE))
                        assertEquals(1, dismissRequests)
                        panel.advanceMotion(7_000_000_000L, visible = true, durationScale = 1f)
                        panel.advanceMotion(7_016_000_000L, visible = true, durationScale = 1f)
                        assertTrue(menu.isAttachedToWindow && panel.alpha > 0f && panel.alpha < 1f)
                        dispatch(menu, row, held, MotionEvent.ACTION_UP)
                        assertEquals("The held press must not activate during exit", 0, actions)
                        assertRowAdmission(row, admitted = false)
                        assertFalse(row.performClick())
                        assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                        assertEquals("Direct activation must also reject the outgoing row", 0, actions)

                        menu.bindTheme(NativeColorScheme.fallback(true))
                        menu.bindActions(menuActions)
                        assertSame(row, rows[0])
                        assertRowAdmission(row, admitted = false)
                        assertFalse(row.performClick())
                        assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                        assertEquals(0, actions)

                        menu.expanded(true)
                        panel.advanceMotion(12_016_000_000L, visible = true, durationScale = 1f)
                        assertEquals(1f, panel.alpha, 0f)
                        assertTrue(menu.isFocusableInTouchMode)
                        assertFalse("Popup focus parking must not tint the entire panel",
                            menu.defaultFocusHighlightEnabled)
                        assertRowAdmission(row, admitted = true)
                        assertFalse(disabled.createAccessibilityNodeInfo().isEnabled)
                        assertFalse(disabled.performClick())
                        assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                        assertEquals(1, actions)

                        menu.expanded(false)
                        menu.expanded(true)
                        val fresh = SystemClock.uptimeMillis()
                        assertTrue(dispatch(menu, row, fresh, MotionEvent.ACTION_DOWN))
                        assertTrue(dispatch(menu, row, fresh, MotionEvent.ACTION_UP))
                        assertEquals(2, actions)
                    } finally {
                        host.removeView(menu)
                    }
                }
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun assertRowAdmission(row: NativeMenuRowView<*>, admitted: Boolean) {
        val node = row.createAccessibilityNodeInfo()
        assertEquals(admitted, node.isEnabled)
        assertEquals(admitted, node.isClickable)
        assertEquals(admitted, node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
    }

    private fun dispatch(menu: NativePopupMenu<Int>, row: NativeMenuRowView<*>, downTime: Long, action: Int): Boolean {
        val label = row.getChildAt(0)
        val origin = IntArray(2).also(menu::getLocationOnScreen)
        val point = IntArray(2).also(label::getLocationOnScreen)
        val x = point[0] - origin[0] + label.width / 2f
        val y = point[1] - origin[1] + label.height / 2f
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        return try { menu.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
