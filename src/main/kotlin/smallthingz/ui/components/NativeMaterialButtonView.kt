package smallthingz.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.common.nativeSurfaceInputAllowed
import smallthingz.ui.geometry.roundedGroupContains
import smallthingz.ui.input.DEFAULT_MINIMUM_TOUCH_TARGET_DP
import smallthingz.ui.input.phase.NativeClickPhaseHost
import smallthingz.ui.input.phase.NativePointerLayerShape
import smallthingz.ui.interaction.view.isNativeClickActivationKey
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.view.MergedRowAccessibility
import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost
import smallthingz.ui.text.view.NativeMergedRowAccessibilityState
import smallthingz.ui.text.view.NativeMergedRowRole
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import smallthingz.ui.util.closePreservingPrimaryFailure
import smallthingz.ui.graphics.ViewGrain
import smallthingz.ui.graphics.bindGrainLayer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Insets and minimums describe the painted surface. A parent may supply a larger touch target. */
data class NativeMaterialButtonStyle(
    val horizontalPaddingDp: Int,
    val verticalPaddingDp: Int = 8,
    val minimumWidthDp: Int = 58,
    val minimumHeightDp: Int = 40,
    val iconSizeDp: Int = 19,
    val iconGapDp: Int = 7,
    val radiusDp: Float? = null,
    val assignedSize: Boolean = false,
    val alignContentStart: Boolean = false,
    val showLabel: Boolean = true,
)

