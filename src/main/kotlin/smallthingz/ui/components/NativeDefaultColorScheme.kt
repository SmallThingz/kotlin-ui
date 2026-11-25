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
    /** Default neutral outline width for the shared surface controls. */
    @field:JvmSynthetic
    internal const val THIN_OUTLINE_WIDTH_DP = 1f

    private val darkColors = NativeDarkSurfacePreset.Amoled.colorScheme()
    private val lightGrain = with(NativeDarkSurfacePreset.Amoled) {
        NativeGrainPreset(backgroundGrain, foregroundGrain)
    }

    override fun resolve(darkTheme: Boolean, sdkInt: Int, readColor: (Int) -> Int): NativeColorScheme =
        if (darkTheme) darkColors.resolve(true, sdkInt, readColor)
        else NativeColorScheme.resolve(false, sdkInt, readColor).let { colors ->
            val backdrop = readColor(R.color.native_default_light_background)
            colors.copy(background = backdrop, surface = backdrop, surfacePreset = lightGrain)
        }
}
