package smallthingz.ui.reverb

import android.content.Context
import smallthingz.ui.components.NativeBottomActionDock
import smallthingz.ui.components.NativeButtonRecipe
import smallthingz.ui.components.NativeMaterialButtonView

/** Constructor-compatible Reverb binding for the shared retained action dock. */
class ReverbBottomActionDock(
    context: Context,
    theme: ReverbTheme,
    action: NativeMaterialButtonView,
) : NativeBottomActionDock(context, theme.density, action) {
    companion object {
        /** Optional construction recipe; the dock also accepts an existing retained button. */
        fun buttonStyle(theme: ReverbTheme): NativeButtonRecipe = theme.appearance.buttons.filled.let {
            it.copy(surface = it.surface.copy(minimumHeightDp = 64, radiusDp = 0f), elevated = false)
        }
    }
}
