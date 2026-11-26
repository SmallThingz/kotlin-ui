/*
 * Copyright 2020 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 * Source-derived from foundation 1.11.2 Scroll.kt / Scrollable.kt.
 */
package smallthingz.ui.scroll

import smallthingz.ui.animation.core.FloatDecayAnimation
import smallthingz.ui.animation.core.FloatDecayAnimationSpec
import smallthingz.ui.foundation.MutatePriority
import smallthingz.ui.foundation.MutatorMutex
import smallthingz.ui.util.fastRoundToInt
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope

/** One retained instance per fixed onboarding page; no Snapshot state or View ownership. */
class NativeVerticalScrollState(initial: Int = 0) {
    var value = initial
        private set
    var maxValue = Int.MAX_VALUE
        private set
    var viewportSize = 0
        private set
    var contentSize = 0
        private set
    private var accumulator = 0f
    private var positionObservers: Array<() -> Unit> = emptyArray()
    private val scrollMutex = MutatorMutex()
    var isScrollInProgress = false
        private set

    /** Shared by every actual/preview host of this retained page, like DefaultScrollableState. */
    internal suspend fun <R> mutate(priority: MutatePriority, block: suspend () -> R): R =
        coroutineScope {
            scrollMutex.mutate(priority) {
                isScrollInProgress = true
                try {
                    block()
                } finally {
                    isScrollInProgress = false
                }
            }
        }

    /** Direct host binding, including a simultaneously drawn predictive preview of this state. */
    fun observePosition(observer: () -> Unit): AutoCloseable {
        positionObservers += observer
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                positionObservers = positionObservers.filterNot { it === observer }.toTypedArray()
            }
        }
    }

    private fun notifyPositionChanged() {
        // Immutable array per registration: a callback may retire a host without iterator mutation
        // or allocating a callback snapshot on every scroll frame. All access is on the UI thread.
        for (observer in positionObservers) observer()
    }

    val canScrollForward get() = value < maxValue
    val canScrollBackward get() = value > 0
    var lastScrolledForward = false
        private set
    var lastScrolledBackward = false
        private set

    /**
     * Source layout publishes the new bound before placing the child, clamps only a value above
     * it, and does not clear the fractional accumulator. Layout uses -value for non-reversed Y.
     */
    fun measured(viewportHeight: Int, contentHeight: Int) {
        viewportSize = viewportHeight
        contentSize = contentHeight
        maxValue = (contentHeight - viewportHeight).coerceAtLeast(0)
        if (value > maxValue) {
            value = maxValue
            notifyPositionChanged()
        }
    }

    /** Saved state contains only the integer, exactly like ScrollState.Saver. */
    fun save(): Int = value

    /** Equivalent to ScrollScope.scrollBy: NaN is ignored and direction flags follow consumption. */
    fun scrollBy(delta: Float): Float {
        if (delta.isNaN()) return 0f
        val consumed = dispatchRawDelta(delta)
        lastScrolledForward = consumed > 0f
        lastScrolledBackward = consumed < 0f
        return consumed
    }

    /** Raw nested dispatch deliberately has the source's weaker bookkeeping semantics. */
    fun dispatchRawDelta(delta: Float): Float {
        val absolute = value + delta + accumulator
        val newValue = absolute.coerceIn(0f, maxValue.toFloat())
        val changed = absolute != newValue
        val consumed = newValue - value
        val consumedInt = consumed.fastRoundToInt()
        value += consumedInt
        accumulator = consumed - consumedInt
        if (consumedInt != 0) notifyPositionChanged()
        return if (changed) consumed else delta
    }
}

enum class NativeVerticalScrollSource { USER_INPUT, SIDE_EFFECT }

/**
 * Host passes physical/finger deltas: down is positive; state scroll offsets reverse that sign.
 * Keyboard PageDown passes -viewportHeight, exactly like ScrollableNode.
 *
 * This callback is synchronous, like the pinned nested pre/self/post-scroll dispatch. Parent
 * arbitration and overscroll wrap this callback; do not silently replace them with no-ops.
 */
interface NativeVerticalScrollHost {
    fun preScroll(availableY: Float, source: NativeVerticalScrollSource): Float
    fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource): Float
    fun positionChanged(value: Int)
}

