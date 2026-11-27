package smallthingz.ui.reverb.onboarding

import smallthingz.ui.reverb.ReverbGlyphView
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbPalette
import smallthingz.ui.reverb.ReverbTextSurface
import smallthingz.ui.components.NativeDetailCardMarker
import smallthingz.ui.components.NativeDetailCardRow
import smallthingz.ui.components.NativeDetailCardStyle
import smallthingz.ui.components.NativeMaterialSwitchView
import smallthingz.ui.text.view.NativeMergedRowRole
import android.content.Context
import android.view.View
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.unit.Density
import androidx.core.view.ViewCompat

/** Exact released roles supplied by the screen owner; no platform font/style substitution. */
data class OnboardingPageTypography(
    val headlineMedium: TextStyle,
    val titleMedium: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
)

internal fun createOnboardingMarker(
    context: Context,
    style: TextStyle,
    density: Density,
    resolver: FontFamily.Resolver,
    cardStyle: NativeDetailCardStyle,
    icon: ReverbIcon? = null,
    marker: String? = null,
): NativeDetailCardMarker {
    val glyph = icon?.let { ReverbGlyphView(context, it, 0) }
    val tint = glyph?.let { view -> { color: Int -> view.bind(checkNotNull(icon), color) } }
    return NativeDetailCardMarker(context, style, density, resolver, cardStyle, marker, glyph, tint,
        ReverbTextSurface::invalidate)
}

class OnboardingBufferRow(
    context: Context,
    typography: OnboardingPageTypography,
    density: Density,
    resolver: FontFamily.Resolver,
    icon: ReverbIcon,
    initialPalette: ReverbPalette,
    inputRoot: () -> View?,
    cardStyle: NativeDetailCardStyle,
) : NativeDetailCardRow(context, typography.titleMedium, typography.bodyMedium, density, resolver,
    createOnboardingMarker(context, typography.titleMedium, density, resolver, cardStyle, icon = icon),
    NativeMaterialSwitchView(context, initialPalette.switchPalette), 52, 32, inputRoot, cardStyle,
    NativeMergedRowRole.SWITCH) {
    fun bind(title: String, body: String, checked: Boolean, visuallyEnabled: Boolean,
             interactionEnabled: Boolean, palette: ReverbPalette) {
        ViewCompat.setStateDescription(this, context.getString(
            if (checked) smallthingz.ui.R.string.state_on else smallthingz.ui.R.string.state_off))
        val toggle = trailing as NativeMaterialSwitchView
        toggle.bindSurfacePreset(palette.surfacePreset)
        toggle.setPalette(palette.switchPalette)
        toggle.setState(checked, visuallyEnabled)
        bindCard(title, body, visuallyEnabled && interactionEnabled, palette, checked)
    }
}

internal fun exact(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
internal fun atMost(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), View.MeasureSpec.AT_MOST)
internal fun unspecified(): Int = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
