package smallthingz.ui.reverb

import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.components.NativeToolbarBackground
import smallthingz.ui.text.style.TextOverflow
import smallthingz.ui.text.view.ParagraphView

/** Shared compact selection bar with a title and a joined action group. */
open class ReverbSelectionToolbar private constructor(
    context: Context,
    theme: ReverbTheme,
    start: View,
    endActions: List<View>,
    private val title: ParagraphView,
    private val group: NativeGroupLayout,
) : ReverbSelectionTopBarLayout(context, start, title, group, endActions) {
    constructor(context: Context, theme: ReverbTheme, start: View, endActions: List<View>) : this(
        context, theme, start, endActions, theme.paragraph(context, "", ReverbDesign.appearance.toolbars.selectionTitleRole),
        NativeGroupLayout(context, ReverbDesign.appearance.toolbars.action.radiusDp,
            LinearLayout.HORIZONTAL, onPaintedSurfaceChanged = ReverbTextSurface::invalidate),
    )

    private val chrome = NativeToolbarBackground(this, toolbarStyle, toolbarStyle.backgroundColor(theme.palette))

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        title.setTextLayout(maxLines = 1, overflow = TextOverflow.Ellipsis)
        endActions.forEachIndexed { index, action ->
            group.addRow(action, NativeGroupLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                dividerStart = index > 0
            })
        }
    }

    fun bindSelectionToolbar(theme: ReverbTheme, label: String) {
        val style = ReverbDesign.appearance.toolbars
        val palette = theme.palette
        bindStyle(theme.density.density, 0, style)
        chrome.bind(style, style.backgroundColor(palette))
        title.setText(label)
        theme.bindParagraph(title, style.selectionTitleRole)
        group.setPalette(style.actionFill(palette), style.actionOutline(palette, NativeToolbarActionTone.NEUTRAL))
    }

    fun previewSelectionBack(progress: Float, direction: Float) =
        toolbarStyle.applySelectionBackPreview(this, progress, direction)
}
