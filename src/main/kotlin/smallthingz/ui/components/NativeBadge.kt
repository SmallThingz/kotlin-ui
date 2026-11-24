package smallthingz.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import smallthingz.ui.controls.ControlPalette

/** Compact, noninteractive metadata; the containing control owns its touch target and semantics. */
data class NativeBadgeStyle(
    val surface: NativeSurfaceStyle? = null,
    val contentColor: Int? = null,
    val textStyle: NativeTextStyle = NativeTextStyle(11f, weight = 500),
    val iconSizeDp: Float = 14f,
    val gapDp: Float = 3f,
    val horizontalPaddingDp: Float = 4f,
    val verticalPaddingDp: Float = 2f,
    val minSizeDp: Float = 0f,
    val tintIcon: Boolean = true,
    val singleLine: Boolean = false,
) {
    init {
        require(listOf(iconSizeDp, gapDp, horizontalPaddingDp, verticalPaddingDp, minSizeDp)
            .all { it.isFinite() && it >= 0f })
    }
}

/**
 * A retained icon and optional label inside the shared rounded surface.
 *
 * Null surface/ink tokens use the current palette's raised/content colors. Explicit tokens let
 * a photo overlay retain contrast independently of its underlying image. Labels use natural font
 * height and wrap under a bounded width unless single-line ellipsis is requested. The caller
 * owns visibility and includes this metadata in its containing control's accessible description.
 */
class NativeBadge(
    context: Context,
    palette: ControlPalette,
    style: NativeBadgeStyle = NativeBadgeStyle(),
) : NativeSurface(context, surfaceStyle(palette, style)) {
    private var componentPalette = palette
    private var componentStyle = style
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
    }
    private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
    private val label = NativeText(context, palette, style.textStyle)

    init {
        isClickable = false
        isLongClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        icon.visibility = GONE
        label.visibility = GONE
        row.addView(icon, LinearLayout.LayoutParams(0, 0))
        row.addView(label, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        applyGeometry()
        applyInk()
    }

    /** Retains both children and the supplied drawable across repeated binding. */
    fun setContent(drawable: Drawable?, text: CharSequence? = null) {
        if (icon.drawable !== drawable) icon.setImageDrawable(drawable)
        val hasIcon = drawable != null
        val hasText = !text.isNullOrEmpty()
        val value = text ?: ""
        icon.visibility = if (hasIcon) VISIBLE else GONE
        if (label.text != value) label.text = value
        label.visibility = if (hasText) VISIBLE else GONE
        updateGap(hasIcon && hasText)
    }

    fun applyPalette(palette: ControlPalette) {
        if (componentPalette == palette) return
        componentPalette = palette
        label.applyPalette(palette)
        super.applyStyle(surfaceStyle(palette, componentStyle))
        applyInk()
    }

    fun applyStyle(style: NativeBadgeStyle) {
        if (componentStyle == style) return
        componentStyle = style
        label.applyStyle(style.textStyle)
        super.applyStyle(surfaceStyle(componentPalette, style))
        applyGeometry()
        applyInk()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyGeometry()
    }

    private fun applyGeometry() {
        val style = componentStyle
        label.setSingleLine(style.singleLine)
        label.ellipsize = if (style.singleLine) TextUtils.TruncateAt.END else null
        val horizontal = context.componentDp(style.horizontalPaddingDp)
        val vertical = context.componentDp(style.verticalPaddingDp)
        setPaddingRelative(horizontal, vertical, horizontal, vertical)
        val minimum = context.componentDp(style.minSizeDp)
        minimumWidth = minimum
        minimumHeight = minimum
        val size = context.componentDp(style.iconSizeDp)
        val params = icon.layoutParams as LinearLayout.LayoutParams
        if (params.width != size || params.height != size) {
            params.width = size
            params.height = size
            icon.layoutParams = params
        }
        updateGap(icon.visibility != GONE && label.visibility != GONE)
    }

    private fun updateGap(hasBoth: Boolean) {
        val gap = if (hasBoth) context.componentDp(componentStyle.gapDp) else 0
        val params = icon.layoutParams as LinearLayout.LayoutParams
        if (params.marginEnd != gap) {
            params.marginEnd = gap
            icon.layoutParams = params
        }
    }

    private fun applyInk() {
        val ink = componentStyle.contentColor ?: componentPalette.content
        label.animateTextColor(ink)
        icon.imageTintList = if (componentStyle.tintIcon) ColorStateList.valueOf(ink) else null
    }

    private companion object {
        fun surfaceStyle(palette: ControlPalette, style: NativeBadgeStyle): NativeSurfaceStyle =
            style.surface ?: NativeSurfaceStyle(palette.raised, cornerRadiusDp = 6f)
    }
}
