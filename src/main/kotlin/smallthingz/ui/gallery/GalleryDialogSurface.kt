package smallthingz.ui.gallery

import android.content.Context
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeSurface
import smallthingz.ui.components.NativeSurfaceStyle
import smallthingz.ui.components.nativeTextStyle
import smallthingz.ui.reverb.ReverbDesign

/** Gallery's centered content dialogs share the library's surfaces and typography. */
class GalleryDialogSurface(context: Context, dark: Boolean) : NativeSurface(
    context, surfaceStyle(ReverbDesign.appearance.resolveColorScheme(context, dark)),
) {
    init { bindTheme(dark) }

    fun bindTheme(dark: Boolean) {
        val palette = ReverbDesign.appearance.resolveColorScheme(context, dark)
        setSurfacePreset(palette.surfacePreset)
        applyStyle(surfaceStyle(palette))
    }

    companion object {
        private val modals = ReverbDesign.appearance.modals
        val titleStyle = ReverbDesign.appearance.typography.nativeTextStyle(modals.floating.headlineRole)
        val horizontalPaddingDp: Int = modals.sheet.horizontalPaddingDp.toInt()
        val verticalPaddingDp: Int = modals.sheet.bottomPaddingDp.toInt()
        val bodyGapDp: Int = modals.floating.headingGapDp.toInt()
        // Short date wheels and IME-resized inputs retain their actual content height.
        const val compactVerticalPaddingDp: Int = 4

        private fun surfaceStyle(palette: NativeColorScheme) = NativeSurfaceStyle(
            fillColor = ReverbDesign.modalSurface(palette),
            cornerRadiusDp = modals.sheet.radiusDp,
            outlineColor = palette.surfacePreset?.borderColor,
            outlineWidthDp = palette.surfacePreset?.borderWidthDp ?: 0f,
            noise = true,
        )
    }
}
