package smallthingz.ui.reverb

import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/** Physical safe area supplied by the host window. */
data class ReverbLibraryInsets(val left: Int = 0, val right: Int = 0, val bottom: Int = 0)

/** Library viewport geometry, overlay slots and gesture distances. */
object ReverbLibraryLayout {
    /** Legacy factory default retained for callers without a window palette. */
    val overscrollGlowColor: Int get() = 0xff666666.toInt()

    fun overscrollGlowColor(palette: ReverbPalette): Int = palette.muted

    fun headerHeightPx(density: Float) = ReverbDesign.appearance.toolbars.compactHeightPx(density)
    fun horizontalOverflowPx(density: Float) = px(30, density)
    fun minimumTouchTargetPx(density: Float) = 48f * density
    fun refreshThresholdPx(density: Float) = px(80, density)
    fun edgeDismissDistancePx(density: Float) = 64f * density
    fun pendingRecoveryHeightPx(density: Float) = px(84, density)

    fun applyListInsets(view: View, density: Float) {
        val side = px(16, density)
        view.setPadding(side, 0, side, 0)
    }

    fun measureToolbar(toolbar: ViewGroup, width: Int, height: Int) {
        for (index in 0 until toolbar.childCount) toolbar.getChildAt(index).measure(exact(width), exact(height))
    }

    fun layoutToolbar(toolbar: ViewGroup) {
        for (index in 0 until toolbar.childCount) toolbar.getChildAt(index).layout(0, 0, toolbar.width, toolbar.height)
    }

    fun measureScreen(toolbar: View, body: View, width: Int, height: Int, insets: ReverbLibraryInsets, density: Float) {
        toolbar.measure(exact(width), exact(headerHeightPx(density)))
        body.measure(exact(width - insets.left - insets.right), exact(height - toolbar.measuredHeight - insets.bottom))
    }

    fun layoutScreen(toolbar: View, body: View, width: Int, height: Int, insets: ReverbLibraryInsets) {
        toolbar.layout(0, 0, width, toolbar.measuredHeight)
        body.layout(insets.left, toolbar.measuredHeight, width - insets.right, height - insets.bottom)
    }

    fun measureBody(content: View?, refresh: View, undo: View?, width: Int, height: Int, density: Float) {
        content?.measure(exact(width), exact(height))
        refresh.measure(atMost(width), atMost(height))
        undo?.measure(exact(width - 2 * px(16, density)), atMost(height - 2 * px(12, density)))
    }

    fun layoutBody(content: View?, refresh: View, undo: View?, width: Int, height: Int, density: Float) {
        content?.layout(0, 0, width, height)
        centeredAt(refresh, width, 0)
        undo?.let { centeredAt(it, width, height - px(12, density) - it.measuredHeight) }
    }

    private fun centeredAt(view: View, width: Int, top: Int) {
        val left = ((width - view.measuredWidth) / 2f).roundToInt()
        view.layout(left, top, left + view.measuredWidth, top + view.measuredHeight)
    }

    private fun px(dp: Int, density: Float) = (dp * density).roundToInt()
    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
    private fun atMost(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.AT_MOST)
}
