package smallthingz.ui.alldoc

import smallthingz.ui.components.*
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.graphics.NoiseDrawable

/** Compatibility facade; the common foreground policy and renderers own all paint bindings. */
object AllDocAppearance {
    fun floatingSurfaceStyle(palette: NativeColorScheme): NativeSurfaceStyle =
        NativeForeground.floatingSurfaceStyle(palette)
    fun bindProgress(view: NativeCircularProgress, palette: ControlPalette) = NativeForeground.bindProgress(view, palette)
    fun bindProgress(view: NativeLinearProgressView, palette: ControlPalette) = NativeForeground.bindProgress(view, palette)
    fun bindToggleRow(view: NativeToggleRow, palette: ControlPalette) = NativeForeground.bindToggleRow(view, palette)
    fun bindGroup(view: NativeGroupLayout, palette: ControlPalette) = NativeForeground.bindGroup(view, palette)
    fun bindGroup(view: NativeGroupLayout, field: Int, outline: Int, palette: ControlPalette) =
        NativeForeground.bindGroup(view, field, outline, palette)
    fun menuColors(palette: ControlPalette): NativeMenuColors = NativeForeground.menuColors(palette)
    fun foregroundDrawable(fill: Int, palette: ControlPalette): NoiseDrawable =
        NativeForeground.foregroundDrawable(fill, palette)
    fun foregroundBackground(palette: ControlPalette, color: Int = palette.raised): NoiseDrawable =
        NativeForeground.foregroundBackground(palette, color)
    fun bindForegroundBackground(drawable: NoiseDrawable, palette: ControlPalette, color: Int = palette.raised) =
        NativeForeground.bindForegroundBackground(drawable, palette, color)
    fun bindSurface(view: NativeSurface, palette: ControlPalette, style: NativeSurfaceStyle) =
        NativeForeground.bindSurface(view, palette, style)
    fun bindSurfaceLabel(label: NativeText, surface: NativeSurface, palette: ControlPalette) =
        NativeForeground.bindSurfaceLabel(label, surface, palette)
    fun bindButton(view: NativeMaterialButtonView, palette: ControlPalette, text: String,
                   enabled: Boolean, container: Int, contentColor: Int) =
        NativeForeground.bindButton(view, palette, text, enabled, container, contentColor)
}
