package smallthingz.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Build
import android.text.TextUtils
import android.text.InputType
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.controls.ControlStyle
import smallthingz.ui.controls.StateColorSurface
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.FontStyle
import smallthingz.ui.text.style.LineHeightStyle
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Metadata augments the retained editor's native node, without installing a delegate. */
internal data class NativeFieldAccessibilityState(
    val label: CharSequence?,
    val placeholder: CharSequence?,
    val placeholderVisible: Boolean,
    val validationError: CharSequence?,
)

/** Typography is explicit so applications can retain their established design. */
data class NativeTextStyle(
    val sizeSp: Float = 14f,
    val lineHeightSp: Float? = null,
    val weight: Int = 400,
    val letterSpacing: Float = 0f,
    val letterSpacingSp: Float? = null,
    val includeFontPadding: Boolean? = false,
    val fontFamily: String = "sans-serif",
    val lineHeightStyle: LineHeightStyle? = null,
    val italic: Boolean = false,
) {
    init {
        require(sizeSp.isFinite() && sizeSp > 0f)
        require(lineHeightSp == null || lineHeightSp.isFinite() && lineHeightSp > 0f)
        require(weight in 1..1000 && letterSpacing.isFinite())
        require(letterSpacingSp == null || letterSpacingSp.isFinite())
        require(fontFamily.isNotBlank())
    }
}

/** Resolve an appearance role for a framework TextView or editor. */
internal fun NativeTypography.nativeTextStyle(role: NativeTypographyRole): NativeTextStyle {
    val text = this[role]
    val size = if (text.fontSize.isSp) text.fontSize.value else role.style.fontSizeSp
    return NativeTextStyle(
        sizeSp = size,
        lineHeightSp = when {
            text.lineHeight.isSp -> text.lineHeight.value
            text.lineHeight.isEm -> text.lineHeight.value * size
            else -> role.style.lineHeightSp
        },
        weight = text.fontWeight?.weight ?: role.style.fontWeight,
        letterSpacing = if (text.letterSpacing.isEm) text.letterSpacing.value else 0f,
        letterSpacingSp = if (text.letterSpacing.isSp) text.letterSpacing.value else null,
        includeFontPadding = text.platformStyle?.includeFontPadding ?: false,
        fontFamily = text.fontFamily?.familyName ?: "sans-serif",
        lineHeightStyle = text.lineHeightStyle,
        italic = text.fontStyle == FontStyle.Italic,
    )
}

enum class NativeTextRole { CONTENT, MUTED, ACCENT, ON_ACCENT }
data class NativeTextFieldStyle(
    val radiusDp: Float = 16f,
    val minHeightDp: Float = 48f,
    val horizontalPaddingDp: Float = 16f,
    val verticalPaddingDp: Float = 12f,
    val textStyle: NativeTextStyle = NativeTextStyle(14f),
    val outlined: Boolean = true,
    val startPaddingDp: Float? = null,
    val endPaddingDp: Float? = null,
    val filled: Boolean = false,
    val compoundDrawablePaddingDp: Float? = null,
) {
    init {
        require(floatArrayOf(radiusDp, minHeightDp, horizontalPaddingDp, verticalPaddingDp).all { it.isFinite() && it >= 0f })
        require(startPaddingDp == null || startPaddingDp.isFinite() && startPaddingDp >= 0f)
        require(endPaddingDp == null || endPaddingDp.isFinite() && endPaddingDp >= 0f)
        require(compoundDrawablePaddingDp == null || compoundDrawablePaddingDp.isFinite() && compoundDrawablePaddingDp >= 0f)
    }
}

/** Let a containing window handle Escape instead of TextView merely clearing button focus.
 * Explicit View key listeners run before onKeyDown. Editing/selection/movement behavior stays native.
 */
internal fun TextView.bubblesPlainControlEscape(keyCode: Int, event: KeyEvent): Boolean =
    keyCode == KeyEvent.KEYCODE_ESCAPE && event.hasNoModifiers() &&
        !isTextSelectable && keyListener == null && movementMethod == null && !onCheckIsTextEditor()

