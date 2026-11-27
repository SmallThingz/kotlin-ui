/*
 * Surface and animation layer derived from AndroidX Material3 1.4.0 Menu.kt / Surface.kt.
 * Copyright 2022 The Android Open Source Project. Licensed under Apache-2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.PopupWindow
import smallthingz.ui.animation.core.SpringSpec
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import smallthingz.ui.unit.Density
import smallthingz.ui.graphics.NoiseDrawable
import kotlin.math.roundToInt

/** Rounded menu surface and the transform outside its shadow and content clip. */
data class NativeAnchoredMenuRecipe(
    val cornerRadiusDp: Float = 18f,
    val verticalPaddingDp: Float = 8f,
    val shadowDp: Float = 3f,
    val windowShadowInsetDp: Float = 8f,
    val motion: NativeAnchoredMenuMotionStyle = NativeAnchoredMenuMotionStyle.Default,
) {
    init {
        require(cornerRadiusDp.isFinite() && cornerRadiusDp >= 0f)
        require(verticalPaddingDp.isFinite() && verticalPaddingDp >= 0f)
        require(shadowDp.isFinite() && shadowDp >= 0f)
        require(windowShadowInsetDp.isFinite() && windowShadowInsetDp >= 0f)
    }

    fun surfaceColor(palette: NativeColorScheme): Int = palette.popupFill

    /** Creates a surface around an unattached child. The caller owns window size and placement. */
    fun createPanel(content: View, density: Density, palette: NativeColorScheme): NativeAnchoredMenuPanel =
        NativeAnchoredMenuPanel(NativeMenuSurface(content.context, density, this, surfaceColor(palette)).apply {
            bindGrain(palette.popupGrain)
            setBody(content)
        }, density, this, drivesMotion = true)

    companion object {
        val Default = NativeAnchoredMenuRecipe()
    }
}

data class NativeAnchoredMenuMotionStyle(
    val collapsedScale: Float = .8f,
    val scaleSpring: SpringSpec<Float> = SpringSpec(dampingRatio = .9f, stiffness = 1400f),
    val alphaSpring: SpringSpec<Float> = SpringSpec(dampingRatio = 1f, stiffness = 3800f),
) {
    init { require(collapsedScale.isFinite() && collapsedScale >= 0f) }

    companion object {
        val Default = NativeAnchoredMenuMotionStyle()
    }
}

