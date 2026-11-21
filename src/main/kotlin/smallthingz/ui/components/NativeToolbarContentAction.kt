package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import smallthingz.ui.geometry.roundedGroupContains
import smallthingz.ui.geometry.roundedGroupGeometry
import smallthingz.ui.input.phase.NativeClickPhaseEndpoint
import smallthingz.ui.input.phase.NativeClickPhaseHost
import smallthingz.ui.input.phase.NativePointerLayerShape
import smallthingz.ui.input.phase.ViewClickPhaseEndpoint
import smallthingz.ui.interaction.view.NativeClickBinding
import smallthingz.ui.interaction.view.NativeClickEnvironment
import smallthingz.ui.interaction.view.RowIndication
import smallthingz.ui.interaction.view.RowIndicationSink
import smallthingz.ui.interaction.view.ViewClickController
import smallthingz.ui.interaction.view.isNativeClickActivationKey
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import smallthingz.ui.text.view.MergedRowAccessibility
import smallthingz.ui.text.view.NativeActionDescriptionView
import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost
import smallthingz.ui.text.view.NativeMergedRowAccessibilityState
import smallthingz.ui.text.view.NativeMergedRowRole
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import smallthingz.ui.text.view.bindActionDescriptionAccessibility
import kotlin.math.min

/** A round action around caller-owned content, with one retained semantics and input owner. */
open class NativeToolbarContentAction(
    context: Context,
    private val content: View,
    description: String,
    inputRoot: View,
    private var density: Float,
    private var minimumTargetDp: Float,
    initialIndicationColor: Int,
) : ViewGroup(context), NativeClickPhaseHost, NativePointerLayerShape,
    NativeMergedRowAccessibilityHost, NativeTopBarHitTarget {
    override fun hitBounds() = NativeTopBarHitBounds(left.toFloat(), top.toFloat(), width.toFloat(), height.toFloat(), min(width, height) / 2f)
    private val identity = Any()
    private val descriptionView = NativeActionDescriptionView(context, description)
    private val accessibilityChildren = listOf(descriptionView)
    override val mergedRowAccessibility = MergedRowAccessibility(
        this, NativeMergedRowRole.BUTTON,
        sourceState = { NativeMergedRowAccessibilityState(isEnabled, null, null,
            accessibilityChildren, rolePresent = false) },
        invokeSourceClick = { click?.let { it(); true } ?: false },
    )
    private val softwareClip = Path()
    private var click: (() -> Unit)? = null
    private val indication = RowIndication.install(this, initialIndicationColor, { readCachedAnimatorDurationScale(context) })
    private val controller = ViewClickController(this, object : NativeClickEnvironment {
        override val nativeInputRoot: View = inputRoot
        override val minimumTouchWidthPx get() = minimumTargetDp * density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent) = roundedGroupGeometry(width.toFloat(), height.toFloat(),
            density, min(width, height) / (2f * density))
            .acceptsPointer(event.x, event.y, event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, minimumTouchWidthPx)
        override fun interestedParent(event: MotionEvent) = false
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, RowIndicationSink(indication) { x, y, point -> point.set(x, y) })
    override val nativeClickEndpoint: NativeClickPhaseEndpoint = ViewClickPhaseEndpoint(controller)
    override fun containsPointerLayer(x: Float, y: Float) =
        roundedGroupContains(width.toFloat(), height.toFloat(), density,
            min(width, height) / (2f * density), x, y)
    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityDelegate = mergedRowAccessibility
        addView(content)
        addView(descriptionView)
        setOnClickListener { click?.invoke() }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, width, height, min(width, height) / 2f)
            }
        }
    }
    fun bindAction(density: Float, minimumTargetDp: Float, indicationColor: Int, callback: () -> Unit) {
        if (this.density != density) indication.onMeasured(width, height, density)
        this.density = density
        this.minimumTargetDp = minimumTargetDp
        click = callback
        indication.updateColor(indicationColor)
        controller.bind(NativeClickBinding(identity, true, onClick = { performClick() }))
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        content.measure(widthMeasureSpec, heightMeasureSpec)
        descriptionView.measure(contentActionExact(content.measuredWidth), contentActionExact(content.measuredHeight))
        setMeasuredDimension(content.measuredWidth, content.measuredHeight)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        content.layout(0, 0, width, height)
        descriptionView.layout(0, 0, width, height)
    }
    fun bindAccessibility(cursor: PlainTextAccessibilityWindowScope,
        geometry: NativePlainTextViewGeometry, sourceNode: (View) -> NativeSourceAccessibilityNode): AutoCloseable =
        bindActionDescriptionAccessibility(this, mergedRowAccessibility, descriptionView, cursor, geometry, sourceNode)
    override fun addChildrenForAccessibility(outChildren: ArrayList<View>) =
        mergedRowAccessibility.addChildrenForAccessibility(outChildren)
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        indication.onMeasured(w, h, density)
        softwareClip.rewind()
        softwareClip.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), min(w, h) / 2f, min(w, h) / 2f, Path.Direction.CW)
        invalidateOutline()
    }
    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) super.draw(canvas) else {
            val checkpoint = canvas.save()
            try { canvas.clipPath(softwareClip); super.draw(canvas) }
            finally { canvas.restoreToCount(checkpoint) }
        }
    }
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

private fun contentActionExact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
