package smallthingz.ui.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.transition.Slide
import android.transition.Transition
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.transition.Visibility
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout

/** Retained Android scenes use Reverb Main's 220ms panel timing and vertical reveal direction. */
object NativePageTransition {
    private const val DURATION_MILLIS = 220L
    private fun panelInterpolator() = PathInterpolator(.4f, 0f, .2f, 1f)

    /** A child panel enters over its stationary predecessor; Back lowers that same foreground. */
    enum class Direction { FORWARD, BACK }

    /**
     * Retained, opaque route roots in a dedicated FrameLayout. The caller binds the destination
     * before show(), and removes transient outgoing roots in onFinished. No pixels are captured
     * and neither root's alpha is changed. Use a full-viewport background on each route.
     *
     * Reverb Main's Library is the model: (1-progress)*height above stationary Home, tween(220).
     * Reversing the same live pair rebases the foreground's presented translation. Its superseded
     * onFinished callback is retired: that callback must not remove the now-incoming root.
     * Explicit cancellation, unrelated replacement and detach commit the latest destination.
     * This is ordinary navigation; predictive gesture ownership belongs to the caller.
     */
    class Panels(private val container: FrameLayout) : AutoCloseable {
        private var animator: ValueAnimator? = null
        private class Run(
            val incoming: View,
            val outgoing: View,
            val direction: Direction,
            val moving: View,
            val baseline: Float,
            val release: (settle: Boolean) -> Unit,
        )
        private var active: Run? = null
        private var closed = false
        val isRunning: Boolean get() = active != null
        fun isOutgoing(view: View): Boolean = active?.outgoing === view

        /** A caller may reuse this still-owned root after verifying its destination's route key. */
        fun reversalTarget(outgoing: View, direction: Direction): View? = active?.takeIf {
            it.incoming === outgoing && it.direction != direction && it.outgoing.parent === container
        }?.outgoing
        private val attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = cancel()
        }

        init { container.addOnAttachStateChangeListener(attach) }

        fun show(incoming: View, outgoing: View, direction: Direction, onFinished: () -> Unit = {}) {
            require(incoming !== outgoing && incoming.parent === container && outgoing.parent === container)
            val previous = active
            val reverse = previous != null && previous.direction != direction &&
                previous.incoming === outgoing && previous.outgoing === incoming
            val presented = if (reverse) previous!!.moving.translationY else null
            // Restore old interaction ownership without settling or delivering obsolete exit cleanup.
            if (reverse) release(settle = false) else cancel()
            require(incoming.parent === container && outgoing.parent === container)
            val moving = if (direction == Direction.FORWARD) incoming else outgoing
            val baseline = if (reverse) previous!!.baseline else moving.translationY
            val start = presented ?: (baseline + if (direction == Direction.FORWARD) container.height else 0)
            val end = baseline + if (direction == Direction.FORWARD) 0 else container.height
            incoming.visibility = View.VISIBLE
            outgoing.visibility = View.GONE
            if (closed || !container.isLaidOut || container.height <= 0 || !container.isAttachedToWindow ||
                !container.isShown || container.windowVisibility != View.VISIBLE ||
                !ValueAnimator.areAnimatorsEnabled()) {
                moving.translationY = baseline
                onFinished()
                return
            }
            val oldAccessibility = outgoing.importantForAccessibility
            val oldFocusability = (outgoing as? ViewGroup)?.descendantFocusability
            outgoing.clearFocus()
            outgoing.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            (outgoing as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            outgoing.visibility = View.VISIBLE
            container.bringChildToFront(moving)
            // The uncovered predecessor remains visual only until the destination settles.
            val shield = View(container.context).apply {
                isClickable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            container.addView(shield, FrameLayout.LayoutParams(-1, -1))
            moving.translationY = start
            active = Run(incoming, outgoing, direction, moving, baseline) { settle ->
                if (settle) {
                    moving.translationY = baseline
                    outgoing.visibility = View.GONE
                }
                outgoing.importantForAccessibility = oldAccessibility
                if (oldFocusability != null) (outgoing as ViewGroup).descendantFocusability = oldFocusability
                container.removeView(shield)
                if (settle) {
                    container.bringChildToFront(incoming)
                    onFinished()
                }
            }
            val motion = ValueAnimator.ofFloat(start, end)
            animator = motion
            motion.duration = DURATION_MILLIS
            motion.interpolator = panelInterpolator()
            motion.addUpdateListener {
                if (animator !== motion) return@addUpdateListener
                if (!ValueAnimator.areAnimatorsEnabled() || !container.isShown ||
                    container.windowVisibility != View.VISIBLE) { cancel(); return@addUpdateListener }
                moving.translationY = it.animatedValue as Float
            }
            motion.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) cancel()
                }
            })
            motion.start()
        }

        /** Retire callbacks before cancellation so an older route cannot settle a newer one. */
        fun cancel() = release(settle = true)

        private fun release(settle: Boolean) {
            val previous = animator
            val run = active
            animator = null
            active = null
            previous?.cancel()
            run?.release?.invoke(settle)
        }

        override fun close() {
            if (closed) return
            closed = true
            cancel()
            container.removeOnAttachStateChangeListener(attach)
        }
    }

    // Compatibility: existing consumers retain their original same-edge Android scene policy.
    fun begin(container: ViewGroup, edge: Int = Gravity.BOTTOM) =
        beginScene(container, Slide(edge).apply { configureScene(this) })

    /** Advancing pages enter at END while their predecessors leave at START; Back reverses both. */
    fun beginHorizontal(container: ViewGroup, direction: Direction) =
        beginScene(container, horizontalSceneTransition(direction))

    internal fun horizontalSceneTransition(direction: Direction) = TransitionSet().apply {
        val forward = direction == Direction.FORWARD
        ordering = TransitionSet.ORDERING_TOGETHER
        addTransition(Slide(if (forward) Gravity.END else Gravity.START).apply { mode = Visibility.MODE_IN })
        addTransition(Slide(if (forward) Gravity.START else Gravity.END).apply { mode = Visibility.MODE_OUT })
        configureScene(this)
    }

    private fun configureScene(transition: Transition) {
        transition.duration = DURATION_MILLIS
        transition.interpolator = panelInterpolator()
        transition.setMatchOrder(Transition.MATCH_INSTANCE)
    }

    private fun beginScene(container: ViewGroup, transition: Transition) {
        if (!container.isLaidOut || !container.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) return
        TransitionManager.endTransitions(container)
        TransitionManager.beginDelayedTransition(container, transition)
    }
}
