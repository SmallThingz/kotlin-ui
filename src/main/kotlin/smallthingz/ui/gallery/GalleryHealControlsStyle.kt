package smallthingz.ui.gallery

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import smallthingz.ui.components.NativeChoiceStyle
import smallthingz.ui.components.NativeTextRole
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.controls.ControlStyle
import smallthingz.ui.text.NativeTypographyRole

/** Paint changes only: preserve the existing Heal selector metrics and canvas reservation. */
object GalleryHealControlsStyle {
    val preparationChoice = NativeChoiceStyle(radiusDp = 12f, minHeightDp = 48f,
        horizontalPaddingDp = 8f, verticalPaddingDp = 8f, outlined = false,
        textStyle = GalleryDesign.textStyle(NativeTypographyRole.LABEL_LARGE),
        selectedTextStyle = GalleryDesign.textStyle(NativeTypographyRole.LABEL_LARGE).copy(weight = 600),
        selectedFill = false, selectedOutlined = true, unselectedOutlined = false,
        normalTextRole = NativeTextRole.MUTED, selectedTextRole = NativeTextRole.CONTENT)

    val comparisonChoice = preparationChoice.copy(outlined = true, unselectedOutlined = true, selectedFill = true,
        normalTextRole = NativeTextRole.CONTENT, selectedTextRole = null)

    /** Keep the existing Button, target, text layout and input-owner callbacks. */
    fun emphasizePreview(button: Button, palette: ControlPalette) {
        ControlStyle(button.context, palette).applyButton(button, filled = true)
    }
}

/** Comparison precedes the decision row; both retained rows keep their exact width/height rules. */
class GalleryHealDecisionGroup(context: Context, comparisonRow: View, decisionRow: View) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // No extra padding, margins or minimum: the sum of the old rows stays unchanged
        // in Selecting, Working and Preview, including wrapped/large-text labels.
        addView(comparisonRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(decisionRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
}
