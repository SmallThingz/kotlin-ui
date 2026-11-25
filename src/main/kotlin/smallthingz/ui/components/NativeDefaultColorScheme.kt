package smallthingz.ui.components

import smallthingz.ui.R

/**
 * Shared appearance policy for windows and common controls.
 *
 * Dark pages use the AMOLED preset; both modes carry the same grain intensities.
 * Accent and content roles still follow the platform Material palette. Resolve again
 * after configuration or system-color changes; only the immutable recipes are shared.
 */
object NativeDefaultColorScheme : NativeColorSchemeResolver {
    private val darkColors = NativeDarkSurfacePreset.Amoled.colorScheme()
    private val lightGrain = NativeGrainPreset(backgroundGrain = 1f, foregroundGrain = .5f)

    override fun resolve(darkTheme: Boolean, sdkInt: Int, readColor: (Int) -> Int): NativeColorScheme =
        if (darkTheme) darkColors.resolve(true, sdkInt, readColor)
        else NativeColorScheme.resolve(false, sdkInt, readColor).let { colors ->
            val backdrop = readColor(R.color.native_default_light_background)
            colors.copy(background = backdrop, surface = backdrop, surfacePreset = lightGrain)
        }
}
