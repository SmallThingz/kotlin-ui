package smallthingz.ui.reverb.settings

import android.content.Context
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbTextRole
import smallthingz.ui.text.view.ParagraphView

internal fun settingsSectionTitle(context: Context, text: String, environment: ReverbEnvironment): ParagraphView =
    environment.paragraph(context, text, ReverbTextRole.BODY_LARGE, environment.palette.onSurface)
        .also { bindSectionTitle(it, environment) }

internal fun bindSectionTitle(view: ParagraphView, environment: ReverbEnvironment) {
    view.setTypography(environment.appearance.settingsSelectors.sectionTitle,
        environment.textDensity, environment.sharedFontResolver)
    view.animateTextColor(environment.palette.onSurface)
}
