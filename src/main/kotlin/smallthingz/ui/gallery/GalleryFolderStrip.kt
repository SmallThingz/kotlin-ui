package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
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
            // Below 48dp the native viewport may clip/scroll a target; never compress the target.
            maxOf(dp(48), usable - reserve)
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
