package smallthingz.ui.components

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
            colors.copy(background = pageColor(colors.background), surface = pageColor(colors.surface),
                surfacePreset = lightGrain)
        }

    // Preserve resource alpha exactly; only the light page RGB is lowered by four percent.
    private fun pageColor(color: Int): Int {
        fun channel(shift: Int): Int = (((color ushr shift) and 255) * .96f).toInt()
        return (color and 0xff000000.toInt()) or (channel(16) shl 16) or
            (channel(8) shl 8) or channel(0)
    }
}
