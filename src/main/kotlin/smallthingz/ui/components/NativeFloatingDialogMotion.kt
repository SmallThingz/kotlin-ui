/*
 * Native adaptation of AndroidX Compose UI/Animation behavior.
 * Copyright 2020 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0; see LICENSE.txt and SOURCE-dialogs.json.
 */

package smallthingz.ui.components

import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.AnimationVector2D
import smallthingz.ui.animation.core.FastOutSlowInEasing
import smallthingz.ui.animation.core.FiniteAnimationSpec
import smallthingz.ui.animation.core.TargetBasedAnimation
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.spring
import smallthingz.ui.animation.core.tween
import smallthingz.ui.unit.IntOffset
import smallthingz.ui.animation.predictiveBackCommitDurationMillis
import kotlin.math.roundToLong

/**
 * Render-free sampling of the floating dialog's Transition channels. The host supplies frame timestamps
 * and global duration scale; no timer, View, composition or worker is owned here.
 *
 * Interrupted tweens use Transition's default springs (Float threshold .01, IntOffset threshold 1).
 * Target changes retain the last sampled value and velocity, not a resampled wall-clock approximation.
 */
internal class NativeFloatingVisibilityMotion {
    private var firstFrameNanos = Long.MIN_VALUE
    private var playTimeNanos = 0L
    private var offsetStartNanos = 0L
    private var alphaStartNanos = 0L
    private var offset = TargetBasedAnimation(tween<IntOffset>(220), IntOffset.VectorConverter,
        IntOffset.Zero, IntOffset.Zero)
    private var fade = TargetBasedAnimation(tween<Float>(160), Float.VectorConverter, 0f, 1f)
    private var offsetVelocity = AnimationVector2D(0f, 0f)
    private var alphaVelocity = AnimationVector1D(0f)
    private var offsetFinished = false
    private var alphaFinished = false
    private var configured = false
    private var fullHeight = 0

    var translation: IntOffset = IntOffset.Zero
        private set
    var alpha: Float = 0f
        private set
    var exiting = false
        private set
    val finished: Boolean get() = configured && offsetFinished && alphaFinished

    /** A settled pre-measure exit still needs one frame to deliver its owner callback. */
    fun needsFrame(predictiveContinuation: Boolean, exitDelivered: Boolean): Boolean =
        predictiveContinuation || (configured && (!finished || (exiting && !exitDelivered)))

    /** Called after actual content measurement, before its first presented frame. */
    fun setHeight(height: Int) {
        if (!configured) {
            configured = true
            fullHeight = height
            translation = IntOffset(0, -height)
            offset = TargetBasedAnimation(tween(220), IntOffset.VectorConverter, translation, IntOffset.Zero)
            if (exiting) {
                offset = TargetBasedAnimation(tween(0), IntOffset.VectorConverter, translation, translation)
                fade = TargetBasedAnimation(tween(0), Float.VectorConverter, 0f, 0f)
                offsetFinished = true
                alphaFinished = true
            }
            return
        }
        if (fullHeight == height) return
        fullHeight = height
        // While entering the target remains zero, so source Transition does not rebuild the channel.
        if (exiting) retargetOffset(IntOffset(0, -height), tween(190))
    }

    fun requestExit() {
        if (exiting) return
        if (finished) {
            // Transition stops its frame loop at idle and starts a fresh clock for the next segment.
            firstFrameNanos = Long.MIN_VALUE
            playTimeNanos = 0L
            offsetStartNanos = 0L
            alphaStartNanos = 0L
        }
        exiting = true
        if (!configured) return
        retargetOffset(IntOffset(0, -fullHeight), tween(190))
        val spec: FiniteAnimationSpec<Float> =
            if (alphaFinished) tween(140) else spring(visibilityThreshold = .01f)
        fade = TargetBasedAnimation(spec, Float.VectorConverter, alpha, 0f, alphaVelocity)
        alphaStartNanos = playTimeNanos
        alphaFinished = false
    }

    private fun retargetOffset(target: IntOffset, requestedSpec: FiniteAnimationSpec<IntOffset>) {
        if (offset.targetValue == target) return
        val spec = if (offsetFinished) requestedSpec
            else spring(visibilityThreshold = IntOffset(1, 1))
        offset = TargetBasedAnimation(spec, IntOffset.VectorConverter, translation, target, offsetVelocity)
        offsetStartNanos = playTimeNanos
        offsetFinished = false
    }