/** Nested scrolling math shared by admitted drag, key and fling deltas; owns no gesture detector. */
class NativeVerticalScrollEngine(
    val state: NativeVerticalScrollState,
    private val host: NativeVerticalScrollHost,
) {
    fun dispatchWithOverscroll(physicalY: Float, source: NativeVerticalScrollSource,
        overscroll: NativeVerticalOverscroll): Float =
        if (state.canScrollForward || state.canScrollBackward) {
            overscroll.scroll(physicalY, source) { dispatch(it, source) }
        } else {
            dispatch(physicalY, source)
        }

    fun dispatch(physicalY: Float, source: NativeVerticalScrollSource): Float {
        val before = state.value
        val consumedPre = host.preScroll(physicalY, source)
        val available = physicalY - consumedPre
        val consumedSelf = -state.scrollBy(-available)
        if (state.value != before) host.positionChanged(state.value)
        val consumedPost = host.postScroll(consumedSelf, available - consumedSelf, source)
        return consumedPre + consumedSelf + consumedPost
    }

    /** Ancestor post-scroll consumes locally only, without ordinary direction bookkeeping. */
    fun dispatchRaw(physicalY: Float): Float {
        val before = state.value
        val consumed = -state.dispatchRawDelta(-physicalY)
        if (state.value != before) host.positionChanged(state.value)
        return consumed
    }

    /**
     * Call at the accepted UserInput mutation boundary after canceling an earlier fling.
     * Page keys are immediate one-viewport changes. Other key/focus behavior belongs to the host.
     */
    fun pageKey(pageDown: Boolean, keyDown: Boolean, ctrlPressed: Boolean, enabled: Boolean): Boolean {
        if (!enabled || !keyDown || ctrlPressed) return false
        val amount = if (pageDown) -state.viewportSize.toFloat() else state.viewportSize.toFloat()
        dispatch(amount, NativeVerticalScrollSource.USER_INPUT)
        return true
    }
}

/**
 * Source play time for a single scroll or snap. Reset when a new animation starts.
 * Supply monotonic frame timestamps and the live nonnegative animator scale. Zero selects the
 * terminal sample. Positive scales apply to the interval since the previous frame.
 */
class ScrollPlayTime {
    private var previousFrame = Long.MIN_VALUE
    private var elapsed = 0.0

    fun reset() {
        previousFrame = Long.MIN_VALUE
        elapsed = 0.0
    }

    fun advance(frame: Long, scale: Float): Long {
        require(scale >= 0f)
        if (scale == 0f) return Long.MAX_VALUE
        if (previousFrame != Long.MIN_VALUE) {
            elapsed += (frame - previousFrame).coerceAtLeast(0L) / scale.toDouble()
        }
        previousFrame = frame
        // Source converts elapsed nanos through Float before its millisecond-based sampler.
        return elapsed.toFloat().toLong()
    }
}

/**
 * Scalar sampling of DefaultFlingBehavior. The owner supplies a spline for its current density.
 *
 * [consume] receives logical ScrollState deltas. The host reverses to physical Y, wraps nested
 * dispatch in overscroll, and reverses consumption back. Callbacks remain synchronous.
 */
class NativeVerticalFling(
    private val decay: FloatDecayAnimationSpec,
    private val consume: (Float) -> Float,
    private val finished: (remainingVelocity: Float) -> Unit,
) {
    private var animation: FloatDecayAnimation? = null
    private val clock = ScrollPlayTime()
    private var lastValue = 0f
    var remainingVelocity = 0f
        private set
    val isRunning get() = animation != null

    /**
     * Called only after current overscroll/pre-fling ownership is resolved. There is no
     * platform ViewConfiguration minimum-fling threshold: source admits abs(velocity)>1f.
     */
    fun start(initialVelocity: Float) {
        check(!isRunning) { "Retire the previous fling sampler first" }
        remainingVelocity = initialVelocity
        lastValue = 0f
        clock.reset()
        if (!(abs(initialVelocity) > 1f)) {
            finished(initialVelocity)
            return
        }
        animation = FloatDecayAnimation(decay, initialVelocity)
    }

    fun frame(frameTimeNanos: Long) = frame(frameTimeNanos, 1f)

    fun frame(frameTimeNanos: Long, durationScale: Float) {
        val current = animation ?: return
        val playTimeNanos = clock.advance(frameTimeNanos, durationScale)
        val value = current.getValueFromNanos(playTimeNanos)
        val velocity = current.getVelocityFromNanos(playTimeNanos)
        val delta = value - lastValue
        // A disabled animation crosses the entire remaining path in this callback. Until the host
        // tells us how much moved, its incoming velocity is still the correct boundary handoff.
        if (durationScale != 0f) remainingVelocity = velocity
        val consumed = consume(delta)
        if (animation !== current) return // The admitted mutation may retire this owner during consumption.
        if (durationScale == 0f) {
            remainingVelocity = when {
                delta == 0f -> velocity
                consumed * delta <= 0f -> remainingVelocity
                abs(consumed) >= abs(delta) -> velocity
                else -> velocityAtBoundary(current, lastValue + consumed, delta > 0f)
            }
        }
        lastValue = value
        if (abs(delta - consumed) > .5f || current.isFinishedFromNanos(playTimeNanos)) finish()
    }

    /** Only a clipped terminal jump needs to locate its boundary on the monotone decay curve. */
    private fun velocityAtBoundary(current: FloatDecayAnimation, position: Float, forward: Boolean): Float {
        var low = 0L
        var high = current.durationNanos
        while (low < high) {
            val middle = low + (high - low) / 2
            val sample = current.getValueFromNanos(middle)
            val beforeBoundary = if (forward) sample < position else sample > position
            if (beforeBoundary) low = middle + 1 else high = middle
        }
        return current.getVelocityFromNanos(low)
    }

    /** Retains sampled velocity; the coroutine owner decides whether source permits continuation. */
    fun cancel() {
        if (animation != null) finish()
    }

    private fun finish() {
        val remaining = remainingVelocity
        animation = null
        finished(remaining)
    }
}
