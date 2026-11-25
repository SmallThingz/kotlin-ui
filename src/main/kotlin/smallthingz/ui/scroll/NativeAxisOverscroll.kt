/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Scalar-axis adaptation of foundation 1.11.2 AndroidOverscroll.android.kt.
package smallthingz.ui.scroll

import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderNode
import android.os.Build
import android.view.MotionEvent
import android.widget.EdgeEffect
import androidx.annotation.RequiresApi
import smallthingz.ui.unit.Density
import smallthingz.ui.scroll.NativeEdgeEffectCompat.absorbToRelaxIfNeeded
import smallthingz.ui.scroll.NativeEdgeEffectCompat.distanceCompat
import smallthingz.ui.scroll.NativeEdgeEffectCompat.onAbsorbCompat
import smallthingz.ui.scroll.NativeEdgeEffectCompat.onPullDistanceCompat
import smallthingz.ui.scroll.NativeEdgeEffectCompat.onReleaseWithOppositeDelta
import kotlin.math.roundToInt

/**
 * Existing View's draw/touch companion. No added View/layer tree or permanent frame callback.
 * The one lazy RenderNode is the released API31 stretch backend, not an extra UI renderer.
 *
 * Positive physical deltas pull the left/top edge in both LTR and RTL. Logical page reversal
 * belongs to the caller. Each instance handles exactly one axis; it never consumes both axes.
 *
 * Caller supplies the released LocalOverscrollFactory color (Android default 0xff666666) and
 * its actual Density. Recreate on density/axis changes; color can rebind without retiring motion.
 */
