package smallthingz.ui.reverb

import smallthingz.ui.components.NativeButtonRecipes
import smallthingz.ui.components.NativeGroupSurfaceRecipes

/** Fixed roles, never caller-supplied recipe overrides. */
enum class ReverbApplicationAction {
    PRIMARY, SECONDARY, ACCENT, DESTRUCTIVE, DESTRUCTIVE_TEXT, TONAL,
    FOOTER_BACK, FOOTER_CONTINUE, RAISED_TEXT, ICON, LARGE_ICON;

    internal fun recipe(recipes: NativeButtonRecipes) = when (this) {
        PRIMARY -> recipes.filled
        SECONDARY -> recipes.neutralText
        ACCENT -> recipes.text
        DESTRUCTIVE -> recipes.destructive
        DESTRUCTIVE_TEXT -> recipes.destructiveText
        TONAL -> recipes.tonal
        FOOTER_BACK -> recipes.footerBack
        FOOTER_CONTINUE -> recipes.footerContinue
        RAISED_TEXT -> recipes.raisedText
        ICON -> recipes.raisedIcon
        LARGE_ICON -> recipes.largeRaisedIcon
    }
}

enum class ReverbApplicationGroup {
    SETTINGS, CHOICES, WARNING, RECORDING;

    internal fun recipe(recipes: NativeGroupSurfaceRecipes) = when (this) {
        SETTINGS -> recipes.settingsGroup
        CHOICES -> recipes.settingsChoices
        WARNING -> recipes.settingsWarning
        RECORDING -> recipes.recordingRow.surface
    }
}
