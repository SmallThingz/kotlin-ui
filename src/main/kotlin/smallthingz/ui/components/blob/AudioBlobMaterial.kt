// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import kotlin.math.pow
import kotlin.math.roundToInt

/** One readable material, including silence: no separately colored badge behind the readout. */
internal const val AUDIO_BLOB_READOUT_INK: Int = -1
internal const val AUDIO_BLOB_READOUT_SUPPORT: Int = -0x929293 // #6d6d6d, > 5:1 against white.
internal const val AUDIO_BLOB_TRANSITION_SUPPORT: Int = -0x8a8a8b // #757575, > 4.5:1 against black and white.
// The native radial blend can rasterize one code below its nominal support color.
private const val AUDIO_BLOB_DARK_INK_SUPPORT: Int = -0x888889 // #777777, with room above the black-ink floor.
internal fun audioBlobReadoutSupport(darkInk: Boolean, transitioning: Boolean): Int = when {
    darkInk -> AUDIO_BLOB_DARK_INK_SUPPORT
    transitioning -> AUDIO_BLOB_TRANSITION_SUPPORT
    else -> AUDIO_BLOB_READOUT_SUPPORT
}

/** Closest neutral correction that still protects compact ink after group opacity. */
internal fun audioBlobSupportInLayer(support: Int, darkInk: Boolean, backdrop: Int, opacity: Float): Int {
    val ink = if (darkInk) 0xff000000.toInt() else AUDIO_BLOB_READOUT_INK
    val inkLuminance = audioBlobMaterialLuminance(blobBlend(backdrop, ink, opacity))
    fun neutral(shade: Int): Int = 0xff000000.toInt() or (shade * 0x010101)
    fun meetsContrast(shade: Int): Boolean {
        val underlay = audioBlobMaterialLuminance(blobBlend(backdrop, neutral(shade), opacity))
        val contrast = if (darkInk) (underlay + .05) / (inkLuminance + .05)
            else (inkLuminance + .05) / (underlay + .05)
        return contrast >= 4.6 // Leave room for compositing and raster rounding.
    }
    var insufficient = support and 255
    if (meetsContrast(insufficient)) return support
    var strongest = if (darkInk) 255 else 0
    while (kotlin.math.abs(insufficient - strongest) > 1) {
        val middle = (insufficient + strongest) / 2
        if (meetsContrast(middle)) strongest = middle else insufficient = middle
    }
    return neutral(strongest)
}

private val transitionSupportLuminance = audioBlobMaterialLuminance(AUDIO_BLOB_TRANSITION_SUPPORT)
private val rasterDarkInkLuminance = audioBlobMaterialLuminance(0xff010101.toInt())
private val rasterLightInkLuminance = audioBlobMaterialLuminance(0xfffefefe.toInt())

private fun blobBlend(a: Int, b: Int, fraction: Float): Int {
    fun channel(shift: Int) = ((a ushr shift and 255) * (1f - fraction) + (b ushr shift and 255) * fraction).roundToInt()
    return -0x1000000 or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private fun blobMaterialSample(active: Int, paused: Int, life: Float, presence: Float,
    silence: Float, fallback: Boolean, radiusFraction: Float): Int {
    val mix = audioBlobColorLife(life, presence)
    val body = blobBlend(paused, active, mix)
    val drain = silence.coerceIn(0f, 1f) * presence * silenceBlobDrainWeight(radiusFraction) * (if (fallback) 1f else mix)
    return blobBlend(body, silenceBlobMutedColor(if (fallback) active else body), drain)
}

/** Conservative channel bounds avoid applying a neutral blend to material that already carries its ink. */
internal fun audioBlobInkNeedsSupport(primary: Int, tertiary: Int, paused: Int, life: Float, presence: Float,
    silence: Float, fallback: Boolean, darkInk: Boolean, radiusFraction: Float,
    covered: Boolean, background: Int,
    gradientMin: Float = 0f, gradientMax: Float = 1f, layerOpacity: Float = 1f): Boolean {
    // During fallback startup the muted active palette can be brighter than the partially mixed
    // body. Bound both radial endpoints for either ink polarity instead of assuming drain darkens.
    val first = blobBlend(primary, tertiary, gradientMin)
    val last = blobBlend(primary, tertiary, gradientMax)
    val a = blobMaterialSample(first, paused, life, presence, silence, fallback, 0f)
    val b = blobMaterialSample(last, paused, life, presence, silence, fallback, 0f)
    val c = blobMaterialSample(first, paused, life, presence, silence, fallback, radiusFraction)
    val d = blobMaterialSample(last, paused, life, presence, silence, fallback, radiusFraction)
    fun channel(shift: Int): Int {
        if (!darkInk) return maxOf(maxOf(a ushr shift and 255, b ushr shift and 255),
            maxOf(c ushr shift and 255, d ushr shift and 255))
        val material = minOf(minOf(a ushr shift and 255, b ushr shift and 255),
            minOf(c ushr shift and 255, d ushr shift and 255))
        return if (covered) material else minOf(material, background ushr shift and 255)
    }
    val bound = -0x1000000 or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    // The compact dimmed readout and its material are composited together over the backdrop.
    val inkLuminance = if (layerOpacity < 1f) {
        val ink = if (darkInk) 0xff010101.toInt() else 0xfffefefe.toInt()
        audioBlobMaterialLuminance(blobBlend(background, ink, layerOpacity))
    } else if (darkInk) rasterDarkInkLuminance else rasterLightInkLuminance
    fun safe(color: Int): Boolean {
        val underlay = if (layerOpacity < 1f) blobBlend(background, color, layerOpacity) else color
        // Paint alpha truncation and intermediate channel rounding can move the raster two codes
        // beyond the scalar sample; fully covered glyph samples can be one code off their ink.
        fun rasterChannel(shift: Int) = ((underlay ushr shift and 255) + if (darkInk) -2 else 2).coerceIn(0, 255)
        val raster = -0x1000000 or (rasterChannel(16) shl 16) or (rasterChannel(8) shl 8) or rasterChannel(0)
        val luminance = audioBlobMaterialLuminance(raster)
        return if (darkInk) (luminance + .05) / (inkLuminance + .05) >= 4.5
            else (inkLuminance + .05) / (luminance + .05) >= 4.5
    }
    return !safe(bound) || (!covered && !safe(background))
}

internal fun audioBlobColorLife(life: Float, presence: Float): Float {
    val t = ((life - .36f) / .5f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t) * presence
}

/** Sample the readout region, including the actual silence drain, before choosing ink polarity. */
internal fun audioBlobUsesDarkInk(
    primary: Int, tertiary: Int, paused: Int, life: Float, presence: Float,
    silence: Float = 0f, fallback: Boolean = false,
): Boolean {
    // Most ink spans the inner half-radius. Match each renderer's existing compositing equation;
    // the measured support still protects local gradient variation at individual glyphs.
    val sampled = blobMaterialSample(blobBlend(primary, tertiary, .5f), paused, life, presence, silence, fallback, .5f)
    return audioBlobMaterialLuminance(sampled) >= transitionSupportLuminance
}

internal fun audioBlobMaterialLuminance(color: Int): Double {
    fun linear(channel: Int): Double {
        val value = channel / 255.0
        return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
    }
    return .2126 * linear(color ushr 16 and 255) + .7152 * linear(color ushr 8 and 255) +
        .0722 * linear(color and 255)
}

/** Keep opaque theme pigments as shader inputs. The shared readout can correct the resulting
 * material for contrast against its backdrop ink, using the same policy at every size. */
internal fun audioBlobMaterialColor(color: Int): Int = color or -0x1000000
