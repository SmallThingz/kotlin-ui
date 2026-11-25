package smallthingz.ui.components

import android.content.res.Configuration
import android.view.Gravity
import android.widget.LinearLayout
import kotlin.math.roundToInt

/** Shared dialog actions keep full label padding; narrow windows stack instead of squeezing labels. */
class NativeDialogActionLayout(
    private val cancel: NativeButtonTouchTarget,
    private val confirm: NativeButtonTouchTarget,
) : LinearLayout(cancel.context), NativeActionFooter {
    override val isActionFooter: Boolean get() = true

    init {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        bindFooterClearance()
        addView(cancel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(confirm, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun bindFooterClearance() {
        val bottom = (NativeMergedActionLayout.ACTION_FOOTER_BOTTOM_PADDING_DP * resources.displayMetrics.density).roundToInt()
        if (paddingBottom != bottom) setPaddingRelative(paddingStart, paddingTop, paddingEnd, bottom)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bindFooterClearance()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val gap = (NativeScreenSpacing.CONTROL_GAP_DP * density).roundToInt()
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED)
            Int.MAX_VALUE else (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        fun preferred(target: NativeButtonTouchTarget) = maxOf(target.button.preferredWidth(false),
            (target.button.minimumTouchTargetDp * density).roundToInt())
        val stacked = preferred(cancel).toLong() + gap + preferred(confirm) > available
        cancel.button.compactPadding = false
        confirm.button.compactPadding = false
        orientation = if (stacked) VERTICAL else HORIZONTAL
        (confirm.layoutParams as LayoutParams).apply {
            marginStart = if (stacked) 0 else gap
            topMargin = if (stacked) gap else 0
            resolveLayoutDirection(this@NativeDialogActionLayout.layoutDirection)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** Use the shared labeled action dimensions while retaining this standalone button's paint and corners. */
fun NativeButtonRecipe.forDialogAction(): NativeButtonRecipe = copy(surface = surface.copy(
    horizontalPaddingDp = joinedActionSurface.horizontalPaddingDp,
    minimumWidthDp = joinedActionSurface.minimumWidthDp,
    minimumHeightDp = joinedActionSurface.minimumHeightDp,
    iconSizeDp = joinedActionSurface.iconSizeDp,
    iconGapDp = joinedActionSurface.iconGapDp,
))
