package smallthingz.ui.reverb

import android.content.res.Resources
import android.graphics.drawable.Drawable
import androidx.annotation.MainThread
import smallthingz.ui.R
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeDarkSurfacePreset
import kotlin.math.roundToInt

/** Add the shared thin control outline to the circular toolbar mark. */
@MainThread
fun ReverbBrandMarkView.setReverbBrandOutline(enabled: Boolean) {
    val preset = NativeDarkSurfacePreset.Amoled
    setMarkPlateOutline(preset.borderColor, if (enabled) preset.borderWidthDp else 0f)
}

/** The legacy bind retains the fixed dark brand palette when an older host supplies a window palette. */
@Deprecated("The mark uses fixed dark brand resources. Call refreshResourcePalette() to reload the artwork colors.")
@MainThread
@Suppress("UNUSED_PARAMETER")
fun ReverbBrandMarkView.bindReverbBrandSurface(palette: NativeColorScheme) = refreshResourcePalette()

internal fun ReverbBrandMarkView.bindDefaultReverbBrandSurface() {
    bindMarkGrain((NativeDarkSurfacePreset.Amoled.foregroundGrain * 255f).roundToInt(), createReverbBrandGrain)
}

// The unqualified nodpi bitmap has one configuration-independent pixel tile. Keep its
// constant state, while each mark owns its bounds, Paint, and foreground-grain opacity.
private var grainState: Drawable.ConstantState? = null
private val createReverbBrandGrain: (Resources) -> Drawable = { resources ->
    val state = grainState ?: checkNotNull(resources.getDrawable(R.drawable.reverb_brand_grain, null).constantState)
        .also { grainState = it }
    state.newDrawable(resources).mutate()
}
