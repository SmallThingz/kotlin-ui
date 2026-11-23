package smallthingz.ui.reverb

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeMenuColors
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeMenuIcons

internal fun NativeColorScheme.menuColors() = NativeMenuColors(raised, surfaceContainerHigh, ink, muted, primary)
internal object ReverbMenuIcons : NativeMenuIcons<ReverbIcon> {
    override val check = ReverbIcon.Check
    override fun create(context: Context, icon: ReverbIcon, color: Int): View = ReverbGlyphView(context, icon, color)
    override fun bind(view: View, icon: ReverbIcon, color: Int) = (view as ReverbGlyphView).bind(icon, color)
}
