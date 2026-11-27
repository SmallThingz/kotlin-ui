package smallthingz.ui.components.dropdown

import android.content.Intent
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeDropdown
import smallthingz.ui.components.NativeUiAppearance
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Two bounded physical-window regressions. Root owns APK builds and device execution. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class DropdownInertiaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private data class Sample(val nanos: Long, val rows: Float)
    private data class Point(val x: Float, val y: Float, val rowHeight: Int)

    /** Can compare against the pre-inertia implementation: no new motion fields or production APIs. */
    @Test fun swipeReleaseContinuesThenDeceleratesAndClamps() = scenario { activity ->
        val wheel = open(activity)
        val release = swipeUp(activity)
        val samples = observe(wheel)
        saveTrace("release", release, samples)
        // A nearest-row snap travels at most half a row: it must fail this exact assertion.
        assertTrue("motion continues after lift", samples.maxOf { it.rows } > release + 1f)
        assertFiniteForward(samples)
        val end = samples.last().rows
        assertEquals("terminal position snaps to a finite row", end.roundToInt().toFloat(), end, .02f)
        // Compare broad observed windows, not a prescribed pixel speed/frame count.
        val first = samples.first()
        val early = samples.firstOrNull { it.nanos - first.nanos >= 100_000_000L }
        assertNotNull("enough observed frames to compare release and rest", early)
        val initialRate = (early!!.rows - first.rows) / ((early.nanos - first.nanos) / 1e9)
        val last = samples.last()
        val late = samples.last { last.nanos - it.nanos >= 150_000_000L }
        val terminalRate = abs(last.rows - late.rows) / ((last.nanos - late.nanos) / 1e9)
        assertTrue("release motion slows to rest or a finite clamp", initialRate > terminalRate + .1)
        val slowsBeforeSnap = samples.any { start ->
            val finish = samples.firstOrNull { it.nanos - start.nanos >= 80_000_000L }
            finish != null && start.nanos >= early.nanos && finish.rows < end - .6f &&
                (finish.rows - start.rows) / ((finish.nanos - start.nanos) / 1e9) in 0.0..(initialRate * .95)
        }
        assertTrue("coasting decelerates before row snap or reaches finite clamp", end >= 38.98f || slowsBeforeSnap)

        // Reuse the real accessibility endpoint action only to set up the finite-end swipe.
        main {
            repeat(40) { wheel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null) }
        }
        await("last row setup") { position(wheel) == 39f }
        main { repeat(3) { wheel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null) } }
        await("interior endpoint setup") { position(wheel) == 36f }
        val (edgeRelease, replay) = replayEndpointSwipe(activity)
        // Preserve admission evidence even if the replay did not supply enough coast distance.
        saveTrace("finite-end-release", edgeRelease, emptyList(), replay)
        assertTrue("endpoint replay releases before finite boundary", edgeRelease < 39f)
        val speed = replay.optDouble("release_velocity_px_per_second", Double.NaN)
        assertTrue("endpoint replay has positive finite velocity", speed.isFinite() && speed > 0.0)
        assertTrue("endpoint replay unconstrained target crosses finite boundary",
            replay.getInt("free_final_y_px") > replay.getInt("boundary_y_px"))
        val edgeSamples = observe(wheel)
        saveTrace("finite-end", edgeRelease, edgeSamples, replay)
        assertFiniteForward(edgeSamples)
        assertEquals("fling clamps at last finite row", 39f, edgeSamples.last().rows, .02f)
        main {
            assertEquals("end cue follows the same live viewport predicate", true to false, overflow(wheel))
            assertFalse(wheel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        }
    }

    /** New implementation only: motion owner fields are inspected solely in test code. */
    @Test fun newDownStopsAndBackOrCloseRetiresMotion() = scenario { activity ->
        var wheel = open(activity)
        swipeUp(activity)
        await("fling must be active before interruption") { value(wheel, "flinging") == true }
        val point = main { point(activity) }
        val down = SystemClock.uptimeMillis()
        val stopped = main {
            event(activity, down, MotionEvent.ACTION_DOWN, point.x, point.y)
            assertStopped(wheel)
            position(wheel)
        }
        val held = observe(wheel)
        assertTrue("held new DOWN arrests position", held.all { abs(it.rows - stopped) <= .02f })
        main { event(activity, down, MotionEvent.ACTION_UP, point.x, point.y) }
        observe(wheel)
        main {
            assertSame("stop gesture UP keeps dropdown open", wheel, pane(activity)?.getChildAt(1))
            assertStopped(wheel)
        }

        // A cancelled fresh drag must never become a fling. A bounded snap is permitted.
        swipeUp(activity, cancel = true)
        main { assertStopped(wheel) }
        observe(wheel)
        main { assertStopped(wheel) }

        swipeUp(activity)
        await("active motion before actual Back") { value(wheel, "flinging") == true }
        main { check(activity.hasWindowFocus()) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("actual window Back retires modal") { pane(activity) == null }
        assertRetired(wheel)
        main { assertNotNull("Back leaves preview anchor", anchor(activity)) }

        // Reopen through the real field, then retire through its existing public owner API.
        wheel = open(activity)
        swipeUp(activity)
        await("active motion before owner close") { value(wheel, "flinging") == true }
        main { activity.closeDropdown() }
        await("owner close detaches modal") { pane(activity) == null }
        assertRetired(wheel)
        val reopened = open(activity)
        main { assertFalse(value(reopened, "flinging") as Boolean) }
    }

    private fun assertRetired(wheel: View) {
        val stopped = main {
            assertFalse(wheel.isAttachedToWindow)
            assertEquals(true, value(session(wheel), "disposed"))
            assertNull("tracker recycled on retirement", value(wheel, "velocityTracker"))
            assertStopped(wheel)
            position(wheel)
        }
        assertTrue("retired owner never moves again", observe(wheel).all { abs(it.rows - stopped) <= .02f })
        main { assertStopped(wheel) }
    }

    private fun assertStopped(wheel: View) {
        assertEquals("fling owner stopped", false, value(wheel, "flinging"))
        assertTrue("scroller no longer active", (value(wheel, "scroller") as? OverScroller)?.isFinished != false)
    }

    /** Timed touchscreen events traverse Activity/Window dispatch, never Wheel.onTouchEvent. */
    private fun swipeUp(activity: DropdownViewportHostActivity, cancel: Boolean = false): Float {
        val point = main { point(activity) }
        val down = SystemClock.uptimeMillis()
        main { event(activity, down, MotionEvent.ACTION_DOWN, point.x, point.y) }
        // 1.5 rows / ~96ms gives finite native velocity with room below the middle selection.
        for (step in 1..6) {
            SystemClock.sleep(16)
            main { event(activity, down, MotionEvent.ACTION_MOVE, point.x, point.y - point.rowHeight * 1.5f * step / 6) }
        }
        return main {
            event(activity, down, if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,
                point.x, point.y - point.rowHeight * 1.5f)
            position(checkNotNull(pane(activity)).getChildAt(1))
        }
    }

    /** Endpoint-only deterministic timestamp replay through the actual Activity/Window.
     * Events cover 96ms in the past but dispatch in one main turn: not physical finger timing.
     * Initial causal swipe above remains the independently qualified, actually paced gesture. */
    private fun replayEndpointSwipe(activity: DropdownViewportHostActivity): Pair<Float, JSONObject> = main {
        val point = point(activity)
        val endTime = SystemClock.uptimeMillis()
        val down = endTime - 96L
        val events = JSONArray()
        fun deliver(action: Int, time: Long, y: Float) {
            val event = MotionEvent.obtain(down, time, action, point.x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            val deliveredAt = SystemClock.uptimeMillis()
            assertTrue("endpoint replay event time is not in the future", down <= time && time <= deliveredAt)
            val record = JSONObject().put("action", action).put("down_time_ms", down)
                .put("event_time_ms", time).put("dispatch_uptime_ms", deliveredAt)
                .put("x_px", point.x).put("y_px", y)
            events.put(record)
            try { assertTrue("window consumed endpoint replay action $action", activity.dispatchTouchEvent(event)) }
            finally { record.put("return_uptime_ms", SystemClock.uptimeMillis()); event.recycle() }
        }
        deliver(MotionEvent.ACTION_DOWN, down, point.y)
        for (step in 1..6) deliver(MotionEvent.ACTION_MOVE, down + step * 16L,
            point.y - point.rowHeight * 1.5f * step / 6)
        deliver(MotionEvent.ACTION_UP, endTime, point.y - point.rowHeight * 1.5f)
        val wheel = checkNotNull(pane(activity)).getChildAt(1)
        val release = position(wheel)
        val motion = value(wheel, "scroller") as? OverScroller
        val velocity = motion?.currVelocity ?: 0f
        val boundary = 39 * point.rowHeight
        // The free replay asks whether this actual release velocity has enough energy to
        // cross the boundary; its admission does not depend on production's bounded finalY.
        val free = if (motion != null && velocity.isFinite() && velocity > 0f) {
            OverScroller(activity).apply {
                fling(0, motion.startY, 0, velocity.roundToInt(), 0, 0, 0, Int.MAX_VALUE)
            }
        } else null
        val evidence = JSONObject().put("transport", "deterministic historical timestamps; one main turn Activity.dispatchTouchEvent")
            .put("events", events).put("release_observed_uptime_ms", SystemClock.uptimeMillis())
            .put("row_height_px", point.rowHeight).put("release_rows", release)
            .put("scroller_present", motion != null)
            .put("release_velocity_px_per_second", if (velocity.isFinite()) velocity.toDouble() else JSONObject.NULL)
            .put("start_y_px", motion?.startY ?: JSONObject.NULL)
            .put("bounded_final_y_px", motion?.finalY ?: JSONObject.NULL)
            .put("free_final_y_px", free?.finalY ?: -1).put("boundary_y_px", boundary)
        release to evidence
    }

    private fun event(activity: DropdownViewportHostActivity, down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue("window consumed touch action $action", activity.dispatchTouchEvent(event)) }
        finally { event.recycle() }
    }

    /** Samples actual animation frames until 250ms of rest, with an 8s absolute bound. */
    private fun observe(wheel: View): List<Sample> {
        val samples = ArrayList<Sample>()
        val done = CountDownLatch(1)
        var lastChange = 0L
        var timedOut = false
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val sample = Sample(frameTimeNanos, position(wheel))
                if (samples.isEmpty() || abs(sample.rows - samples.last().rows) > .001f) lastChange = frameTimeNanos
                samples.add(sample)
                val elapsed = frameTimeNanos - samples.first().nanos
                timedOut = elapsed >= 8_000_000_000L
                if (timedOut || frameTimeNanos - lastChange >= 250_000_000L) done.countDown()
                else Choreographer.getInstance().postFrameCallback(this)
            }
        }
        main { Choreographer.getInstance().postFrameCallback(callback) }
        try { assertTrue("animation frame observation completed", done.await(10, TimeUnit.SECONDS)) }
        finally { main { Choreographer.getInstance().removeFrameCallback(callback) } }
        assertFalse("motion reached bounded rest", timedOut)
        return samples
    }

    private fun assertFiniteForward(samples: List<Sample>) {
        assertTrue("position remains finite and clamped", samples.all { it.rows.isFinite() && it.rows in 0f..39f })
        // Terminal nearest-row settling may move backwards by less than half a row.
        var maximum = samples.first().rows
        for (sample in samples) {
            assertTrue("no reversal beyond nearest-row settling", sample.rows >= maximum - .51f)
            maximum = maxOf(maximum, sample.rows)
        }
    }

    private fun saveTrace(name: String, release: Float, samples: List<Sample>, replay: JSONObject? = null) {
        val directory = File(instrumentation.context.filesDir, "dropdown-regressions/inertia/${UUID.randomUUID()}")
        check(directory.mkdirs())
        val data = JSONArray()
        samples.forEach { data.put(JSONObject().put("frameNanos", it.nanos).put("positionRows", it.rows)) }
        val result = JSONObject().put("releaseRows", release).put("samples", data)
        if (replay != null) result.put("endpoint_replay", replay)
        File(directory, "$name.json").writeText(result.toString(2))
    }

    private fun scenario(body: (DropdownViewportHostActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context, DropdownViewportHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DropdownViewportHostActivity
        try {
            await("Activity and anchor laid out") {
                activity.hasWindowFocus() && anchor(activity)?.height?.let { it > 0 } == true
            }
            body(activity)
        } finally { main { activity.finish() } }
    }

    private fun open(activity: DropdownViewportHostActivity): View {
        main {
            val point = point(activity)
            val now = SystemClock.uptimeMillis()
            event(activity, now, MotionEvent.ACTION_DOWN, point.x, point.y)
            event(activity, now, MotionEvent.ACTION_UP, point.x, point.y)
        }
        await("dropdown fully open") {
            pane(activity)?.getChildAt(0)?.alpha == NativeUiAppearance.Default.anchoredDropdown.scrimOpacity
        }
        return main { checkNotNull(pane(activity)).getChildAt(1) }
    }

    private fun point(activity: DropdownViewportHostActivity): Point {
        val anchor = checkNotNull(anchor(activity))
        val decor = IntArray(2).also(activity.window.decorView::getLocationOnScreen)
        val origin = IntArray(2).also(anchor::getLocationOnScreen)
        return Point(origin[0] - decor[0] + anchor.width / 2f, origin[1] - decor[1] + anchor.height / 2f, anchor.height)
    }
    private fun anchor(activity: DropdownViewportHostActivity): View? = descendants(activity.window.decorView)
        .filterIsInstance<NativeDropdown<*>>().firstOrNull { it.parent !is ViewGroup || it.parent?.javaClass?.simpleName != "Wheel" }
    private fun pane(activity: DropdownViewportHostActivity): FrameLayout? = descendants(activity.window.decorView)
        .filterIsInstance<FrameLayout>().firstOrNull { it.accessibilityPaneTitle == "Choose an option" }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun value(owner: Any, field: String): Any? = owner.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(owner)
    private fun session(wheel: View): Any = checkNotNull(value(wheel, "this\$0"))
    private fun position(wheel: View): Float = value(session(wheel), "position") as Float
    private fun overflow(wheel: View): Pair<Boolean, Boolean> {
        val viewport = checkNotNull(value(session(wheel), "viewport"))
        val above = viewport.javaClass.getDeclaredMethod("overflowsAbove", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(viewport, position(wheel)) as Boolean
        val below = viewport.javaClass.getDeclaredMethod("overflowsBelow", Float::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(viewport, position(wheel), 39) as Boolean
        return above to below
    }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (main(predicate)) return
            SystemClock.sleep(16)
        }
        fail(message)
    }
}
