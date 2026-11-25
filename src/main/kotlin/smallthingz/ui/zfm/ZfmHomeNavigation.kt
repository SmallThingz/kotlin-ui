package smallthingz.ui.zfm

import android.content.Context
import android.view.Gravity
import smallthingz.ui.components.ChoiceArrangement
import smallthingz.ui.components.NativeChoice
import smallthingz.ui.components.NativeChoiceLabelWidth
import smallthingz.ui.components.NativeChoiceSemantics
import smallthingz.ui.components.NativeChoiceStyle
import smallthingz.ui.components.NativeSegmentedChoice
import smallthingz.ui.text.NativeTypographyRole

/**
 * Home's adaptive action group, measured and rendered by the common choice component.
 * Full scaled labels determine wrapping, with at most four columns; focus navigation never
 * activates a destination. Options with omitted semantics become BUTTONs; other roles fail fast.
 *
 * The group owns its chips. Obtain each retained chip with [NativeSegmentedChoice.choiceView]
 * and install the app's click listener, focus key and popup anchor on that exact instance.
 * Stable ids survive rebinds; removed ids are retired and must not remain popup anchors.
 * [NativeSegmentedChoice.onSelectionRequest] is admission only: its default accepts the
 * common component's enabled/current-option checks, then the chip's click listener dispatches
 * the action. A replacement admission callback must return a Boolean without dispatching the
 * action itself, or it would run twice. The app retains lifecycle and action-owner checks.
 *
 * [selectedHomeId] is visual BUTTON selection, not radio/tab state. On every later bind, keep
 * all options BUTTONs, pass null as the group's selected id, then call bindButton on the Home
 * chip with its current label, selected state and enabled state. Group bind clears that visual
 * selection; palette and measurement updates do not require rebinding. After adding new ids,
 * configure their chips with setSingleLine(false), maxLines = Int.MAX_VALUE, ellipsize = null
 * and Gravity.TOP or Gravity.CENTER_HORIZONTAL, as this factory does for its initial ids.
 * Close the group only on permanent owner disposal, never on temporary detach or column changes.
 */
fun ZfmTheme.homeNavigation(
    context: Context,
    destinations: List<NativeChoice>,
    selectedHomeId: String?,
): NativeSegmentedChoice {
    require(destinations.isNotEmpty()) { "Home navigation requires destinations" }
    require(destinations.all { it.semantics == null || it.semantics == NativeChoiceSemantics.BUTTON }) {
        "Home navigation destinations must use BUTTON semantics"
    }
    require(selectedHomeId == null || destinations.any { it.id == selectedHomeId }) {
        "Selected Home id must identify a destination"
    }
    val options = destinations.map { it.copy(semantics = NativeChoiceSemantics.BUTTON) }
    val toolbar = appearance.toolbars
    return NativeSegmentedChoice(context, palette, NativeChoiceStyle(
        radiusDp = toolbar.action.radiusDp,
        minHeightDp = toolbar.action.minimumTargetDp,
        verticalPaddingDp = toolbar.centeredPaddingDp,
        outlined = false,
        iconAbove = true,
        iconSizeDp = toolbar.action.glyphDp,
        iconGapDp = toolbar.centeredPaddingDp,
        textStyle = nativeTextStyle(NativeTypographyRole.LABEL_MEDIUM),
        minimumLabelWidth = NativeChoiceLabelWidth.WholeLabel,
    ), gapDp = toolbar.centeredPaddingDp, arrangement = ChoiceArrangement.EqualWidth,
        maxColumns = 4, semantics = NativeChoiceSemantics.BUTTON,
        activateOnFocusNavigation = false, fillRowHeight = true, singleRow = false,
    ).apply {
        onSelectionRequest = { true }
        bind(options, null)
        options.forEach { option ->
            checkNotNull(choiceView(option.id)).apply {
                setSingleLine(false)
                maxLines = Int.MAX_VALUE
                ellipsize = null
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        }
        selectedHomeId?.let { id ->
            val home = options.first { it.id == id }
            checkNotNull(choiceView(id)).bindButton(home.label, selected = true, enabled = home.enabled)
        }
    }
}