internal fun Context.componentDp(value: Float): Int {
    require(value.isFinite() && value >= 0f)
    return (value * resources.displayMetrics.density).roundToInt()
}

/** Use the same nonlinear font scaling as the library's paragraph renderer. */
internal fun Context.componentSp(value: Float): Float = with(Density(this)) { value.sp.toPx() }

internal fun TextView.componentTypography(style: NativeTextStyle) {
    val density = Density(context)
    val fontPx = with(density) { style.sizeSp.sp.toPx() }
    setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx)
    style.includeFontPadding?.let { includeFontPadding = it }
    typeface = Typeface.create(Typeface.create(style.fontFamily, Typeface.NORMAL), style.weight, style.italic)
    letterSpacing = style.letterSpacingSp?.let {
        val emWidth = textSize * textScaleX
        if (emWidth.isFinite() && emWidth != 0f) with(density) { it.sp.toPx() } / emWidth else 0f
    } ?: style.letterSpacing
    val lineHeightPx = style.lineHeightSp?.let { height ->
        with(density) {
            if (fontScale > 1.05f) height / fontPx.toSp().value * fontPx else height.sp.toPx()
        }
    }
    // setLineHeight skips a zero adjustment; explicitly reset spacing when the new
    // target equals the font metrics so a previous style cannot leave stale leading.
    val extra = if (style.lineHeightStyle == null) {
        lineHeightPx?.let { ceil(it).toInt() - paint.getFontMetricsInt(null) } ?: 0
    } else 0
    setLineSpacing(extra.toFloat(), 1f)
    applyComponentLineHeight(lineHeightPx, style.lineHeightStyle)
}

/** No allocation on unchanged measure/style binds; PX typography must observe configuration. */
private class ComponentMetrics {
    private var density = Float.NaN
    private var fontScale = Float.NaN
    private var textScale = Float.NaN
    fun update(context: Context, textScaleX: Float = 1f): Boolean {
        val nextDensity = context.resources.displayMetrics.density
        val nextFontScale = context.resources.configuration.fontScale
        if (density == nextDensity && fontScale == nextFontScale && textScale == textScaleX) return false
        density = nextDensity
        fontScale = nextFontScale
        textScale = textScaleX
        return true
    }
}

@Suppress("NOTHING_TO_INLINE") // Keep callers independent of this file's Android color initialization.
internal inline fun ControlPalette.roleColor(role: NativeTextRole): Int = when (role) {
    NativeTextRole.CONTENT -> content
    NativeTextRole.MUTED -> muted
    NativeTextRole.ACCENT -> accent
    NativeTextRole.ON_ACCENT -> onAccent
}

