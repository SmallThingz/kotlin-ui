package smallthingz.ui.inject

import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeMergedActionLayout

/** Shared joined geometry; callers address actions by role rather than child position. */
class InjectDialogActions(
    val cancelButton: NativeMaterialButtonView,
    val confirmButton: NativeMaterialButtonView,
    palette: NativeColorScheme,
) : NativeMergedActionLayout(cancelButton.context, listOf(cancelButton, confirmButton), palette) {
    init { asActionFooter() }
}
