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
    @Test fun finalResidualRetargetContinuesInterpolatingUntilExactlyZero() = runBlocking {
        val clock = CapturedFrameClock()
        val animation = UpdatableScrollAnimation(tween(100, easing = LinearEasing))
        animation.value = .005f
        var after = 0
        val job = CoroutineScope(coroutineContext + clock).launch {
            animation.animateToZero({}, { if (++after == 1) animation.value = 7f })
        }
        try {
            yield(); clock.frame(0L); yield()
            assertTrue(job.isActive); assertEquals(7f, animation.value, 0f)
            assertEquals(1, clock.pendingCount)
            clock.frame(20_000_000L); yield()
            // A new normal-duration target must use interpolation, not another residual snap.
            assertTrue(job.isActive); assertEquals(7f, animation.value, 0f)
            repeat(200) { frame ->
                if (job.isActive) { clock.frame((frame + 2) * 20_000_000L); yield() }
            }
            assertTrue(job.isCompleted); assertFalse(job.isCancelled)
            assertEquals(0f, animation.value, 0f); assertEquals(0, clock.pendingCount)
        } finally { job.cancel(); job.join() }
    }

    @Test fun disabledMotionRetargetFromFinalTailDeliversEveryFiniteTarget() = runBlocking {
        val clock = CapturedFrameClock()
        val disabled = object : MotionDurationScale { override val scaleFactor = 0f }
        val animation = UpdatableScrollAnimation(tween(100))
        animation.value = 10f
        val deltas = mutableListOf<Float>()
        var after = 0
        val job = CoroutineScope(coroutineContext + clock + disabled).launch {
            animation.animateToZero({ deltas += it }, {
                when (++after) { 1 -> animation.value = 7f; 2 -> animation.value = -3f }
            })
        }
        try {
            yield(); clock.frame(1L); yield()
            assertTrue(job.isActive)
            clock.frame(2L); yield() // The original final residual tail retargets again.
            assertTrue(job.isActive); assertEquals(-3f, animation.value, 0f)
            assertEquals(1, clock.pendingCount)
            clock.frame(3L); yield()
            assertTrue(job.isCompleted); assertFalse(job.isCancelled)
            assertEquals(listOf(10f, 7f, -3f), deltas)
            assertEquals(3, after); assertEquals(0f, animation.value, 0f)
            assertEquals(0, clock.pendingCount)
        } finally { job.cancel(); job.join() }
    }

    @Test fun durationScaleInitializesBeforeAwaitAndChangesApplyToEveryFrame() = runBlocking {
        val clock = CapturedFrameClock()
        var scaleReads = 0
        val scale = object : MotionDurationScale {
            override var scaleFactor = 0f
                get() {
                    scaleReads++
                    return field
                }
        }
        val animation = UpdatableScrollAnimation(tween(100, easing = LinearEasing))
        animation.value = 8f
        val deltas = mutableListOf<Float>()
        val publishedValues = mutableListOf<Float>()
        val job = CoroutineScope(coroutineContext + clock + scale).launch {
            animation.animateToZero({ deltas += it }, { publishedValues += animation.value })
        }
        try {
            yield()
            assertEquals(1, clock.pendingCount)
            assertEquals(1, scaleReads)
            scale.scaleFactor = 1f
            clock.frame(0L); yield()
            assertEquals(2, scaleReads)
            assertEquals(1, clock.pendingCount)
            clock.frame(25_000_000L); yield()
            assertEquals(6f, animation.value, 0f)
            assertEquals(1, clock.pendingCount)

            scale.scaleFactor = 2f
            clock.frame(75_000_000L); yield()
            assertEquals(4.5f, animation.value, 0f)
            assertEquals(1, clock.pendingCount)

            scale.scaleFactor = 0f
            clock.frame(100_000_000L); yield()
            assertTrue(job.isCompleted)
            assertFalse(job.isCancelled)
            assertEquals(listOf(0f, 2f, 1.5f, 4.5f), deltas)
            assertEquals(listOf(8f, 6f, 4.5f, 0f), publishedValues)
            assertEquals(0, clock.pendingCount)
        } finally { job.cancel(); job.join() }
    }

    @Test fun cancelledRetargetedTailCannotPublishIntoReplacementOwner() = runBlocking {
        val clock = CapturedFrameClock()
        val disabled = object : MotionDurationScale { override val scaleFactor = 0f }
        val scope = CoroutineScope(coroutineContext + clock + disabled)
        val animation = UpdatableScrollAnimation(tween(100))
        animation.value = .005f
        var oldCalls = 0
        val old = scope.launch {
            animation.animateToZero({ oldCalls++ }, { oldCalls++; animation.value = 7f })
        }
        try {
            yield(); clock.frame(1L); yield()
            assertTrue(old.isActive); assertEquals(2, oldCalls)
            val captured = clock.capture()
            old.cancel(); old.join()
            animation.value = 4f
            var newCalls = 0
            val replacement = scope.launch { animation.animateToZero({ newCalls++ }, { newCalls++ }) }
            try {
                yield(); captured(2L); yield()
                assertEquals(2, oldCalls); assertEquals(0, newCalls)
                assertEquals(4f, animation.value, 0f); assertEquals(1, clock.pendingCount)
                clock.frame(3L); yield()
                assertTrue(replacement.isCompleted); assertFalse(replacement.isCancelled)
                assertEquals(2, newCalls); assertEquals(0f, animation.value, 0f)
            } finally { replacement.cancel(); replacement.join() }
        } finally { old.cancel(); old.join() }
    }

    @Test fun retargetedResidualRestartsFrameHistoryWithoutChangingCallbackOrder() = runBlocking {
        val clock = CapturedFrameClock()
        val animation = UpdatableScrollAnimation(tween(100, easing = LinearEasing))
        animation.value = 10f
        val order = mutableListOf<String>()
        var retargeted = false
        var verifyRestart = false
        var restartObserved = false
        val job = CoroutineScope(coroutineContext + clock).launch {
            animation.animateToZero({ delta ->
                order += "before"
                if (verifyRestart) {
                    assertEquals(0f, delta, 0f); assertEquals(7f, animation.value, 0f)
                    verifyRestart = false; restartObserved = true
                }
            }, {
                order += "after"
                if (!retargeted && animation.value == 0f) {
                    animation.value = 7f; retargeted = true; verifyRestart = true
                }
            })
        }
        try {
            yield()
            repeat(200) { frame ->
                if (job.isActive) { clock.frame(frame * 20_000_000L); yield() }
            }
            assertTrue(job.isCompleted); assertFalse(job.isCancelled)
            assertTrue(restartObserved); assertEquals(0f, animation.value, 0f)
            assertEquals(List(order.size / 2) { listOf("before", "after") }.flatten(), order)
            assertEquals(0, clock.pendingCount)
        } finally { job.cancel(); job.join() }
    }
}
