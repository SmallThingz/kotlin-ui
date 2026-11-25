package smallthingz.ui.reverb

import android.content.Context
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import java.util.function.LongSupplier
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeSettingsVisualEnvironment
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density

typealias ReverbPalette = NativeColorScheme
typealias ReverbTextRole = NativeTypographyRole

/** Immutable resources and typography shared by native views in one Reverb window. */
class ReverbEnvironment private constructor(
    val theme: ReverbTheme,
    private val settingsTextContrast: Boolean,
) : NativeSettingsVisualEnvironment {
    internal constructor(theme: ReverbTheme) : this(theme, false)

    constructor(context: Context, darkTheme: Boolean) : this(ReverbDesign.theme(context, darkTheme), false)

    constructor(textDensity: Density, sharedFontResolver: FontFamily.Resolver, palette: ReverbPalette) :
        this(ReverbDesign.windowTheme(textDensity, sharedFontResolver, palette), false)

    override val appearance get() = theme.appearance
    override val textDensity get() = theme.density
    override val sharedFontResolver get() = theme.fontResolver
    override val palette get() = theme.palette
    val audioBlobStyle = appearance.audioBlob.resolve(palette, appearance.typography)

    fun forSettings(): ReverbEnvironment = if (settingsTextContrast) this else ReverbEnvironment(theme, true)

    fun withPalette(value: ReverbPalette): ReverbEnvironment =
        ReverbEnvironment(ReverbTheme(textDensity, sharedFontResolver, value, appearance), settingsTextContrast)

    @MainThread
    override fun paragraph(context: Context, text: String, role: ReverbTextRole, @ColorInt textColor: Int): ParagraphView =
        ParagraphView(context, initialStyle = appearance.typography[role], initialDensity = textDensity,
            initialFontResolver = sharedFontResolver, initialTextColor = textColor).also {
            it.setText(text)
            bindTextContrast(it, textColor)
        }

    @MainThread
    override fun bindParagraphStyle(view: ParagraphView, role: ReverbTextRole, @ColorInt textColor: Int) {
        view.setTypography(appearance.typography[role], textDensity, sharedFontResolver)
        bindTextContrast(view, textColor)
        view.animateTextColor(textColor)
    }

    private fun bindTextContrast(view: ParagraphView, color: Int) {
        if (!settingsTextContrast || (color != palette.onSurface && color != palette.onSurfaceVariant)) {
            view.paintedTextSurface = null
        } else if (view.paintedTextSurface == null) {
            view.paintedTextSurface = LongSupplier { ReverbTextSurface.sample(view) }
        }
    }

    companion object {
        /** Configurable primitive tests use deliberately contrasting recipes to verify binding. */
        @VisibleForTesting(otherwise = VisibleForTesting.NONE)
        fun withTestAppearance(textDensity: Density, sharedFontResolver: FontFamily.Resolver, palette: ReverbPalette,
                               appearance: NativeUiAppearance): ReverbEnvironment =
            ReverbEnvironment(ReverbTheme(textDensity, sharedFontResolver, palette, appearance), false)

        @VisibleForTesting(otherwise = VisibleForTesting.NONE)
        fun withTestAppearance(context: Context, darkTheme: Boolean, appearance: NativeUiAppearance): ReverbEnvironment =
            ReverbEnvironment(ReverbTheme(Density(context), smallthingz.ui.text.font.createFontFamilyResolver(context),
                appearance.resolveColorScheme(context, darkTheme), appearance), false)
    }
}
