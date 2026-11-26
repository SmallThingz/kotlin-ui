package smallthingz.ui.zfm

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.annotation.MainThread
import java.util.Collections
import java.util.IdentityHashMap

/** The app retains and binds this exact view for a surviving key; removal never disposes it. */
data class ZfmChooserRoot(val key: String, val view: View)

/**
 * Layout-only body for one Storage chooser opening, using the platform ScrollView input path.
 * Do not install a second native phase scroll detector around this viewport.
 *
 * Pass the existing management column (Refresh/More/status) as [managementContent]. Publish
 * roots in logical order: bottom-first places the first root at the bottom, top-down at the top.
 * Management stays physically above roots. Supply this viewport as the body of the caller's
 * bounded panel with `scrollBody=false`, keeping Close in that panel's fixed actions slot.
 * Then pass the COMPLETE panel to [wrapPanel] and use the returned view as dialog content.
 * The wrapper observes input/focus even on Close; it paints nothing and never consumes input.
 *
 * [isOpeningCurrent] must be a side-effect-free check against the caller's live opening identity.
 * It must admit construction-time publication (not require `dialog.isShowing` or layout).
 * Call [close] on dismiss/show failure, and construct a new instance for a new opening.
 * No detach, focus regain or publication rearms the initial edge. Initial positioning is
 * conditional: native initial keyboard focus, as well as deliberate focus/input, cancels it.
 * Qualify touch-mode and keyboard/non-touch openings separately; bottom placement is not
 * unconditional, and focus is never suppressed or reclaimed to force it.
 *
 * Logical reading order starts at the selected edge; spatial arrow navigation remains platform
 * owned. Surviving children retain insertion order while their physical coordinates change.
 * No explicit traversal metadata is installed: keyboard/accessibility traversal must be qualified
 * in both directions and after incremental publication, with the caller's metadata and service.
 *
 * No posts, focus requests, smooth scrolling or global layout retry are used for positioning.
 * Caller controls retain styling, accessible semantics, callbacks, permissions and disposal.
 * The caller also owns the focus fallback when a focused key is removed: read [focusedRoot]
 * before publication. This component does not activate, rebind or recreate any root control.
 */
