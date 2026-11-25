package smallthingz.ui.components

import smallthingz.ui.icons.MaterialRoundedIcon

/** Compatibility adapter over the recommended Material Rounded 1.7.8 common set. */
class NativeThemeIconDrawable(mode: NativeThemeMode, density: Float, tint: Int) :
    CachedPathIconDrawable({
        when (mode) {
            NativeThemeMode.SYSTEM -> MaterialRoundedIcon.Devices
            NativeThemeMode.LIGHT -> MaterialRoundedIcon.LightMode
            NativeThemeMode.DARK -> MaterialRoundedIcon.DarkMode
        }.mask
    }, density, tint)
