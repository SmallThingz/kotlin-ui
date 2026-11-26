package smallthingz.ui.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Outline
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.os.Bundle
import android.text.Layout
import android.text.TextUtils
import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.TextView
import org.xmlpull.v1.XmlPullParser
import smallthingz.ui.R
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.controls.StateColorSurface
import smallthingz.ui.controls.ControlStyle
import smallthingz.ui.controls.selectionForeground
import smallthingz.ui.controls.selectionIndication
import smallthingz.ui.graphics.ViewGrain
import smallthingz.ui.graphics.bindGrainLayer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Geometry is explicit so a consumer can retain its established design. */
data class NativeChoiceStyle(
    val radiusDp: Float = 12f,
    val minHeightDp: Float = 48f,
    val textSizeSp: Float = 14f,
    val horizontalPaddingDp: Float = 12f,
    val verticalPaddingDp: Float = 8f,
    val outlined: Boolean = true,
    val iconSizeDp: Float = 18f,
    val iconGapDp: Float = 8f,
    val iconAbove: Boolean = false,
    val selectedFill: Boolean = true,
    val indicatorHeightDp: Float = 0f,
    val indicatorInsetDp: Float = 0f,
    val indicatorWidthDp: Float? = null,
    val indicatorColor: Int? = null,
    val selectedOutlined: Boolean? = null,
    val unselectedOutlined: Boolean? = null,
    val selectedOutlineRole: NativeChoiceOutlineRole = NativeChoiceOutlineRole.ACCENT,
    val textStyle: NativeTextStyle? = null,
    val selectedTextStyle: NativeTextStyle? = null,
    val visualMinHeightDp: Float? = null,
    val normalTextRole: NativeTextRole? = null,
    val selectedTextRole: NativeTextRole? = null,
    val normalIconRole: NativeTextRole? = null,
    val selectedIconRole: NativeTextRole? = null,
    val disabledSelectedFill: NativeChoiceColor? = null,
    val disabledSelectedOutline: NativeChoiceColor? = null,
    val disabledOutline: NativeChoiceColor? = null,
    val disabledContent: NativeChoiceColor? = null,
    val disabledSelectedContent: NativeChoiceColor? = null,
    val retainContentColorsWhenDisabled: Boolean = false,
    val startPaddingDp: Float? = null,
    val endPaddingDp: Float? = null,
    val trailingIconSizeDp: Float = 18f,
    /** Shared color timing by default; false snaps state changes while palette changes still animate. */
    val animateSelection: Boolean = true,
    /** Optional selected-focus ink; null preserves the existing accent outline. */
    val focusedSelectedOutline: NativeChoiceColor? = null,
    /** Opt in to preserving complete short labels when equal-width choices choose their columns. */
    val minimumLabelWidth: NativeChoiceLabelWidth = NativeChoiceLabelWidth.GlyphMinimum,
    /**
     * Opt-in visual padding for a centered, single-line label with a stacked icon.
     * Narrows only the retained surface/ripple/outline, never the View or text layout.
     * Null keeps the cell surface. Wrapped, styled or unsupported content keeps it too.
     */
    val contentSurfacePaddingDp: Float? = null,
) {
    init {
        require(floatArrayOf(radiusDp, minHeightDp, textSizeSp, horizontalPaddingDp,
            verticalPaddingDp, iconSizeDp, iconGapDp, indicatorHeightDp,
            indicatorInsetDp, trailingIconSizeDp).all { it.isFinite() && it >= 0f })
        require(indicatorWidthDp == null || indicatorWidthDp.isFinite() && indicatorWidthDp >= 0f)
        require(visualMinHeightDp == null || visualMinHeightDp.isFinite() && visualMinHeightDp > 0f)
        require(startPaddingDp == null || startPaddingDp.isFinite() && startPaddingDp >= 0f)
        require(endPaddingDp == null || endPaddingDp.isFinite() && endPaddingDp >= 0f)
        require(contentSurfacePaddingDp == null || contentSurfacePaddingDp.isFinite() && contentSurfacePaddingDp >= 0f)
    }
}

enum class ChoiceArrangement { EqualWidth, Wrap }

enum class NativeChoiceOutlineRole { ACCENT, OUTLINE }

enum class NativeChoiceSemantics { RADIO, CHECKBOX, TAB, BUTTON }

/** A theme-reactive color; alpha multiplies the palette color's original alpha. */
data class NativeChoiceColor(val role: NativeTextRole, val alpha: Float = 1f) {
    init { require(alpha.isFinite() && alpha in 0f..1f) }

    internal fun resolve(palette: ControlPalette): Int {
        val color = palette.roleColor(role)
        return (color and 0x00ffffff) or ((Color.alpha(color) * alpha).roundToInt() shl 24)
    }
}

data class NativeChoice(
    val id: String,
    val label: CharSequence,
    val icon: Drawable? = null,
    val enabled: Boolean = true,
    val contentDescription: CharSequence? = null,
    val semantics: NativeChoiceSemantics? = null,
)

private class CheckedEvents(private val userChanged: (Boolean) -> Unit) {
    var listener: CompoundButton.OnCheckedChangeListener? = null
    private var binding = 0
    private var interaction = 0

    fun changed(button: CompoundButton, checked: Boolean) {
        if (binding == 0) listener?.onCheckedChanged(button, checked)
    }

    fun bind(block: () -> Unit) {
        binding++
        try { block() } finally { binding-- }
    }

    fun click(button: CompoundButton, action: () -> Boolean): Boolean {
        val before = button.isChecked
        val outermost = interaction++ == 0
        val result: Boolean
        try {
            result = action()
        } finally {
            interaction--
        }
        if (outermost && binding == 0 && before != button.isChecked) userChanged(button.isChecked)
        return result
    }
}

private enum class ChoiceRole { CheckBox, Radio, Tab, Button }

private fun NativeChoiceSemantics.choiceRole(): ChoiceRole = when (this) {
    NativeChoiceSemantics.CHECKBOX -> ChoiceRole.CheckBox
    NativeChoiceSemantics.RADIO -> ChoiceRole.Radio
    NativeChoiceSemantics.TAB -> ChoiceRole.Tab
    NativeChoiceSemantics.BUTTON -> ChoiceRole.Button
}

private class ChoiceConfiguration(context: Context) {
    private var density = context.resources.displayMetrics.density
    private var scaledDensity = context.resources.displayMetrics.scaledDensity
    private var fontScale = context.resources.configuration.fontScale

    fun changed(context: Context): Boolean {
        val metrics = context.resources.displayMetrics
        val scale = context.resources.configuration.fontScale
        if (density == metrics.density && scaledDensity == metrics.scaledDensity && fontScale == scale) return false
        density = metrics.density
        scaledDensity = metrics.scaledDensity
        fontScale = scale
        return true
    }
}

/** Restores the original native typography when an optional selected override stops applying. */
private class ChoiceTypography(private val view: TextView, private val style: NativeChoiceStyle) {
    private val typeface = view.typeface
    private val letterSpacing = view.letterSpacing
    private val lineExtra = view.lineSpacingExtra
    private val lineMultiplier = view.lineSpacingMultiplier
    private val fontPadding = view.includeFontPadding
    private var initialized = false
    private var applied: NativeTextStyle? = null
    private var density = Float.NaN
    private var scaledDensity = Float.NaN
    private var fontScale = Float.NaN
    private var textScaleX = Float.NaN

    fun apply(checked: Boolean) {
        val next = if (checked) style.selectedTextStyle ?: style.textStyle else style.textStyle
        val metrics = view.resources.displayMetrics
        val scale = view.resources.configuration.fontScale
        if (initialized && applied == next && density == metrics.density &&
            scaledDensity == metrics.scaledDensity && fontScale == scale && textScaleX == view.textScaleX) return
        initialized = true
        applied = next
        density = metrics.density
        scaledDensity = metrics.scaledDensity
        fontScale = scale
        textScaleX = view.textScaleX
        if (next != null) view.componentTypography(next) else {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.context.componentSp(style.textSizeSp))
            view.typeface = typeface
            view.letterSpacing = letterSpacing
            view.includeFontPadding = fontPadding
            view.setLineSpacing(lineExtra, lineMultiplier)
            view.applyComponentLineHeight(null, null)
        }
    }
}

