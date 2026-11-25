package smallthingz.ui.components

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import smallthingz.ui.foundation.MutatePriority
import smallthingz.ui.foundation.MutatorMutex

class NativeTooltipReleaseLifetimeTest {
    @Test(timeout = 10_000)
    fun replacementWindowReceivesSettledFocusAppearanceBeforeShow() = runBlocking {
        val motion = NativeSheetTooltipMotion()
        motion.target(true)
        motion.animate { motion.frame(0L, 0f) }

        // Detaching the handle retires its window; a focus-owned show retains the motion target.
        motion.target(true)
        var replacementAlpha = 0f
        var replacementScale = 0f
        var shown = false
        motion.showWindow({ scale, alpha ->
            replacementAlpha = alpha
            replacementScale = scale
        }, {
            assertEquals(1f, replacementAlpha, 0f)
            assertEquals(1f, replacementScale, 0f)
            shown = true
        })
        assertTrue(shown)
        motion.animate { error("Showing an already settled tooltip must not restart frames") }
    }

    @Test(timeout = 10_000)
    fun settledFocusedPopupStopsRequestingFramesAndDismissRestartsMotion() = runBlocking {
        val motion = NativeSheetTooltipMotion()
        var frames = 0
        suspend fun finishTransition() {
            motion.animate {
                check(++frames <= 2) { "Settled tooltip requested another frame" }
                motion.frame(0L, 0f)
            }
        }
        motion.target(true)
        finishTransition()
        assertTrue(motion.shouldExist)
        assertEquals(1f, motion.alpha, 0f)
        finishTransition()
        assertEquals(1, frames)
        motion.target(false)
        finishTransition()
        assertEquals(2, frames)
        assertFalse(motion.shouldExist)
        assertEquals(0f, motion.alpha, 0f)
    }

    @Test(timeout = 10_000)
    fun repeatedGesturesLeaveNoSubscribersOrChildJobs() = runBlocking {
        val owner = Job(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner)
        try {
            repeat(128) {
                val held = MutableStateFlow(true)
                var dismissals = 0
                val waiter = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    dismissNativeTooltipAfterRelease(held) { dismissals++ }
                }
                assertEquals(1, held.subscriptionCount.value)
                assertFalse(waiter.isCompleted)
                held.value = false
                waiter.join()
                assertEquals(1, dismissals)
                assertEquals(0, held.subscriptionCount.value)
                assertEquals(0, owner.children.count())
            }
        } finally {
            owner.cancelAndJoin()
        }
    }

    @Test(timeout = 10_000)
    fun releaseBeforeAndAfterShowTimeoutBothCompleteOnce() = runBlocking {
        for (releaseFirst in listOf(true, false)) {
            val held = MutableStateFlow(true)
            val showStarted = CompletableDeferred<Unit>()
            val showFinished = CompletableDeferred<Unit>()
            var dismissals = 0
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    withTimeout(20L) {
                        showStarted.complete(Unit)
                        awaitCancellation()
                    }
                } finally {
                    showFinished.complete(Unit)
                    dismissNativeTooltipAfterRelease(held) { dismissals++ }
                }
            }
            showStarted.await()
            if (releaseFirst) held.value = false
            showFinished.await()
            if (!releaseFirst) {
                assertEquals(0, dismissals)
                assertEquals(1, held.subscriptionCount.value)
                assertFalse(waiter.isCompleted)
                held.value = false
            }
            waiter.join()
            assertEquals(1, dismissals)
            assertEquals(0, held.subscriptionCount.value)
        }
    }

    @Test(timeout = 10_000)
    fun mutexHandoffRetainsOnlyTheOutgoingGestureUntilRelease() = runBlocking {
        val mutex = MutatorMutex()
        val held = MutableStateFlow(true)
        val outgoingStarted = CompletableDeferred<Unit>()
        val outgoingWaitingForRelease = CompletableDeferred<Unit>()
        var dismissals = 0
        val outgoing = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                mutex.mutate(MutatePriority.PreventUserInput) {
                    outgoingStarted.complete(Unit)
                    awaitCancellation()
                }
            } finally {
                outgoingWaitingForRelease.complete(Unit)
                dismissNativeTooltipAfterRelease(held) { dismissals++ }
            }
        }
        outgoingStarted.await()
        val incoming = launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.mutate(MutatePriority.PreventUserInput) { }
        }
        incoming.join()
        outgoingWaitingForRelease.await()
        assertFalse(outgoing.isCompleted)
        assertEquals(1, held.subscriptionCount.value)
        held.value = false
        outgoing.join()
        assertEquals(1, dismissals)
        assertEquals(0, held.subscriptionCount.value)
        assertTrue(incoming.isCompleted)
    }

    @Test(timeout = 10_000)
    fun nodeCancellationRemovesHeldSubscriptionWithoutDismissCallback() = runBlocking {
        val owner = Job(coroutineContext[Job])
        val held = MutableStateFlow(true)
        var dismissals = 0
        val waiter = CoroutineScope(coroutineContext + owner).launch(start = CoroutineStart.UNDISPATCHED) {
            dismissNativeTooltipAfterRelease(held) { dismissals++ }
        }
        assertEquals(1, held.subscriptionCount.value)
        owner.cancelAndJoin()
        held.value = false
        assertTrue(waiter.isCancelled)
        assertEquals(0, held.subscriptionCount.value)
        assertEquals(0, owner.children.count())
        assertEquals(0, dismissals)
    }
}
