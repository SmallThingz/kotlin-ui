package smallthingz.ui.reverb

import android.content.res.Resources
import android.graphics.drawable.Drawable
import androidx.annotation.MainThread
import smallthingz.ui.R
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeDarkSurfacePreset
import smallthingz.ui.components.NativeGroupSurfaceRecipes
import smallthingz.ui.components.NativeLayeredMarkPalette
import kotlin.math.roundToInt

/** Use the Library recording-info foreground from the same resolved palette as the host. */
@MainThread
fun ReverbBrandMarkView.bindReverbBrandSurface(palette: NativeColorScheme) {
    bindMarkPlate(
        NativeLayeredMarkPalette(
            NativeGroupSurfaceRecipes.Default.recordingInfoRow.fillColor(palette),
            palette.primary,
            palette.ink,
        ),
        ((palette.surfacePreset?.foregroundGrain ?: 0f) * 255f).roundToInt(),
        createReverbBrandGrain,
    )
}

internal fun ReverbBrandMarkView.bindDefaultReverbBrandSurface() {
    bindMarkPlate(null, (NativeDarkSurfacePreset.Amoled.foregroundGrain * 255f).roundToInt(), createReverbBrandGrain)
}

// The unqualified nodpi bitmap has one configuration-independent pixel tile. Keep its
// constant state, while each mark owns its bounds, Paint, and foreground-grain opacity.
private var grainState: Drawable.ConstantState? = null
private val createReverbBrandGrain: (Resources) -> Drawable = { resources ->
    val state = grainState ?: checkNotNull(resources.getDrawable(R.drawable.reverb_brand_grain, null).constantState)
        .also { grainState = it }
    state.newDrawable(resources).mutate()
}
