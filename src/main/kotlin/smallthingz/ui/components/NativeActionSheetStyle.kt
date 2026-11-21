package smallthingz.ui.components

import android.content.Context
import android.view.View
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import smallthingz.ui.unit.Density

/** Explicit immutable colors; no application palette or global theme registry is retained. */
data class NativeActionSheetColors(
    val surfaceContainerLow: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val scrim: Int,
    val inverseSurface: Int,
    val inverseOnSurface: Int,
    val title: Int,
)

/** Typography and colors for one window configuration. Recreate the owner for density changes. */
data class NativeActionSheetStyle(
    val textDensity: Density,
    val sharedFontResolver: FontFamily.Resolver,
    val palette: NativeActionSheetColors,
    val titleStyle: TextStyle,
    val tooltipStyle: TextStyle,
    val recipe: NativeActionSheetRecipe = NativeActionSheetRecipe(grainSeed = null),
) {
    internal fun title(context: Context, text: String) = paragraph(context,text,titleStyle,palette.title)
    internal fun tooltip(context: Context, text: String) = paragraph(context,text,tooltipStyle,palette.inverseOnSurface)
    private fun paragraph(context: Context,text: String,style: TextStyle,color: Int) =
        ParagraphView(context,style,textDensity,sharedFontResolver,color).also { it.setText(text) }
    internal fun bindTitle(view: ParagraphView) = bind(view,titleStyle,palette.title)
    internal fun bindTooltip(view: ParagraphView) = bind(view,tooltipStyle,palette.inverseOnSurface)
    private fun bind(view: ParagraphView,style: TextStyle,color: Int) {
        view.setTypography(style,textDensity,sharedFontResolver)
        view.animateTextColor(color)
    }
}

/** Caller-owned glyph drawing/semantics; the shared action owns input, ripple and merged role. */
class NativeActionSheetIcon(
    val view: View,
    val bindAccessibility: (PlainTextAccessibilityWindowScope, (View) -> NativeSourceAccessibilityNode) -> AutoCloseable,
    val setColor: (Int) -> Unit,
)

internal fun sheetColorWithAlpha(color: Int,alpha: Float): Int =
    (color and 0x00ffffff) or ((alpha.coerceIn(0f,1f)*255f+.5f).toInt() shl 24)
