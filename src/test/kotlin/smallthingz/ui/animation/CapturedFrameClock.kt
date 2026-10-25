package smallthingz.ui.animation

import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.suspendCancellableCoroutine

/** Models the real dispatcher's captured callback batch, including callbacks cancelled after dequeue. */
internal class CapturedFrameClock : MonotonicFrameClock {
    private val pending = mutableListOf<(Long) -> Unit>()
    val pendingCount get() = pending.size

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
        suspendCancellableCoroutine { continuation ->
            val request: (Long) -> Unit = { time ->
                continuation.resumeWith(runCatching { onFrame(time) })
            }
            pending.add(request)
            continuation.invokeOnCancellation { pending.remove(request) }
        }

    fun capture(): (Long) -> Unit {
        val batch = pending.toList()
        pending.clear()
        return { time -> batch.forEach { it(time) } }
    }

    fun frame(time: Long) = capture()(time)
}
