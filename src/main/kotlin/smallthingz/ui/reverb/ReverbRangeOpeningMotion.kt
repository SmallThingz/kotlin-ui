package smallthingz.ui.reverb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import smallthingz.ui.animation.AnimatedValue
import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.FastOutSlowInEasing
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.tween
import smallthingz.ui.components.waveform.ProgressiveWaveformData
import smallthingz.ui.components.waveform.ProgressiveWaveformFrame
import smallthingz.ui.geometry.Rect
import smallthingz.ui.runtime.MonotonicFrameClock

/** Range opening and predictive close share the same blob and chrome motion. */
class ReverbRangeOpeningMotion(
    private val frames: MonotonicFrameClock,
    private val scope: CoroutineScope,
    private val durationScale: () -> Float,
    private val render: () -> Unit,
) : AutoCloseable {
    private val opening = AnimatedValue(0f, Float.VectorConverter,
        tween<Float>(760, easing = FastOutSlowInEasing), { AnimationVector1D(0f) })
    private val progress = MutableStateFlow(0f)
    private var job: Job? = null
    private var started = false
    private var closed = false
    private var previousWaveformFrame: ProgressiveWaveformFrame? = null

    fun start() {
        if (started || closed) return
        started = true
        opening.target(1f, durationScale())
        lateinit var next: Job
        next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (!closed && opening.running) {
                    frames.withFrameNanos { time ->
                        if (!closed) {
                            opening.frame(time, durationScale())
                            progress.value = opening.value
                            render()
                        }
                    }
                }
            } finally {
                if (job === next) job = null
            }
        }
        job = next
        next.start()
    }

    /** Delay waveform sampling until the opening ceases to compete for the first frames. */
    suspend fun awaitWaveformReadiness() { progress.first { it >= .96f } }

    fun presentation(
        source: RangeMorphSourceGeometry?,
        target: Rect?,
        backProgress: Float,
        visualizerVisible: Boolean,
        snapshotPresent: Boolean,
        maximumDurationSeconds: Double,
        data: ProgressiveWaveformData,
        revealFrame: ProgressiveWaveformFrame,
        startFraction: Float,
        endFraction: Float,
    ): NativeRangeExportPresentation {
        val visual = opening.value * (1f - backProgress.coerceIn(0f, 1f))
        val handoff = RANGE_BLOB_MORPH_HANDOFF_PROGRESS
        val morph = ((visual - handoff) / (1f - handoff)).coerceIn(0f, 1f)
        val previous = previousWaveformFrame
        val frame = if (previous != null && previous.morphProgress == morph &&
            previous.startFraction == startFraction && previous.endFraction == endFraction &&
            previous.visibleCoarseFraction == revealFrame.visibleCoarseFraction &&
            previous.visibleDetailFraction == revealFrame.visibleDetailFraction &&
            previous.wobblePhaseSeconds == revealFrame.wobblePhaseSeconds) previous
        else ProgressiveWaveformFrame(morph, revealFrame.visibleCoarseFraction,
            revealFrame.visibleDetailFraction, revealFrame.wobblePhaseSeconds, startFraction, endFraction, true)
            .also { previousWaveformFrame = it }
        return NativeRangeExportPresentation(
            source, target, started, opening.value >= .98f, backProgress,
            visualizerVisible, snapshotPresent, maximumDurationSeconds,
            ((visual - .46f) / .42f).coerceIn(0f, 1f), data, frame,
        )
    }

    override fun close() {
        closed = true
        job?.cancel()
        job = null
    }
}
