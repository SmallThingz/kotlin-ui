package smallthingz.ui.mail

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.FrameLayout
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.common.DropdownDismissPolicy
import smallthingz.ui.common.DropdownGroup
import smallthingz.ui.email.EmailDropdownGroup
import smallthingz.ui.email.emailRecipientMatchColor
import smallthingz.ui.email.emailRecipientPopupBackground

/** Joined recipient rows and subject editor for a native mail composer. */
typealias MailComposerFields = smallthingz.ui.email.EmailComposerFields

/** Native recipient suggestion or alternate-address row. The host supplies its contents. */
typealias MailRecipientRow = smallthingz.ui.email.EmailRecipientRow

/** A retained picker with explicit selection and the mail dismissal policy. */
fun mailDropdownGroup(
    initialTheme: ControlTheme,
    overlayHost: () -> FrameLayout?,
    dismissPolicy: DropdownDismissPolicy = DropdownDismissPolicy.COMMIT,
    inputAllowed: () -> Boolean,
): DropdownGroup = EmailDropdownGroup(initialTheme, overlayHost, dismissPolicy, inputAllowed)

fun mailRecipientMatchColor(theme: ControlTheme): Int = emailRecipientMatchColor(theme)

fun mailRecipientPopupBackground(context: Context, theme: ControlTheme): Drawable =
    emailRecipientPopupBackground(context, theme)
