package smallthingz.ui.email

import android.widget.FrameLayout
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.common.DropdownDismissPolicy
import smallthingz.ui.common.DropdownGroup
import smallthingz.ui.common.DropdownSelectionPolicy

/**
 * Creates a mail choice group with explicit selection timing.
 * Outside taps, Back, and accessibility dismissal use [DropdownDismissPolicy.COMMIT].
 */
@Suppress("FunctionName")
fun EmailDropdownGroup(
    initialTheme: ControlTheme,
    overlayHost: () -> FrameLayout?,
    inputAllowed: () -> Boolean,
): DropdownGroup = EmailDropdownGroup(initialTheme, overlayHost, DropdownDismissPolicy.COMMIT, inputAllowed)

/**
 * Creates a mail choice group with explicit selection timing.
 * Use [DropdownDismissPolicy.CANCEL] to discard pending choices on user dismissal.
 */
@Suppress("FunctionName")
fun EmailDropdownGroup(
    initialTheme: ControlTheme,
    overlayHost: () -> FrameLayout?,
    dismissPolicy: DropdownDismissPolicy,
    inputAllowed: () -> Boolean,
): DropdownGroup = DropdownGroup(
    initialTheme, overlayHost, inputAllowed, DropdownSelectionPolicy.EXPLICIT,
    choiceRows = true, dismissPolicy = dismissPolicy,
)
