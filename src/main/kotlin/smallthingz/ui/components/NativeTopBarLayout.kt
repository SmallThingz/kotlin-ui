package smallthingz.ui.components

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/** Explicit48dp minimum-target routing; never a permanent whole-screen touch delegate. */
open class NativeTopBarLayout(context: Context, initialStyle: NativeToolbarStyle = NativeToolbarStyle.Default) : ViewGroup(context) {
    protected var touchDensity = resources.displayMetrics.density
    protected var toolbarStyle = initialStyle
        private set
    private var delegated: View? = null
    init { clipChildren = false; clipToPadding = false }
    enum class Slot { START, CENTER, END }
    class ActionLayoutParams(val slot: Slot, val minimumVisualOffset: Boolean = false) :
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private var topInsetPx = 0
    fun bindMetrics(density: Float, topInsetPx: Int = 0, contentHeightDp: Float = NativeToolbarStyle.Default.compactHeightDp) =
        bindStyle(density, topInsetPx, if (contentHeightDp == toolbarStyle.compactHeightDp) toolbarStyle else
            toolbarStyle.copy(compactHeightDp = contentHeightDp))

    fun bindStyle(density: Float, topInsetPx: Int, style: NativeToolbarStyle) {
        if (touchDensity == density && this.topInsetPx == topInsetPx && toolbarStyle == style) return
        touchDensity = density
        this.topInsetPx = topInsetPx
        toolbarStyle = style
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (toolbarStyle.action.sizeDp * touchDensity).roundToInt()
        for (index in 0 until childCount) getChildAt(index).measure(topBarExact(size), topBarExact(size))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(topInsetPx + toolbarStyle.compactHeightPx(touchDensity), heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val inset = (toolbarStyle.compactEdgeInsetDp * touchDensity).roundToInt()
        val size = (toolbarStyle.action.sizeDp * touchDensity).roundToInt()
        val y = topInsetPx + ((height - topInsetPx - size) / 2f).roundToInt()
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val params = child.layoutParams as? ActionLayoutParams
            val logicalX = when (params?.slot ?: Slot.START) {
                Slot.START -> inset
                Slot.CENTER -> ((width - size) / 2f).roundToInt()
                Slot.END -> width - inset - size
            }
            // A centered visual retains physical centering even at odd RTL widths.
            val x = if (params?.slot == Slot.CENTER || layoutDirection != LAYOUT_DIRECTION_RTL) logicalX
                else width - logicalX - size
            val residual = if (params?.minimumVisualOffset == true)
                nativeMinimumInteractiveVisualOffset(size, (toolbarStyle.action.minimumTargetDp * touchDensity).roundToInt()) else 0
            child.layout(x + residual, y + residual, x + residual + size, y + residual + size)
        }
    }
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = ActionLayoutParams(Slot.START)
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            delegated = null
            val eligible = ArrayList<View>(childCount)
            val bounds = ArrayList<NativeTopBarHitBounds>(childCount)
            for (index in childCount - 1 downTo 0) {
                val child = getChildAt(index)
                if (child.visibility != VISIBLE) continue
                val shape = (child as? NativeTopBarHitTarget)?.hitBounds() ?: continue
                eligible += child
                bounds += shape
            }
            val hit = nativeTopBarHitIndex(bounds, event.x, event.y,
                event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, toolbarStyle.action.minimumTargetDp * touchDensity)
            delegated = hit?.let(eligible::get)
        }
        val target = delegated ?: return super.dispatchTouchEvent(event)
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(-target.left.toFloat(), -target.top.toFloat())
        val handled = try { target.dispatchTouchEvent(copy) } finally { copy.recycle() }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) delegated = null
        return handled
    }
    override fun onDetachedFromWindow() { delegated = null; super.onDetachedFromWindow() }
}

private fun topBarExact(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
