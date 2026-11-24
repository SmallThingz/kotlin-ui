package smallthingz.ui.components

import androidx.annotation.ColorInt
import kotlin.math.roundToInt

/** Dark fills, grain opacity, and neutral outline paint for an app's surface controls. */
data class NativeDarkSurfacePreset(
    val style: NativeDarkSurfaces,
    val backgroundLightness: Float,
    val foregroundLightness: Float,
    val backgroundGrain: Float,
    val foregroundGrain: Float,
    val borderWidthDp: Float,
    val borderLightness: Float,
) {
    init {
        require(backgroundLightness in 0f..1f && foregroundLightness in 0f..1f)
        require(backgroundGrain in 0f..1f && foregroundGrain in 0f..1f)
        require(borderWidthDp.isFinite() && borderWidthDp >= 0f && borderLightness in 0f..1f)
    }

    @get:ColorInt
    val borderColor: Int
        get() = (0xff shl 24) or ((borderLightness * 255f).roundToInt() * 0x010101)

    /** Apply the fills over a Material resolver while retaining its accent, content, and light colors. */
    fun colorScheme(base: NativeColorSchemeResolver = NativeColorScheme, tintStrength: Float = .45f): NativeColorSchemeResolver =
        base.withDarkSurfaces(style, tintStrength = tintStrength, backgroundLightness = backgroundLightness,
            foregroundLightness = foregroundLightness)

    companion object {
        val Amoled = NativeDarkSurfacePreset(NativeDarkSurfaces.AMOLED,
            backgroundLightness = 0f, foregroundLightness = .25f,
            backgroundGrain = 1f, foregroundGrain = .5f,
            borderWidthDp = 1f, borderLightness = .15f)
    }
}