/** Panel for an anchored window. Its child retains focus, input, and accessibility ownership. */
class NativeAnchoredMenuPanel internal constructor(
    private val surface: NativeMenuSurface,
    private val density: Density,
    private val recipe: NativeAnchoredMenuRecipe,
    private val drivesMotion: Boolean,
) : ViewGroup(surface.context) {
    private var motion = NativeMenuTransition(recipe.motion)
    private var originX = .5f
    private var originY = 0f
    private var onCollapsed: (() -> Unit)? = null
    private var framePosted = false
    private var frameCallback: Choreographer.FrameCallback? = null

    init {
        clipChildren = false
        clipToPadding = false
        clipToOutline = false
        isFocusable = false
        isClickable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        applyMotion(visible = false)
        addView(surface)
    }

    /** Use before showing the window. The window owner supplies focus, outside touch and dismiss. */
    fun applyWindowStyle(window: PopupWindow) {
        // Use the panel as the elevation root without an extra clipping background container.
        window.setBackgroundDrawable(null)
        outlineProvider = NativeWindowShadowOutline
        window.elevation = recipe.windowShadowInsetDp * density.density
        window.animationStyle = 0
        window.enterTransition = null
        window.exitTransition = null
    }

    fun bindPalette(palette: NativeColorScheme) {
        surface.bindFill(recipe.surfaceColor(palette))
        surface.bindGrain(palette.popupGrain)
    }

    /** Physical fractions of the panel, derived by the caller from its actual placement. */
    fun setAnimationPivot(xFraction: Float, yFraction: Float) {
        require(xFraction.isFinite() && yFraction.isFinite())
        originX = xFraction
        originY = yFraction
        placePivot()
    }

    /** A collapse callback is replaced by the next request and discarded on detach. */
    fun expanded(expanded: Boolean, onCollapsed: (() -> Unit)? = null) {
        this.onCollapsed = if (expanded) null else onCollapsed
        retargetMotion(expanded)
        if (!motionShouldExist) finishCollapse() else scheduleFrame()
    }

    internal val motionShouldExist get() = motion.shouldExist

    internal fun retargetMotion(expanded: Boolean) = motion.retarget(expanded)

    internal fun advanceMotion(time: Long, visible: Boolean,
        durationScale: Float = readCachedAnimatorDurationScale(context),
    ) {
        motion.frame(time, durationScale)
        applyMotion(visible)
    }

    internal fun animateFrame(time: Long, durationScale: Float) {
        advanceMotion(time, visible = true, durationScale = durationScale)
        if (!motionShouldExist) finishCollapse()
        else if (motion.isRunning) scheduleFrame()
        else cancelFrame()
    }

    private fun finishCollapse() {
        val callback = onCollapsed
        onCollapsed = null
        cancelFrame()
        if (motion.isRunning) motion = NativeMenuTransition(recipe.motion)
        applyMotion(visible = false)
        callback?.invoke()
    }

    private fun applyMotion(visible: Boolean) {
        scaleX = motion.scale
        scaleY = motion.scale
        alpha = if (visible) motion.alpha else 0f
    }

    private fun scheduleFrame() {
        if (!drivesMotion || framePosted || !isAttachedToWindow || !motion.isRunning) return
        val callback = frameCallback ?: object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                // Removing a callback cannot retract a frame batch already captured by Android.
                if (frameCallback !== this || !framePosted) return
                framePosted = false
                animateFrame(time, readCachedAnimatorDurationScale(context))
            }
        }.also { frameCallback = it }
        framePosted = true
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun cancelFrame() {
        val callback = frameCallback ?: return
        frameCallback = null
        if (!framePosted) return
        framePosted = false
        Choreographer.getInstance().removeFrameCallback(callback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        if (drivesMotion) {
            cancelFrame()
            onCollapsed = null
            motion = NativeMenuTransition(recipe.motion)
            applyMotion(visible = false)
        }
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        surface.measure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(surface.measuredWidth, surface.measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        surface.layout(0, 0, surface.measuredWidth, surface.measuredHeight)
        if (drivesMotion) placePivot()
    }

    private fun placePivot() {
        pivotX = originX * width
        pivotY = originY * height
    }
}

/** The shadow sits outside the rounded content clip, with no tonal overlay. */
internal open class NativeMenuSurface(
    context: Context,
    density: Density,
    recipe: NativeAnchoredMenuRecipe,
    initialFill: Int,
) : ViewGroup(context), NativeModalPaintedSurface {
    private val radius = recipe.cornerRadiusDp * density.density
    private val pad = (recipe.verticalPaddingDp * density.density).roundToInt()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = initialFill
    }
    private var grainIntensity = 0f
    private val grain = NoiseDrawable(initialFill, NoiseDrawable.SEED_BACKGROUND, 0f)
    override val paintedTextSurface: Long get() = grain.paintedTextSurface
    private val fillMotion = ViewColorTransition(this, intArrayOf(initialFill), ::publishFill)
    private val clip = NativeMenuClip(context, radius)
    private lateinit var body: View

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        elevation = recipe.shadowDp * density.density
        outlineProvider = menuOutline(radius)
        clipToOutline = false
        if (Build.VERSION.SDK_INT >= 28) {
            outlineAmbientShadowColor = Color.BLACK
            outlineSpotShadowColor = Color.BLACK
        }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(clip)
        grain.callback = this
    }

    fun setBody(view: View) {
        check(!::body.isInitialized)
        clip.addView(view)
        body = view
        NativeModalTextSurface.bindTree(view)
    }

    fun bindFill(color: Int) = fillMotion.retarget(color)

    fun bindGrain(intensity: Float) {
        if (grainIntensity == intensity) return
        grainIntensity = intensity
        grain.setNoiseIntensity(intensity)
        NativeModalTextSurface.invalidate(this)
        invalidate()
    }

    private fun publishFill() {
        val next = fillMotion.color(0)
        if (fill.color == next) return
        fill.color = next
        grain.setBackgroundColor(fill.color)
        NativeModalTextSurface.invalidate(this)
        invalidate()
    }

    override fun verifyDrawable(who: android.graphics.drawable.Drawable): Boolean =
        who === grain || super.verifyDrawable(who)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bodyHeight = (MeasureSpec.getSize(heightMeasureSpec) - 2 * pad).coerceAtLeast(0)
        measureBody(body, widthMeasureSpec, MeasureSpec.makeMeasureSpec(bodyHeight, MeasureSpec.getMode(heightMeasureSpec)))
        val width = body.measuredWidth
        val height = resolveSize(body.measuredHeight + 2 * pad, heightMeasureSpec)
        clip.measure(exact(width), exact(height))
        setMeasuredDimension(width, height)
    }

    protected open fun measureBody(body: View, widthMeasureSpec: Int, heightMeasureSpec: Int) =
        body.measure(widthMeasureSpec, heightMeasureSpec)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        clip.layout(0, 0, width, height)
        body.layout(0, pad, body.measuredWidth, pad + body.measuredHeight)
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, fill)
        grain.drawGrain(canvas, 0f, 0f, width.toFloat(), height.toFloat(), radius)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        grain.setBounds(0, 0, w, h)
    }

    // An inert part of the surface shields its rectangular allocated area.
    override fun onTouchEvent(event: MotionEvent) = true
}

private fun menuOutline(radius: Float) = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
}

/** Android outline on the hardware path, and the same rounded path for software drawing. */
private class NativeMenuClip(context: Context, private val radius: Float) : ViewGroup(context) {
    private val path = Path()

    init {
        clipChildren = false
        clipToPadding = false
        clipToOutline = true
        outlineProvider = menuOutline(radius)
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        path.rewind()
        path.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
        invalidateOutline()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) super.dispatchDraw(canvas)
        else {
            val save = canvas.save()
            try { canvas.clipPath(path); super.dispatchDraw(canvas) }
            finally { canvas.restoreToCount(save) }
        }
    }
}
