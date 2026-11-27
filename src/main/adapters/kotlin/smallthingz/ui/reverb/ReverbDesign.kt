package smallthingz.ui.reverb

import android.content.Context
import androidx.annotation.MainThread
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.components.NativeDarkSurfacePreset
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density

/** Reverb's visual defaults. Semantic Reverb components resolve these recipes internally. */
object ReverbDesign {
    /** Fixed brand/launcher ring, independent of a window's forced light or dark theme. */
    val headerIconOutline get() = NativeDarkSurfacePreset.Amoled

    val appearance: NativeUiAppearance = NativeUiAppearance.Default.copy(
        toolbars = NativeUiAppearance.Default.toolbars.copy(compactHeightDp = 58f),
    )

    /** Shared top and bottom modal fill. Keep text, status and action roles unchanged. */
    fun modalSurface(palette: ReverbPalette): Int = appearance.modals.sheet.surfaceColor(palette)

    internal fun modalLinkSurface(palette: ReverbPalette): Int = appearance.modals.floating.link.fill(palette)

    fun windowBackdrop(palette: ReverbPalette): NoiseDrawable =
        appearance.backdrops.windowBackground(palette)

    fun bindWindowBackdrop(backdrop: NoiseDrawable, palette: ReverbPalette) {
        appearance.backdrops.bindWindowBackground(backdrop, palette)
    }

    fun statusBarProtection(context: Context) = appearance.modals.statusBar.let { style ->
        style.create(appearance.resolveColorScheme(context, darkTheme = !style.darkIcons))
    }

    /** Resolve contrasting colors from this appearance, retaining any explicit window grain policy. */
    fun statusBarProtection(context: Context, theme: ReverbTheme) =
        statusBarAppearance(context, theme).createProtection()

    internal fun statusBarAppearance(context: Context, theme: ReverbTheme) =
        theme.appearance.modals.statusBar.let { style ->
            val palette = theme.appearance.resolveColorScheme(context, darkTheme = !style.darkIcons)
            style.resolve(palette, theme.palette.surfacePreset ?: palette.surfacePreset)
        }

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

    /** Common semantic controls in the same window use this already-resolved theme. */
    val controlTheme: ControlTheme get() = controls

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