/** A real checked control; state binding is silent and does not recreate its View. */
class NativeChip private constructor(
    context: Context,
    palette: ControlPalette,
    style: NativeChoiceStyle,
    semantics: NativeChoiceSemantics,
    attributes: AttributeSet?,
) : CheckBox(context, attributes), NativeInputGate, AutoCloseable {
    constructor(
        context: Context,
        palette: ControlPalette,
        style: NativeChoiceStyle = NativeChoiceStyle(),
        semantics: NativeChoiceSemantics = NativeChoiceSemantics.CHECKBOX,
    ) : this(context, palette, style, semantics, null)

    companion object {
        internal fun forSegmentedChoice(
            context: Context,
            palette: ControlPalette,
            style: NativeChoiceStyle,
        ): NativeChip = context.resources.getXml(R.xml.smallthingz_native_chip_attributes).use { attributes ->
            var event: Int
            do {
                event = attributes.next()
            } while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT)
            check(event == XmlPullParser.START_TAG)
            NativeChip(context, palette, style, NativeChoiceSemantics.CHECKBOX, attributes)
        }
    }

    override val acceptsNativeInput: Boolean get() = !selectionClosed
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (bubblesPlainControlEscape(keyCode, event)) false else super.onKeyDown(keyCode, event)

    val choiceStyle = style
    var onCheckedChange: ((Boolean) -> Unit)? = null
    internal var exclusive = semantics == NativeChoiceSemantics.RADIO || semantics == NativeChoiceSemantics.TAB
    var onActivationRequest: (() -> Boolean)? = null
    internal var preserveIconColors = false
    internal var uniformSelectionEligible = false
    internal var measuredContentHeight: Int = 0
        private set
    internal fun hasUniformSelectionContent(): Boolean {
        if (!uniformSelectionEligible) return false
        val currentLayout = layout
        // Do not invoke caller transformations to probe them. TextView may build the display
        // layout during drawing, so an unavailable transformed layout retains the old renderer.
        if (transformationMethod != null && currentLayout == null) return false
        return !hasSelectionPaintSpans(text) && !hasSelectionPaintSpans(currentLayout?.text)
    }

    private fun hasSelectionPaintSpans(value: CharSequence?): Boolean {
        val spans = value as? Spanned ?: return false
        val end = spans.length
        if (end == Int.MAX_VALUE) return true
        // Include both zero and end: even an empty paragraph can paint a margin/background.
        val limit = end + 1
        return spans.nextSpanTransition(-1, limit, CharacterStyle::class.java) <= end ||
            spans.nextSpanTransition(-1, limit, LeadingMarginSpan::class.java) <= end ||
            spans.nextSpanTransition(-1, limit, LineBackgroundSpan::class.java) <= end
    }
    internal var uniformSelectionForeground: NativeSelectionForeground? = null
        set(value) {
            if (field === value) return
            field = value
            if (value != null && foregroundContentBounds == null) {
                foregroundContentBounds = RectF()
                drawChoiceContentCallback = { drawChoiceContent(it) }
                uniformSelectionInk = NativeSelectionInk()
            }
            selectionAnimation?.parentOwnsForeground = value != null
            invalidate()
        }
    private var foregroundContentBounds: RectF? = null
    private var drawChoiceContentCallback: ((Canvas) -> Unit)? = null
    internal var uniformSelectionInk: NativeSelectionInk? = null
        private set

    internal fun prepareUniformSelectionInk(surface: Int, fill: Int, trackRange: Long, pillRange: Long) {
        val state = selectionAnimation ?: return
        val ink = checkNotNull(uniformSelectionInk)
        if (ink.update(surface, fill, currentTextColor or -0x1000000, state.animatedRippleColor, trackRange, pillRange)) invalidate()
        state.publishParentRipple(ink.rippleColor)
    }
    internal var parentDrawsSelectedFill = false
        set(value) {
            if (field == value) return
            field = value
            selectionAnimation?.paintFill = !value
        }
    private var dispatchingControlledClick = false
    private var role = semantics.choiceRole()
    private var buttonSelected = false
    private val paintSelected: Boolean
        get() = if (role == ChoiceRole.Button) buttonSelected else isChecked
    private var relay: CheckedEvents? = null
    private var ready = false
    private var currentPalette = palette
    private var palettePaint = ControlStyle(context, palette)
    private var normalIcon: Drawable? = null
    private var installedChoiceIcon: Drawable? = null
    private var checkedIcon: Drawable? = null
    private var trailingIcon: Drawable? = null
    private var measuredTopIconWidth: Int? = null
    internal var stretchTopIconToWidth = false
        set(value) {
            if (field == value) return
            field = value
            measuredTopIconWidth = null
            applyIcon()
            requestLayout()
        }
    private val density: Float get() = resources.displayMetrics.density
    private val configuration = ChoiceConfiguration(context)
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var indicatorGrain: ViewGrain? = null
    private var typography: ChoiceTypography? = null
    private var visualInsetPx = 0
    private var contentSurfaceInsetPx = 0
    internal val selectedFillInset: Int get() = visualInsetPx
    internal val selectedFillHorizontalInset: Int
        get() = if (uniformSelectionForeground == null) contentSurfaceInsetPx else 0
    private var selectionAnimation: StateColorSurface? = null
    private var visualBackground: Drawable? = null
    private var indicatorColor = style.indicatorColor ?: palette.accent
    private var selectionClosed = false
    private var boundTextColors: ColorStateList? = null
    private var boundIconColors: ColorStateList? = null

    init {
        relay = CheckedEvents { onCheckedChange?.invoke(it) }
        super.setOnCheckedChangeListener { button, checked -> relay?.changed(button, checked) }
        val paint = ControlStyle(context, palette)
        paint.configureButton(this)
        paint.typography(this, choiceStyle.textSizeSp, medium = true)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, context.componentSp(choiceStyle.textSizeSp))
        if (choiceStyle.textStyle != null || choiceStyle.selectedTextStyle != null) {
            typography = ChoiceTypography(this, choiceStyle).also { it.apply(paintSelected) }
        }
        buttonDrawable = null
        buttonTintList = null
        gravity = Gravity.CENTER
        minimumWidth = dp(48f)
        minimumHeight = dp(max(48f, choiceStyle.minHeightDp))
        minWidth = minimumWidth
        minHeight = minimumHeight
        setPaddingRelative(dp(choiceStyle.startPaddingDp ?: choiceStyle.horizontalPaddingDp), dp(choiceStyle.verticalPaddingDp),
            dp(choiceStyle.endPaddingDp ?: choiceStyle.horizontalPaddingDp), dp(choiceStyle.verticalPaddingDp))
        compoundDrawablePadding = dp(choiceStyle.iconGapDp)
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        ready = true
        isSelected = paintSelected
        paintPalette()
    }

    override fun setOnCheckedChangeListener(listener: CompoundButton.OnCheckedChangeListener?) {
        val events = relay
        if (events == null) super.setOnCheckedChangeListener(listener) else events.listener = listener
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(if (ready && role == ChoiceRole.Button) false else checked)
        if (ready) {
            isSelected = paintSelected
            typography?.apply(paintSelected)
            if (activeIcon() !== installedChoiceIcon) applyIcon()
        }
    }

    override fun toggle() {
        if (role == ChoiceRole.Button || onActivationRequest != null || dispatchingControlledClick) return
        if (!exclusive || !isChecked) super.toggle()
    }

    override fun performClick(): Boolean {
        if (!isEnabled || !acceptsNativeInput()) return false
        val request = onActivationRequest
        if (request != null) {
            if (dispatchingControlledClick) return false
            dispatchingControlledClick = true
            try {
                val accepted = request()
                if (accepted) super.performClick()
                return accepted
            } finally {
                dispatchingControlledClick = false
            }
        }
        return relay?.click(this) { super.performClick() } ?: super.performClick()
    }

    override fun performLongClick(): Boolean =
        if (!isEnabled || !acceptsNativeInput()) false else super.performLongClick()

    fun bind(label: CharSequence, checked: Boolean, enabled: Boolean = true) {
        if (!TextUtils.equals(text, label)) text = label
        bindChecked(checked, enabled)
    }

    fun bindChecked(checked: Boolean, enabled: Boolean = true) {
        refreshConfiguration()
        checkNotNull(relay).bind {
            buttonSelected = false
            isEnabled = enabled
            isChecked = checked
        }
    }

    /** Model-owned selection for an ordinary Button action; checked state remains false. */
    fun bindButton(label: CharSequence, selected: Boolean = false, enabled: Boolean = true) {
        require(role == ChoiceRole.Button) { "bindButton requires BUTTON semantics" }
        refreshConfiguration()
        if (!TextUtils.equals(text, label)) text = label
        checkNotNull(relay).bind {
            buttonSelected = selected
            isEnabled = enabled
            isChecked = false
        }
    }

    /** Null checkedIcon keeps the normal icon when selected. Drawables are owned by this control. */
    fun setIcons(normal: Drawable?, checked: Drawable? = null) {
        if (normalIcon === normal && checkedIcon === checked) return
        normalIcon = normal
        checkedIcon = checked
        applyIcon()
    }

    /** The control retains this owned end glyph across selection, palette and density binding. */
    fun setTrailingIcon(icon: Drawable?) {
        if (trailingIcon === icon) return
        trailingIcon = icon
        applyIcon()
    }

    fun applyPalette(palette: ControlPalette) {
        refreshConfiguration()
        if (currentPalette == palette) return
        currentPalette = palette
        paintPalette()
    }

    internal fun asSingleChoice(semantics: NativeChoiceSemantics) {
        val previousRole = role
        exclusive = semantics != NativeChoiceSemantics.BUTTON
        role = semantics.choiceRole()
        if (previousRole != role || role == ChoiceRole.Button && isChecked)
            bindChecked(isChecked, isEnabled)
    }

    override fun getAccessibilityClassName(): CharSequence = when (role) {
        ChoiceRole.CheckBox -> "android.widget.CheckBox"
        ChoiceRole.Radio -> "android.widget.RadioButton"
        ChoiceRole.Tab -> "android.app.ActionBar\$Tab"
        ChoiceRole.Button -> "android.widget.Button"
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = accessibilityClassName
        if (role == ChoiceRole.Tab || role == ChoiceRole.Button) {
            info.isCheckable = false
            info.isChecked = false
        }
        info.isSelected = paintSelected
        if (role == ChoiceRole.Button) {
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SELECT)
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_SELECTION)
        }
        if (exclusive && isEnabled && (!isChecked || onActivationRequest != null))
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SELECT)
        (parent as? NativeSegmentedChoice)?.describeItem(this, info)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_CLICK) {
            if (!isEnabled || !acceptsNativeInput()) return false
            if (onActivationRequest != null) return performClick()
        }
        if (role == ChoiceRole.Button && (action == AccessibilityNodeInfo.ACTION_SELECT ||
            action == AccessibilityNodeInfo.ACTION_CLEAR_SELECTION)) return false
        if (action == AccessibilityNodeInfo.ACTION_SELECT && exclusive) {
            if (!isEnabled || !acceptsNativeInput()) return false
            if (onActivationRequest != null) return performClick()
            if (!isChecked) performClick()
            return isChecked
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onDraw(canvas: Canvas) {
        val foreground = uniformSelectionForeground
        if (foreground == null) drawChoiceContent(canvas)
        else {
            updateForegroundContentBounds()
            foreground.draw(canvas, this, checkNotNull(foregroundContentBounds), checkNotNull(drawChoiceContentCallback))
        }
        if (!paintSelected || choiceStyle.indicatorHeightDp == 0f) return
        val inset = choiceStyle.indicatorInsetDp * density
        if (width <= inset * 2f) return
        val indicatorWidth = min(width - inset * 2f,
            choiceStyle.indicatorWidthDp?.times(density) ?: (width - inset * 2f))
        val indicatorLeft = (width - indicatorWidth) / 2f
        val color = indicatorColor
        indicatorPaint.color = color
        indicatorPaint.alpha = if (isEnabled) Color.alpha(color) else Color.alpha(color) / 2
        val indicatorTop = max(0f, height - choiceStyle.indicatorHeightDp * density)
        canvas.drawRect(indicatorLeft, indicatorTop,
            indicatorLeft + indicatorWidth, height.toFloat(), indicatorPaint)
        indicatorGrain?.draw(canvas, indicatorLeft, indicatorTop,
            indicatorLeft + indicatorWidth, height.toFloat(), coverage = indicatorPaint.alpha)
    }

    // Both call sites run synchronously inside this chip's onDraw. The clipped ink passes
    // need only the superclass content, since View.draw would redraw the background as well.
    @android.annotation.SuppressLint("WrongCall")
    private fun drawChoiceContent(canvas: Canvas) {
        val iconOnly = iconOnlyDrawable()
        if (iconOnly != null) {
            val end = trailingIcon?.takeUnless { it === iconOnly }
            val gap = if (end == null) 0 else compoundDrawablePadding
            val combined = iconOnly.bounds.width() + gap + (end?.bounds?.width() ?: 0)
            val left = paddingLeft + (width - paddingLeft - paddingRight - combined) / 2f
            val primaryX = if (layoutDirection == View.LAYOUT_DIRECTION_RTL && end != null)
                left + end.bounds.width() + gap else left
            drawCenteredIcon(canvas, iconOnly, primaryX)
            if (end != null) drawCenteredIcon(canvas, end,
                if (layoutDirection == View.LAYOUT_DIRECTION_RTL) left else left + iconOnly.bounds.width() + gap)
        } else {
            centerInlineIcon()
            super.onDraw(canvas)
        }
    }

    private fun updateForegroundContentBounds() {
        var textWidth = 0f
        val textLayout = layout
        if (textLayout != null) for (line in 0 until textLayout.lineCount)
            textWidth = max(textWidth, textLayout.getLineWidth(line))
        val icon = activeIcon()
        val end = trailingIcon
        val iconWidth = icon?.bounds?.width() ?: 0
        val endWidth = end?.takeUnless { it === icon }?.bounds?.width() ?: 0
        val gap = if (text.isNullOrEmpty()) 0 else compoundDrawablePadding
        // The capsule contract uses centered inline icons and text, with identical ink roles.
        val contentWidth = textWidth + iconWidth + endWidth +
            (if (iconWidth > 0) gap else 0) + (if (endWidth > 0) gap else 0)
        val contentHeight = max(max(textLayout?.height ?: 0, icon?.bounds?.height() ?: 0),
            end?.bounds?.height() ?: 0)
        val x = paddingLeft + (width - paddingLeft - paddingRight - contentWidth) / 2f
        val y = paddingTop + (height - paddingTop - paddingBottom - contentHeight) / 2f
        checkNotNull(foregroundContentBounds).set(x, y, x + contentWidth, y + contentHeight)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        requestComponentLineHeightLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        refreshConfiguration()
        typography?.apply(paintSelected)
        refreshComponentLineHeight()
        if (stretchTopIconToWidth && choiceStyle.iconAbove) {
            // The group first measures intrinsic labels to choose its column count. Only its
            // final exact cell width stretches the preview; an old expanded preview must not
            // become the next intrinsic minimum and progressively reduce the column count.
            val trailingReserve = if (trailingIcon == null) 0 else
                dp(choiceStyle.trailingIconSizeDp) + compoundDrawablePadding
            val targetWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY)
                max(0, MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - trailingReserve)
                else dp(choiceStyle.iconSizeDp)
            if (measuredTopIconWidth != targetWidth) {
                measuredTopIconWidth = targetWidth
                applyIcon()
            }
        }
        val iconOnly = iconOnlyDrawable()
        val contentHeight: Int
        if (iconOnly != null) {
            val end = trailingIcon?.takeUnless { it === iconOnly }
            val contentWidth = paddingLeft + paddingRight + iconOnly.bounds.width() +
                if (end == null) 0 else compoundDrawablePadding + end.bounds.width()
            contentHeight = paddingTop + paddingBottom + max(iconOnly.bounds.height(), end?.bounds?.height() ?: 0)
            setMeasuredDimension(resolveSizeAndState(max(suggestedMinimumWidth, contentWidth), widthMeasureSpec, 0),
                resolveSizeAndState(max(suggestedMinimumHeight, contentHeight), heightMeasureSpec, 0))
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val iconHeight = activeIcon()?.bounds?.height() ?: 0
            val textHeight = layout?.height ?: 0
            val bodyHeight = if (choiceStyle.iconAbove && iconHeight > 0)
                iconHeight + compoundDrawablePadding + textHeight else max(iconHeight, textHeight)
            contentHeight = paddingTop + paddingBottom + max(bodyHeight, trailingIcon?.bounds?.height() ?: 0)
        }
        val minimum = choiceStyle.visualMinHeightDp
        measuredContentHeight = contentHeight
        val inset = if (minimum == null) 0 else
            max(0, (measuredHeight - max(dp(minimum), contentHeight)) / 2)
        if (visualInsetPx != inset) { visualInsetPx = inset; invalidate() }
        updateContentSurfaceInset(measuredWidth)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateContentSurfaceInset(width)
    }

    private fun updateContentSurfaceInset(cellWidth: Int) {
        if (choiceStyle.contentSurfacePaddingDp == null) return
        val next = contentSurfaceInset(cellWidth)
        if (contentSurfaceInsetPx == next) return
        contentSurfaceInsetPx = next
        invalidateOutline()
        invalidate()
    }

    private fun contentSurfaceInset(cellWidth: Int): Int {
        val visualPadding = choiceStyle.contentSurfacePaddingDp ?: return 0
        // Retain the established renderer for arbitrary spans, transformations, compound
        // trailing content, wrapping, asymmetric padding and non-centered text.
        if (!choiceStyle.iconAbove || trailingIcon != null || text.isNullOrEmpty() ||
            transformationMethod != null || hasSelectionPaintSpans(text) ||
            paddingLeft != paddingRight ||
            (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) != Gravity.CENTER_HORIZONTAL) return 0
        val textLayout = layout ?: return 0
        if (textLayout.alignment != Layout.Alignment.ALIGN_CENTER || textLayout.lineCount != 1 ||
            textLayout.getEllipsisCount(0) != 0 || hasSelectionPaintSpans(textLayout.text)) return 0
        val labelWidth = textLayout.getLineWidth(0)
        if (!labelWidth.isFinite()) return 0
        val contentWidth = max(ceil(labelWidth).toInt(), activeIcon()?.bounds?.width() ?: 0)
        // No maximum label width: content that needs the cell keeps the complete surface.
        val surfaceWidth = max(dp(48f).toFloat(), contentWidth + 2f * visualPadding * density)
        return max(0, ((cellWidth - ceil(surfaceWidth)) / 2f).toInt())
    }

    override fun getBaseline(): Int = if (ready && iconOnlyDrawable() != null) -1 else super.getBaseline()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshConfiguration()
        requestLayout()
    }

    private fun refreshConfiguration() {
        if (!configuration.changed(context)) return
        minimumWidth = dp(48f)
        minimumHeight = dp(max(48f, choiceStyle.minHeightDp))
        minWidth = minimumWidth
        minHeight = minimumHeight
        setPaddingRelative(dp(choiceStyle.startPaddingDp ?: choiceStyle.horizontalPaddingDp), dp(choiceStyle.verticalPaddingDp),
            dp(choiceStyle.endPaddingDp ?: choiceStyle.horizontalPaddingDp), dp(choiceStyle.verticalPaddingDp))
        compoundDrawablePadding = dp(choiceStyle.iconGapDp)
        measuredTopIconWidth = null
        applyIcon()
        if (typography == null) setTextSize(TypedValue.COMPLEX_UNIT_PX, context.componentSp(choiceStyle.textSizeSp))
        else typography?.apply(paintSelected)
        paintPalette()
    }

    private fun activeIcon(): Drawable? = if (paintSelected) checkedIcon ?: normalIcon else normalIcon

    private fun iconOnlyDrawable(): Drawable? = if (text.isNullOrEmpty() &&
        (choiceStyle.iconAbove || trailingIcon != null)) activeIcon() ?: trailingIcon else null

    private fun drawCenteredIcon(canvas: Canvas, icon: Drawable, x: Float) {
        val y = paddingTop + (height - paddingTop - paddingBottom - icon.bounds.height()) / 2f
        val save = canvas.save()
        canvas.translate(x - icon.bounds.left, y - icon.bounds.top)
        icon.draw(canvas)
        canvas.restoreToCount(save)
    }

    internal fun minimumCellWidth(): Int {
        val iconWidth = activeIcon()?.bounds?.width() ?: 0
        if (choiceStyle.minimumLabelWidth == NativeChoiceLabelWidth.WholeLabel) {
            val transformation = transformationMethod
            val displayed = if (transformation == null) text else {
                val displayLayout = layout
                // An ellipsized layout can expose substituted characters in its text sequence.
                // Probe the transformation once when the full display text is unavailable.
                if (displayLayout == null || (0 until displayLayout.lineCount).any { displayLayout.getEllipsisCount(it) > 0 }) {
                    transformation.getTransformation(text, this)
                } else displayLayout.text
            }
            val labelWidth = if (displayed.isNullOrEmpty()) 0f else Layout.getDesiredWidth(displayed, paint)
            return wholeLabelChoiceCellWidth(dp(48f), paddingLeft + paddingRight, labelWidth,
                iconWidth, trailingIcon?.bounds?.width() ?: 0, compoundDrawablePadding, choiceStyle.iconAbove)
        }
        val textWidth = if (text.isNullOrEmpty()) 0 else max(1, paint.measureText("M").roundToInt())
        val contentWidth = if (choiceStyle.iconAbove) max(iconWidth, textWidth)
            else iconWidth + textWidth + if (iconWidth > 0 && textWidth > 0) compoundDrawablePadding else 0
        val endWidth = trailingIcon?.bounds?.width() ?: 0
        return max(dp(48f), paddingLeft + paddingRight + contentWidth + endWidth +
            if (endWidth > 0 && contentWidth > 0) compoundDrawablePadding else 0)
    }

    private fun centerInlineIcon() {
        val icon = if (choiceStyle.iconAbove) null else activeIcon()
        val end = trailingIcon
        if (icon == null && end == null) return
        val centered = textAlignment == View.TEXT_ALIGNMENT_CENTER ||
            (textAlignment == View.TEXT_ALIGNMENT_GRAVITY || textAlignment == View.TEXT_ALIGNMENT_INHERIT) &&
                (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL
        if (!centered) {
            icon?.setBounds(0, 0, dp(choiceStyle.iconSizeDp), dp(choiceStyle.iconSizeDp))
            end?.setBounds(0, 0, dp(choiceStyle.trailingIconSizeDp), dp(choiceStyle.trailingIconSizeDp))
            return
        }
        val textLayout = layout ?: return
        var lineWidth = 0f
        for (line in 0 until textLayout.lineCount) lineWidth = max(lineWidth, textLayout.getLineWidth(line))
        if (choiceStyle.iconAbove) lineWidth = max(lineWidth, (activeIcon()?.bounds?.width() ?: 0).toFloat())
        val iconWidth = if (icon == null) 0 else dp(choiceStyle.iconSizeDp)
        val endWidth = if (end == null) 0 else dp(choiceStyle.trailingIconSizeDp)
        val gap = if (text.isNullOrEmpty()) 0 else compoundDrawablePadding
        val freeWidth = width - paddingLeft - paddingRight - iconWidth - endWidth - lineWidth -
            (if (icon == null) 0 else gap) - (if (end == null) 0 else gap)
        val inset = max(0, (freeWidth / 2f).roundToInt())
        val shift = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -inset else inset
        // TextView already centers the label inside its icon-reserved area. Only the inline
        // drawable needs shifting from the padding edge to center the icon and label together.
        icon?.setBounds(shift, 0, shift + iconWidth, dp(choiceStyle.iconSizeDp))
        end?.setBounds(-shift, 0, -shift + endWidth, dp(choiceStyle.trailingIconSizeDp))
    }

    private fun applyIcon() {
        val icon = activeIcon()
        installedChoiceIcon = icon
        val iconWidth = if (stretchTopIconToWidth && choiceStyle.iconAbove)
            measuredTopIconWidth ?: dp(choiceStyle.iconSizeDp) else dp(choiceStyle.iconSizeDp)
        icon?.setBounds(0, 0, iconWidth, dp(choiceStyle.iconSizeDp))
        trailingIcon?.setBounds(0, 0, dp(choiceStyle.trailingIconSizeDp), dp(choiceStyle.trailingIconSizeDp))
        if (choiceStyle.iconAbove) setCompoundDrawablesRelative(null, icon, trailingIcon, null)
        else setCompoundDrawablesRelative(icon, null, trailingIcon, null)
        // Relative top-only replacement can skip TextView's retained tint application.
        // Keep a null tint untouched so supplied artwork retains its own colors/tint.
        compoundDrawableTintList?.let { setCompoundDrawableTintList(it) }
    }

    private fun paintPalette() {
        palettePaint = ControlStyle(context, currentPalette)
        // The underline is foreground paint even when this choice has no selected fill.
        indicatorGrain = bindGrainLayer(indicatorGrain,
            if (choiceStyle.indicatorHeightDp > 0f) currentPalette.surfacePreset?.foregroundGrain ?: 0f else 0f)
        fun roleColor(role: NativeTextRole?, fallback: Int): Int =
            if (role == null) fallback else currentPalette.roleColor(role)
        fun colors(normal: Int, selected: Int): ColorStateList {
            val legacy = palettePaint.foregroundStates(normal = normal, selected = selected)
            val disabled = choiceStyle.disabledContent?.resolve(currentPalette)
            val selectedOverride = choiceStyle.disabledSelectedContent?.resolve(currentPalette)
            if (disabled == null && selectedOverride == null && !choiceStyle.retainContentColorsWhenDisabled) return legacy
            val legacyDisabled = legacy.getColorForState(intArrayOf(-android.R.attr.state_enabled), legacy.defaultColor)
            val disabledNormal = disabled ?: if (choiceStyle.retainContentColorsWhenDisabled) normal else legacyDisabled
            val disabledSelected = selectedOverride ?: disabled ?: if (choiceStyle.retainContentColorsWhenDisabled) selected else legacyDisabled
            return ColorStateList(arrayOf(
                intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_selected),
                intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(disabledSelected, disabledNormal, selected, normal))
        }
        boundTextColors = colors(
            normal = roleColor(choiceStyle.normalTextRole, currentPalette.content),
            selected = roleColor(choiceStyle.selectedTextRole, defaultSelectedInk()))
        boundIconColors = colors(
            normal = roleColor(choiceStyle.normalIconRole, currentPalette.content),
            selected = roleColor(choiceStyle.selectedIconRole, defaultSelectedInk()))
        backgroundTintList = null
        val selectedFill = if (choiceStyle.selectedFill) currentPalette.selection else Color.TRANSPARENT
        val selectedOutline = if (choiceStyle.selectedOutlineRole == NativeChoiceOutlineRole.ACCENT)
            currentPalette.accent else currentPalette.outline
        val selectedOutlined = choiceStyle.selectedOutlined ?: choiceStyle.outlined
        val unselectedOutlined = choiceStyle.unselectedOutlined ?: choiceStyle.outlined
        val states = arrayOf(
            intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_selected),
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_focused, android.R.attr.state_selected),
            intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected), intArrayOf())
        val fill = ColorStateList(states, intArrayOf(
            if (choiceStyle.selectedFill) choiceStyle.disabledSelectedFill?.resolve(currentPalette) ?: selectedFill else 0,
            0, selectedFill, 0, selectedFill, 0))
        val stroke = ColorStateList(states, intArrayOf(
            if (selectedOutlined) choiceStyle.disabledSelectedOutline?.resolve(currentPalette)
                ?: choiceStyle.disabledOutline?.resolve(currentPalette) ?: currentPalette.outline else 0,
            if (unselectedOutlined) choiceStyle.disabledOutline?.resolve(currentPalette) ?: currentPalette.outline else 0,
            choiceStyle.focusedSelectedOutline?.resolve(currentPalette) ?: currentPalette.accent, currentPalette.accent,
            if (selectedOutlined) selectedOutline else 0, if (unselectedOutlined) currentPalette.outline else 0))
        val indication = selectionIndication(selectedFill,currentPalette.surface,defaultSelectedInk(),currentPalette.accent)
        val ripple = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf((indication and 0x00ffffff) or (40 shl 24),(currentPalette.accent and 0x00ffffff) or (40 shl 24)))
        val text = checkNotNull(boundTextColors)
        val icons = checkNotNull(boundIconColors)
        val accent = ColorStateList.valueOf(choiceStyle.indicatorColor ?: currentPalette.accent)
        val retained = selectionAnimation ?: StateColorSurface(this,fill,stroke,text,ripple,
            choiceStyle.radiusDp*density,density,icons,accent,currentPalette.surface,
            animateState=choiceStyle.animateSelection,publishInk={ink,icon,indicator ->
                setTextColor(ink)
                compoundDrawableTintList=if(preserveIconColors)null else ColorStateList.valueOf(icon)
                indicatorColor=indicator
            }).also {selectionAnimation=it}
        retained.parentOwnsForeground = uniformSelectionForeground != null
        retained.setSurfacePreset(currentPalette.surfacePreset)
        retained.bind(fill,stroke,text,ripple,choiceStyle.radiusDp*density,density,icons,accent,currentPalette.surface)
        retained.paintFill = !parentDrawsSelectedFill
        val visual = visualBackground ?: (when {
            choiceStyle.contentSurfacePaddingDp != null -> ChoiceVisualBackground(
                retained.background, horizontalInset = { selectedFillHorizontalInset }) { visualInsetPx }
            choiceStyle.visualMinHeightDp != null -> ChoiceVisualBackground(retained.background) { visualInsetPx }
            else -> retained.background
        }).also { visualBackground = it }
        if (background !== visual) background = visual
    }

    private fun defaultSelectedInk(): Int = if (choiceStyle.selectedFill)
        selectionForeground(currentPalette.selection, currentPalette.surface, currentPalette.onAccent, currentPalette.content)
        else currentPalette.accent

    override fun close() {
        selectionClosed = true
        isEnabled = false
        onActivationRequest = null
        onCheckedChange = null
        setOnCheckedChangeListener(null)
        setOnKeyListener(null)
        cancelPendingInputEvents()
        selectionAnimation?.close()
        selectionAnimation = null
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()
}

