package smallthingz.ui.components

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * An intrinsic action floating over scroll content inside an already inset FrameLayout.
 * Keeps the normal end/bottom placement and bounds wrapping between both side margins.
 * This does not alter the action's surface, input, accessibility or lifetime ownership.
 */
data class NativeFloatingActionRecipe(
    val edgeMarginDp: Int = 20,
    val minimumScrollClearanceDp: Int = 96,
    val contentGapDp: Int = NativeScreenSpacing.CONTROL_GAP_DP,
) {
    init { require(edgeMarginDp >= 0 && minimumScrollClearanceDp >= 0 && contentGapDp >= 0) }

    fun layoutParams(context: Context) = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM or Gravity.END,
    ).apply {
        // FrameLayout bounds WRAP_CONTENT by the padded host minus BOTH margins.
        marginStart = dp(context, edgeMarginDp)
        marginEnd = dp(context, edgeMarginDp)
        bottomMargin = dp(context, edgeMarginDp)
    }

    /**
     * Own the scroll child's bottom padding while this visible floating action is presented.
     * A layout change updates clearance from the actual target height (including wrapped text),
     * then Android remeasures the scroll child. The normal 96dp scroll tail remains a floor.
     * Close before retiring the host; this only removes the listener/restores padding, never
     * closes or reparents the action. The caller keeps its existing button owner and callbacks.
     */
    fun bindScrollClearance(action: View, scrollContent: View): AutoCloseable {
        val previousBottom = scrollContent.paddingBottom
        var appliedBottom = previousBottom
        var closed = false
        fun applyBottom(bottom: Int) {
            if (scrollContent.paddingBottom != bottom) {
                scrollContent.setPadding(scrollContent.paddingLeft, scrollContent.paddingTop,
                    scrollContent.paddingRight, bottom)
            }
            appliedBottom = bottom
        }
        fun update() {
            if (closed) return
            val height = if (action.isLaidOut) action.height else action.measuredHeight
            applyBottom(max(previousBottom, max(dp(action.context, minimumScrollClearanceDp),
                height + dp(action.context, edgeMarginDp) + dp(action.context, contentGapDp))))
        }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        update()
        action.addOnLayoutChangeListener(listener)
        return AutoCloseable {
            if (!closed) {
                closed = true
                action.removeOnLayoutChangeListener(listener)
                // A replacement padding owner may have already published its own bottom inset.
                if (scrollContent.paddingBottom == appliedBottom) applyBottom(previousBottom)
            }
        }
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    companion object {
        val Default = NativeFloatingActionRecipe()
    }
}
