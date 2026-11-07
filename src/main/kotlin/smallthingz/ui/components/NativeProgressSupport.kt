// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components

import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar

/** Shared ownership of frames; retained progress controls stop when their actual viewport is hidden. */
internal class NativeProgressAnimationOwner(
    private val view: ProgressBar,
    private val indicator: NativeAnimatedProgressDrawable,
) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private var attached = false
    private var aggregatedVisible = true
    private val visibleRect = Rect()
    private var observedTree: ViewTreeObserver? = null

    init {
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) onViewAttachedToWindow(view)
    }

    override fun onViewAttachedToWindow(view: View) {
        attached = true
        val tree = view.viewTreeObserver
        if (tree.isAlive) {
            tree.addOnPreDrawListener(this)
            observedTree = tree
        }
        update()
    }

    override fun onViewDetachedFromWindow(view: View) {
        attached = false
        indicator.allowAnimation(false)
        observedTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observedTree = null
    }

    fun onVisibilityAggregated(isVisible: Boolean) {
        aggregatedVisible = isVisible
        update()
    }

    override fun onPreDraw(): Boolean {
        update()
        return true
    }

    fun update() {
        val visible = attached && aggregatedVisible && view.isIndeterminate &&
            view.indeterminateDrawable === indicator && view.windowVisibility == View.VISIBLE &&
            view.isShown && hasVisibleAlpha() && view.getGlobalVisibleRect(visibleRect)
        indicator.allowAnimation(visible && ValueAnimator.areAnimatorsEnabled())
    }

    private fun hasVisibleAlpha(): Boolean {
        var ancestor: View? = view
        while (ancestor != null) {
            if (!(ancestor.alpha > 0f)) return false
            ancestor = ancestor.parent as? View
        }
        return true
    }
}

/** Platform duration scaling with phase retained through visibility and palette changes. */
internal abstract class NativeAnimatedProgressDrawable(
    private val cycleMillis: Long,
) : Drawable(), Animatable {
    protected var animationTimeMillis = 0f
        private set
    private var animationAllowed = false
    private var animator: ValueAnimator? = null

    override fun start() {
        if (!animationAllowed || !isVisible || !ValueAnimator.areAnimatorsEnabled() || isRunning()) return
        val driver = animator ?: ValueAnimator.ofFloat(0f, cycleMillis.toFloat()).apply {
            duration = cycleMillis
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!animationAllowed || !isVisible || !ValueAnimator.areAnimatorsEnabled()) {
                    stop()
                } else {
                    animationTimeMillis = it.animatedValue as Float
                    invalidateSelf()
                }
            }
        }.also { animator = it }
        val retainedPhase = animationTimeMillis.toLong()
        driver.start()
        if (retainedPhase > 0L) driver.currentPlayTime = retainedPhase
    }

    fun allowAnimation(allowed: Boolean) {
        animationAllowed = allowed
        if (allowed) start() else stop()
    }

    override fun stop() {
        animator?.takeIf { it.isStarted }?.cancel()
    }

    override fun isRunning(): Boolean = animator?.isStarted == true

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) start() else stop()
        return changed
    }
}
