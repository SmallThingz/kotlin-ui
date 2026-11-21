package smallthingz.ui.components

import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.unit.Density

@RunWith(AndroidJUnit4::class)
class NativeAudioControlAppearanceIntegrationTest {
    private val palette = NativeColorScheme.fallback(darkTheme = false)

    @Test fun wheelStyleMovesTheProfileHitAreaAndAnAcceptedGestureStillSettles() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wheel = NativeDurationWheelView(context)
        val style = NativeDurationWheelStyle.Default.copy(heightDp = 132f, profileWidthFraction = .5f)
        wheel.setAppearance(style, palette)
        wheel.setAccessibilityLabel("Duration")
        wheel.setMaximumDurationSeconds(3600.0)
        wheel.measure(exact(480), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals((style.heightDp * context.resources.displayMetrics.density).toInt(), wheel.measuredHeight)
        wheel.layout(0, 0, wheel.measuredWidth, wheel.measuredHeight)

        val events = mutableListOf<String>()
        wheel.onInteractionStart = { events += "start" }
        wheel.onDurationChanged = { events += "duration=$it" }
        wheel.onInteractionEnd = { events += "end" }
        val now = SystemClock.uptimeMillis()
        fun dispatch(action: Int): Boolean {
            val event = MotionEvent.obtain(now, now, action, wheel.width * .67f, wheel.height * .15f, 0)
            return try { wheel.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        assertTrue(dispatch(MotionEvent.ACTION_DOWN))
        wheel.isEnabled = false
        assertTrue(dispatch(MotionEvent.ACTION_UP))
        assertEquals(listOf("start", "end"), events)
        assertEquals("Duration, 0:00:00 15x", wheel.contentDescription.toString())
        assertFalse(dispatch(MotionEvent.ACTION_DOWN))
    }

    @Test fun discreteSliderAppearancePreservesControlledProgressAndItsSuppliedBounds() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val style = NativeDiscreteSliderStyle.Default.copy(thumbHeightDp = 68f)
        val requests = mutableListOf<Int>()
        val slider = NativeDiscreteSliderView(context, 1, 4, true, 1f, style, palette, "Level", { false }) {
            requests += it
        }
        slider.measure(exact(260), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(68, slider.measuredHeight)
        slider.layout(0, 0, slider.measuredWidth, slider.measuredHeight)
        val child = slider.pointerView
        assertEquals("Level", child.contentDescription)
        fun assertProgress(current: Float) {
            val info = checkNotNull(child.createAccessibilityNodeInfo())
            try {
                val range = checkNotNull(info.rangeInfo)
                assertEquals(0f, range.min, 0f)
                assertEquals(4f, range.max, 0f)
                assertEquals(current, range.current, 0f)
            } finally {
                @Suppress("DEPRECATION")
                info.recycle()
            }
        }
        val progress = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id
        val adjust = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 1.5f) }
        assertTrue(child.performAccessibilityAction(progress, adjust))
        assertEquals(listOf(2), requests)
        assertProgress(1f)
        slider.bind(2, false, 1f, style, palette)
        assertProgress(2f)
        assertFalse(child.performAccessibilityAction(progress, adjust))
        assertEquals(listOf(2), requests)
        slider.close()
    }

    @Test fun fineSeekStyleUpdatesTheLivePuckAndKeepsItsBoundPlaybackAction() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = FineSeekInput()
        val scope = CoroutineScope(SupervisorJob())
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = error("No frame requested")
        }
        val style = NativeSpringFineSeekStyle.Default.copy(puckDiameterDp = 42f, hitDiameterDp = 72f, glyphSizeDp = 18f)
        val environment = NativeSpringFineSeekEnvironment(palette, Density(1f), 8f, clock, scope, View(context), { false })
        val view = NativeSpringFineSeekView(context, environment, NativeSpringFineSeekStyle.Default,
            { playing -> if (playing) "Pause" else "Jouer" }, { _, _ -> input })
        try {
            view.setAppearance(style, palette)
            var toggles = 0
            view.bind(object : NativeSpringFineSeekBinding {
                override val enabled = true
                override val isPlaying = false
                override val interactionKey = Unit
                override val onTogglePlayback: () -> Unit = { toggles++ }
            })
            view.measure(exact(300), exact(110))
            view.layout(0, 0, 300, 110)
            val puck = view.puckClickView as ViewGroup
            assertEquals(72, puck.width)
            assertEquals(72, puck.height)
            assertEquals(18, puck.getChildAt(0).width)
            assertEquals("Jouer", puck.getChildAt(0).contentDescription)
            view.fineSeekNode.dispatch(NativeDragPointerEvent(emptyList(), Offset.Zero), NativePointerPass.MAIN)
            assertEquals(36f, input.geometry.inputCircleRadius, 0f)
            assertEquals(input.geometry.puckLeft(input.horizontalPull), puck.left)
            assertEquals(input.geometry.puckTop(input.constrainedVerticalPull), puck.top)
            assertTrue(puck.performClick())
            assertEquals(1, toggles)
        } finally {
            view.release()
            scope.cancel()
        }
    }

    private class FineSeekInput : NativeSpringFineSeekInput<NativeSpringFineSeekBinding> {
        override val horizontalPull = .5f
        override val constrainedVerticalPull = -.25f
        override val dragging = false
        override val needsFrames = false
        lateinit var geometry: NativeSpringFineSeekGeometry
        override fun bind(value: NativeSpringFineSeekBinding, newTouchSlop: Float) = Unit
        override fun setVisualStyle(style: NativeSpringFineSeekStyle) = Unit
        override fun pointer(event: NativeDragPointerEvent, pass: NativePointerPass, geometry: NativeSpringFineSeekGeometry) {
            this.geometry = geometry
        }
        override fun frame(frameNanos: Long) = error("No frame requested")
        override fun cancel() = Unit
        override fun release() = Unit
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}
