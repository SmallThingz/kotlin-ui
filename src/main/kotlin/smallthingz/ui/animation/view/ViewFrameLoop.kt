package smallthingz.ui.animation.view

import android.view.View
import androidx.annotation.MainThread
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.MotionDurationScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One source animation's frame job, inheriting the real host's paused clock and duration scale. */
@MainThread
class ViewFrameLoop(
    private val view: View,
    private val scope: CoroutineScope,
    private val running: () -> Boolean,
    private val sample: (Long, Float) -> Unit,
) {
    private val frames = requireNotNull(scope.coroutineContext[MonotonicFrameClock]) {
        "View animations require the owning window's frame clock"
    }
    private var job: Job? = null
    private var generation = 0L
    val durationScale get() = scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f

    fun request() {
        if (job?.isActive == true || !view.isAttachedToWindow || !running()) return
        val owner = ++generation
        lateinit var next: Job
        next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (next.isActive && owner == generation && job === next &&
                    view.isAttachedToWindow && running()) {
                    frames.withFrameNanos { nanos ->
                        // A frame already taken by the dispatcher can outlive cancellation.
                        if (owner == generation && job === next && next.isActive &&
                            view.isAttachedToWindow && running()) {
                            sample(nanos, durationScale)
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

    fun cancel() {
        generation++
        val previous = job
        job = null
        previous?.cancel()
    }
}
