package smallthingz.ui.components

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Actual attached RecordingCanvas + Window pixels; bitmap Canvas cannot draw API35 patterned ink. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class NativeProgressSwitchPressedWindowIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun hardwarePressedInkIsRoundedAndCornerStillActivatesTheFullTarget() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var dialog: Dialog? = null
        var row: NativeProgressSwitch? = null
        var probe: ForegroundProbe? = null
        var outstandingDown: Long? = null
        withRetiringTestActivity(activity, root = { probe }, cleanup = {
            try {
                probe?.stopFrames()
                outstandingDown?.let { down -> row?.let { touch(it, MotionEvent.ACTION_CANCEL, down) } }
            } finally { try { row?.close() } finally { dialog?.dismiss() } }
        }) {
            val changes = mutableListOf<Boolean>()
            val fixture = main {
                Dialog(activity, android.R.style.Theme_Material_NoActionBar).also { dialog = it }
            }
            val window = main { requireNotNull(fixture.window) }
            val control = main {
                window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                val context = fixture.context
                val lightTheme = TypedValue()
                assertTrue(context.theme.resolveAttribute(android.R.attr.isLightTheme, lightTheme, true))
                assertEquals("Use the existing dark hardware-window recipe", 0, lightTheme.data)
                fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
                NativeProgressSwitch(context, NativeColorScheme.fallback(true), "Pressed fixture switch",
                    { changes += it }).also { actual ->
                    row = actual
                    actual.bindProgress(false, true, null)
                    val sample = ForegroundProbe(context, actual, dp(8)).also { probe = it }
                    val parking = View(context).apply { isFocusableInTouchMode = true }
                    val root = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        isForceDarkAllowed = false
                        setBackgroundColor(BACKDROP)
                        addView(actual, LinearLayout.LayoutParams(dp(64), dp(48)))
                        addView(sample, LinearLayout.LayoutParams(dp(64) * 2 + dp(8) * 3, dp(48) + dp(8) * 2))
                        addView(parking, LinearLayout.LayoutParams(dp(1), dp(1)))
                    }
                    fixture.setContentView(root)
                    fixture.show()
                    assertTrue(parking.requestFocus())
                }
            }
            val sample = checkNotNull(probe)
            awaitReady(control, sample)
            val dimensions = main { control.width to control.height }
            main {
                assertTrue("Hardware press qualification requires enabled animators", ValueAnimator.areAnimatorsEnabled())
                assertFalse("Focus ink must not masquerade as pressed ink", control.isFocused)
                val minimum = (48 * control.resources.displayMetrics.density).roundToInt()
                assertTrue(control.width >= minimum && control.height >= minimum)
            }
            val baseline = capture(window, sample, Color.MAGENTA)
            try {
                assertIdle(baseline, sample.gap, dimensions.first, dimensions.second)
                main {
                    outstandingDown = SystemClock.uptimeMillis()
                    assertTrue(touch(control, MotionEvent.ACTION_DOWN, checkNotNull(outstandingDown)))
                    assertTrue(changes.isEmpty())
                    sample.startFrames()
                }
                // Fixed observation interval, not a loop that waits until desired pixels appear.
                SystemClock.sleep(420)
                main { sample.stopFrames() }
                val pressed = capture(window, sample, Color.CYAN)
                try {
                    main {
                        assertTrue(control.isAttachedToWindow && control.hasWindowFocus() && control.isHardwareAccelerated)
                        assertTrue("The probe must receive a real hardware Canvas", sample.sawHardwareCanvas)
                        assertFalse(control.isFocused)
                        assertTrue("DOWN must not dispatch a toggle", changes.isEmpty())
                    }
                    val receipt = main {
                        val age = sample.lastDrawUptime - checkNotNull(outstandingDown)
                        assertTrue("Reject a stale or excessively delayed pressed recording: age=$age", age in 420L..1_000L)
                        assertEquals(dimensions, control.width to control.height)
                        "size=${control.width}x${control.height} draws=${sample.draws} " +
                            "lastDrawUptime=${sample.lastDrawUptime} down=$outstandingDown " +
                            "captureUptime=${SystemClock.uptimeMillis()} hardware=${sample.sawHardwareCanvas}"
                    }
                    assertInk(baseline, pressed, sample.gap, dimensions.first, dimensions.second, receipt)
                    main {
                        val node = checkNotNull(control.createAccessibilityNodeInfo())
                        try {
                            assertTrue(node.isCheckable && !node.isChecked && node.isEnabled)
                            assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                            val bounds = Rect().also(node::getBoundsInScreen)
                            assertEquals(control.width, bounds.width())
                            assertEquals(control.height, bounds.height())
                        } finally { node.recycle() }
                        assertTrue(touch(control, MotionEvent.ACTION_UP, checkNotNull(outstandingDown)))
                        outstandingDown = null
                        assertEquals("Clipped corner remains an ordinary input target", listOf(true), changes)
                        control.bindProgress(false, false, null)
                        control.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                        assertEquals("Disabled callback remains refused", listOf(true), changes)
                    }
                } finally { pressed.recycle() }
            } finally { baseline.recycle() }
        }
    }

    private fun assertIdle(image: Bitmap, gap: Int, width: Int, height: Int) {
        for (left in listOf(gap, width + gap * 2)) {
            for ((x, y) in listOf(width / 2 to height / 2, 1 to 1,
                width - 2 to 1, 1 to height - 2, width - 2 to height - 2)) {
                assertEquals("Idle foreground must not contain focus/hover/previous press ink",
                    BACKDROP, image.getPixel(left + x, gap + y))
            }
        }
    }

    private fun assertInk(before: Bitmap, after: Bitmap, gap: Int, width: Int, height: Int, receipt: String) {
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        fun changed(x: Int, y: Int) = before.getPixel(x, y) != after.getPixel(x, y)
        val clippedX = gap
        val rawX = width + gap * 2
        assertTrue("Actual clipped center contribution; $receipt", changed(clippedX + width / 2, gap + height / 2))
        assertTrue("Actual unmasked center contribution; $receipt", changed(rawX + width / 2, gap + height / 2))
        assertTrue("Raw corner ink must establish the clipping counterfactual; $receipt", changed(rawX + 1, gap + 1))
        for ((x, y) in listOf(1 to 1, width - 2 to 1, 1 to height - 2, width - 2 to height - 2)) {
            assertEquals("No square pressed ink at $x,$y; $receipt",
                before.getPixel(clippedX + x, gap + y), after.getPixel(clippedX + x, gap + y))
        }
    }

    private class ForegroundProbe(context: Context, val row: NativeProgressSwitch, val gap: Int) : View(context) {
        var marker = Color.MAGENTA
        var sawHardwareCanvas = false
            private set
        var draws = 0
            private set
        var lastDrawUptime = 0L
            private set
        private val markerPaint = Paint()
        private var frameDeadline = 0L
        private val frame = object : Runnable {
            override fun run() {
                invalidate()
                if (SystemClock.uptimeMillis() < frameDeadline) postOnAnimation(this)
            }
        }
        fun startFrames() { frameDeadline = SystemClock.uptimeMillis() + 420; postOnAnimation(frame) }
        fun stopFrames() { frameDeadline = 0; removeCallbacks(frame) }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            sawHardwareCanvas = canvas.isHardwareAccelerated
            draws++
            lastDrawUptime = SystemClock.uptimeMillis()
            canvas.drawColor(BACKDROP)
            markerPaint.color = marker
            canvas.drawRect(0f, 0f, gap.toFloat(), gap.toFloat(), markerPaint)
            // Original row owns attachment, input and drawable callbacks. No replacement paint/state.
            for (raw in listOf(false, true)) {
                val checkpoint = canvas.save()
                try {
                    canvas.translate((if (raw) row.width + gap * 2 else gap).toFloat(), gap.toFloat())
                    canvas.clipRect(0, 0, row.width, row.height)
                    if (raw) row.foreground.draw(canvas) else row.onDrawForeground(canvas)
                } finally { canvas.restoreToCount(checkpoint) }
            }
        }
    }

    private fun awaitReady(row: View, probe: View) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (main { row.isAttachedToWindow && row.hasWindowFocus() && row.isHardwareAccelerated &&
                    row.isLaidOut && probe.isLaidOut && !row.isLayoutRequested && !probe.isLayoutRequested }) return
            SystemClock.sleep(16)
        }
        fail("Actual hardware window did not attach, focus and lay out")
    }

    private fun capture(window: Window, probe: ForegroundProbe, marker: Int): Bitmap {
        val committed = CountDownLatch(1)
        val commit = Runnable { committed.countDown() }
        val observer = main {
            check(probe.isAttachedToWindow && probe.isHardwareAccelerated && probe.hasWindowFocus())
            probe.marker = marker
            probe.viewTreeObserver.also { it.registerFrameCommitCallback(commit); probe.invalidate() }
        }
        try { assertTrue("Requested hardware frame did not commit", committed.await(5, TimeUnit.SECONDS)) }
        finally { main { if (observer.isAlive) observer.unregisterFrameCommitCallback(commit) } }
        val bounds = main {
            val point = IntArray(2).also(probe::getLocationInWindow)
            Rect(point[0], point[1], point[0] + probe.width, point[1] + probe.height)
        }
        val image = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        val ownership = Any()
        var completed = false
        var abandoned = false
        var result = PixelCopy.ERROR_UNKNOWN
        try {
            main {
                PixelCopy.request(window, bounds, image, { status ->
                    synchronized(ownership) {
                        result = status
                        completed = true
                        if (abandoned) image.recycle()
                    }
                    done.countDown()
                }, Handler(Looper.getMainLooper()))
            }
        } catch (failure: Throwable) { image.recycle(); throw failure }
        val delivered = try { done.await(5, TimeUnit.SECONDS) }
        catch (failure: Throwable) {
            synchronized(ownership) { abandoned = true; if (completed) image.recycle() }
            throw failure
        }
        if (!delivered) {
            synchronized(ownership) { abandoned = true; if (completed) image.recycle() }
            throw AssertionError("PixelCopy callback did not complete; destination retained until callback")
        }
        try {
            assertEquals("Actual Window PixelCopy", PixelCopy.SUCCESS, result)
            assertEquals("Reject stale Window frame", marker, image.getPixel(1, 1))
            main {
                val point = IntArray(2).also(probe::getLocationInWindow)
                assertTrue(probe.isAttachedToWindow && probe.hasWindowFocus())
                assertEquals(bounds, Rect(point[0], point[1], point[0] + probe.width, point[1] + probe.height))
            }
            return image
        } catch (failure: Throwable) { image.recycle(); throw failure }
    }

    private fun touch(row: View, action: Int, down: Long): Boolean {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1f, 1f, 0)
        return try { row.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    private companion object { const val BACKDROP = 0xff101010.toInt() }
}
