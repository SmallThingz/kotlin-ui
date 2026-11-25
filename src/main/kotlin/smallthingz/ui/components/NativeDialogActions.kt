package smallthingz.ui.components

import android.view.Gravity
import android.widget.LinearLayout
import kotlin.math.roundToInt

/** Shared dialog actions keep full label padding; narrow windows stack instead of squeezing labels. */
class NativeDialogActionLayout(
    private val cancel: NativeButtonTouchTarget,
    private val confirm: NativeButtonTouchTarget,
) : LinearLayout(cancel.context) {
    init {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        addView(cancel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(confirm, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
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

/** Same footprint for short actions; long or translated labels retain their intrinsic width. */
fun NativeButtonRecipe.forDialogAction(): NativeButtonRecipe = copy(surface = surface.copy(
    horizontalPaddingDp = 20, minimumWidthDp = 128, minimumHeightDp = 48,
    iconSizeDp = 20,
))
