package smallthingz.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import android.graphics.drawable.Drawable
import smallthingz.ui.animation.smoothProgressSpring
import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.animation.view.ViewSpringDriver
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.compositeOver
import smallthingz.ui.graphics.toArgb
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.max
import kotlin.math.roundToInt

/** Shared action with opt-in filled calibration progress; Initial gesture owns the stable outer box. */
open class NativeProgressActionView(
    context:Context,
    environment:NativeSettingsVisualEnvironment,
    inputRoot:()->View?,
    leadingIcon: ((Float, Int) -> Drawable)? = null,
    private val stableLabels:List<String> = emptyList(),
    private val progressSurface:Boolean=false,
    samplingLabel: () -> String = { "" },
    action:()->Unit,
):ViewGroup(context),NativeMergedActionChild,AutoCloseable {
    private var environment=environment
    private val visual=CalibrationActionSurface(context,environment,inputRoot,leadingIcon,progressSurface,samplingLabel,action)
    private val sizingLabel=if(stableLabels.isEmpty())null else ParagraphView(context,environment.appearance.typography.buttonLabelStyle,
        environment.textDensity,environment.sharedFontResolver,environment.palette.primary)
    val textView get()=visual.label
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren=false;clipToPadding=false;addView(visual)
    }
    private var reserveStableIcon = leadingIcon != null
    internal fun reserveStableLeadingIcon() {
        if (reserveStableIcon) return
        reserveStableIcon = true
        requestLayout()
    }
    fun bind(text:String,enabled:Boolean,next:NativeSettingsVisualEnvironment) {
        environment=next;visual.bind(text,enabled,next)
    }
    internal fun setLeadingActionIcon(icon: Drawable?) = visual.setLeadingIcon(icon)
    fun bindProgress(progress:Float?,running:Boolean) = visual.bindProgress(progress,running)

    override val actionMinimumWidthPx: Int get() = max(visual.actionMinimumWidthPx,
        (environment.appearance.calibration.actionMinTargetDp * environment.textDensity.density).roundToInt())
    override fun joinActionSurface() = visual.joinActionSurface()

    /** Adds instructions to the button's accessibility label without adding visible text. */
    internal fun setActionDescription(description: String?) { visual.contentDescription = description }
    /** Native hosts can expose this action until a source-geometry owner takes over. */
    internal fun enableStandaloneAccessibility() = visual.enableStandaloneAccessibility()
    override fun onMeasure(w:Int,h:Int) {
        visual.measure(w,h)
        val minimum=(environment.appearance.calibration.actionMinTargetDp * environment.textDensity.density).roundToInt()
        var stableHeight=minimum
        sizingLabel?.let {label->
            label.setTypography(environment.appearance.typography.buttonLabelStyle,environment.textDensity,environment.sharedFontResolver)
            val style = environment.appearance.calibration.actionSurface
            val density = environment.textDensity.density
            val iconSize = if (reserveStableIcon) (style.iconSizeDp * density).roundToInt() else 0
            val iconGap = if (reserveStableIcon) (style.iconGapDp * density).roundToInt() else 0
            val padding = 2 * (style.horizontalPaddingDp * density).roundToInt()
            val textWidth = if (MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED) unspecified()
                else atMost((MeasureSpec.getSize(w) - padding - iconSize - iconGap).coerceAtLeast(0))
            val vertical = 2 * (style.verticalPaddingDp * density).roundToInt()
            stableLabels.forEach { text ->
                label.setText(text)
                label.measure(textWidth, unspecified())
                stableHeight = max(stableHeight, max(iconSize, label.measuredHeight) + vertical)
            }
        }
        val measuredW=resolveSize(max(minimum,visual.measuredWidth),w)
        val measuredH=resolveSize(max(stableHeight,visual.measuredHeight),h)
        if(progressSurface)visual.measure(exact(measuredW),exact(measuredH))
        setMeasuredDimension(measuredW,measuredH)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val x=((width-visual.measuredWidth)/2f).roundToInt()
        val y=((height-visual.measuredHeight)/2f).roundToInt()
        visual.layout(x,y,x+visual.measuredWidth,y+visual.measuredHeight)
    }
    override fun close()=visual.close()
}

