package smallthingz.ui.components

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import smallthingz.ui.controls.ControlPalette
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class NativeSliderValueMode { INTEGER, CONTINUOUS }

/** DEFAULT retains the shared behavior; NATIVE_INTEGER follows the platform key set and increment. */
enum class NativeSliderKeyboardPolicy { DEFAULT, NATIVE_INTEGER }

/** Custom delegates can retain their admission checks and forward accepted range actions here. */
open class NativeSliderAccessibilityDelegate : View.AccessibilityDelegate() {
    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
        if (host is NativeSlider && host.valueMode == NativeSliderValueMode.CONTINUOUS &&
            (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id ||
                action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            return host.performContinuousAccessibilityAction(action, args)
        }
        return super.performAccessibilityAction(host, action, args)
    }
}

/** Explicit overrides for semantic roles absent from [ControlPalette], such as SecondaryContainer. */
data class NativeSliderColors(
    val thumbColor: Int? = null,
    val activeTrackColor: Int? = null,
    val inactiveTrackColor: Int? = null,
    val activeTickColor: Int? = null,
    val inactiveTickColor: Int? = null,
    val disabledThumbColor: Int? = null,
    val disabledActiveTrackColor: Int? = null,
    val disabledInactiveTrackColor: Int? = null,
    val disabledActiveTickColor: Int? = null,
    val disabledInactiveTickColor: Int? = null,
)

/** Geometry follows the recovered Material3 1.4.0 default Slider; no Compose runtime is used. */
data class NativeSliderStyle(
    val steps: Int = 0,
    val minHeightDp: Float = 48f,
    val minWidthDp: Float = 48f,
    val trackHeightDp: Float = 16f,
    val thumbWidthDp: Float = 4f,
    val thumbHeightDp: Float = 44f,
    val pressedThumbWidthDp: Float = 2f,
    val thumbTrackGapDp: Float = 6f,
    val trackInsideCornerDp: Float = 2f,
    val tickSizeDp: Float = 4f,
    val stopIndicatorSizeDp: Float = 4f,
    val showStopIndicator: Boolean = true,
    val horizontalPaddingDp: Float = 0f,
    val verticalPaddingDp: Float = 0f,
    val colors: NativeSliderColors = NativeSliderColors(),
) {
    init {
        require(steps >= 0 && steps < Int.MAX_VALUE)
        require(floatArrayOf(minHeightDp, minWidthDp, trackHeightDp, thumbWidthDp, thumbHeightDp,
            pressedThumbWidthDp, thumbTrackGapDp, trackInsideCornerDp, tickSizeDp,
            stopIndicatorSizeDp, horizontalPaddingDp, verticalPaddingDp).all { it.isFinite() && it >= 0f })
        require(thumbWidthDp > 0f && thumbHeightDp > 0f && pressedThumbWidthDp > 0f)
    }
}

/**
 * Retained native range control with shared geometry and user-completion ownership.
 *
 * Native min/max/progress, accessibility range and caller listeners remain available. The caller
 * owns draft values and admission; choose one durable update path. Ordinarily use [onValueCommitted];
 * native-policy consumers may instead own immediate non-drag progress, without a second commit owner.
 * Native listeners receive binding notifications with fromUser=false.
 * Palette/style changes never change the value or listener.
 *
 * Completion order: native progress/start/stop listener, then [onInteractionFinished], then (only
 * for an enabled, changed, uncancelled interaction) [onValueCommitted]. The interaction is already
 * retired before either component completion callback. Cancellation rollback is caller-owned.
 *
 * INTEGER is the unchanged default. CONTINUOUS requires steps=0 and retains logical native min/max;
 * [continuousValue] owns its fractional draft, drawing and accessibility range. Native progress is
 * only its truncating integer mirror. Use the Float callbacks exclusively for durable completion.
 * Native start/stop and coherent integer-mirror notifications remain available to editing UI.
 * Continuous completion order is native stop, Float finish, then guarded Float commit. An owner
 * cancels before changing its document/route and rolls back synchronously from the finish callback.
 *
 * Continuous range accessibility dispatch retains caller-delegate admission. Custom delegates must
 * forward accepted range actions to [NativeSliderAccessibilityDelegate] or
 * [performContinuousAccessibilityAction]; native integer fallback is rejected without a value change.
 * Assigning a null delegate restores the shared continuous delegate. Range changes cancel input and
 * silently clamp the Float draft. Integer progress assignment is an authoritative integer-valued bind.
 */
