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
import smallthingz.ui.text.NativeTypographyRole

/**
 * ZFM's window-scoped adapter to the common recipes, with no independent palette policy.
 * Resolve again after the saved app mode, configuration, font scale or system palette changes.
 * The caller supplies its saved mode; this adapter never substitutes the host's night mode.
 */
class ZfmTheme private constructor(private val theme: ControlTheme) {
    val appearance: NativeUiAppearance get() = theme.appearance
    val colors: NativeColorScheme get() = theme.colors
    val palette: ControlPalette get() = colors.pageControls
    val controlTheme: ControlTheme get() = theme
    val density get() = theme.density
    val fontResolver get() = theme.fontResolver
    val typography get() = appearance.typography
    val fileGrid get() = ZfmFileGridRecipe.Default
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
    val modalSurface get() = appearance.modals.sheet.surfaceColor(colors)

    val textButtonStyle get() = buttons.text.surface
    val buttonTypography get() = buttons.text.labelStyle

    fun nativeTextStyle(role: NativeTypographyRole) = typography.nativeTextStyle(role)

    /** Use the backdrop recipes for pages/windows so their grain intensity is preserved. */
    fun pageBackground() = appearance.backdrops.pageBackground(colors)
    fun windowBackground() = appearance.backdrops.windowBackground(colors)

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
    ): NativeMaterialButtonView = button(context, label, filled, enabled, recipe, accent, onClick)

    /** Explicit foreground variant; the required argument preserves the legacy factory's defaults. */
    fun button(
        context: Context,
        label: String,
        filled: Boolean = false,
        enabled: Boolean = true,
        recipe: NativeButtonRecipe = if (filled) buttons.filled else buttons.text,
        foreground: Int,
        onClick: () -> Unit,
    ): NativeMaterialButtonView = AppButton(
        context, this, filled, recipe, ButtonForeground.resolve(foreground, this), onClick,
    ).also { bindButton(it, label, enabled, filled, foreground) }

    /**
     * The button must have been constructed with a NativeButtonRecipe. Omitted overrides retain
     * a ZFM button's filled identity, construction-time semantic foreground and current callback.
     * A foreground override affects only this bind; omitting it later resolves the construction
     * role against this theme again. An explicit callback replaces the retained callback.
     */
    fun bindButton(button: NativeMaterialButtonView, label: String = button.label.getText(),
                   enabled: Boolean = button.isEnabled,
                   filled: Boolean? = null, foreground: Int? = null,
                   onClick: (() -> Unit)? = null) {
        val filledAction = filled ?: (button as? AppButton)?.filledAction ?: false
        val foregroundColor = foreground ?: (button as? AppButton)?.defaultForeground?.color(this) ?: accent
        // Geometry and filled/text identity belong to the retained construction recipe.
        require(button !is AppButton || button.filledAction == filledAction) {
            "A retained button must keep its construction recipe"
        }
        val colors = if (filledAction || foregroundColor == accent) buttonPalette
            else buttonPalette.copy(accent = foregroundColor)
        button.bindTypography(density, fontResolver)
        if (onClick == null) button.bind(label, enabled, colors)
        else button.bind(label, enabled, colors, onClick = onClick)
    }

    companion object {
        fun resolve(context: Context, dark: Boolean): ZfmTheme =
            ZfmTheme(ControlTheme.resolve(context, dark))

        fun resolve(context: Context, dark: Boolean, appearance: NativeUiAppearance): ZfmTheme =
            ZfmTheme(ControlTheme.resolve(context, dark, appearance))
    }
}

/** Keep semantic foregrounds across palette changes without repurposing the caller's view tags. */
private class ButtonForeground private constructor(private val role: Role, private val explicit: Int) {
    private enum class Role { ACCENT, CONTENT, ERROR, EXPLICIT }

    fun color(theme: ZfmTheme): Int = when (role) {
        Role.ACCENT -> theme.accent
        Role.CONTENT -> theme.ink
        Role.ERROR -> theme.error
        Role.EXPLICIT -> explicit
    }

    companion object {
        fun resolve(color: Int, theme: ZfmTheme) = ButtonForeground(when (color) {
            theme.accent -> Role.ACCENT
            theme.ink -> Role.CONTENT
            theme.error -> Role.ERROR
            else -> Role.EXPLICIT
        }, color)
    }
}

private class AppButton(
    context: Context,
    theme: ZfmTheme,
    val filledAction: Boolean,
    recipe: NativeButtonRecipe,
    val defaultForeground: ButtonForeground,
    onClick: () -> Unit,
) : NativeMaterialButtonView(context, recipe, theme.density, theme.fontResolver, onClick = onClick)
