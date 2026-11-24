package app.smallthingz.themedemo

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import smallthingz.ui.components.NativeChoice
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeDarkSurfacePreset
import smallthingz.ui.components.NativeDarkSurfaces
import smallthingz.ui.components.NativeSegmentedControl
import smallthingz.ui.components.NativeSlider
import smallthingz.ui.components.NativeSliderStyle
import smallthingz.ui.components.NativeSurface
import smallthingz.ui.components.NativeSurfaceStyle
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.components.withDarkSurfaces
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import kotlin.math.roundToInt

private val amoledPreset = NativeDarkSurfacePreset.Amoled
private fun Float.percent() = (this * 100f).roundToInt()

class MainActivity : Activity() {
    private enum class Adjustment(val caption: String, val key: String, val initial: Int,
        val maximum: Int = 100, val amoledInitial: Int? = null) {
        TINT("Tint strength", "strength", 45),
        BACKGROUND_LIGHTNESS("Background lightness", "backgroundLightness", 32, amoledInitial = amoledPreset.backgroundLightness.percent()),
        FOREGROUND_LIGHTNESS("Foreground lightness", "foregroundLightness", 75, amoledInitial = amoledPreset.foregroundLightness.percent()),
        BACKGROUND("Background grain", "backgroundGrain", amoledPreset.backgroundGrain.percent()),
        CARDS("Foreground grain", "cardGrain", amoledPreset.foregroundGrain.percent()),
        BORDER_WIDTH("Border width", "borderWidth", (amoledPreset.borderWidthDp * 4f).roundToInt(), maximum = 16),
        BORDER_LIGHTNESS("Border lightness", "borderLightness", amoledPreset.borderLightness.percent()),
    }

    private data class AdjustmentViews(val label: ParagraphView, val slider: NativeSlider)

    private val preferences by lazy { getPreferences(MODE_PRIVATE) }
    private var mode = amoledPreset.style
    private val amounts by lazy {
        Adjustment.entries.map(::readAmount).toIntArray()
    }
    private val controls = mutableMapOf<Adjustment, AdjustmentViews>()
    private val titles = mutableListOf<ParagraphView>()
    private val appearance = NativeUiAppearance.Default
    private lateinit var baseColors: NativeColorScheme
    private lateinit var activeColors: NativeColorScheme
    private val options = listOf(NativeChoice("TINTED", "Tinted"), NativeChoice("AMOLED", "AMOLED"))
    private val density by lazy { Density(this) }
    private val fonts by lazy { createFontFamilyResolver(this) }
    private lateinit var backdrop: NoiseDrawable
    private lateinit var modes: NativeSegmentedControl
    private lateinit var gallery: ThemeGallery
    private lateinit var tintControls: LinearLayout
    private lateinit var swatches: List<NativeSurface>
    private lateinit var swatchValues: List<ParagraphView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = when (preferences.getString("mode", null)) {
            NativeDarkSurfaces.TINTED.name -> NativeDarkSurfaces.TINTED
            else -> amoledPreset.style
        }
        baseColors = appearance.resolveColorScheme(this, darkTheme = true)
        val colors = palette()
        val root = FrameLayout(this).apply {
            isForceDarkAllowed = false
            isFocusableInTouchMode = true
            setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
        backdrop = appearance.backdrops.pageBackground(colors)
        root.background = backdrop
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(20))
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(page, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        page.addView(label("Theme preview", NativeTypographyRole.DISPLAY_SMALL).apply { isAccessibilityHeading = true })
        modes = NativeSegmentedControl(this, colors.settingsControls).apply {
            onSelectionRequest = { id ->
                val next = NativeDarkSurfaces.valueOf(id)
                if (next != mode) {
                    mode = next
                    preferences.edit().putString("mode", id).apply()
                    Adjustment.entries.filter { it.amoledInitial != null }.forEach { setting ->
                        val value = readAmount(setting)
                        amounts[setting.ordinal] = value
                        controls.getValue(setting).slider.progress = value
                    }
                    bindTheme()
                }
                true
            }
        }
        page.addView(modes, row(14))
        gallery = ThemeGallery(this, root, appearance, density, fonts, colors, borders())
        page.addView(gallery.view, row(22))

        page.addView(label("Surface settings", NativeTypographyRole.TITLE_LARGE).apply {
            isAccessibilityHeading = true
        }, row(26))
        tintControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addAdjustment(this, Adjustment.TINT, colors, top = 0)
        }
        page.addView(tintControls, row(20))
        addAdjustment(page, Adjustment.BACKGROUND_LIGHTNESS, colors, top = 20)
        addAdjustment(page, Adjustment.FOREGROUND_LIGHTNESS, colors, top = 10)
        addAdjustment(page, Adjustment.BACKGROUND, colors, top = 10)
        addAdjustment(page, Adjustment.CARDS, colors, top = 10)
        addAdjustment(page, Adjustment.BORDER_WIDTH, colors, top = 10)
        addAdjustment(page, Adjustment.BORDER_LIGHTNESS, colors, top = 10)

