package smallthingz.ui.common

import smallthingz.ui.scroll.NativeOverscrollAdapter

import android.content.Context
import smallthingz.ui.scroll.NativeOverscrollAxis
import smallthingz.ui.unit.Density

class NativeHorizontalOverscroll(
    context: Context,
    density: Density,
    glowColor: Int,
    invalidate: () -> Unit,
) : NativeOverscrollAdapter(context, density, glowColor, NativeOverscrollAxis.HORIZONTAL, invalidate)
