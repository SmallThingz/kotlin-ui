package smallthingz.ui.zfm

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.roundToInt

/** Names a destination scope without giving its maintenance action another destination card. */
class ZfmHomeSection(
    context: Context,
    theme: ZfmTheme,
    title: String,
    val body: View,
    val action: View? = null,
) : LinearLayout(context) {
    private val headingGap = (theme.appearance.settingsSelectors.retentionSection.stackedGapDp *
        theme.density.density).roundToInt()
    val heading = ParagraphView(context, theme.appearance.settingsSelectors.sectionTitle,
        theme.density, theme.fontResolver, theme.ink).apply {
        setText(title)
        isAccessibilityHeading = true
        setPaddingRelative(0, 0, 0, headingGap)
    }
    private var bottomFirst = false

    init {
        orientation = VERTICAL
        addView(heading, LayoutParams(-1, -2))
        addView(body, LayoutParams(-1, -2))
        action?.let { addView(it, LayoutParams(-1, -2)) }
    }

    /** Encounter the scope at the starting edge; maintenance stays beyond its destinations. */
    fun bindDirection(bottomUp: Boolean) {
        if (bottomFirst == bottomUp) return
        bottomFirst = bottomUp
        heading.setPaddingRelative(0, if (bottomUp) headingGap else 0, 0,
            if (bottomUp) 0 else headingGap)
        val ordered = if (bottomUp) listOfNotNull(action, body, heading)
            else listOfNotNull(heading, body, action)
        removeAllViews()
        ordered.forEach { addView(it, LayoutParams(-1, -2)) }
    }

    companion object {
        /** Neutral section maintenance keeps the common minimum touch target and wrapping label. */
        fun action(context: Context, theme: ZfmTheme, label: String, onClick: () -> Unit) =
            theme.buttons.neutralText.let { recipe ->
                theme.button(context, label, recipe = recipe.copy(surface = recipe.surface.copy(
                    minimumHeightDp = maxOf(recipe.surface.minimumHeightDp, recipe.minimumTouchTargetDp),
                    alignContentStart = true)), foreground = theme.ink, onClick = onClick)
            }
    }
}
