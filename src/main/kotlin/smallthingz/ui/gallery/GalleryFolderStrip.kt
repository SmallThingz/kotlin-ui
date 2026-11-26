package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import smallthingz.ui.components.NativeSurface
import smallthingz.ui.components.NativeSurfaceStyle
import smallthingz.ui.components.NativeText
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

data class GalleryFolderEntry(val label: String, val onClick: () -> Unit)

/** Natural-width folder targets, capped by this viewport rather than the screen or scroll content. */
class GalleryFolderStrip(
    context: Context,
    palette: ControlPalette,
    entries: List<GalleryFolderEntry>,
) : HorizontalScrollView(context) {
    private var chipWidthLimit: Int? = null
    private val chips = entries.map { FolderChip(context, palette, it) }
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        chips.forEach { addView(it, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)) }
    }

    init {
        isHorizontalScrollBarEnabled = false
        addView(row, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        updateGaps()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) null else {
            val usable = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            val reserve = if (chips.size > 1) dp(8) + dp(24) else 0
            val preferred = if (chips.size > 1)
                minOf(usable - reserve, (usable * .6f).roundToInt(), dp(240)) else usable
            // Below 48dp the native viewport may clip/scroll a target; never compress the target.
            maxOf(dp(48), preferred)
        }
        if (chipWidthLimit != limit) {
            chipWidthLimit = limit
            // The parent's specs can change while the scroller still passes UNSPECIFIED to
            // its row. Invalidate only those cached measurements; do not schedule another pass.
            chips.forEach { it.forceLayout() }
            row.forceLayout()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val leftPadding = paddingLeft
        val rightPadding = paddingRight
        if (!clipToPadding || (leftPadding == 0 && rightPadding == 0)) {
            return super.computeScrollDeltaToGetChildRectOnScreen(rect)
        }
        val viewportWidth = width - leftPadding - rightPadding
        if (viewportWidth <= 0) return 0

        val scroll = scrollX
        val viewportLeft = scroll + leftPadding
        val leftDelta = rect.left - viewportLeft
        val rightDelta = rect.right - (viewportLeft + viewportWidth)
        // Move only as far as the nearer alignment edge, even for a chip wider than the viewport.
        val delta = when {
            leftDelta > 0 && rightDelta > 0 -> minOf(leftDelta, rightDelta)
            leftDelta < 0 && rightDelta < 0 -> maxOf(leftDelta, rightDelta)
            else -> return 0
        }
        val content = getChildAt(0) ?: return 0
        val maxScroll = (content.width - viewportWidth).coerceAtLeast(0)
        return (scroll + delta).coerceIn(0, maxScroll) - scroll
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateGaps()
    }

    private fun updateGaps() {
        chips.forEachIndexed { index, chip ->
            val params = chip.layoutParams as LinearLayout.LayoutParams
            val gap = if (index < chips.lastIndex) dp(8) else 0
            if (params.marginEnd != gap) {
                params.marginEnd = gap
                chip.layoutParams = params
            }
        }
    }

    private inner class FolderChip(context: Context, palette: ControlPalette, entry: GalleryFolderEntry) :
        NativeSurface(context, NativeSurfaceStyle(palette.surface, cornerRadiusDp = 12f,
            outlineColor = palette.outline, outlineWidthDp = 1f, focusOutlineColor = palette.accent)) {
        init {
            setSurfacePreset(palette.surfacePreset)
            isFocusable = true
            isClickable = true
            contentDescription = entry.label
            tooltipText = entry.label
            updateSpacing()
            addView(NativeText(context, palette, GalleryDesign.textStyle(NativeTypographyRole.BODY_MEDIUM)).apply {
                text = entry.label
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            setOnClickListener { if (isEnabled && isShown) entry.onClick() }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = chipWidthLimit
            val width = if (cap == null) widthMeasureSpec else {
                val mode = MeasureSpec.getMode(widthMeasureSpec)
                val size = if (mode == MeasureSpec.UNSPECIFIED) cap else minOf(cap, MeasureSpec.getSize(widthMeasureSpec))
                MeasureSpec.makeMeasureSpec(size, if (mode == MeasureSpec.EXACTLY) mode else MeasureSpec.AT_MOST)
            }
            super.onMeasure(width, heightMeasureSpec)
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            updateSpacing()
        }

        private fun updateSpacing() {
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
