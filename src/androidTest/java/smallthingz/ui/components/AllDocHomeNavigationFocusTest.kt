package smallthingz.ui.components

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.alldoc.AllDocHomeNavigation
import kotlin.math.roundToInt

/** Attached native dispatch/node fixtures; no hardware-key or screen-reader traversal claim.
 * Accessibility focus requires the same UiAutomation touch-exploration gate as NativeRetiredTextFocusTest.
 */
@RunWith(AndroidJUnit4::class)
class AllDocHomeNavigationFocusTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun keyboardFocusAndKeyUpActivationSurviveThreeTwoOneColumnReflow() = mounted { f ->
        main { assertTrue(f.library.requestFocus()) }
        for (columns in listOf(3, 2, 1, 2, 3)) {
            val before = f.requests.size
            reflow(f, columns)
            main {
                assertState(f, columns)
                assertSame(f.library, f.root.findFocus())
                navigate(f, KeyEvent.KEYCODE_MOVE_HOME, "Recents")
                navigate(f, KeyEvent.KEYCODE_MOVE_END, "Folders")
                navigate(f, KeyEvent.KEYCODE_DPAD_LEFT, "Library")
                navigate(f, KeyEvent.KEYCODE_DPAD_RIGHT, "Folders")
                if (columns < 3) {
                    navigate(f, KeyEvent.KEYCODE_MOVE_HOME, "Recents")
                    if (columns == 1) {
                        navigate(f, KeyEvent.KEYCODE_DPAD_DOWN, "Library")
                        navigate(f, KeyEvent.KEYCODE_DPAD_DOWN, "Folders")
                        navigate(f, KeyEvent.KEYCODE_DPAD_UP, "Library")
                    } else {
                        navigate(f, KeyEvent.KEYCODE_DPAD_DOWN, "Folders")
                        navigate(f, KeyEvent.KEYCODE_DPAD_UP, "Recents")
                        navigate(f, KeyEvent.KEYCODE_DPAD_RIGHT, "Library")
                    }
                } else navigate(f, KeyEvent.KEYCODE_DPAD_LEFT, "Library")
                assertEquals("Reflow and focus navigation must not activate", before, f.requests.size)
                assertState(f, columns)
                for (key in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE)) {
                    val count = f.requests.size
                    val down = KeyEvent(KeyEvent.ACTION_DOWN, key)
                    assertTrue(f.root.dispatchKeyEvent(down))
                    assertEquals("Activation must wait for key-up", count, f.requests.size)
                    assertEquals("Recents", f.bar.selectedId)
                    assertTrue(f.root.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP)))
                    assertEquals(count + 1, f.requests.size)
                    assertEquals("Library", f.requests.last())
                    assertSame(f.library, f.root.findFocus())
                    assertState(f, columns) // An accepted request still needs an authoritative bind.
                }
            }
        }
    }

    @Test fun actualAccessibilityFocusNodesAndActionsSurviveColumnReflow() = withTouchExploration {
        mounted { f ->
            main {
                assertTrue(f.library.requestFocus())
                assertTrue("Must acquire actual framework accessibility focus",
                    f.library.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
                assertTrue(f.library.isAccessibilityFocused)
            }
            try {
                for (columns in listOf(3, 2, 1, 2, 3)) {
                    val before = f.requests.size
                    reflow(f, columns)
                    main {
                        assertState(f, columns, accessibilityFocus = "Library")
                        assertSame(f.library, f.root.findFocus())
                        assertEquals("Reflow must not activate", before, f.requests.size)
                        for (action in listOf(AccessibilityNodeInfo.ACTION_CLICK, AccessibilityNodeInfo.ACTION_SELECT)) {
                            val count = f.requests.size
                            assertTrue(f.library.performAccessibilityAction(action, null))
                            assertEquals(count + 1, f.requests.size)
                            assertEquals("Library", f.requests.last())
                            assertState(f, columns, accessibilityFocus = "Library")
                        }
                    }
                }
                val before = f.requests.size
                main { f.bar.bind(f.options, "Library") }
                awaitLayout(f)
                main {
                    assertState(f, 3, selected = "Library", accessibilityFocus = "Library")
                    assertSame(f.library, f.root.findFocus())
                    assertEquals("Owner selection binding must stay silent", before, f.requests.size)
                    assertTrue(f.library.performAccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null))
                    assertFalse(f.library.isAccessibilityFocused)
                }
            } finally {
                main {
                    if (f.library.isAccessibilityFocused) f.library.performAccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
                }
            }
        }
    }

    private class Fixture(activity: NativeMenuAccessibilityTestActivity) {
        val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
            fontScale = 2f
            densityDpi = 160
        })
        val root = FrameLayout(activity)
        val options = listOf("Recents", "Library", "Folders").map {
            NativeChoice(it, it, ColorDrawable(Color.WHITE), contentDescription = it)
        }
        val requests = mutableListOf<String>()
        val bar = AllDocHomeNavigation.create(context, NativeColorScheme.fallback(false).pageControls)
        lateinit var chips: Map<String, NativeChip>
            private set

        fun bind() {
            bar.layoutDirection = View.LAYOUT_DIRECTION_LTR
            bar.onSelectionRequest = { requests += it; true }
            bar.bind(options, "Recents")
            chips = options.associate { it.id to checkNotNull(bar.choiceView(it.id)) }
        }
        val library get() = chips.getValue("Library")
        val originalNodes = mutableMapOf<String, AccessibilityNodeInfo>()
        var cellWidth = 0
        val gap get() = (bar.gapDp * context.resources.displayMetrics.density).roundToInt()
    }

    private fun navigate(f: Fixture, key: Int, target: String) {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, key)
        assertTrue(f.root.dispatchKeyEvent(down))
        assertSame(f.chips.getValue(target), f.root.findFocus())
        assertTrue(f.root.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP)))
        assertSame(f.chips.getValue(target), f.root.findFocus())
    }

    private fun assertState(f: Fixture, columns: Int, selected: String = "Recents", accessibilityFocus: String? = null) {
        assertEquals(2f, f.bar.resources.configuration.fontScale, 0f)
        assertEquals(selected, f.bar.selectedId)
        assertEquals(3, f.bar.childCount)
        assertEquals((3 + columns - 1) / columns, f.chips.values.map { it.top }.distinct().size)
        f.options.forEachIndexed { index, option ->
            val chip = checkNotNull(f.bar.choiceView(option.id))
            assertSame(f.chips.getValue(option.id), chip)
            assertSame(chip, f.bar.getChildAt(index))
            assertTrue(chip.isAttachedToWindow && chip.isShown)
            assertTrue(chip.width >= 48 && chip.height >= 48) // Fixture density is 160dpi.
            assertEquals(option.id == selected, chip.isChecked)
            assertEquals(option.id == accessibilityFocus, chip.isAccessibilityFocused)
            val node = checkNotNull(chip.createAccessibilityNodeInfo())
            try {
                assertEquals("Retain native node identity", f.originalNodes.getValue(option.id), node)
                assertEquals("android.app.ActionBar\$Tab", node.className.toString())
                assertEquals(option.label.toString(), node.contentDescription.toString())
                assertTrue(node.isEnabled && node.isClickable && node.isVisibleToUser)
                assertFalse(node.isCheckable)
                assertEquals(chip.hasFocus(), node.isFocused)
                assertEquals(option.id == accessibilityFocus, node.isAccessibilityFocused)
                assertEquals(option.id == selected, node.isSelected)
                assertTrue(node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK))
                assertTrue(node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SELECT))
                val item = checkNotNull(node.collectionItemInfo)
                assertEquals(index / columns, item.rowIndex)
                assertEquals(index % columns, item.columnIndex)
                assertEquals(option.id == selected, item.isSelected)
            } finally { node.recycle() }
        }
        val node = checkNotNull(f.bar.createAccessibilityNodeInfo())
        try {
            assertEquals("android.widget.TabWidget", node.className.toString())
            val collection = checkNotNull(node.collectionInfo)
            assertEquals((3 + columns - 1) / columns, collection.rowCount)
            assertEquals(columns, collection.columnCount)
            assertEquals(AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE, collection.selectionMode)
        } finally { node.recycle() }
    }

    private fun reflow(f: Fixture, columns: Int) {
        val width = columns * f.cellWidth + (columns - 1) * f.gap
        main {
            assertTrue("Fixture window must fit a visible three-column bar ($width px)", width <= f.root.width)
            f.bar.layoutParams = FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        awaitLayout(f)
        main { assertEquals(width, f.bar.width) }
    }

    private fun mounted(test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            as NativeMenuAccessibilityTestActivity
        var installed: Fixture? = null
        withRetiringTestActivity(activity, root = { installed?.root }, cleanup = {
            installed?.let { fixture ->
                try { fixture.originalNodes.values.forEach { it.recycle() } }
                finally { fixture.bar.close() }
            }
        }) {
            main {
                val f = Fixture(activity).also { installed = it }
                f.bind()
                // Keep the fixture independent of the device's previous touch-mode state.
                f.chips.values.forEach { it.isFocusableInTouchMode = true }
                f.root.addView(f.bar, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
                activity.setContentView(f.root)
            }
            val f = checkNotNull(installed)
            awaitLayout(f)
            main {
                // Use the measured label minima only to pick widths, then assert real row/node geometry.
                f.cellWidth = f.chips.values.maxOf { it.minimumCellWidth() } + 8
                f.chips.forEach { (id, chip) -> f.originalNodes[id] = checkNotNull(chip.createAccessibilityNodeInfo()) }
            }
            test(f)
        }
    }

    private fun awaitLayout(f: Fixture) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            var ready = false
            main {
                ready = f.bar.isAttachedToWindow && f.bar.isShown && f.bar.hasWindowFocus() &&
                    f.bar.isLaidOut && !f.root.isLayoutRequested && !f.bar.isLayoutRequested
            }
            if (ready) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Attached home navigation did not finish its ViewRoot traversal")
    }

    private fun withTouchExploration(test: () -> Unit) {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val serviceInfo = checkNotNull(automation.serviceInfo)
        val originalFlags = serviceInfo.flags
        val manager = checkNotNull(instrumentation.targetContext.getSystemService(AccessibilityManager::class.java))
        val ready = CountDownLatch(1)
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
            if (enabled) ready.countDown()
        }
        try {
            main {
                manager.addTouchExplorationStateChangeListener(listener)
                if (manager.isTouchExplorationEnabled) ready.countDown()
            }
            // Existing service-fixture gate: no secure/system setting changes or fake focus flags.
            serviceInfo.flags = originalFlags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            automation.serviceInfo = serviceInfo
            assertTrue("Requires UiAutomation touch exploration; node semantics alone are not focus coverage",
                ready.await(5, TimeUnit.SECONDS))
            main { assertTrue(manager.isEnabled && manager.isTouchExplorationEnabled) }
            test()
        } finally {
            try { main { manager.removeTouchExplorationStateChangeListener(listener) } }
            finally {
                serviceInfo.flags = originalFlags
                automation.serviceInfo = serviceInfo
            }
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