open class NativeSlider(
    context: Context,
    palette: ControlPalette,
    style: NativeSliderStyle = NativeSliderStyle(),
    val valueMode: NativeSliderValueMode = NativeSliderValueMode.INTEGER,
) : SeekBar(context, null, 0, android.R.style.Widget_Material_SeekBar) {
    private var componentStyle = style
    private var componentKeyboardPolicy = NativeSliderKeyboardPolicy.DEFAULT
    private var callerListener: OnSeekBarChangeListener? = null
    private var ready = false
    private var suppressNativeCallback = 0
    private var expectedUserValue: Int? = null
    private var interaction = Interaction.NONE
    private var interactionStart = 0
    private var interactionChanged = false
    private var bindingRevision = 0L
    private var lastUserBindingRevision = 0L
    private var lastUserValue: Int? = null
    private var generation = 0L
    private var touchDownTime = 0L
    private var touchDispatchDepth = 0
    private var accessibilityDispatchDepth = 0
    private var cancelledTouchProgress: Int? = null
    private var cancellingTouch = false
    private val heldKeys = mutableMapOf<Int, Long>()
    private var continuousDraft = 0f
    private var continuousStart = 0f
    private var lastUserContinuousValue: Float? = null
    private var nativeTouchAccepted = false
    private var continuousTouchSample: ContinuousTouchSample? = null
    private val continuousAccessibilityDispatches = mutableListOf<ContinuousAccessibilityDispatch>()
    private val continuousAccessibilityDelegate = NativeSliderAccessibilityDelegate()
    private var continuousAnnouncementPending = false
    private val continuousAnnouncement = Runnable {
        continuousAnnouncementPending = false
        if (isAttachedToWindow && visibility == View.VISIBLE) sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
    }
    private val trackGeometry = GeometryDrawable()
    private val thumbGeometry = GeometryDrawable()
    private val sliderPaint = SliderPaint(this, palette, style.colors)

    var onValueCommitted: ((Int) -> Unit)? = null
    var onInteractionFinished: ((value: Int, cancelled: Boolean) -> Unit)? = null
    /** Optional admission for each user Float change. May be rechecked after input-owner cleanup. */
    var onContinuousValueRequest: ((Float) -> Boolean)? = null
    /** User changes only; authoritative binding never invokes this callback. */
    var onContinuousValueChanged: ((Float) -> Unit)? = null
    var onContinuousValueCommitted: ((Float) -> Unit)? = null
    var onContinuousInteractionFinished: ((value: Float, cancelled: Boolean) -> Unit)? = null
    val continuousValue: Float get() = if (valueMode == NativeSliderValueMode.CONTINUOUS) continuousDraft else progress.toFloat()
    val isUserInteractionActive: Boolean get() = interaction != Interaction.NONE

    init {
        require(valueMode != NativeSliderValueMode.CONTINUOUS || style.steps == 0)
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = true
        background = null
        progressTintList = null
        progressBackgroundTintList = null
        secondaryProgressTintList = null
        thumbTintList = null
        tickMark = null
        progressDrawable = trackGeometry
        thumb = thumbGeometry
        splitTrack = false
        super.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (suppressNativeCallback > 0) return
                if (valueMode == NativeSliderValueMode.CONTINUOUS) {
                    if (fromUser) {
                        try {
                            if (accessibilityDispatchDepth > 0) {
                                continuousAccessibilityDispatches.lastOrNull()?.rejectedNativeMutation = true
                            } else if (touchDispatchDepth > 0 && interaction == Interaction.TOUCH && nativeTouchAccepted) {
                                flushContinuousTouchSample()
                            }
                        } finally {
                            setProgressWithoutCallback(continuousDraft.toInt())
                        }
                    } else {
                        bindContinuousValueInternal(value.toFloat(), notifyMirror = true)
                    }
                    return
                }
                val simulated = expectedUserValue == value
                if (simulated) expectedUserValue = null // Consume before a reentrant model binding.
                val user = fromUser || simulated
                if (fromUser && touchDispatchDepth > 0 && accessibilityDispatchDepth == 0 && interaction != Interaction.TOUCH) return
                val delivered = if (user) snap(value) else value
                if (delivered != value) setProgressWithoutCallback(delivered)
                if (user && interaction != Interaction.NONE) {
                    interactionChanged = true
                    lastUserValue = delivered
                    lastUserBindingRevision = bindingRevision
                } else if (!user) {
                    bindingRevision++
                }
                callerListener?.onProgressChanged(this@NativeSlider, delivered, user)
                invalidate()
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                if (valueMode == NativeSliderValueMode.CONTINUOUS) nativeTouchAccepted = true
                callerListener?.onStartTrackingTouch(this@NativeSlider)
                if (valueMode == NativeSliderValueMode.CONTINUOUS) flushContinuousTouchSample()
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                var delivered = false
                withCleanup({
                    if (valueMode == NativeSliderValueMode.CONTINUOUS && !cancellingTouch) flushContinuousTouchSample()
                    callerListener?.onStopTrackingTouch(this@NativeSlider)
                    delivered = true
                }, {
                    if (interaction == Interaction.TOUCH) finishInteraction(cancellingTouch || !delivered || !isEnabled)
                })
            }
        })
        continuousDraft = progress.toFloat()
        ready = true
        if (valueMode == NativeSliderValueMode.CONTINUOUS) super.setAccessibilityDelegate(continuousAccessibilityDelegate)
        applyGeometry()
    }

    override fun setOnSeekBarChangeListener(listener: OnSeekBarChangeListener?) {
        callerListener = listener
    }

    override fun setAccessibilityDelegate(delegate: View.AccessibilityDelegate?) {
        super.setAccessibilityDelegate(if (ready && valueMode == NativeSliderValueMode.CONTINUOUS && delegate == null)
            continuousAccessibilityDelegate else delegate)
    }

    /** Silent authoritative binding. Owners preserve a draft by skipping binding while active. */
    fun bindContinuousValue(value: Float) {
        check(valueMode == NativeSliderValueMode.CONTINUOUS)
        bindContinuousValueInternal(value, notifyMirror = false)
    }

    override fun setProgress(progress: Int) {
        if (ready && valueMode == NativeSliderValueMode.CONTINUOUS) bindContinuousValueInternal(progress.toFloat(), notifyMirror = true)
        else super.setProgress(progress)
    }

    override fun setProgress(progress: Int, animate: Boolean) {
        if (ready && valueMode == NativeSliderValueMode.CONTINUOUS) bindContinuousValueInternal(progress.toFloat(), notifyMirror = true)
        else super.setProgress(progress, animate)
    }

    override fun setMin(min: Int) {
        if (!ready || valueMode != NativeSliderValueMode.CONTINUOUS) { super.setMin(min); return }
        if (min == this.min) return
        cancelUserInteraction()
        suppressNativeCallback++
        try { super.setMin(min) } finally { suppressNativeCallback-- }
        bindContinuousValueInternal(continuousDraft, notifyMirror = false)
    }

    override fun setMax(max: Int) {
        if (!ready || valueMode != NativeSliderValueMode.CONTINUOUS) { super.setMax(max); return }
        if (max == this.max) return
        cancelUserInteraction()
        suppressNativeCallback++
        try { super.setMax(max) } finally { suppressNativeCallback-- }
        bindContinuousValueInternal(continuousDraft, notifyMirror = false)
    }

    fun applyPalette(palette: ControlPalette) {
        sliderPaint.applyPalette(palette)
    }

    fun applyStyle(style: NativeSliderStyle) {
        require(valueMode != NativeSliderValueMode.CONTINUOUS || style.steps == 0)
        require(componentKeyboardPolicy != NativeSliderKeyboardPolicy.NATIVE_INTEGER || style.steps == 0)
        if (componentStyle == style) return
        componentStyle = style
        applyGeometry()
        sliderPaint.applyColors(style.colors)
        invalidate()
    }

    /**
     * Configure while idle. Reapplying the same policy is silent, including during an interaction.
     * NATIVE_INTEGER requires integer values with steps=0 and reads the inherited key increment.
     * It leaves touch/accessibility unchanged and never synthesizes touch start/stop for a key.
     * An accepted keyboard sequence finishes before a boundary DOWN yields focus; its UP is retired.
     */
    fun setKeyboardPolicy(policy: NativeSliderKeyboardPolicy) {
        if (componentKeyboardPolicy == policy) return
        check(!isUserInteractionActive) { "Keyboard policy cannot change during an interaction" }
        require(policy != NativeSliderKeyboardPolicy.NATIVE_INTEGER ||
            (valueMode == NativeSliderValueMode.INTEGER && componentStyle.steps == 0))
        componentKeyboardPolicy = policy
    }

    /** Cancel before an owning route retires; rollback/admission policy stays with that owner. */
    fun cancelUserInteraction(): Boolean {
        if (interaction == Interaction.NONE) return false
        if (interaction == Interaction.TOUCH && !cancellingTouch) {
            cancellingTouch = true
            val event = MotionEvent.obtain(touchDownTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            withCleanup({
                // Native drag bookkeeping must terminate before setEnabled(false).
                super.onTouchEvent(event)
            }, {
                event.recycle()
                try {
                    if (interaction == Interaction.TOUCH) finishInteraction(true)
                } finally {
                    cancellingTouch = false
                    isPressed = false
                    if (touchDispatchDepth > 0) cancelledTouchProgress = progress
                }
            })
        } else {
            finishInteraction(true)
        }
        return true
    }

    override fun setEnabled(enabled: Boolean) {
        withCleanup({
            if (ready && !enabled && isEnabled) cancelUserInteraction()
        }, {
            super.setEnabled(enabled)
            if (ready) invalidate()
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        // A cancelled/retired pointer cannot fall into SeekBar's UP-as-click path.
        if (event.actionMasked != MotionEvent.ACTION_DOWN && interaction != Interaction.TOUCH) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // AbsSeekBar still owns native pressed/drag cleanup until its outer frame returns.
            if (cancellingTouch) return false
            if (!prepareInteraction()) return false
            beginInteraction(Interaction.TOUCH)
            touchDownTime = event.downTime
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancellingTouch = true
        if (valueMode == NativeSliderValueMode.CONTINUOUS) {
            continuousTouchSample = if (event.actionMasked == MotionEvent.ACTION_CANCEL) null
                else ContinuousTouchSample(event.x, generation, bindingRevision)
        }
        touchDispatchDepth++
        var handled = false
        var dispatchSucceeded = false
        return withCleanup({
            handled = super.onTouchEvent(event)
            if (valueMode == NativeSliderValueMode.CONTINUOUS && !cancellingTouch) flushContinuousTouchSample()
            dispatchSucceeded = true
            handled
        }, {
            touchDispatchDepth--
            withCleanup({
                if (!dispatchSucceeded) cancelUserInteraction()
                else if (event.actionMasked == MotionEvent.ACTION_UP && interaction == Interaction.TOUCH) finishInteraction(!handled)
                else if ((event.actionMasked == MotionEvent.ACTION_CANCEL || !handled) && interaction == Interaction.TOUCH) finishInteraction(true)
            }, {
                if (!dispatchSucceeded || event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    cancellingTouch = false
                    isPressed = false
                }
                if (touchDispatchDepth == 0) {
                    val restore = cancelledTouchProgress
                    cancelledTouchProgress = null
                    restore?.let { setProgressWithoutCallback(it) }
                }
            })
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (componentKeyboardPolicy == NativeSliderKeyboardPolicy.NATIVE_INTEGER) return onNativeKeyDown(keyCode, event)
        if (keyDirection(keyCode) == null) return super.onKeyDown(keyCode, event)
        if (!isEnabled) return false
        if (isUnownedKeyRepeat(keyCode, event)) return false
        if (interaction != Interaction.KEYBOARD) {
            if (!prepareInteraction()) return false
            beginInteraction(Interaction.KEYBOARD)
        }
        val direction = checkNotNull(keyDirection(keyCode))
        heldKeys[keyCode] = event.downTime
        val range = max.toLong() - min.toLong()
        val divisions = if (componentStyle.steps > 0) componentStyle.steps.toLong() + 1L else 100L
        val increment = max(1L, (range.toDouble() / divisions).roundToLong())
        val page = increment * (divisions / 10L).coerceIn(1L, 10L)
        val target = when (direction) {
            KEY_HOME -> min.toLong()
            KEY_END -> max.toLong()
            KEY_PAGE_UP -> progress.toLong() - page
            KEY_PAGE_DOWN -> progress.toLong() + page
            else -> progress.toLong() + direction * increment
        }.coerceIn(min.toLong(), max.toLong()).toInt()
        try {
            if (valueMode == NativeSliderValueMode.CONTINUOUS) {
                val delta = (max.toDouble() - min.toDouble()) / 100.0
                val continuousTarget = when (direction) {
                    KEY_HOME -> min.toDouble()
                    KEY_END -> max.toDouble()
                    KEY_PAGE_UP -> continuousDraft - delta * 10.0
                    KEY_PAGE_DOWN -> continuousDraft + delta * 10.0
                    else -> continuousDraft + direction * delta
                }.coerceIn(min.toDouble(), max.toDouble()).toFloat()
                setContinuousUserValue(continuousTarget)
            } else setUserProgress(snap(target))
        } catch (failure: Throwable) {
            try { cancelUserInteraction() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
        return true
    }

    private fun onNativeKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val direction = nativeKeyDirection(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (!isEnabled) return false
        if (isUnownedKeyRepeat(keyCode, event)) return false
        if (nativeKeyTarget(direction) == progress) {
            // Stock fallback can move focus. Finish accepted changes before focus-loss cancellation.
            if (interaction == Interaction.KEYBOARD) finishInteraction(false)
            return false
        }
        val began = interaction != Interaction.KEYBOARD
        if (began) {
            if (!prepareInteraction()) return false
            beginInteraction(Interaction.KEYBOARD)
        }
        val owner = generation
        // Prior-owner cancellation may change RTL direction or range/value.
        val target = nativeKeyTarget(checkNotNull(nativeKeyDirection(keyCode)))
        if (target == progress) {
            if (began) retireEmptyKeyboardAttempt(owner) else finishInteraction(false)
            return false
        }
        heldKeys[keyCode] = event.downTime
        try {
            // The retained bridge reports fromUser=true without native touch start/stop callbacks.
            setUserProgress(target)
        } catch (failure: Throwable) {
            try { cancelUserInteraction() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
        return true
    }

    private fun nativeKeyDirection(key: Int): Int? {
        val direction = when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MINUS -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> 1
            else -> return null
        }
        return if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -direction else direction
    }

    private fun nativeKeyTarget(direction: Int): Int =
        (progress.toLong() + direction.toLong() * keyProgressIncrement.toLong())
            .coerceIn(min.toLong(), max.toLong()).toInt()

    private fun retireEmptyKeyboardAttempt(owner: Long) {
        if (interaction != Interaction.KEYBOARD || generation != owner || interactionChanged) return
        interaction = Interaction.NONE
        heldKeys.clear()
        expectedUserValue = null
        generation++
    }

    // Retiring input clears heldKeys. Its later repeat DOWN must not acquire a new owner or
    // trigger a native boundary handoff that finishes a different, newer interaction.
    private fun isUnownedKeyRepeat(keyCode: Int, event: KeyEvent): Boolean =
        event.repeatCount > 0 &&
            (interaction != Interaction.KEYBOARD || heldKeys[keyCode] != event.downTime)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyDirection(keyCode) != null && interaction == Interaction.KEYBOARD && heldKeys[keyCode] == event.downTime) {
            heldKeys.remove(keyCode)
            if (event.isCanceled || !isEnabled || heldKeys.isEmpty()) finishInteraction(event.isCanceled || !isEnabled)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val changesValue = action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id ||
            action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (!changesValue) return super.performAccessibilityAction(action, arguments)
        if (!isEnabled) return false
        if (valueMode == NativeSliderValueMode.CONTINUOUS) {
            val dispatch = ContinuousAccessibilityDispatch()
            continuousAccessibilityDispatches.add(dispatch)
            accessibilityDispatchDepth++
            return withCleanup({
                // The installed delegate owns admission. Never fall back after its rejection.
                val handled = super.performAccessibilityAction(action, arguments)
                handled && !dispatch.rejectedNativeMutation
            }, {
                accessibilityDispatchDepth--
                continuousAccessibilityDispatches.removeAt(continuousAccessibilityDispatches.lastIndex)
            })
        }
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id) {
            if (arguments?.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) != true ||
                !arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE).isFinite()) return false
        }
        if (!prepareInteraction()) return false
        var resolvedArguments = arguments
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id) {
            val requested = checkNotNull(arguments).getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)
            if (componentStyle.steps > 0) {
                // Native SeekBar truncates its Float argument before notifying listeners.
                // Resolve the discrete step first, while preserving its delegate/range route.
                resolvedArguments = Bundle(arguments).apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, snapRequested(requested.toDouble()).toFloat())
                }
            }
        }
        beginInteraction(Interaction.ACCESSIBILITY)
        accessibilityDispatchDepth++
        var handled = false
        return withCleanup({
            handled = super.performAccessibilityAction(action, resolvedArguments)
            handled
        }, {
            accessibilityDispatchDepth--
            if (interaction == Interaction.ACCESSIBILITY) finishInteraction(!handled)
        })
    }

    /** Float range handler for the shared delegate or an explicitly forwarding caller delegate. */
    fun performContinuousAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (valueMode != NativeSliderValueMode.CONTINUOUS || !isEnabled) return false
        val target = when (action) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> {
                if (arguments?.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) != true) return false
                arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)
            }
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> continuousDraft + (max.toDouble() - min.toDouble()).toFloat() / 20f
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> continuousDraft - (max.toDouble() - min.toDouble()).toFloat() / 20f
            else -> return false
        }
        if (!target.isFinite()) return false
        val clamped = target.coerceIn(min.toFloat(), max.toFloat())
        if (clamped == continuousDraft) return false
        // Reject before cancelling another input owner. Admission is rechecked after its cleanup.
        if (!admitContinuousValue(clamped)) return false
        val needsReadmission = interaction != Interaction.NONE
        if (!prepareInteraction()) return false
        // Cancellation may rebind the draft/range; relative actions use that current value.
        val currentTarget = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> continuousDraft + (max.toDouble() - min.toDouble()).toFloat() / 20f
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> continuousDraft - (max.toDouble() - min.toDouble()).toFloat() / 20f
            else -> target
        }.coerceIn(min.toFloat(), max.toFloat())
        if (currentTarget == continuousDraft) return false
        beginInteraction(Interaction.ACCESSIBILITY)
        var handled = false
        var completed = false
        return withCleanup({
            handled = setContinuousUserValue(currentTarget, admissionChecked = !needsReadmission)
            completed = true
            handled
        }, {
            if (interaction == Interaction.ACCESSIBILITY) {
                if (completed && !handled && !interactionChanged) finishContinuousInteraction(true, notify = false)
                else finishInteraction(!handled)
            }
        })
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (!ready || valueMode != NativeSliderValueMode.CONTINUOUS) return
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
            min.toFloat(), max.toFloat(), continuousDraft)
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        if (isEnabled && max > min) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            if (continuousDraft < max.toFloat()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            if (continuousDraft > min.toFloat()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        withCleanup({
            if (ready && !gainFocus && interaction == Interaction.KEYBOARD) cancelUserInteraction()
        }, { super.onFocusChanged(gainFocus, direction, previouslyFocusedRect) })
    }

    override fun onDetachedFromWindow() {
        withCleanup({ cancelUserInteraction() }, {
            removeCallbacks(continuousAnnouncement)
            continuousAnnouncementPending = false
            generation++
            super.onDetachedFromWindow()
        })
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        withCleanup({
            if (ready && !hasWindowFocus) cancelUserInteraction()
        }, { super.onWindowFocusChanged(hasWindowFocus) })
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (ready && visibility != View.VISIBLE) cancelUserInteraction()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (ready && visibility != View.VISIBLE) cancelUserInteraction()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyGeometry()
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (ready) {
            sliderPaint.onEnabledChanged()
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val desiredHeight = max(measuredHeight, max(minimumHeight,
            ceil(componentStyle.thumbHeightDp * resources.displayMetrics.density).toInt() + paddingTop + paddingBottom))
        setMeasuredDimension(
            resolveSizeAndState(max(measuredWidth, minimumWidth), widthMeasureSpec, measuredState),
            resolveSizeAndState(desiredHeight, heightMeasureSpec, measuredState shl MEASURED_HEIGHT_STATE_SHIFT),
        )
    }

    override fun onDraw(canvas: Canvas) {
        // SeekBar owns input/range; shared paint owns all visual geometry and RTL mirroring.
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val trackWidth = (right - left).coerceAtLeast(0f)
        if (trackWidth <= 0f) return
        val density = resources.displayMetrics.density
        val trackHeight = componentStyle.trackHeightDp * density
        val outerRadius = trackHeight / 2f
        val centerY = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val range = max.toLong() - min.toLong()
        val fraction = if (range <= 0L) 0f else if (valueMode == NativeSliderValueMode.CONTINUOUS)
            ((continuousDraft.toDouble() - min.toDouble()) / range).toFloat().coerceIn(0f, 1f)
            else ((progress.toLong() - min.toLong()).toDouble() / range).toFloat().coerceIn(0f, 1f)
        val valueX = if (componentStyle.steps > 0 && fraction > 0f && fraction < 1f) {
            outerRadius + (trackWidth - outerRadius * 2f).coerceAtLeast(0f) * fraction
        } else trackWidth * fraction
        val thumbWidth = visibleThumbWidthDp() * density
        val gap = if (componentStyle.thumbTrackGapDp > 0f) thumbWidth / 2f + componentStyle.thumbTrackGapDp * density else 0f
        val insideRadius = componentStyle.trackInsideCornerDp * density
        val activeEnd = (valueX - gap).coerceIn(0f, trackWidth)
        val inactiveStart = (valueX + gap).coerceIn(0f, trackWidth)
        val saved = canvas.save()
        canvas.translate(if (layoutDirection == View.LAYOUT_DIRECTION_RTL) right else left, centerY)
        if (layoutDirection == View.LAYOUT_DIRECTION_RTL) canvas.scale(-1f, 1f)
        // Match the recovered default track's RTL start-corner threshold as well as its shape.
        val activeThreshold = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) insideRadius else outerRadius
        if (activeEnd > activeThreshold) {
            sliderPaint.drawSegment(canvas, 0f, activeEnd, 0f, trackHeight, outerRadius, insideRadius, sliderPaint.activeTrack)
        }
        if (valueX < trackWidth - gap - outerRadius) {
            sliderPaint.drawSegment(canvas, inactiveStart, trackWidth, 0f, trackHeight, insideRadius, outerRadius,
                sliderPaint.inactiveTrack)
            if (componentStyle.showStopIndicator && componentStyle.stopIndicatorSizeDp > 0f) {
                sliderPaint.drawDot(canvas, trackWidth - outerRadius, 0f, componentStyle.stopIndicatorSizeDp * density / 2f,
                    sliderPaint.activeTrack)
            }
        }
        if (componentStyle.steps > 0 && componentStyle.tickSizeDp > 0f) {
            val intervals = componentStyle.steps + 1
            // Coalesce sub-pixel overdraw for large caller-specified ranges without capping values.
            val stride = ceil(intervals.toDouble() / trackWidth.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
            var index = 0
            while (index <= intervals) {
                if (!(componentStyle.showStopIndicator && index == intervals)) {
                    val tickX = outerRadius + (trackWidth - 2f * outerRadius).coerceAtLeast(0f) * (index.toFloat() / intervals)
                    if (tickX < valueX - gap || tickX > valueX + gap) {
                        sliderPaint.drawDot(canvas, tickX, 0f, componentStyle.tickSizeDp * density / 2f,
                            if (tickX <= valueX) sliderPaint.activeTick else sliderPaint.inactiveTick)
                    }
                }
                if (intervals - index < stride) break
                index += stride
            }
            if (!componentStyle.showStopIndicator && intervals % stride != 0) {
                val tickX = outerRadius + (trackWidth - 2f * outerRadius).coerceAtLeast(0f)
                if (tickX < valueX - gap || tickX > valueX + gap) {
                    sliderPaint.drawDot(canvas, tickX, 0f, componentStyle.tickSizeDp * density / 2f,
                        if (tickX <= valueX) sliderPaint.activeTick else sliderPaint.inactiveTick)
                }
            }
        }
        sliderPaint.drawThumb(canvas, valueX, 0f, thumbWidth / 2f, componentStyle.thumbHeightDp * density / 2f)
        canvas.restoreToCount(saved)
    }

    /** Public cancellation callbacks run before an outer caller may claim ownership. */
    private fun prepareInteraction(): Boolean {
        // Native touch callbacks can precede more SeekBar mutations in the same frame.
        if (touchDispatchDepth > 0) return false
        val policy = componentKeyboardPolicy
        val expectedGeneration = generation + if (interaction != Interaction.NONE) 1L else 0L
        if (interaction != Interaction.NONE) cancelUserInteraction()
        return isEnabled && interaction == Interaction.NONE && componentKeyboardPolicy == policy &&
            generation == expectedGeneration
    }

    private fun beginInteraction(kind: Interaction) {
        generation++
        interaction = kind
        interactionStart = progress
        interactionChanged = false
        lastUserValue = null
        heldKeys.clear()
        cancelledTouchProgress = null
        if (valueMode == NativeSliderValueMode.CONTINUOUS) {
            continuousStart = continuousDraft
            lastUserContinuousValue = null
            nativeTouchAccepted = false
            continuousTouchSample = null
        }
        if (kind == Interaction.TOUCH) applyGeometry()
    }

    private fun finishInteraction(cancelled: Boolean) {
        if (interaction == Interaction.NONE) return
        if (valueMode == NativeSliderValueMode.CONTINUOUS) {
            finishContinuousInteraction(cancelled)
            return
        }
        val wasTouch = interaction == Interaction.TOUCH
        val value = progress
        val acceptedRevision = lastUserBindingRevision
        val changed = interactionChanged && value != interactionStart && lastUserValue == value &&
            acceptedRevision == bindingRevision
        interaction = Interaction.NONE
        interactionChanged = false
        heldKeys.clear()
        expectedUserValue = null
        val finishedGeneration = ++generation
        if (wasTouch) applyGeometry()
        onInteractionFinished?.invoke(value, cancelled)
        if (!cancelled && changed && isEnabled && generation == finishedGeneration && progress == value &&
            bindingRevision == acceptedRevision) {
            onValueCommitted?.invoke(value)
        }
    }

    private fun finishContinuousInteraction(cancelled: Boolean, notify: Boolean = true) {
        val wasTouch = interaction == Interaction.TOUCH
        val value = continuousDraft
        val acceptedRevision = lastUserBindingRevision
        val changed = interactionChanged && value != continuousStart && lastUserContinuousValue == value &&
            acceptedRevision == bindingRevision
        interaction = Interaction.NONE
        interactionChanged = false
        nativeTouchAccepted = false
        continuousTouchSample = null
        heldKeys.clear()
        expectedUserValue = null
        val finishedGeneration = ++generation
        if (wasTouch) applyGeometry()
        if (!notify) return
        onContinuousInteractionFinished?.invoke(value, cancelled)
        if (!cancelled && changed && isEnabled && generation == finishedGeneration && continuousDraft == value &&
            bindingRevision == acceptedRevision) onContinuousValueCommitted?.invoke(value)
    }

    private fun bindContinuousValueInternal(value: Float, notifyMirror: Boolean) {
        require(value.isFinite())
        val previousMirror = continuousDraft.toInt()
        continuousDraft = value.coerceIn(min.toFloat(), max.toFloat())
        bindingRevision++ // A same-integer replacement still supersedes the accepted user draft.
        setProgressWithoutCallback(continuousDraft.toInt())
        invalidate()
        if (notifyMirror && previousMirror != progress) callerListener?.onProgressChanged(this, progress, false)
    }

    private fun admitContinuousValue(value: Float): Boolean {
        val owner = generation
        val revision = bindingRevision
        return (onContinuousValueRequest?.invoke(value) != false) && isEnabled &&
            generation == owner && bindingRevision == revision
    }

    private fun setContinuousUserValue(value: Float, admissionChecked: Boolean = false): Boolean {
        if (!value.isFinite() || interaction == Interaction.NONE || !isEnabled) return false
        val clamped = value.coerceIn(min.toFloat(), max.toFloat())
        if (clamped == continuousDraft || (!admissionChecked && !admitContinuousValue(clamped))) return false
        val previousMirror = continuousDraft.toInt()
        val owner = generation
        val revision = bindingRevision
        continuousDraft = clamped
        interactionChanged = true
        lastUserContinuousValue = clamped
        lastUserBindingRevision = revision
        setProgressWithoutCallback(clamped.toInt())
        invalidate()
        if (previousMirror != progress) callerListener?.onProgressChanged(this, progress, true)
        if (generation == owner && bindingRevision == revision && continuousDraft == clamped) {
            onContinuousValueChanged?.invoke(clamped)
        }
        if (isAttachedToWindow && !continuousAnnouncementPending) {
            continuousAnnouncementPending = true
            postDelayed(continuousAnnouncement, 200L)
        }
        return true
    }

    private fun flushContinuousTouchSample() {
        val sample = continuousTouchSample ?: return
        if (sample.consumed || !nativeTouchAccepted || cancellingTouch || interaction != Interaction.TOUCH ||
            sample.generation != generation || sample.revision != bindingRevision) return
        sample.consumed = true
        val available = width - paddingLeft - paddingRight
        if (available <= 0 || !sample.x.isFinite()) return
        val position = ((sample.x - paddingLeft) / available).coerceIn(0f, 1f)
        val fraction = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) 1f - position else position
        val value = (min.toDouble() + (max.toDouble() - min.toDouble()) * fraction).toFloat()
        setContinuousUserValue(value)
    }

    private fun setUserProgress(value: Int) {
        if (value == progress) return
        expectedUserValue = value
        try { super.setProgress(value, false) } finally { expectedUserValue = null }
    }

    private fun setProgressWithoutCallback(value: Int) {
        suppressNativeCallback++
        try { super.setProgress(value, false) } finally { suppressNativeCallback-- }
    }

    private fun snap(value: Int): Int = snapRequested(value.toDouble())

    private fun snapRequested(value: Double): Int {
        if (componentStyle.steps == 0 || max <= min) return value.coerceIn(min.toDouble(), max.toDouble()).toInt()
        val intervals = componentStyle.steps.toLong() + 1L
        val range = max.toLong() - min.toLong()
        val fraction = (value.coerceIn(min.toDouble(), max.toDouble()) - min.toDouble()) / range
        val index = (fraction * intervals).roundToLong().coerceIn(0L, intervals)
        return (min.toLong() + (range.toDouble() * index / intervals).roundToLong()).coerceIn(min.toLong(), max.toLong()).toInt()
    }

    private fun keyDirection(key: Int): Int? = when (key) {
        KeyEvent.KEYCODE_DPAD_LEFT -> if (layoutDirection == View.LAYOUT_DIRECTION_RTL) 1 else -1
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        KeyEvent.KEYCODE_DPAD_UP -> if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        KeyEvent.KEYCODE_DPAD_DOWN -> if (layoutDirection == View.LAYOUT_DIRECTION_RTL) 1 else -1
        KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> 1
        KeyEvent.KEYCODE_MINUS -> -1
        KeyEvent.KEYCODE_MOVE_HOME -> KEY_HOME
        KeyEvent.KEYCODE_MOVE_END -> KEY_END
        KeyEvent.KEYCODE_PAGE_UP -> KEY_PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> KEY_PAGE_DOWN
        else -> null
    }

    private fun visibleThumbWidthDp(): Float = if (interaction == Interaction.TOUCH) componentStyle.pressedThumbWidthDp else componentStyle.thumbWidthDp

    private fun applyGeometry() {
        val style = componentStyle
        val density = resources.displayMetrics.density
        minimumHeight = context.componentDp(style.minHeightDp)
        minimumWidth = context.componentDp(style.minWidthDp)
        trackGeometry.width = 0
        trackGeometry.height = context.componentDp(style.trackHeightDp)
        thumbGeometry.width = context.componentDp(style.thumbWidthDp).coerceAtLeast(1)
        thumbGeometry.height = context.componentDp(style.thumbHeightDp).coerceAtLeast(1)
        thumbOffset = thumbGeometry.width / 2
        val horizontal = ((style.horizontalPaddingDp + visibleThumbWidthDp() / 2f) * density).roundToInt()
        val vertical = context.componentDp(style.verticalPaddingDp)
        setPaddingRelative(horizontal, vertical, horizontal, vertical)
        requestLayout()
        invalidate()
    }

    private class GeometryDrawable : Drawable() {
        var width = 0
        var height = 0
        override fun getIntrinsicWidth() = width
        override fun getIntrinsicHeight() = height
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSPARENT
        override fun onLevelChange(level: Int): Boolean { invalidateSelf(); return true }
    }

    private data class ContinuousTouchSample(val x: Float, val generation: Long, val revision: Long, var consumed: Boolean = false)
    private class ContinuousAccessibilityDispatch(var rejectedNativeMutation: Boolean = false)

    private enum class Interaction { NONE, TOUCH, KEYBOARD, ACCESSIBILITY }
    private companion object {
        const val KEY_HOME = -10
        const val KEY_END = 10
        const val KEY_PAGE_UP = -20
        const val KEY_PAGE_DOWN = 20
        inline fun <T> withCleanup(block: () -> T, cleanup: () -> Unit): T {
            var failure: Throwable? = null
            try { return block() } catch (error: Throwable) { failure = error; throw error }
            finally {
                try { cleanup() } catch (error: Throwable) {
                    if (failure == null) throw error else failure.addSuppressed(error)
                }
            }
        }
    }
}