    fun frame(frameTimeNanos: Long, durationScale: Float) {
        if (!configured) return
        if (firstFrameNanos == Long.MIN_VALUE) firstFrameNanos = frameTimeNanos
        val delta = frameTimeNanos - firstFrameNanos
        playTimeNanos = if (durationScale == 0f) delta else (delta / durationScale.toDouble()).roundToLong()
        // Transition skips finished channels. Its StartDelay wrapper holds each initial value and
        // velocity if a changed duration scale rewinds global play time behind that channel's start.
        if (!offsetFinished) {
            val offsetTime = if (durationScale == 0f) offset.durationNanos
                else (playTimeNanos - offsetStartNanos).coerceAtLeast(0L)
            translation = offset.getValueFromNanos(offsetTime)
            offsetVelocity = offset.getVelocityVectorFromNanos(offsetTime)
            offsetFinished = offset.isFinishedFromNanos(offsetTime)
        }
        if (!alphaFinished) {
            val alphaTime = if (durationScale == 0f) fade.durationNanos
                else (playTimeNanos - alphaStartNanos).coerceAtLeast(0L)
            alpha = fade.getValueFromNanos(alphaTime)
            alphaVelocity = fade.getVelocityVectorFromNanos(alphaTime)
            alphaFinished = fade.isFinishedFromNanos(alphaTime)
        }
    }
}

/**
 * Source-derived predictive Back progress contract. Native dispatcher owns stream admission;
 * Cancellation may opt into a finite return; other callers retain immediate stream retirement.
 * Commit keeps the source remaining*220ms behavior.
 */
class NativeFloatingPredictiveMotion(private val animateCancellation: Boolean = false) {
    private var continuation: TargetBasedAnimation<Float, AnimationVector1D>? = null
    private var firstFrameNanos = Long.MIN_VALUE
    private var committedContinuation = false
    private var rawAnchor: Float? = null
    private var heldProgress = 0f
    var progress = 0f
        private set
    var active = false
        private set
    var commitCompleted = false
        private set
    val hasContinuation get() = continuation != null

    fun started(rawProgress: Float = 0f) {
        val returning = animateCancellation && continuation != null && !committedContinuation
        heldProgress = if (returning) progress else 0f
        rawAnchor = if (returning) rawProgress.coerceIn(0f, 1f) else null
        continuation = null
        firstFrameNanos = Long.MIN_VALUE
        progress = heldProgress
        active = true
        commitCompleted = false
    }

    fun progressed(value: Float) {
        continuation = null
        firstFrameNanos = Long.MIN_VALUE
        val raw = value.coerceIn(0f, 1f)
        val anchor = rawAnchor
        progress = when {
            anchor == null -> raw
            raw == anchor -> heldProgress
            raw == 1f -> 1f
            raw < anchor -> heldProgress * raw / anchor
            else -> heldProgress + (1f - heldProgress) * (raw - anchor) / (1f - anchor)
        }
        active = true
    }

    fun cancelled() {
        commitCompleted = false
        if (animateCancellation && active && progress > 0f) {
            // Duplicate cancellation must not restart the return clock.
            if (continuation == null || committedContinuation) continueTo(0f, 160, false)
        } else {
            continuation = null
            firstFrameNanos = Long.MIN_VALUE
            active = false
        }
    }

    /** Ordinary Back reverses a return; repeated commits still start a new empty stream. */
    fun ordinaryBack(): Boolean {
        if (animateCancellation && continuation != null && !committedContinuation) return committed()
        disable()
        started()
        return committed()
    }

    /** True requires immediate terminal delivery, with no additional frame request. */
    fun committed(): Boolean {
        if (!active) started() // Keyboard/three-button Back supplies an empty progress stream.
        val durationMillis = predictiveBackCommitDurationMillis(progress)
        if (durationMillis == 0) {
            continuation = null
            firstFrameNanos = Long.MIN_VALUE
            progress = 1f
            commitCompleted = true
            return true
        }
        continueTo(1f, durationMillis, true)
        return false
    }

    fun disable() {
        continuation = null
        firstFrameNanos = Long.MIN_VALUE
        if (!commitCompleted) {
            progress = 0f
            active = false
        }
    }

    fun finishCommitCallback() { active = false }

    private fun continueTo(target: Float, durationMillis: Int, committed: Boolean) {
        committedContinuation = committed
        continuation = TargetBasedAnimation(
            tween(durationMillis, easing = FastOutSlowInEasing), Float.VectorConverter, progress, target)
        firstFrameNanos = Long.MIN_VALUE
    }

    /** True means source onBack must run now, after the terminal frame value was applied. */
    fun frame(frameTimeNanos: Long, durationScale: Float): Boolean {
        val animation = continuation ?: return false
        if (firstFrameNanos == Long.MIN_VALUE) firstFrameNanos = frameTimeNanos
        val playTime = if (durationScale == 0f) animation.durationNanos
            else ((frameTimeNanos - firstFrameNanos) / durationScale).toLong()
        progress = animation.getValueFromNanos(playTime)
        if (!animation.isFinishedFromNanos(playTime)) return false
        continuation = null
        commitCompleted = committedContinuation
        // Source invokes onBack before clearing gestureActive in finally.
        if (!committedContinuation) active = false
        return committedContinuation
    }
}
