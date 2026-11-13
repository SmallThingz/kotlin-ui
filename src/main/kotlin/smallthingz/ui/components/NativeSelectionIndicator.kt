package smallthingz.ui.components

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Choreographer
import android.view.View
import smallthingz.ui.animation.ColorTransition
import smallthingz.ui.animation.core.FastOutSlowInEasing
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import kotlin.math.min

/** One fill follows the actual selected cell bounds without changing measurement or semantic state. */
internal class NativeSelectionIndicator(
    private val host: View,
    initialColor: Int,
) : Choreographer.FrameCallback, View.OnAttachStateChangeListener, AutoCloseable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val displayed = RectF()
    private val start = RectF()
    private val target = RectF()
    private val colors = ViewColorTransition(host, intArrayOf(initialColor), { host.invalidate() })
    private val choreographer = Choreographer.getInstance()
    private var initialized = false
    private var running = false
    private var posted = false
    private var closed = false
    private var scaleReadDenied = false
    private var elapsed = 0.0
    private var lastFrame = 0L

    internal val paintedBounds: RectF get() = displayed
    internal val paintedColor: Int get() = colors.color(0)
    internal val isVisible: Boolean get() = initialized && !closed

    init { host.addOnAttachStateChangeListener(this) }

    fun bindColor(color: Int) { colors.retarget(color) }

    fun retarget(left: Float, top: Float, right: Float, bottom: Float) {
        if (closed) return
        val changed = !initialized || target.left != left || target.top != top || target.right != right || target.bottom != bottom
        if (changed) scaleReadDenied = false
        val scale = frameScale()
        val now = System.nanoTime()
        sample(now, scale)
        if (changed) {
            start.set(displayed)
            target.set(left, top, right, bottom)
            elapsed = 0.0
            lastFrame = now
            running = true
            if (!initialized || !host.isAttachedToWindow || !host.isShown || scale <= 0f) snap()
            initialized = true
        }
        if (!host.isAttachedToWindow || !host.isShown) snap()
        host.invalidate()
        schedule()
    }

    fun clear() {
        initialized = false
        running = false
        schedule()
        host.invalidate()
    }

    fun draw(canvas: Canvas, radius: Float) {
        if (!initialized || closed) return
        paint.color = colors.color(0)
        val corner = min(radius, min(displayed.width(), displayed.height()) / 2f)
        canvas.drawRoundRect(displayed, corner, corner, paint)
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        if (closed) return
        if (!host.isAttachedToWindow || !host.isShown) snap()
        else sample(frameTimeNanos, frameScale())
        host.invalidate()
        schedule()
    }

    private fun sample(now: Long, durationScale: Float) {
        if (!running) return
        if (durationScale <= 0f) { snap(); return }
        val scale = if (durationScale.isFinite()) durationScale.toDouble() else 1.0
        elapsed += (now - lastFrame).coerceAtLeast(0L) / scale
        lastFrame = maxOf(lastFrame, now)
        val fraction = (elapsed / ColorTransition.DURATION_NANOS).coerceIn(0.0, 1.0).toFloat()
        if (fraction >= 1f) { snap(); return }
        val eased = FastOutSlowInEasing.transform(fraction)
        displayed.set(
            start.left + (target.left - start.left) * eased,
            start.top + (target.top - start.top) * eased,
            start.right + (target.right - start.right) * eased,
            start.bottom + (target.bottom - start.bottom) * eased,
        )
    }

    private fun snap() {
        displayed.set(target)
        start.set(target)
        running = false
        elapsed = 0.0
    }

    private fun frameScale(): Float {
        if (!ValueAnimator.areAnimatorsEnabled()) return 0f
        // Optional settings access is retried only when new bounds are requested.
        if (scaleReadDenied) return 1f
        val scale = try { readCachedAnimatorDurationScale(host.context) } catch (_: SecurityException) {
            scaleReadDenied = true
            1f
        }
        return if (scale.isFinite() && scale >= 0f) scale else 1f
    }

    private fun schedule() {
        if (running && !posted && !closed) {
            posted = true
            choreographer.postFrameCallback(this)
        } else if (!running && posted) {
            choreographer.removeFrameCallback(this)
            posted = false
        }
    }

    override fun onViewAttachedToWindow(view: View) = Unit
    override fun onViewDetachedFromWindow(view: View) {
        snap()
        schedule()
        host.invalidate()
    }

    override fun close() {
        if (closed) return
        closed = true
        snap()
        schedule()
        colors.close()
        host.removeOnAttachStateChangeListener(this)
    }
}