/**
 * Retains option Views by stable id. Equal-width cells wrap when their minimum content cannot fit;
 * explicit Wrap is useful for filter chips. Neither binding nor palette changes invoke selection.
 */
open class NativeSegmentedChoice(
    context: Context,
    palette: ControlPalette,
    style: NativeChoiceStyle = NativeChoiceStyle(),
    val gapDp: Float = 4f,
    val arrangement: ChoiceArrangement = ChoiceArrangement.EqualWidth,
    val maxColumns: Int = Int.MAX_VALUE,
    val verticalGapDp: Float = gapDp,
    val semantics: NativeChoiceSemantics = NativeChoiceSemantics.RADIO,
    /** False makes directional keys and Home/End move focus without activating the choice. */
    var activateOnFocusNavigation: Boolean = true,
    val fixedColumns: Int? = null,
    val fillRowHeight: Boolean = false,
    /** One equal-width row; bounded height is propagated to cells, including compact visual hosts. */
    val singleRow: Boolean = false,
    /** Move one retained fill behind the selected cell; the cells retain their input and semantics. */
    val movingSelectionBackground: Boolean = false,
) : ViewGroup(context), NativeInputGate, AutoCloseable {
    private var closed = false
    override val acceptsNativeInput: Boolean get() = !closed
    val choiceStyle = style
    var onSelectionChange: ((String) -> Unit)? = null
    /** Nonnull requests activation, including reselection. The owner binds authoritative selection. */
    var onSelectionRequest: ((String) -> Boolean)? = null
        set(value) {
            field = value
            options.forEach { option -> chips[option.id]?.let { configureActivation(it, option.id) } }
        }
    var selectedId: String? = null
        private set
    protected var choicePalette: ControlPalette = palette
        private set
    private val chips = LinkedHashMap<String, NativeChip>()
    private var options: List<NativeChoice> = emptyList()
    private var ready = false
    private var placement = IntArray(0)
    private var rowCount = 0
    private var measuredColumns = 0
    private var handledNavigationKey: Int? = null
    private val density: Float get() = resources.displayMetrics.density
    private val configuration = ChoiceConfiguration(context)
    protected open val tabSemantics: Boolean get() = false
    protected open val fillWrappedCells: Boolean get() = arrangement == ChoiceArrangement.EqualWidth
    private val choiceSemantics: NativeChoiceSemantics
        get() = if (tabSemantics) NativeChoiceSemantics.TAB else semantics
    private val selectionIndicator = if (movingSelectionBackground && style.selectedFill)
        NativeSelectionIndicator(this, palette.selection).also { it.setNoiseIntensity(palette.surfacePreset?.foregroundGrain ?: 0f) } else null
    private var uniformSelectionForeground: NativeSelectionForeground? = null
    internal fun setIndicatorNoiseIntensity(intensity: Float) { selectionIndicator?.setNoiseIntensity(intensity) }
    internal var capsuleColumns: Int? = null
    internal var preferredCellWidth = 0
        private set
    internal var preferredCellContentHeight = 0
        private set

    internal fun probeCapsuleCells() {
        preferredCellWidth = 0
        preferredCellContentHeight = 0
        val unconstrained = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (index in 0 until childCount) {
            val child = getChildAt(index) as NativeChip
            child.measure(unconstrained, unconstrained)
            preferredCellWidth = max(preferredCellWidth, child.measuredWidth)
            preferredCellContentHeight = max(preferredCellContentHeight, child.measuredContentHeight)
        }
    }

    /** Capsule presentation uses matching text/icon roles; other choice styles retain their own ink. */
    internal fun useUniformSelectionForeground(surfaceRange: (() -> Long)? = null, surfaceColor: () -> Int?) {
        check(chips.isEmpty() && uniformSelectionForeground == null)
        uniformSelectionForeground = NativeSelectionForeground(this, checkNotNull(selectionIndicator),
            { choiceStyle.radiusDp * density }, surfaceColor, surfaceRange)
    }

    init {
        require(gapDp.isFinite() && gapDp >= 0f && verticalGapDp.isFinite() && verticalGapDp >= 0f && maxColumns > 0)
        require(fixedColumns == null || fixedColumns > 0 && fixedColumns <= maxColumns)
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        ready = true
    }

    fun bind(options: List<NativeChoice>, selectedId: String?) {
        if (closed) return
        require(options.all { it.id.isNotEmpty() } && options.map { it.id }.toSet().size == options.size)
        val activeIds = options.mapTo(HashSet()) { it.id }
        val removed = chips.keys.filter { it !in activeIds }
        removed.forEach { key -> chips.remove(key)?.let { it.close(); removeView(it) } }
        this.options = options.toList()
        this.selectedId = selectedId?.takeIf { id -> options.any { it.id == id && semanticsOf(it) != NativeChoiceSemantics.BUTTON } }
        for (option in options) {
            val chip = chips.getOrPut(option.id) {
                NativeChip.forSegmentedChoice(context, choicePalette, choiceStyle).apply {
                    parentDrawsSelectedFill = selectionIndicator != null
                    id = View.generateViewId()
                    onCheckedChange = { checked -> if (checked) selectFromUser(option.id) }
                    setOnKeyListener { view, keyCode, event -> handleChoiceKey(view, option.id, keyCode, event) }
                    this@NativeSegmentedChoice.addView(this, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                }
            }
            chip.asSingleChoice(semanticsOf(option))
            chip.setIcons(ownedIcon(option.icon))
            configureActivation(chip, option.id)
            chip.bind(option.label, option.id == this.selectedId, isEnabled && option.enabled)
            uniformSelectionForeground?.let { foreground ->
                // Caller spans keep TextView's RGB. The public retained view can also acquire
                // styles later, so inspect its live buffer separately before each draw.
                chip.uniformSelectionEligible = option.label !is Spanned
                chip.uniformSelectionForeground = foreground.takeIf {
                    chip.hasUniformSelectionContent() && it.isAvailable
                }
            }
            chip.contentDescription = option.contentDescription
        }
        // Drawing order changes without detaching retained controls or discarding their focus.
        if (options.indices.any { getChildAt(it) !== chips[options[it].id] }) {
            options.forEach { bringChildToFront(checkNotNull(chips[it.id])) }
        }
        updateSelectionIndicator()
        requestLayout()
    }

    /** Updates checked state and retargets the pill using the existing cell bounds. */
    internal fun bindSelection(id: String?) {
        if (closed) return
        val target = id?.takeIf { candidate ->
            options.any { it.id == candidate && semanticsOf(it) != NativeChoiceSemantics.BUTTON }
        }
        val previous = selectedId
        if (previous == target) return
        selectedId = target
        for (option in options) {
            if (option.id == previous || option.id == target) {
                chips[option.id]?.bindChecked(option.id == target, isEnabled && option.enabled)
            }
        }
        updateSelectionIndicator()
    }

    open fun applyPalette(palette: ControlPalette) {
        if (configuration.changed(context)) requestLayout()
        choicePalette = palette
        setIndicatorNoiseIntensity(palette.surfacePreset?.foregroundGrain ?: 0f)
        chips.values.forEach { it.applyPalette(palette) }
        updateSelectionIndicator()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (ready) {
            options.forEach { chips[it.id]?.isEnabled = enabled && it.enabled }
            updateSelectionIndicator()
        }
    }

    /** Retained semantic node. Visibility and layout are owned by this group. */
    fun choiceView(id: String): NativeChip? = chips[id]

    private fun selectFromUser(id: String) {
        if (!isEnabled || !acceptsNativeInput() || isButton(id) ||
            options.none { it.id == id && it.enabled }) {
            // A native listener may retire or rebind the group before this component callback.
            // Restore the current authoritative selection without another checked callback.
            val option = options.firstOrNull { it.id == id }
            if (option != null) chips[id]?.bindChecked(id == selectedId && !isButton(id), isEnabled && option.enabled)
            return
        }
        if (selectedId == id) return
        bindSelection(id)
        onSelectionChange?.invoke(id)
    }

    private fun configureActivation(chip: NativeChip, id: String) {
        chip.onActivationRequest = if (onSelectionRequest == null && !isButton(id)) null else ({ requestSelection(id) })
    }

    private fun semanticsOf(option: NativeChoice): NativeChoiceSemantics = option.semantics ?: choiceSemantics
    private fun isButton(id: String): Boolean = options.any { it.id == id && semanticsOf(it) == NativeChoiceSemantics.BUTTON }

    private fun requestSelection(id: String): Boolean {
        if (!isEnabled || !acceptsNativeInput() || options.none { it.id == id && it.enabled }) return false
        val request = onSelectionRequest
        if (request != null) return request(id)
        if (isButton(id)) return false
        selectFromUser(id)
        return true
    }

    private fun handleChoiceKey(view: View, id: String, code: Int, event: KeyEvent): Boolean {
        if (!isEnabled || !view.isEnabled || !view.acceptsNativeInput() || !view.hasFocus() || !event.hasNoModifiers()) return false
        val direction = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (layoutDirection == View.LAYOUT_DIRECTION_RTL) 1 else -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
            KeyEvent.KEYCODE_DPAD_UP -> -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            KeyEvent.KEYCODE_MOVE_HOME -> Int.MIN_VALUE
            KeyEvent.KEYCODE_MOVE_END -> Int.MAX_VALUE
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_UP) {
            val handled = handledNavigationKey == code
            handledNavigationKey = null
            return handled
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        handledNavigationKey = null
        val eligible = options.filter { it.enabled }
        if (eligible.isEmpty()) return false
        if ((code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) &&
            rowCount > 0 && placement.size >= childCount * 6) {
            val currentView = chips[id] ?: return false
            val currentIndex = indexOfChild(currentView)
            if (currentIndex < 0) return false
            val center = placement[currentIndex * 6] + placement[currentIndex * 6 + 2] / 2f
            var row = placement[currentIndex * 6 + 4] + direction
            while (row in 0 until rowCount) {
                val target = eligible.minByOrNull { option ->
                    val index = indexOfChild(chips[option.id])
                    if (index < 0 || placement[index * 6 + 4] != row) Float.POSITIVE_INFINITY
                    else kotlin.math.abs(placement[index * 6] + placement[index * 6 + 2] / 2f - center)
                }
                val targetView = target?.let { chips[it.id] }
                val targetIndex = if (targetView != null) indexOfChild(targetView) else -1
                if (targetIndex >= 0 && placement[targetIndex * 6 + 4] == row) {
                    if (targetView?.requestFocus() != true) return false
                    if (activateOnFocusNavigation && !isButton(checkNotNull(target).id)) requestSelection(target.id)
                    handledNavigationKey = code
                    return true
                }
                row += direction
            }
            return false
        }
        val current = eligible.indexOfFirst { it.id == id }
        val index = when (direction) {
            Int.MIN_VALUE -> 0
            Int.MAX_VALUE -> eligible.lastIndex
            else -> if (current < 0) 0 else (current + direction + eligible.size) % eligible.size
        }
        val target = eligible[index].id
        if (chips[target]?.requestFocus() != true) return false
        if (activateOnFocusNavigation && !isButton(target)) requestSelection(target)
        handledNavigationKey = code
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) return super.dispatchTouchEvent(event)
        if (!acceptsNativeInput()) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
            return false
        }
        // A compact-row owner may admit the standard 48dp minimum target outside visual bounds.
        // Only DOWN needs projection into a child; subsequent motion retains its real geometry.
        val extra = max(0f, (48f * density - height) / 2f)
        if (singleRow && capsuleColumns == null && event.actionMasked == MotionEvent.ACTION_UP &&
            (event.x < 0f || event.x >= width || event.y < -extra || event.y >= height + extra)) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            return try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
        }
        if (singleRow && capsuleColumns == null && event.actionMasked == MotionEvent.ACTION_DOWN && height > 0 &&
            event.x >= 0f && event.x < width && event.y >= -extra && event.y < height + extra &&
            (event.y < 0f || event.y >= height)) {
            val projected = MotionEvent.obtain(event)
            projected.offsetLocation(0f, event.y.coerceIn(0f, height - 1f) - event.y)
            return try { super.dispatchTouchEvent(projected) } finally { projected.recycle() }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (configuration.changed(context)) {
            // A retained child's measure cache may otherwise skip its own configuration refresh.
            chips.values.forEach { it.applyPalette(choicePalette) }
        }
        val count = childCount
        if (placement.size < count * 6) placement = IntArray(count * 6)
        val gap = (gapDp * density).roundToInt()
        val verticalGap = (verticalGapDp * density).roundToInt()
        val horizontalPadding = paddingLeft + paddingRight
        val verticalPadding = paddingTop + paddingBottom
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val available = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else max(0, widthSize - horizontalPadding)
        val childWidthSpec = if (widthMode == MeasureSpec.UNSPECIFIED) MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            else MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST)
        var state = 0
        var largest = 0
        var minimumCell = (48f * density).roundToInt()
        var naturalWidth = 0L
        for (i in 0 until count) {
            val child = getChildAt(i)
            child.measure(childWidthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            largest = max(largest, child.measuredWidth)
            if (child is NativeChip) minimumCell = max(minimumCell, child.minimumCellWidth())
            naturalWidth += child.measuredWidth.toLong()
            state = combineMeasuredStates(state, child.measuredState)
        }
        if (widthMode == MeasureSpec.UNSPECIFIED && choiceStyle.minimumLabelWidth == NativeChoiceLabelWidth.WholeLabel) {
            // An intrinsic TextView measure can still honor a caller's maxWidth.
            largest = max(largest, minimumCell)
        }
        naturalWidth += gap.toLong() * max(0, count - 1)
        val compact = singleRow && capsuleColumns == null
        val effectiveFixedColumns = (capsuleColumns ?: fixedColumns)?.let { requested ->
            if (widthMode == MeasureSpec.UNSPECIFIED) requested else
                choiceColumnsThatFit(available, gap, minimumCell, requested)
        }
        val singleRowColumns = if (compact) count else effectiveFixedColumns ?: count
        val equalWidth = largest.toLong() * singleRowColumns + gap.toLong() * max(0, singleRowColumns - 1)
        val fillCells = fillWrappedCells || effectiveFixedColumns != null
        val equal = if (compact) true else if (effectiveFixedColumns != null) count <= effectiveFixedColumns else
            arrangement == ChoiceArrangement.EqualWidth && count <= maxColumns &&
                minimumCell.toLong() * count + gap.toLong() * max(0, count - 1) <= available
        val intrinsicColumns = effectiveFixedColumns ?: min(count, maxColumns)
        val desired = if (equal) equalWidth else if (fillCells)
            largest.toLong() * intrinsicColumns + gap.toLong() * max(0, intrinsicColumns - 1)
            else naturalWidth
        val contentWidth = if (widthMode == MeasureSpec.EXACTLY ||
            (fillCells && widthMode != MeasureSpec.UNSPECIFIED)) available
            else min(desired, available.toLong()).toInt()
        var contentHeight = 0
        var occupiedWidth = 0
        rowCount = 0
        measuredColumns = 0
        if (count > 0 && equal) {
            measuredColumns = singleRowColumns
            val rowGap = if (compact && singleRowColumns > 1)
                min(gap, contentWidth / (singleRowColumns - 1)) else gap
            val usable = max(0, contentWidth - rowGap * (singleRowColumns - 1))
            val cellWidth = usable / singleRowColumns
            val remainder = usable % singleRowColumns
            var left = 0
            for (i in 0 until count) {
                val child = getChildAt(i)
                val width = cellWidth + if (i < remainder) 1 else 0
                val boundedHeight = if (compact && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED)
                    MeasureSpec.makeMeasureSpec(max(0, MeasureSpec.getSize(heightMeasureSpec) - verticalPadding), MeasureSpec.getMode(heightMeasureSpec))
                    else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), boundedHeight)
                placement[i * 6] = left
                placement[i * 6 + 2] = child.measuredWidth
                placement[i * 6 + 3] = child.measuredHeight
                placement[i * 6 + 4] = 0
                placement[i * 6 + 5] = i
                contentHeight = max(contentHeight, child.measuredHeight)
                left += width + rowGap
            }
            finishRow(0, count, 0, contentHeight)
            rowCount = 1
        } else if (count > 0) {
            val columnMinimum = if (arrangement == ChoiceArrangement.EqualWidth) minimumCell else largest
            val rowColumns = effectiveFixedColumns ?: if (fillCells)
                choiceColumnsThatFit(contentWidth, gap, columnMinimum, min(count, maxColumns))
                else maxColumns
            measuredColumns = rowColumns
            if (fillCells) {
                val usable = max(0, contentWidth - gap * (rowColumns - 1))
                for (i in 0 until count) {
                    val cellWidth = usable / rowColumns + if (i % rowColumns < usable % rowColumns) 1 else 0
                    getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                }
            }
            var rowStart = 0
            var rowWidth = 0
            var rowHeight = 0
            var rowItems = 0
            for (i in 0 until count) {
                val child = getChildAt(i)
                if (rowItems > 0 && (rowItems == rowColumns || effectiveFixedColumns == null &&
                    rowWidth.toLong() + gap + child.measuredWidth > contentWidth)) {
                    occupiedWidth = max(occupiedWidth, rowWidth)
                    finishRow(rowStart, i, contentHeight, rowHeight)
                    contentHeight += rowHeight + verticalGap
                    rowCount++
                    rowStart = i
                    rowWidth = 0
                    rowHeight = 0
                    rowItems = 0
                }
                val left = rowWidth + if (rowItems == 0) 0 else gap
                placement[i * 6] = left
                placement[i * 6 + 2] = child.measuredWidth
                placement[i * 6 + 3] = child.measuredHeight
                placement[i * 6 + 4] = rowCount
                placement[i * 6 + 5] = rowItems
                rowWidth = left + child.measuredWidth
                rowHeight = max(rowHeight, child.measuredHeight)
                rowItems++
            }
            finishRow(rowStart, count, contentHeight, rowHeight)
            contentHeight += rowHeight
            occupiedWidth = max(occupiedWidth, rowWidth)
            rowCount++
        }
        val measuredContentWidth = if (!equal && !fillCells && widthMode != MeasureSpec.EXACTLY)
            occupiedWidth else contentWidth
        setMeasuredDimension(resolveSizeAndState(measuredContentWidth + horizontalPadding, widthMeasureSpec, state),
            resolveSizeAndState(contentHeight + verticalPadding, heightMeasureSpec, state shl MEASURED_HEIGHT_STATE_SHIFT))
    }

    private fun finishRow(start: Int, end: Int, top: Int, rowHeight: Int) {
        for (i in start until end) {
            val child = getChildAt(i)
            if (fillRowHeight) {
                child.measure(MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY))
                placement[i * 6 + 3] = child.measuredHeight
            }
            placement[i * 6 + 1] = top + (rowHeight - child.measuredHeight) / 2
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val index = i * 6
            val childWidth = placement[index + 2]
            val x = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) width - paddingRight - placement[index] - childWidth
                else paddingLeft + placement[index]
            val y = paddingTop + placement[index + 1]
            getChildAt(i).layout(x, y, x + childWidth, y + placement[index + 3])
        }
        updateSelectionIndicator()
    }

    private fun updateSelectionIndicator() {
        val indicator = selectionIndicator ?: return
        val child = selectedId?.let { chips[it] }
        if (child == null) {
            indicator.clear()
            return
        }
        val color = if (!child.isEnabled) choiceStyle.disabledSelectedFill?.resolve(choicePalette)
            ?: choicePalette.selection else choicePalette.selection
        indicator.bindColor(color)
        if (child.isLaidOut) {
            val inset = child.selectedFillInset.toFloat()
            val horizontal = child.selectedFillHorizontalInset.toFloat()
            indicator.retarget(child.left + horizontal, child.top + inset, child.right - horizontal, child.bottom - inset)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        uniformSelectionForeground?.prepare()
        selectionIndicator?.draw(canvas, choiceStyle.radiusDp * density)
        super.dispatchDraw(canvas)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configuration.changed(context)
        requestLayout()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = when (choiceSemantics) {
            NativeChoiceSemantics.TAB -> "android.widget.TabWidget"
            NativeChoiceSemantics.RADIO -> "android.widget.RadioGroup"
            NativeChoiceSemantics.CHECKBOX -> "android.view.ViewGroup"
            NativeChoiceSemantics.BUTTON -> "android.view.ViewGroup"
        }
        var columns = 0
        for (i in 0 until childCount) columns = max(columns, placement.getOrElse(i * 6 + 5) { 0 } + 1)
        if (childCount > 0 && fixedColumns != null) columns = measuredColumns.takeIf { it > 0 } ?: fixedColumns
        info.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(rowCount, columns, false,
            if (options.any { semanticsOf(it) != NativeChoiceSemantics.BUTTON })
                AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE else AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_NONE)
    }

    internal fun describeItem(child: View, info: AccessibilityNodeInfo) {
        val index = indexOfChild(child)
        if (index < 0 || placement.size < (index + 1) * 6) return
        info.collectionItemInfo = AccessibilityNodeInfo.CollectionItemInfo.obtain(
            placement[index * 6 + 4], 1, placement[index * 6 + 5], 1, false, child.isSelected)
    }

    override fun close() {
        if (closed) return
        closed = true
        isEnabled = false
        onSelectionRequest = null
        onSelectionChange = null
        uniformSelectionForeground?.close()
        selectionIndicator?.close()
        chips.values.forEach { it.close() }
        cancelPendingInputEvents()
    }

    private fun ownedIcon(icon: Drawable?): Drawable? = icon?.constantState?.newDrawable(resources)?.mutate() ?: icon
}

