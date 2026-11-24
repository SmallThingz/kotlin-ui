package smallthingz.ui.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.compositeOver
import smallthingz.ui.graphics.toArgb
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.controls.ControlStyle
import smallthingz.ui.controls.StateColorSurface
import smallthingz.ui.controls.selectionForeground
import smallthingz.ui.controls.selectionIndication
import kotlin.math.max
import kotlin.math.roundToInt

data class NativeSettingsRowStyle(
    val minHeightDp: Float = 56f,
    val horizontalPaddingDp: Float = 16f,
    val verticalPaddingDp: Float = 10f,
    val iconSizeDp: Float = 24f,
    val gapDp: Float = 12f,
    val minTextWidthDp: Float = 112f,
    val titleSizeSp: Float = 16f,
    val detailSizeSp: Float = 13f,
    val valueSizeSp: Float = 14f,
    val cornerRadiusDp: Int = 0,
    val titleWeight: Int = 500,
    val titleTextStyle: NativeTextStyle? = null,
    val detailTextStyle: NativeTextStyle? = null,
    val detailGapDp: Float = 0f,
    val iconRole: NativeTextRole = NativeTextRole.MUTED,
    val startPaddingDp: Float? = null,
    val endPaddingDp: Float? = null,
    /** Keep the normal container tied to the current palette, including while disabled. */
    val containerFill: Boolean = false,
    /** Label-to-accessory spacing; the leading icon and internal accessory gap keep gapDp. */
    val trailingGapDp: Float? = null,
    /** Paint policy only; disabled semantics and caller-owned accessory state are unchanged. */
    val dimDisabledContent: Boolean = true,
) {
    init {
        require(floatArrayOf(minHeightDp, horizontalPaddingDp, verticalPaddingDp, iconSizeDp, gapDp,
            minTextWidthDp, detailGapDp).all { it.isFinite() && it >= 0f })
        require(floatArrayOf(titleSizeSp, detailSizeSp, valueSizeSp).all { it.isFinite() && it > 0f })
        require(cornerRadiusDp >= 0)
        require(titleWeight in 1..1000)
        require(startPaddingDp == null || startPaddingDp.isFinite() && startPaddingDp >= 0f)
        require(endPaddingDp == null || endPaddingDp.isFinite() && endPaddingDp >= 0f)
        require(trailingGapDp == null || trailingGapDp.isFinite() && trailingGapDp >= 0f)
    }
}

/** Apply the sampled color directly to a transparent accessory, without starting another tween. */
fun interface NativeAccessoryForeground {
    fun paint(@ColorInt color: Int)
}

