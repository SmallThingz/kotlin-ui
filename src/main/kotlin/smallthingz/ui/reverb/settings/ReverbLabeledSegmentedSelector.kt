package smallthingz.ui.reverb.settings

import android.content.Context
import android.view.ViewGroup
import smallthingz.ui.common.SegmentOption
import smallthingz.ui.reverb.ReverbEnvironment
import kotlin.math.roundToInt

/** A settings section label and Reverb's responsive capsule for named modes. */
class ReverbLabeledSegmentedSelector<T : Any>(
    context: Context,
    environment: ReverbEnvironment,
    label: String,
    options: List<SegmentOption<T>>,
    selected: T,
    enabled: Boolean = true,
    inputAllowed: () -> Boolean,
    onSelectionRequest: (T) -> Boolean,
) : ViewGroup(context), AutoCloseable {
    private val recipe = environment.appearance.settingsSelectors.retentionSection
    private val title = settingsSectionTitle(context, label, environment)
    private val selector = ReverbSegmentedSelector(context, environment.theme, availableOptions(options, enabled),
        selected, inputAllowed, onSelectionRequest)
    private val content = selector.layoutWithTitle(title)
    private var environment = environment
    private var closed = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(title)
        addView(selector.view)
    }

    /** Bind the authoritative selection and enabled state without requesting a selection. */
    fun bind(label: String, options: List<SegmentOption<T>>, selected: T, enabled: Boolean,
             environment: ReverbEnvironment) {
        if (closed) return
        if (this.environment !== environment) {
            if (this.environment.textDensity.density != environment.textDensity.density) requestLayout()
            this.environment = environment
            bindSectionTitle(title, environment)
        }
        title.setText(label)
        selector.bind(availableOptions(options, enabled), selected, environment.theme)
    }

    override fun onMeasure(w: Int, h: Int) {
        val inset = dp(recipe.sideInsetDp)
        val inlineGap = dp(recipe.inlineGapDp)
        val width = if (MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED)
            content.preferredWidth(inlineGap) + 2 * inset else MeasureSpec.getSize(w)
        content.measure((width - 2 * inset).coerceAtLeast(0), inlineGap, dp(recipe.stackedGapDp),
            preferredInlineWidth = dp(recipe.preferredInlineWidthDp))
        setMeasuredDimension(resolveSize(width, w), resolveSize(content.measuredHeight, h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content.layout(width, dp(recipe.sideInsetDp), 0, layoutDirection == LAYOUT_DIRECTION_RTL)
    }

    override fun close() {
        if (closed) return
        closed = true
        selector.close()
    }

    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()

    private fun availableOptions(options: List<SegmentOption<T>>, enabled: Boolean) =
        if (enabled) options else options.map { if (it.enabled) it.copy(enabled = false, disabledReason = null) else it }
}
