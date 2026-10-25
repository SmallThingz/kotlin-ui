package smallthingz.ui.animation

import smallthingz.ui.MotionDurationScale
import smallthingz.ui.animation.core.LinearEasing
import smallthingz.ui.animation.core.tween
import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.*
import org.junit.Test

class UpdatableScrollCancellationTest {
    @Test fun beforeFrameCancellationStopsMainLoopDelivery() = beforeFrameCancellation(8f)
    @Test fun beforeFrameCancellationStopsTerminalFrameDelivery() = beforeFrameCancellation(.005f)
    @Test fun afterFrameCancellationDoesNotRequestAnotherMainFrame() = afterFrameCancellation(8f)
    @Test fun afterFrameCancellationDoesNotRequestATerminalFrame() = afterFrameCancellation(.005f)
    @Test fun cancelledStartDoesNotRequestMainFrame() = cancelledStart(8f)
    @Test fun cancelledStartDoesNotRequestTerminalFrame() = cancelledStart(.005f)

    private fun beforeFrameCancellation(initial: Float) = runBlocking {
        val clock = NonCancellableClock()
        val animation = UpdatableScrollAnimation(tween(100, easing = LinearEasing))
        animation.value = initial
        var before = 0
        var after = 0
        var returnedNormally = false
        lateinit var job: Job
        job = CoroutineScope(coroutineContext + clock).launch {
            animation.animateToZero({ before++; job.cancel() }, { after++ })
            returnedNormally = true
        }
        try {
            yield()
            assertEquals(1, clock.pendingCount)
            clock.frame(1L); yield()
            assertEquals(1, before)
            assertEquals(0, after)
            assertEquals(0, clock.pendingCount)
            assertTrue(job.isCompleted)
            assertFalse(returnedNormally)
            assertReusable(animation, clock)
        } finally {
            job.cancel()
            // Baseline may have queued one more noncancellable request; deliver it so its existing
            // inside-callback guard can release the coroutine instead of hanging a failing test.
            clock.frame(3L); yield(); job.join()
        }
    }

    private fun afterFrameCancellation(nextValue: Float) = runBlocking {
        val clock = NonCancellableClock()
        val animation = UpdatableScrollAnimation(tween(100, easing = LinearEasing))
        animation.value = 8f
        var before = 0
        var after = 0
        var returnedNormally = false
        lateinit var job: Job
        job = CoroutineScope(coroutineContext + clock).launch {
            animation.animateToZero({ before++ }, {
                after++
                animation.value = nextValue
                job.cancel()
            })
            returnedNormally = true
        }
        try {
            yield()
            clock.frame(1L); yield()
            assertEquals(1, before)
            assertEquals(1, after)
            assertEquals(nextValue, animation.value, 0f)
            assertEquals(0, clock.pendingCount)
            assertTrue(job.isCompleted)
            assertFalse(returnedNormally)
            assertReusable(animation, clock)
        } finally {
            job.cancel(); clock.frame(3L); yield(); job.join()
        }
    }

    private fun cancelledStart(initial: Float) = runBlocking {
        val clock = NonCancellableClock()
        val parent = Job().apply { cancel() }
        val animation = UpdatableScrollAnimation(tween(100))
        animation.value = initial
        var calls = 0
        val job = CoroutineScope(coroutineContext + clock + parent).launch(start = CoroutineStart.UNDISPATCHED) {
            animation.animateToZero({ calls++ }, { calls++ })
        }
        try {
            assertEquals(0, clock.pendingCount)
            assertEquals(0, calls)
            assertEquals(initial, animation.value, 0f)
            assertTrue(job.isCompleted)
            assertReusable(animation, clock)
        } finally {
            job.cancel(); clock.frame(3L); yield(); job.join()
        }
    }

    private suspend fun assertReusable(animation: UpdatableScrollAnimation, clock: NonCancellableClock) {
        val disabled = object : MotionDurationScale { override val scaleFactor = 0f }
        animation.value = 3f
        var before = 0
        var after = 0
        val replacement = CoroutineScope(coroutineContext + clock + disabled).launch {
            animation.animateToZero({ before++ }, { after++ })
        }
        yield()
        assertEquals(1, clock.pendingCount)
        clock.frame(2L); replacement.join()
        assertEquals(1, before)
        assertEquals(1, after)
        assertEquals(0f, animation.value, 0f)
        assertEquals(0, clock.pendingCount)
    }

    private class NonCancellableClock : MonotonicFrameClock {
        private val pending = mutableListOf<(Long) -> Unit>()
        val pendingCount get() = pending.size
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = suspendCoroutine { continuation ->
            pending.add { time -> continuation.resumeWith(runCatching { onFrame(time) }) }
        }
        fun frame(time: Long) {
            val batch = pending.toList()
            pending.clear()
            batch.forEach { it(time) }
        }
    }
}
