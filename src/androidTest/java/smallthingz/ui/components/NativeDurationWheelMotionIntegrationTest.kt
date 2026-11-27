package smallthingz.ui.components

import android.os.SystemClock
import android.view.InputDevice
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

    @Test fun cancelledDragRestoresCommittedDurationAndEndsOnceAtEachScale() = onMain {
        for (scale in listOf(0f, 1f)) {
            val fixture = WheelFixture(scale)
            fixture.setDuration(12.0)
            val committedDescription = fixture.description
            fixture.touch(MotionEvent.ACTION_DOWN)
            fixture.touch(MotionEvent.ACTION_MOVE, .75f)
            assertEquals(listOf("start"), fixture.events)
            fixture.touch(MotionEvent.ACTION_CANCEL, .75f)
            fixture.touch(MotionEvent.ACTION_CANCEL)
            assertFalse(fixture.frame(150))
            assertFalse(fixture.frame(300))
            assertEquals(committedDescription, fixture.description)
            assertEquals(listOf("start", "end"), fixture.events)

            // The next drag must start at 12, not at the cancelled fractional position.
            fixture.releaseAfterDrag(rows = 1.25f)
            if (scale == 1f) {
                assertTrue(fixture.frame(149))
                assertEquals(listOf("start", "end", "start"), fixture.events)
                assertFalse(fixture.frame(150))
            }
            assertEquals(listOf("start", "end", "start", "duration=13", "end"), fixture.events)
        }
    }

    @Test fun cancelledReplacementRestoresAllWheelPositionsWithoutDiscardingAnAcceptedUp() = onMain {
        val fixture = WheelFixture(1f)
        fixture.releaseAfterDrag()
        fixture.touch(MotionEvent.ACTION_CANCEL)
        assertTrue(fixture.frame(50))
        assertEquals(listOf("start"), fixture.events)

        fixture.touch(MotionEvent.ACTION_DOWN, profile = true)
        fixture.touch(MotionEvent.ACTION_MOVE, .75f, profile = true)
        fixture.touch(MotionEvent.ACTION_CANCEL, profile = true)
        assertFalse(fixture.frame(150))
        assertEquals(listOf("start", "start", "end"), fixture.events)

        // Settling the centred profile reads both the profile and the displayed time.
        fixture.scale = 0f
        fixture.touch(MotionEvent.ACTION_DOWN, profile = true)
        fixture.touch(MotionEvent.ACTION_UP, profile = true)
        assertEquals("0:00:00 1x", fixture.description)
        assertEquals(listOf("start", "start", "end", "start", "end"), fixture.events)
    }

    @Test fun cancelledPointerLiftRollsBackOnlyTheOwningPointer() = onMain {
        val fixture = WheelFixture(1f)
        fixture.touch(MotionEvent.ACTION_DOWN)
        fixture.touch(MotionEvent.ACTION_MOVE, .75f)
        fixture.pointerEvent(MotionEvent.ACTION_POINTER_DOWN, index = 1)
        fixture.pointerEvent(MotionEvent.ACTION_POINTER_UP, index = 1, flags = MotionEvent.FLAG_CANCELED)
        assertEquals(listOf("start"), fixture.events)
        fixture.pointerEvent(MotionEvent.ACTION_POINTER_DOWN, index = 1)
        fixture.pointerEvent(MotionEvent.ACTION_POINTER_UP, index = 0, flags = MotionEvent.FLAG_CANCELED)
        assertFalse(fixture.frame(150))
        assertEquals(listOf("start", "end"), fixture.events)
    }

    @Test fun heldTouchConsumesMouseScrollUntilCancellation() = onMain {
        val fixture = WheelFixture(1f)
        fixture.setDuration(12.0)
        fixture.touch(MotionEvent.ACTION_DOWN)
        fixture.touch(MotionEvent.ACTION_MOVE, .75f)

        assertTrue(fixture.scrollProfileWithMouse())
        assertEquals(listOf("start"), fixture.events)
        assertFalse(fixture.frame(150))

        fixture.touch(MotionEvent.ACTION_CANCEL)
        assertFalse(fixture.frame(300))
        assertEquals("0:00:12 1x", fixture.description)
        assertEquals(listOf("start", "end"), fixture.events)

        assertTrue(fixture.scrollProfileWithMouse())
        assertEquals("0:00:12 5x", fixture.description)
        assertEquals(listOf("start", "end", "start", "end"), fixture.events)
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
        val description: String get() = wheel.contentDescription.toString()

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

        fun setDuration(seconds: Double) = wheel.setDurationSeconds(seconds)

        private fun secondsX(): Float {
            val timeRight = wheel.width * (1f - style.profileWidthFraction) - style.profileGapDp * density
            return timeRight * (2.5f / 3f)
        }

        private fun profileX(): Float = wheel.width * (1f - style.profileWidthFraction * .5f)

        fun touch(action: Int, rows: Float = 0f, profile: Boolean = false) {
            val x = if (profile) profileX() else secondsX()
            val y = wheel.height * .5f - rows * style.rowDp * density
            dispatch(MotionEvent.obtain(downTime, downTime, action, x, y, 0))
        }

        fun scrollProfileWithMouse(): Boolean {
            val properties = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            }
            val coords = MotionEvent.PointerCoords().apply {
                x = profileX()
                y = wheel.height * .5f
                setAxisValue(MotionEvent.AXIS_VSCROLL, -1f)
            }
            val event = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_SCROLL,
                1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0,
            )
            return try {
                wheel.onGenericMotionEvent(event)
            } finally {
                event.recycle()
            }
        }

        fun pointerEvent(action: Int, index: Int, flags: Int = 0) {
            val properties = Array(2) { id ->
                MotionEvent.PointerProperties().apply {
                    this.id = id
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(2) {
                MotionEvent.PointerCoords().apply {
                    x = secondsX()
                    y = wheel.height * .5f - .75f * style.rowDp * density
                    pressure = 1f
                    size = 1f
                }
            }
            dispatch(MotionEvent.obtain(
                downTime, downTime, action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags,
            ))
        }

        private fun dispatch(event: MotionEvent) {
            try {
                assertTrue(wheel.onTouchEvent(event))
            } finally {
                event.recycle()
            }
        }

        fun frame(elapsedMs: Long): Boolean = wheel.advanceSnap(releaseNanos + elapsedMs * 1_000_000L)
    }
}
