package smallthingz.ui.reverb

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import smallthingz.ui.animation.CapturedFrameClock
import smallthingz.ui.components.waveform.ProgressiveWaveformData
import smallthingz.ui.components.waveform.ProgressiveWaveformFrame

class NativeInlineWaveformMotionTest {
    @Test fun proofPendingStartsIdleAndRealLoadingCompletionStillSettles() = runBlocking {
        val clock = CapturedFrameClock()
        val frames = mutableListOf<ProgressiveWaveformFrame>()
        val motion = NativeInlineWaveformMotion(clock, this, { 1f }) { frames += it }
        val pendingProof = ProgressiveWaveformData(FloatArray(4), 0, FloatArray(4), 0, detailAvailable = false)
        try {
            motion.setPresentationActive(false)
            motion.setPresentationActive(true)
            motion.bind(pendingProof, loading = false, morphStarted = false)
            yield()
            assertEquals("Waiting for proof must leave the clock idle", 0, clock.pendingCount)
            val initial = ProgressiveWaveformFrame(0f, 0f, 0f, 0f, 0f, 0f, false)
            assertEquals(listOf(initial), frames)

            motion.bind(pendingProof, loading = false, morphStarted = false)
            clock.frame(1_000_000_000L)
            yield()
            assertEquals(0, clock.pendingCount)
            assertEquals(listOf(initial), frames)

            // Keep the tween targets fixed to measure just the completion settle.
            motion.bind(pendingProof, loading = true, morphStarted = false)
            yield()
            assertEquals(1, clock.pendingCount)
            val loadFrame = 2_000_000_000L
            clock.frame(loadFrame)
            yield()
            motion.bind(pendingProof, loading = false, morphStarted = false)
            repeat(14) { index ->
                assertEquals(1, clock.pendingCount)
                clock.frame(loadFrame + (index + 1) * 16_000_000L)
                yield()
            }
            assertEquals(0, clock.pendingCount)
            assertEquals(15, frames.size)
            assertEquals(.224f, frames.last().wobblePhaseSeconds, .000001f)
        } finally { motion.close() }
    }
}
