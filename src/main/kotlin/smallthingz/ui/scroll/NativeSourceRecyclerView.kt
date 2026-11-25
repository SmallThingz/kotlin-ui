/*
 * Source-derived from foundation 1.11.2 LazyListState/Scrollable/DragGestureNode.
 * AndroidX portions copyright The Android Open Source Project, Apache-2.0.
 * Reverb application integration is retained under the Reverb project GPL-3.0 license.
 */
package smallthingz.ui.scroll

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.recyclerview.widget.RecyclerView
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import kotlin.math.roundToInt

private const val sourceOutlineGutterDp = 30f

/** Actual RV virtualization; native pointer dispatch does not run RV's alternate drag/fling path. */
class NativeSourceRecyclerView(context: Context) : RecyclerView(context) {
    private var scrollIndicator: NativeScrollIndicator? = null
    private var requestedWillNotDraw = willNotDraw()
    var sourceOverscroll: NativeVerticalOverscroll? = null
    var sourceAccessibilityNode: (() -> NativeSourceAccessibilityNode)? = null
    var sourceAccessibilityChildren: (() -> List<View>)? = null
    var sourceSemanticsScrollBy: ((Float) -> Boolean)? = null
    var sourceStopScroll: (() -> Unit)? = null
    var sourceOnUserScroll: (() -> Unit)? = null
    var sourceAccessibilityEventsBound=false
    override fun sendAccessibilityEventUnchecked(event:AccessibilityEvent) {
        if(sourceAccessibilityEventsBound && (event.eventType==AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event.eventType==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))return
        super.sendAccessibilityEventUnchecked(event)
    }
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if(sourceAccessibilityNode?.invoke()?.present==false)null else super.createAccessibilityNodeInfo()
    override fun addChildrenForAccessibility(out:ArrayList<View>) {
        val children=sourceAccessibilityChildren
        if(children==null)super.addChildrenForAccessibility(out)else out.addAll(children())
    }
    init {
        overScrollMode = OVER_SCROLL_NEVER
        isNestedScrollingEnabled = false // Shared source nested-scroll chain owns pre/self/post.
        clipChildren = false
        clipToPadding = false
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val extra = sourceOutlineGutter(resources.displayMetrics.density)
                outline.setRect(-extra, 0, width + extra, height)
            }
        }
    }
    fun containsSourcePointer(x: Float, y: Float, density: Float): Boolean {
        val extra = sourceOutlineGutter(density)
        return x >= -extra && x < width + extra && y >= 0f && y < height
    }

    private fun sourceOutlineGutter(density: Float) = (sourceOutlineGutterDp * density).roundToInt()

    /** Binds a passive vertical thumb. A null style removes it. */
    fun bindScrollIndicator(style: NativeScrollIndicatorStyle?, color: Int) {
        val indicator = scrollIndicator
        when {
            style == null -> {
                if (indicator == null) return
                scrollIndicator = null
                super.setWillNotDraw(requestedWillNotDraw)
            }
            indicator == null -> {
                scrollIndicator = NativeScrollIndicator(style, color)
                super.setWillNotDraw(false)
            }
            !indicator.bind(style, color) -> return
        }
        invalidate()
    }

    /** Accepts the current painted page range during an existing theme transition. */
    internal fun bindScrollIndicatorTransitionUnderlay(surface: Long) {
        if (scrollIndicator?.bindTransitionUnderlay(surface) == true) invalidate()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val manager = layoutManager
        if (isEnabled && !isLayoutSuppressed && manager != null &&
            event.actionMasked == MotionEvent.ACTION_SCROLL && manager.canScrollVertically()) {
            val axis = when {
                event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) -> MotionEvent.AXIS_VSCROLL
                event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) -> MotionEvent.AXIS_SCROLL
                else -> null
            }
            // RecyclerView may scroll and still return false; revoke the reveal before delegation.
            if (axis != null && event.getAxisValue(axis) != 0f) sourceOnUserScroll?.invoke()
        }
        return super.onGenericMotionEvent(event)
    }

    override fun draw(canvas: Canvas) {
        val edge = sourceOverscroll
        if (edge == null) super.draw(canvas) else edge.draw(canvas) { super.draw(it) }
        scrollIndicator?.draw(canvas, this)
    }

    override fun setWillNotDraw(willNotDraw: Boolean) {
        // RecyclerView can request draw suppression again when its last decoration is removed.
        requestedWillNotDraw = willNotDraw
        super.setWillNotDraw(willNotDraw && scrollIndicator == null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sourceOverscroll?.measured(w, h)
    }
}
