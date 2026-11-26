package app.smallthingz.dropdownviewportqa

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeDropdown
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Real-window coverage; root owns execution, including normal navigation and immersive modes. */
@RunWith(AndroidJUnit4::class)
class DropdownViewportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun normalLongListUsesSafeWindowEdges() = scenario(fullscreen = false, short = false)
    @Test fun fullscreenLongListUsesAvailableWindowEdges() = scenario(fullscreen = true, short = false)
    @Test fun shortListStaysCompact() = scenario(fullscreen = false, short = true)
    @Test fun alreadyPaddedHostDoesNotSubtractInsetsTwice() = scenario(fullscreen = false, short = false, padded = true)
    @Test fun bottomFieldKeepsItsAnchorAndUsesTheSafeTop() = scenario(fullscreen = false, short = false, bottom = true)
    @Test fun shortViewportMiddleFieldAcceptsActualWindowTap() = scenario(fullscreen = false, short = false, shortViewport = true)

    private fun scenario(fullscreen: Boolean, short: Boolean, padded: Boolean = false,
        bottom: Boolean = false, shortViewport: Boolean = false) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            await("Activity did not become visible") { activity.hasWindowFocus() && activity.window.decorView.height > 0 }
            if (fullscreen) click(activity, "Screen: Normal")
            if (short) click(activity, "List: 40 choices")
            if (bottom) click(activity, "Field position: Middle")
            click(activity, "Try this dropdown")
            lateinit var appRoot: FrameLayout
            var shortHeight = 0
            instrumentation.runOnMainSync {
                appRoot = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
                val scale = activity.resources.displayMetrics.density
                if (padded) appRoot.setPadding((13 * scale).toInt(), (17 * scale).toInt(), (11 * scale).toInt(), (19 * scale).toInt())
                if (shortViewport) {
                    shortHeight = (320 * scale).toInt()
                    appRoot.layoutParams = appRoot.layoutParams.apply { height = shortHeight }
                }
            }
            await("Scenario field was not laid out") {
                !appRoot.isLayoutRequested && (!shortViewport || appRoot.height == shortHeight) &&
                    descendants(activity.window.decorView).filterIsInstance<NativeDropdown<*>>().singleOrNull()?.height?.let { it > 0 } == true
            }
            lateinit var anchor: View
            var rowHeight = 0
            instrumentation.runOnMainSync {
                anchor = descendants(activity.window.decorView).filterIsInstance<NativeDropdown<*>>().single()
                rowHeight = anchor.height
                tapThroughWindow(activity, anchor)
            }
            await("Dropdown did not fully expand") {
                val pane = pane(activity)
                pane != null && pane.getChildAt(0).alpha == NativeUiAppearance.Default.anchoredDropdown.scrimOpacity
            }
            instrumentation.runOnMainSync {
                val pane = checkNotNull(pane(activity))
                val wheel = pane.getChildAt(1) as ViewGroup
                val bitmap = Bitmap.createBitmap(wheel.width, wheel.height, Bitmap.Config.ARGB_8888)
                try {
                    // Force the actual production draw path, then read its clipping rectangle.
                    // Reflection is confined to this fixture; no diagnostic API is added to the library.
                    wheel.draw(Canvas(bitmap))
                    val rectangle = wheel.javaClass.getDeclaredField("rectangle").apply { isAccessible = true }
                        .get(wheel) as RectF
                    val origin = IntArray(2).also(wheel::getLocationOnScreen)
                    val visible = Rect().also(wheel::getWindowVisibleDisplayFrame)
                    val metrics = activity.windowManager.currentWindowMetrics
                    val insets = checkNotNull(wheel.rootWindowInsets).getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                    val expectedTop = maxOf(origin[1], visible.top, metrics.bounds.top + insets.top)
                    val expectedBottom = minOf(origin[1] + wheel.height, visible.bottom, metrics.bounds.bottom - insets.bottom)
                    val rows = optionRows(wheel)
                    if (short) {
                        assertEquals(3 * rowHeight - 1f, rectangle.height(), .6f)
                        assertEquals(3, rows.size)
                    } else {
                        assertEquals(expectedTop + .5f, origin[1] + rectangle.top, .6f)
                        assertEquals(expectedBottom - .5f, origin[1] + rectangle.bottom, .6f)
                    }
                    assertMountedViewport(wheel, if (short) 3 else 40, rows)
                    for (row in rows) {
                        val top = row.translationY
                        val fullyVisible = top >= rectangle.top - 1f && top + rowHeight <= rectangle.bottom + 1f
                        if (!fullyVisible) {
                            assertFalse("Clipped row became focusable", row.isFocusable)
                            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, row.importantForAccessibility)
                        }
                    }
                    val selected = rows.single { it.isSelected }
                    val anchorOrigin = IntArray(2).also(anchor::getLocationOnScreen)
                    assertEquals(anchorOrigin[1].toFloat(), origin[1] + selected.translationY, .6f)
                    assertEquals(if (short) false to false else true to true, overflow(wheel))
                } finally { bitmap.recycle() }
            }
            if (!short) {
                // Exercise the actual wheel state and finite accessibility scroll endpoints.
                instrumentation.runOnMainSync {
                    val wheel = checkNotNull(pane(activity)).getChildAt(1)
                    repeat(40) { wheel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null) }
                }
                await("Wheel did not settle at its first finite option") {
                    pane(activity)?.getChildAt(1)?.let { position(it) == 0f } == true
                }
                instrumentation.runOnMainSync {
                    val wheel = checkNotNull(pane(activity)).getChildAt(1)
                    assertMountedViewport(wheel as ViewGroup, 40)
                    assertEquals(false to true, overflow(wheel))
                    assertFalse(wheel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
                    repeat(40) { wheel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null) }
                }
                await("Wheel did not settle at its last finite option") {
                    pane(activity)?.getChildAt(1)?.let { position(it) == 39f } == true
                }
                instrumentation.runOnMainSync {
                    val wheel = checkNotNull(pane(activity)).getChildAt(1)
                    assertMountedViewport(wheel as ViewGroup, 40)
                    assertEquals(true to false, overflow(wheel))
                    assertFalse(wheel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
                }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun tapThroughWindow(activity: MainActivity, anchor: View) {
        val origin = IntArray(2).also(activity.window.decorView::getLocationOnScreen)
        val point = IntArray(2).also(anchor::getLocationOnScreen)
        val x = point[0] - origin[0] + anchor.width / 2f
        val y = point[1] - origin[1] + anchor.height / 2f
        val down = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(activity.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
    }

    private fun session(wheel: View): Any = wheel.javaClass.getDeclaredField("this\$0")
        .apply { isAccessible = true }.get(wheel)!!

    private fun position(wheel: View): Float = session(wheel).let { session ->
        session.javaClass.getDeclaredField("position").apply { isAccessible = true }.getFloat(session)
    }

    private fun optionRows(wheel: ViewGroup): List<NativeDropdown<*>> =
        (0 until wheel.childCount).map(wheel::getChildAt).filterIsInstance<NativeDropdown<*>>()

    /** Check actual mounted options against pixel coverage, independently of the mount planner. */
    private fun assertMountedViewport(wheel: ViewGroup, expectedCount: Int, rows: List<NativeDropdown<*>> = optionRows(wheel)) {
        val owner = session(wheel)
        fun integer(name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.getInt(owner)
        assertEquals("Logical choices survive sparse mounting", expectedCount, integer("lastPosition") + 1)
        val height = integer("rowHeight")
        val anchor = integer("anchorY")
        val top = integer("safeTop")
        val bottom = integer("safeBottom")
        val above = ceil((anchor - top).toDouble() / height).toInt()
        val below = ceil((bottom - anchor - height).toDouble() / height).toInt()
        // Selected slot, one overscan on each side, and a possible fractional edge row.
        assertTrue("Mounted rows stay within viewport plus overscan",
            rows.size in 1..minOf(expectedCount, above + below + 4))
        val position = position(wheel)
        val indices = rows.map { row ->
            val logical = position + (row.translationY - anchor) / height
            val index = logical.roundToInt()
            assertEquals("Row translation retains its logical slot", index.toFloat(), logical, .01f)
            assertTrue(index in 0 until expectedCount)
            index
        }
        assertEquals("Mounted logical rows are unique", indices.size, indices.toSet().size)
        assertEquals("Mounted children retain logical traversal order", indices.sorted(), indices)
        for (index in 0 until expectedCount) {
            val y = anchor + (index - position) * height
            if (y < bottom && y + height > top) {
                assertTrue("Visible logical row $index is mounted", index in indices)
                val row = rows[indices.indexOf(index)]
                assertEquals(height, row.height)
                assertEquals(wheel.width, row.width)
            }
        }
    }

    /** Read the same live clipping predicates that gate the production fade/chevron draw calls. */
    private fun overflow(wheel: View): Pair<Boolean, Boolean> {
        val session = session(wheel)
        val viewport = session.javaClass.getDeclaredField("viewport").apply { isAccessible = true }.get(session)!!
        val position = position(wheel)
        val above = viewport.javaClass.getDeclaredMethod("overflowsAbove", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(viewport, position) as Boolean
        val below = viewport.javaClass.getDeclaredMethod("overflowsBelow", Float::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(viewport, position,
                session.javaClass.getDeclaredField("lastPosition").apply { isAccessible = true }.getInt(session)) as Boolean
        return above to below
    }

    private fun pane(activity: MainActivity): FrameLayout? = descendants(activity.window.decorView)
        .filterIsInstance<FrameLayout>().firstOrNull { it.accessibilityPaneTitle == "Choose an option" }

    private fun click(activity: MainActivity, label: String) {
        instrumentation.runOnMainSync {
            val button = descendants(activity.window.decorView).filterIsInstance<NativeMaterialButtonView>().single {
                descendants(it).filterIsInstance<ParagraphView>().any { paragraph -> paragraph.getText().toString() == label }
            }
            assertTrue(button.performClick())
        }
        instrumentation.waitForIdleSync()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            SystemClock.sleep(16)
        }
        fail(message)
    }
}
