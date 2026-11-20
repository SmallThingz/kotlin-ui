package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Rect
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.nativeMergedRoleBoundsInScreen
import kotlin.math.roundToInt

/** Shared layout, line painting and accessibility for the two linear progress modes. */
abstract class NativeLinearProgressView internal constructor(
    context: Context,
    indicatorColor: Int,
    trackColor: Int,
    density: Float,
) : View(context) {
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    protected val colors = ViewColorTransition(this, intArrayOf(indicatorColor, trackColor), { invalidate() })
    protected var density = density
        private set
    protected abstract val accessibilityProgress: Float?
    private var geometry: NativePlainTextViewGeometry? = null
    private var exposure: (() -> Boolean)? = null
    private var geometryToken: Any? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this, true)
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    protected fun bindAppearance(indicatorColor: Int, trackColor: Int, density: Float): Boolean {
        val densityChanged = this.density != density
        this.density = density
        colors.retarget(indicatorColor, trackColor)
        return densityChanged
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize((240f * density).roundToInt(), w),
            resolveSize((4f * density).roundToInt(), h))
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        invalidate()
    }

    protected fun drawProgressLine(canvas: Canvas, start: Float, end: Float, color: Int) {
        paint.color = color
        paint.strokeWidth = height.toFloat()
        paint.style = Paint.Style.STROKE
        nativeLinearProgressLine(width.toFloat(), height.toFloat(), layoutDirection == LAYOUT_DIRECTION_RTL,
            start, end) { a, b, y, round ->
            paint.strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT
            canvas.drawLine(a, y, b, y, paint)
        }
    }

    /** IncreaseVerticalSemanticsBounds applies 10 dp negative padding before clipping/mapping. */
    fun semanticsBoundsInView(): Rect {
        val padding = (10f * density).roundToInt().toFloat()
        return Rect(0f, -padding, width.toFloat(), height + padding)
    }

    fun bindAccessibility(value: NativePlainTextViewGeometry, exposed: () -> Boolean): AutoCloseable {
        val token = Any()
        geometryToken = token
        geometry = value
        exposure = exposed
        return AutoCloseable {
            if (geometryToken === token) { geometryToken = null; geometry = null; exposure = null }
        }
    }

    internal fun sourceNode(): NativeSourceAccessibilityNode? {
        val mapping = geometry ?: return null
        val captured = mapping.capture(this, Offset.Zero, semanticsBoundsInView())
        val bounds = captured?.let { nativeMergedRoleBoundsInScreen(it, isLaidOut) } ?: android.graphics.Rect()
        var hidden = exposure?.invoke() != true
        var ancestor: View? = this
        while (ancestor != null) {
            if (ancestor.alpha == 0f || ancestor.visibility != VISIBLE) hidden = true
            ancestor = ancestor.parent as? View
        }
        val present = isAttachedToWindow && isShown && captured != null && !captured.visibleBoundsInRoot.isEmpty
        return NativeSourceAccessibilityNode(present, hidden, present && !hidden, bounds)
    }

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (sourceNode()?.present == false) null else super.createAccessibilityNodeInfo()

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.ProgressBar"
        info.isEnabled = true
        info.isFocusable = false
        info.isFocused = false
        info.isClickable = false
        info.isLongClickable = false
        info.isSelected = false
        info.isCheckable = false
        info.isChecked = false
        info.text = null
        info.contentDescription = null
        info.setBoundsInParent(android.graphics.Rect())
        val progress = accessibilityProgress
        info.rangeInfo = progress?.let {
            AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 1f, it)
        }
        AccessibilityNodeInfoCompat.wrap(info).stateDescription = if (progress == null) {
            context.getString(smallthingz.ui.R.string.in_progress)
        } else {
            context.getString(smallthingz.ui.R.string.template_percent, calibrationProgressPercent(progress))
        }
        val actions = info.actionList
        for (index in actions.size - 1 downTo 0) info.removeAction(actions[index])
        info.addAction(if (isAccessibilityFocused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        sourceNode()?.populate(info)
    }

    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> super.performAccessibilityAction(action, args)
        else -> false
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.className = "android.view.View"
        event.isEnabled = true
        event.text.clear()
        event.contentDescription = null
    }
}
