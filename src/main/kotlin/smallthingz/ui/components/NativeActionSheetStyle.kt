package smallthingz.ui.components

import android.content.Context
import android.view.View
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import smallthingz.ui.unit.Density

/** Immutable paint resolved from the sheet's window theme. */
@ConsistentCopyVisibility
data class NativeActionSheetColors internal constructor(
    /** The resolved sheet fill, under the original property name. */
    val surfaceContainerLow: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val scrim: Int,
    val inverseSurface: Int,
    val inverseOnSurface: Int,
    val title: Int,
    val edgeColor: Int,
    val edgeWidthDp: Float,
) {
    internal constructor(
        surfaceContainerLow: Int,
        onSurface: Int,
        onSurfaceVariant: Int,
        scrim: Int,
        inverseSurface: Int,
        inverseOnSurface: Int,
        title: Int,
    ) : this(surfaceContainerLow, onSurface, onSurfaceVariant, scrim, inverseSurface,
        inverseOnSurface, title, 0, NativeDefaultColorScheme.THIN_OUTLINE_WIDTH_DP)

    @Deprecated("Retained for binary compatibility", level = DeprecationLevel.HIDDEN)
    fun copy(
        surfaceContainerLow: Int = this.surfaceContainerLow,
        onSurface: Int = this.onSurface,
        onSurfaceVariant: Int = this.onSurfaceVariant,
        scrim: Int = this.scrim,
        inverseSurface: Int = this.inverseSurface,
        inverseOnSurface: Int = this.inverseOnSurface,
        title: Int = this.title,
    ): NativeActionSheetColors = NativeActionSheetColors(surfaceContainerLow, onSurface, onSurfaceVariant,
        scrim, inverseSurface, inverseOnSurface, title, edgeColor, edgeWidthDp)
}

/** Use [NativeModalRecipes.actionSheetStyle] to resolve paint and typography for a window. */
@ConsistentCopyVisibility
data class NativeActionSheetStyle internal constructor(
    val textDensity: Density,
    val sharedFontResolver: FontFamily.Resolver,
    val palette: NativeActionSheetColors,
    val titleStyle: TextStyle,
    val tooltipStyle: TextStyle,
    val recipe: NativeActionSheetRecipe = NativeActionSheetRecipe(grainSeed = null),
) {
    /** Change sheet geometry and behavior while retaining the resolved window paint. */
    fun copy(recipe: NativeActionSheetRecipe): NativeActionSheetStyle =
        NativeActionSheetStyle(textDensity, sharedFontResolver, palette, titleStyle, tooltipStyle,
            recipe.copy(grainSeed = this.recipe.grainSeed, grainIntensity = this.recipe.grainIntensity))

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
