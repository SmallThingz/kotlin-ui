package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.geometry.roundedGroupContains
import smallthingz.ui.geometry.roundedGroupGeometry
import smallthingz.ui.input.phase.*
import smallthingz.ui.interaction.view.*
import smallthingz.ui.text.view.*
import kotlin.math.min
import kotlin.math.roundToInt

/** Rounded toolbar action with a retained semantic icon and independent48dp input target. */
open class NativeTopBarAction(
    context: Context,
    private var density: Float,
    inputRoot: () -> View?,
    description: String,
    private val selectionWrapper: Boolean = false,
    private val sharedSurface: Boolean = false,
    rolePresent: Boolean = false,
    initialTint: Int,
    onPaintedSurfaceChanged: (View) -> Unit = {},
    foreground: (View, Int) -> Int = { _, tint -> tint },
) : ViewGroup(context), NativeTopBarHitTarget {
    /** Caller-owned glyph/branding. Geometry, input and semantics stay owned by this action. */
    var iconPainter: ((Canvas, Rect, Int) -> Unit)? = null
        set(value) { field = value; leaf.invalidate() }
    private val surface = if (sharedSurface) null else NativeGroupLayout(context, 15f,
        onPaintedSurfaceChanged = onPaintedSurfaceChanged)
    private val leaf = NativeTopBarActionLeaf(context, density, inputRoot, description,
        if (sharedSurface) 0f else 15f, rolePresent, initialTint, foreground,
        { canvas, bounds, tint -> iconPainter?.invoke(canvas, bounds, tint) })
    val accessibilityAction: View get() = leaf
    val visual: View get() = leaf
    val sampledIconTint: Int get() = leaf.sampledTint
    init {
        clipChildren = false; clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (surface == null) addView(leaf) else {
            surface.addRow(leaf, NativeGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(surface)
        }
    }
    fun bindAction(density: Float, enabled: Boolean, tint: Int, indicationColor: Int, onClick: () -> Unit) {
        this.density = density
        leaf.bind(density, enabled, tint, indicationColor, onClick)
    }
    fun setSurfacePaletteImmediately(fill: Int, border: Int) { surface?.setPaletteImmediately(fill, border) }
    fun setDescription(value: String) = leaf.setDescription(value)
    fun cancelInput() = leaf.cancelInput()
    fun invalidateTextSurfaceInk() = leaf.invalidate()
    fun bindAccessibility(cursor: PlainTextAccessibilityWindowScope,
        geometry: NativePlainTextViewGeometry, sourceNode: (View) -> NativeSourceAccessibilityNode): AutoCloseable =
        leaf.bindAccessibility(cursor, geometry, sourceNode)
    fun sourceClip(view: View): NativeTextSemanticsClip? = when (view) {
        surface -> NativeTextSemanticsClip.Rectangle(smallthingz.ui.geometry.Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
        this, leaf -> NativeTextSemanticsClip.Unclipped
        else -> null
    }
    override fun hitBounds() = NativeTopBarHitBounds(
        left + (surface?.left ?: 0).toFloat(), top + (surface?.top ?: 0).toFloat(),
        (surface?.width ?: width).toFloat(), (surface?.height ?: height).toFloat(),
        if (sharedSurface) 0f else 15f * density,
    )
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val outer = ((if (selectionWrapper) 48f else 46f) * density).roundToInt()
        val visual = ((if (sharedSurface) 48f else 46f) * density).roundToInt()
        (surface ?: leaf).measure(actionExact(visual), actionExact(visual))
        setMeasuredDimension(resolveSize(outer, widthMeasureSpec), resolveSize(outer, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (surface == null) { leaf.layout(0, 0, width, height); return }
        val minimum = (48f * density).roundToInt()
        val x = ((width - surface.measuredWidth) / 2f).roundToInt() +
            nativeMinimumInteractiveVisualOffset(surface.measuredWidth, minimum)
        val y = ((height - surface.measuredHeight) / 2f).roundToInt() +
            nativeMinimumInteractiveVisualOffset(surface.measuredHeight, minimum)
        surface.layout(x, y, x + surface.measuredWidth, y + surface.measuredHeight)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(-(surface?.left ?: 0).toFloat(), -(surface?.top ?: 0).toFloat())
        return try { leaf.dispatchTouchEvent(copy) } finally { copy.recycle() }
    }
}

private class NativeTopBarActionLeaf(
    context: Context,
    private var density: Float,
    inputRoot: () -> View?,
    description: String,
    private val cornerRadiusDp: Float,
    private val rolePresent: Boolean,
    initialTint: Int,
    private val foreground: (View, Int) -> Int,
    private val paintIcon: (Canvas, Rect, Int) -> Unit,
) : ViewGroup(context), NativeClickPhaseHost, NativePointerLayerShape, NativeMergedRowAccessibilityHost {
    private val identity = Any()
    private var click: (() -> Unit)? = null
    private val iconBounds = Rect()
    private val colorMotion = ViewColorTransition(this, intArrayOf(initialTint), ::invalidate)
    val sampledTint: Int get() = colorMotion.color(0)
    private val descriptionView = NativeActionDescriptionView(context, description)
    private val accessibilityChildren = listOf(descriptionView)
    override val mergedRowAccessibility = MergedRowAccessibility(
        this, NativeMergedRowRole.BUTTON,
        sourceState = { NativeMergedRowAccessibilityState(isEnabled, null, null,
            accessibilityChildren, rolePresent = rolePresent) },
        invokeSourceClick = { if (isEnabled && click != null) { click?.invoke(); true } else false },
    )
    private val indication = RowIndication.install(this, initialTint, { readCachedAnimatorDurationScale(context) })
    private val controller = ViewClickController(this, object : NativeClickEnvironment {
        override val nativeInputRoot: View? get() = inputRoot()
        override val minimumTouchWidthPx get() = 48f * density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent) = roundedGroupGeometry(width.toFloat(), height.toFloat(),
            density, cornerRadiusDp).acceptsPointer(event.x, event.y,
            event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, minimumTouchWidthPx)
        override fun interestedParent(event: MotionEvent) = false
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, RowIndicationSink(indication) { x, y, point -> point.set(x, y) })
    override val nativeClickEndpoint: NativeClickPhaseEndpoint = ViewClickPhaseEndpoint(controller)
    override fun containsPointerLayer(x: Float, y: Float) =
        roundedGroupContains(width.toFloat(), height.toFloat(), density, cornerRadiusDp, x, y)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        // The actual Icon child owns the description; Settings retains its explicit button role.
        isClickable = true
        setWillNotDraw(false)
        accessibilityDelegate = mergedRowAccessibility
        addView(descriptionView)
        if (rolePresent) addView(mergedRowAccessibility.roleView)
        setOnClickListener { if (isEnabled) click?.invoke() }
    }
    fun bind(nextDensity: Float, enabled: Boolean, tint: Int, indicationColor: Int, callback: () -> Unit) {
        if (density != nextDensity) requestLayout()
        density = nextDensity
        click = callback
        isEnabled = enabled
        isFocusable = enabled
        colorMotion.retarget(if (enabled) tint else (tint and 0x00FFFFFF) or (97 shl 24))
        indication.updateColor(indicationColor)
        controller.bind(NativeClickBinding(identity, enabled, onClick = { performClick() }))
        updateGeometry(width, height)
        invalidate()
    }
    fun setDescription(value: String) { descriptionView.contentDescription = value }
    fun cancelInput() = controller.cancel()
    fun bindAccessibility(cursor: PlainTextAccessibilityWindowScope,
        geometry: NativePlainTextViewGeometry, sourceNode: (View) -> NativeSourceAccessibilityNode): AutoCloseable =
        bindActionDescriptionAccessibility(this, mergedRowAccessibility, descriptionView, cursor, geometry, sourceNode)
    override fun addChildrenForAccessibility(outChildren: ArrayList<View>) =
        mergedRowAccessibility.addChildrenForAccessibility(outChildren)
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = (23f * density).roundToInt()
        descriptionView.measure(actionExact(min(size, w)), actionExact(min(size, h)))
        if (rolePresent) mergedRowAccessibility.roleView.measure(actionExact(w), actionExact(h))
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = ((width - descriptionView.measuredWidth) / 2f).roundToInt()
        val y = ((height - descriptionView.measuredHeight) / 2f).roundToInt()
        descriptionView.layout(x, y, x + descriptionView.measuredWidth, y + descriptionView.measuredHeight)
        if (rolePresent) mergedRowAccessibility.roleView.layout(0, 0, width, height)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateGeometry(w, h)
    private fun updateGeometry(w: Int, h: Int) {
        val size = (23f * density).roundToInt()
        val glyphWidth = min(size, w)
        val glyphHeight = min(size, h)
        val x = ((w - glyphWidth) / 2f).roundToInt()
        val y = ((h - glyphHeight) / 2f).roundToInt()
        iconBounds.set(x, y, x + glyphWidth, y + glyphHeight)
        indication.onMeasured(w, h, density)
    }
    override fun onDraw(canvas: Canvas) = paintIcon(canvas, iconBounds, foreground(this, sampledTint))
    override fun onRtlPropertiesChanged(layoutDirection: Int) { super.onRtlPropertiesChanged(layoutDirection); invalidate() }
    override fun onTouchEvent(event: MotionEvent) = controller.touch(event)
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean =
        if (isNativeClickActivationKey(code)) controller.key(event) else super.onKeyDown(code, event)
    override fun onKeyUp(code: Int, event: KeyEvent): Boolean =
        if (isNativeClickActivationKey(code)) controller.key(event) else super.onKeyUp(code, event)
    // The new non-interactive semantics child must not intercept the parent's hover observer.
    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchHoverEvent(event)
        return controller.hover(event) || handled
    }
    override fun onFocusChanged(focused: Boolean, direction: Int, rect: Rect?) { super.onFocusChanged(focused, direction, rect); controller.focusChanged(focused) }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller.attached()
        controller.bind(if (click == null) null else NativeClickBinding(identity, isEnabled, onClick = { performClick() }))
        controller.focusChanged(isFocused)
    }
    override fun onDetachedFromWindow() { controller.detached(); super.onDetachedFromWindow() }
    override fun getAccessibilityClassName(): CharSequence = "android.view.View"
}

private fun actionExact(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
