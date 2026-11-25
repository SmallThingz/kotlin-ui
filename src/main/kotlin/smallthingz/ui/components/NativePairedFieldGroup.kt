package smallthingz.ui.components

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An equal-weight pair of fields with a shared surface and optional supporting row. */
data class NativePairedFieldRecipe(
    val cornerRadiusDp: Float = 23f,
    val paddingDp: Float = 20f,
    val columnGapDp: Float = 24f,
    val supportingGapDp: Float = 12f,
) {
    fun createSurface(
        context: Context,
        fields: NativePairedFieldLayout,
        onPaintedSurfaceChanged: (View) -> Unit = {},
    ): NativeGroupLayout = NativeGroupLayout(context, cornerRadiusDp, onPaintedSurfaceChanged = onPaintedSurfaceChanged)
        .apply { addRow(fields, NativeGroupLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }

    fun createFields(context: Context, first: View, second: View, supporting: View?, density: Float): NativePairedFieldLayout =
        NativePairedFieldLayout(context, this, first, second, supporting, density)

    fun bindSurface(surface: NativeGroupLayout, palette: NativeColorScheme) {
        surface.setSurfacePreset(palette.surfacePreset)
        surface.setPalette(palette.field, palette.border)
    }
}

class NativePairedFieldLayout internal constructor(
    context: Context,
    private val recipe: NativePairedFieldRecipe,
    private val first: View,
    private val second: View,
    private val supporting: View?,
    density: Float,
) : ViewGroup(context) {
    var density = density
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }
    private var rowHeight = 0
    private var firstWidth = 0
    private var secondWidth = 0
    private var gap = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(first)
        addView(second)
        if (supporting != null) addView(supporting)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val padding = dp(recipe.paddingDp)
        val inner = (width - 2 * padding).coerceAtLeast(0)
        gap = min(dp(recipe.columnGapDp), inner)
        val remaining = inner - gap
        val unit = (remaining / 2f).roundToInt()
        firstWidth = unit + (remaining - unit * 2).coerceIn(-1, 1)
        secondWidth = remaining - firstWidth
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        first.measure(MeasureSpec.makeMeasureSpec(firstWidth, MeasureSpec.EXACTLY), unbounded)
        second.measure(MeasureSpec.makeMeasureSpec(secondWidth, MeasureSpec.EXACTLY), unbounded)
        rowHeight = max(first.measuredHeight, second.measuredHeight)
        val supportingHeight = if (supporting != null && supporting.visibility != GONE) {
            supporting.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), unbounded)
            dp(recipe.supportingGapDp) + supporting.measuredHeight
        } else 0
        setMeasuredDimension(width, 2 * padding + rowHeight + supportingHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val padding = dp(recipe.paddingDp)
        place(first, padding, padding)
        place(second, padding + firstWidth + gap, padding)
        if (supporting != null && supporting.visibility != GONE) {
            place(supporting, padding, padding + rowHeight + dp(recipe.supportingGapDp))
        }
    }

    private fun place(child: View, start: Int, top: Int) {
        val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
        child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
    }

    private fun dp(value: Float) = (value * density).roundToInt()
}
