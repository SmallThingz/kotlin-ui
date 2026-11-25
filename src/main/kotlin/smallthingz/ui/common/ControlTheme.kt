package smallthingz.ui.common

import android.content.Context
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

/** Window configuration for semantic common controls. Recreate it on a configuration change. */
class ControlTheme internal constructor(
    internal val appearance: NativeUiAppearance,
    internal val colors: NativeColorScheme,
    internal val density: Density,
    internal val fontResolver: FontFamily.Resolver,
) {
    /** Recolor retained controls while keeping their window's typography and density. */
    fun withColorScheme(colors: NativeColorScheme): ControlTheme =
        if (colors == this.colors) this else ControlTheme(appearance, colors, density, fontResolver)

    companion object {
        fun resolve(context: Context, dark: Boolean): ControlTheme =
            resolve(context, dark, NativeUiAppearance.Default)

        /** Resolve a caller-supplied appearance while retaining the shared default entry point. */
        fun resolve(context: Context, dark: Boolean, appearance: NativeUiAppearance): ControlTheme {
            return ControlTheme(appearance, appearance.resolveColorScheme(context, dark),
                Density(context), createFontFamilyResolver(context))
        }
    }
}
