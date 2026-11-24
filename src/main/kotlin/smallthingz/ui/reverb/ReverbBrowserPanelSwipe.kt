package smallthingz.ui.reverb

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/** Recognizes an intentional horizontal finger swipe without disturbing normal taps or vertical scroll. */
internal class ReverbBrowserPanelSwipe(
    context: Context,
    private val swipeDistance: (width: Int) -> Float,
    private val canSwipe: () -> Boolean,
    private val onDrag: (Float) -> Unit = {},
    private val onSwipe: (direction: Int) -> Unit,
) : FrameLayout(context) {
    private val config = ViewConfiguration.get(context)
    private val slop = config.scaledTouchSlop.toFloat()
    private val minimumFlingSpeed = maxOf(
        config.scaledMinimumFlingVelocity * 4f, 350f * resources.displayMetrics.density,
    )
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var phase = ReverbBrowserPanelSwipePhase.IDLE

    init {
        filterTouchesWhenObscured = true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val trusted = onFilterTouchEventForSecurity(event) &&
            event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) == 0
        if (action == MotionEvent.ACTION_DOWN) {
            finishGesture()
            if (trusted && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && canSwipe()) {
                downX = event.x
                downY = event.y
                velocityTracker = VelocityTracker.obtain()
                phase = ReverbBrowserPanelSwipePhase.PENDING
            }
        }
        val dx = event.x - downX
        val previous = phase
        phase = when {
            action == MotionEvent.ACTION_CANCEL || event.pointerCount != 1 || !trusted -> phase.cancel()
            action == MotionEvent.ACTION_MOVE && velocityTracker != null -> phase.move(
                dx, event.y - downY, slop,
                beforeLongPress = event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout(),
                eligible = canSwipe(),
            )
            else -> phase
        }
        when (phase) {
            ReverbBrowserPanelSwipePhase.IDLE, ReverbBrowserPanelSwipePhase.CANCELED -> stopTracking()
            ReverbBrowserPanelSwipePhase.DRAGGING -> if (previous == ReverbBrowserPanelSwipePhase.PENDING) takeGesture(event)
            ReverbBrowserPanelSwipePhase.PENDING -> Unit
        }
        if (previous == ReverbBrowserPanelSwipePhase.DRAGGING && phase == ReverbBrowserPanelSwipePhase.CANCELED) onDrag(0f)
        velocityTracker?.addMovement(event)
        if (action == MotionEvent.ACTION_MOVE && phase == ReverbBrowserPanelSwipePhase.DRAGGING) onDrag(dx)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            val wasSwipe = phase.ownsTouch
            val direction = if (action == MotionEvent.ACTION_UP) swipeDirection(dx) else 0
            finishGesture()
            if (direction != 0) onSwipe(direction)
            if (wasSwipe) return true
        }
        return phase.ownsTouch || super.dispatchTouchEvent(event) ||
            (action == MotionEvent.ACTION_DOWN && phase == ReverbBrowserPanelSwipePhase.PENDING)
    }

    private fun takeGesture(event: MotionEvent) {
        // The original child gets one cancel so ending the swipe cannot click a button or edit text.
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        try {
            super.dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun swipeDirection(dx: Float): Int {
        val tracker = velocityTracker ?: return 0
        tracker.computeCurrentVelocity(1000, config.scaledMaximumFlingVelocity.toFloat())
        return phase.release(
            dx, tracker.xVelocity, tracker.yVelocity, swipeDistance(width), slop, minimumFlingSpeed,
            eligible = canSwipe(),
        )
    }

    private fun stopTracking() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun finishGesture() {
        stopTracking()
        if (phase == ReverbBrowserPanelSwipePhase.DRAGGING) onDrag(0f)
        if (phase.ownsTouch) parent?.requestDisallowInterceptTouchEvent(false)
        phase = ReverbBrowserPanelSwipePhase.IDLE
    }

    override fun onCancelPendingInputEvents() {
        finishGesture()
        super.onCancelPendingInputEvents()
    }

    override fun onDetachedFromWindow() {
        finishGesture()
        super.onDetachedFromWindow()
    }
}

internal enum class ReverbBrowserPanelSwipePhase {
    IDLE, PENDING, DRAGGING, CANCELED;

    val ownsTouch: Boolean get() = this == DRAGGING || this == CANCELED

    fun move(dx: Float, dy: Float, slop: Float, beforeLongPress: Boolean, eligible: Boolean): ReverbBrowserPanelSwipePhase {
        // The address field can enter native editing after the gesture's DOWN event.
        if (!eligible) return cancel()
        if (this != PENDING || maxOf(abs(dx), abs(dy)) <= slop) return this
        return if (beforeLongPress && horizontal(dx, dy)) DRAGGING else IDLE
    }

    fun cancel(): ReverbBrowserPanelSwipePhase = if (ownsTouch) CANCELED else IDLE

    fun release(
        dx: Float, xVelocity: Float, yVelocity: Float, distance: Float, slop: Float, minimumFlingSpeed: Float,
        eligible: Boolean,
    ): Int {
        if (this != DRAGGING || !eligible) return 0
        val direction = if (dx < 0f) -1 else 1
        val enoughDistance = abs(dx) >= distance
        val fling = abs(dx) >= 2f * slop && direction * xVelocity >= minimumFlingSpeed &&
            horizontal(xVelocity, yVelocity)
        return if (enoughDistance || fling) direction else 0
    }

    private fun horizontal(x: Float, y: Float) = abs(x) > 1.25f * abs(y)
}
