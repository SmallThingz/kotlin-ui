package smallthingz.ui.reverb.settings

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbTextSurface
import smallthingz.ui.reverb.ReverbTheme
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.view.ParagraphView
import java.util.function.LongSupplier
import kotlin.math.roundToInt

/** Copy and conditional warning for the Recording section of Settings. */
data class ReverbRecordingPresentation(val title: String, val compressionWarning: String?)

/** Recording settings owns one continuous group for choices and the three feature sections. */
class ReverbRecordingSettingsView(
    context: Context,
    private val choices: ReverbAudioChoices<*, *, *, *>,
    compression: View,
    silenceDetection: View,
    saveNotification: View,
    initial: ReverbRecordingPresentation,
    private var theme: ReverbTheme,
) : ViewGroup(context) {
    private val appearance = ReverbDesign.appearance
    private val geometry = appearance.settingsSelectors.recordingSection
    private val title = ParagraphView(context, appearance.settingsSelectors.sectionTitle,
        theme.density, theme.fontResolver, theme.palette.onSurface)
    private val warningText = ParagraphView(context, appearance.typography[NativeTypographyRole.BODY_SMALL],
        theme.density, theme.fontResolver, theme.palette.error)
    private val warning = FrameLayout(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(warningText, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START))
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        title.paintedTextSurface = LongSupplier { ReverbTextSurface.sample(title) }
        addView(title)
        addView(choices.view)
        with(choices.group) {
            addRow(compression, groupedRow(true))
            addRow(warning, groupedRow(false))
            addRow(silenceDetection, groupedRow(true))
            addRow(saveNotification, groupedRow(true))
        }
        bind(initial, theme)
    }

    fun bind(next: ReverbRecordingPresentation, theme: ReverbTheme) {
        this.theme = theme
        title.setText(next.title)
        title.setTypography(appearance.settingsSelectors.sectionTitle, theme.density, theme.fontResolver)
        title.animateTextColor(theme.palette.onSurface)
        warning.visibility = if (next.compressionWarning == null) GONE else VISIBLE
        warningText.setText(next.compressionWarning.orEmpty())
        warningText.setTypography(appearance.typography[NativeTypographyRole.BODY_SMALL], theme.density, theme.fontResolver)
        warningText.animateTextColor(theme.palette.error)
        val horizontal = dp(geometry.warningHorizontalInsetDp)
        val vertical = dp(geometry.warningVerticalInsetDp)
        warning.setPadding(horizontal, vertical, horizontal, vertical)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val inner = (width - 2 * dp(geometry.sideInsetDp)).coerceAtLeast(0)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        title.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), unspecified)
        choices.view.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), unspecified)
        val height = dp(geometry.titleTopInsetDp) + title.measuredHeight + dp(geometry.bodyGapDp) + choices.view.measuredHeight
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val inset = dp(geometry.sideInsetDp)
        val titleTop = dp(geometry.titleTopInsetDp)
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - inset - title.measuredWidth else inset
        title.layout(x, titleTop, x + title.measuredWidth, titleTop + title.measuredHeight)
        val groupTop = titleTop + title.measuredHeight + dp(geometry.bodyGapDp)
        choices.view.layout(inset, groupTop, width - inset, groupTop + choices.view.measuredHeight)
    }

    private fun groupedRow(divided: Boolean) = NativeGroupLayout.LayoutParams(-1, -2).apply { dividerTop = divided }
    private fun dp(value: Int) = (value * theme.density.density).roundToInt()
}
