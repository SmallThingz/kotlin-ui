package smallthingz.ui.zfm

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.Gravity
import android.widget.LinearLayout
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbToolbarActionView
import kotlin.math.roundToInt

/** Retained collection-header action using Reverb's surfaced toolbar recipe. */
class ZfmCollectionActionView(
    context: Context,
    theme: ZfmTheme,
    inputRoot: () -> View?,
    label: String,
    glyph: Drawable,
    private val onAction: () -> Unit,
) : ReverbToolbarActionView(context, theme.density.density, theme.colors, inputRoot,
    label, selectionWrapper = true, rolePresent = true) {
    init {
        glyph.mutate().let {
            it.setTint(sampledIconTint)
            setGlyph(it, sampledIconTint)
        }
        bindTheme(theme)
    }

    fun bindTheme(theme: ZfmTheme, enabled: Boolean = accessibilityAction.isEnabled) {
        bindToolbarAction(theme.density.density, theme.colors, enabled,
            NativeToolbarActionTone.MUTED, onAction)
    }

    override fun onCancelPendingInputEvents() {
        super.onCancelPendingInputEvents()
        cancelInput()
    }
}

/** Separate surfaced actions keep full target slots and the shared toolbar spacing token. */
class ZfmCollectionActionsRow(
    context: Context,
    theme: ZfmTheme,
    private val actions: List<ZfmCollectionActionView>,
) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        actions.forEach { addView(it) }
        bindTheme(theme)
    }

    fun bindTheme(theme: ZfmTheme) {
        val toolbar = ReverbDesign.appearance.toolbars
        val density = theme.density.density
        val slot = (maxOf(toolbar.action.sizeDp, toolbar.action.minimumTargetDp) * density).roundToInt()
        // The same shared token is used for the gap in ZfmHomeNavigation.
        val gap = (toolbar.centeredPaddingDp * density).roundToInt()
        actions.forEachIndexed { index, action ->
            val start = if (index == 0) 0 else gap
            val params = action.layoutParams as LayoutParams
            if (params.width != slot || params.height != slot || params.marginStart != start) {
                params.width = slot
                params.height = slot
                params.marginStart = start
                action.layoutParams = params
            }
            action.bindTheme(theme)
        }
    }
}
