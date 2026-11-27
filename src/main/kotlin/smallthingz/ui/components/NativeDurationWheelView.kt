package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.ColorInt
import java.util.function.LongSupplier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import smallthingz.ui.scroll.ScrollPlayTime

/** Dimensions and projection for the native duration wheel. Current and maximum times are supplied separately. */
data class NativeDurationWheelStyle(
    val widthDp: Float = 280f,
    val heightDp: Float = 160f,
    val rowDp: Float = 56f,
    val radiusDp: Float = 52f,
    val perspectiveDp: Float = 320f,
    val centerYOffsetDp: Float = -0.75f,
    val strokeDp: Float = 1f,
    val fontFamily: String = "sans-serif",
    val valueSp: Float = 25f,
    val profileSp: Float = 14f,
    val profileWidthFraction: Float = 0.19f,
    val profileGapDp: Float = 14f,
    val timeGridHalfWidthFraction: Float = 0.38f,
    val profileGridHalfWidthFraction: Float = 0.34f,
    val timeHitHalfWidthFraction: Float = 0.40f,
    val angleStepDegrees: Float = 42f,
    val faceVisibleLimit: Float = 2.34f,
    val faceEdgeFadeSpan: Float = 0.42f,
    val minFaceAlpha: Float = 0.18f,
    val gridVisibleLimit: Float = 2.12f,
    val gridEdgeFadeSpan: Float = 0.75f,
    val gridFrontAlpha: Float = 0.36f,
    val gridWidthFalloff: Float = 0.035f,
    val gridWidthFalloffLimit: Float = 1.8f,
    val dividerAlpha: Float = 0.60f,
    val snapMillis: Float = 150f,
    val rangeInsetDp: Float = 24f,
    val rangeGapDp: Float = 4f,
    val rangeMinimumRoomDp: Float = 390f,
    val settingsInsetDp: Float = 14f,
    val settingsVerticalInsetDp: Float = 8f,
) {
    init {
        require(rowDp.isFinite() && rowDp > 0f)
        require(perspectiveDp.isFinite() && perspectiveDp > 0f)
        require(faceEdgeFadeSpan > 0f && gridEdgeFadeSpan > 0f)
        require(snapMillis.isFinite() && snapMillis >= 0f)
    }

    companion object {
        val Default = NativeDurationWheelStyle()
    }
}

internal fun rangeDurationWheelInputAllowed(
    enabled: Boolean,
    interactionAlreadyActive: Boolean,
): Boolean = enabled || interactionAlreadyActive

internal fun durationWheelSnapEasing(fraction: Float): Float {
    val remaining = 1f - fraction
    return 1f - remaining * remaining * remaining
}

internal fun interface DurationWheelScale {
    fun read(): Float
}

