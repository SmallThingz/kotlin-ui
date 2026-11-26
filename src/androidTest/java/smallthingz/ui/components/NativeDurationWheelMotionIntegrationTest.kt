package smallthingz.ui.components

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDurationWheelMotionIntegrationTest {
    @Test fun snapDurationRunsFromReleaseAtEachScale() = onMain {
        for ((scale, terminalMs) in listOf(.5f to 75L, 1f to 150L, 2f to 300L)) {
            val fixture = WheelFixture(scale)
            fixture.releaseAfterDrag()
            assertEquals(listOf("start"), fixture.events)
            assertTrue(fixture.frame(terminalMs - 1))
            assertEquals(listOf("start"), fixture.events)
            assertFalse(fixture.frame(terminalMs))
            assertFalse(fixture.frame(terminalMs + 1))
            assertEquals(listOf("start", "duration=1", "end"), fixture.events)
        }
    }

    @Test fun midflightScaleChangesApplyToEachNewFrameInterval() = onMain {
        val fixture = WheelFixture(1f)
        fixture.releaseAfterDrag()
        assertTrue(fixture.frame(50))
        fixture.scale = 2f
        assertTrue(fixture.frame(150))
        fixture.scale = .5f
        assertTrue(fixture.frame(174))
        assertEquals(listOf("start"), fixture.events)
        assertFalse(fixture.frame(175))
        assertEquals(listOf("start", "duration=1", "end"), fixture.events)
    }

    @Test fun zeroScaleCompletesAtReleaseOrOnTheNextPendingFrame() = onMain {
        val disabled = WheelFixture(0f)
        disabled.releaseAfterDrag()
        assertEquals(listOf("start", "duration=1", "end"), disabled.events)
        assertFalse(disabled.frame(0))

        val midflight = WheelFixture(1f)
        midflight.releaseAfterDrag()
        assertTrue(midflight.frame(25))
        midflight.scale = 0f
        assertFalse(midflight.frame(50))
        assertFalse(midflight.frame(75))
        assertEquals(listOf("start", "duration=1", "end"), midflight.events)
    }

    @Test fun replacementGestureRetiresPendingSnapAndEndsEvenWithoutAChange() = onMain {
        val fixture = WheelFixture(1f)
        fixture.releaseAfterDrag(rows = .4f)
        assertTrue(fixture.frame(50))
        fixture.touch(MotionEvent.ACTION_DOWN)
        assertFalse(fixture.frame(150))
        fixture.scale = 0f
        fixture.touch(MotionEvent.ACTION_UP)
        assertFalse(fixture.frame(175))
        assertEquals(listOf("start", "start", "end"), fixture.events)
    }

    private fun onMain(block: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }

    private class WheelFixture(var scale: Float) {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val style = NativeDurationWheelStyle.Default
        private val density = context.resources.displayMetrics.density
        private val releaseNanos = 1_000_000_000L
        private val downTime = SystemClock.uptimeMillis()
        private val wheel = NativeDurationWheelView(context, style, { scale }, { releaseNanos })
        val events = mutableListOf<String>()

        init {
            wheel.layout(0, 0, (style.widthDp * density).toInt(), (style.heightDp * density).toInt())
            wheel.setMaximumDurationSeconds(59.0)
            wheel.onInteractionStart = { events += "start" }
            wheel.onDurationChanged = { events += "duration=$it" }
            wheel.onInteractionEnd = { events += "end" }
        }

        fun releaseAfterDrag(rows: Float = .75f) {
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_MOVE, rows)
            touch(MotionEvent.ACTION_UP, rows)
        }

        fun touch(action: Int, rows: Float = 0f) {
            val timeRight = wheel.width * (1f - style.profileWidthFraction) - style.profileGapDp * density
            val x = timeRight * (2.5f / 3f)
            val y = wheel.height * .5f - rows * style.rowDp * density
            val event = MotionEvent.obtain(downTime, downTime, action, x, y, 0)
            try {
                assertTrue(wheel.onTouchEvent(event))
            } finally {
                event.recycle()
            }
        }

        fun frame(elapsedMs: Long): Boolean = wheel.advanceSnap(releaseNanos + elapsedMs * 1_000_000L)
    }
}
