package smallthingz.ui.components

import android.animation.ValueAnimator
import android.transition.Slide
import android.transition.Transition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/** Retained Android scenes use Reverb Main's 220ms panel timing and vertical reveal direction. */
object NativePageTransition {
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
