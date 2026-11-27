package smallthingz.ui.reverb

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeAnchoredMenuRecipe
import smallthingz.ui.components.colors
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeMenuIcons

internal fun NativeColorScheme.menuColors() = NativeAnchoredMenuRecipe.Default.colors(this)
internal object ReverbMenuIcons : NativeMenuIcons<ReverbIcon> {
    override val check = ReverbIcon.Check
    override fun create(context: Context, icon: ReverbIcon, color: Int): View =
        ReverbGlyphView(context, icon, color).apply { useTextSurfaceContrast = true }
    override fun bind(view: View, icon: ReverbIcon, color: Int) = (view as ReverbGlyphView).bind(icon, color)
}
