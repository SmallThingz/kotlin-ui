package smallthingz.ui.reverb.settings

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import smallthingz.ui.R
import smallthingz.ui.common.SegmentOption
import smallthingz.ui.components.exact
import smallthingz.ui.components.unspecified
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbTextRole
import smallthingz.ui.reverb.ReverbTextSurface
import kotlin.math.roundToInt

/** Retention's two buffer readouts share a single short, interruptible numeric transition. */
open class ReverbRetentionSection(
    context: Context,
    mode: ReverbRetentionMode,
    private var environment: ReverbEnvironment,
    private val titleText: () -> String,
    inputAllowed: (View) -> Boolean,
    onMode: (ReverbRetentionMode) -> Boolean,
    oneShotValue: View,
    loopingValue: View,
) : ViewGroup(context), AutoCloseable {
    private val sectionLayout = environment.appearance.settingsSelectors.retentionSection
    private val title = settingsSectionTitle(context, titleText(), environment)
    private var selectedMode = mode
    private var optionLocales = resources.configuration.locales
    private var modeOptions = loadModeOptions()
    private val modes = ReverbSegmentedSelector(context, environment.theme, modeOptions, mode,
        appearance = environment.appearance, inputAllowed = { inputAllowed(this) }, onSelectionRequest = onMode)
    private val fields = environment.appearance.pairedFields
    private val error = environment.paragraph(context, "", ReverbTextRole.BODY_SMALL, environment.palette.error).apply {
        visibility = GONE
    }
    private val body = fields.createFields(context, oneShotValue, loopingValue, error, environment.textDensity.density)
    private val surface = fields.createSurface(context, body, ReverbTextSurface::invalidate)
    private val header = modes.layoutWithTitle(title)
    private val oneShotNumbers = oneShotValue as? ReverbRetentionValueView
    private val loopingNumbers = loopingValue as? ReverbRetentionValueView
    private var animation: ValueAnimator? = null
    private var offset = 0f
    private var startOffset = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        fields.bindSurface(surface, environment.palette)
        addView(title)
        addView(modes.view)
        addView(surface)
    }

    fun bind(mode: ReverbRetentionMode, message: String?, next: ReverbEnvironment) {
        val modeChanged = selectedMode != mode
        val environmentChanged = environment !== next
        environment = next
        if (environmentChanged) {
            bindSectionTitle(title, next)
            next.bindParagraphStyle(error, ReverbTextRole.BODY_SMALL, next.palette.error)
            body.density = next.textDensity.density
            fields.bindSurface(surface, next.palette)
        }
        bindMode(mode)
        error.setText(message.orEmpty())
        error.visibility = if (message == null) GONE else VISIBLE
        if (modeChanged) animateNumbers()
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val inner = (width - 2 * dp(sectionLayout.sideInsetDp)).coerceAtLeast(0)
        header.measure(inner, inlineGap = dp(sectionLayout.inlineGapDp), stackedGap = dp(sectionLayout.stackedGapDp),
            preferredInlineWidth = dp(sectionLayout.preferredInlineWidthDp))
        surface.measure(exact(inner), unspecified())
        val height = header.measuredHeight + dp(sectionLayout.bodyGapDp) + surface.measuredHeight +
            dp(sectionLayout.trailingInsetDp)
        setMeasuredDimension(resolveSize(width, w), resolveSize(height, h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val inset = dp(sectionLayout.sideInsetDp)
        header.layout(width, inset, 0, layoutDirection == LAYOUT_DIRECTION_RTL)
        val top = header.measuredHeight + dp(sectionLayout.bodyGapDp)
        surface.layout(inset, top, width - inset, top + surface.measuredHeight)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (optionLocales != newConfig.locales) bindMode(selectedMode)
    }

    private fun bindMode(mode: ReverbRetentionMode) {
        selectedMode = mode
        val locales: LocaleList = resources.configuration.locales
        if (optionLocales != locales) {
            optionLocales = locales
            modeOptions = loadModeOptions()
            title.setText(titleText())
        }
        modes.bind(modeOptions, mode, environment.theme)
    }

    private fun loadModeOptions() = listOf(
        SegmentOption(ReverbRetentionMode.TIME, context.getString(R.string.native_settings_time_option)),
        SegmentOption(ReverbRetentionMode.SIZE, context.getString(R.string.native_settings_size_option)),
    )

    private fun animateNumbers() {
        if (oneShotNumbers == null && loopingNumbers == null) return
        if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE || !ValueAnimator.areAnimatorsEnabled()) {
            settleNumbers()
            return
        }
        val driver = animation ?: ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150L
            interpolator = DecelerateInterpolator()
            addUpdateListener { value -> publishNumbers(startOffset * (1f - value.animatedFraction)) }
        }.also { animation = it }
        startOffset = if (driver.isRunning) offset else -dp(8).toFloat()
        driver.cancel()
        publishNumbers(startOffset)
        driver.start()
    }

    private fun publishNumbers(offset: Float) {
        this.offset = offset
        oneShotNumbers?.setNumericOffset(offset)
        loopingNumbers?.setNumericOffset(offset)
    }

    private fun settleNumbers() {
        animation?.cancel()
        if (offset != 0f) publishNumbers(0f)
    }

    override fun onDetachedFromWindow() {
        settleNumbers()
        super.onDetachedFromWindow()
    }

    override fun close() {
        settleNumbers()
        modes.close()
    }

    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}
