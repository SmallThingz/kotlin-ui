package smallthingz.ui.animation

import android.view.View
import smallthingz.ui.animation.view.ViewFrameLoop
import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.*
import org.junit.Test

/** Executes the production frame owner with only View attachment supplied by a host fixture. */
class ViewFrameLoopFixtureTest {
    @Test fun cancelledNonCancellableFrameDoesNotRequeueOrRetainParent() = runBlocking {
        val clock = NonCancellableFrameClock()
        val parent = Job()
        val view = View()
        var samples = 0
        val frames = ViewFrameLoop(view, CoroutineScope(coroutineContext + clock + parent), { true }) { _, _ -> samples++ }
        try {
            frames.request(); yield()
            val obsolete = clock.capture()
            parent.cancel()
            assertFalse(parent.isCompleted)
            obsolete(1L); yield()
            assertEquals(0, samples)
            assertEquals(0, clock.pendingCount)
            assertTrue(parent.isCompleted)
        } finally {
            view.attached = false
            frames.cancel(); parent.cancel()
            clock.capture()(2L); yield()
        }
    }

    @Test fun cancelledNonCancellableFrameCannotQueueBesideReplacement() = runBlocking {
        val clock = NonCancellableFrameClock()
        val parent = Job()
        val view = View()
        var samples = 0
        val frames = ViewFrameLoop(view, CoroutineScope(coroutineContext + clock + parent), { samples == 0 }) { _, _ -> samples++ }
        try {
            frames.request(); yield()
            val obsolete = clock.capture()
            frames.cancel(); frames.request(); yield()
            assertEquals(1, clock.pendingCount)
            obsolete(1L); yield()
            assertEquals(0, samples)
            assertEquals(1, clock.pendingCount)
            clock.capture()(2L); yield()
            assertEquals(1, samples)
            assertEquals(0, clock.pendingCount)
            parent.cancel(); yield()
            assertTrue(parent.isCompleted)
        } finally {
            view.attached = false
            frames.cancel(); parent.cancel()
            clock.capture()(3L); yield()
        }
    }

    @Test fun cancelledCapturedBatchCannotPublishOrClearReplacement() = runBlocking {
        val clock = CapturedFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        val view = View()
        var samples = 0
        val frames = ViewFrameLoop(view, scope, { samples == 0 }) { _, _ -> samples++ }
        try {
            frames.request(); yield()
            val obsolete = clock.capture()
            frames.cancel(); frames.request(); yield()
            assertEquals(1, clock.pendingCount)
            obsolete(1L); yield()
            assertEquals(0, samples)
            assertEquals(1, clock.pendingCount)
            clock.frame(2L); yield()
            assertEquals(1, samples)
        } finally { frames.cancel() }
    }

    @Test fun scopeCancellationRejectsCapturedBatch() = runBlocking {
        val clock = CapturedFrameClock()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + clock + job)
        var samples = 0
        val frames = ViewFrameLoop(View(), scope, { true }) { _, _ -> samples++ }
        try {
            frames.request(); yield()
            val obsolete = clock.capture()
            job.cancel(); job.join()
            obsolete(1L); yield()
            assertEquals(0, samples)
            assertEquals(0, clock.pendingCount)
        } finally { frames.cancel(); job.cancel() }
    }

    @Test fun detachReattachRejectsOldBatchButAllowsNewLifetime() = runBlocking {
        val clock = CapturedFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        val view = View()
        var samples = 0
        val frames = ViewFrameLoop(view, scope, { samples == 0 }) { _, _ -> samples++ }
        try {
            frames.request(); yield()
            val obsolete = clock.capture()
            view.attached = false; frames.cancel()
            view.attached = true; frames.request(); yield()
            obsolete(1L); yield()
            assertEquals(0, samples)
            assertEquals(1, clock.pendingCount)
            clock.frame(2L); yield()
            assertEquals(1, samples)
        } finally { frames.cancel() }
    }

    @Test fun queuedFrameStopsWhenItsWorkDisappears() = runBlocking {
        val clock = CapturedFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        var work = true
        var samples = 0
        val frames = ViewFrameLoop(View(), scope, { work }) { _, _ -> samples++; work = false }
        try {
            frames.request(); yield()
            work = false
            clock.frame(1L); yield()
            assertEquals(0, samples)
            assertEquals(0, clock.pendingCount)
            work = true; frames.request(); yield()
            clock.frame(2L); yield()
            assertEquals(1, samples)
        } finally { frames.cancel() }
    }

    @Test fun sampleCanCancelAndRequestReplacementWithoutOldFinallyClearingIt() = runBlocking {
        val clock = CapturedFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        var samples = 0
        lateinit var frames: ViewFrameLoop
        frames = ViewFrameLoop(View(), scope, { samples < 2 }) { _, _ ->
            samples++
            if (samples == 1) { frames.cancel(); frames.request() }
        }
        try {
            frames.request(); yield()
            clock.frame(1L); yield()
            assertEquals(1, samples)
            assertEquals(1, clock.pendingCount)
            clock.frame(2L); yield()
            assertEquals(2, samples)
            assertEquals(0, clock.pendingCount)
        } finally { frames.cancel() }
    }

    /** A valid supplied clock need not cancel its suspended continuation when the owner cancels. */
    private class NonCancellableFrameClock : MonotonicFrameClock {
        private val pending = mutableListOf<(Long) -> Unit>()
        val pendingCount get() = pending.size
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = suspendCoroutine { continuation ->
            pending.add { time -> continuation.resumeWith(runCatching { onFrame(time) }) }
        }
        fun capture(): (Long) -> Unit {
            val batch = pending.toList()
            pending.clear()
            return { time -> batch.forEach { it(time) } }
        }
    }
}
