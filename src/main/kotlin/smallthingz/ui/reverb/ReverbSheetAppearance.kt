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
        statusBarProtection = ReverbDesign.statusBarProtection(anchor.context, environment))
}

fun NativeActionSheet.bindReverbTheme(environment: ReverbTheme) {
    updateStyle(reverbActionSheetStyle(environment))
    updateStatusBarAppearance { ReverbDesign.statusBarAppearance(it, environment) }
}

fun reverbActionSheetStyle(theme: ReverbTheme): NativeActionSheetStyle =
    theme.appearance.actionSheetStyle(theme.density, theme.fontResolver, theme.palette)

internal fun createReverbSupportingParagraph(
    context: Context, text: String, role: NativeTypographyRole, theme: ReverbTheme,
): ParagraphView = ParagraphView(context, theme.appearance.typography[role], theme.density, theme.fontResolver,
    theme.palette.onSurfaceVariant).also { it.setText(text) }

internal fun ReverbTheme.bindSupportingParagraph(view: ParagraphView, role: NativeTypographyRole) {
    view.setTypography(appearance.typography[role], density, fontResolver)
    view.animateTextColor(palette.onSurfaceVariant)
}
