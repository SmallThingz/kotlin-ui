// Reverb application presentation source, retained under the Reverb project GPL-3.0 license.

package smallthingz.ui.reverb

/** Offsets the closing panel to the edge of its viewport before the host retires it. */
fun reverbIncidentPanelTranslationX(viewportWidth: Float, openProgress: Float): Float =
    viewportWidth.coerceAtLeast(0f) * (1f - openProgress.coerceIn(0f, 1f))
