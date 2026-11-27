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
     * Explicit cancellation, unrelated replacement and detach commit ordinary navigation. An
     * uncommitted predictive preview instead restores its outgoing route without delivering Back.
     * The caller owns gesture events and calls seekPreview/commitPreview or reverses show().
     */
    class Panels(
        private val container: FrameLayout,
        private val edge: Int = Gravity.BOTTOM,
        private val pager: Boolean = false,
        private val renderOffset: ((View, Float) -> Unit)? = null,
    ) : AutoCloseable {
        init { require(edge == Gravity.BOTTOM || edge == Gravity.TOP || edge == Gravity.END || edge == Gravity.RIGHT) }
        private val renderedOffsets = java.util.WeakHashMap<View, Float>()
        private val horizontal get() = edge == Gravity.END || edge == Gravity.RIGHT
        private var View.offset: Float
            get() = if (renderOffset != null) renderedOffsets[this] ?: 0f
                else if (horizontal) translationX else translationY
            set(value) {
                if (renderOffset != null) { renderedOffsets[this] = value; renderOffset.invoke(this, value) }
                else if (horizontal) translationX = value else translationY = value
            }
        private val distance: Float get() = when (edge) {
            Gravity.TOP -> -container.height.toFloat()
            Gravity.RIGHT -> container.width.toFloat()
            Gravity.END -> container.width.toFloat() * if (container.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
            else -> container.height.toFloat()
        }
        private var animator: ValueAnimator? = null
        private class Run(
            val incoming: View,
            val outgoing: View,
            val direction: Direction,
            val moving: View,
            val baseline: Float,
            val start: Float,
            val end: Float,
            val otherBaseline: Float,
            val release: (settle: Boolean) -> Unit,
        )
        private var active: Run? = null
        private var closed = false
        private var previewing = false
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

        fun show(incoming: View, outgoing: View, direction: Direction, onFinished: () -> Unit = {}) =
            prepare(incoming, outgoing, direction, preview = false, onFinished)

        /** A predictive reverse owns the same retained pair; only commit delivers onFinished. */
        fun preview(incoming: View, outgoing: View, direction: Direction, onFinished: () -> Unit = {}): Boolean {
            prepare(incoming, outgoing, direction, preview = true, onFinished)
            return active != null && previewing
        }

        fun seekPreview(progress: Float) {
            val run = active ?: return
            if (!previewing || !ValueAnimator.areAnimatorsEnabled()) return
            position(run, run.start + (run.end - run.start) * progress.coerceIn(0f, 1f))
        }

        fun commitPreview() {
            if (!previewing) return
            previewing = false
            animate()
        }

        private fun prepare(incoming: View, outgoing: View, direction: Direction,
                            preview: Boolean, onFinished: () -> Unit) {
            require(incoming !== outgoing && incoming.parent === container && outgoing.parent === container)
            val previous = active
            val reverse = previous != null && previous.direction != direction &&
                previous.incoming === outgoing && previous.outgoing === incoming
            val presented = if (reverse) previous!!.moving.offset else null
            // Restore old interaction ownership without settling or delivering obsolete exit cleanup.
            if (reverse) release(settle = false) else cancel()
            require(incoming.parent === container && outgoing.parent === container)
            val moving = if (direction == Direction.FORWARD) incoming else outgoing
            val baseline = if (reverse) previous!!.baseline else moving.offset
            val other = if (moving === incoming) outgoing else incoming
            val otherBaseline = if (reverse) previous!!.otherBaseline else other.offset
            val start = presented ?: (baseline + if (direction == Direction.FORWARD) distance else 0f)
            val end = baseline + if (direction == Direction.FORWARD) 0f else distance
            incoming.visibility = View.VISIBLE
            outgoing.visibility = View.GONE
            if (closed || !container.isLaidOut || container.height <= 0 || !container.isAttachedToWindow ||
                !container.isShown || container.windowVisibility != View.VISIBLE ||
                (!preview && !ValueAnimator.areAnimatorsEnabled())) {
                moving.offset = baseline
                if (pager) other.offset = otherBaseline
                if (!preview) onFinished() else {
                    incoming.visibility = View.GONE
                    outgoing.visibility = View.VISIBLE
                }
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
            previewing = preview
            active = Run(incoming, outgoing, direction, moving, baseline, start, end, otherBaseline) { settle ->
                val cancelledPreview = previewing
                previewing = false
                if (settle) {
                    moving.offset = baseline
                    if (pager) other.offset = otherBaseline
                    outgoing.visibility = if (cancelledPreview) View.VISIBLE else View.GONE
                    incoming.visibility = if (cancelledPreview) View.GONE else View.VISIBLE
                }
                outgoing.importantForAccessibility = oldAccessibility
                if (oldFocusability != null) (outgoing as ViewGroup).descendantFocusability = oldFocusability
                container.removeView(shield)
                if (settle) {
                    container.bringChildToFront(if (cancelledPreview) outgoing else incoming)
                    if (!cancelledPreview) onFinished()
                }
            }
            position(checkNotNull(active), start)
            if (!preview) animate()
        }

        private fun position(run: Run, value: Float) {
            run.moving.offset = value
            if (pager) {
                val other = if (run.moving === run.incoming) run.outgoing else run.incoming
                other.offset = run.otherBaseline + value - run.baseline - distance
            }
        }

        private fun animate() {
            val run = active ?: return
            if (!ValueAnimator.areAnimatorsEnabled() || !container.isShown ||
                container.windowVisibility != View.VISIBLE) { cancel(); return }
            val motion = ValueAnimator.ofFloat(run.moving.offset, run.end)
            animator = motion
            motion.duration = DURATION_MILLIS
            motion.interpolator = panelInterpolator()
            motion.addUpdateListener {
                if (animator !== motion) return@addUpdateListener
                if (!ValueAnimator.areAnimatorsEnabled() || !container.isShown ||
                    container.windowVisibility != View.VISIBLE) { cancel(); return@addUpdateListener }
                position(run, it.animatedValue as Float)
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

    /** A replaced complete child enters from below; reverse only lowers the outgoing child. */
    fun beginVertical(container: ViewGroup, direction: Direction) =
        beginScene(container, Slide(Gravity.BOTTOM).apply {
            mode = if (direction == Direction.FORWARD) Visibility.MODE_IN else Visibility.MODE_OUT
            configureScene(this)
        })

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