/** A labelled settings row whose trailing control keeps its identity during responsive reflow. */
open class NativeSettingsRow(
    context: Context,
    palette: ControlPalette,
    val style: NativeSettingsRowStyle = NativeSettingsRowStyle(),
) : ViewGroup(context) {
    private val resolvedTitleStyle = style.titleTextStyle ?: NativeTextStyle(style.titleSizeSp, weight = style.titleWeight)
    private val resolvedDetailStyle = style.detailTextStyle ?: NativeTextStyle(style.detailSizeSp)
    val titleView = NativeText(context, palette, resolvedTitleStyle)
    val detailView = NativeText(context, palette, resolvedDetailStyle, NativeTextRole.MUTED)
    val valueView = NativeText(context, palette, NativeTextStyle(style.valueSizeSp), NativeTextRole.MUTED)
    private val iconView = ImageView(context)
    private val textColumn = LinearLayout(context)
    private var detailContent: View? = null
    private var hasDetailText = false
    private val trailing = LinearLayout(context)
    private val accessory = FrameLayout(context)
    private var action: (() -> Unit)? = null
    private var appliedPalette: ControlPalette? = null
    private var appliedDensity = Float.NaN
    private var appliedFontScale = Float.NaN
    private var initialized = false
    private var colorSurface:StateColorSurface?=null
    private var hasPublishedInk = false
    private var publishedIconInk = 0
    private var selectedAccessoryInk = palette.onAccent
    private var normalAccessoryInk = palette.content
    private var accessoryForeground: NativeAccessoryForeground? = null
    private var lastAccessoryInk = 0
    private var hasPublishedAccessoryInk = false
    private var stacked = false
    private var topHeight = 0
    private var contentHeight = 0

    init {
        setPaddingRelative(dp(style.startPaddingDp ?: style.horizontalPaddingDp), dp(style.verticalPaddingDp),
            dp(style.endPaddingDp ?: style.horizontalPaddingDp), dp(style.verticalPaddingDp))
        minimumHeight = dp(style.minHeightDp)
        clipToPadding = false
        valueView.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        iconView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        iconView.visibility = GONE
        detailView.visibility = GONE
        valueView.visibility = GONE
        accessory.visibility = GONE
        textColumn.orientation = LinearLayout.VERTICAL
        textColumn.addView(titleView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        textColumn.addView(detailView, detailLayoutParams())
        trailing.orientation = LinearLayout.HORIZONTAL
        trailing.gravity = Gravity.CENTER_VERTICAL
        trailing.addView(valueView, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        trailing.addView(accessory, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(iconView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(textColumn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(trailing, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        setOnClickListener { if (isEnabled) action?.invoke() }
        isClickable = false
        isFocusable = false
        initialized = true
        applyPalette(palette)
    }

    /** Authoritative label binding does not invoke an action or alter the accessory's state. */
    fun bind(
        title: CharSequence,
        detail: CharSequence? = null,
        icon: Drawable? = null,
        value: CharSequence? = null,
        onClick: (() -> Unit)? = null,
    ) {
        setText(titleView, title)
        setText(detailView, detail)
        setText(valueView, value)
        hasDetailText = !detail.isNullOrEmpty()
        updateDetailVisibility()
        valueView.visibility = if (value.isNullOrEmpty()) GONE else VISIBLE
        if (iconView.drawable !== icon) iconView.setImageDrawable(icon)
        iconView.visibility = if (icon == null) GONE else VISIBLE
        action = onClick
        isClickable = onClick != null
        isFocusable = onClick != null
        importantForAccessibility = if (onClick == null) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
        val labelImportance = if (onClick == null) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO
        titleView.importantForAccessibility = labelImportance
        detailView.importantForAccessibility = labelImportance
        valueView.importantForAccessibility = labelImportance
        contentDescription = if (onClick == null) null else
            listOfNotNull(title, detail?.takeUnless { it.isEmpty() }, value?.takeUnless { it.isEmpty() }).joinToString(", ")
        requestLayout()
    }

    /** Replaces the text detail with caller-owned content below the title. Pass null to restore it. */
    fun setDetailContent(view: View?) {
        if (detailContent === view) return
        require(view?.parent == null) { "Detail content already belongs to another parent" }
        detailContent?.let(textColumn::removeView)
        detailContent = view
        view?.let { textColumn.addView(it, detailLayoutParams()) }
        updateDetailVisibility()
        requestLayout()
    }

    private fun updateDetailVisibility() {
        detailView.visibility = if (detailContent == null && hasDetailText) VISIBLE else GONE
    }

    private fun detailLayoutParams() = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(style.detailGapDp) }

    /**
     * Only an explicit replacement reparents a control; repaint and reflow never do.
     * The caller owns its enabled/checked/listener state and accessible label. If the row and
     * accessory are separate actions, each needs its own meaningful accessibility description.
     */
    fun setAccessory(view: View?, params: FrameLayout.LayoutParams? = null) = setAccessory(view, params, null)

    /** Follow the row's sampled paint while keeping the accessory's activation and enabled state independent. */
    fun setAccessory(
        view: View?,
        params: FrameLayout.LayoutParams? = null,
        onForegroundChanged: NativeAccessoryForeground?,
    ) {
        require(view != null || onForegroundChanged == null)
        val current = if (accessory.childCount == 0) null else accessory.getChildAt(0)
        if (current === view) {
            if (view != null && params != null && view.layoutParams !== params) view.layoutParams = params
            setAccessoryForeground(onForegroundChanged)
            return
        }
        require(view == null || view.parent == null) { "Accessory already belongs to another parent" }
        setAccessoryForeground(null)
        accessory.removeAllViews()
        if (view != null) {
            val supplied = view.layoutParams
            val childParams = params ?: when (supplied) {
                is FrameLayout.LayoutParams -> supplied
                is MarginLayoutParams -> FrameLayout.LayoutParams(supplied)
                null -> FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                else -> FrameLayout.LayoutParams(supplied)
            }
            accessory.addView(view, childParams)
        }
        accessory.visibility = if (view == null) GONE else VISIBLE
        requestLayout()
        setAccessoryForeground(onForegroundChanged)
    }

    /** Foreground for a separate accessory on the row's settled normal or selected surface. */
    val accessoryContentColor: Int get() = if (isSelected) selectedAccessoryInk else normalAccessoryInk

    fun applyPalette(palette: ControlPalette) {
        val density = resources.displayMetrics.density
        val fontScale = resources.configuration.fontScale
        if (appliedPalette == palette && appliedDensity == density && appliedFontScale == fontScale) return
        val paint = ControlStyle(context, palette)
        if (appliedDensity != density || appliedFontScale != fontScale) {
            setPaddingRelative(dp(style.startPaddingDp ?: style.horizontalPaddingDp), dp(style.verticalPaddingDp),
                dp(style.endPaddingDp ?: style.horizontalPaddingDp), dp(style.verticalPaddingDp))
            minimumHeight = dp(style.minHeightDp)
            titleView.componentTypography(resolvedTitleStyle)
            detailView.componentTypography(resolvedDetailStyle)
            valueView.componentTypography(NativeTextStyle(style.valueSizeSp))
            val detailGap = dp(style.detailGapDp)
            for (view in listOfNotNull(detailView, detailContent)) {
                val params = view.layoutParams as LinearLayout.LayoutParams
                if (params.topMargin != detailGap) view.layoutParams = params.apply { topMargin = detailGap }
            }
        }
        val iconColor = palette.roleColor(style.iconRole)
        val states = arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf())
        val selectedFill = if (style.containerFill) Color(palette.selection).compositeOver(Color(palette.surface)).toArgb() else palette.selection
        val selectedInk = selectionForeground(selectedFill, palette.surface, palette.onAccent, palette.content)
        selectedAccessoryInk = selectedInk
        normalAccessoryInk = palette.content
        fun ink(normal: Int) = if (style.dimDisabledContent) paint.foregroundStates(normal, selectedInk)
            else paint.foregroundStates(normal, selectedInk, disabled = normal)
        val iconColors = ink(iconColor)
        val titleColors = ink(palette.content)
        val detailColors = ink(palette.muted)
        val fills=ColorStateList(states,intArrayOf(selectedFill,if(style.containerFill)palette.surface else 0))
        val strokes=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled,android.R.attr.state_focused),intArrayOf()),intArrayOf(palette.accent,0))
        val selectedRipple = selectionIndication(selectedFill, palette.surface, selectedInk, palette.accent)
        val ripple = ColorStateList(states, intArrayOf(rippleColor(selectedRipple), rippleColor(palette.accent)))
        val radius = style.cornerRadiusDp * density
        val colors = colorSurface ?: StateColorSurface(this, fills, strokes, iconColors, ripple, radius, density,
            titleColors, detailColors, palette.surface, publishInk = ::publishInk).also {
            it.qualifyAccentInk = true
            colorSurface = it
        }
        colors.bind(fills, strokes, iconColors, ripple, radius, density, titleColors, detailColors, palette.surface)
        if(background !== colors.background)background=colors.background
        appliedPalette = palette
        appliedDensity = density
        appliedFontScale = fontScale
        updateLabelState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        appliedPalette?.let(::applyPalette)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (initialized) updateLabelState()
    }

    // The row's selection is visual and semantic state for its own action.
    override fun dispatchSetSelected(selected: Boolean) = Unit

    override fun performClick(): Boolean = acceptsNativeInput() && super.performClick()

    private fun updateLabelState() {
        titleView.isEnabled = isEnabled
        detailView.isEnabled = isEnabled
        valueView.isEnabled = isEnabled
        iconView.isEnabled = isEnabled
        // The caller owns a trailing switch/editor's enabled, checked and listener state.
    }

    private fun publishInk(icon: Int, title: Int, detail: Int) {
        if (!hasPublishedInk || publishedIconInk != icon) {
            publishedIconInk = icon
            iconView.imageTintList = ColorStateList.valueOf(icon)
        }
        // A single owner samples the fill and all three inks on the same frame.
        if (!hasPublishedInk || titleView.currentTextColor != title) titleView.setTextColor(title)
        if (!hasPublishedInk || detailView.currentTextColor != detail) detailView.setTextColor(detail)
        if (!hasPublishedInk || valueView.currentTextColor != detail) valueView.setTextColor(detail)
        hasPublishedInk = true
        publishAccessoryForeground()
    }

    private fun setAccessoryForeground(painter: NativeAccessoryForeground?) {
        if (accessoryForeground === painter) return
        accessoryForeground = painter
        hasPublishedAccessoryInk = false
        publishAccessoryForeground()
    }

    private fun publishAccessoryForeground() {
        val painter = accessoryForeground ?: return
        // An independently enabled action remains readable even when the row is disabled.
        val alternate = if (isSelected) normalAccessoryInk else selectedAccessoryInk
        val ink = checkNotNull(colorSurface).foregroundOnSurface(accessoryContentColor, alternate)
        if (hasPublishedAccessoryInk && lastAccessoryInk == ink) return
        lastAccessoryInk = ink
        hasPublishedAccessoryInk = true
        painter.paint(ink)
    }

    private fun rippleColor(color: Int) = (color and 0xFFFFFF) or (40 shl 24)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val unconstrainedHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val widthLimit = MeasureSpec.makeMeasureSpec(available,
            if (bounded) MeasureSpec.AT_MOST else MeasureSpec.UNSPECIFIED)
        val hasIcon = iconView.visibility != GONE
        if (hasIcon) {
            val iconWidth = if (bounded) dp(style.iconSizeDp).coerceAtMost(available) else dp(style.iconSizeDp)
            val iconSpec = MeasureSpec.makeMeasureSpec(iconWidth, MeasureSpec.EXACTLY)
            iconView.measure(iconSpec, iconSpec)
        }
        val hasAccessory = accessory.childCount > 0 && accessory.getChildAt(0).visibility != GONE
        accessory.visibility = if (hasAccessory) VISIBLE else GONE
        val hasValue = valueView.visibility != GONE
        val accessoryParams = accessory.layoutParams as LinearLayout.LayoutParams
        val accessoryGap = if (hasValue && hasAccessory) dp(style.gapDp) else 0
        // Measure both independently before choosing a direction. A long value must not
        // consume the width before a retained switch/editor gets its own measure pass.
        if (hasAccessory) accessory.measure(widthLimit, unconstrainedHeight)
        if (hasValue) valueView.measure(widthLimit, unconstrainedHeight)
        val verticalTrailing = bounded && hasValue && hasAccessory &&
            valueView.measuredWidth + accessory.measuredWidth + accessoryGap > available
        val orientation = if (verticalTrailing) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        if (trailing.orientation != orientation) trailing.orientation = orientation
        trailing.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        accessoryParams.marginStart = if (verticalTrailing) 0 else accessoryGap
        accessoryParams.topMargin = if (verticalTrailing) accessoryGap else 0
        val hasTrailing = hasValue || hasAccessory
        trailing.visibility = if (hasTrailing) VISIBLE else GONE
        if (hasTrailing) trailing.measure(widthLimit, unconstrainedHeight)
        val gap = dp(style.gapDp)
        val trailingGap = dp(style.trailingGapDp ?: style.gapDp)
        val leadingWidth = if (hasIcon) iconView.measuredWidth + gap else 0
        val trailingWidth = if (hasTrailing) trailing.measuredWidth else 0
        val fontScale = resources.configuration.fontScale.coerceAtLeast(1f)
        val minTextWidth = (dp(style.minTextWidthDp) * fontScale).roundToInt()
        stacked = bounded && hasTrailing && available - leadingWidth - trailingWidth - trailingGap < minTextWidth
        val textWidth = (available - leadingWidth -
            (if (hasTrailing && !stacked) trailingWidth + trailingGap else 0)).coerceAtLeast(0)
        textColumn.measure(MeasureSpec.makeMeasureSpec(textWidth,
            if (bounded) MeasureSpec.EXACTLY else MeasureSpec.UNSPECIFIED), unconstrainedHeight)
        topHeight = max(textColumn.measuredHeight, if (hasIcon) iconView.measuredHeight else 0)
        if (hasTrailing && !stacked) topHeight = max(topHeight, trailing.measuredHeight)
        contentHeight = topHeight + (if (stacked) trailingGap + trailing.measuredHeight else 0)
        val desiredWidth = paddingLeft + paddingRight + (if (stacked)
            max(leadingWidth + textColumn.measuredWidth, trailingWidth)
        else leadingWidth + textColumn.measuredWidth + (if (hasTrailing) trailingGap + trailingWidth else 0))
        val desiredHeight = max(minimumHeight, paddingTop + contentHeight + paddingBottom)
        setMeasuredDimension(resolveSizeAndState(desiredWidth, widthMeasureSpec, 0),
            resolveSizeAndState(desiredHeight, heightMeasureSpec, 0))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val start = if (rtl) width - paddingRight else paddingLeft
        val end = if (rtl) paddingLeft else width - paddingRight
        val contentTop = paddingTop + ((height - paddingTop - paddingBottom - contentHeight) / 2).coerceAtLeast(0)
        val hasIcon = iconView.visibility != GONE
        val gap = dp(style.gapDp)
        val trailingGap = dp(style.trailingGapDp ?: style.gapDp)
        if (hasIcon) {
            val x = if (rtl) start - iconView.measuredWidth else start
            val y = contentTop + (topHeight - iconView.measuredHeight) / 2
            iconView.layout(x, y, x + iconView.measuredWidth, y + iconView.measuredHeight)
        }
        val leadingWidth = if (hasIcon) iconView.measuredWidth + gap else 0
        val textX = if (rtl) start - leadingWidth - textColumn.measuredWidth else start + leadingWidth
        val textY = contentTop + (topHeight - textColumn.measuredHeight) / 2
        textColumn.layout(textX, textY, textX + textColumn.measuredWidth, textY + textColumn.measuredHeight)
        if (trailing.visibility != GONE) {
            val x = if (rtl) end else end - trailing.measuredWidth
            val y = if (stacked) contentTop + topHeight + trailingGap else contentTop + (topHeight - trailing.measuredHeight) / 2
            trailing.layout(x, y, x + trailing.measuredWidth, y + trailing.measuredHeight)
        }
    }

    override fun getBaseline(): Int = titleView.baseline.takeIf { it >= 0 }?.let { textColumn.top + it } ?: -1
    private fun dp(value: Float) = (value * resources.displayMetrics.density).roundToInt()
    private fun setText(view: TextView, value: CharSequence?) {
        if (!TextUtils.equals(view.text, value)) view.text = value
    }
}

/** A rounded group with retained rows and dividers that follow visible row boundaries. */
