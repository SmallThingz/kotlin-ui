package smallthingz.ui.components

import smallthingz.ui.interaction.view.NativeClickBinding
import smallthingz.ui.interaction.view.NativeClickEnvironment
import smallthingz.ui.interaction.view.NativeClickIndication
import smallthingz.ui.interaction.view.NativePressToken
import smallthingz.ui.interaction.view.NativeStateToken
import smallthingz.ui.interaction.view.ViewClickController
import smallthingz.ui.text.view.MergedRowAccessibility
import smallthingz.ui.text.view.NativeActionDescriptionView
import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost
import smallthingz.ui.text.view.NativeMergedRowAccessibilityState
import smallthingz.ui.text.view.NativeMergedRowRole
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.animation.view.transitionForegroundInk

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.compositeOver
import smallthingz.ui.graphics.toArgb
import smallthingz.ui.unit.Density
import smallthingz.ui.input.phase.NativeClickPhaseNode
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.input.phase.ViewClickPhaseEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

interface NativeSpringFineSeekBinding {
    val enabled: Boolean
    val isPlaying: Boolean
    val interactionKey: Any?
    val onTogglePlayback: () -> Unit
}

/** The host's gesture and transport owner, driven by this view's existing pointer and frame phases. */
interface NativeSpringFineSeekInput<B : NativeSpringFineSeekBinding> {
    val horizontalPull: Float
    val constrainedVerticalPull: Float
    val dragging: Boolean
    val needsFrames: Boolean
    fun bind(value: B, newTouchSlop: Float)
    fun setVisualStyle(style: NativeSpringFineSeekStyle)
    fun pointer(event: NativeDragPointerEvent, pass: NativePointerPass, geometry: NativeSpringFineSeekGeometry)
    fun frame(frameNanos: Long)
    fun cancel()
    fun release()
}

class NativeSpringFineSeekEnvironment(
    val palette: NativeColorScheme,
    val density: Density,
    val touchSlop: Float,
    val frameClock: MonotonicFrameClock,
    val scope: CoroutineScope,
    val inputRoot: View,
    val parentInterested: (NativeDragPointerChange) -> Boolean,
)

/**
 * A stationary draw surface with a separate moving puck. Register [fineSeekNode] on this rectangle
 * while [fineSeekInputEnabled], and [puckClickNode] on the current [puckClickView] in the same tree.
 */
