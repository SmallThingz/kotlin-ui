package smallthingz.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.controls.ControlStyle
import smallthingz.ui.controls.StateColorSurface
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.controls.selectionForeground
import smallthingz.ui.controls.selectionIndication
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
) {
    init {
        require(sizeSp.isFinite() && sizeSp > 0f)
        require(lineHeightSp == null || lineHeightSp.isFinite() && lineHeightSp > 0f)
        require(weight in 1..1000 && letterSpacing.isFinite())
        require(letterSpacingSp == null || letterSpacingSp.isFinite())
        require(fontFamily.isNotBlank())
    }
}

enum class NativeTextRole { CONTENT, MUTED, ACCENT, ON_ACCENT }
enum class NativeButtonTone { TEXT, TONAL, OUTLINED, FILLED }

data class NativeButtonStyle(
    val tone: NativeButtonTone = NativeButtonTone.TEXT,
    val radiusDp: Float = 12f,
    val minHeightDp: Float = 48f,
    val minWidthDp: Float = 48f,
    val horizontalPaddingDp: Float = 12f,
    val verticalPaddingDp: Float = 8f,
    val textStyle: NativeTextStyle = NativeTextStyle(14f, weight = 500),
    val outlined: Boolean = false,
    val fullCorner: Boolean = false,
    val disabledContentColor: Int? = null,
    val visualMinHeightDp: Float? = null,
    val disabledContainerColor: Int? = null,
) {
    init {
        require(floatArrayOf(radiusDp, minHeightDp, minWidthDp, horizontalPaddingDp, verticalPaddingDp).all { it.isFinite() && it >= 0f })
        require(visualMinHeightDp == null || visualMinHeightDp.isFinite() && visualMinHeightDp >= 0f)
    }
}

data class NativeIconButtonStyle(
    val tone: NativeButtonTone = NativeButtonTone.TEXT,
    val radiusDp: Float = 12f,
    val minSizeDp: Float = 48f,
    val iconSizeDp: Float = 24f,
    val outlined: Boolean = false,
    val backgroundInsetDp: Float = 0f,
    val disabledContainerAlpha: Float? = null,
    val disabledContentAlpha: Float? = null,
    val disabledContentColor: Int? = null,
) {
    init {
        require(floatArrayOf(radiusDp, minSizeDp, iconSizeDp, backgroundInsetDp).all { it.isFinite() && it >= 0f })
        require(minSizeDp > 0f && iconSizeDp <= minSizeDp && backgroundInsetDp * 2f < minSizeDp)
        require(disabledContainerAlpha == null || disabledContainerAlpha.isFinite() && disabledContainerAlpha in 0f..1f)
        require(disabledContentAlpha == null || disabledContentAlpha.isFinite() && disabledContentAlpha in 0f..1f)
    }
}

