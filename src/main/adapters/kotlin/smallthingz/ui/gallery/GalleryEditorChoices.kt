package smallthingz.ui.gallery

import android.content.Context
import smallthingz.ui.components.ChoiceArrangement
import smallthingz.ui.components.NativeChoiceColor
import smallthingz.ui.components.NativeChoiceLabelWidth
import smallthingz.ui.components.NativeChoiceSemantics
import smallthingz.ui.components.NativeChoiceStyle
import smallthingz.ui.components.NativeSegmentedChoice
import smallthingz.ui.components.NativeTextRole
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.text.NativeTypographyRole

enum class GalleryEditorChoiceKind { MENU, RETOUCH, FINISH, AI }

/** Shared editor chooser presentation; the application owns tool availability and activation. */
class GalleryEditorChoices(
    context: Context,
    palette: ControlPalette,
    kind: GalleryEditorChoiceKind,
) : NativeSegmentedChoice(
    context, palette, editorChoiceStyle(kind),
    gapDp = if (kind == GalleryEditorChoiceKind.MENU) 8f else 4f,
    maxColumns = 3,
    arrangement = when (kind) {
        GalleryEditorChoiceKind.RETOUCH, GalleryEditorChoiceKind.FINISH -> ChoiceArrangement.Wrap
        else -> ChoiceArrangement.EqualWidth
    },
    verticalGapDp = if (kind == GalleryEditorChoiceKind.MENU) 8f else 4f,
    semantics = if (kind == GalleryEditorChoiceKind.MENU) NativeChoiceSemantics.RADIO
        else NativeChoiceSemantics.BUTTON,
    activateOnFocusNavigation = false,
    fillRowHeight = true,
) {
    // The shared grid reduces the three-column maximum using the actual scaled labels.
    // All groups fill their columns equally so short labels cannot leave ragged rows.
    override val fillWrappedCells: Boolean get() = true
}

private fun editorChoiceStyle(kind: GalleryEditorChoiceKind): NativeChoiceStyle {
    val grouped = kind != GalleryEditorChoiceKind.MENU
    val label = GalleryDesign.textStyle(NativeTypographyRole.LABEL_MEDIUM)
    return NativeChoiceStyle(
        radiusDp = 12f, minHeightDp = if (grouped) 56f else 64f,
        horizontalPaddingDp = 4f, verticalPaddingDp = if (grouped) 6f else 8f,
        outlined = false, iconSizeDp = 22f, iconGapDp = 4f, iconAbove = true,
        minimumLabelWidth = NativeChoiceLabelWidth.WholeLabel,
        textStyle = label.copy(weight = 400), selectedTextStyle = label.copy(weight = 600),
        disabledContent = NativeChoiceColor(NativeTextRole.MUTED, .38f),
        disabledSelectedContent = NativeChoiceColor(NativeTextRole.ACCENT, .38f),
        disabledSelectedFill = NativeChoiceColor(NativeTextRole.ACCENT, .10f),
    )
}