class NativeTabBar(
    context: Context,
    palette: ControlPalette,
    style: NativeChoiceStyle = NativeChoiceStyle(radiusDp = 16f, textSizeSp = 12f, outlined = false, iconSizeDp = 19f, iconGapDp = 7f),
    gapDp: Float = 4f,
    arrangement: ChoiceArrangement = ChoiceArrangement.EqualWidth,
    maxColumns: Int = Int.MAX_VALUE,
    verticalGapDp: Float = gapDp,
    activateOnFocusNavigation: Boolean = true,
    fixedColumns: Int? = null,
    fillRowHeight: Boolean = false,
) : NativeSegmentedChoice(context, palette, style, gapDp, arrangement, maxColumns, verticalGapDp,
    NativeChoiceSemantics.TAB, activateOnFocusNavigation, fixedColumns, fillRowHeight) {
    override val tabSemantics: Boolean get() = true
}

data class NativeSwatchChoice(
    val id: String,
    val label: CharSequence,
    val background: Int,
    val foreground: Int,
    val enabled: Boolean = true,
)

/** Shared document-color preview, including sample ink and selected outline/check. */
class NativeSwatchGrid(
    context: Context,
    palette: ControlPalette,
    val swatchSizeDp: Float = 48f,
    gapDp: Float = 8f,
    maxColumns: Int = 5,
    val fillEqualCells: Boolean = false,
) : NativeSegmentedChoice(context, palette,
    NativeChoiceStyle(radiusDp = 8f, textSizeSp = 12f, horizontalPaddingDp = 4f, verticalPaddingDp = 4f,
        outlined = false, iconSizeDp = swatchSizeDp, iconGapDp = 4f, iconAbove = true, selectedFill = false),
    gapDp, ChoiceArrangement.Wrap, maxColumns) {
    private val swatches = LinkedHashMap<String, SwatchDrawable>()
    override val fillWrappedCells: Boolean get() = fillEqualCells

    init { require(swatchSizeDp.isFinite() && swatchSizeDp > 0f) }

    fun bindSwatches(options: List<NativeSwatchChoice>, selectedId: String?) {
        val active = options.mapTo(HashSet()) { it.id }
        swatches.keys.retainAll(active)
        val choices = options.map { option ->
            val drawable = swatches.getOrPut(option.id) {
                SwatchDrawable(resources.displayMetrics.density, swatchSizeDp, choicePalette, option.background, option.foreground)
            }
            drawable.setColors(option.background, option.foreground)
            drawable.updateDensity(resources.displayMetrics.density)
            NativeChoice(option.id, option.label, drawable, option.enabled)
        }
        bind(choices, selectedId)
        // The sample colors are document content, not control foreground colors.
        options.forEach { option ->
            choiceView(option.id)?.apply {
                stretchTopIconToWidth = fillEqualCells
                swatches.getValue(option.id).bindOwner(this, choiceStyle.animateSelection)
                preserveIconColors = true
                compoundDrawableTintList = null
            }
        }
    }

    override fun applyPalette(palette: ControlPalette) {
        super.applyPalette(palette)
        swatches.values.forEach { it.palette = palette; it.updateDensity(resources.displayMetrics.density) }
        swatches.keys.forEach { choiceView(it)?.compoundDrawableTintList = null }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        swatches.values.forEach { it.updateDensity(resources.displayMetrics.density) }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** Bounds only: the original StateColorSurface retains color, motion, grain, outline and ripple. */
private class ChoiceVisualBackground(
    drawable: Drawable,
    private val horizontalInset: (() -> Int)? = null,
    private val inset: () -> Int,
) : DrawableWrapper(drawable) {
    private fun updateContentBounds() {
        val vertical = inset().coerceIn(0, bounds.height() / 2)
        val horizontal = (horizontalInset?.invoke() ?: 0).coerceIn(0, bounds.width() / 2)
        drawable?.setBounds(bounds.left + horizontal, bounds.top + vertical,
            bounds.right - horizontal, bounds.bottom - vertical)
    }

    override fun draw(canvas: Canvas) {
        updateContentBounds()
        super.draw(canvas)
    }

    override fun getOutline(outline: Outline) {
        // The new opt-in mode must also expose its inset before the first draw.
        // Leave the existing vertical-only/default outline path unchanged.
        if (horizontalInset != null) updateContentBounds()
        super.getOutline(outline)
    }
}

private abstract class ChoiceDrawable(protected var density: Float, palette: ControlPalette) : Drawable() {
    fun updateDensity(value: Float) {
        require(value.isFinite() && value > 0f)
        if (density == value) return
        density = value
        invalidateSelf()
    }
    var palette: ControlPalette = palette
        set(value) { if (field != value) { field = value; updateColors(); invalidateSelf() } }
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val rect = RectF()
    protected val path = Path()
    private var opacity = 255
    private var checkedState = false
    private var enabledState = false
    protected val checked: Boolean get() = checkedState
    protected val enabled: Boolean get() = enabledState
    private var colorMotion: ViewColorTransition? = null
    private var animateState = true

    fun bindOwner(view: View, animateState: Boolean = true) {
        if (colorMotion != null) return
        this.animateState = animateState
        colorMotion = ViewColorTransition(view, targetColors(), { invalidateSelf() })
    }

    protected abstract fun targetColors(): IntArray
    protected fun updateColors(stateChange: Boolean = false) {
        val motion = colorMotion ?: return
        if (stateChange && !animateState) motion.snap(*targetColors()) else motion.retarget(*targetColors())
    }
    protected fun animatedColor(index: Int): Int = colorMotion?.color(index) ?: targetColors()[index]

    override fun isStateful(): Boolean = true
    override fun onStateChange(state: IntArray): Boolean {
        checkedState = android.R.attr.state_checked in state
        enabledState = android.R.attr.state_enabled in state
        updateColors(stateChange = true)
        invalidateSelf()
        return true
    }
    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    protected fun color(value: Int, stroke: Float = 0f) {
        paint.color = value
        paint.alpha = (Color.alpha(value) * opacity + 127) / 255
        paint.style = if (stroke > 0f) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = stroke
    }

    protected fun muted(value: Int): Int {
        if (enabled) return value
        fun channel(shift: Int) = (((value ushr shift) and 255) + ((palette.surface ushr shift) and 255)) / 2
        return Color.argb(channel(24), channel(16), channel(8), channel(0))
    }
}

private class SwatchDrawable(
    density: Float,
    private val sizeDp: Float,
    palette: ControlPalette,
    private var backgroundColor: Int,
    private var foregroundColor: Int,
) : ChoiceDrawable(density, palette) {
    override fun targetColors() = intArrayOf(backgroundColor, foregroundColor,
        muted(if (checked) palette.accent else palette.outline), muted(palette.accent), muted(palette.onAccent))

    override fun getIntrinsicWidth(): Int = (sizeDp * density).roundToInt()
    override fun getIntrinsicHeight(): Int = intrinsicWidth

    fun setColors(background: Int, foreground: Int) {
        if (backgroundColor == background && foregroundColor == foreground) return
        backgroundColor = background
        foregroundColor = foreground
        updateColors()
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val radius = min(8f * density, rect.width() / 4f)
        color(animatedColor(0))
        canvas.drawRoundRect(rect, radius, radius, paint)
        val unit = min(rect.width(), rect.height()) / 48f
        color(animatedColor(1), max(1f, 2f * unit))
        paint.strokeCap = Paint.Cap.ROUND
        for (line in 0..2) {
            val y = bounds.top + (17f + line * 7f) * unit
            canvas.drawLine(bounds.left + 11f * unit, y, bounds.left + (if (line == 2) 30f else 37f) * unit, y, paint)
        }
        rect.inset(density, density)
        color(animatedColor(2), (if (checked) 2f else 1f) * density)
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (checked) {
            val x = bounds.right - 10f * unit
            val y = bounds.bottom - 10f * unit
            color(animatedColor(3))
            canvas.drawCircle(x, y, 7f * unit, paint)
            color(animatedColor(4), max(1f, 1.5f * unit))
            path.reset()
            path.moveTo(x - 3f * unit, y)
            path.lineTo(x - .5f * unit, y + 2.5f * unit)
            path.lineTo(x + 3.5f * unit, y - 2.5f * unit)
            canvas.drawPath(path, paint)
        }
    }
}
