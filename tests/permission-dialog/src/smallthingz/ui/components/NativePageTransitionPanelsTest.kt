package smallthingz.ui.components

import android.animation.ValueAnimator
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Source-authored host checks; compositor/predictive parity requires separate qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
// Robolectric's native window session supplies ADD_FLAG_APP_VISIBLE to the real ViewRoot.
// Legacy graphics leaves that flag unset by default, even for an attached/shown Activity.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativePageTransitionPanelsTest {
    private fun layout(host: FrameLayout, width: Int = 360, height: Int = 600) {
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, width, height)
    }

    private fun animator(motion: NativePageTransition.Panels): ValueAnimator =
        ReflectionHelpers.getField(motion, "animator")

    private fun withPanels(edge: Int = Gravity.BOTTOM, pager: Boolean = false, rtl: Boolean = false, block: (FrameLayout, FrameLayout, FrameLayout, NativePageTransition.Panels) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val applicationInfo = controller.get().applicationInfo
        val previousFlags = applicationInfo.flags
        // The library's empty test manifest does not enable RTL. Opt the fixture in before
        // creating its Views, as the consuming application's manifest does in production.
        if (rtl) applicationInfo.flags = previousFlags or ApplicationInfo.FLAG_SUPPORTS_RTL
        val host = FrameLayout(controller.get()).apply {
            layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        val first = FrameLayout(controller.get())
        val next = FrameLayout(controller.get()).apply { visibility = View.GONE }
        host.addView(first, FrameLayout.LayoutParams(-1, -1))
        host.addView(next, FrameLayout.LayoutParams(-1, -1))
        val previousScale = ValueAnimator.getDurationScale()
        val setScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val motion = NativePageTransition.Panels(host, edge, pager = pager)
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
            applicationInfo.flags = previousFlags
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

    @Test fun reentrantCompletionKeepsTheNewerPanelAndReleasesItsShield() = withPanels { host, first, next, motion ->
        val superseded = FrameLayout(host.context).apply { visibility = View.GONE }
        val winner = FrameLayout(host.context).apply { visibility = View.GONE }
        host.addView(superseded, FrameLayout.LayoutParams(-1, -1))
        host.addView(winner, FrameLayout.LayoutParams(-1, -1))
        next.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        next.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val exits = mutableListOf<String>()
        lateinit var winningAnimator: ValueAnimator

        motion.show(next, first, NativePageTransition.Direction.FORWARD) {
            exits += "first"
            motion.show(winner, next, NativePageTransition.Direction.FORWARD) { exits += "next" }
            winningAnimator = animator(motion)
        }
        val retiredAnimator = animator(motion)
        motion.show(superseded, next, NativePageTransition.Direction.FORWARD) { exits += "superseded" }

        assertEquals(listOf("first"), exits)
        assertSame(winningAnimator, animator(motion))
        assertTrue(motion.owns(winner))
        assertFalse(motion.owns(superseded))
        assertEquals(5, host.childCount)
        retiredAnimator.end()
        assertSame(winningAnimator, animator(motion))
        winningAnimator.end()
        motion.close()

        assertEquals(listOf("first", "next"), exits)
        assertFalse(motion.isRunning)
        assertEquals(4, host.childCount)
        for (root in listOf(first, next, superseded, winner)) assertSame(host, root.parent)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, next.importantForAccessibility)
        assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, next.descendantFocusability)
        assertEquals(View.VISIBLE, winner.visibility)
        assertEquals(0f, winner.translationY, 0f)
        assertEquals(View.GONE, next.visibility)
        assertEquals(View.GONE, superseded.visibility)
    }

    @Test fun supersededPreviewDoesNotClaimTheReentrantPreview() = withPanels { host, first, next, motion ->
        val superseded = FrameLayout(host.context).apply { visibility = View.GONE }
        val winner = FrameLayout(host.context).apply { visibility = View.GONE }
        host.addView(superseded, FrameLayout.LayoutParams(-1, -1))
        host.addView(winner, FrameLayout.LayoutParams(-1, -1))
        var completions = 0

        motion.show(next, first, NativePageTransition.Direction.FORWARD) {
            assertTrue(motion.preview(winner, next, NativePageTransition.Direction.FORWARD) { completions++ })
        }
        assertFalse(motion.preview(superseded, next, NativePageTransition.Direction.FORWARD) { completions++ })
        assertTrue(motion.owns(winner))
        assertEquals(5, host.childCount)

        motion.cancel()
        assertEquals(0, completions)
        assertEquals(4, host.childCount)
        assertEquals(View.VISIBLE, next.visibility)
        assertEquals(View.GONE, winner.visibility)
        assertEquals(View.GONE, superseded.visibility)
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

    @Test fun zeroWidthOrdinaryEndPagerSettlesWhenWidthBecomesPositive() =
        withPanels(Gravity.END, pager = true) { host, first, next, motion ->
            layout(host, width = 0, height = 600)
            assertTrue(host.isLaidOut && host.isShown)
            assertEquals(0, host.width)
            first.translationX = 3f
            next.translationX = 7f
            var finished = 0
            motion.show(next, first, NativePageTransition.Direction.FORWARD) { finished++ }
            val retired = animator(motion)
            assertTrue(motion.isRunning)
            assertEquals(3, host.childCount)
            assertEquals(3f, first.translationX, 0f)
            assertEquals(7f, next.translationX, 0f)
            assertEquals(0, finished)

            layout(host, width = 360, height = 600)
            assertEquals(1, finished)
            assertFalse(motion.isRunning)
            assertFalse(motion.owns(first) || motion.owns(next))
            assertEquals(2, host.childCount)
            assertEquals(3f, first.translationX, 0f)
            assertEquals(7f, next.translationX, 0f)
            assertEquals(View.GONE, first.visibility)
            assertEquals(View.VISIBLE, next.visibility)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, first.importantForAccessibility)
            retired.end()
            layout(host, width = 420, height = 600)
            motion.cancel(); motion.close()
            assertEquals(1, finished)
            assertEquals(3f, first.translationX, 0f)
            assertEquals(7f, next.translationX, 0f)
            assertEquals(2, host.childCount)
        }

    @Test fun ordinaryResizeSettlesVerticalPanelsAndHorizontalPagerExactlyOnce() {
        for (pager in listOf(false, true)) for (direction in NativePageTransition.Direction.entries) {
            for ((width, height) in listOf(420 to 660, 300 to 540)) {
                withPanels(if (pager) Gravity.END else Gravity.BOTTOM, pager = pager) { host, first, next, motion ->
                    fun offset(view: View) = if (pager) view.translationX else view.translationY
                    if (pager) { first.translationX = 3f; next.translationX = 7f }
                    else { first.translationY = 3f; next.translationY = 7f }
                    val incoming = if (direction == NativePageTransition.Direction.FORWARD) next else first
                    val outgoing = if (direction == NativePageTransition.Direction.FORWARD) first else next
                    incoming.visibility = View.GONE
                    outgoing.visibility = View.VISIBLE
                    outgoing.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    outgoing.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    var cleanups = 0
                    // No app preview wrapper/listener: Panels must own ordinary resize settlement.
                    motion.show(incoming, outgoing, direction) {
                        cleanups++
                        assertFalse(motion.isRunning)
                        host.removeView(outgoing)
                    }
                    val retired = animator(motion)
                    retired.currentPlayTime = 110L
                    assertTrue(motion.isRunning)
                    assertEquals(3, host.childCount)
                    val firstPose = offset(first)
                    val nextPose = offset(next)
                    // A position-only layout must not settle motion or retire its reversal target.
                    host.layout(8, 12, 368, 612)
                    assertSame(retired, animator(motion))
                    assertEquals(firstPose, offset(first), .001f)
                    assertEquals(nextPose, offset(next), .001f)
                    assertEquals(0, cleanups)

                    layout(host, width, height)
                    assertEquals(1, cleanups)
                    assertFalse(motion.isRunning)
                    assertFalse(motion.owns(first) || motion.owns(next))
                    assertEquals(3f, offset(first), 0f)
                    assertEquals(7f, offset(next), 0f)
                    assertEquals(View.VISIBLE, incoming.visibility)
                    assertEquals(View.GONE, outgoing.visibility)
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, outgoing.importantForAccessibility)
                    assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, outgoing.descendantFocusability)
                    assertNull(outgoing.parent)
                    assertSame(host, incoming.parent)
                    assertEquals(1, host.childCount) // departed root and input shield both released
                    retired.end()
                    layout(host, 360, 600)
                    motion.cancel()
                    motion.close()
                    assertEquals(1, cleanups)
                    assertEquals(3f, offset(first), 0f)
                    assertEquals(7f, offset(next), 0f)
                    assertEquals(1, host.childCount)
                }
            }
        }
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
    @Test fun predictiveCancelReversesThePresentedPoseWithoutCommittingBack() = withPanels { host, first, next, motion ->
        motion.show(next, first, NativePageTransition.Direction.FORWARD)
        animator(motion).end()
        var finished = 0
        assertTrue(motion.preview(first, next, NativePageTransition.Direction.BACK) { finished++ })
        motion.seekPreview(.4f)
        assertEquals(240f, next.translationY, .001f)
        motion.show(next, first, NativePageTransition.Direction.FORWARD)
        assertEquals(240f, next.translationY, .001f)
        animator(motion).end()
        assertEquals(0, finished)
        assertEquals(View.VISIBLE, next.visibility)
        assertEquals(View.GONE, first.visibility)
        assertEquals(2, host.childCount)
    }

    @Test fun remainingProgressFractionalReleasePreservesPoseAndCancelResumesEntry() = withPanels { host, first, next, motion ->
        val policy = NativePageTransition.PredictiveSettlement.REMAINING_PROGRESS
        var obsoleteEntry = 0
        var cancelledBack = 0
        var cancelFinished = 0
        var committedBack = 0
        motion.show(next, first, NativePageTransition.Direction.FORWARD) { obsoleteEntry++ }
        val entry = animator(motion)
        entry.currentPlayTime = 110L
        val interruptedPose = next.translationY
        assertTrue(interruptedPose > 0f && interruptedPose < 600f)
        assertTrue(motion.preview(first, next, NativePageTransition.Direction.BACK) { cancelledBack++ })
        assertEquals(interruptedPose, next.translationY, .001f)
        motion.seekPreview(.5f)
        val cancelReleasePose = next.translationY
        assertTrue(cancelReleasePose > interruptedPose && cancelReleasePose < 600f)
        motion.cancelPreview(policy) { cancelFinished++ }
        val returning = animator(motion)
        val shield = host.getChildAt(host.childCount - 1)
        assertNotSame(entry, returning)
        assertEquals(160L, returning.duration)
        assertEquals(cancelReleasePose, next.translationY, .001f)
        assertTrue(motion.owns(first) && motion.owns(next))
        assertEquals(3, host.childCount)
        assertTrue(shield.isClickable)
        returning.currentPlayTime = 80L
        assertTrue(next.translationY > interruptedPose && next.translationY < cancelReleasePose)
        assertEquals(0, cancelledBack)
        assertEquals(0, cancelFinished)
        returning.end()
        val resumed = animator(motion)
        assertNotSame(returning, resumed)
        assertEquals(220L, resumed.duration)
        assertEquals(interruptedPose, next.translationY, .001f)
        assertTrue(motion.isRunning)
        assertTrue(motion.owns(first) && motion.owns(next))
        assertSame(host, first.parent)
        assertSame(host, next.parent)
        assertSame(shield, host.getChildAt(host.childCount - 1))
        assertEquals(3, host.childCount)
        assertEquals(0, cancelFinished)
        entry.end(); returning.end()
        assertSame(resumed, animator(motion))
        assertEquals(interruptedPose, next.translationY, .001f)
        resumed.currentPlayTime = 110L
        assertTrue(next.translationY > 0f && next.translationY < interruptedPose)
        resumed.end()
        assertEquals(0, obsoleteEntry)
        assertEquals(0, cancelledBack)
        assertEquals(1, cancelFinished)
        assertFalse(motion.isRunning)
        assertEquals(View.VISIBLE, next.visibility)
        assertEquals(View.GONE, first.visibility)
        assertEquals(0f, next.translationY, 0f)
        assertEquals(2, host.childCount)

        assertTrue(motion.preview(first, next, NativePageTransition.Direction.BACK) { committedBack++ })
        motion.seekPreview(.5f)
        assertEquals(300f, next.translationY, .001f)
        motion.commitPreview(policy)
        val settling = animator(motion)
        assertEquals(110L, settling.duration)
        assertEquals(300f, next.translationY, .001f)
        assertEquals(0, committedBack)
        settling.currentPlayTime = 55L
        assertTrue(next.translationY > 300f && next.translationY < 600f)
        assertEquals(0, committedBack)
        settling.end()
        assertEquals(1, committedBack)
        assertFalse(motion.isRunning)
        assertEquals(View.VISIBLE, first.visibility)
        assertEquals(View.GONE, next.visibility)
        assertEquals(2, host.childCount)
        settling.end(); motion.commitPreview(policy); motion.cancel(); motion.close()
        assertEquals(1, committedBack)
        assertEquals(1, cancelFinished)
        assertEquals(0, cancelledBack)
        assertEquals(0, obsoleteEntry)
    }

    @Test fun remainingProgressFullReleaseSettlesSynchronouslyWithoutAnimator() = withPanels { host, first, next, motion ->
        val policy = NativePageTransition.PredictiveSettlement.REMAINING_PROGRESS
        motion.show(next, first, NativePageTransition.Direction.FORWARD)
        animator(motion).end()
        var finished = 0
        assertTrue(motion.preview(first, next, NativePageTransition.Direction.BACK) { finished++ })
        motion.seekPreview(1f)
        assertEquals(600f, next.translationY, 0f)
        assertEquals(0f, first.translationY, 0f)
        assertTrue(motion.isRunning)
        assertEquals(3, host.childCount)
        assertEquals(0, finished)
        motion.commitPreview(policy)
        // Completion is synchronous under the paused looper: no equal-endpoint animator or drain.
        assertEquals(1, finished)
        assertFalse(motion.isRunning)
        assertNull(ReflectionHelpers.getField<ValueAnimator?>(motion, "animator"))
        assertEquals(View.VISIBLE, first.visibility)
        assertEquals(View.GONE, next.visibility)
        assertEquals(0f, first.translationY, 0f)
        assertEquals(0f, next.translationY, 0f) // Hidden outgoing root returns to its baseline.
        assertEquals(2, host.childCount)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, next.importantForAccessibility)
        motion.commitPreview(policy); motion.cancel(); motion.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, finished)
        assertNull(ReflectionHelpers.getField<ValueAnimator?>(motion, "animator"))
    }

    @Test fun predictiveCommitContinuesFromReleaseAndStopDeliversOnce() = withPanels { host, first, next, motion ->
        motion.show(next, first, NativePageTransition.Direction.FORWARD)
        animator(motion).end()
        var finished = 0
        assertTrue(motion.preview(first, next, NativePageTransition.Direction.BACK) { finished++ })
        motion.seekPreview(.6f)
        motion.commitPreview()
        assertEquals(360f, next.translationY, .001f)
        motion.cancel(); motion.cancel()
        assertEquals(1, finished)
        assertEquals(View.VISIBLE, first.visibility)
        assertEquals(2, host.childCount)
    }

    @Test fun previewDetachRestoresOutgoingAndNeverCommitsNavigation() = withPanels { host, first, next, motion ->
        var finished = 0
        assertTrue(motion.preview(next, first, NativePageTransition.Direction.FORWARD) { finished++ })
        motion.seekPreview(.7f)
        (host.parent as ViewGroup).removeView(host)
        assertEquals(0, finished)
        assertEquals(View.VISIBLE, first.visibility)
        assertEquals(View.GONE, next.visibility)
        assertFalse(motion.isRunning)
        assertEquals(2, host.childCount)
    }

    @Test fun disabledPreviewDoesNotNavigateUntilCommitted() = withPanels { host, first, next, motion ->
        val setScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        var finished = 0
        setScale.invoke(null, 0f)
        assertTrue(motion.preview(next, first, NativePageTransition.Direction.FORWARD) { finished++ })
        val pose = next.translationY
        motion.seekPreview(.8f)
        assertEquals(pose, next.translationY, 0f)
        motion.cancel()
        assertEquals(0, finished)
        assertEquals(View.VISIBLE, first.visibility)
        assertTrue(motion.preview(next, first, NativePageTransition.Direction.FORWARD) { finished++ })
        motion.commitPreview()
        assertEquals(1, finished)
        assertFalse(motion.isRunning)
        assertEquals(2, host.childCount)
    }

    @Test fun endPanelUsesLogicalEndInRtlAndSharesTheReverseProgress() = withPanels(Gravity.END, rtl = true) { host, first, next, motion ->
        assertEquals("Fixture must resolve RTL before testing END geometry", View.LAYOUT_DIRECTION_RTL, host.layoutDirection)
        motion.show(next, first, NativePageTransition.Direction.FORWARD)
        assertEquals(-360f, next.translationX, 0f)
        assertEquals(0f, next.translationY, 0f)
        animator(motion).end()
        assertTrue(motion.preview(first, next, NativePageTransition.Direction.BACK))
        motion.seekPreview(.5f)
        assertEquals(-180f, next.translationX, .001f)
        motion.cancel()
        assertEquals(0f, next.translationX, 0f)
        assertEquals(View.VISIBLE, next.visibility)
    }

    @Test fun topFallbackMovesIncomingDownThenReversesUp() = withPanels(Gravity.TOP) { _, first, next, motion ->
        motion.show(next, first, NativePageTransition.Direction.FORWARD)
        assertEquals(-600f, next.translationY, 0f)
        animator(motion).end()
        assertTrue(motion.preview(first, next, NativePageTransition.Direction.BACK))
        motion.seekPreview(.5f)
        assertEquals(-300f, next.translationY, .001f)
        motion.commitPreview()
        animator(motion).end()
        assertEquals(View.VISIBLE, first.visibility)
    }

    @Test fun rejectedWindowPreviewReturnsFalseWithoutACompletionOwner() = withPanels { host, first, next, motion ->
        val attachInfo: Any = ReflectionHelpers.getField(host, "mAttachInfo")
        var finished = 0
        try {
            ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.GONE)
            assertFalse(motion.preview(next, first, NativePageTransition.Direction.FORWARD) { finished++ })
            motion.commitPreview()
            assertEquals(0, finished)
            assertFalse(motion.isRunning)
        } finally { ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE) }
        assertTrue(motion.preview(next, first, NativePageTransition.Direction.FORWARD) { finished++ })
        motion.commitPreview()
        animator(motion).end()
        assertEquals(1, finished)
    }

    @Test fun pagerNextMovesBothBodiesAndDisabledReverseRestoresBothBaselines() =
        withPanels(Gravity.END, pager = true) { _, first, next, motion ->
            motion.show(next, first, NativePageTransition.Direction.FORWARD)
            assertEquals(360f, next.translationX, 0f)
            assertEquals(0f, first.translationX, 0f)
            animator(motion).currentPlayTime = 110L
            assertTrue(first.translationX < 0f)
            assertEquals(360f, next.translationX - first.translationX, .001f)
            val setScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
                .apply { isAccessible = true }
            setScale.invoke(null, 0f)
            motion.show(first, next, NativePageTransition.Direction.BACK)
            assertEquals(0f, first.translationX, 0f)
            assertEquals(0f, next.translationX, 0f)
            assertEquals(View.VISIBLE, first.visibility)
            assertEquals(View.GONE, next.visibility)
        }

}
