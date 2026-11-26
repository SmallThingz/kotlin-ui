package smallthingz.ui.components

import android.animation.ValueAnimator
import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Source-authored host checks; compositor/predictive parity requires separate qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class NativePageTransitionPanelsTest {
    private fun layout(host: FrameLayout) {
        host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 360, 600)
    }

    private fun animator(motion: NativePageTransition.Panels): ValueAnimator =
        ReflectionHelpers.getField(motion, "animator")

    private fun withPanels(block: (FrameLayout, FrameLayout, FrameLayout, NativePageTransition.Panels) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val host = FrameLayout(controller.get())
        val first = FrameLayout(controller.get())
        val next = FrameLayout(controller.get()).apply { visibility = View.GONE }
        host.addView(first, FrameLayout.LayoutParams(-1, -1))
        host.addView(next, FrameLayout.LayoutParams(-1, -1))
        val previousScale = ValueAnimator.getDurationScale()
        val setScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val motion = NativePageTransition.Panels(host)
        try {
            controller.get().setContentView(host)
            // Manual child layout alone does not deliver the paused window's pending traversal.
            // Establish the same ordinary visible/focused window used by production host fixtures.
            controller.visible().windowFocusChanged(true)
            shadowOf(Looper.getMainLooper()).idle()
            layout(host)
            // A geometry test must own its animator scale after window initialization. Do not
            // depend on the platform cache/default or a prior test's disabled-animation setting.
            setScale.invoke(null, 1f)
            val state = "attached=${host.isAttachedToWindow}, shown=${host.isShown}, " +
                "window=${host.windowVisibility}, laidOut=${host.isLaidOut}, height=${host.height}, " +
                "scale=${ValueAnimator.getDurationScale()}, enabled=${ValueAnimator.areAnimatorsEnabled()}"
            assertTrue(state, host.isAttachedToWindow)
            assertTrue(state, host.isShown)
            assertEquals(state, View.VISIBLE, host.windowVisibility)
            assertTrue(state, host.isLaidOut && host.height > 0)
            assertEquals(state, 1f, ValueAnimator.getDurationScale(), 0f)
            assertTrue(state, ValueAnimator.areAnimatorsEnabled())
            block(host, first, next, motion)
        } finally {
            motion.close()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
            setScale.invoke(null, previousScale)
        }
    }

    @Test fun forwardCoversStationaryPredecessorAndBackLowersOnlyForeground() = withPanels { host, first, next, motion ->
        motion.show(next, first, NativePageTransition.Direction.FORWARD)
        assertTrue(motion.isRunning)
        assertEquals(600f, next.translationY, 0f)
        assertEquals(0f, first.translationY, 0f)
        assertTrue(host.indexOfChild(next) > host.indexOfChild(first))
        animator(motion).currentPlayTime = 110L
        val halfway = next.translationY
        assertTrue(halfway > 0f && halfway < 600f)
        assertEquals(0f, first.translationY, 0f)
        assertEquals(1f, first.alpha, 0f)
        assertEquals(1f, next.alpha, 0f)
        animator(motion).end()
        assertFalse(motion.isRunning)
        assertEquals(View.GONE, first.visibility)
        assertEquals(2, host.childCount)

        motion.show(first, next, NativePageTransition.Direction.BACK)
        animator(motion).currentPlayTime = 110L
        assertEquals(600f - halfway, next.translationY, .01f)
        assertEquals(0f, first.translationY, 0f)
        assertTrue(host.indexOfChild(next) > host.indexOfChild(first))
        animator(motion).end()
        assertEquals(View.GONE, next.visibility)
        assertEquals(View.VISIBLE, first.visibility)
        assertEquals(0f, next.translationY, 0f)
    }

    @Test fun rapidReversalRetiresOldAnimatorAndRestoresInteractionOwnership() = withPanels { host, first, next, motion ->
        var finished = 0
        first.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        first.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        motion.show(next, first, NativePageTransition.Direction.FORWARD) { finished++ }
        val retired = animator(motion)
        retired.currentPlayTime = 110L
        val presented = next.translationY
        assertTrue(presented > 0f && presented < 600f)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, first.importantForAccessibility)
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, first.descendantFocusability)
        assertTrue(host.getChildAt(host.childCount - 1).isClickable)
        motion.show(first, next, NativePageTransition.Direction.BACK) { finished++ }
        assertEquals("Superseded cleanup must not remove the now-incoming root", 0, finished)
        assertEquals(presented, next.translationY, .001f)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, first.importantForAccessibility)
        assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, first.descendantFocusability)
        val active = animator(motion)
        retired.end()
        assertSame(active, animator(motion))
        assertEquals(presented, next.translationY, .001f)
        assertEquals(3, host.childCount) // exactly one shield after ownership transfer
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, next.importantForAccessibility)
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, next.descendantFocusability)
        active.currentPlayTime = 110L
        assertTrue(next.translationY > presented && next.translationY < 600f)
        motion.cancel()
        motion.cancel()
        assertEquals(1, finished)
        assertEquals(2, host.childCount)
        assertEquals(0f, next.translationY, 0f)
        assertFalse(motion.isRunning)
    }

    @Test fun repeatedSamePairReversalKeepsPoseBaselineAndOnlyLatestExitCleanup() = withPanels { host, first, next, motion ->
        val exits = mutableListOf<String>()
        next.translationY = 7f
        motion.show(next, first, NativePageTransition.Direction.FORWARD) {
            exits += "obsolete first"; host.removeView(first)
        }
        val forward = animator(motion)
        forward.currentPlayTime = 110L
        val entrancePose = next.translationY
        assertTrue(entrancePose > 7f && entrancePose < 607f)
        assertSame(first, motion.reversalTarget(next, NativePageTransition.Direction.BACK))
        motion.show(first, next, NativePageTransition.Direction.BACK) {
            exits += "obsolete next"; host.removeView(next)
        }
        assertEquals(entrancePose, next.translationY, .001f)
        assertSame(host, first.parent)
        val back = animator(motion)
        back.currentPlayTime = 110L
        val exitPose = next.translationY
        assertTrue(exitPose > entrancePose && exitPose < 607f)
        assertSame(next, motion.reversalTarget(first, NativePageTransition.Direction.FORWARD))
        motion.show(next, first, NativePageTransition.Direction.FORWARD) {
            exits += "final first"; host.removeView(first)
        }
        assertEquals(exitPose, next.translationY, .001f)
        assertEquals(0f, first.translationY, 0f)
        assertTrue(exits.isEmpty())
        assertSame(host, next.parent)
        assertEquals(3, host.childCount)
        assertTrue(host.indexOfChild(next) > host.indexOfChild(first))
        forward.end(); back.end()
        assertEquals(exitPose, next.translationY, .001f)
        animator(motion).currentPlayTime = 110L
        assertTrue(next.translationY > 7f && next.translationY < exitPose)
        animator(motion).end()
        assertEquals(listOf("final first"), exits)
        assertEquals(7f, next.translationY, 0f)
        assertNull(first.parent)
        assertEquals(1, host.childCount)
        assertEquals(View.VISIBLE, next.visibility)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, next.importantForAccessibility)
        assertFalse(motion.isRunning)
    }

    @Test fun unrelatedReplacementStillSettlesAndReleasesPreviousExit() = withPanels { host, first, next, motion ->
        val third = FrameLayout(host.context)
        host.addView(third, FrameLayout.LayoutParams(-1, -1))
        val exits = mutableListOf<String>()
        motion.show(next, first, NativePageTransition.Direction.FORWARD) {
            exits += "first"; host.removeView(first)
        }
        animator(motion).currentPlayTime = 110L
        assertNull(motion.reversalTarget(next, NativePageTransition.Direction.FORWARD))
        motion.show(third, next, NativePageTransition.Direction.FORWARD) {
            exits += "next"; host.removeView(next)
        }
        assertEquals(listOf("first"), exits)
        assertNull(first.parent)
        assertEquals(0f, next.translationY, 0f)
        assertEquals(600f, third.translationY, 0f)
        motion.cancel()
        assertEquals(listOf("first", "next"), exits)
        assertEquals(1, host.childCount)
        assertSame(third, host.getChildAt(0))
    }

    @Test fun disabledReverseSettlesLatestDestinationWithoutObsoleteCleanup() = withPanels { host, first, next, motion ->
        var obsolete = 0
        var latest = 0
        motion.show(next, first, NativePageTransition.Direction.FORWARD) { obsolete++ }
        animator(motion).currentPlayTime = 110L
        val setScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val previous = ValueAnimator.getDurationScale()
        try {
            setScale.invoke(null, 0f)
            motion.show(first, next, NativePageTransition.Direction.BACK) { latest++ }
            assertEquals(0, obsolete)
            assertEquals(1, latest)
            assertFalse(motion.isRunning)
            assertEquals(2, host.childCount)
            assertEquals(0f, next.translationY, 0f)
            assertEquals(View.VISIBLE, first.visibility)
            assertEquals(View.GONE, next.visibility)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, first.importantForAccessibility)
        } finally { setScale.invoke(null, previous) }
    }

    @Test fun detachReleasesShieldAndRetainedExitExactlyOnce() = withPanels { host, first, next, motion ->
        var finished = 0
        motion.show(next, first, NativePageTransition.Direction.FORWARD) { finished++ }
        animator(motion).currentPlayTime = 110L
        motion.show(first, next, NativePageTransition.Direction.BACK) { finished++ }
        assertEquals(0, finished)
        (host.parent as ViewGroup).removeView(host)
        assertEquals(1, finished)
        assertFalse(motion.isRunning)
        assertEquals(2, host.childCount)
        assertEquals(0f, next.translationY, 0f)
        assertEquals(View.GONE, next.visibility)
        assertEquals(View.VISIBLE, first.visibility)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, next.importantForAccessibility)
        motion.close()
        assertEquals(1, finished)
    }

    @Test fun disabledAnimationsSettleBothDirectionsWithoutAShield() = withPanels { host, first, next, motion ->
        val getScale = ValueAnimator::class.java.getDeclaredMethod("getDurationScale")
        val setScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).apply { isAccessible = true }
        val previous = getScale.invoke(null)
        try {
            setScale.invoke(null, 0f)
            var finished = 0
            motion.show(next, first, NativePageTransition.Direction.FORWARD) { finished++ }
            assertEquals(View.VISIBLE, next.visibility)
            motion.show(first, next, NativePageTransition.Direction.BACK) { finished++ }
            assertEquals(2, finished)
            assertFalse(motion.isRunning)
            assertEquals(2, host.childCount)
            assertEquals(View.VISIBLE, first.visibility)
            assertEquals(View.GONE, next.visibility)
            assertEquals(0f, first.translationY, 0f)
            assertEquals(0f, next.translationY, 0f)
        } finally { setScale.invoke(null, previous) }
    }

    @Test fun disablingAnimationsDuringMotionRetiresTheRetainedExit() = withPanels { host, first, next, motion ->
        motion.show(next, first, NativePageTransition.Direction.FORWARD)
        val running = animator(motion)
        val getScale = ValueAnimator::class.java.getDeclaredMethod("getDurationScale")
        val setScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).apply { isAccessible = true }
        val previous = getScale.invoke(null)
        try {
            setScale.invoke(null, 0f)
            running.currentPlayTime = 110L
            assertFalse(motion.isRunning)
            assertEquals(2, host.childCount)
            assertEquals(View.GONE, first.visibility)
            assertEquals(0f, next.translationY, 0f)
        } finally { setScale.invoke(null, previous) }
    }

    @Test fun fileReplacementKeepsCompleteOutgoingRowsUntilSettlement() = withPanels { host, list, document, motion ->
        val oldRow = View(host.context)
        list.addView(oldRow)
        document.addView(View(host.context))
        motion.show(document, list, NativePageTransition.Direction.FORWARD) { host.removeView(list) }
        assertSame(list, oldRow.parent)
        assertSame(host, list.parent)
        animator(motion).currentPlayTime = 100L
        assertSame(list, oldRow.parent)
        animator(motion).end()
        assertNull(list.parent)
        assertSame(host, document.parent)
        assertEquals(1, host.childCount)
    }

    @Test fun closeRestoresOwnedPoseAndDoesNotStartLaterAnimations() = withPanels { host, first, next, motion ->
        next.translationY = 3f
        motion.show(next, first, NativePageTransition.Direction.FORWARD)
        motion.close()
        assertEquals(3f, next.translationY, 0f)
        motion.show(first, next, NativePageTransition.Direction.BACK)
        assertFalse(motion.isRunning)
        assertEquals(2, host.childCount)
        assertEquals(View.VISIBLE, first.visibility)
    }

    @Test fun unattachedHostAppliesDestinationImmediately() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val host = FrameLayout(controller.get())
        val first = View(host.context)
        val next = View(host.context)
        host.addView(first); host.addView(next)
        val motion = NativePageTransition.Panels(host)
        try {
            motion.show(next, first, NativePageTransition.Direction.FORWARD)
            assertFalse(motion.isRunning)
            assertEquals(View.GONE, first.visibility)
            assertEquals(View.VISIBLE, next.visibility)
            assertEquals(2, host.childCount)
        } finally {
            motion.close()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
