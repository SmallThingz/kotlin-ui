package smallthingz.ui.components

import android.animation.ValueAnimator
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.transition.Slide
import android.transition.TransitionValues
import android.transition.Visibility
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercise Android's actual Slide geometry, not a second implementation of its edge arithmetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class NativePageTransitionHorizontalTest {
    @Test fun forwardLtrMovesBothPagesLeft() = checkMotion(NativePageTransition.Direction.FORWARD, false, 1f)
    @Test fun backLtrMovesBothPagesRight() = checkMotion(NativePageTransition.Direction.BACK, false, -1f)
    @Test fun forwardRtlMovesBothPagesRight() = checkMotion(NativePageTransition.Direction.FORWARD, true, -1f)
    @Test fun backRtlMovesBothPagesLeft() = checkMotion(NativePageTransition.Direction.BACK, true, 1f)

    private fun checkMotion(direction: NativePageTransition.Direction, rtl: Boolean, incomingSign: Float) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val applicationInfo = controller.get().applicationInfo
        val previousFlags = applicationInfo.flags
        // The library's empty manifest does not opt the host application into RTL.
        applicationInfo.flags = previousFlags or ApplicationInfo.FLAG_SUPPORTS_RTL
        val host = FrameLayout(controller.get()).apply {
            layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        val outgoing = View(controller.get())
        val incoming = View(controller.get())
        host.addView(outgoing, FrameLayout.LayoutParams(-1, -1))
        host.addView(incoming, FrameLayout.LayoutParams(-1, -1))
        val previousScale = ValueAnimator.getDurationScale()
        val setScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val animators = mutableListOf<ValueAnimator>()
        try {
            controller.get().setContentView(host)
            host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 360, 600)
            assertEquals(if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, host.layoutDirection)
            setScale.invoke(null, 1f)
            val scene = NativePageTransition.horizontalSceneTransition(direction)
            assertEquals(2, scene.transitionCount)
            val enter = scene.getTransitionAt(0) as Slide
            val exit = scene.getTransitionAt(1) as Slide
            // A combined same-edge Slide must not animate both visibility changes.
            assertEquals(Visibility.MODE_IN, enter.mode)
            assertEquals(Visibility.MODE_OUT, exit.mode)
            val endValues = TransitionValues(incoming).also(enter::captureEndValues)
            val startValues = TransitionValues(outgoing).also(exit::captureStartValues)
            val enterAnimation = requireNotNull(enter.onAppear(host, incoming, null, endValues)) as ValueAnimator
            val exitAnimation = requireNotNull(exit.onDisappear(host, outgoing, startValues, null)) as ValueAnimator
            animators += enterAnimation
            animators += exitAnimation
            // Transition.animate normally applies these shared recipe properties to each animator.
            animators.forEach {
                it.duration = scene.duration
                it.interpolator = scene.interpolator
                it.start()
                it.currentPlayTime = 0L
            }
            assertEquals(incomingSign * host.width, incoming.translationX, .01f)
            assertEquals(0f, outgoing.translationX, .01f)
            animators.forEach { it.currentPlayTime = scene.duration / 2 }
            val incomingDistance = incomingSign * incoming.translationX
            val outgoingDistance = -incomingSign * outgoing.translationX
            assertTrue("Incoming page must approach the viewport from its own edge", incomingDistance > 0f && incomingDistance < host.width)
            assertTrue("Outgoing page must leave at the opposite edge", outgoingDistance > 0f && outgoingDistance < host.width)
            assertEquals("The two page poses must remain one viewport apart", host.width.toFloat(),
                incomingDistance + outgoingDistance, .01f)
            assertEquals(1f, incoming.alpha, 0f)
            assertEquals(1f, outgoing.alpha, 0f)
        } finally {
            animators.forEach { it.cancel() }
            controller.pause().stop().destroy()
            setScale.invoke(null, previousScale)
            applicationInfo.flags = previousFlags
        }
    }
}
