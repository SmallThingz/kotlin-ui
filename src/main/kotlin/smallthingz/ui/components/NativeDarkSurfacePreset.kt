package smallthingz.ui.components

import androidx.annotation.ColorInt
import kotlin.math.roundToInt

/** Dark fills, grain opacity, and neutral outline paint for an app's surface controls. */
data class NativeDarkSurfacePreset(
    val style: NativeDarkSurfaces,
    val backgroundLightness: Float,
    val foregroundLightness: Float,
    override val backgroundGrain: Float,
    override val foregroundGrain: Float,
    override val borderWidthDp: Float,
    val borderLightness: Float,
) : NativeSurfacePreset {
    init {
        require(backgroundLightness in 0f..1f && foregroundLightness in 0f..1f)
        require(backgroundGrain in 0f..1f && foregroundGrain in 0f..1f)
        require(borderWidthDp.isFinite() && borderWidthDp >= 0f && borderLightness in 0f..1f)
    }

    @get:ColorInt
    override val borderColor: Int
        get() = (0xff shl 24) or ((borderLightness * 255f).roundToInt() * 0x010101)

    /** Apply the fills over a Material resolver while retaining its accent, content, and light colors. */
    fun colorScheme(base: NativeColorSchemeResolver = NativeColorScheme, tintStrength: Float = .45f): NativeColorSchemeResolver {
        val surfaces = base.withDarkSurfaces(style, tintStrength = tintStrength, backgroundLightness = backgroundLightness,
            foregroundLightness = foregroundLightness)
        return NativeColorSchemeResolver { dark, sdk, readColor ->
            val colors = surfaces.resolve(dark, sdk, readColor)
            if (dark) colors.copy(surfacePreset = this) else colors
        }
    }

    companion object {
        val Amoled = NativeDarkSurfacePreset(NativeDarkSurfaces.AMOLED,
            backgroundLightness = DEFAULT_AMOLED_BACKGROUND_LIGHTNESS, foregroundLightness = .25f,
            backgroundGrain = 1f, foregroundGrain = .5f,
            borderWidthDp = 1f, borderLightness = .15f)
    }
}