open class NativeAxisOverscroll(
    private val context: Context,
    private val density: Density,
    glowColor: Int,
    private val axis: NativeOverscrollAxis,
    private val invalidate: () -> Unit,
) {
    private var currentGlowColor = glowColor
    private var width = 0
    private var height = 0
    private var pointerId = -1
    private var pointerCross = Float.NaN
    private var scrollCycle = false
    private var effectsEnabled = true
    private var effectGeneration = 0L
    private var near: EdgeEffect? = null
    private var far: EdgeEffect? = null
    private var nearNegation: EdgeEffect? = null
    private var farNegation: EdgeEffect? = null
    private var stretchRenderer: NativeAxisStretchRenderer? = null

    /** Suppress edge paint while preserving delegated scroll and fling motion. */
    fun setEffectsEnabled(value: Boolean) {
        if (effectsEnabled == value) return
        effectsEnabled = value
        if (!value) {
            dispose()
            invalidate()
        }
    }

    /** Palette-only update: retain every edge and its pull/fling state. */
    fun setGlowColor(color: Int) {
        if (currentGlowColor == color) return
        currentGlowColor = color
        forEachEffect(includeNegation = true) { it.color = color }
        invalidate()
    }

    /** Source uses distance, not isFinished: legacy glow is not reported as an active stretch. */
    val isInProgress get() = near.stretched || far.stretched
    private val EdgeEffect?.stretched get() = this != null && distanceCompat != 0f
    private val EdgeEffect?.animating get() = this != null && !isFinished

    /** Call at the draw boundary: source updateSize observes DrawScope.size, not layout callbacks. */
    fun measured(newWidth: Int, newHeight: Int) {
        val initial = width == 0 && height == 0
        val changed = width != newWidth || height != newHeight
        width = newWidth
        height = newHeight
        if (changed) forEachEffect(includeNegation = true) { it.setSize(crossExtent, mainExtent) }
        if (!initial && changed) release()
    }

    /** Observe every pointer event even when consumed elsewhere; retain last position after up. */
    fun pointer(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pointerId = event.getPointerId(0)
            pointerCross = crossPosition(event, 0)
            return
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pointerId = -1
            return
        }
        val lifted = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        val retained = (0 until event.pointerCount).firstOrNull {
            it != lifted && event.getPointerId(it) == pointerId
        }
        val index = retained ?: (0 until event.pointerCount).firstOrNull { it != lifted } ?: return
        pointerId = event.getPointerId(index)
        pointerCross = crossPosition(event, index)
    }

    /**
     * Wrap nested pre/self/post dispatch. Positive physicalDelta pulls the left/top edge, negative the right/bottom.
     * Only drag/UserInput leftovers create a new pull; SideEffect destretches at exactly 4x.
     */
    fun scroll(physicalDelta: Float, source: NativeOverscrollSource, performScroll: (Float) -> Float): Float {
        if (!effectsEnabled || width <= 0 || height <= 0) return performScroll(physicalDelta)
        val generation = effectGeneration
        if (!scrollCycle) {
            if (near.stretched) pullNear(0f)
            if (far.stretched) pullFar(0f)
            scrollCycle = true
        }
        val multiplier = if (source == NativeOverscrollSource.SIDE_EFFECT) 4f else 1f
        val destretch = physicalDelta * multiplier
        val preConsumed = when {
            physicalDelta == 0f -> 0f
            near.stretched && physicalDelta < 0f -> {
                val consumed = pullNear(destretch)
                if (!near.stretched) nearEffect().finish()
                if (consumed == destretch) physicalDelta else consumed / multiplier
            }
            far.stretched && physicalDelta > 0f -> {
                val consumed = pullFar(destretch)
                if (!far.stretched) farEffect().finish()
                if (consumed == destretch) physicalDelta else consumed / multiplier
            }
            else -> 0f
        }
        if (preConsumed != 0f) invalidate()
        val available = physicalDelta - preConsumed
        val consumed = performScroll(available)
        // Nested scroll may synchronously replace the presentation owning these effects.
        if (!effectsEnabled || generation != effectGeneration) return preConsumed + consumed
        val remainder = available - consumed
        if (available != 0f && consumed != 0f && isInProgress) release()
        var needsInvalidate = false
        if (source == NativeOverscrollSource.USER_INPUT) {
            if (remainder > .5f) { pullNear(remainder); needsInvalidate = true }
            else if (remainder < -.5f) { pullFar(remainder); needsInvalidate = true }
        }
        if (available != 0f) {
            if (near.animating && physicalDelta < 0f) {
                nearEffect().onReleaseWithOppositeDelta(physicalDelta)
                needsInvalidate = near.animating || needsInvalidate
            }
            if (far.animating && physicalDelta > 0f) {
                farEffect().onReleaseWithOppositeDelta(physicalDelta)
                needsInvalidate = far.animating || needsInvalidate
            }
        }
        if (needsInvalidate) invalidate()
        // Fresh overscroll is intentionally not added to consumption; this is the source contract.
        return preConsumed + consumed
    }

    class FlingStart internal constructor(
        val remainingVelocity: Float,
        internal val active: Boolean,
        internal val generation: Long,
    )

    /** Must precede nested pre-fling, exactly where OverscrollEffect wraps performFling. */
    fun beginFling(physicalVelocity: Float): FlingStart {
        val generation = effectGeneration
        if (!effectsEnabled || width <= 0 || height <= 0) {
            return FlingStart(physicalVelocity, false, generation)
        }
        val consumed = when {
            near.stretched && physicalVelocity < 0f ->
                nearEffect().absorbToRelaxIfNeeded(physicalVelocity, mainExtent.toFloat(), density)
            far.stretched && physicalVelocity > 0f ->
                -farEffect().absorbToRelaxIfNeeded(-physicalVelocity, mainExtent.toFloat(), density)
            else -> 0f
        }
        if (consumed != 0f) invalidate()
        return FlingStart(physicalVelocity - consumed, true, generation)
    }

    /**
     * After normal nested post-fling completion, including a zero-velocity drag release/cancel.
     * Do not call from a finally block if performFling itself was cancelled: the source has none.
     */
    fun finishFling(start: FlingStart, leftoverPhysicalVelocity: Float) {
        if (!effectsEnabled || !start.active || start.generation != effectGeneration) return
        scrollCycle = false
        if (leftoverPhysicalVelocity > 0f) nearEffect().onAbsorbCompat(leftoverPhysicalVelocity.roundToInt())
        else if (leftoverPhysicalVelocity < 0f) farEffect().onAbsorbCompat(-leftoverPhysicalVelocity.roundToInt())
        release()
    }

    private fun release() {
        var needs = false
        forEachEffect(includeNegation = false) {
            it.onRelease()
            needs = !it.isFinished || needs
        }
        if (needs) invalidate()
    }

    private val horizontal get() = axis == NativeOverscrollAxis.HORIZONTAL
    private val mainExtent get() = if (horizontal) width else height
    private val crossExtent get() = if (horizontal) height else width
    private fun crossPosition(event: MotionEvent, index: Int) =
        if (horizontal) event.getY(index) else event.getX(index)

    private fun nearDisplacement(): Float {
        val position = (if (pointerCross.isNaN()) crossExtent / 2f else pointerCross) / crossExtent
        return if (horizontal) 1f - position else position
    }

    private fun pullNear(delta: Float): Float {
        val edge = nearEffect()
        val consumed = edge.onPullDistanceCompat(delta / mainExtent, nearDisplacement()) * mainExtent
        return if (edge.distanceCompat != 0f) delta else consumed
    }

    private fun pullFar(delta: Float): Float {
        val edge = farEffect()
        val consumed = -edge.onPullDistanceCompat(-delta / mainExtent, farDisplacement()) * mainExtent
        return if (edge.distanceCompat != 0f) delta else consumed
    }

    private fun farDisplacement(): Float {
        val position = (if (pointerCross.isNaN()) crossExtent / 2f else pointerCross) / crossExtent
        return if (horizontal) position else 1f - position
    }

    private fun newEdge(): EdgeEffect = NativeEdgeEffectCompat.create(context).apply {
        color = currentGlowColor
        if (width != 0 || height != 0) setSize(crossExtent, mainExtent)
    }
    private fun nearEffect() = near ?: newEdge().also { near = it }
    private fun farEffect() = far ?: newEdge().also { far = it }
    private fun nearNegationEffect() = nearNegation ?: newEdge().also { nearNegation = it }
    private fun farNegationEffect() = farNegation ?: newEdge().also { farNegation = it }

    private inline fun forEachEffect(includeNegation: Boolean, action: (EdgeEffect) -> Unit) {
        near?.let(action); far?.let(action)
        if (includeNegation) { nearNegation?.let(action); farNegation?.let(action) }
    }

    /** Content keeps the viewport's own dimensions and source scroll clip; only the backend expands. */
    fun draw(canvas: Canvas, drawContent: (Canvas) -> Unit) {
        // DrawScope.size is the viewport's size. Canvas dimensions may include the parent's clip
        // expansion; only RenderNode bounds below use canvas.width/height, exactly as the source.
        if (!effectsEnabled || width <= 0 || height <= 0) { drawContent(canvas); return }
        if (Build.VERSION.SDK_INT >= 31) {
            if (!canvas.isHardwareAccelerated) {
                forEachEffect(includeNegation = true) { it.finish() }
                drawContent(canvas)
                return
            }
            val active = near.animating || nearNegation.stretched || far.animating || farNegation.stretched
            if (!active) { drawContent(canvas); return }
            val renderer = stretchRenderer ?: NativeAxisStretchRenderer(horizontal).also { stretchRenderer = it }
            renderer.draw(canvas, 30f * density.density, drawContent) { recordingCanvas ->
                var needs = false
                if (nearNegation.stretched) { drawRotated(nearNegationEffect(), recordingCanvas, if (horizontal) 90f else 180f); nearNegationEffect().finish() }
                if (near.animating) {
                    needs = drawRotated(nearEffect(), recordingCanvas, if (horizontal) 270f else 0f) || needs
                    if (near.stretched) nearNegationEffect().onPullDistanceCompat(nearEffect().distanceCompat, nearDisplacement())
                }
                if (farNegation.stretched) { drawRotated(farNegationEffect(), recordingCanvas, if (horizontal) 270f else 0f); farNegationEffect().finish() }
                if (far.animating) {
                    needs = drawRotated(farEffect(), recordingCanvas, if (horizontal) 90f else 180f) || needs
                    if (far.stretched) farNegationEffect().onPullDistanceCompat(farEffect().distanceCompat, farDisplacement())
                }
                if (needs) invalidate()
            }
        } else {
            drawContent(canvas)
            var needs = false
            if (near.animating) {
                if (!horizontal) needs = nearEffect().draw(canvas) || needs
                else {
                    val saved = canvas.save()
                    try {
                        canvas.rotate(270f)
                        canvas.translate(-height.toFloat(), 0f)
                        needs = nearEffect().draw(canvas) || needs
                    } finally { canvas.restoreToCount(saved) }
                }
            }
            if (far.animating) {
                val saved = canvas.save()
                try {
                    if (horizontal) {
                        canvas.rotate(90f)
                        canvas.translate(0f, -width.toFloat())
                    } else {
                        canvas.rotate(180f)
                        canvas.translate(-width.toFloat(), -height.toFloat())
                    }
                    needs = farEffect().draw(canvas) || needs
                } finally { canvas.restoreToCount(saved) }
            }
            if (needs) invalidate()
        }
    }

    /** Retire effects and outstanding fling handles; a retained owner can start a fresh cycle. */
    fun dispose() {
        effectGeneration++
        scrollCycle = false
        forEachEffect(includeNegation = true) { it.finish() }
        if (Build.VERSION.SDK_INT >= 31) stretchRenderer?.dispose()
        stretchRenderer = null
    }

    private fun drawRotated(edge: EdgeEffect, canvas: Canvas, rotation: Float): Boolean {
        if (rotation == 0f) return edge.draw(canvas)
        val saved = canvas.save()
        return try { canvas.rotate(rotation); edge.draw(canvas) } finally { canvas.restoreToCount(saved) }
    }
}

