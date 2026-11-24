package smallthingz.ui.reverb

import android.content.Context
import android.graphics.Rect
import android.view.View
import smallthingz.ui.components.NativeSelectionTopBarLayout
import smallthingz.ui.components.NativeTopBarAction
import smallthingz.ui.components.compactToolbarActionTop
import smallthingz.ui.components.nativeMinimumInteractiveVisualOffset
import kotlin.math.roundToInt

/** Keeps selection wrappers on Reverb's compact visual slots, including odd pixel sizes. */
open class ReverbSelectionTopBarLayout internal constructor(
    context: Context,
    private val start: View,
    private val title: View,
    private val end: View,
    private val endActions: List<View>,
) : NativeSelectionTopBarLayout(context, ReverbDesign.appearance.toolbars, start, title, end, endActions.size) {
    private val actionBounds = Rect()

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val size = (toolbarStyle.action.sizeDp * touchDensity).roundToInt()
        val edge = (toolbarStyle.compactEdgeInsetDp * touchDensity).roundToInt()
        val minimum = (toolbarStyle.action.minimumTargetDp * touchDensity).roundToInt()
        val residual = nativeMinimumInteractiveVisualOffset(size, minimum)
        val startLeft = leftInsetPx + edge + residual
        val endLeft = width - rightInsetPx - edge - size + residual
        val actionTop = compactToolbarActionTop(height, size) + residual
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val shift = alignAction(start, start, if (rtl) endLeft else startLeft, actionTop, size)
        // Retain the measured title width and its gap from the start wrapper. The end group
        // moves outwards, so the existing conservative title allocation remains disjoint.
        title.offsetLeftAndRight(shift)
        endActions.lastOrNull()?.let { outer ->
            alignAction(end, outer, if (rtl) startLeft else endLeft, actionTop, size)
        }
    }

    private fun alignAction(container: View, action: View, left: Int, top: Int, compactSize: Int): Int {
        val visual = (action as? NativeTopBarAction)?.visual ?: action
        actionBounds.set(0, 0, visual.width, visual.height)
        offsetDescendantRectToMyCoords(visual, actionBounds)
        // Joined end cells paint the full 48dp. Align their centered glyph to the compact
        // 46dp action, retaining each cell's full surface and independent input ownership.
        val glyph = (toolbarStyle.action.glyphDp * touchDensity).roundToInt()
        val compactGlyphInset = ((compactSize - glyph) / 2f).roundToInt()
        val glyphOffsetX = ((visual.width - glyph) / 2f).roundToInt() - compactGlyphInset
        val glyphOffsetY = ((visual.height - glyph) / 2f).roundToInt() - compactGlyphInset
        val dx = left - actionBounds.left - glyphOffsetX
        val dy = top - actionBounds.top - glyphOffsetY
        container.offsetLeftAndRight(dx)
        container.offsetTopAndBottom(dy)
        return dx
    }
}
