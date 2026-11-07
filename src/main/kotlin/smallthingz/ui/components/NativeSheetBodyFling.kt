package smallthingz.ui.components

import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.widget.OverScroller
import android.widget.ScrollView
import androidx.annotation.MainThread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Owns the fling accepted from the sheet's retained ScrollView, so there is only one decay.
 * Platform ScrollView does not report a residual velocity when its own fling reaches an edge.
 * The host must consume that child's pre-fling before starting this owner.
 *
 * Scroll velocities use ScrollView's convention; the boundary callback uses sheet coordinates
 * (positive moves the sheet down). This contract deliberately does not cover RecyclerView.
 */
@MainThread
internal class NativeSheetBodyFling(
    private val host: View,
    private val body: ScrollView,
    private val onBoundary: (sheetVelocity: Float) -> Unit,
    private val onFinished: () -> Unit,
    private val onUnavailable: () -> Unit = onFinished,
) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private val scroller = OverScroller(host.context)
    private var generation = 0L
    private var frameCallback: Choreographer.FrameCallback? = null
    private var framePosted = false
    private var previousY = 0
    private var direction = 0

    var isRunning: Boolean = false
        private set

    /**
     * Replaces any previous run. Unavailable/disabled views leave [isRunning] false without a
     * callback; the host must check admission. A velocity rounding to zero finishes synchronously.
     */
    fun start(scrollVelocityY: Float) {
        requireMainThread()
        require(scrollVelocityY.isFinite()) { "Fling velocity must be finite" }
        stop()
        if (!canRun() || !host.isEnabled || !body.isEnabled) return

        // OverScroller takes an Int velocity and applies abs internally. Int.MIN_VALUE cannot
        // be negated; use the representable negative counterpart of Int.MAX_VALUE instead.
        val velocity = scrollVelocityY.roundToInt().coerceAtLeast(-Int.MAX_VALUE)
        if (velocity == 0) {
            onFinished()
            return
        }
        direction = if (velocity > 0) 1 else -1
        previousY = 0
        scroller.fling(0, 0, 0, velocity, 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
        isRunning = true
        val owner = generation
        // A Choreographer callback may already have been dequeued when stop() removes it.
        // One callback per fling, reused per frame, prevents it from advancing a newer run.
        frameCallback = Choreographer.FrameCallback { nanos ->
            if (owner == generation && isRunning) doFrame(nanos)
        }
        postFrame(owner)
    }

    fun stop() {
        requireMainThread()
        generation++
        isRunning = false
        frameCallback?.let(choreographer::removeFrameCallback)
        frameCallback = null
        framePosted = false
        scroller.forceFinished(true)
    }

    override fun doFrame(frameTimeNanos: Long) {
        requireMainThread()
        framePosted = false
        val owner = generation
        if (!isRunning) return
        if (!canRun()) {
            finishUnavailable(owner)
            return
        }

        try {
            if (!scroller.computeScrollOffset()) {
                finish(owner)
                return
            }
            val nextY = scroller.currY
            val requested = nextY.toLong() - previousY.toLong()
            previousY = nextY
            if (requested != 0L) {
                val before = body.scrollY
                // Keep ScrollView.scrollBy's internal Int addition representable. Retain the
                // original delta below so even a representational boundary transfers ownership.
                val minimum = max(Int.MIN_VALUE.toLong(), Int.MIN_VALUE.toLong() - before)
                val maximum = min(Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong() - before)
                val delta = requested.coerceIn(minimum, maximum).toInt()
                body.scrollBy(0, delta)
                // Scroll listeners may dismiss the sheet or start a replacement fling.
                if (owner != generation || !isRunning) return
                if (!canRun()) {
                    finishUnavailable(owner)
                    return
                }
                val consumed = body.scrollY.toLong() - before.toLong()
                if (consumed != requested) {
                    // A delayed final frame may cross the boundary after decay has finished.
                    // OverScroller.finish() retains its last sampled velocity; that is no
                    // longer momentum available for the sheet to consume.
                    val sheetVelocity = if (scroller.isFinished) 0f else -direction * scroller.currVelocity
                    stop()
                    onBoundary(sheetVelocity)
                    return
                }
            }
            if (scroller.isFinished) finish(owner) else postFrame(owner)
        } catch (failure: Throwable) {
            // Do not retire a replacement run created by a reentrant scroll/client callback.
            if (owner == generation) stop()
            throw failure
        }
    }

    private fun finish(owner: Long) {
        if (owner != generation || !isRunning) return
        stop()
        onFinished()
    }

    /** Loss of an active view ends ownership without manufacturing a boundary velocity. */
    private fun finishUnavailable(owner: Long) {
        if (owner != generation || !isRunning) return
        stop()
        onUnavailable()
    }

    private fun postFrame(owner: Long) {
        if (owner != generation || !isRunning || framePosted) return
        val callback = frameCallback ?: return
        framePosted = true
        choreographer.postFrameCallback(callback)
    }

    private fun canRun(): Boolean =
        host.isAttachedToWindow && body.isAttachedToWindow &&
            host.windowVisibility == View.VISIBLE && body.windowVisibility == View.VISIBLE &&
            host.isShown && body.isShown && host.rootView === body.rootView

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Sheet fling must run on the main thread" }
    }
}