open class NativeMaterialButtonView private constructor(
    context: Context,
    private val filled: Boolean,
    textStyle: TextStyle,
    private var density: Density,
    resolver: FontFamily.Resolver,
    private val style: NativeMaterialButtonStyle,
    inputPolicy: NativeButtonInputPolicy,
    onClick: () -> Unit,
    private val recipe: NativeButtonRecipe?,
) : ViewGroup(context), NativeClickPhaseHost, NativePointerLayerShape, NativeMergedRowAccessibilityHost, NativeMergedActionChild, AutoCloseable {
    constructor(
        context: Context,
        filled: Boolean,
        textStyle: TextStyle,
        density: Density,
        resolver: FontFamily.Resolver,
        style: NativeMaterialButtonStyle,
        inputPolicy: NativeButtonInputPolicy = NativeButtonInputPolicy.Default,
        onClick: () -> Unit,
    ) : this(context, filled, textStyle, density, resolver, style, inputPolicy, onClick, null)

    constructor(
        context: Context,
        recipe: NativeButtonRecipe,
        density: Density,
        resolver: FontFamily.Resolver,
        inputPolicy: NativeButtonInputPolicy = NativeButtonInputPolicy.Default,
        onClick: () -> Unit,
    ) : this(context, recipe.elevated, recipe.labelStyle, density, resolver, recipe.surface, inputPolicy, onClick, recipe)

    val minimumTouchTargetDp: Int get() = recipe?.minimumTouchTargetDp ?: DEFAULT_MINIMUM_TOUCH_TARGET_DP
    private var joinedSurface = false
    private val joinedLabelGeometry get() = joinedSurface && style.showLabel
    private val assignedGeometry get() = style.assignedSize && !joinedLabelGeometry
    private var action = onClick
    private var closed = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    internal val sampledContainerColor: Int get() = paint.color
    internal val sampledContainerGrainIntensity: Float get() = grainIntensity
    /** Also publishes preset-only changes, even before the first color bind. */
    internal var onContainerColorChanged: ((Int) -> Unit)? = null
    private var colorMotion: ViewColorTransition? = null
    private var grain: ViewGrain? = null
    private var grainIntensity = 0f
    private var icon: Drawable? = null
    private var leading: View? = null
    private var hasLeadingImageTint = false
    private var leadingImageTint = 0
    private val hasLeading get() = icon != null || leading != null
    private var leadingOriginalImportance = IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private var leadingExposed = false
    private var displayedInk = Color.TRANSPARENT
    private var indicationInk = Color.TRANSPARENT
    private var inputReady = false
    var compactPadding = false
    val label = ParagraphView(context, textStyle, density, resolver, displayedInk)
    private val labelChildren = if (style.showLabel) listOf(label) else emptyList()
    private var accessibilityChildren: List<View> = labelChildren
    private val clipPath = Path()
    private val content = object : ViewGroup(context) {
        private var iconWidth = 0
        private var iconHeight = 0
        private var iconGap = 0
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            clipToOutline = true
            clipChildren = !style.assignedSize
            clipToPadding = !style.assignedSize
            setWillNotDraw(false)
            if (style.showLabel) addView(label)
        }
        override fun onMeasure(w: Int, h: Int) {
            val horizontal = horizontalPadding()
            val vertical = dp(style.verticalPaddingDp)
            val width = MeasureSpec.getSize(w)
            val height = MeasureSpec.getSize(h)
            val bounded = assignedGeometry || MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED
            val widthBounded = assignedGeometry || MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED
            val innerWidth = if (widthBounded) (width - 2 * horizontal).coerceAtLeast(0) else Int.MAX_VALUE
            val innerHeight = if (bounded) (height - 2 * vertical).coerceAtLeast(0) else Int.MAX_VALUE
            val leadingSize = leadingSize()
            iconWidth = min(leadingSize, innerWidth)
            iconHeight = min(leadingSize, innerHeight)
            iconGap = min(leadingGap(), innerWidth - iconWidth)
            leading?.measure(exact(iconWidth), exact(iconHeight))
            if (style.showLabel) label.measure(if (widthBounded) atMost(innerWidth - iconWidth - iconGap) else w,
                if (bounded) atMost(innerHeight) else h)
            setMeasuredDimension(
                if (assignedGeometry) width else resolveSize(contentWidth(horizontal, iconWidth, iconGap, label.measuredWidth), w),
                if (assignedGeometry) height else resolveSize(max(minimumHeightPx(), 2 * vertical + max(iconHeight, label.measuredHeight)), h),
            )
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            val start = if (style.alignContentStart) {
                if (rtl) (width - horizontalPadding() - iconWidth - iconGap - label.measuredWidth).toFloat()
                else horizontalPadding().toFloat()
            } else (width - iconWidth - iconGap - label.measuredWidth) / 2f
            val x = (if (rtl) start else start + iconWidth + iconGap).roundToInt()
            val y = ((height - label.measuredHeight) / 2f).roundToInt()
            if (style.showLabel) label.layout(x, y, x + label.measuredWidth, y + label.measuredHeight)
            val ix = (if (rtl) start + label.measuredWidth + iconGap else start).roundToInt()
            val iy = ((height - iconHeight) / 2f).roundToInt()
            leading?.layout(ix, iy, ix + iconWidth, iy + iconHeight)
            icon?.setBounds(ix, iy, ix + iconWidth, iy + iconHeight)
        }
        override fun onDraw(canvas: Canvas) {
            icon?.let { it.layoutDirection = layoutDirection; it.draw(canvas) }
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            updateContentClip(w, h)
        }
        override fun draw(canvas: Canvas) {
            if (canvas.isHardwareAccelerated) super.draw(canvas) else {
                val save = canvas.save()
                try { canvas.clipPath(clipPath); super.draw(canvas) } finally { canvas.restoreToCount(save) }
            }
        }
    }
    private val input = NativeMaterialButtonInput(this, content, style.radiusDp, minimumTouchTargetDp, filled, inputPolicy) { elevation = if (joinedSurface) 0f else it }
    override val nativeClickEndpoint get() = input.pointerEndpoint
    override val mergedRowAccessibility = MergedRowAccessibility(this, NativeMergedRowRole.BUTTON,
        { NativeMergedRowAccessibilityState(isEnabled, null, null, accessibilityChildren,
            contentDescription = mergedActionDescription()) },
        { if (canActivate()) { action(); true } else false })

    protected open fun mergedActionDescription(): CharSequence? = null
    private val shape = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            // Refreshes outside onLayout (including failed ones) invalidate its last-layout key.
            // Clear before reading density; only a successful layout invalidation commits a key.
            when {
                view === this@NativeMaterialButtonView -> buttonOutlineGeometry.valid = false
                view === content -> contentOutlineGeometry.valid = false
            }
            outline.setRoundRect(0, 0, view.width, view.height, radius(view.width, view.height))
        }
    }
    private class OutlineGeometry {
        var valid = false
        var width = 0
        var height = 0
        var density = 0f
        var radius = 0f
    }
    private val buttonOutlineGeometry = OutlineGeometry()
    private val contentOutlineGeometry = OutlineGeometry()

    private fun invalidateLayoutOutline(view: View, previous: OutlineGeometry) {
        if (view.outlineProvider !== shape) {
            previous.valid = false
            view.invalidateOutline()
            return
        }
        val width = view.width
        val height = view.height
        val density = layoutDensity
        val radius = radius(width, height)
        val finite = density.isFinite() && radius.isFinite()
        if (finite && previous.valid && previous.width == width && previous.height == height &&
            previous.density == density && previous.radius == radius) return
        previous.valid = false
        view.invalidateOutline()
        previous.width = width
        previous.height = height
        previous.density = density
        previous.radius = radius
        previous.valid = finite
    }
    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
        isFocusable = true
        if (style.assignedSize) defaultFocusHighlightEnabled = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        outlineProvider = shape
        content.outlineProvider = shape
        label.isScreenReaderFocusable = false
        addView(content)
        addView(mergedRowAccessibility.roleView)
        accessibilityDelegate = mergedRowAccessibility
        if (inputPolicy.inputRoot == null) {
            mergedRowAccessibility.enableStandaloneAccessibility({ !closed }) {
                contentDescription ?: if (style.showLabel) label.getText() else
                    leading?.contentDescription?.takeIf { leadingExposed }
            }
        }
        setOnClickListener { if (canActivate()) action() }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        inputReady = true
    }

    override val actionMinimumWidthPx: Int get() = if (joinedLabelGeometry) minimumWidthPx() else dp(minimumTouchTargetDp)

    override fun joinActionSurface() {
        if (joinedSurface) return
        joinedSurface = true
        input.useSquareSurface()
        elevation = 0f
        updateContentClip(content.width, content.height)
        invalidateOutline()
        content.invalidateOutline()
        content.invalidate()
        invalidate()
        requestLayout()
    }

    private fun updateContentClip(w: Int, h: Int) {
        clipPath.rewind()
        val radius = radius(w, h)
        clipPath.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
    }

    fun clipsAccessibilityTextAt(view: View): Boolean = view === content
    fun registerTextClip(register: (View) -> AutoCloseable): AutoCloseable = register(content)

    fun bind(text: String, enabled: Boolean, container: Int, contentColor: Int,
             onClick: () -> Unit = action, indicationColor: Int = contentColor) {
        if (closed) return
        action = onClick
        indicationInk = indicationColor
        if (isEnabled == enabled) input.bind(enabled, indicationInk) else isEnabled = enabled
        isFocusable = enabled
        label.setText(text)
        val motion = colorMotion ?: ViewColorTransition(this, intArrayOf(container, contentColor), ::publishColors)
            .also { colorMotion = it; publishColors() }
        motion.retarget(container, contentColor)
    }

    fun bind(
        text: String,
        enabled: Boolean,
        palette: NativeButtonPalette,
        emphasized: Boolean = true,
        busy: Boolean = false,
        onClick: () -> Unit = action,
    ) {
        setSurfacePreset(palette.surfacePreset)
        val paint = requireNotNull(recipe).paint(enabled, emphasized, busy)
        val fill = paint.container.resolve(palette, paint.containerOpacity)
        val ink = paint.content.resolve(palette, paint.contentOpacity)
        bind(text, enabled, fill, ink, onClick, paint.indication?.resolve(palette) ?: ink)
    }

    internal fun setSurfacePreset(preset: NativeSurfacePreset?) {
        val intensity = preset?.foregroundGrain ?: 0f
        if (grainIntensity == intensity) return
        grainIntensity = intensity
        grain = bindGrainLayer(grain, intensity)
        if (colorMotion == null) onContainerColorChanged?.invoke(paint.color) else publishColors()
    }

    override fun setEnabled(enabled: Boolean) {
        val changed = isEnabled != enabled && inputReady && !closed
        if (changed) input.bind(enabled, indicationInk)
        super.setEnabled(enabled && !closed)
        if (changed && hasLeadingImageTint) publishLeadingImageTint()
    }

    fun bindTypography(textStyle: TextStyle, next: Density, resolver: FontFamily.Resolver) {
        if (closed) return
        if (density != next) requestLayout()
        density = next
        label.setTypography(textStyle, next, resolver)
    }

    fun bindTypography(next: Density, resolver: FontFamily.Resolver) =
        bindTypography(requireNotNull(recipe).labelStyle, next, resolver)

    fun setLeadingIcon(value: Drawable?) {
        if (closed || icon === value) return
        icon = value
        value?.setTint(displayedInk)
        content.requestLayout()
        content.invalidate()
    }

    fun setLeadingView(value: View?, exposeToAccessibility: Boolean = false) {
        if (closed || leading === value && (value == null || leadingExposed == exposeToAccessibility)) return
        val replacing = leading !== value
        if (replacing) releaseLeadingView()
        if (value == null) return
        if (replacing) {
            leading = value
            leadingOriginalImportance = value.importantForAccessibility
        }
        leadingExposed = exposeToAccessibility
        accessibilityChildren = if (exposeToAccessibility) listOf(value) + labelChildren else labelChildren
        value.importantForAccessibility = if (exposeToAccessibility) leadingOriginalImportance
            else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (replacing) content.addView(value, 0)
    }

    /** Keeps parent-sampled ink on an external image and reapplies recipe opacity after enabled changes. */
    fun setLeadingImageTint(@ColorInt color: Int) {
        if (closed) return
        check(leading is ImageView && recipe != null)
        if (hasLeadingImageTint && leadingImageTint == color) return
        hasLeadingImageTint = true
        leadingImageTint = color
        publishLeadingImageTint()
    }

    private fun publishLeadingImageTint() {
        val image = leading as ImageView
        val opacity = checkNotNull(recipe).paint(isEnabled, emphasized = true).contentOpacity
        val tint = if (opacity == null) leadingImageTint else srgbColorWithAlpha(leadingImageTint, opacity)
        if (image.imageTintList?.defaultColor != tint) image.imageTintList = ColorStateList.valueOf(tint)
    }

    private fun releaseLeadingView() {
        val former = leading ?: return
        val importance = leadingOriginalImportance
        leading = null
        hasLeadingImageTint = false
        leadingExposed = false
        accessibilityChildren = labelChildren
        try { content.removeView(former) }
        finally { former.importantForAccessibility = importance }
    }

    private fun publishColors() {
        val colors = colorMotion ?: return
        val fill = colors.color(0)
        var ink = colors.color(1)
        if (colors.isInterpolating && Color.alpha(fill) == 255 && Color.alpha(ink) == 255 &&
            ColorUtils.calculateContrast(ink, fill) < 4.5) {
            ink = if (ColorUtils.calculateContrast(Color.BLACK, fill) >= ColorUtils.calculateContrast(Color.WHITE, fill)) Color.BLACK else Color.WHITE
        }
        if (grainIntensity > 0f) ink = smallthingz.ui.controls.textSurfaceForeground(
            checkNotNull(grain).surfaceRange(fill), ink)
        paint.color = fill
        onContainerColorChanged?.invoke(fill)
        displayedInk = ink
        label.setTextColor(ink)
        icon?.setTint(ink)
        if (icon != null) content.invalidate()
        invalidate()
    }

    fun preferredWidth(compact: Boolean): Int = contentWidth(padding(compact), leadingSize(), leadingGap(),
        if (style.showLabel) label.maxIntrinsicTextWidth() else 0)

    private fun leadingSize(): Int = if (hasLeading) dp(style.iconSizeDp) else 0
    private fun leadingGap(): Int = if (hasLeading && style.showLabel) dp(style.iconGapDp) else 0
    private fun contentWidth(padding: Int, leadingWidth: Int, gap: Int, labelWidth: Int): Int {
        val width = 2 * padding + leadingWidth + gap + labelWidth
        return if (assignedGeometry) width else max(minimumWidthPx(), width)
    }
    fun intrinsicHeight(width: Int): Int {
        // Match content.onMeasure's available label width before an owner fixes the height.
        // Measuring only the padded width underestimates wrapping beside a leading icon.
        val innerWidth = (width - 2 * horizontalPadding()).coerceAtLeast(0)
        val iconWidth = min(leadingSize(), innerWidth)
        val iconGap = min(leadingGap(), innerWidth - iconWidth)
        val labelHeight = if (style.showLabel) {
            label.measure(atMost(innerWidth - iconWidth - iconGap), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            label.measuredHeight
        } else 0
        return max(minimumHeightPx(), max(leadingSize(), labelHeight) + 2 * dp(style.verticalPaddingDp))
    }

    private val layoutDensity get() = if (assignedGeometry) resources.displayMetrics.density else density.density
    private fun dp(value: Int) = (value * layoutDensity).roundToInt()
    private fun minimumWidthPx() = dp(if (joinedLabelGeometry)
        max(joinedActionSurface.minimumWidthDp, minimumTouchTargetDp) else style.minimumWidthDp)
    private fun padding(compact: Boolean) = dp(if (joinedLabelGeometry) joinedActionSurface.horizontalPaddingDp
        else if (compact && filled) 12 else style.horizontalPaddingDp)
    private fun horizontalPadding() = padding(compactPadding)
    private fun minimumHeightPx() = dp(if (joinedSurface) max(style.minimumHeightDp, minimumTouchTargetDp)
        else style.minimumHeightDp)
    private fun radius(w: Int, h: Int): Float = if (joinedSurface) 0f else min(min(w, h) / 2f, style.radiusDp?.times(layoutDensity) ?: Float.MAX_VALUE)
    override fun containsPointerLayer(x: Float, y: Float): Boolean = roundedGroupContains(width.toFloat(), height.toFloat(),
        layoutDensity, radius(width, height) / layoutDensity, x, y)
    override fun onMeasure(w: Int, h: Int) {
        content.measure(w, h)
        setMeasuredDimension(content.measuredWidth, content.measuredHeight)
        mergedRowAccessibility.roleView.measure(exact(measuredWidth), exact(measuredHeight))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content.layout(0, 0, width, height)
        mergedRowAccessibility.roleView.layout(0, 0, width, height)
        invalidateLayoutOutline(this, buttonOutlineGeometry)
        invalidateLayoutOutline(content, contentOutlineGeometry)
        input.measured()
    }
    override fun onDraw(canvas: Canvas) {
        val radius = radius(width, height)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
        grain?.draw(canvas, 0f, 0f, width.toFloat(), height.toFloat(), radius, paint.alpha)
        drawButtonOverlay(canvas, clipPath)
    }
    protected open fun drawButtonOverlay(canvas: Canvas, clip: Path) = Unit
    fun cancelInput() = input.cancel()
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null
    override fun addChildrenForAccessibility(out: ArrayList<View>) = mergedRowAccessibility.addChildrenForAccessibility(out)
    override fun getAccessibilityClassName(): CharSequence = "android.view.View"
    override fun onTouchEvent(event: MotionEvent) = input.touch(event)
    override fun onKeyDown(code: Int, event: KeyEvent) =
        if (style.assignedSize || isNativeClickActivationKey(code)) input.key(event) else super.onKeyDown(code, event)
    override fun onKeyUp(code: Int, event: KeyEvent) =
        if (style.assignedSize || isNativeClickActivationKey(code)) input.key(event) else super.onKeyUp(code, event)
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val native = super.onHoverEvent(event)
        return input.hover(event) || native
    }
    override fun onFocusChanged(focused: Boolean, direction: Int, rect: Rect?) {
        super.onFocusChanged(focused, direction, rect)
        input.focusChanged(focused)
    }
    override fun onCancelPendingInputEvents() {
        super.onCancelPendingInputEvents()
        input.cancel()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (closed) return
        input.attached()
        if (!style.assignedSize) input.focusChanged(isFocused)
    }
    override fun onDetachedFromWindow() {
        input.detached()
        super.onDetachedFromWindow()
    }
    private fun canActivate(): Boolean = !closed && isEnabled && isAttachedToWindow && isShown &&
        nativeSurfaceInputAllowed(this)
    override fun performClick(): Boolean = canActivate() && super.performClick()
    override fun close() {
        if (closed) return
        closed = true
        action = {}
        isEnabled = false
        isFocusable = false
        var failure = closePreservingPrimaryFailure(null, input::cancel)
        failure = closePreservingPrimaryFailure(failure) { colorMotion?.close(); colorMotion = null }
        closePreservingPrimaryFailure(failure, ::releaseLeadingView)?.let { throw it }
    }
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
    private fun atMost(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.AT_MOST)
}
