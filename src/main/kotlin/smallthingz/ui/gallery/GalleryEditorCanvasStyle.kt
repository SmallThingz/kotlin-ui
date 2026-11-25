package smallthingz.ui.gallery

import androidx.annotation.ColorInt

/** Opaque neutral sRGB backing shared by editor presentation and entry/handoff views. */
object GalleryEditorCanvasStyle {
    /** Canvas-only color; independent of window chrome, missing tiles and exported pixels. */
    @get:ColorInt
    val backdropColor: Int = 0xff242424.toInt()
}
