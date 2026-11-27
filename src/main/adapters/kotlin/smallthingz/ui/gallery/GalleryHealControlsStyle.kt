package smallthingz.ui.gallery

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import smallthingz.ui.components.NativeButtonPalette
import smallthingz.ui.components.NativeChoiceStyle
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeTextRole
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.controls.ControlStyle
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/** Gallery-owned Heal appearance; selection metrics and action targets stay native. */
object GalleryHealControlsStyle {
    /** Fixed separation outside scrolling content, before the existing category divider.
     * Apply after attaching the Heal viewport to its vertical controls parent. Natural
     * parent measurement budgets this margin; never inset/clip the button or auto-scroll.
     * This owns the viewport's bottom margin for that panel's lifetime; repeated binding
     * replaces the resolved gap instead of accumulating it.
     */
    fun separateFromCategoryBar(viewport: View) {
        val params = viewport.layoutParams as ViewGroup.MarginLayoutParams
        val gap = (GalleryDesign.appearance.modals.sheet.actionsGapDp *
            viewport.resources.displayMetrics.density).roundToInt().coerceAtLeast(0)
        if (params.bottomMargin == gap) return
        params.bottomMargin = gap
        viewport.layoutParams = params
    }

    val preparationChoice = NativeChoiceStyle(radiusDp = 12f, minHeightDp = 48f,
        horizontalPaddingDp = 8f, verticalPaddingDp = 8f, outlined = false,
        textStyle = GalleryDesign.textStyle(NativeTypographyRole.LABEL_LARGE),
        selectedTextStyle = GalleryDesign.textStyle(NativeTypographyRole.LABEL_LARGE).copy(weight = 600),
        selectedFill = false, selectedOutlined = true, unselectedOutlined = false,
        normalTextRole = NativeTextRole.MUTED, selectedTextRole = NativeTextRole.CONTENT)

    val comparisonChoice = preparationChoice.copy(outlined = true, unselectedOutlined = true, selectedFill = true,
        normalTextRole = NativeTextRole.CONTENT, selectedTextRole = null)

    /** Selection commands share the native text-button disabled state without replacing the view. */
    fun paintSelectionAction(button: Button, palette: ControlPalette) {
        val colors = NativeButtonPalette(accent = palette.accent, onAccent = palette.onAccent,
            content = palette.content, muted = palette.muted, quietSurface = palette.raised,
            tonalSurface = palette.selection, onTonal = palette.content, errorSurface = palette.raised,
            onError = palette.content, surfacePreset = palette.surfacePreset)
        ControlStyle(button.context, palette).applyButton(button, GalleryDesign.appearance.buttons.text, colors)
    }

    /** Keep the existing Button, target, text layout and input-owner callbacks. */
    fun emphasizePreview(button: Button, palette: ControlPalette) {
        val colors = NativeButtonPalette(accent = palette.accent, onAccent = palette.onAccent,
            content = palette.content, muted = palette.muted, quietSurface = palette.raised,
            tonalSurface = palette.selection, onTonal = palette.content, errorSurface = palette.raised,
            onError = palette.content, surfacePreset = palette.surfacePreset)
        ControlStyle(button.context, palette).applyButton(button, GalleryDesign.appearance.buttons.filled, colors)
    }

    /** Use the window's appearance and all of its semantic button colors when available. */
    fun emphasizePreview(
        button: Button,
        colors: NativeColorScheme,
        appearance: NativeUiAppearance = GalleryDesign.appearance,
    ) {
        ControlStyle(button.context, colors.pageControls).applyButton(button, appearance.buttons.filled, colors.buttonPalette)
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

    /** Move the intact natural-height group within already reserved parent space.
     * The parent's next ordinary layout restores natural placement before re-evaluating
     * fit; neither panel measurement nor the spacing between these rows changes.
     */
    internal fun extendDecisionTo(reservedBottom: Int) {
        val spare = reservedBottom - bottom
        if (spare > 0) offsetTopAndBottom(spare)
    }
}
