package smallthingz.ui.reverb

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.annotation.MainThread
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeColorSchemeResolver
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density

/** Reverb's visual defaults. Semantic Reverb components resolve these recipes internally. */
object ReverbDesign {
    val appearance: NativeUiAppearance = NativeUiAppearance.Default.copy(
        colorScheme = NativeColorSchemeResolver { dark, sdk, readColor ->
            val base = NativeColorScheme.resolve(dark, sdk, readColor)
            base.copy(background = darken(base.background, .04f), surface = darken(base.surface, .04f))
        },
        toolbars = NativeUiAppearance.Default.toolbars.copy(compactHeightDp = 58f),
    )

    /** Shared top and bottom modal fill. Keep text, status and action roles unchanged. */
    fun modalSurface(palette: ReverbPalette): Int = darken(palette.surfaceContainerLow, .08f)

    internal fun modalLinkSurface(palette: ReverbPalette): Int = darken(palette.surfaceContainerHighest, .15f)

    fun windowBackdrop(palette: ReverbPalette): NoiseDrawable =
        NoiseDrawable(palette.background, appearance.backdrops.windowGrainSeed)

    fun bindWindowBackdrop(backdrop: NoiseDrawable, palette: ReverbPalette) {
        backdrop.setBackgroundColor(palette.background)
    }

    fun statusBarProtection(context: Context) = appearance.modals.statusBar.let { style ->
        style.create(appearance.resolveColorScheme(context, darkTheme = !style.darkIcons))
    }

    private fun darken(color: Int, amount: Float): Int = ColorUtils.blendARGB(color, Color.BLACK, amount)

    fun theme(context: Context, dark: Boolean): ReverbTheme = windowTheme(
        Density(context), createFontFamilyResolver(context), appearance.resolveColorScheme(context, dark),
    )

    /** Adapt an existing window's resolved resources and palette without taking a style override. */
    fun windowTheme(density: Density, fontResolver: FontFamily.Resolver, palette: NativeColorScheme): ReverbTheme =
        ReverbTheme(density, fontResolver, palette)
}

/** Window resource and text configuration. Construct a new value when the configuration changes. */
class ReverbTheme internal constructor(
    val density: Density,
    val fontResolver: FontFamily.Resolver,
    val palette: NativeColorScheme,
    internal val appearance: NativeUiAppearance = ReverbDesign.appearance,
) {
    /** Resolved theme for common controls in the same window. */
    val controls: ControlTheme = ControlTheme(appearance, palette, density, fontResolver)

    @MainThread
    fun paragraph(context: Context, text: String, role: NativeTypographyRole): ParagraphView =
        ParagraphView(context, appearance.typography[role], density, fontResolver, palette.ink).also { it.setText(text) }

    @MainThread
    fun bindParagraph(view: ParagraphView, role: NativeTypographyRole) {
        view.setTypography(appearance.typography[role], density, fontResolver)
        view.animateTextColor(palette.ink)
    }
}

/** Paragraph construction shared by Reverb components and existing native host adapters. */
@MainThread
fun createReverbParagraph(
    context: Context,
    text: String,
    role: NativeTypographyRole,
    density: Density,
    fontResolver: FontFamily.Resolver,
    color: Int,
): ParagraphView = ParagraphView(
    context = context,
    initialStyle = ReverbDesign.appearance.typography[role],
    initialDensity = density,
    initialFontResolver = fontResolver,
    initialTextColor = color,
).also { it.setText(text) }
