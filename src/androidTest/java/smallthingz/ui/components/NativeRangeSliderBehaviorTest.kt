package smallthingz.ui.components

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.ControlPalette

@RunWith(AndroidJUnit4::class)
class NativeRangeSliderBehaviorTest {
    private val palette = ControlPalette(-1, -1, 0xff202020.toInt(), 0xff606060.toInt(),
        0xff3458a0.toInt(), -1, 0xff3458a0.toInt(), 0xffaaaaaa.toInt())

    private class Trace(val slider: NativeRangeSlider, val gap: Int) {
        val previews = mutableListOf<Pair<Int, Int>>()
        val commits = mutableListOf<Pair<Int, Int>>()
        val values get() = slider.lower to slider.upper

        init {
            slider.onRangePreview = { lower, upper -> record(previews, lower, upper) }
            slider.onRangeCommitted = { lower, upper -> record(commits, lower, upper) }
        }

        private fun record(events: MutableList<Pair<Int, Int>>, lower: Int, upper: Int) {
            assertEquals(values, lower to upper)
            assertTrue(lower >= 3 && upper <= 43 && upper - lower >= gap)
            assertEquals(0, (lower - 3) % 4)
            assertEquals(0, (upper - 3) % 4)
            events.add(lower to upper)
        }

        fun touch(action: Int, downTime: Long, fraction: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                slider.width * fraction, slider.height * .5f, 0)
            try { slider.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun withSlider(lower: Int = 11, upper: Int = 35, minGap: Int = 5, test: Trace.() -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            lateinit var slider: NativeRangeSlider
            instrumentation.runOnMainSync {
                slider = NativeRangeSlider(activity, palette, 3, 46, 4, lower, upper,
                    "Lower", "Upper", minGap = minGap)
                slider.layoutDirection = View.LAYOUT_DIRECTION_LTR
                activity.setContentView(slider, android.view.ViewGroup.LayoutParams(-1,
                    (64 * activity.resources.displayMetrics.density).toInt()))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(slider.isLaidOut)
                Trace(slider, ((minGap + 3) / 4) * 4).test()
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
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
}
