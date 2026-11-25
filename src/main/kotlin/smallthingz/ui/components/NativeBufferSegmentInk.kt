// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Home's indication is a foreground: it washes both the text and the surface beneath it. */
class NativeBufferSegmentInk {
    private var field = 0
    private var indication = 0
    private var endpointFloor = 4.5
    private var targetFloor = 4.5
    private var backgroundDark = 0
    private var backgroundLight = 0
    private var backgroundLow = 0.0
    private var backgroundHigh = 0.0

    fun target(field: Int, indication: Int, fill: Int, foreground: Int) {
        this.field = field
        this.indication = indication
        prepareBackground(fill)
        targetFloor = min(4.5, contrastBound(foreground))
        // A reversal retains the intentional dimming of either unfinished endpoint.
        endpointFloor = min(endpointFloor, targetFloor)
    }

    fun foreground(fill: Int, animated: Int, running: Boolean): Int {
        if (!running) {
            endpointFloor = targetFloor
            return animated
        }
        prepareBackground(fill)
        return qualifiedForeground(animated)
    }

    /** Textured Home surfaces supply the composed parent and tab paint, including while settled. */
    internal fun target(surface: Long, indication: Int, foreground: Int) {
        this.indication = indication
        prepareBackground(surface)
        targetFloor = if (foreground ushr 24 == 255) 4.5 else min(4.5, contrastBound(foreground))
        endpointFloor = min(endpointFloor, targetFloor)
    }

    internal fun foreground(surface: Long, animated: Int, running: Boolean): Int {
        if (surface ushr 56 != 255L || (surface ushr 24 and 255L) != 255L) return animated
        prepareBackground(surface)
        if (!running) {
            targetFloor = if (animated ushr 24 == 255) 4.5 else min(4.5, contrastBound(animated))
            endpointFloor = targetFloor
        }
        return qualifiedForeground(animated)
    }

    private fun qualifiedForeground(animated: Int): Int {
        if (contrastBound(animated) >= endpointFloor) return animated
        val dark = animated and -0x1000000
        val light = dark or 0xFFFFFF
        val darkContrast = contrastBound(dark)
        val lightContrast = contrastBound(light)
        // Fixed foreground overlays can make 4.5 unattainable mid-transition. The guaranteed
        // floor is min(endpointFloor, max(darkContrast, lightContrast)), not an impossible 4.5.
        return if (darkContrast >= lightContrast) dark else light
    }

    private fun prepareBackground(fill: Int) {
        val background = composite(fill, field, fill ushr 24)
        prepareBackground(smallthingz.ui.controls.textSurfaceRange(background))
    }

    private fun prepareBackground(surface: Long) {
        backgroundDark = (surface ushr 32).toInt()
        backgroundLight = surface.toInt()
        backgroundLow = bufferSegmentSrgbLuminance(channelMinimum(backgroundDark, overlay(backgroundDark)))
        backgroundHigh = bufferSegmentSrgbLuminance(channelMaximum(backgroundLight, overlay(backgroundLight)))
    }

    private fun contrastBound(foreground: Int): Double {
        val darkInk = composite(foreground, backgroundDark, foreground ushr 24)
        val lightInk = composite(foreground, backgroundLight, foreground ushr 24)
        val inkLow = bufferSegmentSrgbLuminance(channelMinimum(darkInk, overlay(darkInk)))
        val inkHigh = bufferSegmentSrgbLuminance(channelMaximum(lightInk, overlay(lightInk)))
        return when {
            inkLow >= backgroundHigh -> (inkLow + .05) / (backgroundHigh + .05)
            backgroundLow >= inkHigh -> (backgroundLow + .05) / (inkHigh + .05)
            else -> 1.0
        }
    }

    // Hover/focus and press are the only Home state layers, each bounded by 26/255.
    // Every channel stays between its unoverlaid value and this maximum combined overlay.
    // Taking separate channel extrema also bounds tinted palettes whose channels move oppositely.
    private fun overlay(color: Int) = composite(indication, composite(indication, color, 26), 26)

    private fun channelMinimum(a: Int, b: Int): Int =
        (min(a ushr 16 and 255, b ushr 16 and 255) shl 16) or
            (min(a ushr 8 and 255, b ushr 8 and 255) shl 8) or min(a and 255, b and 255)

    private fun channelMaximum(a: Int, b: Int): Int =
        (max(a ushr 16 and 255, b ushr 16 and 255) shl 16) or
            (max(a ushr 8 and 255, b ushr 8 and 255) shl 8) or max(a and 255, b and 255)

    private fun composite(front: Int, back: Int, alpha: Int): Int {
        fun channel(shift: Int): Int =
            (((front ushr shift and 255) * alpha + (back ushr shift and 255) * (255 - alpha) + 127) / 255) shl shift
        return -0x1000000 or channel(16) or channel(8) or channel(0)
    }
}

/** Byte channels share one lazily initialized table; its mutable storage never escapes. */
private object SrgbChannelTable {
    val values = DoubleArray(256) { channel ->
        val value = channel / 255.0
        if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
}

internal fun bufferSegmentSrgbLinearChannel(channel: Int): Double = SrgbChannelTable.values[channel]

/** Relative luminance of RGB bytes; callers composite alpha before evaluating contrast. */
internal fun bufferSegmentSrgbLuminance(color: Int): Double =
    0.2126 * bufferSegmentSrgbLinearChannel(color ushr 16 and 255) +
        0.7152 * bufferSegmentSrgbLinearChannel(color ushr 8 and 255) +
        0.0722 * bufferSegmentSrgbLinearChannel(color and 255)
