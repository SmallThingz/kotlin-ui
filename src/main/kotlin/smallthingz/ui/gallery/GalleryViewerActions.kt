package smallthingz.ui.gallery

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import smallthingz.ui.components.NativeChip
import smallthingz.ui.components.NativeChoiceSemantics
import smallthingz.ui.components.NativeChoiceStyle
import smallthingz.ui.components.NativeTextRole
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/** One retained, equal-slot primary action row for both photos and videos.
 * Only viewports narrower than the combined touch targets scroll horizontally.
 */
class GalleryViewerActions(context: Context, private val palette: ControlPalette) : HorizontalScrollView(context) {
    private val labels = mutableListOf<Pair<NativeChip, String>>()
    private var compact = false
    private var availableWidth = 0
    val minimumRowWidth: Int get() = labels.size * dp(48)
    private val row = object : LinearLayout(context) {
        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // HorizontalScrollView measures its child with an unbounded width; restore our
            // equal-slot bound here instead of letting natural label widths grow the row.
            super.onMeasure(MeasureSpec.makeMeasureSpec(maxOf(availableWidth, minimumRowWidth), MeasureSpec.EXACTLY),
                if (compact) MeasureSpec.makeMeasureSpec(dp(48), MeasureSpec.EXACTLY) else heightMeasureSpec)
        }
    }

    init {
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun addAction(icon: Drawable?, label: String, selected: Boolean, enabled: Boolean,
        onActivate: () -> Boolean): NativeChip {
        val toolbar = GalleryDesign.appearance.toolbars.action
        val text = GalleryDesign.textStyle(NativeTypographyRole.LABEL_MEDIUM)
        return NativeChip(context, palette, NativeChoiceStyle(
            radiusDp = toolbar.radiusDp, minHeightDp = 56f,
            horizontalPaddingDp = 4f, verticalPaddingDp = 5f,
            outlined = false, iconSizeDp = toolbar.glyphDp, iconGapDp = 3f, iconAbove = true,
            textStyle = text, selectedTextStyle = text,
            normalTextRole = NativeTextRole.MUTED, selectedTextRole = NativeTextRole.CONTENT,
            normalIconRole = NativeTextRole.CONTENT, selectedIconRole = NativeTextRole.ACCENT,
        ), semantics = NativeChoiceSemantics.BUTTON).apply {
            setIcons(icon)
            bindButton(if (compact) "" else label, selected, enabled)
            gravity = if (compact) Gravity.CENTER else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            maxLines = Int.MAX_VALUE
            ellipsize = null
            contentDescription = label
            tooltipText = label
            onActivationRequest = { if (isEnabled && isShown) onActivate() else false }
            labels += this to label
            row.addView(this, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }

    /** Changes presentation without replacing the focused action or its accessibility identity. */
    fun setCompact(value: Boolean) {
        if (compact == value) return
        compact = value
        labels.forEach { (view, label) ->
            view.text = if (value) "" else label
            view.gravity = if (value) Gravity.CENTER else Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        availableWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) minimumRowWidth
            else (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
