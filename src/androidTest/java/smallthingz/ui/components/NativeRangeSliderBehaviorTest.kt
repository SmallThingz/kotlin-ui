package smallthingz.ui.components

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.ControlPalette
import java.text.NumberFormat

@RunWith(AndroidJUnit4::class)
class NativeRangeSliderBehaviorTest {
    private val palette = ControlPalette(-1, -1, 0xff202020.toInt(), 0xff606060.toInt(),
        0xff3458a0.toInt(), -1, 0xff3458a0.toInt(), 0xffaaaaaa.toInt())

    private class Trace(val slider: NativeRangeSlider, val gap: Int, val min: Int, val last: Int, val step: Int) {
        val previews = mutableListOf<Pair<Int, Int>>()
        val commits = mutableListOf<Pair<Int, Int>>()
        val values get() = slider.lower to slider.upper

        init {
            slider.onRangePreview = { lower, upper -> record(previews, lower, upper) }
            slider.onRangeCommitted = { lower, upper -> record(commits, lower, upper) }
        }

        private fun record(events: MutableList<Pair<Int, Int>>, lower: Int, upper: Int) {
            assertEquals(values, lower to upper)
            assertTrue(lower >= min && upper <= last && upper - lower >= gap)
            assertEquals(0, (lower - min) % step)
            assertEquals(0, (upper - min) % step)
            events.add(lower to upper)
        }

        fun touch(action: Int, downTime: Long, fraction: Float) = touchAt(action, downTime, slider.width * fraction)

        fun touchAt(action: Int, downTime: Long, x: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                x, slider.height * .5f, 0)
            try { slider.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun withActivity(test: (Activity) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { test(activity) } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun withSlider(lower: Int = 11, upper: Int = 35, minGap: Int = 5,
        min: Int = 3, max: Int = 46, step: Int = 4, test: Trace.() -> Unit) =
        withActivity { activity ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            lateinit var slider: NativeRangeSlider
            instrumentation.runOnMainSync {
                slider = NativeRangeSlider(activity, palette, min, max, step, lower, upper,
                    "Lower", "Upper", minGap = minGap)
                slider.layoutDirection = View.LAYOUT_DIRECTION_LTR
                activity.setContentView(slider, android.view.ViewGroup.LayoutParams(-1,
                    (64 * activity.resources.displayMetrics.density).toInt()))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(slider.isLaidOut)
                Trace(slider, ((minGap + step - 1) / step) * step, min, min + (max - min) / step * step, step).test()
            }
        }

    @Test fun constructorAndBindsSilentlyNormalizeOnTheReachableGrid() =
        withSlider(lower = 35, upper = 2) {
            assertEquals(35 to 43, values)
            slider.setValues(-50, 100)
            assertEquals(3 to 43, values)
            slider.setValues(27, 7)
            assertEquals(27 to 35, values)
            slider.setValues(100, 100)
            assertEquals(35 to 43, values)
            slider.setValues(8, 32)
            assertTrue(slider.lower in listOf(7, 11))
            assertTrue(slider.upper in listOf(31, 35))
            assertTrue(previews.isEmpty())
            assertTrue(commits.isEmpty())
        }

    @Test fun dragClampsLowerAtRoundedGapAndCommitsOnlyOnRelease() = withSlider {
        val time = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, time, .2f)
        touch(MotionEvent.ACTION_MOVE, time, .95f)
        assertEquals(27 to 35, values)
        assertEquals(values, previews.last())
        assertTrue(commits.isEmpty())
        touch(MotionEvent.ACTION_UP, time, .95f)
        assertEquals(listOf(27 to 35), commits)
    }

    @Test fun cancelAndRebindRetireTheDragBeforeAStaleRelease() = withSlider {
        for (explicitCancel in listOf(false, true)) {
            slider.setValues(11, 35)
            previews.clear()
            val time = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, time, .8f)
            touch(MotionEvent.ACTION_MOVE, time, .5f)
            assertTrue(previews.isNotEmpty())
            if (explicitCancel) slider.cancelInteraction() else touch(MotionEvent.ACTION_CANCEL, time, .5f)
            val beforeBind = previews.toList()
            slider.setValues(7, 19)
            touch(MotionEvent.ACTION_UP, time, .8f)
            assertEquals(7 to 19, values)
            assertEquals(beforeBind, previews)
            assertTrue(commits.isEmpty())
        }
    }

    @Test fun dragCanSeparateCoincidentHandles() = withSlider(lower = 23, upper = 23, minGap = 0) {
        for (end in listOf(.1f, .9f)) {
            slider.setValues(23, 23)
            val time = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, time, .5f)
            touch(MotionEvent.ACTION_MOVE, time, end)
            touch(MotionEvent.ACTION_UP, time, end)
            if (end < .5f) {
                assertTrue(slider.lower < 23)
                assertEquals(23, slider.upper)
            } else {
                assertEquals(23, slider.lower)
                assertTrue(slider.upper > 23)
            }
            assertEquals(listOf(values), commits)
            commits.clear()
        }
    }

