package smallthingz.ui.components

import android.content.Context
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
import androidx.core.graphics.ColorUtils
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.geometry.roundedGroupGeometry
import smallthingz.ui.input.phase.NativeClickPhaseEndpoint
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The surface owns clipping and sizing. The input host supplies its window's click admission. */
fun interface NativeMaterialButtonInteractionsFactory {
    fun create(host: View, indication: View, filled: Boolean, setElevationPx: (Float) -> Unit): NativeMaterialButtonInteractions
}

interface NativeMaterialButtonInteractions {
    val pointerEndpoint: NativeClickPhaseEndpoint
    val foreground: Drawable
    fun bind(enabled: Boolean, contentColor: Int)
    fun measured(width: Int, height: Int)
    fun touch(event: MotionEvent): Boolean
    fun key(event: KeyEvent): Boolean
    fun hover(event: MotionEvent): Boolean
    fun focusChanged(focused: Boolean)
    fun cancel()
    fun attached()
    fun detached()
}

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

open class NativeMaterialButtonView(
    context: Context,
    private val filled: Boolean,
    textStyle: TextStyle,
    private var density: Density,
    resolver: FontFamily.Resolver,
    private val style: NativeMaterialButtonStyle,
    factory: NativeMaterialButtonInteractionsFactory,
    onClick: () -> Unit,
) : ViewGroup(context), NativeClickPhaseHost, NativePointerLayerShape, NativeMergedRowAccessibilityHost, AutoCloseable {
    private var action = onClick
    private var closed = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var colorMotion: ViewColorTransition? = null
    private var icon: Drawable? = null
    private var leading: View? = null
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
            val bounded = style.assignedSize || MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED
            val widthBounded = style.assignedSize || MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED
            val innerWidth = if (widthBounded) (width - 2 * horizontal).coerceAtLeast(0) else Int.MAX_VALUE
            val innerHeight = if (bounded) (height - 2 * vertical).coerceAtLeast(0) else Int.MAX_VALUE
            val hasIcon = icon != null || leading != null
            iconWidth = if (hasIcon) min(dp(style.iconSizeDp), innerWidth) else 0
            iconHeight = if (hasIcon) min(dp(style.iconSizeDp), innerHeight) else 0
            iconGap = if (hasIcon && style.showLabel) min(dp(style.iconGapDp), innerWidth - iconWidth) else 0
            leading?.measure(exact(iconWidth), exact(iconHeight))
            if (style.showLabel) label.measure(if (widthBounded) atMost(innerWidth - iconWidth - iconGap) else w,
                if (bounded) atMost(innerHeight) else h)
            setMeasuredDimension(
                if (style.assignedSize) width else resolveSize(max(dp(style.minimumWidthDp), 2 * horizontal + iconWidth + iconGap + label.measuredWidth), w),
                if (style.assignedSize) height else resolveSize(max(dp(style.minimumHeightDp), 2 * vertical + max(iconHeight, label.measuredHeight)), h),
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
            clipPath.rewind()
            val radius = radius(w, h)
            clipPath.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
        }
        override fun draw(canvas: Canvas) {
            if (canvas.isHardwareAccelerated) super.draw(canvas) else {
                val save = canvas.save()
                try { canvas.clipPath(clipPath); super.draw(canvas) } finally { canvas.restoreToCount(save) }
            }
        }
    }
    private val input = factory.create(this, content, filled) { elevation = it }
    override val nativeClickEndpoint get() = input.pointerEndpoint
    override val mergedRowAccessibility = MergedRowAccessibility(this, NativeMergedRowRole.BUTTON,
        { NativeMergedRowAccessibilityState(isEnabled, null, null, accessibilityChildren) },
        { if (canActivate()) { action(); true } else false })
    private val shape = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius(view.width, view.height))
        }
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
        content.foreground = input.foreground
        label.isScreenReaderFocusable = false
        addView(content)
        addView(mergedRowAccessibility.roleView)
        accessibilityDelegate = mergedRowAccessibility
        setOnClickListener { if (canActivate()) action() }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        inputReady = true
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

    override fun setEnabled(enabled: Boolean) {
        if (isEnabled != enabled && inputReady && !closed) input.bind(enabled, indicationInk)
        super.setEnabled(enabled && !closed)
    }

    fun bindTypography(textStyle: TextStyle, next: Density, resolver: FontFamily.Resolver) {
        if (closed) return
        if (density != next) requestLayout()
        density = next
        label.setTypography(textStyle, next, resolver)
    }

    fun setLeadingIcon(value: Drawable?) {
        if (closed || icon === value) return
        icon = value
        value?.setTint(displayedInk)
        content.requestLayout()
        content.invalidate()
    }

    fun setLeadingView(value: View?, exposeToAccessibility: Boolean = false) {
        if (closed || leading === value) return
        leading?.let(content::removeView)
        leading = value
        accessibilityChildren = if (exposeToAccessibility && value != null) listOf(value) + labelChildren else labelChildren
        value?.let {
            if (!exposeToAccessibility) it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            content.addView(it, 0)
        }
    }

    private fun publishColors() {
        val colors = colorMotion ?: return
        val fill = colors.color(0)
        var ink = colors.color(1)
        if (colors.isInterpolating && Color.alpha(fill) == 255 && Color.alpha(ink) == 255 &&
            ColorUtils.calculateContrast(ink, fill) < 4.5) {
            ink = if (ColorUtils.calculateContrast(Color.BLACK, fill) >= ColorUtils.calculateContrast(Color.WHITE, fill)) Color.BLACK else Color.WHITE
        }
        paint.color = fill
        displayedInk = ink
        label.setTextColor(ink)
        icon?.setTint(ink)
        if (icon != null) content.invalidate()
        invalidate()
    }

    fun preferredWidth(compact: Boolean): Int = label.maxIntrinsicTextWidth() + 2 * padding(compact)
    fun intrinsicHeight(width: Int): Int {
        label.measure(atMost((width - 2 * horizontalPadding()).coerceAtLeast(0)), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return max(dp(style.minimumHeightDp), label.measuredHeight + 2 * dp(style.verticalPaddingDp))
    }

    private val layoutDensity get() = if (style.assignedSize) resources.displayMetrics.density else density.density
    private fun dp(value: Int) = (value * layoutDensity).roundToInt()
    private fun padding(compact: Boolean) = dp(if (compact && filled) 12 else style.horizontalPaddingDp)
    private fun horizontalPadding() = padding(compactPadding)
    private fun radius(w: Int, h: Int): Float = min(min(w, h) / 2f, style.radiusDp?.times(layoutDensity) ?: Float.MAX_VALUE)
    override fun containsPointerLayer(x: Float, y: Float): Boolean = roundedGroupGeometry(width.toFloat(), height.toFloat(),
        layoutDensity, radius(width, height) / layoutDensity).contains(x, y)
    override fun onMeasure(w: Int, h: Int) {
        content.measure(w, h)
        setMeasuredDimension(content.measuredWidth, content.measuredHeight)
        mergedRowAccessibility.roleView.measure(exact(measuredWidth), exact(measuredHeight))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content.layout(0, 0, width, height)
        mergedRowAccessibility.roleView.layout(0, 0, width, height)
        invalidateOutline()
        content.invalidateOutline()
        input.measured(width, height)
    }
    override fun onDraw(canvas: Canvas) {
        val radius = radius(width, height)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
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
    private fun canActivate(): Boolean = !closed && isEnabled && isAttachedToWindow && isShown
    override fun performClick(): Boolean = canActivate() && super.performClick()
    override fun close() {
        if (closed) return
        closed = true
        action = {}
        isEnabled = false
        isFocusable = false
        val failure = closePreservingPrimaryFailure(null, input::cancel)
        closePreservingPrimaryFailure(failure) { colorMotion?.close(); colorMotion = null }?.let { throw it }
    }
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
    private fun atMost(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.AT_MOST)
}
