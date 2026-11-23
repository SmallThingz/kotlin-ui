package smallthingz.ui.reverb

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeActionSheet
import smallthingz.ui.components.NativeActionSheetStyle
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.view.ParagraphView

/** Reverb's appearance binding; all window/input/state ownership lives in NativeActionSheet. */
fun createReverbActionSheet(
    anchor: View,
    environment: ReverbTheme,
    title: String,
    onDismiss: () -> Unit,
): NativeActionSheet {
    return NativeActionSheet(anchor, reverbActionSheetStyle(environment), title, onDismiss,
        closeIcon = null,
        contentBackground = null,
        statusBarProtection = ReverbDesign.statusBarProtection(anchor.context))
}

fun NativeActionSheet.bindReverbTheme(environment: ReverbTheme) =
    updateStyle(reverbActionSheetStyle(environment))

fun reverbActionSheetStyle(theme: ReverbTheme): NativeActionSheetStyle {
    val palette = theme.palette.copy(surfaceContainerLow = ReverbDesign.modalSurface(theme.palette))
    return ReverbDesign.appearance.modals.actionSheetStyle(theme.density, theme.fontResolver, palette).let {
        it.copy(recipe = it.recipe.copy(handleHeightDp = 2f))
    }
}

internal fun createReverbSupportingParagraph(
    context: Context, text: String, role: NativeTypographyRole, theme: ReverbTheme,
): ParagraphView = createReverbParagraph(context, text, role, theme.density, theme.fontResolver,
    theme.palette.onSurfaceVariant)

internal fun ReverbTheme.bindSupportingParagraph(view: ParagraphView, role: NativeTypographyRole) {
    view.setTypography(ReverbDesign.appearance.typography[role], density, fontResolver)
    view.animateTextColor(palette.onSurfaceVariant)
}
