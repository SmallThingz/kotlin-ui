package smallthingz.ui.components

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeScreenTransitionTest {
    private class DequeueTarget(context: Context) : View(context) {
        private var posted: Runnable? = null

        override fun postOnAnimation(action: Runnable) {
            posted = action
        }

        // Model a callback the frame scheduler already removed from its queue for dispatch.
        fun dequeue(): Runnable = checkNotNull(posted).also { posted = null }
    }

    private fun withTransition(block: (DequeueTarget, NativeScreenTransition) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val target = DequeueTarget(controller.get())
        val transition = NativeScreenTransition(target)
        val previousScale = ValueAnimator.getDurationScale()
        val setScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        try {
            controller.get().setContentView(target)
            controller.visible().windowFocusChanged(true)
            shadowOf(Looper.getMainLooper()).idle()
            setScale.invoke(null, 1f)
            assertTrue(target.isAttachedToWindow && target.isShown)
            assertEquals(View.VISIBLE, target.windowVisibility)
            assertTrue(ValueAnimator.areAnimatorsEnabled())
            block(target, transition)
        } finally {
            transition.close()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
            setScale.invoke(null, previousScale)
        }
    }

    @Test fun dequeuedStartCannotAnimateAfterCancel() = withTransition { target, transition ->
        transition.transitionTo("first")
        transition.transitionTo("second")
        val cancelledStart = target.dequeue()
        assertTrue(transition.isRunning)

        transition.cancel()
        cancelledStart.run()

        assertFalse(transition.isRunning)
        assertEquals(1f, target.alpha, 0f)
        assertEquals(0f, target.translationY, 0f)
    }

    @Test fun dequeuedStartCannotConsumeTheNewerStart() = withTransition { target, transition ->
        transition.transitionTo("first")
        transition.transitionTo("second")
        val staleStart = target.dequeue()
        transition.transitionTo("third")
        val currentStart = target.dequeue()

        staleStart.run()
        assertTrue(transition.isRunning)
        assertEquals(1f, target.alpha, 0f)
        assertEquals(0f, target.translationY, 0f)

        currentStart.run()
        assertTrue(transition.isRunning)
        assertEquals(0f, target.alpha, 0f)
        assertTrue(target.translationY > 0f)
    }
}
