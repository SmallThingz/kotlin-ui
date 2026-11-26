package smallthingz.ui.gallery

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/** Spends only spare, already-measured Heal panel space; never changes its measurement. */
open class GalleryHealControlsLayout(context: Context) : LinearLayout(context) {
    var reservedDecisionGroup: GalleryHealDecisionGroup? = null
    var anchorReadyDecision = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val group = reservedDecisionGroup ?: return
        if (!anchorReadyDecision || group.parent !== this || group.visibility != View.VISIBLE) return
        // Details or any other later visible content must retain ordinary vertical flow.
        val lastVisible = (childCount - 1 downTo 0).firstOrNull { getChildAt(it).visibility != View.GONE }
        if (lastVisible == null || getChildAt(lastVisible) !== group) return
        val targetBottom = height - paddingBottom
        if (targetBottom <= group.bottom || !fullyFitsScrollViewport()) return
        group.extendDecisionTo(targetBottom)
    }

    private fun fullyFitsScrollViewport(): Boolean {
        var ancestor = parent
        var foundViewport = false
        while (ancestor is ViewGroup) {
            if (ancestor is ScrollView) {
                foundViewport = true
                val panel = Rect(0, 0, width, height)
                ancestor.offsetDescendantRectToMyCoords(this, panel)
                // Drawing rect includes the actual scroll offset; compare in the same
                // content coordinates. A partial/offscreen panel keeps natural flow.
                val viewport = Rect()
                ancestor.getDrawingRect(viewport)
                viewport.left += ancestor.paddingLeft
                viewport.top += ancestor.paddingTop
                viewport.right -= ancestor.paddingRight
                viewport.bottom -= ancestor.paddingBottom
                if (!viewport.contains(panel)) return false
            }
            ancestor = ancestor.parent
        }
        return foundViewport
    }
}
