package smallthingz.ui.common

import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeStandardIconButtonView

typealias Button = NativeMaterialButtonView
typealias IconButton = NativeStandardIconButtonView
typealias MergedList = NativeGroupLayout

/** Equal-width cells for buttons that leave the shared surface visible and own their own input. */
fun mergedButtonGroup(
    context: Context,
    cornerRadiusDp: Float,
    buttons: List<View>,
    onPaintedSurfaceChanged: (View) -> Unit = {},
): MergedList = MergedList(context, cornerRadiusDp, LinearLayout.HORIZONTAL, onPaintedSurfaceChanged).apply {
    buttons.forEachIndexed { index, button ->
        val params = NativeGroupLayout.LayoutParams(0, MATCH_PARENT, 1f)
        params.dividerStart = index > 0
        addRow(button, params)
    }
}
