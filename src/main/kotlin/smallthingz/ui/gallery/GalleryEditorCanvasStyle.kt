package smallthingz.ui.gallery

import androidx.annotation.ColorInt
import smallthingz.ui.components.NativeColorScheme

/** Editor presentation and entry/handoff backing from the current resolved appearance. */
object GalleryEditorCanvasStyle {
    /** Reapply when the palette changes; document pixels, missing tiles and exports remain caller-owned. */
    @ColorInt
    fun backdropColor(colors: NativeColorScheme): Int = colors.background
}
