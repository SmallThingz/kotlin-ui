package smallthingz.ui.components

import android.content.Context
import android.view.View
import androidx.core.graphics.ColorUtils
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.controls.textSurfaceForeground

/** Shared toolbar paint and predictive preview, retaining one input owner and surface. */
open class NativeThemedToolbarAction(
    context: Context,
    density: Float,
    initialPalette: NativeColorScheme,
    inputRoot: () -> View?,
    label: String,
    selectionWrapper: Boolean = false,
    private val sharedSurface: Boolean = false,
    rolePresent: Boolean = false,
    onPaintedSurfaceChanged: (View) -> Unit = NativeTextSurface::invalidate,
    foreground: (View, Int) -> Int = { view, tint -> textSurfaceForeground(NativeTextSurface.sample(view), tint) },
    private val style: NativeToolbarStyle = NativeUiAppearance.Default.toolbars,
) : NativeTopBarAction(context, density, inputRoot, label, selectionWrapper, sharedSurface, rolePresent,
    style.contentColor(initialPalette), onPaintedSurfaceChanged, foreground,
    style.action), NativeTextSurfaceTarget {
    private var palette = initialPalette
    private var tone = NativeToolbarActionTone.MUTED
    private var previewTone = NativeToolbarActionTone.MUTED
    private var previewProgress = 0f
    private var surfaceMotion: ViewColorTransition? = null
    private var previewTintMotion: ViewColorTransition? = null
    private val previewTint = { checkNotNull(previewTintMotion).color(0) }

    fun bindToolbarAction(density: Float, palette: NativeColorScheme, enabled: Boolean,
                          tone: NativeToolbarActionTone, onClick: () -> Unit) {
        this.palette = palette
        this.tone = tone
        if (!sharedSurface) {
            bindSurfacePreset(palette.surfacePreset)
            val fill = style.actionFill(palette)
            val border = style.actionOutline(palette, tone)
            if (surfaceMotion == null) {
                surfaceMotion = ViewColorTransition(this, intArrayOf(fill, border, border), ::paintSurface)
            }
            updateSurfaceTargets()
        }
        previewTintMotion?.retarget(style.actionTint(palette, previewTone))
        bindAction(density, enabled, style.actionTint(palette, tone), style.contentColor(palette), onClick)
    }

    /** Paint a predictive preview while the owner keeps the current action and accessible name. */
    fun setToolbarActionPreview(renderer: NativePathMorph, progress: Float, tone: NativeToolbarActionTone) {
        previewProgress = progress.coerceIn(0f, 1f)
        if (previewTone != tone) {
            previewTone = tone
            updateSurfaceTargets()
        }
        val motion = previewTintMotion ?: ViewColorTransition(visual,
            intArrayOf(style.actionTint(palette, NativeToolbarActionTone.MUTED)), visual::invalidate)
            .also { previewTintMotion = it }
        motion.retarget(style.actionTint(palette, previewTone))
        setGlyphMorph(renderer, previewProgress, previewTint)
        paintSurface()
    }

    private fun updateSurfaceTargets() {
        surfaceMotion?.retarget(style.actionFill(palette), style.actionOutline(palette, tone),
            style.actionOutline(palette, previewTone))
        paintSurface()
    }

    private fun paintSurface() {
        val motion = surfaceMotion ?: return
        setSurfacePaletteImmediately(motion.color(0), ColorUtils.blendARGB(motion.color(1), motion.color(2), previewProgress))
    }
}
