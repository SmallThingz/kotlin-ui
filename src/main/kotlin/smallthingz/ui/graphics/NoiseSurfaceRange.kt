package smallthingz.ui.graphics

import smallthingz.ui.controls.textSurfaceRange

/** Conservative opaque bounds including texture interpolation and integer raster rounding. */
internal fun noiseSurfaceRange(color: Int, paintAlpha: Int): Long {
    if (color ushr 24 != 255) return 0L
    val grainAlpha = (17 * paintAlpha + 254) / 255
    if (grainAlpha == 0) return textSurfaceRange(color)
    val fillAlpha = 255 - grainAlpha
    var darkest = 0xff000000.toInt()
    var lightest = 0xff000000.toInt()
    for (shift in 0..16 step 8) {
        val channel = color ushr shift and 255
        darkest = darkest or ((channel * fillAlpha / 255) shl shift)
        lightest = lightest or (((channel * fillAlpha + 255 * grainAlpha + 254) / 255) shl shift)
    }
    return textSurfaceRange(darkest, lightest)
}

/** Composite a translucent fill and its grain over an already bounded opaque underlay. */
internal fun noiseSurfaceRangeOver(surface: Long, fill: Int, paintAlpha: Int): Long {
    val darkest = (surface ushr 32).toInt()
    val lightest = surface.toInt()
    if (darkest ushr 24 != 255 || lightest ushr 24 != 255) return 0L
    fun composite(background: Int): Int {
        val alpha = fill ushr 24
        fun channel(shift: Int) = (((fill ushr shift and 255) * alpha +
            (background ushr shift and 255) * (255 - alpha) + 127) / 255) shl shift
        return -0x1000000 or channel(16) or channel(8) or channel(0)
    }
    return textSurfaceRange((noiseSurfaceRange(composite(darkest), paintAlpha) ushr 32).toInt(),
        noiseSurfaceRange(composite(lightest), paintAlpha).toInt())
}
