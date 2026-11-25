package smallthingz.ui.zfm

import android.content.Context
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.components.NativeButtonRecipe
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeSurface
import smallthingz.ui.components.NativeSurfaceStyle
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.components.nativeTextStyle
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbTheme
import smallthingz.ui.text.NativeTypographyRole

/**
 * ZFM's window-scoped adapter to Reverb's current recipes, with no independent palette policy.
 * Resolve again after the saved app mode, configuration, font scale or system palette changes.
 * The caller supplies its saved mode; this adapter never substitutes the host's night mode.
 */
class ZfmTheme private constructor(private val theme: ReverbTheme) {
    val appearance: NativeUiAppearance get() = ReverbDesign.appearance
    val colors: NativeColorScheme get() = theme.palette
    val palette: ControlPalette get() = colors.pageControls
    val controlTheme: ControlTheme get() = theme.controlTheme
    val density get() = theme.density
    val fontResolver get() = theme.fontResolver
    val typography get() = appearance.typography
    val buttons get() = appearance.buttons
    val groupSurfaces get() = appearance.groupSurfaces
    val buttonPalette get() = colors.buttonPalette
    val switchPalette get() = colors.switchPalette

    val surface get() = colors.surface
    val field get() = colors.field
    val raised get() = colors.raised
    val ink get() = colors.ink
    val muted get() = colors.muted
    val accent get() = colors.primary
    val onAccent get() = colors.onPrimary
    val selection get() = palette.selection
    val outline get() = palette.outline
    val error get() = colors.error
    val modalSurface get() = ReverbDesign.modalSurface(colors)

    val textButtonStyle get() = buttons.text.surface
    val buttonTypography get() = buttons.text.labelStyle

    fun nativeTextStyle(role: NativeTypographyRole) = typography.nativeTextStyle(role)

    /** Use the backdrop recipes for pages/windows so their grain intensity is preserved. */
    fun pageBackground() = appearance.backdrops.pageBackground(colors)
    fun windowBackground() = ReverbDesign.windowBackdrop(colors)

    /** Existing NativeSurface cards can delegate to the shared choices-group geometry. */
    fun cardStyle(
        radiusDp: Float = groupSurfaces.settingsChoices.cornerRadiusDp,
        elevated: Boolean = false,
    ): NativeSurfaceStyle {
        val recipe = groupSurfaces.settingsChoices
        return NativeSurfaceStyle(
            fillColor = if (elevated) raised else recipe.fillColor(colors),
            cornerRadiusDp = radiusDp,
            outlineColor = recipe.borderColor(colors),
        )
    }

    /** Apply through this binding to retain the shared foreground preset and color motion. */
    fun bindCard(view: NativeSurface, style: NativeSurfaceStyle = cardStyle()) {
        view.setSurfacePreset(colors.surfacePreset)
        view.applyStyle(style)
    }

    /** Recipe construction also retains shared touch targets, state paint and button motion. */
    fun button(
        context: Context,
        label: String,
        filled: Boolean = false,
        enabled: Boolean = true,
        recipe: NativeButtonRecipe = if (filled) buttons.filled else buttons.text,
        onClick: () -> Unit,
    ): NativeMaterialButtonView = NativeMaterialButtonView(
        context, recipe, density, fontResolver, onClick = onClick,
    ).also { bindButton(it, label, enabled) }

    /** The button must have been constructed with a NativeButtonRecipe. */
    fun bindButton(button: NativeMaterialButtonView, label: String = button.label.getText(),
                   enabled: Boolean = button.isEnabled) {
        button.bind(label, enabled, buttonPalette)
    }

    companion object {
        fun resolve(context: Context, dark: Boolean): ZfmTheme =
            ZfmTheme(ReverbDesign.theme(context, dark))
    }
}