private class CalibrationActionSurface(
    context: Context,
    private var environment: NativeSettingsVisualEnvironment,
    inputRoot: () -> View?,
    private val leadingIcon: ((Float, Int) -> Drawable)?,
    private val progressSurface: Boolean,
    private val samplingLabel: () -> String,
    action: () -> Unit,
) : NativeMaterialButtonView(context, false, environment.appearance.typography.buttonLabelStyle, environment.textDensity,
    environment.sharedFontResolver, environment.appearance.calibration.actionSurface,
    NativeButtonInputPolicy(inputRoot), action) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress: Float? = null
    private var running = false
    private var closed = false
    private val fill = smoothProgressSpring()
    private var fillDriver: ViewSpringDriver<Float, AnimationVector1D>? = null
    private var heldPulse: ValueAnimator? = null
    private var heldOpacity = .7f
    private val progressColors = if (progressSurface) ViewColorTransition(this,
        intArrayOf(progressTint()), { invalidate() }) else null

    init { bindIcon() }

    fun enableStandaloneAccessibility() {
        mergedRowAccessibility.enableStandaloneAccessibility({ !closed }) {
            contentDescription ?: label.getText()
        }
    }

    override fun mergedActionDescription(): CharSequence? = contentDescription

    private fun bindIcon() {
        leadingIcon?.let { setLeadingIcon(it(environment.textDensity.density, environment.palette.primary)) }
    }

    fun bind(text: String, enabled: Boolean, next: NativeSettingsVisualEnvironment) {
        val densityChanged = environment.textDensity.density != next.textDensity.density
        environment = next
        if (densityChanged) bindIcon()
        bindTypography(environment.appearance.typography.buttonLabelStyle, next.textDensity, next.sharedFontResolver)
        bindColors(text, enabled)
    }

    private fun progressTint() = Color(environment.palette.primary).copy(alpha = environment.appearance.calibration.progressOpacity).toArgb()

    private fun bindColors(text: String, enabled: Boolean) {
        val palette = environment.palette
        // This action uses the raw-color overload, so it must forward the window's surface policy.
        // Transparent actions reveal their containing group's grain; progress fills paint their own.
        setSurfacePreset(palette.surfacePreset)
        val ink = when {
            progressSurface && (enabled || running) -> palette.ink
            enabled -> palette.primary
            else -> Color(palette.onSurfaceVariant).copy(alpha = environment.appearance.calibration.disabledActionContentOpacity).toArgb()
        }
        // Resolve the tonal fill against the shared Settings/Onboarding group role.
        // An opaque owner paints theme grain once, without attenuating it by the tonal opacity.
        val container = if (progressSurface) Color(palette.primary)
            .copy(alpha = if (enabled || running) environment.appearance.calibration.actionOpacity
                else environment.appearance.calibration.disabledActionOpacity)
            .compositeOver(Color(palette.foregroundFill)).toArgb() else 0
        bind(text, enabled, container, ink)
        progressColors?.retarget(progressTint())
    }

    fun bindProgress(value: Float?, active: Boolean) {
        val starting = active && !running
        progress = value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        running = active
        if (starting) {
            stopMotion()
            fill.snapTo(0f)
            heldOpacity = .7f
        }
        updateMotion()
        bindColors(label.getText(), isEnabled)
        ViewCompat.setStateDescription(this, if (active) progressDescription() else null)
        invalidate()
    }

    private fun progressDescription() = progress?.let { "${(it * 100).roundToInt()}%" }
        ?: samplingLabel()

    private fun stopMotion() {
        fillDriver?.close()
        fillDriver = null
        heldPulse?.cancel()
        heldPulse = null
    }

    private fun updateMotion() {
        if (closed || !isAttachedToWindow || !isShown || windowVisibility != VISIBLE || !running) {
            stopMotion()
            fill.snapTo(progress ?: 0f)
            return
        }
        val target = progress
        if (target == null) {
            fillDriver?.close()
            fillDriver = null
            if (heldPulse == null && ValueAnimator.areAnimatorsEnabled()) {
                heldPulse = ValueAnimator.ofFloat(.45f, 1f).apply {
                    duration = environment.appearance.calibration.heldPulseMillis
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { heldOpacity = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        } else {
            heldPulse?.cancel()
            heldPulse = null
            val driver = fillDriver ?: ViewSpringDriver(this, fill, { invalidate() }).also { fillDriver = it }
            driver.retarget(target)
        }
    }

    override fun drawButtonOverlay(canvas: Canvas, clip: Path) {
        if (!running || progressColors == null) return
        val checkpoint = canvas.save()
        try {
            canvas.clipPath(clip)
            paint.color = progressColors.color(0)
            val fraction = if (progress == null) {
                paint.alpha = (paint.alpha * heldOpacity).roundToInt()
                1f
            } else fill.value.coerceIn(0f, 1f)
            if (layoutDirection == LAYOUT_DIRECTION_RTL) {
                canvas.drawRect(width * (1f - fraction), 0f, width.toFloat(), height.toFloat(), paint)
            } else canvas.drawRect(0f, 0f, width * fraction, height.toFloat(), paint)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? = super.createAccessibilityNodeInfo()?.also { node ->
        if (progressSurface && running) {
            AccessibilityNodeInfoCompat.wrap(node).stateDescription = progressDescription()
            progress?.let { node.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 1f, it) }
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateMotion() }
    override fun onDetachedFromWindow() { stopMotion(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) updateMotion()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) updateMotion()
    }
    override fun close() {
        if (closed) return
        closed = true
        stopMotion()
        progressColors?.close()
        super.close()
    }
}
