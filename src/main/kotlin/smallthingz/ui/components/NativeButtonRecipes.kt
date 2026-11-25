package smallthingz.ui.components

import android.graphics.Color
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.TextStyle

/** Resolved theme roles shared by the retained actions in a window. */
data class NativeButtonPalette(
    val accent: Int,
    val onAccent: Int,
    val content: Int,
    val muted: Int,
    val quietSurface: Int,
    val tonalSurface: Int,
    val onTonal: Int,
    val errorSurface: Int,
    val onError: Int,
    val raisedSurface: Int = quietSurface,
    internal val surfacePreset: NativeSurfacePreset? = null,
)

enum class NativeButtonColorRole {
    TRANSPARENT, ACCENT, ON_ACCENT, CONTENT, MUTED, QUIET_SURFACE, TONAL_SURFACE, ON_TONAL, ERROR_SURFACE, ON_ERROR,
    RAISED_SURFACE;

    internal fun resolve(palette: NativeButtonPalette, opacity: Float? = null): Int {
        val color = when (this) {
            TRANSPARENT -> Color.TRANSPARENT
            ACCENT -> palette.accent
            ON_ACCENT -> palette.onAccent
            CONTENT -> palette.content
            MUTED -> palette.muted
            QUIET_SURFACE -> palette.quietSurface
            TONAL_SURFACE -> palette.tonalSurface
            ON_TONAL -> palette.onTonal
            ERROR_SURFACE -> palette.errorSurface
            ON_ERROR -> palette.onError
            RAISED_SURFACE -> palette.raisedSurface
        }
        return if (opacity == null) color else (color and 0x00ffffff) or
            ((opacity.coerceIn(0f, 1f) * 255f + .5f).toInt() shl 24)
    }
}

/** Semantic paint chosen when an action is bound, without a per-bind style allocation. */
data class NativeButtonPaint(
    val container: NativeButtonColorRole,
    val content: NativeButtonColorRole,
    val containerOpacity: Float? = null,
    val contentOpacity: Float? = null,
    val indication: NativeButtonColorRole? = null,
)

/** Geometry, typography and state paint for one retained button. */
data class NativeButtonRecipe(
    val surface: NativeMaterialButtonStyle,
    val enabledPaint: NativeButtonPaint,
    val disabledPaint: NativeButtonPaint,
    val quietPaint: NativeButtonPaint? = null,
    val elevated: Boolean = false,
    val labelStyle: TextStyle = NativeTypography.Default.buttonLabelStyle,
    val minimumTouchTargetDp: Int = 48,
    val surroundingSurface: NativeButtonColorRole = NativeButtonColorRole.TRANSPARENT,
    val disabledSurroundingSurface: NativeButtonColorRole? = null,
    val busyPaint: NativeButtonPaint? = null,
) {
    internal fun paint(enabled: Boolean, emphasized: Boolean, busy: Boolean = false): NativeButtonPaint = when {
        busy && busyPaint != null -> busyPaint
        !enabled -> disabledPaint
        emphasized -> enabledPaint
        else -> quietPaint ?: enabledPaint
    }

    fun contentColor(palette: NativeButtonPalette, enabled: Boolean, busy: Boolean = false): Int =
        paint(enabled, emphasized = true, busy = busy).let { it.content.resolve(palette, it.contentOpacity) }

    fun surroundingColor(palette: NativeButtonPalette, enabled: Boolean = true): Int =
        (if (enabled) surroundingSurface else disabledSurroundingSurface ?: surroundingSurface).resolve(palette)
}

/** Button paint and a distinct spinner role for a pending action. */
data class NativeProgressButtonRecipe(
    val button: NativeButtonRecipe,
    val progress: NativeButtonColorRole = NativeButtonColorRole.ACCENT,
    val surroundingHeightDp: Int? = null,
) {
    fun progressColor(palette: NativeButtonPalette): Int = progress.resolve(palette)

    fun bindSurroundingSurface(surface: NativeGroupLayout, palette: NativeButtonPalette, enabled: Boolean) {
        surface.setSurfacePreset(palette.surfacePreset)
        surface.setPalette(button.surroundingColor(palette, enabled), Color.TRANSPARENT)
    }
}

private val filledSurface = NativeMaterialButtonStyle(24)
private val textSurface = NativeMaterialButtonStyle(12)
private val disabledFill = NativeButtonPaint(NativeButtonColorRole.CONTENT, NativeButtonColorRole.CONTENT,
    containerOpacity = .12f, contentOpacity = .38f)
private val disabledText = NativeButtonPaint(NativeButtonColorRole.TRANSPARENT, NativeButtonColorRole.CONTENT,
    contentOpacity = .38f)
private val accentText = NativeButtonPaint(NativeButtonColorRole.TRANSPARENT, NativeButtonColorRole.ACCENT,
    indication = NativeButtonColorRole.ACCENT)
private val enabledIcon = NativeButtonPaint(NativeButtonColorRole.TRANSPARENT, NativeButtonColorRole.MUTED,
    indication = NativeButtonColorRole.CONTENT)
private val roundedIconSurface = NativeMaterialButtonStyle(0, verticalPaddingDp = 0,
    minimumWidthDp = 48, minimumHeightDp = 48, iconSizeDp = 24, radiusDp = 14f, showLabel = false)
private val joinedActionSurface = NativeMaterialButtonStyle(horizontalPaddingDp = 12, iconSizeDp = 18, radiusDp = 0f,
    minimumWidthDp = 48, minimumHeightDp = 48)

