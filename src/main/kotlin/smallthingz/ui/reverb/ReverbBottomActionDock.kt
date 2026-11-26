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
) : NativeBottomActionDock(context, theme.density, action,
    horizontalInsetDp = theme.appearance.settingsSelectors.retentionSection.sideInsetDp,
    minimumActionHeightDp = MINIMUM_ACTION_HEIGHT_DP) {
    companion object {
        // A floor, not a fixed height: the retained action still measures wrapped/scaled labels.
        private const val MINIMUM_ACTION_HEIGHT_DP = 48

        /** Optional construction recipe; the dock also accepts an existing retained button. */
        fun buttonStyle(theme: ReverbTheme): NativeButtonRecipe = theme.appearance.buttons.filled.let {
            it.copy(surface = it.surface.copy(minimumHeightDp = MINIMUM_ACTION_HEIGHT_DP, radiusDp = 0f), elevated = false)
        }
    }
}