@RequiresApi(31)
private class NativeAxisStretchRenderer(private val horizontal: Boolean) {
    private val node = RenderNode("AndroidEdgeEffectOverscrollEffect")
    fun draw(canvas: Canvas, maxElevation: Float, content: (Canvas) -> Unit, effects: (Canvas) -> Unit) {
        // Canvas bounds include host clip expansion; measured viewport bounds size the effects.
        val expansion = maxElevation.roundToInt() * 2
        if (horizontal) node.setPosition(0, 0, canvas.width, canvas.height + expansion)
        else node.setPosition(0, 0, canvas.width + expansion, canvas.height)
        val translateX = if (horizontal) 0f else maxElevation
        val translateY = if (horizontal) maxElevation else 0f
        val recording = node.beginRecording()
        try {
            effects(recording)
            recording.save()
            try { recording.translate(translateX, translateY); content(recording) }
            finally { recording.restore() }
        } finally { node.endRecording() }
        val saved = canvas.save()
        try { canvas.translate(-translateX, -translateY); canvas.drawRenderNode(node) }
        finally { canvas.restoreToCount(saved) }
    }
    fun dispose() { node.discardDisplayList() }
}

/** Physical screen axis; independent of layout direction. */
enum class NativeOverscrollAxis { HORIZONTAL, VERTICAL }

enum class NativeOverscrollSource { USER_INPUT, SIDE_EFFECT }
