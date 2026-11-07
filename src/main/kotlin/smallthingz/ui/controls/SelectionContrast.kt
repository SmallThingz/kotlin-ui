package smallthingz.ui.controls

import kotlin.math.pow
import kotlin.math.sqrt

/** Allocation-free contrast for an animated translucent fill over an opaque surface.
 * Foregrounds and surface must be opaque sRGB. [foreground] also updates [indicationColor]
 * for up to two overlapping 26/255 state layers painted beneath the text.
 */
class SelectionContrast(
    private val field: Int,
    private val selectedFill: Int,
    private val idleIndication: Int,
    private val selected: Int,
    private val idle: Int,
) {
    private val selectedLuminance = srgbLuminance(selected)
    private val idleLuminance = srgbLuminance(idle)
    private val crossover = sqrt((selectedLuminance + 0.05) * (idleLuminance + 0.05)) - 0.05
    private val lighter = if (selectedLuminance >= idleLuminance) selected else idle
    private val darker = if (selectedLuminance >= idleLuminance) idle else selected

    var indicationColor: Int = idleIndication
        private set

    /** Logical selection disambiguates fully transparent endpoints, regardless of their RGB bits. */
    fun foreground(animatedFill: Int, selectedWhenTransparent: Boolean = false): Int {
        val alpha = animatedFill ushr 24
        val transparentSelected = selectedWhenTransparent && alpha == 0 && selectedFill ushr 24 == 0
        val background = composite(animatedFill, field, alpha)
        val backgroundLuminance = srgbLuminance(background)
        val preferred = when {
            alpha == 0 -> if (transparentSelected) selected else idle
            animatedFill == selectedFill -> selected
            backgroundLuminance <= crossover -> lighter
            else -> darker
        }
        fun contrast(foreground: Int): Double {
            val luminance = if (foreground == selected) selectedLuminance else idleLuminance
            return if (backgroundLuminance >= luminance)
                (backgroundLuminance + 0.05) / (luminance + 0.05)
            else (luminance + 0.05) / (backgroundLuminance + 0.05)
        }
        val alternate = if (preferred == selected) idle else selected
        val ink = when {
            contrast(preferred) >= 4.5 -> preferred
            contrast(alternate) >= 4.5 -> alternate
            backgroundLuminance <= NEUTRAL_CROSSOVER -> LIGHT_TRANSITION_INK
            else -> DARK_TRANSITION_INK
        }
        val inkLuminance = when (ink) {
            selected -> selectedLuminance
            idle -> idleLuminance
            DARK_TRANSITION_INK -> 0.0
            else -> 1.0
        }
        val indication = if (alpha == 0 && !transparentSelected) idleIndication else ink
        // Focus and press can overlap: each current layer is 10%, rounded to alpha 26/255.
        // The indication is behind the glyphs, so only the surface receives these overlays.
        val firstLayer = composite(indication, background, 26)
        val secondLayer = composite(indication, firstLayer, 26)
        fun readable(stateBackground: Int): Boolean {
            val luminance = srgbLuminance(stateBackground)
            val ratio = (maxOf(luminance, inkLuminance) + 0.05) /
                (minOf(luminance, inkLuminance) + 0.05)
            return ratio >= 4.5
        }
        // Chromatic sRGB blends need not be monotonic in luminance: qualify both states.
        indicationColor = if (readable(firstLayer) && readable(secondLayer)) indication
            else if (backgroundLuminance < inkLuminance) DARK_TRANSITION_INK else LIGHT_TRANSITION_INK
        return ink
    }

    private fun composite(foreground: Int, background: Int, alpha: Int): Int {
        fun channel(shift: Int): Int =
            (((foreground ushr shift and 255) * alpha +
                (background ushr shift and 255) * (255 - alpha) + 127) / 255) shl shift
        return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
    }

    private companion object {
        const val LIGHT_TRANSITION_INK = -1
        const val DARK_TRANSITION_INK = -0x1000000
        val NEUTRAL_CROSSOVER = sqrt(1.05 * 0.05) - 0.05
    }
}

internal fun srgbLuminance(argb: Int): Double {
    fun channel(shift: Int): Double {
        val value = (argb ushr shift and 255) / 255.0
        return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
    }
    return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
}

/** Chooses a readable opaque foreground; explicit component role overrides remain caller-owned. */
internal fun selectionForeground(fill: Int, surface: Int, preferred: Int, alternate: Int): Int {
    val alpha = fill ushr 24
    fun channel(shift: Int): Int = (((fill ushr shift and 255) * alpha +
        (surface ushr shift and 255) * (255 - alpha) + 127) / 255) shl shift
    val background = srgbLuminance(-0x1000000 or channel(16) or channel(8) or channel(0))
    fun ratio(ink: Int): Double {
        val foreground = srgbLuminance(ink)
        return (maxOf(background, foreground) + .05) / (minOf(background, foreground) + .05)
    }
    return when {
        preferred ushr 24 == 255 && ratio(preferred) >= 4.5 -> preferred
        alternate ushr 24 == 255 && ratio(alternate) >= 4.5 -> alternate
        background <= sqrt(1.05 * .05) - .05 -> -1
        else -> -0x1000000
    }
}

/** Qualifies a static selected ripple's whole integer opacity envelope once when paint is bound. */
internal fun selectionIndication(fill: Int, surface: Int, ink: Int, preferred: Int): Int {
    fun composite(foreground: Int, background: Int, alpha: Int): Int {
        fun channel(shift: Int) = (((foreground ushr shift and 255) * alpha +
            (background ushr shift and 255) * (255 - alpha) + 127) / 255) shl shift
        return -0x1000000 or channel(16) or channel(8) or channel(0)
    }
    val background = composite(fill, surface, fill ushr 24)
    val backgroundLuminance = srgbLuminance(background)
    val inkLuminance = srgbLuminance(ink)
    for (alpha in 1..40) {
        val luminance = srgbLuminance(composite(preferred, background, alpha))
        if ((maxOf(luminance, inkLuminance) + .05) / (minOf(luminance, inkLuminance) + .05) < 4.5)
            return if (backgroundLuminance < inkLuminance) -0x1000000 else -1
    }
    return preferred
}
