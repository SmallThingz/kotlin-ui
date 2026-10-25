package smallthingz.ui.animation

import smallthingz.ui.MotionDurationScale
import smallthingz.ui.animation.view.AttachedFrameLoop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AttachedFrameLoopTest {
    @Test fun requiresAttachmentAndUsesTheOwningDurationScale() = runBlocking {
        val clock = CapturedFrameClock()
        val scale = object : MotionDurationScale { override val scaleFactor = 2f }
        val scope = CoroutineScope(coroutineContext + clock + scale)
        var work = true
        var delivered: Pair<Long, Float>? = null
        val frames = AttachedFrameLoop(scope, { work }) { time, duration ->
            delivered = time to duration
            work = false
        }
        try {
            frames.schedule(); yield()
            assertEquals(0, clock.pendingCount)
            frames.attached(); yield()
            assertEquals(1, clock.pendingCount)
            clock.frame(123L); yield()
            assertEquals(123L to 2f, delivered)
            assertEquals(0, clock.pendingCount)
        } finally { frames.close() }
    }

    @Test fun detachedCapturedFrameCannotSampleOrRetireAReplacementJob() = runBlocking {
        val clock = CapturedFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        var samples = 0
        val frames = AttachedFrameLoop(scope, { samples == 0 }) { _, _ -> samples++ }
        try {
            frames.attached(); yield()
            val oldBatch = clock.capture()
            frames.detached()
            frames.attached(); yield()
            assertEquals(1, clock.pendingCount)
            oldBatch(123L); yield()
            assertEquals(0, samples)
            assertEquals(1, clock.pendingCount)
            clock.frame(124L); yield()
            assertEquals(1, samples)
            assertEquals(0, clock.pendingCount)
        } finally { frames.close() }
    }

    @Test fun closeRejectsCapturedCallbacksAndReattachmentWithoutCancellingScope() = runBlocking {
        val clock = CapturedFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        var samples = 0
        val frames = AttachedFrameLoop(scope, { true }) { _, _ -> samples++ }
        frames.attached(); yield()
        val batch = clock.capture()
        frames.close()
        batch(123L); yield()
        frames.attached(); frames.schedule(); yield()
        assertEquals(0, samples)
        assertEquals(0, clock.pendingCount)
        assertTrue(scope.isActive)
    }

    @Test fun scopeCancellationRejectsAnAlreadyCapturedFrame() = runBlocking {
        val clock = CapturedFrameClock()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + clock + job)
        var samples = 0
        val frames = AttachedFrameLoop(scope, { true }) { _, _ -> samples++ }
        try {
            frames.attached(); yield()
            val batch = clock.capture()
            job.cancel(); job.join()
            batch(123L); yield()
            assertEquals(0, samples)
            assertEquals(0, clock.pendingCount)
        } finally { frames.close(); job.cancel() }
    }
}
