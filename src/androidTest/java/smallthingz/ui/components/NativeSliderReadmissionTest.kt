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

    @Test fun cancelledTouchCanDisableWithoutAnOuterKeyChangeOrOwner() = main {
        val events = Events()
        val slider = slider(events)
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

    @Test fun cancelledTouchPolicyChangeRejectsOldKeyAndAllowsNextNativeKey() = main {
        val events = Events()
        val slider = slider(events)
        slider.keyProgressIncrement = 7
        beginTouch(slider, events)
        val before = slider.progress
        slider.onInteractionFinished = { value, cancelled ->
            events.finishes += value to cancelled
            if (cancelled) slider.setKeyboardPolicy(NativeSliderKeyboardPolicy.NATIVE_INTEGER)
        }
        val time = SystemClock.uptimeMillis()
        assertFalse(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time))
        assertEquals(before, slider.progress)
        assertFalse(slider.isUserInteractionActive)
        assertTrue(events.userValues.isEmpty())
        assertEquals(listOf(before to true), events.finishes)
        assertTrue(events.commits.isEmpty())
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time + 1))
        assertEquals(before + 7, slider.progress)
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time + 1, up = true))
        assertEquals(listOf(before + 7), events.userValues)
        assertEquals(listOf(before + 7), events.commits)
        assertFalse(slider.isUserInteractionActive)
    }

    @Test fun cancelledTouchCallbackKeyboardOwnerSurvivesOuterDown() = main {
        val events = Events()
        val slider = slider(events)
        beginTouch(slider, events)
        val before = slider.progress
        val time = SystemClock.uptimeMillis()
        slider.onInteractionFinished = { value, cancelled ->
            events.finishes += value to cancelled
            if (cancelled) assertTrue(key(slider, KeyEvent.KEYCODE_MINUS, time + 1))
        }
        assertFalse(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time))
        assertEquals(before - 1, slider.progress)
        assertEquals(listOf(before - 1), events.userValues)
        assertEquals(listOf(before to true), events.finishes)
        assertTrue(slider.isUserInteractionActive)
        assertTrue(events.commits.isEmpty())
        assertTrue(key(slider, KeyEvent.KEYCODE_MINUS, time + 1, up = true))
        assertFalse(slider.isUserInteractionActive)
        assertEquals(listOf(before to true, before - 1 to false), events.finishes)
        assertEquals(listOf(before - 1), events.commits)
    }

    @Test fun cancelledTouchDirectionChangeUsesCurrentRtlDirection() = main {
        val events = Events()
        val slider = slider(events)
        assertTrue("RTL fixture requires supportsRtl in the test APK",
            slider.context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL != 0)
        beginTouch(slider, events)
        val before = slider.progress
        slider.onInteractionFinished = { value, cancelled ->
            events.finishes += value to cancelled
            if (cancelled) slider.layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val time = SystemClock.uptimeMillis()
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time))
        assertEquals(View.LAYOUT_DIRECTION_RTL, slider.layoutDirection)
        assertEquals(before - 1, slider.progress)
        assertEquals(listOf(before - 1), events.userValues)
        assertTrue(key(slider, KeyEvent.KEYCODE_DPAD_RIGHT, time, up = true))
        assertFalse(slider.isUserInteractionActive)
        assertEquals(listOf(before - 1), events.commits)
    }

    @Test fun cancellationExceptionDoesNotCreateAnOuterKeyboardOwner() = main {
        val events = Events()
        val slider = slider(events)
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
}
