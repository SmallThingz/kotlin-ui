package smallthingz.ui.inject

import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import smallthingz.ui.components.NativeBadge
import smallthingz.ui.text.NativeTypographyRole.BODY_LARGE
import smallthingz.ui.text.style.TextOverflow
import kotlin.math.roundToInt

/** Shared geometry for a title/readout above a single slider or a two-endpoint range slider. */
object InjectLabeledSliderRecipe {
    const val horizontalPaddingDp = 14f
    const val verticalPaddingDp = 10f
    const val stackedFontScale = 1.4f
    const val stackedGapDp = 4f
    const val inlineGapDp = 8f
    const val labelMaxLines = 2

    /** Endpoint values are spoken by the range primitive; the heading/readout stays decorative. */
    fun endpointLabel(label: String, endpoint: String, unit: String): String = buildString {
        append(label)
        if (!endpoint.equals(label, ignoreCase = true)) append(", ").append(endpoint)
        val spokenUnit = unit.trim()
        if (spokenUnit.isNotEmpty()) append(", ").append(spokenUnit)
    }
}

/**
 * Owns layout and readout semantics only. The host owns value formatting, edit admission,
 * release/debounce commits and asynchronous rebinding. The supplied control retains its focus.
 * Pass null [readoutSemanticsTarget] for a range whose two thumbs already speak their values.
 */
open class InjectLabeledSliderLayout(
    context: Context,
    ui: InjectAppearance,
    label: String,
    control: View,
    showValue: Boolean = true,
    private val readoutSemanticsTarget: View? = control,
) : LinearLayout(context) {
    private val recipe = InjectLabeledSliderRecipe
    private val title = ui.paragraph(label, BODY_LARGE).apply {
        setTextLayout(maxLines = recipe.labelMaxLines, overflow = TextOverflow.Ellipsis)
    }
    private val readout = NativeBadge(context, ui.scheme.settingsControls).apply {
        visibility = if (showValue) VISIBLE else GONE
    }
    private val header = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(title)
        addView(readout)
    }

    init {
        orientation = VERTICAL
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(control, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        readoutSemanticsTarget?.contentDescription = label
        applyLayoutRecipe()
    }

    fun bindReadout(text: String) {
        readout.setContent(null, text)
        readoutSemanticsTarget?.let { ViewCompat.setStateDescription(it, text) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayoutRecipe()
    }

    private fun applyLayoutRecipe() {
        setPaddingRelative(dp(recipe.horizontalPaddingDp), dp(recipe.verticalPaddingDp),
            dp(recipe.horizontalPaddingDp), dp(recipe.verticalPaddingDp))
        val stacked = resources.configuration.fontScale >= recipe.stackedFontScale
        header.orientation = if (stacked) VERTICAL else HORIZONTAL
        title.layoutParams = if (stacked) {
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(recipe.stackedGapDp)
            }
        } else LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        readout.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            if (!stacked) marginStart = dp(recipe.inlineGapDp)
        }
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density).roundToInt()
}
