package smallthingz.ui.reverb

import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Placement only: the application retains window type, security flags, token and add/remove lifetime.
 * Pass the width of the window manager's available frame, not an inset-subtracted content width.
 * Android continues fitting system bars/cutouts; the panel consumes its delivered content insets.
 */
class ReverbRequestWindowPlacement private constructor(val width: Int, val y: Int) {
    val height: Int get() = ViewGroup.LayoutParams.WRAP_CONTENT
    val gravity: Int get() = Gravity.TOP or Gravity.CENTER_HORIZONTAL

    /** Shared geometry; the host supplies its window security and pixel-format policy. */
    fun windowParameters(type: Int, flags: Int, format: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(width, height, type, flags, format).also {
            it.gravity = gravity
            it.y = y
        }

    companion object {
        /** Placement in the existing unstyled holder; window and content lifetime remain host-owned. */
        fun contentParameters(): FrameLayout.LayoutParams =
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        /** Top request panels share the permission recipe's width cap and exterior window inset. */
        fun forAvailableWidth(availableWidthPx: Int, density: Float): ReverbRequestWindowPlacement {
            require(availableWidthPx > 0 && density.isFinite() && density > 0f)
            val recipe = ReverbDesign.appearance.modals.permissionDialog
            val inset = (recipe.windowInsetDp * density).roundToInt()
            val maximum = (recipe.maxWidthDp * density).roundToInt().coerceAtLeast(1)
            val width = (availableWidthPx.toLong() - 2L * inset).coerceIn(1L, maximum.toLong()).toInt()
            return ReverbRequestWindowPlacement(width, inset)
        }
    }
}
