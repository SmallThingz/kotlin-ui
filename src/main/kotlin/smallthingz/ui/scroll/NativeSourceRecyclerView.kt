/*
 * Source-derived from foundation 1.11.2 LazyListState/Scrollable/DragGestureNode.
 * AndroidX portions copyright The Android Open Source Project, Apache-2.0.
 * Reverb application integration is retained under the Reverb project GPL-3.0 license.
 */
package smallthingz.ui.scroll

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativePointerCoordinates
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import kotlin.math.roundToInt

private const val sourceOutlineGutterDp = 30f

/** Actual RV virtualization; native pointer dispatch does not run RV's alternate drag/fling path. */
class NativeSourceRecyclerView(context: Context) : RecyclerView(context) {
    private var scrollIndicator: NativeScrollIndicator? = null
    private var indicatorNode: ScrollIndicatorPointerNode? = null
    private var coordinatedIndicatorLayout: LinearLayoutManager? = null
    private var pendingIndicatorPlacement: NativeScrollIndicatorPlacement? = null
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

    /** Binds a fading vertical thumb. A null style removes it. */
    fun bindScrollIndicator(style: NativeScrollIndicatorStyle?, color: Int) {
        val indicator = scrollIndicator
        when {
            style == null -> {
                if (indicator == null) return
                resetScrollIndicator()
                scrollIndicator = null
                if (Build.VERSION.SDK_INT >= 29) {
                    isVerticalScrollBarEnabled = false
                    verticalScrollbarThumbDrawable = null
                }
                super.setWillNotDraw(requestedWillNotDraw)
            }
            indicator == null -> {
                val drawable = NativeScrollIndicator(this, style, color)
                scrollIndicator = drawable
                if (Build.VERSION.SDK_INT >= 29) {
                    scrollBarStyle = SCROLLBARS_OUTSIDE_OVERLAY
                    verticalScrollbarPosition = SCROLLBAR_POSITION_RIGHT
                    verticalScrollbarTrackDrawable = null
                    verticalScrollbarThumbDrawable = drawable
                    isScrollbarFadingEnabled = true
                    isVerticalScrollBarEnabled = true
                } else {
                    isVerticalScrollBarEnabled = false
                }
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

    /** Retained panels that hide by alpha or translation should publish their presentation state. */
    var scrollIndicatorPresentationActive = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                resetScrollIndicator()
                if (windowVisibility == VISIBLE && isShown) invalidate()
            }
        }

    internal val scrollIndicatorPresented: Boolean
        get() = scrollIndicatorPresentationActive && (!isAttachedToWindow || windowVisibility == VISIBLE)

    /** Whether this viewport currently owns a pointer through its scrollbar node. */
    val isScrollIndicatorDragging: Boolean get() = indicatorNode?.dragging == true

    /** Local coordinates for an exclusive thumb hit path in the source pointer tree. */
    fun canStartScrollIndicatorDrag(x: Float, y: Float): Boolean =
        scrollIndicatorPresented && !isScrollIndicatorDragging && isEnabled && !isLayoutSuppressed && isShown &&
            scrollIndicator?.contains(x, y) == true

    val scrollIndicatorPointerNode: NativeViewPhasePointerNode
        get() = indicatorNode ?: ScrollIndicatorPointerNode().also { indicatorNode = it }

    /** An accepted thumb move may still own the next layout after its pointer lifts. */
    val hasPendingScrollIndicatorPlacement: Boolean get() = pendingIndicatorPlacement != null

    /**
     * Call from the current manager's non-prelayout onLayoutChildren, ahead of automatic anchors.
     * Participating managers accept deferred thumb placement using this layout's actual item count.
     */
    fun applyPendingScrollIndicatorPlacement(layout: LinearLayoutManager, itemCount: Int): Boolean {
        if (layoutManager !== layout) return false
        coordinatedIndicatorLayout = layout
        val placement = pendingIndicatorPlacement ?: return false
        cancelPendingScrollIndicatorPlacement()
        return placement.apply(layout, itemCount)
    }

    fun cancelPendingScrollIndicatorPlacement() {
        pendingIndicatorPlacement = null
    }

    internal fun requestScrollIndicatorPlacement(layout: LinearLayoutManager, placement: NativeScrollIndicatorPlacement) {
        if (coordinatedIndicatorLayout === layout) {
            pendingIndicatorPlacement = placement
            requestLayout()
        } else {
            // Retain stock behavior for managers with no coordinated placement callback.
            placement.apply(layout, layout.itemCount)
        }
    }

    private inner class ScrollIndicatorPointerNode : NativeViewPhasePointerNode {
        override val view: View get() = this@NativeSourceRecyclerView
        private var pointerId = Long.MIN_VALUE
        private var grabOffset = 0f
        private var canceledRelease = false
        val dragging get() = pointerId != Long.MIN_VALUE

        override fun bindMotion(event: MotionEvent?, coordinates: NativePointerCoordinates?, androidPointerMask: Int) {
            // A split canceled lift still projects as an unpressed change.
            canceledRelease = event != null && event.flags and MotionEvent.FLAG_CANCELED != 0
        }

        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
            if (pass != NativePointerPass.INITIAL) return
            val indicator = scrollIndicator ?: return
            if (!dragging) {
                if (!event.isDown()) return
                val down = event.changes.first()
                if (down.consumed || !canStartScrollIndicatorDrag(down.position.x, down.position.y)) return
                pointerId = down.id
                grabOffset = indicator.grabOffset(down.position.y)
                down.consume()
                sourceOnUserScroll?.invoke()
                sourceStopScroll?.invoke()
                stopScroll()
                invalidate()
                return
            }
            val change = event.changes.firstOrNull { it.id == pointerId } ?: return
            change.consume()
            if (!change.pressed && canceledRelease) {
                cancel()
                return
            }
            if (isEnabled && !isLayoutSuppressed && change.delta.y != 0f) {
                indicator.scrollToThumb(change.position.y - grabOffset)
            }
            if (!change.pressed) finishDrag()
        }

        override fun cancel() {
            cancelPendingScrollIndicatorPlacement()
            finishDrag()
        }

        private fun finishDrag() {
            if (!dragging) return
            reset()
            if (!scrollIndicatorPresented || !isShown) return
            if (Build.VERSION.SDK_INT >= 29) {
                awakenScrollBars()
            } else {
                scrollIndicator?.awakenLegacy()
                invalidate()
            }
        }

        fun reset() { pointerId = Long.MIN_VALUE }
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

    override fun onScrolled(dx: Int, dy: Int) {
        super.onScrolled(dx, dy)
        if (Build.VERSION.SDK_INT < 29 && dy != 0) scrollIndicator?.awakenLegacy()
    }

    override fun draw(canvas: Canvas) {
        val indicator = scrollIndicator
        indicator?.startFrame()
        val edge = sourceOverscroll
        if (edge == null) super.draw(canvas) else edge.draw(canvas) { super.draw(it) }
        if (indicator != null && scrollIndicatorPresented) {
            indicator.paintThumb(canvas, isScrollIndicatorDragging)
        }
    }

    override fun setWillNotDraw(willNotDraw: Boolean) {
        // RecyclerView can request draw suppression again when its last decoration is removed.
        requestedWillNotDraw = willNotDraw
        super.setWillNotDraw(willNotDraw && scrollIndicator == null)
    }

    override fun setLayoutManager(layout: LayoutManager?) {
        if (layoutManager !== layout) {
            coordinatedIndicatorLayout = null
            indicatorNode?.reset()
            cancelPendingScrollIndicatorPlacement()
        }
        super.setLayoutManager(layout)
    }

    override fun setAdapter(adapter: Adapter<*>?) {
        if (this.adapter !== adapter) indicatorNode?.reset()
        cancelPendingScrollIndicatorPlacement()
        super.setAdapter(adapter)
    }

    override fun swapAdapter(adapter: Adapter<*>?, removeAndRecycleExistingViews: Boolean) {
        if (this.adapter !== adapter) indicatorNode?.reset()
        cancelPendingScrollIndicatorPlacement()
        super.swapAdapter(adapter, removeAndRecycleExistingViews)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) resetScrollIndicator()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) resetScrollIndicator()
    }

    override fun onDetachedFromWindow() {
        resetScrollIndicator()
        super.onDetachedFromWindow()
    }

    private fun resetScrollIndicator() {
        cancelPendingScrollIndicatorPlacement()
        indicatorNode?.reset()
        scrollIndicator?.reset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sourceOverscroll?.measured(w, h)
    }
}
