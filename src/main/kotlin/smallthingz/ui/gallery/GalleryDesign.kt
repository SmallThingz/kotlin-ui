package smallthingz.ui.gallery

import android.content.Context
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeTextStyle
import smallthingz.ui.components.nativeTextStyle
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.text.NativeTypographyRole

/** Gallery uses the shared appearance; product marks and content remain Gallery-owned. */
object GalleryDesign {
    val appearance = ReverbDesign.appearance

    fun palette(context: Context, dark: Boolean): NativeColorScheme =
        appearance.resolveColorScheme(context, dark)

    private val textStyles = NativeTypographyRole.entries.map { appearance.typography.nativeTextStyle(it) }

    /** Framework text adapters retain the shared role's SP units, leading and font settings. */
    fun textStyle(role: NativeTypographyRole): NativeTextStyle = textStyles[role.ordinal]
}
