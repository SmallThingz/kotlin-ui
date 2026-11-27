package smallthingz.ui.components

import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeMenuColors
import smallthingz.ui.components.NativeToggleRow
import smallthingz.ui.components.NativeCircularProgress
import smallthingz.ui.components.NativeLinearProgressView
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeSurface
import smallthingz.ui.components.NativeSurfaceStyle
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.controls.textSurfaceForeground
import smallthingz.ui.graphics.NoiseDrawable

/**
 * Shared paint bindings for retained foreground controls. The window palette owns grain;
 * document/page texture is independent. Binding retains existing views and drawing owners.
 */
object NativeForeground {
    /** Shared treatment for an independent rounded floating surface. */
    fun floatingSurfaceStyle(palette: NativeColorScheme,
                             appearance: NativeUiAppearance = NativeUiAppearance.Default): NativeSurfaceStyle {
        val recipe = appearance.modals.floating
        return NativeSurfaceStyle(fillColor = recipe.surfaceColor(palette),
            cornerRadiusDp = recipe.cornerRadiusDp, elevationDp = recipe.shadowDp,
            outlineColor = palette.border, outlineWidthDp = palette.modalEdgeWidthDp())
    }

    /** Progress geometry, values and colors remain with the existing progress owner. */
    fun bindProgress(view: NativeCircularProgress, palette: ControlPalette) {
        view.bindSurfacePreset(palette.surfacePreset)
    }

    fun bindProgress(view: NativeLinearProgressView, palette: ControlPalette) {
        view.bindSurfacePreset(palette.surfacePreset)
    }

    /** Row state/colors remain owned by bindToggle; this forwards the same window surface preset. */
    fun bindToggleRow(view: NativeToggleRow, palette: ControlPalette) {
        view.bindSurfacePreset(palette.surfacePreset)
    }

    /** Rebind grain without changing a group's independently owned animated fill or border. */
    fun bindGroup(view: NativeGroupLayout, palette: ControlPalette) {
        view.setSurfacePreset(palette.surfacePreset)
    }

    fun bindGroup(view: NativeGroupLayout, field: Int, outline: Int, palette: ControlPalette) {
        bindGroup(view, palette)
        view.setPalette(field, outline)
    }

    internal fun menuColors(palette: ControlPalette): NativeMenuColors = NativeMenuColors(
        palette.raised, palette.raised, palette.content, palette.muted, palette.accent,
        palette.surfacePreset?.foregroundGrain ?: 0f,
    )

    fun foregroundDrawable(fill: Int, palette: ControlPalette): NoiseDrawable =
        foregroundBackground(palette, fill)

    /** Rectangular app foregrounds without a content host can retain this shared drawable. */
    fun foregroundBackground(palette: ControlPalette, color: Int = palette.raised): NoiseDrawable =
        NoiseDrawable(color, NoiseDrawable.SEED_BACKGROUND,
            (palette.surfacePreset?.foregroundGrain ?: 0f) * (color ushr 24) / 255f)

    fun bindForegroundBackground(drawable: NoiseDrawable, palette: ControlPalette, color: Int = palette.raised) {
        drawable.setBackgroundColor(color)
        // NoiseDrawable also supports grain-only backdrops, so foreground fill coverage is ours
        // to forward, just as NativeSurface does while its fill color animates.
        drawable.setNoiseIntensity((palette.surfacePreset?.foregroundGrain ?: 0f) * (color ushr 24) / 255f)
    }

    /** Preserve the common surface's clipping, state paint and color motion. */
    fun bindSurface(view: NativeSurface, palette: ControlPalette, style: NativeSurfaceStyle) {
        view.setSurfacePreset(palette.surfacePreset)
        view.applyStyle(style)
    }

    /**
     * Bind retained label ink to this surface's current paint, cancelling independent ink motion.
     * Call on the UI thread from the surface paint callback and explicitly after palette rebinds.
     * The caller owns the latest palette; this one-shot binding installs no listener or clock.
     */
    fun bindSurfaceLabel(label: NativeText, surface: NativeSurface, palette: ControlPalette) {
        label.setTextColor(textSurfaceForeground(surface.paintedTextSurface, palette.content))
    }

    /** Adapt existing reader commands without replacing their input or accessibility ownership. */
    fun bindButton(view: NativeMaterialButtonView, palette: ControlPalette, text: String,
                   enabled: Boolean, container: Int, contentColor: Int) {
        view.setSurfacePreset(palette.surfacePreset)
        view.bind(text, enabled, container, contentColor)
    }
}
