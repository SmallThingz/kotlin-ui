package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import smallthingz.ui.controls.ControlPalette
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/** One stationary coordinate space owns touch, scroll and settling for an opted-in sheet. */
internal class NativeSheetHost(
    context: Context,
    private val panel: ViewGroup,
    private val scrollBody: ScrollView?,
    private val behavior: NativeSheetBehavior,
    handleStyle: NativeSheetHandleStyle,
    private val style: () -> NativeDialogStyle,
    private val palette: () -> ControlPalette,
    private val canCancel: () -> Boolean,
    private val canCancelOutside: () -> Boolean,
    private val requestCancel: () -> Unit,
    private val commitCancel: () -> Unit,
    private val stateChanged: (NativeSheetState) -> Unit,
    private val lifecycleHandler: Handler,
    private val terminalBack: () -> Unit = requestCancel,
) : ViewGroup(context), NativeInputGate {
    private enum class Owner { NONE, NESTED, DIRECT, FLING, SETTLING }
    private enum class CancelIntent { NONE, ADMITTED, COMMITTED, OWNER_DISMISSING }
    private var owner = Owner.NONE
    private var cancelIntent = CancelIntent.NONE
    private var active = false
    private var suspendedForVisibility = false
    private var pendingHiddenNotice: Long? = null
    private var generation = 0L
    private var needsInitialAnchor = true
    private var needsVisibleReconcile = false
    private var anchors: SheetAnchors? = null
    private var offset = 0f
    var state = NativeSheetState.HIDDEN
        private set
    private var visibleState = NativeSheetState.EXPANDED
    private var targetVisibleState = NativeSheetState.EXPANDED
    private var origin = NativeSheetState.EXPANDED
    private var nestedTarget: View? = null
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var downTarget: View? = null
    private var downInHandle = false
    private var outsideStream = false
    private var outsideTap = false
    private var disallowIntercept = false
    private var canceledStream = false
    private var endingStream = false
    private var terminalHandled = false
    private var pausedTarget: NativeSheetState? = null
    private var pausedVelocity = 0f
    private var tracker: VelocityTracker? = null
    private val configuration = ViewConfiguration.get(context)
    private val slop = configuration.scaledTouchSlop.toFloat()
    private val maximumVelocity = configuration.scaledMaximumFlingVelocity.toFloat()
    private val scratch = Rect()
    private val screenLocation = IntArray(2)
    private var pendingFocus: WeakReference<View>? = null
    private var revealingFocus = false
    private var sawAccessibilityFocus = false
    private val motion = NativeSheetMotion(this, ::movePanel)
    private val keyPresses = NativeKeyPressRetirement(this, panel) { generation }
    private val bodyFling = scrollBody?.let { body -> NativeSheetBodyFling(this, body,
        onBoundary = { velocity ->
            if (active && owner == Owner.FLING && cancelIntent == CancelIntent.NONE) finishRelease(velocity, false)
        }, onFinished = { if (owner == Owner.FLING) finishRelease(0f, false) },
        onUnavailable = ::finishUnavailableFling) }
    val handle: View? = if (behavior.showDragHandle) SheetHandle(context, handleStyle) else null
    val hasPendingCancel: Boolean get() = cancelIntent == CancelIntent.ADMITTED
    override val acceptsNativeInput: Boolean get() = active && !suspendedForVisibility &&
        isEnabled && cancelIntent == CancelIntent.NONE

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        clipChildren = true
        clipToPadding = true
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (handle == null) panel.accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                addSheetActions(info)
            }
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
                performSheetAction(action) || super.performAccessibilityAction(host, action, args)
        }
    }

    fun started() {
        stopWork(); generation++; active = true
        suspendedForVisibility = false; pendingHiddenNotice = null
        cancelIntent = CancelIntent.NONE
        needsInitialAnchor = true; anchors = null; state = NativeSheetState.HIDDEN
        pendingFocus = panel.findFocus()?.let(::WeakReference)
        requestLayout()
    }

    fun stopped(notify: Boolean = true): Long? {
        val changed = state != NativeSheetState.HIDDEN
        val epoch = generation
        active = false; keyPresses.retire()
        if (epoch != generation) return null
        stopWork(); generation++
        cancelIntent = CancelIntent.NONE
        needsInitialAnchor = true; anchors = null; pendingFocus = null; sawAccessibilityFocus = false
        state = NativeSheetState.HIDDEN
        pendingHiddenNotice = if (changed && !notify) generation else null
        if (changed && notify) { refreshActions(); stateChanged(NativeSheetState.HIDDEN) }
        return pendingHiddenNotice
    }

    fun notifyStopped(token: Long) {
        if (pendingHiddenNotice != token || generation != token || active) return
        pendingHiddenNotice = null
        stateChanged(NativeSheetState.HIDDEN)
    }

    fun hidden() {
        if (!active) return
        val acceptedTarget = reconciliationTarget()
        val epoch = generation
        active = false; keyPresses.retire()
        if (epoch != generation) return
        stopWork(); generation++
        visibleState = acceptedTarget
        anchors?.let { movePanel(it.offset(it.visibleFallback(visibleState)) ?: it.expanded) }
    }

    fun resumed() {
        generation++; active = true
        suspendedForVisibility = windowVisibility != VISIBLE || !isShown
        needsInitialAnchor = anchors == null
        needsVisibleReconcile = !needsInitialAnchor
        requestLayout()
    }
    fun repaint() { (handle as? SheetHandle)?.repaint(); invalidate() }
    fun retiring() {
        if (!active && !hasPendingCancel) return
        val epoch = generation
        active = false; keyPresses.retire()
        if (epoch != generation) return
        stopWork(); generation++
    }

    /** Programmatic cancellation has already passed the application's override/veto. */
    fun admitCancel(): Boolean {
        if (cancelIntent != CancelIntent.NONE) return true
        if (!active || !isAttachedToWindow || anchors == null || height == 0) return false
        val epoch = generation
        cancelIntent = CancelIntent.ADMITTED
        stopWork(); owner = Owner.SETTLING
        keyPresses.retire()
        if (epoch != generation || !active || cancelIntent != CancelIntent.ADMITTED) return true
        cancelChildTouch()
        if (epoch != generation || !active || cancelIntent != CancelIntent.ADMITTED) return true
        if (suspendedForVisibility || windowVisibility != VISIBLE || !isShown) return finishAdmittedCancel()
        val geometry = anchors ?: return finishAdmittedCancel()
        motion.start(offset, geometry.hidden, 0f, hide = true) {
            if (epoch == generation && cancelIntent == CancelIntent.ADMITTED) finishAdmittedCancel()
        }
        return true
    }

    fun finishAdmittedCancel(): Boolean {
        if (cancelIntent != CancelIntent.ADMITTED) return false
        cancelIntent = CancelIntent.COMMITTED
        stopWork()
        commitCancel()
        return true
    }

    fun beginOwnerDismissal(): Boolean {
        if (cancelIntent == CancelIntent.OWNER_DISMISSING) return true
        if (!active || cancelIntent != CancelIntent.NONE) return false
        val epoch = generation
        cancelIntent = CancelIntent.OWNER_DISMISSING
        stopWork()
        keyPresses.retire()
        if (epoch != generation || !active || cancelIntent != CancelIntent.OWNER_DISMISSING) return false
        cancelChildTouch()
        return epoch == generation && active && cancelIntent == CancelIntent.OWNER_DISMISSING
    }

    fun abortOwnerDismissal(): Boolean {
        if (!active || cancelIntent != CancelIntent.OWNER_DISMISSING) return false
        cancelIntent = CancelIntent.NONE
        anchors?.let { settle(it.visibleFallback(visibleState), 0f, true) }
        refreshActions()
        return true
    }

    private fun cancelChildTouch() {
        canceledStream = true; terminalHandled = true
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
    }

    fun expand(animate: Boolean): Boolean = requestVisibleState(NativeSheetState.EXPANDED, animate)
    fun partiallyExpand(animate: Boolean): Boolean = requestVisibleState(NativeSheetState.PARTIALLY_EXPANDED, animate)
    fun back(): Boolean {
        if (!canCancel()) return true
        if (state == NativeSheetState.EXPANDED && anchors?.partial != null) partiallyExpand(true)
        else requestUserCancellation(terminalBack)
        return true
    }

    private fun requestVisibleState(next: NativeSheetState, animate: Boolean): Boolean {
        val geometry = anchors ?: return false
        if (!active || !isAttachedToWindow || windowVisibility != VISIBLE ||
            cancelIntent != CancelIntent.NONE || geometry.offset(next) == null) return false
        stopInput(); bodyFling?.stop()
        settle(next, 0f, animate)
        return true
    }

    private fun requestUserCancellation() = requestUserCancellation(requestCancel)

    private fun requestUserCancellation(request: () -> Unit) {
        if (!active || !isEnabled || !canCancel() || cancelIntent != CancelIntent.NONE) return
        val epoch = generation
        request()
        if (epoch == generation && active && cancelIntent == CancelIntent.NONE) {
            anchors?.let { settle(it.visibleFallback(visibleState), 0f, true) }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val design = style()
        val density = resources.displayMetrics.density
        val availableWidth = (width - 2f * design.horizontalMarginDp * density).coerceAtLeast(0f)
        val requested = (availableWidth * design.widthFraction).roundToInt()
        val panelWidth = min(requested, design.maxWidthDp?.let { (it * density).roundToInt() } ?: width)
            .coerceIn(0, width)
        val maximum = (height * design.maxHeightFraction).roundToInt().coerceAtLeast(0)
        panel.measure(MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val epoch = generation
        val next = SheetAnchors.from(height.toFloat(), panel.measuredHeight.toFloat(), behavior.skipPartiallyExpanded)
        val geometryChanged = next != anchors
        val start = active && needsInitialAnchor && height > 0 && panel.measuredHeight > 0
        if (geometryChanged || needsVisibleReconcile) {
            val acceptedTarget = reconciliationTarget()
            if (pointer != MotionEvent.INVALID_POINTER_ID || nestedTarget != null) cancelChildTouch()
            if (epoch != generation) return
            stopWork(); anchors = next
            if (start) offset = next.hidden
            else {
                visibleState = next.visibleFallback(acceptedTarget)
                offset = if (cancelIntent != CancelIntent.NONE) next.clamp(offset, true)
                    else next.offset(visibleState) ?: next.expanded
            }
        }
        val y = offset.roundToInt()
        val x = (width - panel.measuredWidth) / 2
        panel.layout(x, y, x + panel.measuredWidth, y + panel.measuredHeight)
        if (epoch != generation) return
        if (start) {
            needsInitialAnchor = false
            origin = next.initialVisible(); visibleState = origin
            val initialFocus = pendingFocus?.get()
            if (initialFocus != null && focusClippedAt(initialFocus, next.offset(origin) ?: next.expanded)) {
                settle(NativeSheetState.EXPANDED, 0f, false)
            }
            else settle(origin, 0f, true)
        } else if (active && geometryChanged && cancelIntent == CancelIntent.ADMITTED) {
            finishAdmittedCancel(); return
        } else if (active && cancelIntent == CancelIntent.NONE && (geometryChanged || needsVisibleReconcile) &&
            height > 0 && panel.measuredHeight > 0) {
            needsVisibleReconcile = false
            publishState(visibleState)
        }
        if (epoch != generation) return
        if (active && sawAccessibilityFocus && !revealingFocus) revealAccessibilityFocus(panel)
        pendingFocus?.get()?.let(::revealFocusedView)
    }

    private fun movePanel(next: Float) {
        offset = next
        if (panel.isLaidOut) panel.offsetTopAndBottom(next.roundToInt() - panel.top)
        invalidate()
    }

    private fun settle(next: NativeSheetState, velocity: Float, animate: Boolean) {
        val target = anchors?.offset(next) ?: return
        if (!active || cancelIntent != CancelIntent.NONE) return
        bodyFling?.stop(); motion.stop(); owner = Owner.SETTLING
        targetVisibleState = next
        val epoch = generation
        val finish = {
            if (epoch == generation && active && cancelIntent == CancelIntent.NONE) {
                owner = Owner.NONE; visibleState = next; publishState(next)
            }
        }
        if (animate && isAttachedToWindow && windowVisibility == VISIBLE && isShown) {
            motion.start(offset, target, velocity, hide = false, onFinished = finish)
        }
        else { motion.snapTo(target); finish() }
    }

    private fun publishState(next: NativeSheetState) {
        if (state == next) return
        state = next; refreshActions(); stateChanged(next)
    }
    private fun stopWork() { motion.stop(); bodyFling?.stop(); stopInput(); owner = Owner.NONE }
    private fun reconciliationTarget(): NativeSheetState {
        val geometry = anchors ?: return visibleState
        return when (owner) {
            Owner.SETTLING -> geometry.visibleFallback(targetVisibleState)
            Owner.FLING -> if (geometry.partial != null &&
                abs(offset - geometry.partial) < abs(offset - geometry.expanded)) NativeSheetState.PARTIALLY_EXPANDED
                else NativeSheetState.EXPANDED
            Owner.NONE -> geometry.visibleFallback(
                if (pointer != MotionEvent.INVALID_POINTER_ID) pausedTarget ?: visibleState else visibleState)
            else -> geometry.visibleFallback(visibleState)
        }
    }
    private fun finishUnavailableFling() {
        if (!active || owner != Owner.FLING || cancelIntent != CancelIntent.NONE) return
        val next = reconciliationTarget()
        stopWork()
        settle(next, 0f, false)
    }
    private fun stopInput() {
        tracker?.recycle(); tracker = null
        pointer = MotionEvent.INVALID_POINTER_ID; nestedTarget = null; downTarget = null
        outsideStream = false; outsideTap = false; terminalHandled = true
    }
    override fun onDetachedFromWindow() {
        val epoch = generation
        active = false; keyPresses.retire()
        if (epoch == generation) { stopWork(); generation++ }
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        reconcileVisibility(visibility == VISIBLE && isShown)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        reconcileVisibility(isVisible && windowVisibility == VISIBLE)
    }

    private fun reconcileVisibility(visible: Boolean) {
        if (!active) return
        if (!visible && !suspendedForVisibility) {
            suspendedForVisibility = true
            val epoch = generation
            keyPresses.retire()
            if (epoch != generation || !active || !suspendedForVisibility) return
            val target = reconciliationTarget()
            stopWork()
            if (cancelIntent == CancelIntent.ADMITTED) {
                val epoch = generation
                lifecycleHandler.post {
                    if (epoch == generation && hasPendingCancel) finishAdmittedCancel()
                }
            } else if (cancelIntent == CancelIntent.NONE) {
                visibleState = target
                needsVisibleReconcile = true
            }
        } else if (visible && suspendedForVisibility) {
            suspendedForVisibility = false
            if (hasPendingCancel) finishAdmittedCancel() else requestLayout()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        keyPresses.dispatch(event, { acceptsNativeInput }) { super.dispatchKeyEvent(event) }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (cancelIntent != CancelIntent.NONE) return true
        val epoch = generation
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            if (acceptsNativeInput) keyPresses.claimPointerDown()
            if (!isEnabled) { stopInput(); return super.dispatchTouchEvent(event) }
            val interruptedFling = if (owner == Owner.FLING) reconciliationTarget() else null
            bodyFling?.stop()
            tracker?.recycle(); tracker = VelocityTracker.obtain()
            pointer = event.getPointerId(0)
            downX = event.x; downY = event.y; lastY = event.y
            origin = interruptedFling ?: anchors?.visibleFallback(visibleState) ?: visibleState
            canceledStream = false; endingStream = false; terminalHandled = false
            disallowIntercept = false; owner = Owner.NONE
            outsideStream = !insidePanel(event.x, event.y); outsideTap = outsideStream
            downInHandle = handle?.let { insideView(it, event.x, event.y) } == true
            downTarget = if (outsideStream) null else touchedView(panel, event.x - panel.left, event.y - panel.top)
            pausedTarget = interruptedFling; pausedVelocity = 0f
            if (!outsideStream && behavior.dragEnabled && motion.isRunning) {
                pausedTarget = targetVisibleState; pausedVelocity = motion.velocity; motion.stop()
            }
        }
        tracker?.addMovement(event)
        if (action == MotionEvent.ACTION_CANCEL) { canceledStream = true; endingStream = true }
        if (action == MotionEvent.ACTION_UP) endingStream = true
        if (action == MotionEvent.ACTION_POINTER_DOWN) outsideTap = false
        if (outsideStream) {
            if (action == MotionEvent.ACTION_MOVE && (abs(event.x - downX) > slop ||
                    abs(event.y - downY) > slop || insidePanel(event.x, event.y))) outsideTap = false
            if (action == MotionEvent.ACTION_UP) {
                val dismiss = outsideTap && !insidePanel(event.x, event.y) && canCancelOutside()
                stopInput()
                if (dismiss) requestUserCancellation()
            } else if (action == MotionEvent.ACTION_CANCEL) stopInput()
            return true
        }
        if (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) == pointer) {
            val replacement = if (event.actionIndex == 0) 1 else 0
            if (replacement < event.pointerCount) {
                pointer = event.getPointerId(replacement)
                downX = event.getX(replacement); downY = event.getY(replacement); lastY = downY
                tracker?.clear(); tracker?.addMovement(event)
            } else pointer = MotionEvent.INVALID_POINTER_ID
        }
        val result = super.dispatchTouchEvent(event)
        if (epoch != generation) return result
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (!terminalHandled && owner != Owner.FLING && cancelIntent == CancelIntent.NONE) {
                finishRelease(releaseVelocity(), canceledStream)
            }
            tracker?.recycle(); tracker = null
            pointer = MotionEvent.INVALID_POINTER_ID; downTarget = null
        }
        return result || active
    }

    override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        disallowIntercept = disallow
        super.requestDisallowInterceptTouchEvent(disallow)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (cancelIntent != CancelIntent.NONE || outsideStream) return true
        if (owner == Owner.DIRECT) return true
        if (!active || !isEnabled || !behavior.dragEnabled || disallowIntercept || nestedTarget != null ||
            event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val index = event.findPointerIndex(pointer)
        if (index < 0) return false
        val dy = event.getY(index) - downY
        val dx = event.getX(index) - downX
        if (abs(dy) <= slop || abs(dy) <= abs(dx)) return false
        if (!downInHandle && (sensitiveTarget(downTarget) ||
                descendantCanScroll(downTarget, if (dy > 0f) -1 else 1))) return false
        owner = Owner.DIRECT; terminalHandled = false
        lastY = downY + sign(dy) * slop
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_MOVE && owner == Owner.DIRECT) {
            val index = event.findPointerIndex(pointer)
            if (index < 0) { canceledStream = true; finishRelease(0f, true) }
            else {
                val y = event.getY(index)
                dragBy(y - lastY); lastY = y
            }
        }
        return active
    }

    private fun dragBy(delta: Float): Float {
        val geometry = anchors ?: return 0f
        val previous = offset
        movePanel(geometry.clamp(offset + delta, canCancel()))
        return offset - previous
    }

    private fun releaseVelocity(): Float {
        if (pointer == MotionEvent.INVALID_POINTER_ID) return 0f
        tracker?.computeCurrentVelocity(1000, maximumVelocity)
        return tracker?.getYVelocity(pointer) ?: 0f
    }

    private fun finishRelease(velocity: Float, canceled: Boolean) {
        if (terminalHandled && owner != Owner.FLING) return
        terminalHandled = true
        val geometry = anchors ?: return
        if (!active || cancelIntent != CancelIntent.NONE) return
        if (owner != Owner.DIRECT && owner != Owner.NESTED && owner != Owner.FLING) {
            // Observing a tap must not turn an interrupted opening animation into a dismissal.
            pausedTarget?.let { settle(geometry.visibleFallback(it), if (canceled) 0f else pausedVelocity, true) }
            return
        }
        if (canceled || !isEnabled) { settle(geometry.visibleFallback(origin), 0f, true); return }
        // Direct anchored dragging reconciles a nearby anchor; nested raw deltas do not.
        val releaseOrigin = if (owner == Owner.DIRECT) geometry.releaseOrigin(offset, origin, canCancel()) else origin
        val next = geometry.settleTarget(offset, velocity, releaseOrigin, canCancel(),
            56f * resources.displayMetrics.density, 125f * resources.displayMetrics.density)
        if (next == NativeSheetState.HIDDEN) requestUserCancellation()
        else settle(next, velocity, true)
    }

    override fun onStartNestedScroll(child: View, target: View, axes: Int): Boolean =
        active && isEnabled && behavior.dragEnabled && cancelIntent == CancelIntent.NONE && owner != Owner.DIRECT &&
            axes and SCROLL_AXIS_VERTICAL != 0 && isDescendant(panel, target) &&
            (nestedTarget == null || nestedTarget === target)

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int) {
        super.onNestedScrollAccepted(child, target, axes)
        nestedTarget = target
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) {
        if (target !== nestedTarget || !active || !isEnabled || cancelIntent != CancelIntent.NONE || owner == Owner.DIRECT) return
        val geometry = anchors ?: return
        if (dy > 0 && offset > geometry.expanded) {
            owner = Owner.NESTED
            val moved = dragBy(-min(dy.toFloat(), offset - geometry.expanded))
            consumed[1] += (-moved).roundToInt().coerceIn(0, dy)
        }
    }

    override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int) {
        if (target !== nestedTarget || !active || !isEnabled || cancelIntent != CancelIntent.NONE ||
            owner == Owner.DIRECT || dyUnconsumed == 0) return
        owner = Owner.NESTED
        dragBy(-dyUnconsumed.toFloat())
    }

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean {
        if (target !== nestedTarget || !active || cancelIntent != CancelIntent.NONE) return false
        val geometry = anchors ?: return false
        if ((velocityY > 0f && offset > geometry.expanded) ||
            (velocityY < 0f && !target.canScrollVertically(-1))) {
            finishRelease(-velocityY, false)
            return true
        }
        if (target === scrollBody && bodyFling != null && abs(offset - geometry.expanded) < .5f &&
            target.canScrollVertically(if (velocityY > 0f) 1 else -1)) {
            owner = Owner.FLING; terminalHandled = true
            bodyFling.start(velocityY)
            if (!bodyFling.isRunning && owner == Owner.FLING) finishUnavailableFling()
            return true
        }
        return false
    }

    override fun onNestedFling(target: View, velocityX: Float, velocityY: Float, consumed: Boolean): Boolean {
        if (target === nestedTarget && !consumed && active && cancelIntent == CancelIntent.NONE) {
            finishRelease(-velocityY, false)
            return true
        }
        return false
    }

    override fun onStopNestedScroll(target: View) {
        super.onStopNestedScroll(target)
        if (target !== nestedTarget) return
        nestedTarget = null
        if (owner != Owner.DIRECT && owner != Owner.FLING && !terminalHandled) {
            finishRelease(releaseVelocity(), canceledStream || !endingStream)
        }
    }
    override fun requestChildFocus(child: View, focused: View?) {
        super.requestChildFocus(child, focused)
        focused?.let { pendingFocus = WeakReference(it); revealFocusedView(it) }
    }

    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
        if (revealingFocus || !active || cancelIntent != CancelIntent.NONE) return false
        val mapped = Rect(rectangle)
        offsetDescendantRectToMyCoords(child, mapped)
        if (mapped.bottom <= height && mapped.top >= 0) return false
        val target = panel.findFocus() ?: child
        pendingFocus = WeakReference(target)
        val epoch = generation
        return expand(false).also { if (it && epoch == generation && active) revealFocusedView(target) }
    }

    private fun revealFocusedView(target: View) {
        if (revealingFocus || !active || !isLaidOut || cancelIntent != CancelIntent.NONE ||
            !isDescendant(panel, target)) return
        if (motion.isRunning) {
            val eventualOffset = anchors?.offset(targetVisibleState)
            if (eventualOffset != null && !focusClippedAt(target, eventualOffset)) return
        }
        target.getFocusedRect(scratch)
        offsetDescendantRectToMyCoords(target, scratch)
        if (scratch.top >= 0 && scratch.bottom <= height) return
        val epoch = generation
        revealingFocus = true
        try {
            if (anchors?.let { offset > it.expanded } == true) expand(false)
            if (epoch == generation && active && scrollBody != null && isDescendant(scrollBody, target)) {
                val local = Rect()
                target.getFocusedRect(local)
                target.requestRectangleOnScreen(local, true)
            }
        } finally { revealingFocus = false }
    }

    private fun focusClippedAt(target: View, panelOffset: Float): Boolean {
        if (!isDescendant(panel, target)) return false
        target.getFocusedRect(scratch)
        panel.offsetDescendantRectToMyCoords(target, scratch)
        return scratch.top + panelOffset < 0f || scratch.bottom + panelOffset > height
    }

    override fun onRequestSendAccessibilityEvent(child: View, event: AccessibilityEvent): Boolean {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED && !revealingFocus && active) {
            sawAccessibilityFocus = true
            // Outgoing app events are unsealed: querying event.source would throw.
            revealAccessibilityFocus(panel)
        }
        return super.onRequestSendAccessibilityEvent(child, event)
    }

    private fun revealAccessibilityFocus(view: View): Boolean {
        if (!active || revealingFocus || cancelIntent != CancelIntent.NONE || view.visibility != VISIBLE) return false
        if (view.isAccessibilityFocused) { revealFocusedView(view); return true }
        // Query virtual descendants through their local provider, not through a service event.
        val node = view.accessibilityNodeProvider?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (node != null) {
            try {
                val local = Rect()
                node.getBoundsInScreen(local)
                view.getLocationOnScreen(screenLocation)
                local.offset(-screenLocation[0], -screenLocation[1])
                val mapped = Rect(local)
                offsetDescendantRectToMyCoords(view, mapped)
                if (mapped.top < 0 || mapped.bottom > height) {
                    val epoch = generation
                    revealingFocus = true
                    try {
                        expand(false)
                        if (epoch == generation && active) view.requestRectangleOnScreen(local, true)
                    } finally { revealingFocus = false }
                }
                return true
            } finally {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            if (revealAccessibilityFocus(view.getChildAt(index))) return true
        }
        return false
    }

    private fun refreshActions() {
        (handle ?: panel).sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        handle?.invalidate()
    }

    private fun addSheetActions(info: AccessibilityNodeInfo) {
        if (cancelIntent != CancelIntent.NONE || !active || !isEnabled) return
        if (canCancel()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
        if (state != NativeSheetState.EXPANDED) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND)
        else if (anchors?.partial != null) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE)
    }

    private fun performSheetAction(action: Int): Boolean = if (!isEnabled || cancelIntent != CancelIntent.NONE) false else when (action) {
        AccessibilityNodeInfo.ACTION_EXPAND -> expand(true)
        AccessibilityNodeInfo.ACTION_COLLAPSE -> partiallyExpand(true)
        AccessibilityNodeInfo.ACTION_DISMISS -> if (canCancel() && active && cancelIntent == CancelIntent.NONE) {
            requestUserCancellation(); true
        } else false
        else -> false
    }

    private fun insidePanel(x: Float, y: Float): Boolean =
        x >= panel.left && x < panel.right && y >= panel.top && y < panel.bottom
    private fun insideView(view: View, x: Float, y: Float): Boolean {
        scratch.set(0, 0, view.width, view.height)
        offsetDescendantRectToMyCoords(view, scratch)
        return scratch.contains(x.toInt(), y.toInt())
    }
    private fun isDescendant(parent: View, view: View): Boolean {
        var current: View? = view
        while (current != null) { if (current === parent) return true; current = current.parent as? View }
        return false
    }
    private fun descendantCanScroll(target: View?, direction: Int): Boolean {
        var current = target
        while (current != null && current !== panel) {
            if (current.canScrollVertically(direction)) return true
            current = current.parent as? View
        }
        return false
    }
    private fun sensitiveTarget(target: View?): Boolean {
        var current = target
        while (current != null && current !== panel) {
            if (current is EditText || current is TextView && current.isTextSelectable) return true
            current = current.parent as? View
        }
        return false
    }
    private fun touchedView(view: View, x: Float, y: Float): View {
        if (view is ViewGroup) for (index in view.childCount - 1 downTo 0) {
            val child = view.getChildAt(index)
            val cx = x + view.scrollX - child.left - child.translationX
            val cy = y + view.scrollY - child.top - child.translationY
            if (child.visibility == VISIBLE && cx >= 0 && cy >= 0 && cx < child.width && cy < child.height) {
                return touchedView(child, cx, cy)
            }
        }
        return view
    }

    private inner class SheetHandle(context: Context, private val design: NativeSheetHandleStyle) : NativeButton(
        context, palette(), NativeButtonStyle(minHeightDp = (design.heightDp + design.verticalPaddingDp * 2f).coerceAtLeast(48f),
            horizontalPaddingDp = 0f, verticalPaddingDp = 0f, radiusDp = 24f),
    ) {
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val handleColors=smallthingz.ui.animation.view.ViewColorTransition(this,intArrayOf(design.color ?: palette().muted),{invalidate()})
        init {
            contentDescription = "Sheet controls"
            setOnClickListener {
                if (!this@NativeSheetHost.isEnabled || cancelIntent != CancelIntent.NONE) return@setOnClickListener
                when (state) {
                    NativeSheetState.PARTIALLY_EXPANDED -> expand(true)
                    NativeSheetState.EXPANDED -> if (canCancel()) requestUserCancellation() else partiallyExpand(true)
                    NativeSheetState.HIDDEN -> anchors?.let { requestVisibleState(it.initialVisible(), true) }
                }
            }
            repaint()
        }
        fun repaint() { applyPalette(palette()); handleColors.retarget(design.color ?: palette().muted) }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            handlePaint.color=handleColors.color(0)
            val density = resources.displayMetrics.density
            val halfWidth = design.widthDp * density / 2f
            val halfHeight = design.heightDp * density / 2f
            canvas.drawRoundRect(width / 2f - halfWidth, height / 2f - halfHeight,
                width / 2f + halfWidth, height / 2f + halfHeight, halfHeight, halfHeight, handlePaint)
        }
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            addSheetActions(info)
        }
        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
            performSheetAction(action) || super.performAccessibilityAction(action, arguments)
    }
}
