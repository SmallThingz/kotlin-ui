package smallthingz.ui.reverb

import smallthingz.ui.animation.AnimatedValue
import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.FastOutSlowInEasing
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.tween
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.components.waveform.ProgressiveWaveformData
import smallthingz.ui.components.waveform.ProgressiveWaveformFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Released player morph and ProgressiveWaveformCanvas reveal/wobble effects. Retain this for the
 * visual row lifetime (not its changing recording revision), exactly like the three animatables.
 * Shared animation math owns no Composition. No timers poll a completed waveform.
 */
class NativeInlineWaveformMotion(
    private val frames: MonotonicFrameClock,
    private val scope: CoroutineScope,
    private val durationScale: () -> Float,
    private val publish: (ProgressiveWaveformFrame) -> Unit,
) : AutoCloseable {
    private val coarse = animation(430)
    private val detail = animation(330)
    private val morph = animation(760)
    private var loading: Boolean? = null
    private var settlingFrames = 0
    private var phase = 0.0
    private var phaseSampled = false
    private var animationTimeNanos = 0L
    private var lastFrameNanos = 0L
    private var frameTimeSampled = false
    private var presentationActive = true
    private var motionEnabled = true
    private var frameJob: Job? = null
    private var lastPublishedFrame: ProgressiveWaveformFrame? = null
    private var closed = false

    /** Pause decorative sampling while keeping the latest targets and the last visible frame. */
    fun setPresentationActive(active: Boolean) {
        if (closed || presentationActive == active) return
        presentationActive = active
        if (!active) {
            cancelFrames()
        } else if (loading != null) {
            updateMotionEnabled(scale())
            publishFrame()
            requestFrames()
        }
    }

    fun bind(data: ProgressiveWaveformData, loading: Boolean, morphStarted: Boolean) {
        if (closed) return
        val scale = scale()
        val coarseTarget = if (data.coarse.size == 0) 0f else
            (data.coarse.builtCount.toFloat() / data.coarse.size.toFloat()).coerceIn(0f, 1f)
        val detailTarget = if (data.detailAvailable && data.detail.size > 0)
            (data.detail.builtCount.toFloat() / data.detail.size.toFloat()).coerceIn(0f, 1f) else 0f
        coarse.target(coarseTarget, scale)
        detail.target(detailTarget, scale)
        morph.target(if (morphStarted) 1f else 0f, scale)
        if (this.loading != loading) {
            settlingFrames = if (this.loading == true && !loading) 14 else 0
            this.loading = loading
        }
        updateMotionEnabled(scale)
        if (!presentationActive) return
        publishFrame()
        requestFrames()
    }

    private fun active() = !closed && presentationActive && motionEnabled &&
        (loading == true || settlingFrames > 0 || coarse.running || detail.running || morph.running)

    private fun requestFrames() {
        if (frameJob?.isActive == true || !active()) return
        // The first delivered frame establishes a new clock anchor. The tweens and wobble
        // both keep their elapsed visible time across a detach and reattach.
        frameTimeSampled = false
        phaseSampled = false
        val next = scope.launch(start = CoroutineStart.LAZY) {
            while (isActive && active()) {
                frames.withFrameNanos { time ->
                    // A frame callback already taken by the clock can outlive cancellation.
                    if (isActive && active()) sampleFrame(time)
                }
            }
        }
        frameJob = next
        next.start()
    }

    private fun sampleFrame(time: Long) {
        val delta = if (frameTimeSampled) (time - lastFrameNanos).coerceAtLeast(0L) else 0L
        lastFrameNanos = if (frameTimeSampled) maxOf(time, lastFrameNanos) else time
        frameTimeSampled = true
        animationTimeNanos += delta
        val scale = scale()
        motionEnabled = scale > 0f
        coarse.frame(animationTimeNanos, scale)
        detail.frame(animationTimeNanos, scale)
        morph.frame(animationTimeNanos, scale)
        if (!motionEnabled) snapTargets()
        else if (loading == true || settlingFrames > 0) {
            if (phaseSampled) phase += delta / (1_000_000_000.0 * scale)
            phaseSampled = true
            if (loading != true) settlingFrames--
        } else phaseSampled = false
        publishFrame()
    }

    private fun scale(): Float = durationScale().let { if (it.isFinite() && it >= 0f) it else 1f }

    private fun updateMotionEnabled(scale: Float) {
        motionEnabled = scale > 0f
        if (!motionEnabled) {
            snapTargets()
            cancelFrames()
        }
    }

    private fun cancelFrames() {
        val previous = frameJob
        frameJob = null
        previous?.cancel()
    }

    private fun snapTargets() {
        coarse.snap(coarse.target)
        detail.snap(detail.target)
        morph.snap(morph.target)
        settlingFrames = 0
        phaseSampled = false
    }

    private fun publishFrame() {
        val morphProgress = morph.value
        val coarseFraction = coarse.value
        val detailFraction = detail.value
        val wobblePhase = phase.toFloat()
        val previous = lastPublishedFrame
        // Match the data class's Float equality without constructing a duplicate frame.
        if (previous != null &&
            previous.morphProgress.toBits() == morphProgress.toBits() &&
            previous.visibleCoarseFraction.toBits() == coarseFraction.toBits() &&
            previous.visibleDetailFraction.toBits() == detailFraction.toBits() &&
            previous.wobblePhaseSeconds.toBits() == wobblePhase.toBits()
        ) return

        val frame = ProgressiveWaveformFrame(
            morphProgress = morphProgress,
            visibleCoarseFraction = coarseFraction,
            visibleDetailFraction = detailFraction,
            wobblePhaseSeconds = wobblePhase,
            // The owner applies the current selection independently of the animation frame.
            startFraction = 0f, endFraction = 0f, morphFromBlob = false,
        )
        lastPublishedFrame = frame
        try {
            publish(frame)
        } catch (failure: Throwable) {
            if (lastPublishedFrame === frame) lastPublishedFrame = previous
            throw failure
        }
    }

    override fun close() {
        closed = true
        cancelFrames()
        lastPublishedFrame = null
    }

    private fun animation(duration: Int) = AnimatedValue(0f, Float.VectorConverter,
        tween<Float>(duration, easing = FastOutSlowInEasing), { AnimationVector1D(0f) })
}
