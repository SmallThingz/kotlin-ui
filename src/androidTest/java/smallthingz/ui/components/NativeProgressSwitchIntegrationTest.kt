package smallthingz.ui.components

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.injector.InjectProgressSwitch
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class NativeProgressSwitchIntegrationTest {
    @Test fun progressKeepsOneSwitchNodeAndInheritedActivationGuards() = withWindow { activity ->
        val changes = mutableListOf<Boolean>()
        var parentClicks = 0
        val scheme = NativeColorScheme.fallback(darkTheme = false)
        val mark = NativeMaterialSwitchView(activity, scheme)
        val control = object : InjectProgressSwitch(activity, scheme, "Feature enabled",
            onChanged = { changes += it }, mark = mark) {
            override val accessibilityClickLabel: CharSequence get() = "Cancel request"
        }
        val parent = FrameLayout(activity).apply {
            setOnClickListener { parentClicks++ }
            addView(control, FrameLayout.LayoutParams(-2, -2))
        }
        control.bindProgress(checked = true, enabled = true, progress = "Applying")
        activity.setContentView(parent)
        return@withWindow {
            assertTrue(control.isAttachedToWindow)
            val node = checkNotNull(control.createAccessibilityNodeInfo())
            try {
                assertEquals(activity.getString(smallthingz.ui.R.string.switch_role),
                    AccessibilityNodeInfoCompat.wrap(node).roleDescription.toString())
                assertEquals("Feature enabled", node.contentDescription.toString())
                assertTrue(node.isCheckable && node.isChecked && node.isEnabled)
                assertEquals("Applying", AccessibilityNodeInfoCompat.wrap(node).stateDescription.toString())
                assertEquals("Cancel request", node.actionList.single {
                    it.id == AccessibilityNodeInfo.ACTION_CLICK
                }.label.toString())
                assertEquals(0, node.childCount)
            } finally {
                node.recycle()
            }
            val children = arrayListOf<View>()
            control.addChildrenForAccessibility(children)
            assertTrue(children.isEmpty())
            val spinner = mark.getChildAt(0) as NativeCircularProgress
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, spinner.importantForAccessibility)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, mark.importantForAccessibility)

            tap(parent, control)
            assertEquals(listOf(false), changes)
            assertTrue(control.requestFocusFromTouch())
            for (code in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER)) {
                assertTrue(control.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)))
                assertTrue(control.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)))
            }
            assertEquals(listOf(false, false, false), changes)
            assertTrue(control.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(listOf(false, false, false, false), changes)
            assertEquals(0, parentClicks)

            control.bindProgress(checked = true, enabled = false, progress = "Finishing")
            assertSame(spinner, mark.getChildAt(0))
            val disabled = checkNotNull(control.createAccessibilityNodeInfo())
            try {
                assertTrue(disabled.isChecked)
                assertFalse(disabled.isEnabled)
                assertEquals("Finishing", AccessibilityNodeInfoCompat.wrap(disabled).stateDescription.toString())
                assertFalse(disabled.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            } finally {
                disabled.recycle()
            }
            tap(parent, control)
            for (code in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER)) {
                control.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                control.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            assertFalse(control.performClick())
            assertFalse(control.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(4, changes.size)
            assertEquals(0, parentClicks)

            for (checked in listOf(true, false)) {
                control.bindProgress(checked, enabled = true, progress = null)
                assertEquals(0, mark.childCount)
                val idle = checkNotNull(control.createAccessibilityNodeInfo())
                try {
                    assertEquals(checked, idle.isChecked)
                    assertEquals(activity.getString(if (checked) smallthingz.ui.R.string.state_on
                        else smallthingz.ui.R.string.state_off),
                        AccessibilityNodeInfoCompat.wrap(idle).stateDescription.toString())
                } finally {
                    idle.recycle()
                }
            }
            assertTrue(control.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(listOf(false, false, false, false, true), changes)
            control.bindProgress(checked = false, enabled = true, progress = "Retrying")
            assertSame(spinner, mark.getChildAt(0))
            parent.removeView(control)
            assertFalse(spinner.isAttachedToWindow)
            assertFalse(control.performClick())
            assertFalse(control.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertNull(control.createAccessibilityNodeInfo())
            assertEquals(5, changes.size)
            control.close()
        }
    }

    @Test fun slotKeepsIntrinsicSwitchAndMinimumTargetAcrossProgressAndLayoutDirections() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val scheme = NativeColorScheme.fallback(darkTheme = false)
            val mark = NativeMaterialSwitchView(context, scheme)
            val control = NativeProgressSwitch(context, scheme, "Feature enabled", {}, mark = mark)
            fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
            fun measure(width: Int, height: Int) {
                control.measure(width, height)
                control.layout(0, 0, control.measuredWidth, control.measuredHeight)
                assertEquals((control.width - mark.width) / 2, mark.left)
                assertEquals((control.height - mark.height) / 2, mark.top)
            }
            val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                control.layoutDirection = direction
                for (progress in listOf(null, "Applying", null)) {
                    control.bindProgress(checked = true, enabled = true, progress = progress)
                    measure(unspecified, unspecified)
                    assertEquals(dp(52), control.width)
                    assertEquals(dp(48), control.height)
                    assertEquals(dp(52), mark.width)
                    assertEquals(dp(32), mark.height)
                    measure(View.MeasureSpec.makeMeasureSpec(dp(64), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dp(48), View.MeasureSpec.EXACTLY))
                    assertEquals(dp(64), control.width)
                    assertEquals(dp(48), control.height)
                    assertEquals(dp(52), mark.width)
                    measure(View.MeasureSpec.makeMeasureSpec(dp(40), View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(dp(24), View.MeasureSpec.AT_MOST))
                    assertEquals(dp(40), control.width)
                    assertEquals(dp(24), control.height)
                    assertTrue(mark.width <= control.width && mark.height <= control.height)
                }
            }
            control.close()
        }
    }

    private fun tap(parent: View, child: View) {
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, start + if (action == MotionEvent.ACTION_UP) 16 else 0,
                action, child.left + child.width / 2f, child.top + child.height / 2f, 0)
            try {
                assertTrue(parent.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
    }

    private fun withWindow(mount: (Activity) -> () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            lateinit var assertions: () -> Unit
            instrumentation.runOnMainSync { assertions = mount(activity) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync(assertions)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