/** Joined confirm/cancel controls over one raised surface. */
data class NativeTrimActionRecipes(
    val cornerRadiusDp: Float = 14f,
    val cancel: NativeButtonRecipe = trimCancel(NativeTypography.Default[NativeTypographyRole.LABEL_LARGE]),
    val save: NativeProgressButtonRecipe = trimSave(NativeTypography.Default[NativeTypographyRole.LABEL_LARGE]),
) {
    internal constructor(labelStyle: TextStyle) : this(cancel = trimCancel(labelStyle), save = trimSave(labelStyle))

    fun bindSurface(surface: NativeGroupLayout, palette: NativeColorScheme) {
        surface.setSurfacePreset(palette.surfacePreset)
        surface.setPalette(palette.surfaceContainerHigh, palette.border)
    }
}

private fun trimCancel(labelStyle: TextStyle): NativeButtonRecipe {
    val paint = NativeButtonPaint(NativeButtonColorRole.TRANSPARENT, NativeButtonColorRole.CONTENT)
    return NativeButtonRecipe(joinedActionSurface, paint, paint, labelStyle = labelStyle)
}

private fun trimSave(labelStyle: TextStyle): NativeProgressButtonRecipe = NativeProgressButtonRecipe(
    NativeButtonRecipe(joinedActionSurface, accentText,
        NativeButtonPaint(NativeButtonColorRole.TRANSPARENT, NativeButtonColorRole.MUTED),
        labelStyle = labelStyle, busyPaint = accentText))

private fun defaultRangeExport(labelStyle: TextStyle): NativeProgressButtonRecipe = NativeProgressButtonRecipe(
    NativeButtonRecipe(
        NativeMaterialButtonStyle(15, verticalPaddingDp = 0, minimumWidthDp = 48, minimumHeightDp = 48,
            iconSizeDp = 20, radiusDp = 20f, alignContentStart = true),
        enabledPaint = NativeButtonPaint(NativeButtonColorRole.TRANSPARENT, NativeButtonColorRole.ON_ACCENT),
        disabledPaint = NativeButtonPaint(NativeButtonColorRole.TRANSPARENT, NativeButtonColorRole.MUTED,
            indication = NativeButtonColorRole.CONTENT),
        labelStyle = labelStyle, surroundingSurface = NativeButtonColorRole.ACCENT,
        disabledSurroundingSurface = NativeButtonColorRole.RAISED_SURFACE,
    ), surroundingHeightDp = 50)

/** Natural-width actions and the same actions sized by a footer's layout. */
class NativeButtonRecipes(
    labelStyle: TextStyle = NativeTypography.Default.buttonLabelStyle,
    explicitLabelStyle: TextStyle = NativeTypography.Default[NativeTypographyRole.LABEL_LARGE],
    val filled: NativeButtonRecipe = NativeButtonRecipe(filledSurface,
        NativeButtonPaint(NativeButtonColorRole.ACCENT, NativeButtonColorRole.ON_ACCENT), disabledFill,
        elevated = true, labelStyle = labelStyle),
    val text: NativeButtonRecipe = NativeButtonRecipe(textSurface, accentText, disabledText, labelStyle = labelStyle),
    val neutralText: NativeButtonRecipe = text.copy(enabledPaint = accentText.copy(content = NativeButtonColorRole.CONTENT)),
    val destructive: NativeButtonRecipe = filled.copy(
        enabledPaint = NativeButtonPaint(NativeButtonColorRole.ERROR_SURFACE, NativeButtonColorRole.ON_ERROR)),
    val destructiveText: NativeButtonRecipe = text.copy(
        enabledPaint = NativeButtonPaint(NativeButtonColorRole.TRANSPARENT, NativeButtonColorRole.ON_ERROR)),
    val tonal: NativeButtonRecipe = NativeButtonRecipe(filledSurface,
        NativeButtonPaint(NativeButtonColorRole.TONAL_SURFACE, NativeButtonColorRole.ON_TONAL), disabledFill, labelStyle = labelStyle),
    val footerBack: NativeButtonRecipe = text.copy(surface = text.surface.copy(assignedSize = true),
        disabledPaint = text.disabledPaint.copy(content = NativeButtonColorRole.MUTED)),
    val footerContinue: NativeButtonRecipe = filled.copy(surface = filled.surface.copy(assignedSize = true),
        disabledPaint = filled.disabledPaint.copy(containerOpacity = .1f, content = NativeButtonColorRole.MUTED),
        quietPaint = NativeButtonPaint(NativeButtonColorRole.QUIET_SURFACE, NativeButtonColorRole.CONTENT)),
    val raisedText: NativeButtonRecipe = text.copy(surface = text.surface.copy(radiusDp = 12f),
        surroundingSurface = NativeButtonColorRole.RAISED_SURFACE),
    val raisedIcon: NativeButtonRecipe = NativeButtonRecipe(roundedIconSurface, enabledIcon,
        enabledIcon.copy(contentOpacity = .38f), labelStyle = labelStyle, surroundingSurface = NativeButtonColorRole.RAISED_SURFACE),
    val largeRaisedIcon: NativeButtonRecipe = raisedIcon.copy(surface = raisedIcon.surface.copy(
        minimumWidthDp = 54, minimumHeightDp = 54, iconSizeDp = 25, radiusDp = 18f)),
    val rangeExport: NativeProgressButtonRecipe = defaultRangeExport(explicitLabelStyle),
    val trimActions: NativeTrimActionRecipes = NativeTrimActionRecipes(explicitLabelStyle),
)
