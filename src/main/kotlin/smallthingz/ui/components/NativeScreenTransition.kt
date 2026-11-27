package smallthingz.ui.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Retained, snapshot-free route entry. Call after binding the new route's content/visibility.
 * First bind and equal keys do not animate. This owner exclusively controls target alpha and
 * translationY; cancellation, hidden windows and detach restore their settled values.
 * Ordinary Android animator duration scaling applies, including disabled animations.
 */
open class NativeScreenTransition(private val target: View) : AutoCloseable {
    private var route: Any? = null
    private var initialized = false
    private var closed = false
    private var animator: ValueAnimator? = null
    private var pending: Runnable? = null
    val isRunning: Boolean get() = pending != null || animator != null
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = cancel()
    }

    init { target.addOnAttachStateChangeListener(attach) }

    fun transitionTo(routeKey: Any) {
        if (closed || initialized && route == routeKey) return
        val first = !initialized
        initialized = true
        route = routeKey
        cancel()
        if (first || !canAnimate()) return
        // Posting lets route visibility/layout settle without retaining the old route's pixels.
        val start = object : Runnable {
            override fun run() {
                // A callback already dequeued for this frame can outlive removeCallbacks().
                if (pending !== this) return
                pending = null
                if (!canAnimate()) { settle(); return }
                val motion = ValueAnimator.ofFloat(0f, 1f)
                animator = motion
                target.alpha = 0f
                target.translationY = 8f * target.resources.displayMetrics.density
                motion.duration = 180L
                motion.interpolator = DecelerateInterpolator()
                motion.addUpdateListener {
                    if (animator !== motion) return@addUpdateListener
                    if (!canAnimate()) { cancel(); return@addUpdateListener }
                    val progress = it.animatedValue as Float
                    target.alpha = progress
                    target.translationY = (1f - progress) * 8f * target.resources.displayMetrics.density
                }
                motion.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (animator === animation) { animator = null; settle() }
                    }
                })
                motion.start()
            }
        }
        pending = start
        target.postOnAnimation(start)
    }

    private fun canAnimate() = !closed && target.isAttachedToWindow && target.isShown &&
        target.windowVisibility == View.VISIBLE && ValueAnimator.areAnimatorsEnabled()

    fun cancel() {
        pending?.let(target::removeCallbacks)
        pending = null
        val active = animator
        animator = null
        active?.cancel()
        settle()
    }

    private fun settle() { target.alpha = 1f; target.translationY = 0f }

    override fun close() {
        if (closed) return
        closed = true
        cancel()
        target.removeOnAttachStateChangeListener(attach)
    }
}
