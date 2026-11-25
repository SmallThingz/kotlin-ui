package smallthingz.ui.gallery

import androidx.annotation.ColorInt
import smallthingz.ui.components.NativeColorScheme

/** Shared ordinary surround for editor presentation and entry/handoff views. */
object GalleryEditorCanvasStyle {
    /** Canvas-only color; independent of window chrome, missing tiles and exported pixels. */
    @ColorInt
    fun backdropColor(colors: NativeColorScheme): Int = colors.background
}
