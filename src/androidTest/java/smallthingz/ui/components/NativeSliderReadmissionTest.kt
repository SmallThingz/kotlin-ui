package smallthingz.ui.components

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.ControlPalette

/** Real Android View dispatch; no window focus, rendered-pixel or physical-keyboard claim. */
@RunWith(AndroidJUnit4::class)
class NativeSliderReadmissionTest {
    private val palette = ControlPalette(-1, -1, 0xff202020.toInt(), 0xff606060.toInt(),
        0xff3458a0.toInt(), -1, 0xff3458a0.toInt(), 0xffaaaaaa.toInt())
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private class Events {
        val userValues = mutableListOf<Int>()
        val finishes = mutableListOf<Pair<Int, Boolean>>()
        val commits = mutableListOf<Int>()
    }

    private fun slider(events: Events): NativeSlider {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return NativeSlider(context, palette).apply {
            min = 0
            max = 100
            progress = 50
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.EXACTLY))
            layout(0, 0, 400, 96)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) events.userValues += value
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
            onInteractionFinished = { value, cancelled -> events.finishes += value to cancelled }
            onValueCommitted = { events.commits += it }
        }
    }

    private fun keyboardPolicies(action: (NativeSlider, Events, NativeSliderKeyboardPolicy) -> Unit) = main {
        for (policy in NativeSliderKeyboardPolicy.values()) {
            val events = Events()
            val slider = slider(events)
            slider.keyProgressIncrement = 7
            slider.setKeyboardPolicy(policy)
            action(slider, events, policy)
        }
    }

    private fun beginTouch(slider: NativeSlider, events: Events) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
            slider.width * .5f, slider.height * .5f, 0)
        try { assertTrue(slider.dispatchTouchEvent(down)) } finally { down.recycle() }
        assertTrue(slider.isUserInteractionActive)
        events.userValues.clear()
        events.finishes.clear()
        events.commits.clear()
    }

    private fun key(slider: NativeSlider, code: Int, time: Long, up: Boolean = false, repeat: Int = 0): Boolean =
        slider.dispatchKeyEvent(KeyEvent(time, time + repeat,
            if (up) KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN, code, repeat))

    @Test fun defaultKeyboardRepeatKeepsOneOwnerAndCommitsOnceOnUp() = main {
        val events = Events()
        val slider = slider(events)
        val time = SystemClock.uptimeMillis()
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time))
        assertTrue(slider.isUserInteractionActive)
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time, repeat = 1))
        assertEquals(listOf(51, 52), events.userValues)
        assertTrue(events.finishes.isEmpty())
        assertTrue(events.commits.isEmpty())
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time, up = true))
        assertFalse(slider.isUserInteractionActive)
        assertEquals(listOf(52 to false), events.finishes)
        assertEquals(listOf(52), events.commits)
    }

    @Test fun cancelledTouchCanDisableWithoutAnOuterKeyChangeOrOwner() = keyboardPolicies { slider, events, _ ->
        beginTouch(slider, events)
        val before = slider.progress
        slider.onInteractionFinished = { value, cancelled ->
            events.finishes += value to cancelled
            if (cancelled) slider.isEnabled = false
        }
        val time = SystemClock.uptimeMillis()
        assertFalse(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time))
        assertEquals(before, slider.progress)
        assertFalse(slider.isEnabled)
        assertFalse(slider.isUserInteractionActive)
        assertTrue(events.userValues.isEmpty())
        assertEquals(listOf(before to true), events.finishes)
        assertTrue(events.commits.isEmpty())
        slider.isEnabled = true
        assertFalse(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time, repeat = 1))
        assertEquals(before, slider.progress)
        assertFalse(slider.isUserInteractionActive)
    }

    @Test fun cancelledTouchPolicyChangeRejectsOldKeyAndAllowsNextPolicyKey() = keyboardPolicies { slider, events, policy ->
        val nextPolicy = if (policy == NativeSliderKeyboardPolicy.DEFAULT)
            NativeSliderKeyboardPolicy.NATIVE_INTEGER else NativeSliderKeyboardPolicy.DEFAULT
        val nextIncrement = if (nextPolicy == NativeSliderKeyboardPolicy.NATIVE_INTEGER) 7 else 1
        beginTouch(slider, events)
        val before = slider.progress
        slider.onInteractionFinished = { value, cancelled ->
            events.finishes += value to cancelled
            if (cancelled) slider.setKeyboardPolicy(nextPolicy)
        }
        val time = SystemClock.uptimeMillis()
        assertFalse(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time))
        assertEquals(before, slider.progress)
        assertFalse(slider.isUserInteractionActive)
        assertTrue(events.userValues.isEmpty())
        assertEquals(listOf(before to true), events.finishes)
        assertTrue(events.commits.isEmpty())
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time + 1))
        assertEquals(before + nextIncrement, slider.progress)
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time + 1, up = true))
        assertEquals(listOf(before + nextIncrement), events.userValues)
        assertEquals(listOf(before + nextIncrement), events.commits)
        assertFalse(slider.isUserInteractionActive)
    }

    @Test fun cancelledTouchCallbackKeyboardOwnerSurvivesOuterDown() = keyboardPolicies { slider, events, policy ->
        val increment = if (policy == NativeSliderKeyboardPolicy.NATIVE_INTEGER) 7 else 1
        beginTouch(slider, events)
        val before = slider.progress
        val time = SystemClock.uptimeMillis()
        slider.onInteractionFinished = { value, cancelled ->
            events.finishes += value to cancelled
            if (cancelled) assertTrue(key(slider, KeyEvent.KEYCODE_MINUS, time + 1))
        }
        assertFalse(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time))
        assertEquals(before - increment, slider.progress)
        assertEquals(listOf(before - increment), events.userValues)
        assertEquals(listOf(before to true), events.finishes)
        assertTrue(slider.isUserInteractionActive)
        assertTrue(events.commits.isEmpty())
        assertFalse(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time, repeat = 1))
        key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time, up = true)
        assertEquals(before - increment, slider.progress)
        assertTrue(slider.isUserInteractionActive)
        assertEquals(listOf(before to true), events.finishes)
        assertTrue(events.commits.isEmpty())
        assertTrue(key(slider, KeyEvent.KEYCODE_MINUS, time + 1, up = true))
        assertFalse(slider.isUserInteractionActive)
        assertEquals(listOf(before to true, before - increment to false), events.finishes)
        assertEquals(listOf(before - increment), events.commits)
    }

    @Test fun cancelledTouchDirectionChangeUsesCurrentRtlDirection() = keyboardPolicies { slider, events, policy ->
        assertTrue("RTL fixture requires supportsRtl in the test APK",
            slider.context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL != 0)
        val increment = if (policy == NativeSliderKeyboardPolicy.NATIVE_INTEGER) 7 else 1
        beginTouch(slider, events)
        val before = slider.progress
        slider.onInteractionFinished = { value, cancelled ->
            events.finishes += value to cancelled
            if (cancelled) slider.layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val time = SystemClock.uptimeMillis()
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time))
        assertEquals(View.LAYOUT_DIRECTION_RTL, slider.layoutDirection)
        assertEquals(before - increment, slider.progress)
        assertEquals(listOf(before - increment), events.userValues)
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time, up = true))
        assertFalse(slider.isUserInteractionActive)
        assertEquals(listOf(before - increment), events.commits)
    }

    @Test fun cancellationExceptionDoesNotCreateAnOuterKeyboardOwner() = keyboardPolicies { slider, events, _ ->
        beginTouch(slider, events)
        val before = slider.progress
        val failure = IllegalStateException("cancel callback")
        slider.onInteractionFinished = { _, cancelled -> if (cancelled) throw failure }
        try {
            key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, SystemClock.uptimeMillis())
            fail("Expected cancellation callback failure")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(before, slider.progress)
        assertFalse(slider.isUserInteractionActive)
        assertTrue(events.userValues.isEmpty())
        assertTrue(events.commits.isEmpty())
    }

    @Test fun nativeBoundaryDownFinishesAcceptedChangeAndRetiresItsUp() = main {
        val events = Events()
        val slider = slider(events)
        slider.keyProgressIncrement = 7
        slider.setKeyboardPolicy(NativeSliderKeyboardPolicy.NATIVE_INTEGER)
        slider.progress = 93
        val time = SystemClock.uptimeMillis()
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time))
        assertEquals(100, slider.progress)
        assertTrue(events.commits.isEmpty())
        assertFalse(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time, repeat = 1))
        assertFalse(slider.isUserInteractionActive)
        assertEquals(listOf(100), events.userValues)
        assertEquals(listOf(100 to false), events.finishes)
        assertEquals(listOf(100), events.commits)
        key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time, up = true)
        assertEquals(listOf(100 to false), events.finishes)
        assertEquals(listOf(100), events.commits)
    }

    private enum class OuterRoute(val continuous: Boolean) {
        TOUCH(false), INTEGER_ACCESSIBILITY(false), CONTINUOUS_TOUCH(true), CONTINUOUS_ACCESSIBILITY(true)
    }

    private class RetirableSlider(context: android.content.Context, palette: ControlPalette, mode: NativeSliderValueMode) :
        NativeSlider(context, palette, valueMode = mode) {
        var outerTouchDownTime = 0L
        fun deliverDetach() = onDetachedFromWindow()
    }

    private fun nonKeyRoutes(test: (RetirableSlider, Events, OuterRoute) -> Unit) = main {
        for (route in OuterRoute.values()) {
            val events = Events()
            val slider = RetirableSlider(InstrumentationRegistry.getInstrumentation().targetContext, palette,
                if (route.continuous) NativeSliderValueMode.CONTINUOUS else NativeSliderValueMode.INTEGER)
            slider.max = 100
            slider.progress = 50
            slider.keyProgressIncrement = 7
            if (!route.continuous) slider.setKeyboardPolicy(NativeSliderKeyboardPolicy.NATIVE_INTEGER)
            slider.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.EXACTLY))
            slider.layout(0, 0, 400, 96)
            slider.onValueCommitted = { events.commits += it }
            slider.onContinuousValueCommitted = { events.commits += it.toInt() }
            finished(slider, events) { }
            test(slider, events, route)
        }
    }

    private fun finished(slider: NativeSlider, events: Events, callback: () -> Unit) {
        slider.onInteractionFinished = { value, cancelled ->
            events.finishes += value to cancelled
            if (cancelled) callback()
        }
        slider.onContinuousInteractionFinished = { value, cancelled ->
            events.finishes += value.toInt() to cancelled
            if (cancelled) callback()
        }
    }

    private fun outer(slider: RetirableSlider, route: OuterRoute, up: Boolean = false): Boolean {
        if (route == OuterRoute.INTEGER_ACCESSIBILITY || route == OuterRoute.CONTINUOUS_ACCESSIBILITY) {
            val args = android.os.Bundle().apply {
                putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 80f)
            }
            return slider.performAccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args)
        }
        val now = SystemClock.uptimeMillis()
        if (!up) slider.outerTouchDownTime = now
        val event = MotionEvent.obtain(slider.outerTouchDownTime, now,
            if (up) MotionEvent.ACTION_UP else MotionEvent.ACTION_DOWN, 320f, 48f, 0)
        return try { slider.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun nonKeyCancellationPreservesNestedKeyboardOwnerAndItsCommit() = nonKeyRoutes { slider, events, route ->
        val now = SystemClock.uptimeMillis()
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, now))
        finished(slider, events) { assertTrue(key(slider, KeyEvent.KEYCODE_MINUS, now + 1)) }
        assertFalse(outer(slider, route))
        assertEquals(50f, slider.continuousValue, 0f)
        assertTrue(slider.isUserInteractionActive)
        assertTrue(events.commits.isEmpty())
        assertEquals(1, events.finishes.size)
        key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, now, up = true)
        if (route == OuterRoute.TOUCH || route == OuterRoute.CONTINUOUS_TOUCH) assertFalse(outer(slider, route, up = true))
        assertTrue(slider.isUserInteractionActive)
        assertTrue(key(slider, KeyEvent.KEYCODE_MINUS, now + 1, up = true))
        assertFalse(slider.isUserInteractionActive)
        assertEquals(listOf(50), events.commits)
        assertEquals(listOf(true, false), events.finishes.map { it.second })
    }

    @Test fun nonKeyCancellationDisableRejectsOuterWithoutNewOwner() = nonKeyRoutes { slider, events, route ->
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, SystemClock.uptimeMillis()))
        val accepted = slider.continuousValue
        finished(slider, events) { slider.isEnabled = false }
        assertFalse(outer(slider, route))
        assertEquals(accepted, slider.continuousValue, 0f)
        assertFalse(slider.isUserInteractionActive)
        assertTrue(events.commits.isEmpty())
        assertEquals(1, events.finishes.size)
    }

    @Test fun nonKeyCancellationRetirementDoesNotReopenOldDispatch() = nonKeyRoutes { slider, events, route ->
        val now = SystemClock.uptimeMillis()
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, now))
        finished(slider, events) { slider.deliverDetach() }
        assertFalse(outer(slider, route))
        assertFalse(slider.isUserInteractionActive)
        assertTrue(events.commits.isEmpty())
        assertEquals(1, events.finishes.size)
        // Standalone Views remain valid; the fence rejects this dispatch, not all unattached Views.
        finished(slider, events) { }
        assertTrue(key(slider, KeyEvent.KEYCODE_MINUS, now + 1))
        assertTrue(key(slider, KeyEvent.KEYCODE_MINUS, now + 1, up = true))
        assertEquals(listOf(50), events.commits)
    }

    @Test fun nonKeyCancellationCompletedNestedOwnerStillRevokesOuter() = nonKeyRoutes { slider, events, route ->
        val now = SystemClock.uptimeMillis()
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, now))
        finished(slider, events) {
            assertTrue(key(slider, KeyEvent.KEYCODE_MINUS, now + 1))
            assertTrue(key(slider, KeyEvent.KEYCODE_MINUS, now + 1, up = true))
        }
        assertFalse(outer(slider, route))
        assertEquals(50f, slider.continuousValue, 0f)
        assertFalse(slider.isUserInteractionActive)
        assertEquals(listOf(50), events.commits)
        assertEquals(listOf(true, false), events.finishes.map { it.second })
    }

    @Test fun nonKeyOrdinaryRoutesStillAcceptAndCommit() = nonKeyRoutes { slider, events, route ->
        assertTrue(outer(slider, route))
        if (route == OuterRoute.TOUCH || route == OuterRoute.CONTINUOUS_TOUCH) assertTrue(outer(slider, route, up = true))
        assertFalse(slider.isUserInteractionActive)
        assertTrue(slider.continuousValue > 50f)
        assertEquals(1, events.commits.size)
        assertEquals(listOf(false), events.finishes.map { it.second })
    }

    @Test fun accessibilityCancellationUsesCurrentRangeAndReadmission() = nonKeyRoutes { slider, events, route ->
        if (route != OuterRoute.INTEGER_ACCESSIBILITY && route != OuterRoute.CONTINUOUS_ACCESSIBILITY) return@nonKeyRoutes
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, SystemClock.uptimeMillis()))
        finished(slider, events) { slider.max = 60; slider.progress = 20 }
        assertTrue(outer(slider, route))
        assertEquals(60f, slider.continuousValue, 0f)
        assertEquals(listOf(60), events.commits)
        assertEquals(listOf(true, false), events.finishes.map { it.second })
        if (route.continuous) {
            assertTrue(key(slider, KeyEvent.KEYCODE_MINUS, SystemClock.uptimeMillis()))
            slider.onContinuousValueRequest = { true }
            finished(slider, events) { slider.onContinuousValueRequest = { false } }
            val before = slider.continuousValue
            assertFalse(outer(slider, route))
            assertEquals(before, slider.continuousValue, 0f)
            assertFalse(slider.isUserInteractionActive)
            assertEquals(listOf(60), events.commits)
        }
    }

    @Test fun nonKeyPolicyRevocationAndRelativeAccessibilityUseLiveState() = nonKeyRoutes { slider, events, route ->
        val now = SystemClock.uptimeMillis()
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, now))
        if (!route.continuous) {
            val before = slider.continuousValue
            finished(slider, events) { slider.setKeyboardPolicy(NativeSliderKeyboardPolicy.DEFAULT) }
            assertFalse(outer(slider, route))
            assertEquals(before, slider.continuousValue, 0f)
            assertFalse(slider.isUserInteractionActive)
            assertTrue(events.commits.isEmpty())
            finished(slider, events) { }
            assertTrue(outer(slider, route))
            if (route == OuterRoute.TOUCH) assertTrue(outer(slider, route, up = true))
            assertEquals(1, events.commits.size)
        } else {
            finished(slider, events) { slider.max = 40; slider.progress = 20 }
            assertTrue(slider.performAccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
            assertEquals(22f, slider.continuousValue, 0f)
            assertFalse(slider.isUserInteractionActive)
            assertEquals(listOf(22), events.commits)
        }
    }

    private fun pointer(slider: NativeSlider, action: Int, downTime: Long, x: Float): Boolean {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, 48f, 0)
        return try { slider.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun nativeTouchCleanupRejectsReentrantDownAndLaterFreshGestureWorks() = nonKeyRoutes { slider, events, route ->
        if (route != OuterRoute.TOUCH && route != OuterRoute.CONTINUOUS_TOUCH) return@nonKeyRoutes
        for (cancel in listOf(true, false)) {
            slider.progress = 50
            events.commits.clear()
            events.finishes.clear()
            val now = SystemClock.uptimeMillis()
            assertTrue(pointer(slider, MotionEvent.ACTION_DOWN, now, 240f))
            assertTrue(pointer(slider, MotionEvent.ACTION_MOVE, now, 300f))
            var rejectedDowns = 0
            fun completion() {
                assertFalse(pointer(slider, MotionEvent.ACTION_DOWN, now + 1, 200f))
                rejectedDowns++
            }
            slider.onInteractionFinished = { value, cancelled ->
                events.finishes += value to cancelled; completion()
            }
            slider.onContinuousInteractionFinished = { value, cancelled ->
                events.finishes += value.toInt() to cancelled; completion()
            }
            if (cancel) slider.cancelUserInteraction()
            else assertTrue(pointer(slider, MotionEvent.ACTION_UP, now, 300f))
            assertEquals(1, rejectedDowns)
            assertFalse(slider.isUserInteractionActive)
            assertFalse(slider.isPressed)
            assertEquals(listOf(cancel), events.finishes.map { it.second })
            assertEquals(if (cancel) 0 else 1, events.commits.size)
            finished(slider, events) { }
            events.finishes.clear(); events.commits.clear()
            slider.progress = 50
            val fresh = SystemClock.uptimeMillis()
            assertTrue(pointer(slider, MotionEvent.ACTION_DOWN, fresh, 240f))
            assertTrue(pointer(slider, MotionEvent.ACTION_MOVE, fresh, 320f))
            assertTrue(pointer(slider, MotionEvent.ACTION_UP, fresh, 320f))
            assertFalse(slider.isUserInteractionActive)
            assertTrue(slider.continuousValue > 50f)
            assertEquals(listOf(false), events.finishes.map { it.second })
            assertEquals(1, events.commits.size)
        }
    }

    @Test fun keyboardCancellationMayTransferToTouchOutsideNativeDispatch() = nonKeyRoutes { slider, events, route ->
        if (route != OuterRoute.TOUCH && route != OuterRoute.CONTINUOUS_TOUCH) return@nonKeyRoutes
        val now = SystemClock.uptimeMillis()
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, now))
        finished(slider, events) {
            assertTrue(pointer(slider, MotionEvent.ACTION_DOWN, now + 1, 240f))
        }
        val accessibilityRoute = if (route.continuous) OuterRoute.CONTINUOUS_ACCESSIBILITY else OuterRoute.INTEGER_ACCESSIBILITY
        assertFalse(outer(slider, accessibilityRoute))
        assertTrue(slider.isUserInteractionActive)
        assertTrue(events.commits.isEmpty())
        assertEquals(listOf(true), events.finishes.map { it.second })
        assertTrue(pointer(slider, MotionEvent.ACTION_MOVE, now + 1, 320f))
        assertTrue(pointer(slider, MotionEvent.ACTION_UP, now + 1, 320f))
        assertFalse(slider.isUserInteractionActive)
        assertTrue(slider.continuousValue > 57f)
        assertEquals(listOf(true, false), events.finishes.map { it.second })
        assertEquals(1, events.commits.size)
    }

    @Test fun nativeStartStopCallbacksCannotTransferOwnershipMidFrame() = nonKeyRoutes { slider, events, route ->
        if (route != OuterRoute.TOUCH && route != OuterRoute.CONTINUOUS_TOUCH) return@nonKeyRoutes
        val now = SystemClock.uptimeMillis()
        var starts = 0
        var stops = 0
        fun rejectTransfers() {
            assertFalse(key(slider, KeyEvent.KEYCODE_MINUS, now + 1))
            val accessibilityRoute = if (route.continuous) OuterRoute.CONTINUOUS_ACCESSIBILITY else OuterRoute.INTEGER_ACCESSIBILITY
            assertFalse(outer(slider, accessibilityRoute))
            assertFalse(pointer(slider, MotionEvent.ACTION_DOWN, now + 1, 100f))
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(bar: SeekBar) { starts++; rejectTransfers() }
            override fun onStopTrackingTouch(bar: SeekBar) { stops++; rejectTransfers() }
        })
        assertTrue(pointer(slider, MotionEvent.ACTION_DOWN, now, 240f))
        assertTrue(pointer(slider, MotionEvent.ACTION_MOVE, now, 320f))
        assertTrue(pointer(slider, MotionEvent.ACTION_UP, now, 320f))
        assertEquals(1, starts)
        assertEquals(1, stops)
        assertFalse(slider.isUserInteractionActive)
        assertTrue(slider.continuousValue > 50f)
        assertEquals(listOf(false), events.finishes.map { it.second })
        assertEquals(1, events.commits.size)
        val accepted = slider.continuousValue
        assertFalse(key(slider, KeyEvent.KEYCODE_MINUS, now + 1, repeat = 1))
        key(slider, KeyEvent.KEYCODE_MINUS, now + 1, up = true)
        assertEquals(accepted, slider.continuousValue, 0f)
        assertEquals(1, events.commits.size)
        slider.progress = 50
        val fresh = SystemClock.uptimeMillis()
        assertTrue(pointer(slider, MotionEvent.ACTION_DOWN, fresh, 240f))
        assertTrue(pointer(slider, MotionEvent.ACTION_MOVE, fresh, 320f))
        assertTrue(pointer(slider, MotionEvent.ACTION_UP, fresh, 320f))
        assertEquals(2, starts)
        assertEquals(2, stops)
        assertFalse(slider.isUserInteractionActive)
        assertEquals(2, events.commits.size)
    }
}
