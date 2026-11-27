package smallthingz.ui.components

import android.content.Context
import android.util.TypedValue
import android.view.View
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.graphics.NoiseDrawable

/** Backdrops for a rendered page and its framework window during handoff. */
data class NativeBackdropStyle(
    val pageGrainSeed: Int? = NoiseDrawable.SEED_BACKGROUND,
    val windowGrainSeed: Int? = NoiseDrawable.SEED_BACKGROUND,
    val panelScrimAlpha: Float = .32f,
) {
    fun pageBackground(palette: NativeColorScheme) =
        NoiseDrawable(palette.background, pageGrainSeed, palette.backgroundGrain)

    fun panelScrimColor(palette: NativeColorScheme): Int = srgbColorWithAlpha(palette.scrim, panelScrimAlpha)

    fun attachPage(view: View, palette: NativeColorScheme, onPaintChanged: () -> Unit = {}): NativeBackdropBinding =
        NativeBackdropBinding(view, pageGrainSeed, palette.background,
            palette.backgroundGrain, onPaintChanged)

    fun bindPage(binding: NativeBackdropBinding, palette: NativeColorScheme) =
        binding.bind(pageGrainSeed, palette.background, palette.backgroundGrain)

    /** Window handoff using the app's already-resolved palette, including its grain policy. */
    fun windowBackground(palette: NativeColorScheme): NoiseDrawable =
        NoiseDrawable(palette.background, windowGrainSeed, palette.backgroundGrain)

    /** Repaint a retained window drawable without replacing its bounds or callback. */
    fun bindWindowBackground(drawable: NoiseDrawable, palette: NativeColorScheme) {
        drawable.setBackgroundColor(palette.background)
        drawable.setNoiseIntensity(palette.backgroundGrain)
        drawable.setGrainSeed(windowGrainSeed)
    }

    /** Framework-theme fallback for callers without an app-resolved palette. */
    fun windowBackground(context: Context): NoiseDrawable? {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(android.R.attr.colorBackground, value, true)) return null
        val color = if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
        return NoiseDrawable(color, windowGrainSeed)
    }

    companion object {
        val Default = NativeBackdropStyle()
    }
}

/** One retained drawable; only its fill color animates. */
open class NativeBackdropBinding(
    view: View,
    private var grainSeed: Int?,
    initialColor: Int,
    initialGrainIntensity: Float = 1f,
    private val onPaintChanged: () -> Unit = {},
) {
    constructor(view: View, grainSeed: Int?, initialColor: Int, onPaintChanged: () -> Unit) :
        this(view, grainSeed, initialColor, 1f, onPaintChanged)

    private val drawable = NoiseDrawable(initialColor, grainSeed, initialGrainIntensity)
    private var grainIntensity = initialGrainIntensity
    private var published = initialColor
    private val motion: ViewColorTransition = ViewColorTransition(view, intArrayOf(initialColor), ::publish)

    init { view.background = drawable }

    internal fun transitionUnderlay(targetColor: Int): Long =
        if (published == targetColor) 0L else drawable.paintedTextSurface

    fun bind(grainSeed: Int?, color: Int, intensity: Float = 1f) {
        require(intensity in 0f..1f)
        if (grainIntensity != intensity) {
            grainIntensity = intensity
            drawable.setNoiseIntensity(intensity)
            onPaintChanged()
        }
        if (this.grainSeed != grainSeed) {
            this.grainSeed = grainSeed
            drawable.setGrainSeed(grainSeed)
            onPaintChanged()
        }
        motion.retarget(color)
    }

    private fun publish() {
        val color = motion.color(0)
        if (color == published) return
        published = color
        drawable.setBackgroundColor(color)
        onPaintChanged()
    }
}
