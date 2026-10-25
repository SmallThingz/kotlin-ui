package smallthingz.ui.animation

import smallthingz.ui.MotionDurationScale
import smallthingz.ui.animation.core.LinearEasing
import smallthingz.ui.animation.core.tween
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class UpdatableScrollAnimationTest {
    @Test fun cancelledCapturedFrameCannotMutateValueOrPublish() = runBlocking {
        val clock = CapturedFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        val animation = UpdatableScrollAnimation(tween(100, easing = LinearEasing))
        animation.value = 8f
        var calls = 0
        val job = scope.launch { animation.animateToZero({ calls++ }, { calls++ }) }
        yield()
        val oldBatch = clock.capture()
        job.cancel(); job.join()
        oldBatch(10L); yield()
        assertEquals(8f, animation.value, 0f)
        assertEquals(0, calls)
        assertEquals(0, clock.pendingCount)
    }

    @Test fun cancelledTerminalFrameCannotPublishAndNewOwnerStillCompletes() = runBlocking {
        val clock = CapturedFrameClock()
        val disabled = object : MotionDurationScale { override val scaleFactor = 0f }
        val scope = CoroutineScope(coroutineContext + clock + disabled)
        val animation = UpdatableScrollAnimation(tween(100))
        animation.value = .005f
        var oldCalls = 0
        val old = scope.launch { animation.animateToZero({ oldCalls++ }, { oldCalls++ }) }
        yield()
        val oldBatch = clock.capture()
        old.cancel(); old.join()
        animation.value = 4f
        var before = 0
        var after = 0
        val replacement = scope.launch { animation.animateToZero({ before++ }, { after++ }) }
        yield()
        oldBatch(1L); yield()
        assertEquals(4f, animation.value, 0f)
        assertEquals(0, oldCalls)
        assertEquals(1, clock.pendingCount)
        clock.frame(2L); replacement.join()
        assertEquals(0f, animation.value, 0f)
        assertEquals(1, before)
        assertEquals(1, after)
    }

    @Test fun updatedValueAndCallbacksRetainTheirFrameOrdering() = runBlocking {
        val clock = CapturedFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        val animation = UpdatableScrollAnimation(tween(100, easing = LinearEasing))
        animation.value = 10f
        val order = mutableListOf<String>()
        var frames = 0
        val job = scope.launch {
            animation.animateToZero({ delta ->
                order += "before"
                assertTrue(delta >= 0f)
                assertTrue(animation.value >= 0f)
            }, {
                order += "after"
                frames++
                if (frames == 2) animation.value = 7f
            })
        }
        yield()
        var time = 0L
        while (job.isActive && time <= 20_000_000_000L) {
            assertEquals(1, clock.pendingCount)
            clock.frame(time); yield()
            time += 20_000_000L
        }
        assertFalse(job.isActive)
        job.join()
        assertEquals(0f, animation.value, 0f)
        assertTrue(frames > 2)
        assertEquals(List(frames) { listOf("before", "after") }.flatten(), order)
    }

    @Test fun callbackFailureReleasesTheOwnerAndPreservesTheFailure() = runBlocking {
        supervisorScope {
            val clock = CapturedFrameClock()
            val disabled = object : MotionDurationScale { override val scaleFactor = 0f }
            val scope = CoroutineScope(coroutineContext + clock + disabled)
            val animation = UpdatableScrollAnimation(tween(100))
            val failure = IllegalStateException("callback failure")
            animation.value = 5f
            val failed = scope.async { animation.animateToZero({ throw failure }, {}) }
            yield()
            clock.frame(1L); yield()
            try { failed.await(); fail("Failure was swallowed") }
            catch (actual: IllegalStateException) {
                // Coroutine stack-trace recovery may copy the exception and retain the original
                // as its cause. The callback failure itself must survive either execution mode.
                assertSame(failure, if (actual === failure) actual else actual.cause)
            }
            animation.value = 3f
            var delivered = false
            val next = scope.launch { animation.animateToZero({ delivered = true }, {}) }
            yield()
            clock.frame(2L); next.join()
            assertTrue(delivered)
            assertEquals(0f, animation.value, 0f)
        }
    }
}
