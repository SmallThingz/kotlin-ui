package smallthingz.ui.components

import android.content.Context
import smallthingz.ui.icons.MaterialRoundedIcon

internal val dropdownArrowMask: NativePathIconMask get() = MaterialRoundedIcon.ArrowDropDown.mask

internal fun createDropdownIcon(context: Context, color: Int): NativeDropdownIcon {
    val glyph = CachedPathIconDrawable({ dropdownArrowMask }, context.resources.displayMetrics.density, color)
    val view = NativeDecorativeIconView(context, glyph, color)
    return NativeDropdownIcon(view, view::setColorImmediately)
}
