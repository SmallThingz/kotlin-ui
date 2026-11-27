package smallthingz.ui.gallery

import android.content.Context
import android.widget.FrameLayout
import smallthingz.ui.components.ChoiceArrangement
import smallthingz.ui.components.NativeChoice
import smallthingz.ui.components.NativeChoiceStyle
import smallthingz.ui.components.NativeTabBar
import smallthingz.ui.components.NativeTextRole
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.text.NativeTypographyRole

/** Two primary destinations inside Gallery's single top chrome; Search is a separate action. */
class GalleryLibraryTabs(
    context: Context,
    palette: ControlPalette,
    private val destinations: List<NativeChoice>,
    selectedId: String,
    onSelectionRequest: (String) -> Boolean,
) : FrameLayout(context), AutoCloseable {
    private val tabs = NativeTabBar(context, palette, NativeChoiceStyle(
        minHeightDp = 48f,
        horizontalPaddingDp = 4f,
        verticalPaddingDp = 8f,
        outlined = false,
        selectedFill = false,
        indicatorHeightDp = 2f,
        textStyle = GalleryDesign.textStyle(NativeTypographyRole.LABEL_MEDIUM),
        normalTextRole = NativeTextRole.MUTED,
        selectedTextRole = NativeTextRole.CONTENT,
    ), gapDp = 0f, arrangement = ChoiceArrangement.EqualWidth,
        fixedColumns = 2, maxColumns = 2, fillRowHeight = true,
        activateOnFocusNavigation = false)

    init {
        require(destinations.size == 2 && destinations.map { it.id }.distinct().size == 2)
        require(destinations.all { it.icon == null })
        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        tabs.onSelectionRequest = onSelectionRequest
        bind(selectedId)
    }

    fun bind(selectedId: String) {
        require(destinations.any { it.id == selectedId })
        tabs.bind(destinations, selectedId)
        destinations.forEach { destination -> tabs.choiceView(destination.id)?.apply {
            maxLines = Int.MAX_VALUE
            ellipsize = null
        } }
    }

    fun applyPalette(palette: ControlPalette) = tabs.applyPalette(palette)
    override fun close() = tabs.close()
}
