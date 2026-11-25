package smallthingz.ui.components

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.common.DropdownSelectionPolicy
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Attached production wheel, native velocity/physics and real frame callbacks. Events are
 * dispatched to the wheel; actual window routing and causal old/new travel belong to viewport QA. */
@RunWith(AndroidJUnit4::class)
class NativeAnchoredDropdownInertiaIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private class Fixture(val activity: Activity, val root: FrameLayout, val anchor: View,
        val owner: NativeAnchoredDropdown, val optionCount: Int) {
        lateinit var wheel: ViewGroup
        val selections = ArrayList<Int>()
        var onSelection: (() -> Unit)? = null
    }

    private fun scenario(policy: DropdownSelectionPolicy = DropdownSelectionPolicy.PROGRESSIVE,
        collapseMillis: Long = 0, optionCount: Int = 40, partialEdges: Boolean = false, action: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var fixture: Fixture? = null
        try {
            main {
                val scale = activity.resources.displayMetrics.density
                val appearance = NativeUiAppearance.Default
                val root = FrameLayout(activity)
                val anchor = View(activity)
                root.addView(anchor, FrameLayout.LayoutParams((280 * scale).roundToInt(),
                    (64 * scale).roundToInt(), Gravity.CENTER))
                if (partialEdges) {
                    // Center one row in a four-row host. Both edges bisect a real option.
                    activity.setContentView(FrameLayout(activity).apply {
                        addView(root, FrameLayout.LayoutParams(-1, 4 * anchor.layoutParams.height, Gravity.CENTER))
                    })
                } else activity.setContentView(root)
                val owner = NativeAnchoredDropdown(activity, root, appearance,
                    appearance.resolveColorScheme(activity, false), Density(scale), createFontFamilyResolver(activity),
                    appearance.anchoredDropdown.copy(expandMillis = 0, settleMillis = 0, collapseMillis = collapseMillis))
                fixture = Fixture(activity, root, anchor, owner, optionCount)
            }
            val f = checkNotNull(fixture)
            await("Fixture window not ready") { f.anchor.isAttachedToWindow && f.anchor.height > 0 && activity.hasWindowFocus() }
            main {
                assertTrue(f.owner.open(f.anchor, NativeDropdownContent("Kinetic options", optionCount / 2,
                    (0 until optionCount).toList()) { "Choice $it" },
                    policy, {}, { f.selections += it; f.onSelection?.invoke() }))
                f.wheel = (f.root.getChildAt(1) as ViewGroup).getChildAt(1) as ViewGroup
            }
            action(f)
        } finally {
            main { try { fixture?.owner?.close() } finally { activity.finish() } }
        }
    }

    /** Real group rebinds retire each popup while retaining the field and its selection. */
    @Test fun defaultLightAndAmoledOutlinePathsMatchAcrossDensities() {
        for (scale in listOf(1f, 1.5f, 2.625f)) {
            val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
                NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            lateinit var root: FrameLayout
            lateinit var group: smallthingz.ui.common.DropdownGroup
            lateinit var dropdownField: smallthingz.ui.common.DropdownField<Int>
            lateinit var lightTheme: smallthingz.ui.common.ControlTheme
            val content = smallthingz.ui.common.DropdownContent("Options", 20, (0 until 40).toList()) { "Choice $it" }
            val selections = ArrayList<Int>()
            var cleanupGroup: smallthingz.ui.common.DropdownGroup? = null
            var cleanupField: smallthingz.ui.common.DropdownField<Int>? = null
            try {
                main {
                    val appearance = NativeUiAppearance.Default.copy(anchoredDropdown =
                        NativeUiAppearance.Default.anchoredDropdown.copy(expandMillis = 0, settleMillis = 0, collapseMillis = 0))
                    lightTheme = smallthingz.ui.common.ControlTheme(appearance,
                        appearance.resolveColorScheme(activity, false), Density(scale), createFontFamilyResolver(activity))
                    root = FrameLayout(activity)
                    group = smallthingz.ui.common.DropdownGroup(lightTheme, { root }, { true })
                    cleanupGroup = group
                    dropdownField = group.field(activity, content) { selections += it }
                    cleanupField = dropdownField
                    root.addView(dropdownField.view, FrameLayout.LayoutParams((280 * scale).roundToInt(),
                        (64 * scale).roundToInt(), Gravity.CENTER))
                    activity.setContentView(root)
                }
                await("Field window not ready") {
                    dropdownField.view.isAttachedToWindow && dropdownField.view.height > 0 && activity.hasWindowFocus()
                }
                val retainedField = dropdownField.view
                var firstFieldBounds: android.graphics.Rect? = null
                var firstPath: android.graphics.RectF? = null
                var previousWheel: ViewGroup? = null
                for (dark in listOf(false, true, false)) {
                    main {
                        val colors = lightTheme.appearance.resolveColorScheme(activity, dark)
                        group.bindTheme(lightTheme.withColorScheme(colors))
                        dropdownField.bind(content)
                        assertFalse("Theme rebind must retire the previous popup", group.isExpanded)
                        previousWheel?.let { assertFalse(it.isAttachedToWindow) }
                    }
                    await("Rebound field layout pending") { !retainedField.isLayoutRequested }
                    lateinit var wheel: ViewGroup
                    lateinit var popup: NativeAnchoredDropdown
                    main {
                        assertSame(retainedField, dropdownField.view)
                        val bounds = android.graphics.Rect(retainedField.left, retainedField.top,
                            retainedField.right, retainedField.bottom)
                        if (firstFieldBounds == null) firstFieldBounds = bounds else assertEquals(firstFieldBounds, bounds)
                        assertEquals(content, field(dropdownField, "content"))
                        group.open(dropdownField, content)
                        assertTrue(group.isExpanded)
                        popup = field(checkNotNull(field(group, "current")), "dropdown") as NativeAnchoredDropdown
                        wheel = (root.getChildAt(1) as ViewGroup).getChildAt(1) as ViewGroup
                        previousWheel?.let { assertNotSame(it, wheel) }
                    }
                    await("Reopened wheel geometry missing") { wheel.width > 0 && wheel.height > 0 }
                    main {
                        val bitmap = android.graphics.Bitmap.createBitmap(wheel.width, wheel.height,
                            android.graphics.Bitmap.Config.ARGB_8888)
                        try {
                            val canvas = android.graphics.Canvas(bitmap)
                            fun drawBounds(expectedStroke: Float): android.graphics.RectF {
                                wheel.draw(canvas)
                                assertEquals(expectedStroke, (field(wheel, "paint") as android.graphics.Paint).strokeWidth, .001f)
                                return android.graphics.RectF().also {
                                    (field(wheel, "path") as android.graphics.Path).computeBounds(it, true)
                                    assertFalse("Actual drawn clip must be nonempty", it.isEmpty)
                                    assertEquals(expectedStroke / 2f, it.left, .001f)
                                    assertEquals(wheel.width - expectedStroke / 2f, it.right, .001f)
                                }
                            }
                            fun rows() = (0 until wheel.childCount).map { index -> wheel.getChildAt(index).let {
                                listOf(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat(), it.translationY)
                            } }
                            val defaultWidth = popup.outlineWidthDp
                            if (dark) assertEquals(1f, checkNotNull(defaultWidth), 0f) else assertNull(defaultWidth)
                            val defaultPath = drawBounds(scale)
                            if (firstPath == null) firstPath = defaultPath else assertEquals(firstPath, defaultPath)
                            val beforeRows = rows()
                            // Exercise the renderer's existing internal overrides without inventing a field reset API.
                            popup.outlineWidthDp = 0f
                            drawBounds(0f)
                            popup.outlineWidthDp = 2f
                            drawBounds(2f * scale)
                            popup.outlineWidthDp = defaultWidth
                            assertEquals(defaultPath, drawBounds(scale))
                            assertEquals(beforeRows, rows())
                            assertEquals(content, field(dropdownField, "content"))
                            assertTrue(selections.isEmpty())
                        } finally { bitmap.recycle() }
                        previousWheel = wheel
                    }
                }
            } finally {
                main {
                    try { cleanupGroup?.close() }
                    finally { try { cleanupField?.close() } finally { activity.finish() } }
                }
            }
        }
    }

    @Test fun accessibilityDismissClosesWithoutSelecting() {
        for (policy in DropdownSelectionPolicy.values()) scenario(policy) { f ->
            main {
                val node = f.wheel.createAccessibilityNodeInfo()
                assertTrue(node.isDismissable)
                assertTrue(node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS))
                assertTrue(f.wheel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, null))
            }
            await("Accessibility dismiss did not retire the popup") { !f.wheel.isAttachedToWindow }
            main {
                assertTrue(f.selections.isEmpty())
                assertFalse(f.wheel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, null))
            }
        }
    }

    @Test fun explicitFlingAndStopTouchDoNotCommitButNextTapCommitsPaintedRow() =
        scenario(DropdownSelectionPolicy.EXPLICIT) { f ->
            var released = 0f
            main {
                swipe(f); released = position(f); assertTrue(flinging(f)); assertTrue(f.selections.isEmpty())
                assertMountedViewport(f)
            }
            await("Explicit wheel did not coast") { assertMountedViewport(f); position(f) > released + .2f }
            main {
                assertTrue(f.selections.isEmpty())
                val now = SystemClock.uptimeMillis()
                touch(f, MotionEvent.ACTION_DOWN, now, now, slotY(f))
                assertFalse(flinging(f))
                touch(f, MotionEvent.ACTION_UP, now, now + 10, slotY(f))
                assertTrue("Stopping touch must not activate explicit choice", f.selections.isEmpty())
                assertFalse(field(session(f), "closing") as Boolean)
                assertEquals((field(session(f), "settled") as Int).toFloat(), position(f), 0f)
                // Tap the painted row now occupying the fixed selected slot.
                assertMountedViewport(f)
                val row = paintedSlotRow(f)
                val expected = checkNotNull(row.optionAccessibilityLabel).toString().removePrefix("Choice ").toInt()
                touch(f, MotionEvent.ACTION_DOWN, now + 20, now + 20, slotY(f))
                touch(f, MotionEvent.ACTION_UP, now + 20, now + 30, slotY(f))
                assertEquals(listOf(expected), f.selections)
                assertFalse(flinging(f))
            }
        }

    @Test fun upperPartialOptionBrowsesBeforeExplicitActivation() = checkPartialOptionTap(lower = false)

    @Test fun lowerPartialOptionBrowsesBeforeExplicitActivation() = checkPartialOptionTap(lower = true)

    private fun checkPartialOptionTap(lower: Boolean) = scenario(DropdownSelectionPolicy.EXPLICIT, partialEdges = true) { f ->
        val inset = 5f * f.root.resources.displayMetrics.density
        fun cueY() = if (lower) f.wheel.height - inset else inset
        fun rows() = (0 until f.wheel.childCount).map(f.wheel::getChildAt).filterIsInstance<NativeDropdown<*>>()
        fun tap(y: Float) {
            val now = SystemClock.uptimeMillis()
            touch(f, MotionEvent.ACTION_DOWN, now, now, y)
            touch(f, MotionEvent.ACTION_UP, now, now + 10, y)
        }
        var expected = -1
        await("Clipped option missing under the overflow cue") {
            val y = cueY()
            val edge = rows().singleOrNull { y >= it.translationY && y < it.translationY + it.height }
            if (edge == null) return@await false
            val clipped = if (lower) edge.translationY + edge.height > f.wheel.height else edge.translationY < 0f
            if (clipped && edge.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
                expected = edge.optionAccessibilityLabel.toString().removePrefix("Choice ").toInt()
                expected != f.optionCount / 2
            } else false
        }
        main { tap(cueY()) }
        assertTrue("A clipped edge tap must only browse", f.selections.isEmpty())
        await("Edge option did not reach the anchor fully visible") {
            f.wheel.isAttachedToWindow && rows().any { row ->
                row.optionAccessibilityLabel.toString() == "Choice $expected" && row.isFocusable && !row.isSelected &&
                    row.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES &&
                    abs(row.translationY + row.height / 2f - slotY(f)) < .5f
            }
        }
        assertTrue(f.selections.isEmpty())
        main { tap(slotY(f)) }
        assertEquals(listOf(expected), f.selections)
    }

    @Test fun evictedRowActionCannotCommitAfterItsLogicalSlotIsRemounted() =
        scenario(DropdownSelectionPolicy.EXPLICIT, optionCount = 200) { f ->
            main {
                val initiallySelected = paintedSlotRow(f)
                val initialLabel = checkNotNull(initiallySelected.optionAccessibilityLabel).toString()
                assertTrue(initiallySelected.isSelected)
                // A duplicate choice intentionally does not publish. Browse to a different
                // uncommitted choice so the final live action must deliver one new selection.
                assertTrue(f.wheel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
                val originalPosition = position(f)
                val retired = paintedSlotRow(f)
                val candidateLabel = checkNotNull(retired.optionAccessibilityLabel).toString()
                assertNotEquals("Positive control must choose a different option", initialLabel, candidateLabel)
                assertTrue("Browsing retains the original selection", initiallySelected.isSelected)
                assertFalse("Browsing does not select the candidate", retired.isSelected)
                assertTrue("Explicit browsing does not publish", f.selections.isEmpty())
                val expected = candidateLabel.removePrefix("Choice ").toInt()
                // Keep the actual production action, as well as the View, across eviction.
                @Suppress("UNCHECKED_CAST")
                val retainedAction = field(retired, "open") as (View, NativeDropdownContent<*>) -> Unit
                val retainedContent = field(retired, "content") as NativeDropdownContent<*>
                fun jumpTo(target: Float) {
                    val now = SystemClock.uptimeMillis()
                    val y = slotY(f)
                    val end = y - (target - position(f)) * f.anchor.height
                    touch(f, MotionEvent.ACTION_DOWN, now - 32, now - 32, y)
                    touch(f, MotionEvent.ACTION_MOVE, now - 32, now - 16, end)
                    touch(f, MotionEvent.ACTION_CANCEL, now - 32, now, end)
                    assertEquals(target, position(f), 0f)
                    assertFalse(flinging(f))
                    assertMountedViewport(f)
                }
                fun assertRetiredActionRejected() {
                    assertFalse(retired.performClick())
                    retainedAction(retired, retainedContent)
                    assertTrue(f.selections.isEmpty())
                    assertFalse(field(session(f), "closing") as Boolean)
                    assertFalse(field(session(f), "disposed") as Boolean)
                }
                jumpTo((f.optionCount - 1).toFloat())
                assertFalse("Old slot row was actually evicted", retired.isAttachedToWindow)
                assertRetiredActionRejected()
                jumpTo(originalPosition)
                val replacement = paintedSlotRow(f)
                assertNotSame("Returning creates a new owner for the same logical slot", retired, replacement)
                assertFalse("Uncommitted candidate stays unselected after remount", replacement.isSelected)
                val selected = (0 until f.wheel.childCount).map(f.wheel::getChildAt)
                    .filterIsInstance<NativeDropdown<*>>().single { it.isSelected }
                assertEquals("Original selection survives eviction", initialLabel,
                    selected.optionAccessibilityLabel?.toString())
                assertRetiredActionRejected()
                assertTrue(replacement.performClick())
                assertEquals(listOf(expected), f.selections)
            }
        }

    @Test fun cancellationAndActivePointerLossNeverFlingAndReuseTrackerUntilRetirement() = scenario { f ->
        main {
            val now = SystemClock.uptimeMillis()
            val start = slotY(f)
            touch(f, MotionEvent.ACTION_DOWN, now - 40, now - 40, start)
            val tracker = field(f.wheel, "velocityTracker")
            touch(f, MotionEvent.ACTION_MOVE, now - 40, now - 20, start - f.anchor.height)
            touch(f, MotionEvent.ACTION_CANCEL, now - 40, now, start - f.anchor.height)
            assertFalse(flinging(f))
            assertNull(field(f.wheel, "scroller"))
            assertEquals(-1, field(f.wheel, "pointer"))
            touch(f, MotionEvent.ACTION_DOWN, now + 1, now + 1, start)
            assertSame(tracker, field(f.wheel, "velocityTracker"))
            touch(f, MotionEvent.ACTION_MOVE, now + 1, now + 15, start - f.anchor.height, pointerId = 7)
            touch(f, MotionEvent.ACTION_UP, now + 1, now + 30, start - f.anchor.height)
            assertFalse(flinging(f))
            assertNull(field(f.wheel, "scroller"))
            assertEquals(-1, field(f.wheel, "pointer"))
            f.owner.close()
            assertNull(field(f.wheel, "velocityTracker"))
            assertNull(field(f.wheel, "scroller"))
        }
    }

    @Test fun progressiveSelectionCanRetireOwnerDuringFlingWithoutRescheduling() = scenario { f ->
        main {
            swipe(f)
            assertTrue(flinging(f))
            f.onSelection = { f.owner.close() }
        }
        await("Progressive fling did not publish and retire") { field(session(f), "disposed") as Boolean }
        main {
            val before = position(f)
            val count = f.selections.size
            assertFalse(flinging(f))
            assertNull(field(f.wheel, "velocityTracker"))
            assertNull(field(f.wheel, "scroller"))
            assertFalse(f.wheel.isAttachedToWindow)
            (field(f.wheel, "flingFrame") as Runnable).run()
            assertEquals(before, position(f), 0f)
            assertEquals(count, f.selections.size)
        }
    }

    @Test fun cancelledTouchDuringCollapseStillRetiresTheSession() = scenario(collapseMillis = 150) { f ->
        main {
            // Begin the actual non-tap-indication collapse through Back dispatch.
            val pane = f.wheel.parent as View
            pane.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
            assertTrue(field(session(f), "closing") as Boolean)
            assertTrue((field(session(f), "reveal") as Float) > 0f)
            val now = SystemClock.uptimeMillis()
            touch(f, MotionEvent.ACTION_DOWN, now, now, slotY(f))
            touch(f, MotionEvent.ACTION_CANCEL, now, now + 1, slotY(f))
        }
        await("Cancelled closing touch stranded the modal") { field(session(f), "disposed") as Boolean }
    }

    @Test fun focusLossDuringExplicitCollapseCannotStrandAnUntouchableModal() =
        scenario(DropdownSelectionPolicy.EXPLICIT, collapseMillis = 150) { f ->
            main {
                (f.wheel.parent as View).dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_BACK))
                assertTrue(field(session(f), "closing") as Boolean)
                f.wheel.onWindowFocusChanged(false)
                assertTrue(field(session(f), "disposed") as Boolean)
                assertFalse(f.wheel.isAttachedToWindow)
                assertFalse(flinging(f))
                assertTrue(f.selections.isEmpty())
            }
        }

    @Test fun releaseBeyondSlopWithoutMoveReopensAPartiallyClosingSession() = scenario(collapseMillis = 150) { f ->
        main {
            (f.wheel.parent as View).dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
                android.view.KeyEvent.KEYCODE_BACK))
            val oldMotion = checkNotNull(field(session(f), "animator")) as android.animation.ValueAnimator
            assertTrue(field(session(f), "closing") as Boolean)
            val now = SystemClock.uptimeMillis()
            touch(f, MotionEvent.ACTION_DOWN, now - 48, now - 48, slotY(f))
            // The owner sees no MOVE: release itself promotes the gesture to a drag.
            touch(f, MotionEvent.ACTION_UP, now - 48, now, slotY(f) - f.anchor.height)
            assertFalse("Old collapse must lose animation ownership", oldMotion.isStarted)
            assertFalse(field(session(f), "closing") as Boolean)
            assertFalse(field(session(f), "disposed") as Boolean)
            assertEquals(1f, field(session(f), "reveal") as Float, 0f)
        }
        // Let the original collapse's deadline pass without blocking the main looper.
        SystemClock.sleep(200)
        main {
            assertTrue(f.wheel.isAttachedToWindow)
            assertFalse(field(session(f), "disposed") as Boolean)
        }
    }

    private fun swipe(f: Fixture) {
        val now = SystemClock.uptimeMillis()
        val down = now - 48
        val start = slotY(f)
        touch(f, MotionEvent.ACTION_DOWN, down, down, start)
        for (sample in 1..3) touch(f, MotionEvent.ACTION_MOVE, down, down + sample * 12,
            start - f.anchor.height * sample / 4f)
        touch(f, MotionEvent.ACTION_UP, down, now, start - f.anchor.height)
    }

    private fun touch(f: Fixture, action: Int, down: Long, time: Long, y: Float, pointerId: Int = 0) {
        val properties = MotionEvent.PointerProperties().apply { id = pointerId; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coordinates = MotionEvent.PointerCoords().apply { x = f.wheel.width / 2f; this.y = y; pressure = 1f; size = 1f }
        val event = MotionEvent.obtain(down, time, action, 1, arrayOf(properties), arrayOf(coordinates),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue(f.wheel.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    private fun paintedSlotRow(f: Fixture): NativeDropdown<*> =
        (0 until f.wheel.childCount).map(f.wheel::getChildAt).filterIsInstance<NativeDropdown<*>>().single {
            it.height == f.anchor.height && abs(it.translationY - (field(session(f), "anchorY") as Int)) < .5f
        }

    /** Observe real child geometry during motion; do not repeat firstMounted/lastMounted. */
    private fun assertMountedViewport(f: Fixture) {
        val owner = session(f)
        val anchor = field(owner, "anchorY") as Int
        val top = field(owner, "safeTop") as Int
        val bottom = field(owner, "safeBottom") as Int
        val height = f.anchor.height
        assertEquals(f.optionCount - 1, field(owner, "lastPosition"))
        val above = ceil((anchor - top).toDouble() / height).toInt()
        val below = ceil((bottom - anchor - height).toDouble() / height).toInt()
        assertTrue("Mounted rows remain bounded during motion",
            f.wheel.childCount in 1..minOf(f.optionCount, above + below + 4))
        val position = position(f)
        val indices = (0 until f.wheel.childCount).map { slot ->
            val logical = position + (f.wheel.getChildAt(slot).translationY - anchor) / height
            val index = logical.roundToInt()
            assertEquals(index.toFloat(), logical, .01f)
            assertTrue(index in 0 until f.optionCount)
            index
        }
        assertEquals(indices.size, indices.toSet().size)
        assertEquals(indices.sorted(), indices)
        for (index in 0 until f.optionCount) {
            val y = anchor + (index - position) * height
            if (y < bottom && y + height > top) {
                assertTrue("Visible logical row $index survives motion", index in indices)
                val row = f.wheel.getChildAt(indices.indexOf(index))
                assertEquals(height, row.height)
                assertEquals(f.wheel.width, row.width)
            }
        }
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun session(f: Fixture): Any = checkNotNull(field(f.wheel, "this\$0"))
    private fun position(f: Fixture) = field(session(f), "position") as Float
    private fun flinging(f: Fixture) = field(f.wheel, "flinging") as Boolean
    private fun slotY(f: Fixture) = (field(session(f), "anchorY") as Int) + f.anchor.height / 2f
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