/** A retained native text node with shared typography and palette handling. */
open class NativeText(
    context: Context,
    palette: ControlPalette,
    style: NativeTextStyle = NativeTextStyle(),
    role: NativeTextRole = NativeTextRole.CONTENT,
) : TextView(context) {
    private var componentPalette = palette
    private var componentStyle = style
    private var componentRole = role
    private val componentMetrics = ComponentMetrics()
    private var extraLineSpacingPx: Float? = null
    private var inkMotion:ViewColorTransition?=null
    private var inkTargets:ColorStateList?=null
    init { refreshMetrics(); applyColor() }
    /**
     * Retains additional natural line spacing in raw pixels, with multiplier 1.
     * An explicit [NativeTextStyle.lineHeightSp] takes precedence: this value remains dormant
     * until that height is removed. Null clears the override and restores natural spacing
     * when no explicit height is present. Configuration changes do not rescale captured pixels.
     */
    fun applyExtraLineSpacing(extraPx: Float?) {
        require(extraPx == null || extraPx.isFinite() && extraPx >= 0f)
        extraLineSpacingPx = extraPx
        applyNaturalExtraLineSpacing(extraPx ?: 0f)
    }

    fun applyPalette(palette: ControlPalette) {
        refreshMetrics()
        if (componentPalette == palette) return
        componentPalette = palette
        applyColor()
    }
    fun applyStyle(style: NativeTextStyle) {
        if (componentStyle == style) { refreshMetrics(); return }
        componentStyle = style
        componentMetrics.update(context, textScaleX)
        componentTypography(style)
        restoreExtraLineSpacing()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshMetrics(force = true)
    }
    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        requestComponentLineHeightLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        refreshMetrics()
        refreshComponentLineHeight()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    private fun refreshMetrics(force: Boolean = false) {
        if (componentMetrics.update(context, textScaleX) || force) componentTypography(componentStyle)
        restoreExtraLineSpacing()
    }
    private fun restoreExtraLineSpacing() {
        val extra = extraLineSpacingPx ?: return
        applyNaturalExtraLineSpacing(extra)
    }
    private fun applyNaturalExtraLineSpacing(extra: Float) {
        if (componentStyle.lineHeightSp != null) return
        if (lineSpacingExtra != extra || lineSpacingMultiplier != 1f) setLineSpacing(extra, 1f)
    }
    fun setRole(role: NativeTextRole) {
        if (componentRole == role) return
        componentRole = role
        applyColor()
    }
    private fun applyColor() {
        val color = componentPalette.roleColor(componentRole)
        val colors=ControlStyle(context, componentPalette).foregroundStates(color,color)
        animateTextColors(colors)
    }
    fun animateTextColor(color:Int)=animateTextColors(ColorStateList.valueOf(color))
    private fun animateTextColors(colors:ColorStateList) {
        inkTargets=colors
        val target=colors.getColorForState(drawableState,colors.defaultColor)
        val current=inkMotion
        if(current==null) {
            if(!isAttachedToWindow)super.setTextColor(target)
            inkMotion=ViewColorTransition(this,intArrayOf(currentTextColor),{super.setTextColor(checkNotNull(inkMotion).color(0))})
        }
        checkNotNull(inkMotion).retarget(target)
    }
    override fun setTextColor(color:Int) {
        inkMotion?.close();inkMotion=null;inkTargets=null
        super.setTextColor(color)
    }
    override fun setTextColor(colors:ColorStateList?) {
        inkMotion?.close();inkMotion=null;inkTargets=null
        super.setTextColor(colors)
    }
    override fun drawableStateChanged() {
        super.drawableStateChanged()
        inkTargets?.let {inkMotion?.retarget(it.getColorForState(drawableState,it.defaultColor))}
    }
}

/** The selected presentation owner, independent of whether the editor is currently empty. */
enum class NativePlaceholderPresentation { NATIVE, STYLED, EXTERNAL }

private val transparentPlaceholderColors = ColorStateList(arrayOf(intArrayOf()), intArrayOf(Color.TRANSPARENT))