/** Cylindrical hours/minutes/seconds picker with a wrapping step-profile column. */
class NativeDurationWheelView internal constructor(
    context: Context,
    initialStyle: NativeDurationWheelStyle,
    private val durationScale: DurationWheelScale,
    private val nanoTime: LongSupplier,
) : View(context) {
    constructor(
        context: Context,
        initialStyle: NativeDurationWheelStyle = NativeDurationWheelStyle.Default,
    ) : this(context, initialStyle, DurationWheelScale { readCachedAnimatorDurationScale(context) }, System::nanoTime)

    private class Wheel(var position: Double, var count: Int)

    private val density = resources.displayMetrics.density
    private val textScale = density * resources.configuration.fontScale
    private var style = initialStyle
    private var rowPx = style.rowDp * density
    private var radiusPx = style.radiusDp * density
    private var perspectivePx = style.perspectiveDp * density
    private var centerYOffsetPx = style.centerYOffsetDp * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val hourWheel = Wheel(0.0, 1)
    private val minuteWheel = Wheel(0.0, 60)
    private val secondWheel = Wheel(0.0, 60)
    private val profileWheel = Wheel(0.0, PROFILE_LABELS.size)
    private val wheels = arrayOf(hourWheel, minuteWheel, secondWheel, profileWheel)
    private var hourValues = intArrayOf(0)
    private var minuteValues = rangeDurationWheelValues(step = 1, maxInclusive = 59, currentValue = 0)
    private var secondValues = rangeDurationWheelValues(step = 1, maxInclusive = 59, currentValue = 0)

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(initialStyle.fontFamily, Typeface.NORMAL)
        textSize = initialStyle.valueSp * textScale
    }
    private val profilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(initialStyle.fontFamily, Typeface.BOLD)
        textSize = initialStyle.profileSp * textScale
    }
    private val colonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(initialStyle.fontFamily, Typeface.BOLD)
        textSize = initialStyle.valueSp * textScale
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = initialStyle.strokeDp * density
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
    }
    private var valueBaseline = -(valuePaint.ascent() + valuePaint.descent()) * 0.5f
    private var profileBaseline = -(profilePaint.ascent() + profilePaint.descent()) * 0.5f
    private var colonBaseline = -(colonPaint.ascent() + colonPaint.descent()) * 0.5f
    private val numberBuffer = CharArray(12)

    @ColorInt private var inkColor = Color.WHITE
    @ColorInt private var mutedColor = Color.GRAY
    @ColorInt private var borderColor = Color.DKGRAY
    @ColorInt private var errorColor = Color.RED
    private var paletteMotion:ViewColorTransition?=null

    private var maximumDurationSecondsExact = 0.0
    private var maximumWholeSeconds = 0
    private var maximumParts = RangeDurationWheelTimeParts(0, 0, 0)
    private var currentDurationSecondsExact = 0.0

    private var timeRight = 1f
    private var timeColumnWidth = 1f
    private var hourCenter = 0f
    private var minuteCenter = 0f
    private var secondCenter = 0f
    private var firstColon = 0f
    private var secondColon = 0f
    private var dividerX = 0f
    private var profileStart = 0f
    private var profileWidth = 1f
    private var profileCenter = 0f

    private var profileIndex = 0
    private var activeWheelIndex = NO_WHEEL
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downY = 0f
    private var downPosition = 0.0
    private var downRowPx = rowPx
    private var dragged = false

    private var animationWheelIndex = NO_WHEEL
    private var animationStart = 0.0
    private var animationTarget = 0.0
    private val animationPlayTime = ScrollPlayTime()
    private var animationDurationMs = style.snapMillis
    private var lastCommittedSeconds = 0
    private var accessibilityLabel = ""

    var onInteractionStart: (() -> Unit)? = null
    var onInteractionEnd: (() -> Unit)? = null
    var onDurationChanged: ((Int) -> Unit)? = null

    private val animationFrame = object : Runnable {
        override fun run() {
            if (advanceSnap(nanoTime.asLong)) postOnAnimation(this)
        }
    }

    internal fun advanceSnap(frameTimeNanos: Long): Boolean {
        val index = animationWheelIndex
        if (index == NO_WHEEL) return false
        val playTime = animationPlayTime.advance(frameTimeNanos, durationScale.read())
        val elapsedMs = playTime / 1_000_000f
        if (playTime == Long.MAX_VALUE || elapsedMs >= animationDurationMs) {
            wheels[index].position = animationTarget
            stopAnimation()
            finishInteraction(index)
            return false
        }
        val eased = durationWheelSnapEasing(elapsedMs / animationDurationMs)
        wheels[index].position = animationStart +
            (animationTarget - animationStart) * eased.toDouble()
        invalidate()
        return true
    }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateContentDescription()
    }

    fun setAccessibilityLabel(label: String) {
        if (accessibilityLabel == label) return
        accessibilityLabel = label
        updateContentDescription()
    }

    fun setAppearance(style: NativeDurationWheelStyle, palette: NativeColorScheme) {
        if (this.style != style) {
            this.style = style
            rowPx = style.rowDp * density
            radiusPx = style.radiusDp * density
            perspectivePx = style.perspectiveDp * density
            centerYOffsetPx = style.centerYOffsetDp * density
            valuePaint.typeface = Typeface.create(style.fontFamily, Typeface.NORMAL)
            profilePaint.typeface = Typeface.create(style.fontFamily, Typeface.BOLD)
            colonPaint.typeface = profilePaint.typeface
            valuePaint.textSize = style.valueSp * textScale
            profilePaint.textSize = style.profileSp * textScale
            colonPaint.textSize = style.valueSp * textScale
            valueBaseline = -(valuePaint.ascent() + valuePaint.descent()) * 0.5f
            profileBaseline = -(profilePaint.ascent() + profilePaint.descent()) * 0.5f
            colonBaseline = -(colonPaint.ascent() + colonPaint.descent()) * 0.5f
            linePaint.strokeWidth = style.strokeDp * density
            updateHorizontalGeometry(width)
            requestLayout()
            invalidate()
        }
        setPalette(palette.onSurface, palette.onSurfaceVariant, palette.outlineVariant, palette.error)
    }

    private fun setPalette(
        @ColorInt ink: Int,
        @ColorInt muted: Int,
        @ColorInt border: Int,
        @ColorInt error: Int,
    ) {
        val motion=paletteMotion ?: ViewColorTransition(this,intArrayOf(ink,muted,border,error),{
            paletteMotion?.let {applyPalette(it.color(0),it.color(1),it.color(2),it.color(3))}
        }).also {paletteMotion=it;applyPalette(ink,muted,border,error)}
        motion.retarget(ink,muted,border,error)
    }

    private fun applyPalette(ink:Int,muted:Int,border:Int,error:Int) {
        inkColor = ink
        mutedColor = muted
        borderColor = border
        errorColor = error
        invalidate()
    }

    fun setMaximumDurationSeconds(seconds: Double) {
        val safe = seconds.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        if (maximumDurationSecondsExact == safe) return
        maximumDurationSecondsExact = safe
        val wholeLimit = rangeDurationWheelWholeLimitSeconds(safe)
        val limitChanged = maximumWholeSeconds != wholeLimit
        if (limitChanged) {
            maximumWholeSeconds = wholeLimit
            maximumParts = splitRangeDurationWheelSeconds(wholeLimit)
        }
        val gesturing = isGestureActive()
        val selectedSeconds = currentTotalSeconds()
        val displaySeconds = if (gesturing) selectedSeconds else {
            rangeDurationWheelDisplaySeconds(currentDurationSecondsExact, safe)
        }
        // Fractions can change overflow rounding without changing the selectable integer values.
        if (!limitChanged && displaySeconds == selectedSeconds) return
        if (!gesturing) lastCommittedSeconds = displaySeconds
        rebuildForDisplaySeconds(displaySeconds)
    }

    fun setDurationSeconds(seconds: Double) {
        val safe = seconds.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        if (isGestureActive()) return
        if (currentDurationSecondsExact == safe) return
        currentDurationSecondsExact = safe
        val displaySeconds = rangeDurationWheelDisplaySeconds(safe, maximumDurationSecondsExact)
        if (lastCommittedSeconds == displaySeconds) return
        lastCommittedSeconds = displaySeconds
        rebuildForDisplaySeconds(displaySeconds)
    }

    private fun rebuildForDisplaySeconds(displaySeconds: Int) {
        val parts = splitRangeDurationWheelSeconds(displaySeconds)
        rebuildTimeWheels(parts.hours, parts.minutes, parts.seconds)
        updateContentDescription()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (style.widthDp * density).toInt()
        val desiredHeight = (style.heightDp * density).toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateHorizontalGeometry(w)
    }

    private fun updateHorizontalGeometry(w: Int) {
        profileWidth = w * style.profileWidthFraction
        val separatorGap = style.profileGapDp * density
        profileStart = w - profileWidth
        timeRight = (profileStart - separatorGap).coerceAtLeast(1f)
        timeColumnWidth = timeRight / 3f
        hourCenter = timeColumnWidth * 0.5f
        minuteCenter = timeColumnWidth * 1.5f
        secondCenter = timeColumnWidth * 2.5f
        firstColon = timeColumnWidth
        secondColon = timeColumnWidth * 2f
        dividerX = profileStart - separatorGap * 0.5f
        profileCenter = profileStart + profileWidth * 0.5f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // The dial grid is shared geometry, not a border drawn independently around every value.
        // Each separator exists once at the half-step between adjacent faces; the vertical rails
        // join those exact projected endpoints so there are no doubled or misaligned strokes.
        drawWheelGrid(canvas, hourWheel, hourCenter, timeColumnWidth * style.timeGridHalfWidthFraction)
        drawWheelGrid(canvas, minuteWheel, minuteCenter, timeColumnWidth * style.timeGridHalfWidthFraction)
        drawWheelGrid(canvas, secondWheel, secondCenter, timeColumnWidth * style.timeGridHalfWidthFraction)
        drawWheelGrid(canvas, profileWheel, profileCenter, profileWidth * style.profileGridHalfWidthFraction)

        linePaint.color = borderColor
        linePaint.alpha = (style.dividerAlpha * 255f).toInt()
        canvas.drawLine(dividerX, 0f, dividerX, height.toFloat(), linePaint)
        linePaint.alpha = 255

        val selectedHour = currentHour()
        val selectedMinute = currentMinute()
        val selectedSecond = currentSecond()
        val errorMask = currentErrorMask(selectedHour, selectedMinute, selectedSecond)
        colonPaint.alpha = 255
        colonPaint.color = if (
            errorMask and RANGE_DURATION_WHEEL_ERROR_HOUR != 0
        ) errorColor else mutedColor
        canvas.drawText(":", firstColon, dialCenterY() + colonBaseline, colonPaint)
        colonPaint.color = if (
            errorMask and (RANGE_DURATION_WHEEL_ERROR_HOUR or RANGE_DURATION_WHEEL_ERROR_MINUTE) != 0
        ) errorColor else mutedColor
        canvas.drawText(":", secondColon, dialCenterY() + colonBaseline, colonPaint)

        drawNumberWheel(
            canvas, hourWheel, hourCenter, NumberKind.HOUR,
            selectedHour, selectedMinute, selectedSecond, errorMask,
        )
        drawNumberWheel(
            canvas, minuteWheel, minuteCenter, NumberKind.MINUTE,
            selectedHour, selectedMinute, selectedSecond, errorMask,
        )
        drawNumberWheel(
            canvas, secondWheel, secondCenter, NumberKind.SECOND,
            selectedHour, selectedMinute, selectedSecond, errorMask,
        )
        drawProfileWheel(canvas, profileCenter)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // View.dispatchTouchEvent can still reach an overridden onTouchEvent while disabled.
        // Block new input, but let an interaction accepted while enabled receive its terminal
        // UP/CANCEL so Compose cannot be left holding the native wheel edit lock.
        if (!rangeDurationWheelInputAllowed(isEnabled, activeWheelIndex != NO_WHEEL)) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = wheelIndexAt(event.x)
                if (index == NO_WHEEL) return false
                stopAnimation()
                onInteractionStart?.invoke()
                requestFocus()
                parent?.requestDisallowInterceptTouchEvent(true)
                activeWheelIndex = index
                pointerId = event.getPointerId(0)
                downY = event.y
                downPosition = wheels[index].position
                downRowPx = rowPx
                dragged = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(pointerId)
                if (pointerIndex < 0 || activeWheelIndex == NO_WHEEL) return false
                val deltaY = event.getY(pointerIndex) - downY
                if (abs(deltaY) > touchSlop) dragged = true
                wheels[activeWheelIndex].position = downPosition - deltaY / downRowPx
                postInvalidateOnAnimation()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (activeWheelIndex == NO_WHEEL) return false
                if (!dragged) {
                    performClick()
                    val third = height / 3f
                    when {
                        event.y < third -> wheels[activeWheelIndex].position -= 1.0
                        event.y > third * 2f -> wheels[activeWheelIndex].position += 1.0
                    }
                }
                snapActiveWheel()
                pointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    if (event.flags and MotionEvent.FLAG_CANCELED != 0) {
                        cancelTouchInteraction()
                    } else {
                        if (activeWheelIndex != NO_WHEEL) snapActiveWheel()
                        pointerId = MotionEvent.INVALID_POINTER_ID
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelTouchInteraction()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!rangeDurationWheelInputAllowed(isEnabled, interactionAlreadyActive = false)) return false
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        ) {
            val index = wheelIndexAt(event.x)
            if (index == NO_WHEEL) return false
            val delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (delta == 0f) return false
            // A held touch owns the wheel until its terminal event.
            if (activeWheelIndex != NO_WHEEL) return true
            stopAnimation()
            onInteractionStart?.invoke()
            wheels[index].position += if (delta > 0f) -1.0 else 1.0
            animateTo(index, round(wheels[index].position))
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        val hadInteraction = isGestureActive()
        stopAnimation()
        activeWheelIndex = NO_WHEEL
        pointerId = MotionEvent.INVALID_POINTER_ID
        if (hadInteraction) onInteractionEnd?.invoke()
        super.onDetachedFromWindow()
    }

    private fun snapActiveWheel() {
        val index = activeWheelIndex
        activeWheelIndex = NO_WHEEL
        if (index == NO_WHEEL) return
        animateTo(index, round(wheels[index].position))
    }

    private fun cancelTouchInteraction() {
        val wasActive = activeWheelIndex != NO_WHEEL
        activeWheelIndex = NO_WHEEL
        pointerId = MotionEvent.INVALID_POINTER_ID
        try {
            parent?.requestDisallowInterceptTouchEvent(false)
            // Without an active pointer, an earlier UP may already own the snap.
            if (!wasActive) return
            stopAnimation()
            profileWheel.position = profileIndex.toDouble()
            val displaySeconds = rangeDurationWheelDisplaySeconds(
                currentDurationSecondsExact, maximumDurationSecondsExact,
            )
            lastCommittedSeconds = displaySeconds
            rebuildForDisplaySeconds(displaySeconds)
        } finally {
            if (wasActive) onInteractionEnd?.invoke()
        }
    }

    private fun animateTo(index: Int, target: Double) {
        stopAnimation()
        val start = wheels[index].position
        val scale = durationScale.read()
        if (abs(target - start) < 0.0001 || style.snapMillis == 0f || scale == 0f) {
            wheels[index].position = target
            finishInteraction(index)
            return
        }
        animationWheelIndex = index
        animationStart = start
        animationTarget = target
        animationPlayTime.reset()
        animationPlayTime.advance(nanoTime.asLong, scale)
        animationDurationMs = style.snapMillis
        postOnAnimation(animationFrame)
    }

    private fun stopAnimation() {
        if (animationWheelIndex == NO_WHEEL) return
        removeCallbacks(animationFrame)
        animationWheelIndex = NO_WHEEL
    }

    private fun finishInteraction(index: Int) {
        try {
            if (index == PROFILE_WHEEL) {
                applyProfileSelection()
            } else {
                val selected = currentDisplayParts()
                rebuildTimeWheels(selected.hours, selected.minutes, selected.seconds)
            }
            publishDurationIfChanged()
            updateContentDescription()
            invalidate()
        } finally {
            // Terminal delivery is independent of whether the selected duration changed. Compose
            // uses this to release the edit owner/export lock after every native settle.
            onInteractionEnd?.invoke()
        }
    }

    private fun publishDurationIfChanged() {
        val seconds = currentTotalSeconds()
        currentDurationSecondsExact = seconds.toDouble()
        if (seconds == lastCommittedSeconds) return
        lastCommittedSeconds = seconds
        onDurationChanged?.invoke(seconds)
    }

    private fun applyProfileSelection() {
        val newProfile = normalizedIndex(profileWheel)
        val selected = currentDisplayParts()
        profileIndex = newProfile
        profileWheel.position = newProfile.toDouble()
        rebuildTimeWheels(selected.hours, selected.minutes, selected.seconds)
    }

    private fun rebuildTimeWheels(hours: Int, minutes: Int, seconds: Int) {
        val step = rangeDurationWheelProfileStep(profileIndex)
        hourValues = rangeDurationWheelValues(
            step = 1,
            maxInclusive = maximumParts.hours,
            currentValue = hours,
        )
        val minuteConstrained = hours == maximumParts.hours
        val minuteMax = if (minuteConstrained) maximumParts.minutes else 59
        minuteValues = rangeDurationWheelValues(
            step = step,
            maxInclusive = minuteMax,
            currentValue = minutes.coerceIn(0, 59),
            includeMaximumBoundary = minuteConstrained,
        )
        val secondConstrained = hours == maximumParts.hours && minutes == maximumParts.minutes
        val secondMax = if (secondConstrained) maximumParts.seconds else 59
        secondValues = rangeDurationWheelValues(
            step = step,
            maxInclusive = secondMax,
            currentValue = seconds.coerceIn(0, 59),
            includeMaximumBoundary = secondConstrained,
        )
        setWheelValue(hourWheel, hourValues, hours)
        setWheelValue(minuteWheel, minuteValues, minutes.coerceIn(0, 59))
        setWheelValue(secondWheel, secondValues, seconds.coerceIn(0, 59))
    }

    private fun setWheelValue(wheel: Wheel, values: IntArray, value: Int) {
        wheel.count = values.size.coerceAtLeast(1)
        val index = values.indexOf(value).takeIf { it >= 0 } ?: 0
        wheel.position = index.toDouble()
    }

    private fun currentDisplayParts(): RangeDurationWheelTimeParts = RangeDurationWheelTimeParts(
        hours = currentHour(),
        minutes = currentMinute(),
        seconds = currentSecond(),
    )

    private fun currentTotalSeconds(): Int = composeRangeDurationWheelSeconds(
        hours = currentHour(),
        minutes = currentMinute(),
        seconds = currentSecond(),
    )

    private fun currentHour(): Int = hourValues[normalizedIndex(hourWheel)]

    private fun currentMinute(): Int = minuteValues[normalizedIndex(minuteWheel)]

    private fun currentSecond(): Int = secondValues[normalizedIndex(secondWheel)]

    private fun normalizedIndex(wheel: Wheel): Int {
        val rounded = round(wheel.position).toLong()
        val count = wheel.count.toLong().coerceAtLeast(1L)
        val modulo = rounded % count
        return (if (modulo < 0L) modulo + count else modulo).toInt()
    }

    private fun drawNumberWheel(
        canvas: Canvas,
        wheel: Wheel,
        centerX: Float,
        kind: NumberKind,
        selectedHour: Int,
        selectedMinute: Int,
        selectedSecond: Int,
        errorMask: Int,
    ) {
        val values = when (kind) {
            NumberKind.HOUR -> hourValues
            NumberKind.MINUTE -> minuteValues
            NumberKind.SECOND -> secondValues
        }
        val base = floor(wheel.position).toLong()
        val selectedLogical = round(wheel.position).toLong()
        for (offset in -3..3) {
            val logical = base + offset
            val relative = logical - wheel.position
            if (abs(relative) >= style.faceVisibleLimit) continue
            val value = values[modulo(logical, values.size)]
            drawCylinderValue(
                canvas = canvas,
                relative = relative.toFloat(),
                centerX = centerX,
                paint = valuePaint,
                overLimit = timeValueIsOverLimit(
                    kind = kind,
                    candidate = value,
                    isSelectedFace = logical == selectedLogical,
                    selectedHour = selectedHour,
                    selectedMinute = selectedMinute,
                    selectedSecond = selectedSecond,
                    errorMask = errorMask,
                ),
            ) {
                drawNumber(canvas, value, valuePaint)
            }
        }
    }

    private fun currentErrorMask(
        hours: Int,
        minutes: Int,
        seconds: Int,
    ): Int = rangeDurationWheelErrorMask(
        hours = hours,
        minutes = minutes,
        seconds = seconds,
        maximumWholeSeconds = maximumWholeSeconds,
    )

    private fun timeValueIsOverLimit(
        kind: NumberKind,
        candidate: Int,
        isSelectedFace: Boolean,
        selectedHour: Int,
        selectedMinute: Int,
        selectedSecond: Int,
        errorMask: Int,
    ): Boolean {
        if (isSelectedFace) {
            return when (kind) {
                NumberKind.HOUR -> errorMask and RANGE_DURATION_WHEEL_ERROR_HOUR != 0
                NumberKind.MINUTE -> errorMask and RANGE_DURATION_WHEEL_ERROR_MINUTE != 0
                NumberKind.SECOND -> errorMask and RANGE_DURATION_WHEEL_ERROR_SECOND != 0
            }
        }

        val candidateHour = if (kind == NumberKind.HOUR) candidate else selectedHour
        val candidateMinute = if (kind == NumberKind.MINUTE) candidate else selectedMinute
        val candidateSecond = if (kind == NumberKind.SECOND) candidate else selectedSecond
        return rangeDurationWheelCandidateIsOverLimit(
            hours = candidateHour,
            minutes = candidateMinute,
            seconds = candidateSecond,
            maximumDurationSecondsExact = maximumDurationSecondsExact,
        )
    }

    private fun drawProfileWheel(canvas: Canvas, centerX: Float) {
        val base = floor(profileWheel.position).toLong()
        for (offset in -3..3) {
            val logical = base + offset
            val relative = logical - profileWheel.position
            if (abs(relative) >= style.faceVisibleLimit) continue
            val value = PROFILE_LABELS[modulo(logical, PROFILE_LABELS.size)]
            drawCylinderValue(canvas, relative.toFloat(), centerX, profilePaint) {
                canvas.drawText(value, 0f, profileBaseline, profilePaint)
            }
        }
    }

    private inline fun drawCylinderValue(
        canvas: Canvas,
        relative: Float,
        centerX: Float,
        paint: Paint,
        overLimit: Boolean = false,
        drawText: () -> Unit,
    ) {
        val angle = relative * style.angleStepDegrees
        val radians = angle * PI.toFloat() / 180f
        val sine = sin(radians)
        val cosine = cos(radians)
        if (cosine <= 0f) return
        val y = sine * radiusPx
        val z = cosine * radiusPx - radiusPx
        val projectionScale = perspectivePx / (perspectivePx - z)
        val absolute = abs(relative)
        val edgeFade = ((style.faceVisibleLimit - absolute) / style.faceEdgeFadeSpan).coerceIn(0f, 1f)
        val facing = cosine.coerceAtLeast(style.minFaceAlpha)
        val opacity = min(1f, facing * (0.55f + edgeFade * 0.45f))
        val centerMix = (1f - absolute / 0.85f).coerceIn(0f, 1f)

        paint.color = if (overLimit) errorColor else blendColor(mutedColor, inkColor, centerMix)
        paint.alpha = (opacity * 255f).toInt().coerceIn(0, 255)

        canvas.save()
        canvas.translate(centerX, dialCenterY() + y)
        val scale = projectionScale * (1f - min(absolute, 1.5f) * 0.05f)
        canvas.scale(scale, scale * cosine)
        drawText()
        canvas.restore()
    }

    private fun drawNumber(canvas: Canvas, value: Int, paint: Paint) {
        var cursor = numberBuffer.size
        var remaining = value
        do {
            numberBuffer[--cursor] = ('0'.code + remaining % 10).toChar()
            remaining /= 10
        } while (remaining > 0)
        if (numberBuffer.size - cursor == 1) numberBuffer[--cursor] = '0'
        canvas.drawText(
            numberBuffer,
            cursor,
            numberBuffer.size - cursor,
            0f,
            valueBaseline,
            paint,
        )
    }

    private fun drawWheelGrid(
        canvas: Canvas,
        wheel: Wheel,
        centerX: Float,
        baseHalfWidth: Float,
    ) {
        val base = floor(wheel.position).toLong()
        var previousVisible = false
        var previousLeft = 0f
        var previousRight = 0f
        var previousY = 0f
        var previousAlpha = 0f

        // Eight half-step boundaries cover the same seven pooled faces as the value renderer.
        for (offset in -4..3) {
            val logicalBoundary = base + offset + 0.5
            val relative = (logicalBoundary - wheel.position).toFloat()
            val absolute = abs(relative)
            if (absolute >= style.gridVisibleLimit) continue

            val radians = relative * style.angleStepDegrees * PI.toFloat() / 180f
            val cosine = cos(radians)
            if (cosine <= 0f) continue
            val sine = sin(radians)
            val y = dialCenterY() + sine * radiusPx
            val z = cosine * radiusPx - radiusPx
            val projectionScale = perspectivePx / (perspectivePx - z)
            val widthScale = projectionScale *
                (1f - min(absolute, style.gridWidthFalloffLimit) * style.gridWidthFalloff)
            val halfWidth = baseHalfWidth * widthScale
            val left = centerX - halfWidth
            val right = centerX + halfWidth
            val edgeFade = ((style.gridVisibleLimit - absolute) / style.gridEdgeFadeSpan)
                .coerceIn(0f, 1f)
            val alpha = style.gridFrontAlpha * cosine * (0.35f + edgeFade * 0.65f)

            linePaint.color = borderColor
            linePaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawLine(left, y, right, y, linePaint)

            if (previousVisible) {
                linePaint.alpha = (min(previousAlpha, alpha) * 255f).toInt().coerceIn(0, 255)
                canvas.drawLine(previousLeft, previousY, left, y, linePaint)
                canvas.drawLine(previousRight, previousY, right, y, linePaint)
            }
            previousVisible = true
            previousLeft = left
            previousRight = right
            previousY = y
            previousAlpha = alpha
        }
        linePaint.alpha = 255
    }

    private fun dialCenterY(): Float = height * 0.5f + centerYOffsetPx

    private fun wheelIndexAt(x: Float): Int {
        if (x >= profileStart) return PROFILE_WHEEL
        if (x < 0f || x > timeRight) return NO_WHEEL
        val hitHalfWidth = timeColumnWidth * style.timeHitHalfWidthFraction
        return when {
            abs(x - hourCenter) <= hitHalfWidth -> HOUR_WHEEL
            abs(x - minuteCenter) <= hitHalfWidth -> MINUTE_WHEEL
            abs(x - secondCenter) <= hitHalfWidth -> SECOND_WHEEL
            else -> NO_WHEEL
        }
    }

    private fun updateContentDescription() {
        val parts = splitRangeDurationWheelSeconds(currentTotalSeconds())
        contentDescription = buildString(40) {
            if (accessibilityLabel.isNotEmpty()) {
                append(accessibilityLabel)
                append(", ")
            }
            append(parts.hours)
            append(':')
            if (parts.minutes < 10) append('0')
            append(parts.minutes)
            append(':')
            if (parts.seconds < 10) append('0')
            append(parts.seconds)
            append(' ')
            append(PROFILE_LABELS[profileIndex])
        }
    }

    private fun isGestureActive(): Boolean =
        activeWheelIndex != NO_WHEEL || animationWheelIndex != NO_WHEEL

    private fun modulo(value: Long, count: Int): Int {
        val divisor = count.toLong()
        val result = value % divisor
        return (if (result < 0L) result + divisor else result).toInt()
    }

    private fun blendColor(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).toInt(),
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt(),
        )
    }

    private enum class NumberKind { HOUR, MINUTE, SECOND }

    private companion object {
        const val HOUR_WHEEL = 0
        const val MINUTE_WHEEL = 1
        const val SECOND_WHEEL = 2
        const val PROFILE_WHEEL = 3
        const val NO_WHEEL = -1
        val PROFILE_LABELS = arrayOf("1x", "5x", "15x")
    }
}
