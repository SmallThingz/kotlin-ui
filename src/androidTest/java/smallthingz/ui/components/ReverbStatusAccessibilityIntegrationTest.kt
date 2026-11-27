package smallthingz.ui.components

import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.R
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Rect
import smallthingz.ui.reverb.LibraryBodyMode
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbLibraryStatusLabels
import smallthingz.ui.reverb.ReverbLibraryStatusView
import smallthingz.ui.reverb.ReverbLibraryTextAction
import smallthingz.ui.reverb.ReverbRangeDurationActions
import smallthingz.ui.reverb.ReverbRangeDurationWheel
import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.scroll.NativeVerticalScrollSource
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ReverbStatusAccessibilityIntegrationTest {
    @Test fun shortLibraryFailureScrollsToTheEndOfTheReasonAndRetry() = withFocusedHost { host ->
        val configuration = Configuration(host.resources.configuration).apply {
            densityDpi = 160
            fontScale = 2f
        }
        val context = host.context.createConfigurationContext(configuration)
        val environment = ReverbEnvironment(context, false)
        val density = environment.textDensity.density
        val width = (160 * density).roundToInt()
        val message = "Could not refresh recordings.\nCheck storage access.\nThen try again."
        var retries = 0

        NativeModalWindowRuntime(context).use { runtime ->
            val nested = object : NativeVerticalNestedHost {
                override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
                override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
                override suspend fun preFling(availableY: Float) = 0f
                override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
            }
            val status = ReverbLibraryStatusView(context, environment, runtime.frames, runtime.scope,
                nested, host, ReverbLibraryStatusLabels({ message }, { "Retry" })) { 0f }
            status.bind(LibraryBodyMode.FAILED) { retries++ }
            host.addView(status, FrameLayout.LayoutParams(width, 0))
            fun layout(height: Int) {
                status.layoutParams = FrameLayout.LayoutParams(width, height)
                status.measure(exact(width), exact(height))
                status.layout(0, 0, width, height)
            }
            try {
                val viewport = checkNotNull(status.statusViewport)
                val column = viewport.getChildAt(0) as ViewGroup
                val reason = column.getChildAt(0) as ParagraphView
                val retry = column.getChildAt(1) as ReverbLibraryTextAction
                val button = retry.getChildAt(0) as NativeMaterialButtonView
                reason.measure(atMost(width), unspecified())
                retry.measure(atMost(width), unspecified())
                val reasonHeight = reason.measuredHeight
                val retryHeight = retry.measuredHeight
                val minimum = (button.minimumTouchTargetDp * density).roundToInt()
                assertTrue(reasonHeight > retryHeight + 1 && reasonHeight <= host.height)

                // The full reason would fit by itself. Reserving Retry must not clip it.
                layout(reasonHeight)
                assertEquals(reasonHeight, reason.height)
                assertEquals(reasonHeight, reason.bottom)
                assertEquals(retryHeight, viewport.state.maxValue)

                val shorterHeight = (reasonHeight + retryHeight) / 2
                layout(shorterHeight)
                assertEquals(reasonHeight, reason.height)
                assertTrue(retry.width >= minimum && retry.height >= minimum)
                assertTrue(reason.bottom <= retry.top)
                assertTrue(viewport.canScrollVertically(1))
                val scrollNode = checkNotNull(viewport.createAccessibilityNodeInfo())
                try {
                    assertTrue(scrollNode.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
                } finally {
                    @Suppress("DEPRECATION")
                    scrollNode.recycle()
                }

                val geometry = NativePlainTextViewGeometry(host, NativeTextSemanticsClipPolicy { view ->
                    if (view === viewport) NativeTextSemanticsClip.Rectangle(
                        Rect(0f, 0f, viewport.width.toFloat(), viewport.height.toFloat()))
                    else NativeTextSemanticsClip.Unclipped
                })
                val reasonBounds = reason.originalAccessibilityBoundsInView()
                val tail = Rect(reasonBounds.left, reasonBounds.bottom - 1f, reasonBounds.right, reasonBounds.bottom)
                fun tailVisible() = !checkNotNull(geometry.capture(reason, Offset.Zero, tail)).visibleBoundsInRoot.isEmpty
                assertFalse(tailVisible())
                assertTrue(viewport.state.scrollBy(viewport.state.maxValue.toFloat()) > 0f)
                layout(shorterHeight)
                assertTrue(tailVisible())
                assertEquals(message, reason.getText())
                assertTrue(column.top + retry.top >= 0 && column.top + retry.bottom <= viewport.height)

                button.mergedRowAccessibility.bindGeometry(geometry) { button.isShown }.use {
                    val info = checkNotNull(button.createAccessibilityNodeInfo())
                    try {
                        assertTrue(info.isVisibleToUser)
                        assertTrue(info.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                    } finally {
                        @Suppress("DEPRECATION")
                        info.recycle()
                    }
                    assertTrue(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                    assertEquals(1, retries)
                }

                val emptyHeight = (64 * density).roundToInt()
                val restoredEmpty = (12 * density).roundToInt()
                assertNull(status.emptyViewport)
                assertNull(status.emptyScrollPosition)
                status.bind(LibraryBodyMode.EMPTY, restoredEmpty) { error("Empty has no Retry action") }
                layout(emptyHeight)
                val emptyViewport = checkNotNull(status.emptyViewport)
                assertSame(emptyViewport, status.statusViewport)
                assertNotSame(viewport.state, emptyViewport.state)
                assertFalse(viewport.isAttachedToWindow)
                assertEquals(restoredEmpty, status.emptyScrollPosition)

                val saved = checkNotNull(status.emptyScrollPosition)
                status.bind(LibraryBodyMode.FAILED, saved) { retries++ }
                layout(shorterHeight)
                val nextFailure = checkNotNull(status.statusViewport)
                assertNotSame(emptyViewport.state, nextFailure.state)
                assertFalse(emptyViewport.isAttachedToWindow)
                assertEquals(0, nextFailure.state.value)
                assertNull(status.emptyScrollPosition)

                status.bind(LibraryBodyMode.EMPTY, saved) { error("Empty has no Retry action") }
                layout(emptyHeight)
                assertEquals(restoredEmpty, status.emptyScrollPosition)
                status.openAtTop()
                layout(emptyHeight)
                assertEquals(0, status.emptyScrollPosition)
                assertEquals(1, retries)
            } finally {
                host.removeView(status)
            }
        }
    }

    @Test fun rangeDurationExposesAdjustmentWithoutADeadClick() = withFocusedHost { host ->
        val environment = ReverbEnvironment(host.context, false)
        var duration = 12.0
        val commits = mutableListOf<Double>()
        val actions = ReverbRangeDurationActions(
            begin = {}, change = {}, end = {}, actionReady = { true },
            durationSeconds = { duration }, maximumSeconds = { it },
            commit = { value, _ ->
                duration = value
                commits += value
                true
            },
        )
        ReverbRangeDurationWheel(host.context, actions, R.string.export_range_title,
            environment.appearance.durationWheel).use { wheel ->
            val view = wheel.view
            host.addView(view, FrameLayout.LayoutParams(280, 160))
            try {
                wheel.bind(60.0, enabled = true, inputVisible = true, durationSeconds = duration,
                    reachableMaximum = 60.0, environment = environment)
                view.measure(exact(280), exact(160))
                view.layout(0, 0, 280, 160)
                val info = checkNotNull(view.createAccessibilityNodeInfo())
                try {
                    assertEquals("android.widget.SeekBar", info.className.toString())
                    assertTrue(info.isFocusable)
                    assertEquals(12f, checkNotNull(info.rangeInfo).current, 0f)
                    assertFalse(info.isClickable)
                    assertFalse(info.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                    assertTrue(info.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
                } finally {
                    @Suppress("DEPRECATION")
                    info.recycle()
                }

                assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertTrue(commits.isEmpty())
                assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
                assertEquals(listOf(13.0), commits)
            } finally {
                host.removeView(view)
            }
        }
    }

    private fun withFocusedHost(block: (FrameLayout) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var root: FrameLayout? = null
        withRetiringTestActivity(activity, root = { root }) {
            val host = onMain {
                FrameLayout(activity).also {
                    root = it
                    activity.setContentView(it)
                }
            }
            val deadline = SystemClock.uptimeMillis() + 5_000L
            while (!onMain { host.isLaidOut && host.hasWindowFocus() }) {
                check(SystemClock.uptimeMillis() < deadline) { "Accessibility fixture window did not gain focus" }
                SystemClock.sleep(16)
            }
            onMain { block(host) }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