class NativeSpringFineSeekView<B : NativeSpringFineSeekBinding>(
    context: Context,
    private val environment: NativeSpringFineSeekEnvironment,
    initialStyle: NativeSpringFineSeekStyle,
    private val playbackDescription: (isPlaying: Boolean) -> CharSequence,
    createInput: (invalidate: () -> Unit, requestFrames: () -> Unit) -> NativeSpringFineSeekInput<B>,
) : ViewGroup(context) {
    private var style = initialStyle
    private var paletteTarget = environment.palette
    private val paletteMotion: ViewColorTransition by lazy {
        val p = environment.palette
        ViewColorTransition(this, intArrayOf(p.primary, p.secondary, p.tertiary, p.onSurfaceVariant, p.onSurface, p.surface), {
            colors.update(paletteMotion.color(0), paletteMotion.color(1), paletteMotion.color(2))
            binding?.let { puck.glyph.bindImmediately(it.isPlaying, puckInk()) }
            puck.invalidate()
            invalidate()
        })
    }
    private val colors = with(environment.palette) { NativeSpringFineSeekColorPalette(primary, secondary, tertiary) }
    private var binding: B? = null
    private var inputActive = false
    private var attachedScope: CoroutineScope? = null
    private var frameJob: Job? = null
    private val field = Path()
    private val paint = Paint(PAINT_FLAGS)
    private val dotPaint = Paint(PAINT_FLAGS)
    private var fieldGradient: LinearGradient? = null
    private var gradientLeftBits = 0
    private var gradientRightBits = 0
    private var gradientEdgeColor = 0
    private var gradientCenterColor = 0
    private val model = createInput(::visualChanged, ::requestFrames)
    private val puck = PuckView(context)
    private var geometry = NativeSpringFineSeekGeometry(0, 0, environment.density.density, style = style)
    private var verticalGestureHeightPixels: Int? = null
    private var geometryLease: AutoCloseable? = null
    private var descriptionLease: AutoCloseable? = null
    val puckClickView: View get() = puck
    /** Remove the source parent pointer modifier when disabled; the disabled child clickable remains. */
    val fineSeekInputEnabled: Boolean get() = binding?.enabled == true

    val fineSeekNode: NativeViewPhasePointerNode = object : NativeViewPhasePointerNode {
        override val view: View get() = this@NativeSpringFineSeekView
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
            model.pointer(event, pass, geometry)
        }
        override fun cancel() { model.cancel() }
    }
    val puckClickNode = NativeClickPhaseNode(puck, ViewClickPhaseEndpoint(puck.controller),
        environment.parentInterested)

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(puck)
        model.setVisualStyle(style)
    }

    fun bind(value: B) {
        binding = value
        model.bind(value, environment.touchSlop)
        puck.bind(value)
    }

    fun setAppearance(style: NativeSpringFineSeekStyle, palette: NativeColorScheme) {
        if (this.style != style) {
            this.style = style
            model.setVisualStyle(style)
            requestLayout()
            puck.requestLayout()
            invalidate()
        }
        if (paletteTarget == palette) return
        paletteTarget = palette
        paletteMotion.retarget(palette.primary,palette.secondary,palette.tertiary,palette.onSurfaceVariant,palette.onSurface,palette.surface)
    }

    private fun puckInk(): Int = transitionForegroundInk(paletteMotion, paletteMotion.color(4), paletteMotion.color(5))

    /** Reuse the screen/window's existing cursor and geometry; never create another cursor. */
    fun bindAccessibility(
        window: PlainTextAccessibilityWindowScope,
        geometry: NativePlainTextViewGeometry,
        exposed: () -> Boolean,
    ): AutoCloseable {
        geometryLease?.close(); descriptionLease?.close()
        val localGeometry = puck.mergedRowAccessibility.bindGeometry(geometry, exposed)
        val localDescription = puck.glyph.bindDescriptionExposure(window, exposed)
        geometryLease = localGeometry; descriptionLease = localDescription
        return AutoCloseable {
            localGeometry.close(); localDescription.close()
            if (geometryLease === localGeometry) geometryLease = null
            if (descriptionLease === localDescription) descriptionLease = null
        }
    }

    fun cancelInput() { model.cancel(); puck.controller.cancel() }

    /** Recycler/presentation owner retires callbacks before binding a different recording. */
    fun release() {
        model.release()
        fieldGradient = null
        binding = null
        puck.controller.bind(null)
        frameJob?.cancel(); frameJob = null
        geometryLease?.close(); geometryLease = null
        descriptionLease?.close(); descriptionLease = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        inputActive = true
        attachedScope = CoroutineScope(environment.scope.coroutineContext +
            SupervisorJob(environment.scope.coroutineContext[Job]))
        requestFrames()
    }

    override fun onDetachedFromWindow() {
        inputActive = false
        model.cancel()
        fieldGradient = null
        frameJob?.cancel(); frameJob = null
        attachedScope?.cancel(); attachedScope = null
        super.onDetachedFromWindow()
    }

    private fun requestFrames() {
        if (!inputActive || !model.needsFrames || frameJob?.isActive == true) return
        frameJob = attachedScope?.launch {
            // The raw clock drives transport and the direct spring return.
            val onFrame = model::frame
            while (model.needsFrames && inputActive) {
                environment.frameClock.withFrameNanos(onFrame)
            }
        }
    }

    private fun visualChanged() {
        if (isLaidOut) placePuck()
        invalidate()
        puck.invalidate()
    }

    /** The compact Library measures its original gesture span before shrinking visual whitespace. */
    fun setVerticalGestureHeightPixels(value: Int?) { verticalGestureHeightPixels = value?.coerceAtLeast(0) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The optional gesture span affects only Y gain, never puck placement or hit geometry.
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        geometry = NativeSpringFineSeekGeometry(w, h, environment.density.density, verticalGestureHeightPixels ?: h, style)
        puck.measure(MeasureSpec.makeMeasureSpec(geometry.hitWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(geometry.hitHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = placePuck()
    private fun placePuck() {
        val x = geometry.puckLeft(model.horizontalPull)
        val y = geometry.puckTop(model.constrainedVerticalPull)
        puck.layout(x, y, x + puck.measuredWidth, y + puck.measuredHeight)
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        if (isLaidOut) placePuck()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val density = environment.density.density
        val liveX = model.horizontalPull
        val liveY = model.constrainedVerticalPull
        val centerX = width * .5f
        val centerY = height * .5f
        val radius = style.puckDiameterDp * .5f * density
        val puckX = centerX + liveX * geometry.drawHorizontalTravel
        val puckY = centerY + liveY * geometry.drawVerticalTravel
        val left = style.fieldInsetDp * density
        val right = width - left
        val leftSpan = (puckX - radius - left).coerceAtLeast(1f)
        val rightSpan = (right - puckX - radius).coerceAtLeast(1f)
        val top = puckY - radius
        val bottom = puckY + radius
        field.rewind()
        field.moveTo(left, centerY)
        field.cubicTo(left + leftSpan * style.fieldCenterHandle, centerY,
            (puckX - radius - leftSpan * style.fieldPuckHandle).coerceAtLeast(left), top, puckX, top)
        field.cubicTo((puckX + radius + rightSpan * style.fieldPuckHandle).coerceAtMost(right), top,
            right - rightSpan * style.fieldCenterHandle, centerY, right, centerY)
        field.cubicTo(right - rightSpan * style.fieldCenterHandle, centerY,
            (puckX + radius + rightSpan * style.fieldPuckHandle).coerceAtMost(right), bottom, puckX, bottom)
        field.cubicTo((puckX - radius - leftSpan * style.fieldPuckHandle).coerceAtLeast(left), bottom,
            left + leftSpan * style.fieldCenterHandle, centerY, left, centerY)
        field.close()

        val power = abs(liveX).pow(style.horizontalTintExponent)
        val yMagnitude = (abs(liveY) / style.verticalTintSpan).coerceIn(0f, 1f)
        // Keep packed Oklab endpoint quantization; only the palette-dependent work is cached.
        val color = colors.color(liveY, yMagnitude)
        val alpha = style.fieldAlpha + style.fieldHorizontalAlpha * power + style.fieldVerticalAlpha * yMagnitude
        paint.color = android.graphics.Color.BLACK
        paint.alpha = 255
        paint.shader = gradient(left, right, color.copy(alpha = style.fieldEdgeAlpha).toArgb(),
            color.copy(alpha = alpha).toArgb())
        canvas.drawPath(field, paint)
        paint.shader = null
        paint.color = color.copy(alpha = style.fieldOverlayAlpha + style.fieldOverlayVerticalAlpha * yMagnitude).toArgb()
        canvas.drawPath(field, paint)
        dotPaint.color = Color(paletteMotion.color(3)).copy(alpha = if (model.dragging) style.draggingDotAlpha else style.centerDotAlpha).toArgb()
        canvas.drawCircle(centerX, centerY, style.centerDotRadiusDp * density, dotPaint)
        if (model.dragging) {
            dotPaint.color = color.copy(alpha = style.draggingGlowAlpha + style.draggingGlowHorizontalAlpha * power).toArgb()
            canvas.drawCircle(puckX, puckY, radius * style.draggingGlowScale, dotPaint)
        }
    }

    private fun gradient(left: Float, right: Float, edgeColor: Int, centerColor: Int): LinearGradient {
        val leftBits = left.toRawBits()
        val rightBits = right.toRawBits()
        val cached = fieldGradient
        if (cached != null && gradientLeftBits == leftBits && gradientRightBits == rightBits &&
            gradientEdgeColor == edgeColor && gradientCenterColor == centerColor) return cached
        val next = LinearGradient(left, 0f, right, 0f, intArrayOf(edgeColor, centerColor, edgeColor),
            null, Shader.TileMode.CLAMP)
        if (left.isFinite() && right.isFinite()) {
            gradientLeftBits = leftBits
            gradientRightBits = rightBits
            gradientEdgeColor = edgeColor
            gradientCenterColor = centerColor
            fieldGradient = next
        } else {
            fieldGradient = null
        }
        return next
    }

    private inner class PuckView(context: Context) : ViewGroup(context), NativeMergedRowAccessibilityHost {
        val glyph = FineSeekGlyph(context, resources.displayMetrics.density, environment.palette.surface)
        private val surfaceBounds = RectF()
        private val surfacePaint = Paint(PAINT_FLAGS)
        val controller = ViewClickController(this, object : NativeClickEnvironment {
            override val nativeInputRoot get() = environment.inputRoot
            override fun admitDown(event: MotionEvent) =
                event.x >= 0f && event.y >= 0f && event.x <= width && event.y <= height
            override fun interestedParent(event: MotionEvent) = false // Shared phase node supplies actual connection.
            override fun longPressOwnershipChanged(owns: Boolean) = Unit // Source has no long-click.
            override val minimumTouchWidthPx get() = 48f * environment.density.density
            override val minimumTouchHeightPx get() = minimumTouchWidthPx
        }, NO_INDICATION)
        override val mergedRowAccessibility = run {
            val children = listOf(glyph)
            MergedRowAccessibility(
                this, NativeMergedRowRole.BUTTON,
                sourceState = { NativeMergedRowAccessibilityState(binding?.enabled == true, null, null, children) },
                invokeSourceClick = {
                    val current = binding
                    if (current?.enabled == true) { current.onTogglePlayback(); true } else false
                },
            )
        }

        init {
            setWillNotDraw(false)
            clipChildren = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = true
            isFocusable = true
            accessibilityDelegate = mergedRowAccessibility
            glyph.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(glyph)
            addView(mergedRowAccessibility.roleView)
            setOnClickListener { binding?.takeIf { it.enabled }?.onTogglePlayback?.invoke() }
        }

        fun bind(value: B) {
            isEnabled = value.enabled
            isFocusable = value.enabled
            glyph.bindImmediately(value.isPlaying, puckInk())
            glyph.contentDescription = playbackDescription(value.isPlaying)
            controller.bind(NativeClickBinding(value.interactionKey ?: NULL_KEY, value.enabled, { performClick() }))
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
            val d = environment.density.density
            val sw = minOf(w, (style.puckDiameterDp * d).roundToInt())
            val sh = minOf(h, (style.puckDiameterDp * d).roundToInt())
            val sx = ((w - sw) * .5f).roundToInt()
            val sy = ((h - sh) * .5f).roundToInt()
            surfaceBounds.set(sx.toFloat(), sy.toFloat(), (sx + sw).toFloat(), (sy + sh).toFloat())
            val gw = minOf(sw, (style.glyphSizeDp * d).roundToInt())
            val gh = minOf(sh, (style.glyphSizeDp * d).roundToInt())
            glyph.measure(MeasureSpec.makeMeasureSpec(gw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(gh, MeasureSpec.EXACTLY))
            mergedRowAccessibility.roleView.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            // Center the glyph inside the rounded surface, which is itself centered in the hit rectangle.
            val x = surfaceBounds.left.toInt() + ((surfaceBounds.width() - glyph.measuredWidth) * .5f).roundToInt()
            val y = surfaceBounds.top.toInt() + ((surfaceBounds.height() - glyph.measuredHeight) * .5f).roundToInt()
            glyph.layout(x, y, x + glyph.measuredWidth, y + glyph.measuredHeight)
            mergedRowAccessibility.roleView.layout(0, 0, width, height)
        }

        override fun onDraw(canvas: Canvas) {
            val foreground = paletteMotion.color(4)
            var fill = Color(foreground)
            if (!model.dragging && foreground == paletteMotion.color(5)) {
                // Material3 applies tonal elevation only when the color equals ColorScheme.surface.
                fill = Color(paletteMotion.color(0)).copy(alpha = style.puckTonalAlpha).compositeOver(fill)
            }
            surfacePaint.color = fill.toArgb()
            val radius = minOf(surfaceBounds.width(), surfaceBounds.height()) * .5f
            canvas.drawRoundRect(surfaceBounds, radius, radius, surfacePaint)
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); controller.attached() }
        override fun onDetachedFromWindow() { controller.detached(); super.onDetachedFromWindow() }
        override fun addChildrenForAccessibility(outChildren: ArrayList<View>) =
            mergedRowAccessibility.addChildrenForAccessibility(outChildren)
        override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
            if (mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null
        override fun onTouchEvent(event: MotionEvent): Boolean = controller.touch(event)
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE -> controller.key(event)
            else -> super.dispatchKeyEvent(event)
        }
        override fun onHoverEvent(event: MotionEvent): Boolean {
            val native = super.onHoverEvent(event)
            return controller.hover(event) || native
        }
        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previous: Rect?) {
            super.onFocusChanged(gainFocus, direction, previous)
            controller.focusChanged(gainFocus)
        }
        override fun getAccessibilityClassName(): CharSequence = "android.view.View"
    }

    private class FineSeekGlyph(context: Context, private val density: Float, tint: Int) :
        NativeActionDescriptionView(context, "") {
        private var playing = false
        private var glyph = playbackGlyph(density, playing, tint)
        private var publishedTint = tint

        fun bindImmediately(isPlaying: Boolean, tint: Int) {
            if (playing != isPlaying) {
                playing = isPlaying
                glyph = playbackGlyph(density, playing, tint)
                publishedTint = tint
                glyph.setBounds(0, 0, width, height)
            }
            if (publishedTint != tint) {
                publishedTint = tint
                glyph.setTint(tint)
            }
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = glyph.setBounds(0, 0, w, h)
        override fun onDraw(canvas: Canvas) = glyph.draw(canvas)
    }

    private companion object {
        const val PAINT_FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG
        val NULL_KEY = Any()
        val NO_INDICATION = object : NativeClickIndication {
            override fun press(token: NativePressToken) = Unit
            override fun release(token: NativePressToken) = Unit
            override fun cancel(token: NativePressToken) = Unit
            override fun stateEnter(token: NativeStateToken) = Unit
            override fun stateExit(token: NativeStateToken) = Unit
        }
    }
}
