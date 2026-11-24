package smallthingz.ui.components

import androidx.annotation.ColorInt
import kotlin.math.roundToInt

/** Dark surface treatments. Content, accent, and feedback colors belong to the base scheme. */
enum class NativeDarkSurfaces { GRAY, TINTED, AMOLED }

private const val MAX_DARK_SURFACE_LEVEL = 72f

/** Wrap a resolver to customize its dark surfaces while preserving its light scheme. */
fun NativeColorSchemeResolver.withDarkSurfaces(
    style: NativeDarkSurfaces,
    @ColorInt tint: Int? = null,
    tintStrength: Float = 0.45f,
    backgroundLightness: Float? = null,
    foregroundLightness: Float? = null,
): NativeColorSchemeResolver {
    requireSurfaceSettings(tintStrength, backgroundLightness, foregroundLightness)
    return NativeColorSchemeResolver { dark, sdk, readColor ->
        val base = resolve(dark, sdk, readColor)
        if (dark) base.withDarkSurfaces(style, tint ?: base.primary, tintStrength, backgroundLightness, foregroundLightness) else base
    }
}

/** Replace the dark surface roles, with separate 0–1 lightness for pages and raised surfaces. */
fun NativeColorScheme.withDarkSurfaces(
    style: NativeDarkSurfaces,
    @ColorInt tint: Int = primary,
    tintStrength: Float = 0.45f,
    backgroundLightness: Float? = null,
    foregroundLightness: Float? = null,
): NativeColorScheme {
    requireSurfaceSettings(tintStrength, backgroundLightness, foregroundLightness)
    val red = tint ushr 16 and 255
    val green = tint ushr 8 and 255
    val blue = tint and 255
    val peak = maxOf(red, green, blue, 1)

    fun tone(level: Int): Int {
        fun channel(value: Int): Int = if (style == NativeDarkSurfaces.TINTED)
            (level * (1f - tintStrength + tintStrength * value / peak)).roundToInt() else level
        return (0xff shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
    }

    val backdropLevel = backgroundLightness?.let { (it * MAX_DARK_SURFACE_LEVEL).roundToInt() }
        ?: when (style) {
            NativeDarkSurfaces.GRAY -> 27
            NativeDarkSurfaces.TINTED -> 23
            NativeDarkSurfaces.AMOLED -> 0
        }
    val gray = style == NativeDarkSurfaces.GRAY
    // Scale the foreground roles together so changing lightness preserves their relative depth.
    val foregroundScale = foregroundLightness?.let { it * MAX_DARK_SURFACE_LEVEL / if (gray) 61f else 54f }
        ?: if (style == NativeDarkSurfaces.AMOLED) 0f else 1f
    fun foreground(grayLevel: Int, tintedLevel: Int) =
        tone(((if (gray) grayLevel else tintedLevel) * foregroundScale).roundToInt().coerceAtMost(255))

    val backdrop = tone(backdropLevel)
    return copy(
        background = backdrop,
        surface = backdrop,
        surfaceVariant = foreground(54, 47),
        surfaceTint = foreground(192, 176),
        surfaceBright = foreground(61, 54),
        surfaceDim = backdrop,
        surfaceContainer = foreground(41, 35),
        surfaceContainerHigh = foreground(49, 42),
        surfaceContainerHighest = foreground(57, 50),
        surfaceContainerLow = foreground(34, 29),
        surfaceContainerLowest = foreground(19, 15),
    )
}

private fun requireSurfaceSettings(tint: Float, background: Float?, foreground: Float?) {
    require(tint in 0f..1f)
    require(background == null || background in 0f..1f)
    require(foreground == null || foreground in 0f..1f)
}
