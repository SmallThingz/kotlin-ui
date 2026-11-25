package smallthingz.ui.inject

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeScreenSpacing
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbTheme
import smallthingz.ui.reverb.createReverbActionSheet
import smallthingz.ui.reverb.reverbActionSheetStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.unit.Density

/** Inject consumes Reverb's live shared recipes; it does not maintain a parallel visual preset. */
object InjectDesign {
    val appearance get() = ReverbDesign.appearance
    val spacing get() = NativeScreenSpacing

    fun theme(context: Context, dark: Boolean) = ReverbDesign.theme(context, dark)

    fun windowTheme(density: Density, fontResolver: FontFamily.Resolver, palette: NativeColorScheme) =
        ReverbDesign.windowTheme(density, fontResolver, palette)

    fun actionSheet(anchor: View, theme: ReverbTheme, title: String, onDismiss: () -> Unit) =
        createReverbActionSheet(anchor, theme, title, onDismiss)

    fun actionSheetStyle(theme: ReverbTheme) = reverbActionSheetStyle(theme)

    fun windowBackdrop(palette: NativeColorScheme) = ReverbDesign.windowBackdrop(palette)

    fun statusBarProtection(context: Context, theme: ReverbTheme) =
        ReverbDesign.statusBarProtection(context, theme)
}

typealias InjectTheme = ReverbTheme

typealias InjectAboutDialog = smallthingz.ui.reverb.ReverbAboutDialog
typealias InjectAboutDetails = smallthingz.ui.reverb.ReverbAboutDetails
typealias InjectProductIdentity = smallthingz.ui.reverb.ReverbProductIdentity
typealias InjectProductTopBar = smallthingz.ui.reverb.ReverbProductTopBar
typealias InjectHeaderAction = smallthingz.ui.reverb.ReverbHeaderAction
typealias InjectHeaderCount = smallthingz.ui.reverb.ReverbHeaderCount
