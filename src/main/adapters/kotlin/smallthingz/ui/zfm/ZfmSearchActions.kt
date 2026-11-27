package smallthingz.ui.zfm

import android.content.Context
import smallthingz.ui.components.NativeMaterialButtonView

/** Search status action with a full native target, retaining the common text-button recipe. */
fun ZfmTheme.searchDetailsButton(
    context: Context,
    label: String,
    onClick: () -> Unit,
): NativeMaterialButtonView {
    val common = buttons.text
    val recipe = common.copy(surface = common.surface.copy(
        minimumHeightDp = maxOf(common.surface.minimumHeightDp, common.minimumTouchTargetDp),
    ))
    return button(context, label, recipe = recipe, onClick = onClick)
}
