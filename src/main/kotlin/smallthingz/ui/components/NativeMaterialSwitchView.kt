/*
 * Geometry and token selection adapted from AndroidX Material3 1.4.0 Switch.kt
 * and tokens/SwitchTokens.kt, Copyright 2022 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package smallthingz.ui.components

import smallthingz.ui.animation.core.AnimationPlayTime

import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import smallthingz.ui.animation.core.FloatSpringSpec
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.ViewGrain
import smallthingz.ui.graphics.bindGrainLayer
import smallthingz.ui.graphics.compositeOver
import smallthingz.ui.graphics.toArgb
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shared switch with a constant 24dp thumb in every state. Thumb content is opt-in.
 * The parent owns the row's click target, checked semantics and press feedback. This View adds
 * no click listener, focus stop, minimum 48dp target, ripple or platform Switch styling.
 *
 * All released callers leave interactionSource null. Their enclosing toggleable row therefore
 * does NOT press this thumb. [setVisualPressed] exists only for explicit-source parity probes;
 * do not connect it to row presses when migrating those callers.
 *
 * Public pinned FloatSpringSpec supplies exact scalar spring equations. Acceptance/dispatch
 * timing and frame callback phase require separate qualification against the released implementation.
 */
class NativeMaterialSwitchView(
    context: Context,
    palette: NativeMaterialSwitchPalette,
    initialChecked: Boolean = false,
    initialEnabled: Boolean = true,
) : ViewGroup(context), Choreographer.FrameCallback {
    private val trackPaint = Paint(PAINT_FLAGS)
    private var trackGrain: ViewGrain? = null
    private var thumbGrain: ViewGrain? = null
    private val thumbPaint = Paint(PAINT_FLAGS)
    private val borderPaint = Paint(PAINT_FLAGS).apply { style = Paint.Style.STROKE }
    private var boundPalette = palette
    private var colors = MaterialSwitchColors.from(palette)
    private var checked = initialChecked
    private var visuallyPressed = false
    private var trackWidth = 0
    private var trackHeight = 0
    private var borderWidth = 0f
    /** Theme-aware construction shared by application and plugin controls. */
    constructor(context: Context, scheme: NativeColorScheme, checked: Boolean = false, enabled: Boolean = true) :
        this(context, scheme.switchPalette, checked, enabled) { bindSurfacePreset(scheme.surfacePreset) }

    private var customOutlineWidthDp: Float? = null
    private var customOutlineColor = 0
    private var thumbSize = 0
    private var offsetMotion: SwitchFloatMotion? = null
    private var framePosted = false
    private val choreographer = Choreographer.getInstance()
    private var colorMotion:ViewColorTransition?=null
    private var thumbProgress: NativeCircularProgress? = null
    private val thumbClip = Path()
    private var thumbCenterX = 0f
    private var thumbCenterY = 0f
    private var drawnThumbRadius = 0f

    init {
        setWillNotDraw(false)
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        minimumWidth = 0
        minimumHeight = 0
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isEnabled = initialEnabled
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        colorMotion=ViewColorTransition(this,intArrayOf(
            colors.track(initialEnabled,initialChecked),colors.thumb(initialEnabled,initialChecked),
            colors.border(initialEnabled,initialChecked)), {
            updateThumbProgressInk()
            invalidate()
        })
    }

    /** Shows indeterminate progress without changing the switch or thumb geometry. */
    fun setThumbProgress(progress: NativeCircularProgress?) {
        if (thumbProgress === progress) return
        require(progress?.parent == null)
        thumbProgress?.let(::removeView)
        thumbProgress = progress
        progress?.let { addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)) }
        updateThumbProgressInk()
        invalidate()
    }

    fun setPalette(palette: NativeMaterialSwitchPalette) {
        if (boundPalette == palette) return
        colors = MaterialSwitchColors.from(palette)
        boundPalette = palette
        retargetColors()
        updateThumbProgressInk()
        invalidate()
    }

    private var surfacePreset: NativeSurfacePreset? = null

    internal fun bindSurfacePreset(preset: NativeSurfacePreset?) {
        if (surfacePreset == preset) return
        surfacePreset = preset
        setTrackNoiseIntensity(preset?.foregroundGrain ?: 0f)
        thumbGrain = bindGrainLayer(thumbGrain, preset?.foregroundGrain ?: 0f)
        customOutlineWidthDp = preset?.borderWidthDp
        customOutlineColor = preset?.borderColor ?: 0
        invalidate()
    }

    /** Apply texture to the track while keeping its thumb and outline solid. */
    fun setTrackNoiseIntensity(intensity: Float) { trackGrain = bindGrainLayer(trackGrain, intensity) }

    /** Style only the track outline, preserving thumb colors and checked-state transitions. */
    fun setTrackOutline(widthDp: Float, color: Int) {
        require(widthDp.isFinite() && widthDp >= 0f)
        if (customOutlineWidthDp == widthDp && customOutlineColor == color) return
        customOutlineWidthDp = widthDp
        customOutlineColor = color
        invalidate()
    }

    fun setState(checked: Boolean, enabled: Boolean) {
        val changed=this.checked!=checked
        this.checked=checked
        isEnabled = enabled
        retargetColors()
        if(!changed)return
        requestLayout()
        invalidate()
    }

    fun setVisualPressed(pressed: Boolean) {
        if (visuallyPressed == pressed) return
        visuallyPressed = pressed
        requestLayout()
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        val changed = isEnabled != enabled
        super.setEnabled(enabled)
        if (changed) {retargetColors();invalidate()}
    }

    private fun retargetColors() {colorMotion?.retarget(colors.track(isEnabled,checked),
        colors.thumb(isEnabled,checked),colors.border(isEnabled,checked))}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestLayout()
        invalidate()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        trackWidth = (52f * density).roundToInt()
        trackHeight = (32f * density).roundToInt()
        borderWidth = min(NativeDefaultColorScheme.THIN_OUTLINE_WIDTH_DP * density, min(trackWidth, trackHeight) / 2f)
        setMeasuredDimension(
            resolveSize(trackWidth, widthMeasureSpec),
            resolveSize(trackHeight, heightMeasureSpec),
        )

        thumbProgress?.let {
            val size = MeasureSpec.makeMeasureSpec((12f * density).roundToInt(), MeasureSpec.EXACTLY)
            it.measure(size, size)
        }

        thumbSize = (THUMB_SIZE_DP * density).toInt()
        val minBound = ((32f - THUMB_SIZE_DP) / 2f) * density
        val maxBound = ((52f - THUMB_SIZE_DP) - (32f - THUMB_SIZE_DP) / 2f) * density
        val targetOffset = if (checked) maxBound else minBound
        val offset = offsetMotion
        if (offset == null) {
            // Initial state is already at its target: there is no entrance animation.
            offsetMotion = SwitchFloatMotion(targetOffset)
        } else {
            val scale = readCachedAnimatorDurationScale(context)
            offset.retarget(targetOffset, snap = visuallyPressed, durationScale = scale)
            postFrameIfNeeded()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // Content has fixed measured bounds. Its draw follows the animated thumb on each frame.
        thumbProgress?.let { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val offset = offsetMotion ?: return
        val originX = ((width - trackWidth) / 2f).roundToInt().toFloat()
        val originY = ((height - trackHeight) / 2f).roundToInt().toFloat()
        val radius = min(trackWidth, trackHeight) / 2f
        trackPaint.color = colorMotion?.color(0) ?: colors.track(isEnabled, checked)
        thumbPaint.color = colorMotion?.color(1) ?: colors.thumb(isEnabled, checked)
        canvas.drawRoundRect(originX, originY, originX + trackWidth, originY + trackHeight,
            radius, radius, trackPaint)
        trackGrain?.draw(canvas, originX, originY, originX + trackWidth, originY + trackHeight, radius, trackPaint.alpha)

        // Preserve pixel truncation for the fixed diameter and animated offset.
        val actualSize = thumbSize
        val logicalOffset = offset.value.toInt()
        val thumbX = if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            trackWidth - actualSize - logicalOffset
        } else logicalOffset
        val thumbY = ((trackHeight - actualSize) / 2f).roundToInt()
        val thumbRadius = actualSize / 2f
        if (thumbProgress != null) {
            thumbCenterX = originX + thumbX + thumbRadius
            thumbCenterY = originY + thumbY + thumbRadius
            drawnThumbRadius = thumbRadius
        }
        canvas.drawRoundRect(originX + thumbX, originY + thumbY,
            originX + thumbX + actualSize, originY + thumbY + actualSize, thumbRadius, thumbRadius, thumbPaint)
        thumbGrain?.draw(canvas, originX + thumbX, originY + thumbY,
            originX + thumbX + actualSize, originY + thumbY + actualSize, thumbRadius, thumbPaint.alpha)

        // Modifier.border draws after background and children, including the thumb.
        val overrideWidth = customOutlineWidthDp
        val stroke = overrideWidth?.let { (it * resources.displayMetrics.density).coerceAtMost(radius) } ?: borderWidth
        if (stroke <= 0f) return
        val stateColor = colorMotion?.color(2) ?: colors.border(isEnabled, checked)
        borderPaint.color = if (overrideWidth == null) stateColor else {
            val alpha = (AndroidColor.alpha(stateColor) * AndroidColor.alpha(customOutlineColor) + 127) / 255
            (customOutlineColor and 0xffffff) or (alpha shl 24)
        }
        borderPaint.strokeWidth = stroke
        val inset = stroke / 2f
        val borderRadius = (radius - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(originX + inset, originY + inset, originX + trackWidth - inset,
            originY + trackHeight - inset, borderRadius, borderRadius, borderPaint)
    }

    private fun updateThumbProgressInk() {
        val progress = thumbProgress ?: return
        val sample = colorMotion?.color(1) ?: colors.thumb(isEnabled, checked)
        val thumb = ColorUtils.compositeColors(sample, AndroidColor.BLACK or boundPalette.surface)
        val preferred = boundPalette.onSurface
        val ink = if (ColorUtils.calculateContrast(preferred, thumb) >= 4.5) preferred else
            if (ColorUtils.calculateContrast(AndroidColor.BLACK, thumb) >= 4.5) AndroidColor.BLACK else AndroidColor.WHITE
        progress.setColorImmediately(ink)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val content = thumbProgress ?: return
        if (content.visibility != VISIBLE || drawnThumbRadius <= 0f) return
        val save = canvas.save()
        thumbClip.rewind()
        thumbClip.addCircle(thumbCenterX, thumbCenterY, drawnThumbRadius, Path.Direction.CW)
        canvas.clipPath(thumbClip)
        canvas.translate(thumbCenterX - content.width / 2f, thumbCenterY - content.height / 2f)
        drawChild(canvas, content, drawingTime)
        canvas.restoreToCount(save)
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        // The child draws at the animated thumb, beyond its fixed layout rectangle.
        if (child === thumbProgress) invalidate()
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePosted = false
        if (!isAttachedToWindow) return
        val scale = readCachedAnimatorDurationScale(context)
        offsetMotion?.frame(frameTimeNanos, scale)
        invalidate()
        postFrameIfNeeded()
    }

    private fun postFrameIfNeeded() {
        if (isAttachedToWindow && !framePosted &&
            offsetMotion?.isRunning == true
        ) {
            framePosted = true
            choreographer.postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postFrameIfNeeded()
    }

    override fun onDetachedFromWindow() {
        choreographer.removeFrameCallback(this)
        framePosted = false
        offsetMotion?.stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val THUMB_SIZE_DP = 24f
        const val PAINT_FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG
    }
}

/** Color inputs for the Material switch, independent of the host app's palette. */
data class NativeMaterialSwitchPalette(
    val surface: Int,
    val onSurface: Int,
    val primary: Int,
    val onPrimary: Int,
    val outline: Int,
    val surfaceContainerHighest: Int,
)

/** Endpoint color selection from SwitchDefaults; presentation uses the shared color timeline. */
private class MaterialSwitchColors(
    private val checkedThumb: Int,
    private val checkedTrack: Int,
    private val uncheckedThumb: Int,
    private val uncheckedTrack: Int,
    private val uncheckedBorder: Int,
    private val disabledCheckedThumb: Int,
    private val disabledCheckedTrack: Int,
    private val disabledUncheckedThumb: Int,
    private val disabledUncheckedTrack: Int,
    private val disabledUncheckedBorder: Int,
) {
    fun thumb(enabled: Boolean, checked: Boolean): Int = when {
        enabled && checked -> checkedThumb
        enabled -> uncheckedThumb
        checked -> disabledCheckedThumb
        else -> disabledUncheckedThumb
    }

    fun track(enabled: Boolean, checked: Boolean): Int = when {
        enabled && checked -> checkedTrack
        enabled -> uncheckedTrack
        checked -> disabledCheckedTrack
        else -> disabledUncheckedTrack
    }

    fun border(enabled: Boolean, checked: Boolean): Int = when {
        checked -> 0
        enabled -> uncheckedBorder
        else -> disabledUncheckedBorder
    }

    companion object {
        fun from(palette: NativeMaterialSwitchPalette): MaterialSwitchColors {
            val surface = Color(palette.surface)
            // Retain Color.copy alpha quantization and compositeOver Float arithmetic exactly.
            fun composite(color: Int, alpha: Float): Int =
                Color(color).copy(alpha = alpha).compositeOver(surface).toArgb()
            val disabledOnSurface = composite(palette.onSurface, 0.12f)
            return MaterialSwitchColors(
                checkedThumb = palette.onPrimary,
                checkedTrack = palette.primary,
                uncheckedThumb = palette.outline,
                uncheckedTrack = palette.surfaceContainerHighest,
                uncheckedBorder = palette.outline,
                disabledCheckedThumb = composite(palette.surface, 1f),
                disabledCheckedTrack = disabledOnSurface,
                disabledUncheckedThumb = composite(palette.onSurface, 0.38f),
                disabledUncheckedTrack = composite(palette.surfaceContainerHighest, 0.12f),
                disabledUncheckedBorder = disabledOnSurface,
            )
        }
    }
}

/**
 * Scalar sampling seam for ThumbNode's Animatable<Float>, not a replacement spring integrator.
 * Uses pinned AndroidX 1.11.2 FloatSpringSpec with Material3's Standard FastSpatial tokens.
 */
private class SwitchFloatMotion(initial: Float) {
    var value = initial
        private set
    private var target = initial
    private var initialValue = initial
    private var initialVelocity = 0f
    private var velocity = 0f
    private var durationNanos = 0L
    private val clock = AnimationPlayTime()
    private var lastFrame = UNSET_FRAME
    private var snap = false
    var isRunning = false
        private set

    fun retarget(next: Float, snap: Boolean, durationScale: Float) {
        if (target == next) return
        initialValue = value
        initialVelocity = velocity
        target = next
        this.snap = snap
        durationNanos = if (snap) 0L else SPRING.getDurationNanos(initialValue, target, initialVelocity)
        clock.reset()
        isRunning = true
        if (lastFrame != UNSET_FRAME) frame(lastFrame, durationScale)
    }

    fun frame(frameNanos: Long, durationScale: Float) {
        if (!isRunning) return
        require(durationScale >= 0f)
        val playTime = clock.advance(frameNanos, durationScale, durationNanos)
        lastFrame = frameNanos
        if (snap || playTime >= durationNanos) {
            stop()
        } else {
            value = SPRING.getValueFromNanos(playTime, initialValue, target, initialVelocity)
            velocity = SPRING.getVelocityFromNanos(playTime, initialValue, target, initialVelocity)
        }
    }

    fun stop() {
        // Retained views must not reattach with a stopped, half-finished thumb.
        value = target
        isRunning = false
        velocity = 0f
        clock.reset()
        lastFrame = UNSET_FRAME
    }

    private companion object {
        const val UNSET_FRAME = Long.MIN_VALUE
        val SPRING = FloatSpringSpec(dampingRatio = 0.9f, stiffness = 1400f, visibilityThreshold = 0.01f)
    }
}
