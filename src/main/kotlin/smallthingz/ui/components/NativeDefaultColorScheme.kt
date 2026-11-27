package smallthingz.ui.components

import smallthingz.ui.R

/**
 * Shared appearance policy for windows and common controls.
 *
 * Dark pages use the AMOLED preset. Light pages use neutral near-white surfaces and
 * lower grain coverage; these are a shared default policy, not a Material palette change.
 * Accent and content roles still follow the platform Material palette. Resolve again
 * after configuration or system-color changes; only the immutable recipes are shared.
 */
object NativeDefaultColorScheme : NativeColorSchemeResolver {
    /** Default neutral outline width for the shared surface controls. */
    @field:JvmSynthetic
    internal const val THIN_OUTLINE_WIDTH_DP = 1f

    private val darkColors = NativeDarkSurfacePreset.Amoled.colorScheme()
    private val lightGrain = NativeGrainPreset(backgroundGrain = .22f, foregroundGrain = .20f)

    override fun resolve(darkTheme: Boolean, sdkInt: Int, readColor: (Int) -> Int): NativeColorScheme =
        if (darkTheme) darkColors.resolve(true, sdkInt, readColor)
        else NativeColorScheme.resolve(false, sdkInt, readColor).let { colors ->
            val backdrop = readColor(R.color.native_default_light_background)
            val foreground = readColor(R.color.native_default_light_foreground)
            val popup = readColor(R.color.native_default_light_popup)
            colors.copy(
                background = backdrop, surface = backdrop, surfaceDim = backdrop,
                surfaceBright = popup,
                surfaceVariant = foreground,
                surfaceContainerLowest = popup,
                surfaceContainerLow = foreground,
                surfaceContainer = foreground,
                surfaceContainerHigh = foreground,
                surfaceContainerHighest = foreground,
                surfacePreset = lightGrain,
                popupFillOverride = popup,
            )
        }
}
