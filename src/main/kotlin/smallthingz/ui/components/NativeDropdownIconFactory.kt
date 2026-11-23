package smallthingz.ui.components

import android.content.Context
import android.graphics.Path

// Rounded Material ArrowDropDown path. Reverb shares this mask with the common field.
internal val dropdownArrowMask: NativePathIconMask by lazy {
    NativePathIconMask(Path().apply {
        moveTo(8.71f, 11.71f)
        rLineTo(2.59f, 2.59f)
        rCubicTo(.39f, .39f, 1.02f, .39f, 1.41f, 0f)
        rLineTo(2.59f, -2.59f)
        rCubicTo(.63f, -.63f, .18f, -1.71f, -.71f, -1.71f)
        lineTo(9.41f, 10f)
        rCubicTo(-.89f, 0f, -1.33f, 1.08f, -.7f, 1.71f)
        close()
    })
}

internal fun createDropdownIcon(context: Context, color: Int): NativeDropdownIcon {
    val glyph = CachedPathIconDrawable({ dropdownArrowMask }, context.resources.displayMetrics.density, color)
    val view = NativeDecorativeIconView(context, glyph, color)
    return NativeDropdownIcon(view, view::setColorImmediately)
}