/** Palette changes never replace this editor, its Editable, selection, or input connection. */
open class NativeTextField(
    context: Context,
    palette: ControlPalette,
    style: NativeTextFieldStyle = NativeTextFieldStyle(),
) : EditText(context) {
    private var componentPalette = palette
    private var componentStyle = style
    private val componentMetrics = ComponentMetrics()
    private var originalCompoundDrawablePadding: Int? = null
    private var colorSurface:StateColorSurface?=null
    private var chromeMotion:ViewColorTransition?=null
    // Superclass constructors can call the public mode setters before these fields initialize.
    private var modeObservationReady = false
    private var observedSingleLine: Boolean? = null
    private var modeOperationDepth = 0
    private var modeOperationAmbiguous = false
    private var fieldAccessibilityOwner: Any? = null
    private var fieldAccessibilityState: NativeFieldAccessibilityState? = null
    private var placeholderOwner: Any? = null
    private var placeholderStyleChanged: (() -> Unit)? = null
    private var placeholderRenderer: NativeTextFieldPlaceholder? = null
    private var placeholderPaintOwned = false
    private var savedPlaceholderHintColors: ColorStateList? = null
    internal var currentPlaceholderTextStyle: NativeTextStyle? = null
        private set

    /**
     * STYLED supports a single-line, plain hint without internal compound drawables or marquee.
     * Unsupported hint/geometry uses native presentation; EXTERNAL ownership always takes priority.
     * The hint object and the editor's text, input connection and scroll state are never replaced.
     */
    val placeholderPresentation: NativePlaceholderPresentation
        get() = when {
            placeholderOwner != null -> NativePlaceholderPresentation.EXTERNAL
            currentPlaceholderTextStyle != null && NativeTextFieldPlaceholder.supports(this) -> NativePlaceholderPresentation.STYLED
            else -> NativePlaceholderPresentation.NATIVE
        }

    /** Retained separately from editor typography; null restores the original native hint path. */
    fun applyPlaceholderTextStyle(style: NativeTextStyle?) {
        if (currentPlaceholderTextStyle == style) return
        currentPlaceholderTextStyle = style
        synchronizePlaceholderPaint()
        placeholderStyleChanged?.invoke()
        requestLayout()
        invalidate()
    }

    internal fun bindPlaceholderPresentation(owner: Any, onStyleChanged: () -> Unit) {
        require(placeholderOwner == null || placeholderOwner === owner) { "Placeholder already belongs to another field" }
        placeholderOwner = owner
        placeholderStyleChanged = onStyleChanged
        synchronizePlaceholderPaint()
        onStyleChanged()
    }

    internal fun releasePlaceholderPresentation(owner: Any) {
        if (placeholderOwner !== owner) return
        placeholderOwner = null
        placeholderStyleChanged = null
        synchronizePlaceholderPaint(captureExternalColors = false)
        requestLayout()
        invalidate()
    }
    init {
        // API 28's constructor synchronizes this flag for text-class input. A subclass can
        // alter raw metadata in later superclass callbacks, so only snapshot the exact class.
        if (Build.VERSION.SDK_INT < 29 && javaClass == NativeTextField::class.java &&
            inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
            observedSingleLine = inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0
        }
        modeObservationReady = true
        applyGeometry()
        applyPaint()
        componentMetrics.update(context, textScaleX)
    }

    /** Null retains the wrapper's legacy API 28 fallback; it is not an inferred native mode. */
    internal fun singleLinePresentationMode(): Boolean? = when {
        Build.VERSION.SDK_INT >= 29 -> isSingleLine
        !modeObservationReady || modeOperationDepth != 0 -> null
        else -> observedSingleLine
    }

    override fun setSingleLine(singleLine: Boolean) {
        if (!modeObservationReady || Build.VERSION.SDK_INT >= 29) {
            super.setSingleLine(singleLine)
            return
        }
        beginModeObservation()
        var succeeded = false
        try {
            super.setSingleLine(singleLine)
            succeeded = true
        } finally {
            finishModeObservation(singleLine, succeeded)
        }
    }

    override fun setInputType(type: Int) {
        if (!modeObservationReady || Build.VERSION.SDK_INT >= 29) {
            super.setInputType(type)
            return
        }
        beginModeObservation()
        var succeeded = false
        try {
            super.setInputType(type)
            succeeded = true
        } finally {
            val multilineMask = InputType.TYPE_MASK_CLASS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            val multilineText = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            finishModeObservation(type and multilineMask != multilineText, succeeded)
        }
    }

    private fun beginModeObservation() {
        if (modeOperationDepth == 0) modeOperationAmbiguous = false
        else modeOperationAmbiguous = true
        modeOperationDepth++
    }

    private fun finishModeObservation(singleLine: Boolean, succeeded: Boolean) {
        if (!succeeded) modeOperationAmbiguous = true
        modeOperationDepth--
        if (modeOperationDepth != 0) return
        // An outer native call can invoke a later nested command. Do not overwrite its result
        // with the outer argument, or guess the partially completed state after a failure.
        val observed = if (modeOperationAmbiguous) null else singleLine
        if (observedSingleLine != observed) {
            observedSingleLine = observed
            requestLayout()
            invalidate()
        }
    }
    fun applyPalette(palette: ControlPalette) {
        refreshMetrics()
        if (componentPalette == palette) return
        componentPalette = palette
        applyPaint()
    }
    fun applyStyle(style: NativeTextFieldStyle) {
        if (componentStyle == style) { refreshMetrics(); return }
        componentStyle = style
        applyGeometry()
        applyPaint()
        componentMetrics.update(context, textScaleX)
    }

    internal fun bindAppearance(palette: ControlPalette, style: NativeTextFieldStyle) {
        if (componentStyle == style) {
            applyPalette(palette)
            return
        }
        componentPalette = palette
        applyStyle(style)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshMetrics(force = true)
        placeholderRenderer?.onConfigurationChanged(newConfig)
    }
    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        requestComponentLineHeightLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        refreshMetrics()
        refreshComponentLineHeight()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        synchronizePlaceholderPaint()
        preparePlaceholder(measuredWidth)?.let { renderer ->
            val desired = renderer.measuredHeight + compoundPaddingTop + compoundPaddingBottom
            if (desired > measuredHeight) setMeasuredDimension(measuredWidthAndState,
                resolveSizeAndState(desired, heightMeasureSpec, measuredHeightAndState and MEASURED_STATE_MASK))
        }
    }
    override fun onDraw(canvas: Canvas) {
        synchronizePlaceholderPaint()
        if (text.isNullOrEmpty() && !hint.isNullOrEmpty()) preparePlaceholder(width)?.draw(canvas, this)
        // Native selection/caret painting stays above the decorative placeholder.
        super.onDraw(canvas)
    }
    override fun performClick(): Boolean = acceptsNativeInput() && super.performClick()
    private fun refreshMetrics(force: Boolean = false) {
        if (componentMetrics.update(context, textScaleX) || force) {
            applyGeometry()
            applyPaint()
        }
    }

    /** Only the owning field container may bind or release its supplemental semantics. */
    internal fun bindFieldAccessibility(owner: Any, state: NativeFieldAccessibilityState) {
        require(fieldAccessibilityOwner == null || fieldAccessibilityOwner === owner) {
            "Editor accessibility already belongs to another field"
        }
        fieldAccessibilityOwner = owner
        if (fieldAccessibilityState == state) return
        fieldAccessibilityState = state
        notifyFieldAccessibilityChanged()
    }

    internal fun releaseFieldAccessibility(owner: Any) {
        if (fieldAccessibilityOwner !== owner) return
        fieldAccessibilityOwner = null
        if (fieldAccessibilityState == null) return
        fieldAccessibilityState = null
        notifyFieldAccessibilityChanged()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val state = fieldAccessibilityState ?: return
        val empty = text.isNullOrEmpty()
        val label = state.label?.takeUnless { it.isBlank() }
        val placeholder = state.placeholder?.takeIf { state.placeholderVisible && it.isNotBlank() }
        val semanticHint = label ?: placeholder
        if (semanticHint != null) {
            info.hintText = semanticHint
            info.isShowingHintText = empty
            if (empty && (info.text.isNullOrEmpty() || TextUtils.equals(info.text, hint))) {
                info.text = if (label != null && placeholder != null && !TextUtils.equals(placeholder, label)) {
                    "$label, $placeholder"
                } else semanticHint
            }
        }
        state.validationError?.takeUnless { it.isBlank() }?.let {
            info.isContentInvalid = true
            info.error = it
        }
    }

    private fun notifyFieldAccessibilityChanged() {
        if (isAttachedToWindow) sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }
    private fun applyGeometry() {
        componentTypography(componentStyle.textStyle)
        minimumHeight = context.componentDp(componentStyle.minHeightDp)
        setPaddingRelative(
            context.componentDp(componentStyle.startPaddingDp ?: componentStyle.horizontalPaddingDp),
            context.componentDp(componentStyle.verticalPaddingDp),
            context.componentDp(componentStyle.endPaddingDp ?: componentStyle.horizontalPaddingDp),
            context.componentDp(componentStyle.verticalPaddingDp),
        )
        val gap = componentStyle.compoundDrawablePaddingDp
        if (gap != null) {
            if (originalCompoundDrawablePadding == null) originalCompoundDrawablePadding = compoundDrawablePadding
            val gapPx = context.componentDp(gap)
            if (compoundDrawablePadding != gapPx) compoundDrawablePadding = gapPx
        } else {
            val original = originalCompoundDrawablePadding
            originalCompoundDrawablePadding = null
            if (original != null && compoundDrawablePadding != original) compoundDrawablePadding = original
        }
    }
    private fun applyPaint() {
        val palette = componentPalette
        val style = componentStyle
        val controls = ControlStyle(context, palette)
        backgroundTintList = null
        val states=arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf(android.R.attr.state_focused),intArrayOf())
        val hasSurface = style.outlined || style.filled
        val fills=if(hasSurface)ColorStateList(states,intArrayOf(palette.surface,palette.raised,palette.raised)) else ColorStateList.valueOf(0)
        val strokes=if(style.outlined)ColorStateList(states,intArrayOf(palette.outline,palette.accent,palette.outline)) else ColorStateList.valueOf(0)
        val ink=controls.foregroundStates(palette.content,palette.content)
        val hint=ColorStateList.valueOf(palette.muted)
        val selection=ColorStateList.valueOf(palette.selection)
        val none=ColorStateList.valueOf(0)
        val radius=style.radiusDp*resources.displayMetrics.density
        val stroke=context.componentDp(1f).coerceAtLeast(1).toFloat()
        val retained=colorSurface ?: StateColorSurface(this,fills,strokes,ink,none,radius,stroke,hint,selection,palette.surface,
            publishInk={text,placeholder,highlight->setTextColor(text);applyComponentHintColors(ColorStateList.valueOf(placeholder));highlightColor=highlight})
        colorSurface=retained
        retained.setSurfacePreset(palette.surfacePreset)
        retained.bind(fills,strokes,ink,none,radius,stroke,hint,selection,palette.surface)
        val next=if(hasSurface)retained.background else null
        if(background !== next)background=next
        val chrome=chromeMotion
        if(chrome==null) {
            tintEditorChrome(palette.accent)
            chromeMotion=ViewColorTransition(this,intArrayOf(palette.accent),{tintEditorChrome(checkNotNull(chromeMotion).color(0))})
        } else chrome.retarget(palette.accent)
    }
    override fun drawableStateChanged() {
        super.drawableStateChanged()
        colorSurface?.updateState(drawableState)
    }

    private fun preparePlaceholder(width: Int): NativeTextFieldPlaceholder? {
        if (placeholderPresentation != NativePlaceholderPresentation.STYLED) return null
        val style = currentPlaceholderTextStyle ?: return null
        val renderer = placeholderRenderer ?: NativeTextFieldPlaceholder(context, componentPalette).also {
            placeholderRenderer = it
        }
        val colors = if (placeholderPaintOwned) savedPlaceholderHintColors else hintTextColors
        renderer.prepare(this, componentPalette, style, colors, width)
        return renderer
    }

    private fun applyComponentHintColors(colors: ColorStateList) {
        if (placeholderPaintOwned) {
            savedPlaceholderHintColors = colors
            setHintTextColor(transparentPlaceholderColors)
        } else setHintTextColor(colors)
        synchronizePlaceholderPaint(captureExternalColors = false)
    }

    private fun synchronizePlaceholderPaint(captureExternalColors: Boolean = true) {
        if (placeholderPresentation != NativePlaceholderPresentation.NATIVE) {
            // A decoration may itself reassert transparent paint. That is not replacement
            // fallback metadata; only palette binds update saved colors while it owns paint.
            if (!placeholderPaintOwned || (placeholderOwner == null && captureExternalColors &&
                    hintTextColors !== transparentPlaceholderColors)) savedPlaceholderHintColors = hintTextColors
            placeholderPaintOwned = true
            if (hintTextColors !== transparentPlaceholderColors) setHintTextColor(transparentPlaceholderColors)
        } else if (placeholderPaintOwned) {
            val restored = savedPlaceholderHintColors
            placeholderPaintOwned = false
            savedPlaceholderHintColors = null
            setHintTextColor(restored)
        }
    }

    private fun tintEditorChrome(color: Int) {
        // API 28 retains construction-theme chrome. Do not reflect into Editor or
        // force a focus/selection change to refresh visible handles on newer APIs.
        if (Build.VERSION.SDK_INT < 29) return
        textCursorDrawable?.mutate()?.let { it.setTint(color); textCursorDrawable = it }
        textSelectHandle?.mutate()?.let { it.setTint(color); setTextSelectHandle(it) }
        textSelectHandleLeft?.mutate()?.let { it.setTint(color); setTextSelectHandleLeft(it) }
        textSelectHandleRight?.mutate()?.let { it.setTint(color); setTextSelectHandleRight(it) }
    }
}