data class NativeTextFieldStyle(
    val radiusDp: Float = 16f,
    val minHeightDp: Float = 48f,
    val horizontalPaddingDp: Float = 16f,
    val verticalPaddingDp: Float = 12f,
    val textStyle: NativeTextStyle = NativeTextStyle(14f),
    val outlined: Boolean = true,
    val startPaddingDp: Float? = null,
    val endPaddingDp: Float? = null,
) {
    init {
        require(floatArrayOf(radiusDp, minHeightDp, horizontalPaddingDp, verticalPaddingDp).all { it.isFinite() && it >= 0f })
        require(startPaddingDp == null || startPaddingDp.isFinite() && startPaddingDp >= 0f)
        require(endPaddingDp == null || endPaddingDp.isFinite() && endPaddingDp >= 0f)
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
    typeface = Typeface.create(Typeface.create(style.fontFamily, Typeface.NORMAL), style.weight, false)
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

private fun alphaColor(color: Int, alpha: Int): Int = (color and 0x00ffffff) or (alpha shl 24)

private fun componentBackground(
    view: View,
    palette: ControlPalette,
    tone: NativeButtonTone,
    radiusDp: Float,
    outlined: Boolean,
    disabledContainerAlpha: Float? = null,
    fullCorner: Boolean = false,
    disabledContainerColor: Int? = null,
    retained: StateColorSurface? = null,
    foreground: ColorStateList,
    publishInk: (Int,Int,Int)->Unit,
): StateColorSurface {
    val context=view.context
    val border = outlined || tone == NativeButtonTone.OUTLINED
    val fill = when (tone) {
        NativeButtonTone.TEXT, NativeButtonTone.OUTLINED -> Color.TRANSPARENT
        NativeButtonTone.TONAL -> palette.raised
        NativeButtonTone.FILLED -> palette.accent
    }
    val selectedFill = if (tone == NativeButtonTone.FILLED) palette.accent else palette.selection
    fun disabledFill(color: Int): Int = disabledContainerColor ?: if (color == Color.TRANSPARENT) color else
        alphaColor(color, disabledContainerAlpha?.let { (Color.alpha(color) * it).roundToInt() } ?: 90)
    val states=arrayOf(intArrayOf(-android.R.attr.state_enabled,android.R.attr.state_selected),
        intArrayOf(-android.R.attr.state_enabled),intArrayOf(android.R.attr.state_focused,android.R.attr.state_selected),
        intArrayOf(android.R.attr.state_focused),intArrayOf(android.R.attr.state_selected),intArrayOf())
    val fills=ColorStateList(states,intArrayOf(disabledFill(selectedFill),disabledFill(fill),selectedFill,fill,selectedFill,fill))
    val focus=if(tone==NativeButtonTone.FILLED)palette.onAccent else palette.accent
    val strokes=ColorStateList(states,intArrayOf(if(border)palette.outline else 0,if(border)palette.outline else 0,
        focus,focus,if(border)palette.accent else 0,if(border)palette.outline else 0))
    val preferredRipple = if (tone == NativeButtonTone.FILLED) palette.onAccent else palette.accent
    val selectedInk = if (tone == NativeButtonTone.FILLED) palette.onAccent else
        selectionForeground(palette.selection, palette.surface, palette.onAccent, palette.content)
    val selectedRipple = selectionIndication(selectedFill, palette.surface, selectedInk, preferredRipple)
    val rippleColors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(alphaColor(selectedRipple, 40), alphaColor(preferredRipple, 40)))
    val radius=if(fullCorner)Float.MAX_VALUE else radiusDp*context.resources.displayMetrics.density
    val stroke=context.componentDp(1f).coerceAtLeast(1).toFloat()
    val result=retained ?: StateColorSurface(view,fills,strokes,foreground,rippleColors,radius,stroke,
        underlay=palette.surface,publishInk=publishInk)
    result.bind(fills,strokes,foreground,rippleColors,radius,stroke,underlay=palette.surface)
    return result
}

private fun componentForeground(context: Context, palette: ControlPalette, tone: NativeButtonTone, disabledContentAlpha: Float? = null, disabledContentColor: Int? = null): ColorStateList {
    val normal = if (tone == NativeButtonTone.FILLED) palette.onAccent else palette.content
    val selected = if (tone == NativeButtonTone.FILLED) palette.onAccent else
        selectionForeground(palette.selection, palette.surface, palette.onAccent, palette.content)
    val controls = ControlStyle(context, palette)
    return if (disabledContentColor != null) controls.foregroundStates(normal, selected, disabledContentColor)
    else if (disabledContentAlpha == null) controls.foregroundStates(normal, selected)
    else controls.foregroundStates(normal, selected, alphaColor(normal, (Color.alpha(normal) * disabledContentAlpha).roundToInt()))
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

/** Native click, keyboard and accessibility behavior, without platform theme geometry. */
open class NativeButton(
    context: Context,
    palette: ControlPalette,
    style: NativeButtonStyle = NativeButtonStyle(),
) : Button(context, null, 0) {
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (bubblesPlainControlEscape(keyCode, event)) false else super.onKeyDown(keyCode, event)

    private var componentPalette = palette
    private var componentStyle = style
    private val componentMetrics = ComponentMetrics()
    private var visualContainer: NativeButtonContainerDrawable? = null
    private var colorSurface:StateColorSurface?=null
    init {
        isAllCaps = false
        gravity = Gravity.CENTER
        stateListAnimator = null
        elevation = 0f
        applyGeometry()
        applyPaint()
        componentMetrics.update(context, textScaleX)
    }
    fun applyPalette(palette: ControlPalette) {
        refreshMetrics()
        if (componentPalette == palette) return
        componentPalette = palette
        applyPaint()
    }
    fun applyStyle(style: NativeButtonStyle) {
        if (componentStyle == style) { refreshMetrics(); return }
        componentStyle = style
        applyGeometry()
        applyPaint()
        componentMetrics.update(context, textScaleX)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshMetrics(force = true)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        refreshMetrics()
        refreshComponentLineHeight()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        updateVisualContainerHeight()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateVisualContainerHeight()
    }
    override fun performClick(): Boolean = acceptsNativeInput() && super.performClick()

    private fun updateVisualContainerHeight() {
        val container = visualContainer ?: return
        val visualMinimum = componentStyle.visualMinHeightDp ?: return
        // The text Layout retains its intrinsic height independently of the View's
        // interaction minimum. Large or wrapped content therefore removes the inset.
        val contentHeight = (layout?.height ?: 0) + compoundPaddingTop + compoundPaddingBottom
        container.setVisualHeight(maxOf(context.componentDp(visualMinimum), contentHeight))
    }
    private fun refreshMetrics(force: Boolean = false) {
        if (componentMetrics.update(context, textScaleX) || force) {
            applyGeometry()
            applyPaint()
        }
    }
    private fun applyGeometry() {
        val style = componentStyle
        componentTypography(style.textStyle)
        minWidth = context.componentDp(style.minWidthDp)
        minimumWidth = minWidth
        minHeight = context.componentDp(style.minHeightDp)
        minimumHeight = minHeight
        setPaddingRelative(context.componentDp(style.horizontalPaddingDp), context.componentDp(style.verticalPaddingDp), context.componentDp(style.horizontalPaddingDp), context.componentDp(style.verticalPaddingDp))
    }
    private fun applyPaint() {
        backgroundTintList = null
        val foreground = componentForeground(context, componentPalette, componentStyle.tone,
            disabledContentColor = componentStyle.disabledContentColor)
        val surface = componentBackground(this, componentPalette, componentStyle.tone, componentStyle.radiusDp,
            componentStyle.outlined, fullCorner = componentStyle.fullCorner,
            disabledContainerColor = componentStyle.disabledContainerColor,retained=colorSurface,foreground=foreground,
            publishInk={ink,icon,_->setTextColor(ink);compoundDrawableTintList=ColorStateList.valueOf(icon)})
        colorSurface=surface
        val paint=surface.background
        visualContainer = if (componentStyle.visualMinHeightDp == null) null else visualContainer ?: NativeButtonContainerDrawable(paint)
        val next=visualContainer ?: paint
        if(background !== next)background=next
        updateVisualContainerHeight()
    }
}

/** The image stays at the requested glyph size even when its touch target grows. */
open class NativeIconButton(
    context: Context,
    palette: ControlPalette,
    style: NativeIconButtonStyle = NativeIconButtonStyle(),
) : ImageButton(context, null, 0) {
    private var componentPalette = palette
    private var componentStyle = style
    private val componentMetrics = ComponentMetrics()
    private var colorSurface:StateColorSurface?=null
    private var insetSurface:InsetDrawable?=null
    private var insetSize=-1
    init {
        scaleType = ImageView.ScaleType.FIT_CENTER
        stateListAnimator = null
        elevation = 0f
        applyGeometry()
        applyPaint()
        componentMetrics.update(context)
    }
    fun applyPalette(palette: ControlPalette) {
        refreshMetrics()
        if (componentPalette == palette) return
        componentPalette = palette
        applyPaint()
    }
    fun applyStyle(style: NativeIconButtonStyle) {
        if (componentStyle == style) { refreshMetrics(); return }
        componentStyle = style
        applyGeometry()
        applyPaint()
        componentMetrics.update(context)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshMetrics(force = true)
    }
    override fun performClick(): Boolean = acceptsNativeInput() && super.performClick()
    private fun refreshMetrics(force: Boolean = false) {
        if (componentMetrics.update(context) || force) {
            applyGeometry()
            applyPaint()
        }
    }
    private fun applyGeometry() {
        val style = componentStyle
        require(style.iconSizeDp.isFinite() && style.iconSizeDp >= 0f && style.iconSizeDp <= style.minSizeDp)
        require(style.backgroundInsetDp.isFinite() && style.backgroundInsetDp >= 0f && style.backgroundInsetDp * 2f < style.minSizeDp)
        minimumWidth = context.componentDp(style.minSizeDp)
        minimumHeight = context.componentDp(style.minSizeDp)
        updateIconPadding(if (width > 0) width else minimumWidth, if (height > 0) height else minimumHeight)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        refreshMetrics()
        // Drawable intrinsic size and size-dependent centering padding must not feed
        // WRAP_CONTENT measurement back into an ever-growing touch target.
        val measuredWidth = resolveSizeAndState(suggestedMinimumWidth, widthMeasureSpec, 0)
        val measuredHeight = resolveSizeAndState(suggestedMinimumHeight, heightMeasureSpec, 0)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(measuredWidth and MEASURED_SIZE_MASK, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight and MEASURED_SIZE_MASK, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(measuredWidth, measuredHeight)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateIconPadding(w, h)
    }
    private fun updateIconPadding(w: Int, h: Int) {
        val glyph = context.componentDp(componentStyle.iconSizeDp)
        val horizontal = ((w - glyph) / 2).coerceAtLeast(0)
        val vertical = ((h - glyph) / 2).coerceAtLeast(0)
        if (paddingLeft != horizontal || paddingTop != vertical || paddingRight != horizontal || paddingBottom != vertical) {
            setPadding(horizontal, vertical, horizontal, vertical)
        }
    }
    private fun applyPaint() {
        backgroundTintList = null
        val foreground=componentForeground(context,componentPalette,componentStyle.tone,
            componentStyle.disabledContentAlpha,componentStyle.disabledContentColor)
        val surface=componentBackground(this,componentPalette,componentStyle.tone,componentStyle.radiusDp,
            componentStyle.outlined,componentStyle.disabledContainerAlpha,retained=colorSurface,foreground=foreground,
            publishInk={ink,_,_->imageTintList=ColorStateList.valueOf(ink)})
        colorSurface=surface
        val inset=context.componentDp(componentStyle.backgroundInsetDp)
        if(inset!=insetSize){insetSize=inset;insetSurface=if(inset>0)InsetDrawable(surface.background,inset) else null}
        val next=insetSurface ?: surface.background
        if(background !== next)background=next
        // A drawable's optical padding must not change the requested glyph geometry.
        updateIconPadding(if (width > 0) width else minimumWidth, if (height > 0) height else minimumHeight)
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
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshMetrics(force = true)
        placeholderRenderer?.onConfigurationChanged(newConfig)
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
    }
    private fun applyPaint() {
        val palette = componentPalette
        val style = componentStyle
        val controls = ControlStyle(context, palette)
        backgroundTintList = null
        val states=arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf(android.R.attr.state_focused),intArrayOf())
        val fills=if(style.outlined)ColorStateList(states,intArrayOf(palette.surface,palette.raised,palette.raised)) else ColorStateList.valueOf(0)
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
        retained.bind(fills,strokes,ink,none,radius,stroke,hint,selection,palette.surface)
        val next=if(style.outlined)retained.background else null
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
