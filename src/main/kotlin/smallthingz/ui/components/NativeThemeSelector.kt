package smallthingz.ui.components

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import smallthingz.ui.R
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.unit.Density
import kotlin.math.roundToInt

/** Presentation only: applications own persisted codes and their system-theme resolution. */
enum class NativeThemeMode(@param:StringRes @field:StringRes val labelRes: Int) {
    SYSTEM(R.string.native_theme_system), LIGHT(R.string.native_theme_light), DARK(R.string.native_theme_dark),
}

/** Explicit custom retained rendering. Localized copy and selector layout remain library-owned. */
interface NativeThemePresentation : NativeSegmentedPresentation {
    val title: View
    fun bindTitle(titleText: String, palette: ControlPalette, titleColor: Int)
}

/** A complete localized theme row. A selection request never changes the owner's bound selection. */
class NativeThemeSelector internal constructor(
    context: Context,
    palette: ControlPalette,
    selected: NativeThemeMode,
    titleColor: Int,
    private val presentation: NativeThemePresentation,
    density: Float,
    inputAllowed: () -> Boolean,
    segmented: NativeSegmentedStyle,
    onSelectionRequest: (NativeThemeMode) -> Boolean,
) : ViewGroup(context), NativeInputGate, AutoCloseable {
    constructor(
        context: Context,
        palette: ControlPalette,
        selected: NativeThemeMode,
        titleColor: Int = palette.content,
        presentation: NativeThemePresentation,
        density: Float = context.resources.displayMetrics.density,
        inputAllowed: () -> Boolean = { true },
        onSelectionRequest: (NativeThemeMode) -> Boolean,
    ) : this(context, palette, selected, titleColor, presentation, density, inputAllowed,
        NativeSegmentedStyle.Default, onSelectionRequest)

    private var closed = false
    private var onSelectionRequest: ((NativeThemeMode) -> Boolean)? = onSelectionRequest
    private val capsule = NativeSegmentedControl(context, palette, segmented, density, inputAllowed, presentation)
    private val contentLayout = NativeLabeledSegmentedLayout(presentation.title, capsule)
    // The Settings factory owns an outer section gutter; custom presentations keep their layout.
    private val horizontalInsetDp = if (presentation is SettingsThemePresentation)
        DEFAULT_SETTINGS_SECTION_HORIZONTAL_INSET_DP else 0
    private var palette = palette
    private var titleColor = titleColor
    private var selected = selected
    private var optionDensity = Float.NaN
    private var optionLocales = ""
    private var options: List<NativeChoice> = emptyList()
    private var density = density

    override val acceptsNativeInput: Boolean
        get() = !closed && isEnabled && capsule.acceptsNativeInput

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(presentation.title)
        addView(capsule)
        capsule.onSelectionRequest = { id ->
            val mode = NativeThemeMode.entries.singleOrNull { it.name == id }
            mode != null && acceptsNativeInput && this.onSelectionRequest?.invoke(mode) == true
        }
        bind(selected, palette, titleColor, density)
    }

    fun bind(selected: NativeThemeMode, palette: ControlPalette, titleColor: Int = palette.content,
             density: Float = resources.displayMetrics.density) {
        if (closed) return
        require(density.isFinite() && density > 0f)
        if (this.density != density) {
            this.density = density
            requestLayout()
        }
        this.selected = selected
        this.palette = palette
        this.titleColor = titleColor
        val locales = resources.configuration.locales.toLanguageTags()
        if (optionDensity != density || optionLocales != locales) {
            optionDensity = density
            optionLocales = locales
            options = NativeThemeMode.entries.map { mode -> NativeChoice(mode.name, context.getString(mode.labelRes),
                NativeThemeIconDrawable(mode, density, palette.muted)) }
        }
        presentation.bindTitle(context.getString(R.string.native_theme_title), palette, titleColor)
        capsule.bind(options, selected.name, palette, density)
    }

    /** Rebind the retained title and choices when the host's font or display configuration changes. */
    fun bind(selected: NativeThemeMode, palette: ControlPalette, textDensity: Density,
             fontResolver: FontFamily.Resolver) {
        if (closed) return
        (presentation as? SettingsThemePresentation)?.bindTypography(textDensity, fontResolver)
        bind(selected, palette, palette.content, textDensity.density)
    }

    internal fun bindEnabled(enabled: Boolean) {
        if (closed) return
        isEnabled = enabled
        capsule.setOptionsEnabled(enabled)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bind(selected, palette, titleColor)
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = if (MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED)
            contentLayout.preferredWidth(dp(16)) + 2 * dp(horizontalInsetDp) else MeasureSpec.getSize(w)
        val available = (width - 2 * horizontalInset(width)).coerceAtLeast(0)
        contentLayout.measure(available, inlineGap = dp(16), stackedGap = dp(8))
        setMeasuredDimension(resolveSize(width, w), resolveSize(2 * dp(12) + contentLayout.measuredHeight + dp(12), h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        contentLayout.layout(width, horizontalInset(width), dp(12), layoutDirection == LAYOUT_DIRECTION_RTL)
    }

    override fun close() {
        if (closed) return
        closed = true
        onSelectionRequest = null
        capsule.close()
    }

    private fun horizontalInset(width: Int) = dp(horizontalInsetDp).coerceAtMost(width.coerceAtLeast(0) / 2)

    private fun dp(value: Int) = (value * density).roundToInt()
}