    @Test fun repeatedLowerKeyCommitsOnePairAndCancelledUpperKeyCannotCommit() = withSlider {
        val lower = slider.getChildAt(0)
        val upper = slider.getChildAt(1)
        val time = SystemClock.uptimeMillis()
        val right = KeyEvent.KEYCODE_DPAD_RIGHT
        fun key(child: View, action: Int, repeat: Int = 0) =
            child.dispatchKeyEvent(KeyEvent(time, time + repeat, action, right, repeat))
        assertTrue(key(lower, KeyEvent.ACTION_DOWN))
        assertTrue(key(lower, KeyEvent.ACTION_DOWN, 1))
        assertEquals(listOf(15 to 35, 19 to 35), previews)
        assertTrue(commits.isEmpty())
        key(lower, KeyEvent.ACTION_UP)
        assertEquals(listOf(19 to 35), commits)
        assertTrue(key(upper, KeyEvent.ACTION_DOWN))
        assertEquals(19 to 39, previews.last())
        slider.cancelInteraction()
        key(upper, KeyEvent.ACTION_UP)
        assertEquals(listOf(19 to 35), commits)
    }

    @Test fun labeledFocusableChildrenExposeRangeAndCommitAccessibleEdits() = withSlider {
        val handles = (0 until slider.childCount).map { slider.getChildAt(it) }.filter { it.isFocusable }
        assertEquals(2, handles.size)
        val setProgress = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS
        for ((index, child) in handles.withIndex()) {
            val node = child.createAccessibilityNodeInfo()
            try {
                assertTrue(node.isFocusable)
                assertEquals(SeekBar::class.java.name, node.className.toString())
                val label = if (index == 0) "Lower" else "Upper"
                assertTrue(node.contentDescription?.contains(label) == true || node.text?.contains(label) == true)
                assertEquals(if (index == 0) 11f else 35f, node.rangeInfo.current, 0f)
                assertTrue(setProgress in node.actionList)
            } finally { node.recycle() }
        }
        fun set(child: View, value: Float) = child.performAccessibilityAction(setProgress.id, Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
        })
        assertTrue(set(handles[0], 35f))
        assertEquals(listOf(27 to 35), commits)
        assertTrue(set(handles[1], 43f))
        assertEquals(listOf(27 to 35, 27 to 43), commits)
        set(handles[1], 43f)
        assertEquals(2, commits.size)
    }

    private fun right(thumb: View, time: Long, action: Int, repeat: Int = 0, flags: Int = 0): Boolean {
        val event = KeyEvent(time, maxOf(time, SystemClock.uptimeMillis()), action, KeyEvent.KEYCODE_DPAD_RIGHT,
            repeat, 0, -1, 0, flags)
        return thumb.dispatchKeyEvent(event)
    }

    private fun Trace.verifyRetirement(detach: Boolean) {
        val parent = slider.parent as ViewGroup
        val lower = slider.getChildAt(0)
        val upper = slider.getChildAt(1)
        val time = SystemClock.uptimeMillis()
        assertTrue(right(lower, time, KeyEvent.ACTION_DOWN))
        assertEquals(15 to 35, values)
        if (detach) parent.removeView(slider) else parent.visibility = View.INVISIBLE
        assertFalse(slider.isShown)
        assertEquals(!detach, slider.isAttachedToWindow)
        assertEquals(11 to 35, values)
        assertEquals(listOf(15 to 35, 11 to 35), previews)
        val set = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS
        for (thumb in listOf(lower, upper)) {
            val fresh = time + 1
            assertFalse(right(thumb, fresh, KeyEvent.ACTION_DOWN))
            right(thumb, fresh, KeyEvent.ACTION_UP)
            assertFalse(thumb.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
            val args = Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, if (thumb === lower) 19f else 43f)
            }
            assertFalse(thumb.performAccessibilityAction(set.id, args))
        }
        if (detach) parent.addView(slider) else parent.visibility = View.VISIBLE
        assertTrue(slider.isShown)
        right(lower, time, KeyEvent.ACTION_UP)
        assertEquals(11 to 35, values)
        assertEquals(listOf(15 to 35, 11 to 35), previews)
        assertTrue(commits.isEmpty())
        assertTrue(right(lower, time + 2, KeyEvent.ACTION_DOWN))
        assertTrue(right(lower, time + 2, KeyEvent.ACTION_UP))
        assertEquals(listOf(15 to 35), commits)
    }

    @Test fun hidingAncestorRetiresEditAndRejectsDirectThumbInput() = withSlider {
        verifyRetirement(detach = false)
    }

    @Test fun detachingFromWindowRetiresEditAndRejectsDirectThumbInput() = withSlider {
        verifyRetirement(detach = true)
    }

    @Test fun ownedRepeatAtBoundAndCanceledKeyUpHaveDistinctOutcomes() = withSlider(lower = 23) {
        val lower = slider.getChildAt(0)
        val time = SystemClock.uptimeMillis()
        for (cancel in listOf(true, false)) {
            val start = if (cancel) time else time + 5
            assertTrue(right(lower, start, KeyEvent.ACTION_DOWN))
            assertEquals(27 to 35, values)
            assertTrue(right(lower, start, KeyEvent.ACTION_DOWN, repeat = 1))
            assertEquals(listOf(27 to 35), previews)
            assertTrue(commits.isEmpty())
            assertTrue(right(lower, start, KeyEvent.ACTION_UP,
                flags = if (cancel) KeyEvent.FLAG_CANCELED else 0))
            if (cancel) {
                assertEquals(23 to 35, values)
                assertEquals(listOf(27 to 35, 23 to 35), previews)
                assertTrue(commits.isEmpty())
                previews.clear()
            } else {
                assertEquals(27 to 35, values)
                right(lower, start, KeyEvent.ACTION_UP)
                assertEquals(listOf(27 to 35), commits)
            }
        }
    }

    @Test fun boundaryDpadNavigatesFromLowerToUpperThenToAdjacentButton() = withActivity { activity ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var slider: NativeRangeSlider
        lateinit var next: Button
        val commits = mutableListOf<Pair<Int, Int>>()
        val previews = mutableListOf<Pair<Int, Int>>()
        instrumentation.runOnMainSync {
            val row = LinearLayout(activity).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
            slider = NativeRangeSlider(activity, palette, 0, 100, 5, 60, 100,
                "Lower", "Upper", minGap = 40)
            next = Button(activity).apply { text = "Next" }
            row.addView(slider, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(next)
            activity.setContentView(row)
            slider.onRangeCommitted = { l, u -> commits.add(l to u) }
            slider.onRangePreview = { l, u -> previews.add(l to u) }
        }
        instrumentation.waitForIdleSync()
        var wasInTouchMode = false
        instrumentation.runOnMainSync { wasInTouchMode = slider.isInTouchMode }
        try {
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync {
                assertTrue(activity.hasWindowFocus())
                assertTrue(slider.getChildAt(0).requestFocus())
            }
            for (destination in listOf(1, 2)) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertSame(if (destination == 1) slider.getChildAt(1) else next, activity.currentFocus)
                    assertEquals(60 to 100, slider.lower to slider.upper)
                    assertTrue(previews.isEmpty())
                    assertTrue(commits.isEmpty())
                }
            }
        } finally {
            instrumentation.setInTouchMode(wasInTouchMode)
        }
    }

    @Test fun largeAdjacentIntegersHaveExactSpokenValuesAndSingleStepScrolls() = withActivity { activity ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val max = Int.MAX_VALUE
        lateinit var slider: NativeRangeSlider
        instrumentation.runOnMainSync {
            slider = NativeRangeSlider(activity, palette, max - 8, max, 1,
                max - 4, max - 3, "Lower", "Upper")
            activity.setContentView(slider)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertTrue(slider.isAttachedToWindow)
            val format = NumberFormat.getIntegerInstance(activity.resources.configuration.locales[0])
            val commits = mutableListOf<Pair<Int, Int>>()
            slider.onRangeCommitted = { l, u -> commits.add(l to u) }
            fun assertExact(lower: Int, upper: Int) {
                assertEquals(lower to upper, slider.lower to slider.upper)
                for ((i, value) in listOf(lower, upper).withIndex()) {
                    val node = slider.getChildAt(i).createAccessibilityNodeInfo()
                    try {
                        assertEquals(format.format(value.toLong()),
                            AccessibilityNodeInfoCompat.wrap(node).stateDescription?.toString())
                    } finally { node.recycle() }
                }
            }
            assertExact(max - 4, max - 3)
            val forward = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            val backward = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            fun scroll(index: Int, action: Int, lower: Int, upper: Int) {
                val count = commits.size
                assertTrue(slider.getChildAt(index).performAccessibilityAction(action, null))
                assertExact(lower, upper)
                assertEquals(count + 1, commits.size)
                assertEquals(lower to upper, commits.last())
            }
            scroll(0, backward, max - 5, max - 3)
            scroll(0, forward, max - 4, max - 3)
            scroll(1, forward, max - 4, max - 2)
            scroll(1, backward, max - 4, max - 3)
        }
    }

    @Test fun coincidentThumbBoundsNavigateToPeerInBothDirections() = withActivity { activity ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val midpoint = 500_100
        var touchMode = false
        instrumentation.runOnMainSync { touchMode = activity.window.decorView.isInTouchMode }
        try {
            instrumentation.setInTouchMode(false)
            for (rtl in listOf(false, true)) for (gap in 0..1) {
                var callbacks = 0
                lateinit var slider: NativeRangeSlider
                instrumentation.runOnMainSync {
                    slider = NativeRangeSlider(activity, palette, 0, 1_000_000, 1,
                        midpoint, midpoint + gap, "Lower", "Upper", minGap = gap)
                    slider.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    activity.setContentView(slider)
                    slider.onRangePreview = { _, _ -> callbacks++ }
                    slider.onRangeCommitted = { _, _ -> callbacks++ }
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val lower = slider.getChildAt(0)
                    val upper = slider.getChildAt(1)
                    assertTrue(lower.width > 0)
                    assertEquals(lower.left, upper.left)
                    assertEquals(lower.right, upper.right)
                    assertTrue(lower.requestFocus())
                }
                for (destination in listOf(1, 0)) {
                    val right = (destination == 1) != rtl
                    instrumentation.sendKeyDownUpSync(if (right) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        assertSame(slider.getChildAt(destination), activity.currentFocus)
                        assertEquals(midpoint to (midpoint + gap), slider.lower to slider.upper)
                        assertEquals(0, callbacks)
                    }
                }
            }
        } finally { instrumentation.setInTouchMode(touchMode) }
    }

    @Test fun denseGridIgnoresThumbReleaseJitterButAcceptsTrackTapsAndDrags() =
        withSlider(250_000, 750_000, 0, min = 0, max = 1_000_000, step = 1) {
            fun center() = slider.getChildAt(0).let { (it.left + it.right) / 2f }
            val start = center()
            val slop = ViewConfiguration.get(slider.context).scaledTouchSlop
            for (offset in listOf(0, 1, slop - 1)) {
                val time = SystemClock.uptimeMillis()
                touchAt(MotionEvent.ACTION_DOWN, time, start)
                touchAt(MotionEvent.ACTION_UP, time, start + offset)
                assertEquals(250_000 to 750_000, values)
                assertTrue(previews.isEmpty())
                assertTrue(commits.isEmpty())
            }
            val tapX = slider.width * .4f
            val tap = SystemClock.uptimeMillis()
            touchAt(MotionEvent.ACTION_DOWN, tap, tapX)
            touchAt(MotionEvent.ACTION_UP, tap, tapX)
            assertTrue(slider.lower in 250_001 until 500_000)
            assertEquals(750_000, slider.upper)
            val tapped = values
            assertEquals(listOf(tapped), commits)
            val drag = SystemClock.uptimeMillis()
            touchAt(MotionEvent.ACTION_DOWN, drag, center())
            touchAt(MotionEvent.ACTION_MOVE, drag, start)
            val dragged = values
            assertTrue(dragged.first < tapped.first)
            assertEquals(tapped.second, dragged.second)
            assertEquals(dragged, previews.last())
            assertEquals(listOf(tapped), commits)
            touchAt(MotionEvent.ACTION_UP, drag, start)
            assertEquals(listOf(tapped, dragged), commits)
        }

    private enum class Change { STYLE, WIDTH, PADDING, DIRECTION, PALETTE }

    @Test fun stationaryReleaseAfterTrackGeometryChangesRollsBackButPalettePreservesDrag() {
        for (change in Change.values()) withSlider {
            val start = slider.getChildAt(0).let { (it.left + it.right) / 2f }
            val end = slider.width * .4f
            val time = SystemClock.uptimeMillis()
            touchAt(MotionEvent.ACTION_DOWN, time, start)
            touchAt(MotionEvent.ACTION_MOVE, time, end)
            val draft = values
            assertTrue(draft.first > 11)
            assertEquals(draft, previews.last())
            assertTrue(commits.isEmpty())
            when (change) {
                Change.STYLE -> slider.applyStyle(NativeSliderStyle(horizontalPaddingDp = 12f))
                Change.WIDTH -> slider.layout(slider.left, slider.top, slider.right - slider.width / 4, slider.bottom)
                Change.PADDING -> slider.setPadding(slider.width / 8, 0, 0, 0)
                Change.DIRECTION -> slider.layoutDirection = View.LAYOUT_DIRECTION_RTL
                Change.PALETTE -> slider.applyPalette(palette.copy(accent = 0xffb34830.toInt()))
            }
            touchAt(MotionEvent.ACTION_UP, time, end)
            if (change == Change.PALETTE) {
                assertEquals(draft, values)
                assertEquals(listOf(draft), commits)
            } else {
                assertEquals(change.name, 11 to 35, values)
                assertEquals(change.name, values, previews.last())
                assertTrue(change.name, commits.isEmpty())
            }
        }
    }
}
