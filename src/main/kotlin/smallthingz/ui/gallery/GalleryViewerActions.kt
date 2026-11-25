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
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One retained, equal-slot primary action row for both photos and videos.
 * Narrow viewports scroll instead of splitting a labelled action's unbroken words.
 */
class GalleryViewerActions(context: Context, private val palette: ControlPalette) : HorizontalScrollView(context) {
    private class ActionLabel(val view: NativeChip, val label: String) {
        private var locale: Locale? = null
        private var words = emptyList<String>()

        fun minimumWidth(): Int {
            val currentLocale = view.textLocale
            if (locale != currentLocale) {
                // Cache legal line-break segments, including locale-specific punctuation
                // rules. Measurement only refreshes their advances, never tokenizes again.
                val breaks = BreakIterator.getLineInstance(currentLocale)
                breaks.setText(label)
                val segments = mutableListOf<String>()
                var start = breaks.first()
                var end = breaks.next()
                while (end != BreakIterator.DONE) {
                    var contentEnd = end
                    while (contentEnd > start && Character.isWhitespace(label[contentEnd - 1])) contentEnd--
                    if (contentEnd > start) segments += label.substring(start, contentEnd)
                    start = end
                    end = breaks.next()
                }
                words = segments
                locale = currentLocale
            }
            var advance = 0f
            for (word in words) advance = maxOf(advance, view.paint.measureText(word))
            val iconWidth = view.compoundDrawablesRelative[1]?.bounds?.width() ?: 0
            return maxOf(iconWidth, ceil(advance).toInt()) + view.compoundPaddingLeft + view.compoundPaddingRight
        }
    }

    private val labels = mutableListOf<ActionLabel>()
    private var compact = false
    private var availableWidth = 0
    val minimumRowWidth: Int get() {
        var cell = dp(48)
        if (!compact) for (action in labels) cell = maxOf(cell, action.minimumWidth())
        return labels.size * cell
    }
    private val row = object : LinearLayout(context) {
        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // HorizontalScrollView measures its child with an unbounded width; restore our
            // equal-slot bound here instead of letting natural label widths grow the row.
            val height = if (compact) MeasureSpec.makeMeasureSpec(dp(48), MeasureSpec.EXACTLY) else heightMeasureSpec
            val width = maxOf(availableWidth, minimumRowWidth)
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), height)
            // NativeChip refreshes typography during measurement. Reconcile a changed
            // font/configuration before publishing widths derived from its old paint.
            val refreshed = maxOf(availableWidth, minimumRowWidth)
            if (refreshed != width) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(refreshed, MeasureSpec.EXACTLY), height)
            }
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
            labels += ActionLabel(this, label)
            row.addView(this, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }

    /** Changes presentation without replacing the focused action or its accessibility identity. */
    fun setCompact(value: Boolean) {
        if (compact == value) return
        compact = value
        labels.forEach { action ->
            action.view.text = if (value) "" else action.label
            action.view.gravity = if (value) Gravity.CENTER else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // MATCH_PARENT text can invalidate paint without requesting a new natural
            // height. Compact also bypasses TextView measurement for its icon-only chip.
            action.view.forceLayout()
        }
        row.requestLayout()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        availableWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) minimumRowWidth
            else (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        // The child's unbounded specs do not fully describe our availableWidth/compact
        // inputs. Discard cached row dimensions before LinearLayout measures its equal
        // slots and recomputes their natural labelled height (or exact 48dp compact row).
        row.forceLayout()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
