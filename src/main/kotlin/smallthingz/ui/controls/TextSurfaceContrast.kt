package smallthingz.ui.controls

/** Opaque sRGB luminance bounds of the paint beneath a plain label; equal colors mean a flat fill. */
fun textSurfaceRange(darkest: Int, lightest: Int = darkest): Long =
    (darkest.toLong() shl 32) or (lightest.toLong() and 0xffffffffL)

/**
 * Keep readable caller ink; otherwise choose the uniform black/white ink with the largest worst
 * contrast across the actual surface range. A textured midpoint may make 4.5 unattainable; this
 * maximizes its achievable contrast without changing the texture or claiming that its base is every
 * pixel. Translucent ink/unknown surfaces retain the caller's alpha and color.
 */
fun textSurfaceForeground(surface: Long, preferred: Int): Int {
    val first = (surface ushr 32).toInt()
    val second = surface.toInt()
    if (preferred ushr 24 != 255 || first ushr 24 != 255 || second ushr 24 != 255) return preferred
    val a = srgbLuminance(first)
    val b = srgbLuminance(second)
    val low = minOf(a, b)
    val high = maxOf(a, b)
    val ink = srgbLuminance(preferred)
    val contrast = when {
        ink < low -> (low + .05) / (ink + .05)
        ink > high -> (ink + .05) / (high + .05)
        else -> 1.0
    }
    if (contrast >= 4.5) return preferred
    return if ((low + .05) / .05 >= 1.05 / (high + .05)) 0xff000000.toInt() else 0xffffffff.toInt()
}

/** Qualify a ripple's complete opacity prefix against both extrema of a textured underlay. */
internal fun textSurfaceForegroundWithIndication(surface: Long, preferred: Int, indication: Int): Long {
    val darkest = (surface ushr 32).toInt()
    val lightest = surface.toInt()
    var minimum = srgbLuminance(darkest)
    var maximum = srgbLuminance(lightest)
    val preferredLuminance = srgbLuminance(preferred)
    fun candidate(): Int {
        val nearest = preferredLuminance.coerceIn(minimum, maximum)
        return when {
            preferred ushr 24 == 255 && (maxOf(preferredLuminance, nearest) + .05) / (minOf(preferredLuminance, nearest) + .05) >= 4.5 -> preferred
            1.05 / (maximum + .05) >= 4.5 -> -1
            (minimum + .05) / .05 >= 4.5 -> -0x1000000
            else -> 0
        }
    }
    var ink = candidate().takeIf { it != 0 } ?: textSurfaceForeground(surface, preferred)
    var accepted = 0
    for (alpha in 1..(indication ushr 24)) {
        val range = smallthingz.ui.graphics.noiseSurfaceRangeOver(surface,
            (indication and 0x00ffffff) or (alpha shl 24), 0)
        minimum = minOf(minimum, srgbLuminance((range ushr 32).toInt()))
        maximum = maxOf(maximum, srgbLuminance(range.toInt()))
        val next = candidate()
        if (next == 0) break
        ink = next
        accepted = alpha
    }
    return (accepted.toLong() shl 32) or (ink.toLong() and 0xffffffffL)
}
