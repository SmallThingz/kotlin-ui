package smallthingz.ui.components

import android.content.Context
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density

/** Window typography and palette with its existing painted-surface contrast binding. */
interface NativeSettingsVisualEnvironment {
    val textDensity: Density
    val sharedFontResolver: FontFamily.Resolver
    val palette: NativeColorScheme
    val appearance: NativeUiAppearance

    fun paragraph(context: Context, text: String, role: NativeTypographyRole, textColor: Int = palette.ink): ParagraphView
    fun bindParagraphStyle(view: ParagraphView, role: NativeTypographyRole, textColor: Int = palette.ink)
}
