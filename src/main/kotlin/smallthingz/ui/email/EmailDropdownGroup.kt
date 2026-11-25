package smallthingz.ui.email

import android.widget.FrameLayout
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.common.DropdownGroup
import smallthingz.ui.common.DropdownSelectionPolicy

/** One mail window's choices. Browsing is reversible until the user activates an option. */
@Suppress("FunctionName")
fun EmailDropdownGroup(
    initialTheme: ControlTheme,
    overlayHost: () -> FrameLayout?,
    inputAllowed: () -> Boolean,
): DropdownGroup = DropdownGroup(initialTheme, overlayHost, inputAllowed, DropdownSelectionPolicy.EXPLICIT, choiceRows = true)
