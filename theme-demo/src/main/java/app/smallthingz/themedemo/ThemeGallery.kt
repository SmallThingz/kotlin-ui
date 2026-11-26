package app.smallthingz.themedemo

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.common.DropdownContent
import smallthingz.ui.common.DropdownGroup
import smallthingz.ui.common.ToggleRow
import smallthingz.ui.common.ToggleState
import smallthingz.ui.common.formatPlaybackTime
import smallthingz.ui.components.NativeButtonRecipe
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeLabeledTextField
import smallthingz.ui.components.NativeLabeledTextFieldStyle
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeSlider
import smallthingz.ui.components.NativeSliderStyle
import smallthingz.ui.components.NativeSurface
import smallthingz.ui.components.NativeSurfaceStyle
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.components.waveform.ProgressiveWaveformData
import smallthingz.ui.components.waveform.ProgressiveWaveformFrame
import smallthingz.ui.components.waveform.ProgressiveWaveformView
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class DemoBorders(val widthDp: Float, val color: Int)

/** The app owns the sample state. The same library views used in other apps paint it. */
internal class ThemeGallery(
    private val context: Context,
    host: FrameLayout,
    private val appearance: NativeUiAppearance,
    private val density: Density,
    private val fonts: FontFamily.Resolver,
    initial: NativeColorScheme,
    initialBorders: DemoBorders,
) : AutoCloseable {
    private var colors = initial
    private var borders = initialBorders
    private val windowTheme = ControlTheme.resolve(context, true)
    private val dropdowns = DropdownGroup(windowTheme.withColorScheme(initial), { host }) { host.hasWindowFocus() }
    private var format = DropdownContent("Export format", "WAV", listOf("WAV", "AAC", "FLAC")) { it }
    private var normalize = true
    private var trimming = false
    private val primaryLabels = mutableListOf<ParagraphView>()
    private val secondaryLabels = mutableListOf<ParagraphView>()
    val view = column()

    private val player = NativeSurface(context, cardStyle(initial.raised))
    private val editorCard = NativeSurface(context, cardStyle(initial.field))
    private val clock = text("", NativeTypographyRole.LABEL_MEDIUM, secondary = true)
    private val waveform = ProgressiveWaveformView(context).apply { setWaveform(sampleWaveform()) }
    private val playback = NativeSlider(context, initial.pageControls, NativeSliderStyle(trackHeightDp = 6f,
        thumbHeightDp = 24f)).apply {
        contentDescription = "Playback position"
        max = 42
        progress = 12
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(view: SeekBar, progress: Int, fromUser: Boolean) { bindTimeline() }
            override fun onStartTrackingTouch(view: SeekBar) = Unit
            override fun onStopTrackingTouch(view: SeekBar) = Unit
        })
    }
    private val trim = button(appearance.buttons.tonal) { trimming = !trimming; bindTimeline() }
    private val reset = button(appearance.buttons.text) {
        trimming = false
        playback.progress = 0
        bindTimeline()
    }
    private val fileName = NativeLabeledTextField(context, initial.settingsControls, "File name",
        fieldStyle(initial)).apply {
        editor.setSingleLine(true)
        editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        editor.setText("Meeting note")
    }
    private val formatField = dropdowns.field(context, format) { value ->
        format = format.copy(selected = value)
        formChanged()
    }
    private val normalizeRow = ToggleRow(context, normalizeState(), windowTheme.withColorScheme(initial)) { checked ->
        normalize = checked
        formChanged()
    }
    private val confirmation = text("", NativeTypographyRole.BODY_SMALL, secondary = true).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }
    private val apply = button(appearance.buttons.filled) {
        val name = fileName.editor.text.toString().trim()
        confirmation.setText("Ready to export $name as ${format.selected}." + if (normalize) " Normalized." else "")
        confirmation.visibility = View.VISIBLE
    }

    init {
        view.addView(heading("Reverb player"))
        val playerContent = cardContent(player)
        playerContent.addView(text("Meeting note", NativeTypographyRole.TITLE_LARGE))
        playerContent.addView(clock, row(top = 2))
        playerContent.addView(waveform, row(top = 12, height = dp(60)))
        playerContent.addView(playback)
        playerContent.addView(actions(trim, reset))
        view.addView(player, row(top = 8))

        view.addView(heading("Export settings"), row(top = 20))
        val settings = cardContent(editorCard)
        settings.addView(fileName)
        settings.addView(formatField.view, row(top = 6))
        settings.addView(normalizeRow.view)
        settings.addView(actions(apply), row(top = 4))
        settings.addView(confirmation, row(top = 6))
        view.addView(editorCard, row(top = 8))
        fileName.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = formChanged()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        bindPalette(initial)
    }

    fun bindPalette(next: NativeColorScheme) {
        colors = next
        bindOutlines()
        waveform.setStyle(next.waveformStyle)
        playback.applyPalette(next.pageControls)
        fileName.applyPalette(next.settingsControls)
        primaryLabels.forEach { it.setTextColor(next.ink) }
        secondaryLabels.forEach { it.setTextColor(next.muted) }
        bindTimeline()
        bindForm()
    }

    fun setBorders(next: DemoBorders) {
        if (borders == next) return
        borders = next
        bindOutlines()
    }

    private fun bindOutlines() {
        player.applyStyle(cardStyle(colors.raised))
        editorCard.applyStyle(cardStyle(colors.field))
        fileName.applyStyle(fieldStyle(colors))
        normalizeRow.setTrackOutline(borders.widthDp, borders.color)
    }

    fun setForegroundGrain(intensity: Float) {
        player.setNoiseIntensity(intensity)
        editorCard.setNoiseIntensity(intensity)
        normalizeRow.setTrackNoiseIntensity(intensity)
    }
    fun dismissPopup() = dropdowns.dismiss()

    private fun bindTimeline() {
        val second = playback.progress
        clock.setText(if (trimming) "Trim preview · 0:12 to 0:30"
            else "${formatPlaybackTime(second * 1000)} / 0:42")
        waveform.setFrame(WAVEFORM_FRAME, if (trimming) 12f / 42f else 0f,
            if (trimming) 30f / 42f else second / 42f)
        trim.bind(if (trimming) "Done" else "Trim", true, colors.buttonPalette)
        reset.bind("Reset", trimming || second > 0, colors.buttonPalette)
    }

    private fun bindForm() {
        val theme = windowTheme.withColorScheme(colors)
        dropdowns.bindTheme(theme)
        formatField.bind(format)
        normalizeRow.bind(normalizeState(), theme)
        apply.bind("Apply", !fileName.editor.text.isNullOrBlank(), colors.buttonPalette)
    }

    private fun formChanged() {
        confirmation.visibility = View.GONE
        bindForm()
    }

    private fun normalizeState() = ToggleState("Normalize audio", normalize, true)
    private fun fieldStyle(colors: NativeColorScheme) = NativeLabeledTextFieldStyle(colors.error,
        minimumWidthDp = 0f, outlineColor = borders.color, outlineWidthDp = borders.widthDp,
        focusedOutlineColor = colors.primary)
    private fun cardStyle(fill: Int) = NativeSurfaceStyle(fill,
        cornerRadiusDp = 20f, outlineColor = borders.color, outlineWidthDp = borders.widthDp, noise = true)

    private fun heading(value: String) = text(value, NativeTypographyRole.TITLE_MEDIUM).apply {
        isAccessibilityHeading = true
    }

    private fun text(value: String, role: NativeTypographyRole, secondary: Boolean = false) =
        ParagraphView(context, appearance.typography[role], density, fonts, if (secondary) colors.muted else colors.ink).apply {
            setText(value)
            (if (secondary) secondaryLabels else primaryLabels).add(this)
        }

    private fun button(recipe: NativeButtonRecipe, action: () -> Unit) =
        NativeMaterialButtonView(context, recipe, density, fonts, onClick = action)

    private fun actions(vararg buttons: View) = LinearLayout(context).apply {
        buttons.forEachIndexed { index, button ->
            addView(button, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                if (index != 0) marginStart = dp(8)
            })
        }
    }

    private fun cardContent(card: NativeSurface) = column().apply {
        setPadding(dp(14), dp(14), dp(14), dp(12))
        card.addView(this, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun row(top: Int, height: Int = WRAP_CONTENT) =
        LinearLayout.LayoutParams(MATCH_PARENT, height).apply { topMargin = dp(top) }
    private fun dp(units: Int) = (units * density.density).roundToInt()

    override fun close() {
        formatField.close()
        dropdowns.close()
        normalizeRow.close()
        trim.close()
        reset.close()
        apply.close()
    }

    private companion object {
        val WAVEFORM_FRAME = ProgressiveWaveformFrame(1f, 1f, 1f, 0f, 0f, 0f, false)

        fun sampleWaveform(): ProgressiveWaveformData {
            val detail = FloatArray(128) { i ->
                val phrase = abs(sin(i * .121)) * (.55 + .45 * abs(sin(i * .043)))
                (.06 + phrase * (.23 + .68 * abs(sin(i * 1.21)))).toFloat()
            }
            val coarse = FloatArray(32) { i -> (0..3).maxOf { detail[i * 4 + it] } }
            return ProgressiveWaveformData(coarse, coarse.size, detail, detail.size, true)
        }
    }
}
