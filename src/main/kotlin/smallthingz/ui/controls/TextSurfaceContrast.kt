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
