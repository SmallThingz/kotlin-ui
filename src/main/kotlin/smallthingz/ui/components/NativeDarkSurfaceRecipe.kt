package smallthingz.ui.components

import kotlin.math.roundToInt

// Pure paint inputs shared by runtime surfaces and the platform-resource exporter.
internal const val MAX_DARK_SURFACE_LEVEL = 72f
internal const val DEFAULT_AMOLED_BACKGROUND_LEVEL = 0
internal const val DEFAULT_AMOLED_BACKGROUND_LIGHTNESS = DEFAULT_AMOLED_BACKGROUND_LEVEL / MAX_DARK_SURFACE_LEVEL

internal object NativeDarkSurfaceRecipe {
    const val amoledForegroundLightness = .25f
    const val amoledForegroundGrain = .5f

    fun foregroundScale(lightness: Float?, gray: Boolean, amoled: Boolean): Float =
        lightness?.let { it * MAX_DARK_SURFACE_LEVEL / if (gray) 61f else 54f }
            ?: if (amoled) 0f else 1f

    fun foregroundLevel(grayLevel: Int, tintedLevel: Int, gray: Boolean, scale: Float): Int =
        ((if (gray) grayLevel else tintedLevel) * scale).roundToInt().coerceAtMost(255)

    fun raisedLevel(gray: Boolean, scale: Float): Int = foregroundLevel(49, 42, gray, scale)

    /** Fixed identity paint, independent of the launcher's night mode or dynamic palette. */
    val amoledRaisedColor: Int
        get() {
            val level = raisedLevel(false, foregroundScale(amoledForegroundLightness, false, true))
            return (0xff shl 24) or (level * 0x010101)
        }
}