@MainThread
class ZfmChooserViewport(
    context: Context,
    managementContent: View,
    val openingGeneration: Long,
    private val isOpeningCurrent: (Long) -> Boolean,
    bottomFirst: Boolean = true,
) : ScrollView(context), AutoCloseable {
    private val gate = ZfmChooserPlacementGate(openingGeneration)
    private val roots = RootLayout(context)
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(managementContent, LinearLayout.LayoutParams(-1, -2))
        addView(roots, LinearLayout.LayoutParams(-1, -2))
    }
    private var bottomFirst = bottomFirst
    private var boundary: Boundary? = null
    private var closed = false
    private var committed = false
    private var publicationNeedsLayout = false
    private var layoutDepth = 0
    private var applyingPosition = false
    private var dispatchDepth = 0
    private var touchActive = false
    private val heldKeys = HashSet<Int>()
    private data class ScrollPosition(val view: View, val x: Int, val y: Int)
    private data class Pending(
        val ticket: ZfmChooserPlacementGate.Ticket,
        val anchor: ZfmChooserAnchor,
        val scrollPositions: List<ScrollPosition>,
    )
    private var pending: Pending? = null

    init {
        isFillViewport = false
        addView(column, FrameLayout.LayoutParams(-1, -2))
    }

    /** One wrapper per opening; [panel] must already contain this body and be unparented. */
    fun wrapPanel(panel: View): View {
        check(!closed && boundary == null) { "The chooser panel is wrapped once per opening" }
        require(panel !== this && panel.parent == null && isInside(this, panel)) {
            "Wrap the complete unparented bounded panel containing this viewport"
        }
        return Boundary(panel).also { boundary = it }
    }

    /**
     * Before [close], returns false when [isOpeningCurrent] rejects the opening, without changing
     * its children. After [close], throws IllegalStateException; a closed instance cannot publish.
     * Duplicate keys/views, replacing a surviving key's view, or adopting a foreign view are errors.
     * Call on every render, including unchanged roots with status/management geometry changes,
     * while prior committed bounds remain available and before the next layout. Geometry changes
     * alone do not capture an anchor. Input/focus cancellation still wins over pending placement.
     * Multiple publications before layout keep the last committed visible-key snapshot.
     * Direction changes preserve a surviving visible key; they never request a second initial edge.
     */
    fun publish(items: List<ZfmChooserRoot>, bottomFirst: Boolean = this.bottomFirst): Boolean {
        check(!closed) { "Chooser is closed" }
        if (!isOpeningCurrent(openingGeneration)) return false
        val next = items.toList()
        roots.validate(next)
        val ticket = gate.ticket()
        if (pending == null && committed && !publicationNeedsLayout && ticket != null &&
            ownsWindow() && idle()) {
            pending = Pending(ticket, ZfmChooserAnchors.capture(bounds(), scrollY, height, this.bottomFirst),
                scrollPositions())
        }
        this.bottomFirst = bottomFirst
        publicationNeedsLayout = true
        roots.publish(next, bottomFirst)
        requestLayout()
        return true
    }

    fun focusedRoot(): ZfmChooserRoot? = roots.items.firstOrNull { it.view.hasFocus() }
    fun rootView(key: String): View? = roots.items.firstOrNull { it.key == key }?.view

    private fun idle() = dispatchDepth == 0 && !touchActive && heldKeys.isEmpty()
    private fun ownsWindow(initial: Boolean = false): Boolean {
        val host = boundary ?: return false
        return !closed && isAttachedToWindow && host.isAttachedToWindow &&
            host.token != null && windowToken == host.token && host.windowToken == host.token &&
            windowVisibility == VISIBLE && isShown && host.isShown && isInside(this, host) &&
            (host.hasWindowFocus() || (initial && !host.everFocused)) && isOpeningCurrent(openingGeneration)
    }

    private fun scrollPositions(): List<ScrollPosition> {
        val result = ArrayList<ScrollPosition>()
        fun visit(view: View) {
            result.add(ScrollPosition(view, view.scrollX, view.scrollY))
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        boundary?.let(::visit)
        return result
    }

    private fun scrollPositionsMatch(positions: List<ScrollPosition>, exceptViewport: Boolean = false) =
        positions.all { (exceptViewport && it.view === this) ||
            (it.view.scrollX == it.x && it.view.scrollY == it.y) }

    private fun bounds(): List<ZfmChooserRootBounds> = roots.items.mapNotNull {
        val row = it.view
        if (row.visibility != VISIBLE) null else ZfmChooserRootBounds(
            it.key, column.top + roots.top + row.top, column.top + roots.top + row.bottom,
        )
    }

    private fun retirePosition() { gate.input(); pending = null }

    private fun committedLayout(ticket: ZfmChooserPlacementGate.Ticket?) {
        if (width <= 0 || height <= 0 || !isAttachedToWindow) return
        val initial = gate.commitInitial(ticket)
        val restore = pending
        pending = null
        committed = true
        publicationNeedsLayout = false
        // The first visible attached layout may precede the window's first focus notification.
        // Only that initial placement admits pre-focus; all subsequent restores require focus.
        if (!ownsWindow(initial) || !idle() || !gate.accepts(ticket)) return
        val maximum = (column.bottom - height + paddingBottom).coerceAtLeast(0)
        val target = when {
            initial -> if (bottomFirst) maximum else 0
            restore != null && gate.accepts(restore.ticket) &&
                scrollPositionsMatch(restore.scrollPositions, exceptViewport = true) ->
                ZfmChooserAnchors.restore(restore.anchor, bounds(), maximum)
            else -> return // Platform layout already clamps when no surviving request is admissible.
        }
        applyingPosition = true
        try { scrollTo(scrollX, target) } finally { applyingPosition = false }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        // A drag/fling or external scroll after capture owns the new position. Layout's ordinary
        // range clamp is part of the pending publication, not a new user scroll.
        if (layoutDepth == 0 && !applyingPosition && (l != oldl || t != oldt)) retirePosition()
        super.onScrollChanged(l, t, oldl, oldt)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!isScrollAction(action) && action != AccessibilityNodeInfo.ACTION_FOCUS &&
            action != AccessibilityNodeInfo.ACTION_CLEAR_FOCUS &&
            action != AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS &&
            action != AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            return super.performAccessibilityAction(action, arguments)
        retirePosition()
        dispatchDepth++
        return try { super.performAccessibilityAction(action, arguments) } finally { dispatchDepth-- }
    }

    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
        retirePosition()
        return super.requestChildRectangleOnScreen(child, rectangle, immediate)
    }

    override fun onDetachedFromWindow() {
        retirePosition()
        super.onDetachedFromWindow()
    }

    /**
     * Idempotently retires positioning and observers, not the caller-owned controls or callbacks.
     * Dismiss/show-failure owners must call this; opening rejection alone does not dispose the view.
     * A later [publish] is an ownership error, not the false result of a stale live opening.
     */
    override fun close() {
        if (closed) return
        closed = true
        gate.close()
        pending = null
        touchActive = false
        heldKeys.clear()
        boundary?.removeObserver()
        // Views and their click/permission lifetimes belong to the caller, not this layout.
    }

    private inner class Boundary(private val panel: View) : ViewGroup(context) {
        var token: IBinder? = null
            private set
        var everFocused = false
            private set
        private var initialScrollPositions: List<ScrollPosition> = emptyList()
        private var observer: ViewTreeObserver? = null
        private val focusObserver = ViewTreeObserver.OnGlobalFocusChangeListener { old, new ->
            if (old !== new && ((old != null && isInside(old, this)) ||
                    (new != null && isInside(new, this)))) retirePosition()
        }
        private val scrollObserver = ViewTreeObserver.OnScrollChangedListener { observeScroll() }

        private fun observeScroll() {
            if (layoutDepth != 0 || applyingPosition) return
            // Global/a11y notifications may be delivered after our own scrollTo. Compare actual
            // positions, so such a queued notification cannot cancel a newer unchanged snapshot.
            val positions = pending?.scrollPositions ?: if (!committed) initialScrollPositions else return
            if (!scrollPositionsMatch(positions)) retirePosition()
        }

        init {
            setWillNotDraw(true)
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(panel, LayoutParams(-1, -2))
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            token = windowToken
            gate.attach()
            initialScrollPositions = scrollPositions()
            if (!closed) observer = viewTreeObserver.also {
                it.addOnGlobalFocusChangeListener(focusObserver)
                it.addOnScrollChangedListener(scrollObserver)
            }
        }

        fun removeObserver() {
            observer?.takeIf { it.isAlive }?.let {
                it.removeOnGlobalFocusChangeListener(focusObserver)
                it.removeOnScrollChangedListener(scrollObserver)
            }
            observer = null
        }

        override fun onDetachedFromWindow() {
            gate.detach()
            pending = null
            touchActive = false
            heldKeys.clear()
            token = null
            initialScrollPositions = emptyList()
            removeObserver()
            super.onDetachedFromWindow()
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            gate.windowChanged(hasWindowFocus)
            pending = null
            if (hasWindowFocus) everFocused = true
            if (!hasWindowFocus) { touchActive = false; heldKeys.clear() }
            super.onWindowFocusChanged(hasWindowFocus)
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            if (visibility != VISIBLE) {
                gate.windowChanged(false)
                pending = null
                touchActive = false
                heldKeys.clear()
            }
            super.onWindowVisibilityChanged(visibility)
        }

        override fun onMeasure(w: Int, h: Int) {
            // Forward the original budget exactly once. FrameLayout's MATCH_PARENT second pass
            // would apply the existing bounded panel's width/height fractions a second time.
            panel.measure(w, h)
            setMeasuredDimension(panel.measuredWidth, panel.measuredHeight)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            observeScroll() // Also catches footer accessibility scrolling before its queued event.
            val ticket = gate.ticket()
            layoutDepth++
            try {
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                committedLayout(ticket)
            } finally { layoutDepth-- }
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            retirePosition()
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touchActive = true
            dispatchDepth++
            return try { super.dispatchTouchEvent(event) } finally {
                dispatchDepth--
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                    touchActive = false
            }
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            retirePosition()
            if (event.action == KeyEvent.ACTION_DOWN) heldKeys.add(event.keyCode)
            dispatchDepth++
            return try { super.dispatchKeyEvent(event) } finally {
                dispatchDepth--
                if (event.action == KeyEvent.ACTION_UP) heldKeys.remove(event.keyCode)
            }
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_SCROLL) return super.dispatchGenericMotionEvent(event)
            retirePosition()
            dispatchDepth++
            return try { super.dispatchGenericMotionEvent(event) } finally { dispatchDepth-- }
        }

        override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean {
            retirePosition()
            dispatchDepth++
            return try { super.dispatchKeyShortcutEvent(event) } finally { dispatchDepth-- }
        }

        override fun requestChildFocus(child: View, focused: View) {
            retirePosition()
            super.requestChildFocus(child, focused)
        }

        override fun clearChildFocus(child: View) {
            retirePosition()
            super.clearChildFocus(child)
        }

        override fun requestSendAccessibilityEvent(child: View, event: AccessibilityEvent): Boolean {
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) observeScroll()
            // Descendant accessibility activation bypasses touch/key dispatch. Retire on its
            // ordinary clicked event, including a callback's publication before the next layout.
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED) retirePosition()
            return super.requestSendAccessibilityEvent(child, event)
        }
    }

    /** Reordering is physical placement only: surviving controls never detach or lose identity. */
    private class RootLayout(context: Context) : ViewGroup(context) {
        var items: List<ZfmChooserRoot> = emptyList()
            private set
        private var order: List<ZfmChooserRoot> = emptyList()

        init { setWillNotDraw(true); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun validate(next: List<ZfmChooserRoot>) {
            require(next.map { it.key }.toSet().size == next.size) { "Duplicate chooser root key" }
            val views = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
            val previous = items.associateBy { it.key }
            next.forEach { root ->
                require(views.add(root.view)) { "A chooser view cannot represent two keys" }
                require(previous[root.key]?.let { it.view === root.view } != false) { "Retain the view for a surviving key" }
                require(root.view.parent == null || (root.view.parent === this &&
                    previous[root.key]?.view === root.view)) { "Root view belongs to another owner/key" }
            }
        }

        fun publish(next: List<ZfmChooserRoot>, bottomFirst: Boolean) {
            val keys = next.mapTo(HashSet()) { it.key }
            items.filter { it.key !in keys }.forEach { removeView(it.view) }
            next.filter { it.view.parent == null }.forEach { addView(it.view, LayoutParams(-1, -2)) }
            items = next
            order = ZfmChooserAnchors.physicalOrder(next, bottomFirst)
            requestLayout()
        }

        override fun onMeasure(w: Int, h: Int) {
            var height = 0L
            var width = 0
            order.forEach {
                val child = it.view
                if (child.visibility != GONE) {
                    child.measure(getChildMeasureSpec(w, 0, LayoutParams.MATCH_PARENT),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                    height += child.measuredHeight
                    width = maxOf(width, child.measuredWidth)
                }
            }
            setMeasuredDimension(resolveSize(width, w), resolveSize(height.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), h))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            var y = 0
            order.forEach {
                val child = it.view
                if (child.visibility != GONE) {
                    val bottom = (y.toLong() + child.measuredHeight).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    child.layout(0, y, width, bottom)
                    y = bottom
                }
            }
        }
    }

    private fun isInside(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun isScrollAction(action: Int): Boolean = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
        action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ||
        action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id ||
        action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id ||
        action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id ||
        action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id ||
        action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id
}