        page.addView(label("Surface preview", NativeTypographyRole.TITLE_MEDIUM).apply { isAccessibilityHeading = true }, row(20))
        val strip = LinearLayout(this)
        val values = mutableListOf<ParagraphView>()
        swatches = listOf("Flat", "Page", "Card").mapIndexed { index, name ->
            val value = label("", NativeTypographyRole.LABEL_MEDIUM)
            values += value
            NativeSurface(this, swatchStyle(colors.surface, borders(), noise = index != 0)).apply {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(9), dp(25), dp(9), dp(11))
                    addView(label(name, NativeTypographyRole.TITLE_SMALL))
                    addView(value, row(3))
                }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
                strip.addView(this, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(7)
                })
            }
        }
        swatchValues = values
        page.addView(strip, row(8))
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        setContentView(root)
        root.requestFocus()
        root.requestApplyInsets()
        bindTheme()
    }

    private fun addAdjustment(parent: LinearLayout, setting: Adjustment, colors: NativeColorScheme, top: Int) {
        val caption = label("", NativeTypographyRole.TITLE_MEDIUM)
        val slider = NativeSlider(this, colors.pageControls, NativeSliderStyle(trackHeightDp = 10f, thumbHeightDp = 28f)).apply {
            contentDescription = setting.caption
            max = setting.maximum
            progress = amount(setting)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    amounts[setting.ordinal] = progress
                    when (setting) {
                        Adjustment.TINT, Adjustment.BACKGROUND_LIGHTNESS, Adjustment.FOREGROUND_LIGHTNESS -> bindTheme()
                        Adjustment.BORDER_WIDTH, Adjustment.BORDER_LIGHTNESS -> bindBorders()
                        Adjustment.BACKGROUND, Adjustment.CARDS -> bindGrain()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
            onValueCommitted = { preferences.edit().putInt(keyFor(setting), it).apply() }
        }
        controls[setting] = AdjustmentViews(caption, slider)
        parent.addView(caption, row(top))
        parent.addView(slider, row(2))
    }

    private fun amount(setting: Adjustment) = amounts[setting.ordinal]

    private fun keyFor(setting: Adjustment) = if (setting.amoledInitial == null) setting.key else "${setting.key}.${mode.name}"

    private fun readAmount(setting: Adjustment): Int {
        val initial = if (mode == NativeDarkSurfaces.AMOLED) setting.amoledInitial ?: setting.initial else setting.initial
        return preferences.getInt(keyFor(setting), initial).coerceIn(0, setting.maximum)
    }

    private fun palette() = baseColors.withDarkSurfaces(mode,
        tintStrength = amount(Adjustment.TINT) / 100f,
        backgroundLightness = amount(Adjustment.BACKGROUND_LIGHTNESS) / 100f,
        foregroundLightness = amount(Adjustment.FOREGROUND_LIGHTNESS) / 100f)

    private fun bindTheme() {
        val colors = palette()
        if (!::activeColors.isInitialized || activeColors.surface != colors.surface) {
            backdrop.setBackgroundColor(colors.surface)
            window.setBackgroundDrawable(ColorDrawable(colors.surface))
        }
        activeColors = colors
        gallery.bindPalette(colors)
        titles.forEach { it.setTextColor(colors.ink) }
        tintControls.visibility = if (mode == NativeDarkSurfaces.TINTED) View.VISIBLE else View.GONE
        controls.values.forEach { it.slider.applyPalette(colors.pageControls) }
        bindCaption(Adjustment.TINT)
        bindCaption(Adjustment.BACKGROUND_LIGHTNESS)
        bindCaption(Adjustment.FOREGROUND_LIGHTNESS)
        swatchValues.forEach { it.setTextColor(colors.muted) }
        bindBorders()
        bindGrain()
    }

    private fun borders(): DemoBorders {
        val shade = (amount(Adjustment.BORDER_LIGHTNESS) * 255 + 50) / 100
        return DemoBorders(amount(Adjustment.BORDER_WIDTH) / 4f, Color.rgb(shade, shade, shade))
    }

    private fun bindBorders() {
        val borders = borders()
        modes.bind(options, mode.name, activeColors.settingsControls.copy(outline = borders.color))
        modes.setOutlineWidth(borders.widthDp)
        gallery.setBorders(borders)
        swatches.forEachIndexed { index, view ->
            val color = if (index == 2) activeColors.surfaceContainerHigh else activeColors.surface
            view.applyStyle(swatchStyle(color, borders, noise = index != 0))
        }
        bindCaption(Adjustment.BORDER_WIDTH)
        bindCaption(Adjustment.BORDER_LIGHTNESS)
    }

    private fun bindGrain() {
        val background = amount(Adjustment.BACKGROUND)
        val cards = amount(Adjustment.CARDS)
        backdrop.setNoiseIntensity(background / 100f)
        gallery.setForegroundGrain(cards / 100f)
        modes.setNoiseIntensity(cards / 100f)
        swatches[1].setNoiseIntensity(background / 100f)
        swatches[2].setNoiseIntensity(cards / 100f)
        swatchValues[0].setText("0% grain")
        swatchValues[1].setText("$background% grain")
        swatchValues[2].setText("$cards% grain")
        bindCaption(Adjustment.BACKGROUND)
        bindCaption(Adjustment.CARDS)
    }

    private fun bindCaption(setting: Adjustment) {
        val amount = amount(setting)
        val value = if (setting == Adjustment.BORDER_WIDTH) {
            if (amount == 0) "Off" else "${(amount / 4f).toString().removeSuffix(".0")} dp"
        } else "$amount%"
        controls.getValue(setting).label.setText("${setting.caption} · $value")
    }

    private fun swatchStyle(color: Int, borders: DemoBorders, noise: Boolean) = NativeSurfaceStyle(color,
        cornerRadiusDp = 15f, outlineColor = borders.color, outlineWidthDp = borders.widthDp, noise = noise)

    private fun label(text: String, role: NativeTypographyRole) =
        ParagraphView(this, appearance.typography[role], density, fonts, baseColors.ink).apply {
            setText(text)
            titles += this
        }

    private fun row(top: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * density.density).roundToInt()

    override fun onResume() {
        super.onResume()
        val current = appearance.resolveColorScheme(this, darkTheme = true)
        if (baseColors != current) {
            baseColors = current
            bindTheme()
        }
    }

    override fun onStop() {
        gallery.dismissPopup()
        super.onStop()
    }

    override fun onDestroy() {
        modes.close()
        gallery.close()
        super.onDestroy()
    }
}
