package smallthingz.ui.components

import android.view.View

/** Caller-owned content and semantic lifetime; the dialog owns only its window and motion. */
class NativeFloatingDialogContent(
    val view: View,
    val predictiveMotionTarget: View = view,
    val bindWindowAccessibility: (View) -> Unit,
    val dispose: () -> Unit,
) {
    /** Optional paint outside the caller's content and its input bounds. */
    internal var exteriorTopPaintSpace: (() -> Int)? = null
    internal var exteriorBottomPaintSpace: (() -> Int)? = null

    companion object {
        /**
         * Use a shared panel as the complete dialog content. The window reserves its exterior
         * highlight without adding padding to the panel, its viewport or its input bounds.
         * Paint space follows surface rebinds; accessibility and disposal stay caller-owned.
         */
        fun forPanel(
            surface: NativeFloatingPanelSurface,
            bindWindowAccessibility: (View) -> Unit,
            dispose: () -> Unit,
        ): NativeFloatingDialogContent =
            NativeFloatingDialogContent(surface, surface, bindWindowAccessibility, dispose).apply {
                exteriorTopPaintSpace = surface::exteriorTopPaintSpace
                exteriorBottomPaintSpace = surface::exteriorBottomPaintSpace
            }
    }
}
