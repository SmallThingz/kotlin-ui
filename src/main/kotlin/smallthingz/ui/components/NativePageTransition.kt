package smallthingz.ui.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.transition.Slide
import android.transition.Transition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout

/** Retained Android scenes use Reverb Main's 220ms panel timing and vertical reveal direction. */
object NativePageTransition {
    /** A child panel enters over its stationary predecessor; Back lowers that same foreground. */
    enum class Direction { FORWARD, BACK }

    /**
     * Retained, opaque route roots in a dedicated FrameLayout. The caller binds the destination
     * before show(), and removes transient outgoing roots in onFinished. No pixels are captured
     * and neither root's alpha is changed. Use a full-viewport background on each route.
     *
     * Reverb Main's Library is the model: (1-progress)*height above stationary Home, tween(220).
     * Cancellation commits the latest destination, including rapid navigation and host detach.
     * This is ordinary navigation; predictive gesture ownership belongs to the caller.
     */
    class Panels(private val container: FrameLayout) : AutoCloseable {
        private var animator: ValueAnimator? = null
        private var finish: (() -> Unit)? = null
        private var closed = false
        private var leaving: View? = null
        val isRunning: Boolean get() = finish != null
        fun isOutgoing(view: View): Boolean = leaving === view
        private val attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = cancel()
        }

        init { container.addOnAttachStateChangeListener(attach) }

        fun show(incoming: View, outgoing: View, direction: Direction, onFinished: () -> Unit = {}) {
            cancel()
            require(incoming !== outgoing && incoming.parent === container && outgoing.parent === container)
            incoming.visibility = View.VISIBLE
            outgoing.visibility = View.GONE
            if (closed || !container.isLaidOut || container.height <= 0 || !container.isAttachedToWindow ||
                !container.isShown || container.windowVisibility != View.VISIBLE ||
                !ValueAnimator.areAnimatorsEnabled()) {
                onFinished()
                return
            }
            val moving = if (direction == Direction.FORWARD) incoming else outgoing
            val start = if (direction == Direction.FORWARD) container.height.toFloat() else 0f
            val end = if (direction == Direction.FORWARD) 0f else container.height.toFloat()
            val oldTranslation = moving.translationY
            val oldAccessibility = outgoing.importantForAccessibility
            val oldFocusability = (outgoing as? ViewGroup)?.descendantFocusability
            outgoing.clearFocus()
            outgoing.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            (outgoing as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            outgoing.visibility = View.VISIBLE
            leaving = outgoing
            container.bringChildToFront(moving)
            // The uncovered predecessor remains visual only until the destination settles.
            val shield = View(container.context).apply {
                isClickable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            container.addView(shield, FrameLayout.LayoutParams(-1, -1))
            moving.translationY = oldTranslation + start
            finish = {
                moving.translationY = oldTranslation
                outgoing.visibility = View.GONE
                outgoing.importantForAccessibility = oldAccessibility
                if (oldFocusability != null) (outgoing as ViewGroup).descendantFocusability = oldFocusability
                container.removeView(shield)
                container.bringChildToFront(incoming)
                leaving = null
                onFinished()
            }
            val motion = ValueAnimator.ofFloat(start, end)
            animator = motion
            motion.duration = 220L
            motion.interpolator = PathInterpolator(.4f, 0f, .2f, 1f)
            motion.addUpdateListener {
                if (animator !== motion) return@addUpdateListener
                if (!ValueAnimator.areAnimatorsEnabled() || !container.isShown ||
                    container.windowVisibility != View.VISIBLE) { cancel(); return@addUpdateListener }
                moving.translationY = oldTranslation + (it.animatedValue as Float)
            }
            motion.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) cancel()
                }
            })
            motion.start()
        }

        /** Retire callbacks before cancellation so an older route cannot settle a newer one. */
        fun cancel() {
            val previous = animator
            animator = null
            previous?.cancel()
            val complete = finish
            finish = null
            complete?.invoke()
        }

        override fun close() {
            if (closed) return
            closed = true
            cancel()
            container.removeOnAttachStateChangeListener(attach)
        }
    }

    // Compatibility: existing consumers retain their original same-edge Android scene policy.
    fun begin(container: ViewGroup, edge: Int = Gravity.BOTTOM) {
        if (!container.isLaidOut || !container.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) return
        TransitionManager.endTransitions(container)
        TransitionManager.beginDelayedTransition(container, Slide(edge).apply {
            duration = 220L
            interpolator = PathInterpolator(.4f, 0f, .2f, 1f)
            setMatchOrder(Transition.MATCH_INSTANCE)
        })
    }
}
