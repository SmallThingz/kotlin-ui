// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.Choreographer
import android.view.MotionEvent
import android.widget.FrameLayout
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowSystemClock
import smallthingz.ui.components.NativeModalWindowRuntime
import smallthingz.ui.interaction.view.NativeClickEnvironment
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.home.ReverbHomeBlobAttachment
import smallthingz.ui.reverb.home.ReverbHomeBlobControl
import smallthingz.ui.reverb.home.ReverbHomeBlobState
import smallthingz.ui.reverb.home.ReverbHomeFlipFrame

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReverbHomeBlobCloseHostTest {
    private val frameInterval = Duration.ofMillis(16)
    private lateinit var previousFrameDelay: Duration
    private var previousVsyncPaused = false

    @Before fun controlVsync() {
        previousFrameDelay = ShadowChoreographer.getFrameDelay()
        previousVsyncPaused = ShadowChoreographer.isPaused()
        // ActivityController focus dispatch drains due work. Keep each rescheduled vsync in the future.
        ShadowChoreographer.setFrameDelay(frameInterval)
        ShadowChoreographer.setPaused(true)
    }

    @After fun restoreVsync() {
        ShadowChoreographer.setFrameDelay(previousFrameDelay)
        ShadowChoreographer.setPaused(previousVsyncPaused)
    }

    @Test fun attachedCloseStopsAudioFramesAndLateUpdatesCannotRestartThem() = withControl {
        val renderer = requireNotNull(attachment.renderer)
        renderer.submitFrame(.3f, FloatArray(0), silenceSuppressed = false)
        assertTrue(framePosted.getBoolean(renderer))

        control.close()
        assertSame(root, control.parent)
        assertTrue(renderer.isAttachedToWindow && renderer.isShown && renderer.hasWindowFocus())
        assertEquals(1, attachment.detachCount)
        assertNull(attachment.renderer)
        assertFalse(framePosted.getBoolean(renderer))
        val stoppedAt = shaderTime.getFloat(renderer)

        control.setEnvironment(ReverbEnvironment(activity.get(), true))
        renderer.submitFrame(.3f, FloatArray(0), silenceSuppressed = false)
        assertFalse(framePosted.getBoolean(renderer))
        activity.settleWindowFocus(false)
        assertFalse(renderer.hasWindowFocus())
        activity.settleWindowFocus(true)
        assertTrue(renderer.hasWindowFocus())
        assertFalse(framePosted.getBoolean(renderer))

        var delivered = false
        Choreographer.getInstance().postFrameCallback { delivered = true }
        advanceFrames(4)
        assertTrue("The window still receives frames after control close", delivered)
        assertEquals(stoppedAt, shaderTime.getFloat(renderer), 0f)

        root.removeView(control)
        root.addView(control)
        activity.settleWindowFocus(true)
        assertTrue(renderer.isAttachedToWindow && renderer.hasWindowFocus())
        assertNull(attachment.renderer)
        assertFalse(framePosted.getBoolean(renderer))
        control.close()
        assertEquals(1, attachment.detachCount)
    }

    @Test fun liveDetachAndReattachStillAllowsFreshAudioFrames() = withControl {
        val renderer = requireNotNull(attachment.renderer)
        renderer.submitFrame(.3f, FloatArray(0), silenceSuppressed = false)
        assertTrue(framePosted.getBoolean(renderer))

        root.removeView(control)
        assertFalse(renderer.isAttachedToWindow)
        assertFalse(framePosted.getBoolean(renderer))
        assertNull(attachment.renderer)

        root.addView(control)
        activity.settleWindowFocus(true)
        assertTrue(renderer.isAttachedToWindow && renderer.hasWindowFocus())
        assertSame(renderer, attachment.renderer)
        val beforeFreshAudio = shaderTime.getFloat(renderer)
        renderer.submitFrame(.3f, FloatArray(0), silenceSuppressed = false)
        assertTrue(framePosted.getBoolean(renderer))
        advanceFrames(4)
        assertTrue("Live audio must advance the renderer after reattachment", shaderTime.getFloat(renderer) > beforeFreshAudio)
    }

    private fun advanceFrames(count: Int) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(count) {
            ShadowSystemClock.advanceBy(frameInterval)
            var dispatched = 0
            while (dispatched < 64 && !looper.isIdle) {
                looper.runOneTask()
                dispatched++
            }
            assertTrue("Main looper still has due work after 64 messages", looper.isIdle)
        }
    }

    private fun awaitWindowState(description: String, reached: () -> Boolean) {
        repeat(8) {
            if (reached()) return
            advanceFrames(1)
        }
        assertTrue("$description after eight frame steps", reached())
    }

    private fun ActivityController<Activity>.settleWindowFocus(focused: Boolean) {
        val decor = get().window.decorView
        windowFocusChanged(focused)
        // A pending traversal can hold the window's focus message until the next vsync.
        awaitWindowState("Activity decor must report window focus=$focused") { decor.hasWindowFocus() == focused }
    }

    private fun withControl(test: Fixture.() -> Unit) {
        // setup() calls visible() before the test can install its hierarchy.
        val activity = Robolectric.buildActivity(Activity::class.java)
            .create().start().postCreate(null).resume()
        val previousScale = ValueAnimator.getDurationScale()
        try {
            val context = activity.get()
            NativeModalWindowRuntime(context).use { runtime ->
                val root = FrameLayout(context)
                context.setContentView(root)
                val attachment = Attachment()
                val control = ReverbHomeBlobControl(context, ReverbEnvironment(context, false),
                    NoPointerInput, runtime.scope) {}
                control.use {
                    root.addView(control)
                    activity.visible().topActivityResumed(true)
                    awaitWindowState("Activity content must attach and be shown") { root.isAttachedToWindow && root.isShown }
                    activity.settleWindowFocus(true)
                    // Settle expansion before measuring scheduling due to the submitted signal.
                    setAnimationScale.invoke(null, 0f)
                    control.bind(
                        ReverbHomeBlobState(attachment, active = true, saving = false,
                            captureEnabled = true, clickableEnabled = true, filled = false, dimmed = false,
                            primaryText = "7:57", secondaryText = null, visualizerVisible = true),
                        ReverbHomeFlipFrame(),
                    ) {}
                    val renderer = requireNotNull(attachment.renderer)
                    assertTrue("Renderer must be attached", renderer.isAttachedToWindow)
                    assertTrue("Renderer must be shown", renderer.isShown)
                    assertTrue("Renderer must inherit window focus", renderer.hasWindowFocus())
                    assertFalse(framePosted.getBoolean(renderer))
                    setAnimationScale.invoke(null, 1f)
                    assertTrue(ValueAnimator.areAnimatorsEnabled())
                    Fixture(activity, root, control, attachment).test()
                }
            }
        } finally {
            try {
                activity.pause().stop().destroy()
            } finally {
                setAnimationScale.invoke(null, previousScale)
            }
        }
    }

    private data class Fixture(
        val activity: ActivityController<Activity>,
        val root: FrameLayout,
        val control: ReverbHomeBlobControl,
        val attachment: Attachment,
    )

    private class Attachment : ReverbHomeBlobAttachment {
        var renderer: AudioReactiveBlobView? = null
            private set
        var detachCount = 0
            private set

        override fun attach(view: AudioReactiveBlobView) { renderer = view }

        override fun detach(view: AudioReactiveBlobView) {
            assertSame(renderer, view)
            renderer = null
            detachCount++
        }

        override fun currentBaseRadiusFraction(): Float? = renderer?.currentBaseRadiusFraction()
    }

    private object NoPointerInput : NativeClickEnvironment {
        override fun admitDown(event: MotionEvent) = false
        override fun interestedParent(event: MotionEvent) = false
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
        override val minimumTouchWidthPx = 0f
        override val minimumTouchHeightPx = 0f
    }

    private companion object {
        val setAnimationScale = ValueAnimator::class.java
            .getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val framePosted = AudioReactiveBlobView::class.java.getDeclaredField("framePosted")
            .apply { isAccessible = true }
        val shaderTime = AudioReactiveBlobView::class.java.getDeclaredField("shaderTimeSeconds")
            .apply { isAccessible = true }
    }
}
