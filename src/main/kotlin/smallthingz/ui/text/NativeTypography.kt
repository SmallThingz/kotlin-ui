/*
 * Material token construction follows AndroidX material3 1.4.0 TypographyTokens.kt.
 * Copyright 2021 The Android Open Source Project. Licensed under the Apache License,
 * Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
 */
package smallthingz.ui.text

import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.text.style.LineHeightStyle
import smallthingz.ui.unit.TextUnit
import smallthingz.ui.unit.sp

enum class NativeTypographyFontFamily { DEFAULT, SANS_SERIF }

enum class NativeTypographyLineHeightAlignment { PROPORTIONAL, CENTER }

/**
 * Released 0.1.2 typography, without a UI-runtime dependency. Colors belong to the appearance color scheme.
 *
 * ReverbTheme creates six fresh TextStyles: they use the ui-text defaults (proportional line
 * height, trimmed first/last leading, default family), not the Material3 token defaults.
 * Inherited roles use Material3 1.4.0 TypographyTokens/TypeScaleTokens: centered, untrimmed,
 * sans-serif. All current roles have normal font style and fixed line-height mode.
 */
data class NativeTypographyMetrics(
    val fontSizeSp: Float,
    val lineHeightSp: Float,
    val fontWeight: Int,
    val letterSpacingSp: Float = 0f,
    val fontFamily: NativeTypographyFontFamily = NativeTypographyFontFamily.DEFAULT,
    val lineHeightAlignment: NativeTypographyLineHeightAlignment = NativeTypographyLineHeightAlignment.PROPORTIONAL,
    val trimLineHeightEdges: Boolean = true,
    val italic: Boolean = false,
)

private fun materialTextStyle(size: Float, height: Float, weight: Int = 400, tracking: Float = 0f) =
    NativeTypographyMetrics(
        fontSizeSp = size,
        lineHeightSp = height,
        fontWeight = weight,
        letterSpacingSp = tracking,
        fontFamily = NativeTypographyFontFamily.SANS_SERIF,
        lineHeightAlignment = NativeTypographyLineHeightAlignment.CENTER,
        trimLineHeightEdges = false,
    )

enum class NativeTypographyRole(val style: NativeTypographyMetrics) {
    DISPLAY_LARGE(materialTextStyle(57f, 64f, tracking = -0.2f)),
    DISPLAY_MEDIUM(materialTextStyle(45f, 52f)),
    DISPLAY_SMALL(materialTextStyle(36f, 44f)),
    HEADLINE_LARGE(materialTextStyle(32f, 40f)),
    HEADLINE_MEDIUM(materialTextStyle(28f, 36f)),
    HEADLINE_SMALL(materialTextStyle(24f, 32f)),
    TITLE_LARGE(NativeTypographyMetrics(22f, 28f, 700)),
    TITLE_MEDIUM(NativeTypographyMetrics(16f, 24f, 500)),
    TITLE_SMALL(materialTextStyle(14f, 20f, 500, 0.1f)),
    BODY_LARGE(NativeTypographyMetrics(16f, 24f, 400)),
    BODY_MEDIUM(NativeTypographyMetrics(14f, 20f, 400)),
    BODY_SMALL(NativeTypographyMetrics(12f, 16f, 400)),
    LABEL_LARGE(NativeTypographyMetrics(14f, 20f, 600, 0.1f)),
    LABEL_MEDIUM(materialTextStyle(12f, 16f, 500, 0.5f)),
    LABEL_SMALL(materialTextStyle(11f, 16f, 500, 0.5f)),
}

private val inheritedTextStyle = TextStyle.Default.copy(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** Immutable typography roles with optional replacements for a chosen appearance. */
class NativeTypography(overrides: Map<NativeTypographyRole, TextStyle> = emptyMap()) {
    private val roles = Array(NativeTypographyRole.entries.size) { index ->
        val role = NativeTypographyRole.entries[index]
        overrides[role] ?: defaultParagraphStyle(role)
    }
    val defaultTextStyle: TextStyle = inheritedTextStyle.merge(this[NativeTypographyRole.BODY_LARGE])
    private val label = this[NativeTypographyRole.LABEL_LARGE]
    val buttonLabelStyle: TextStyle = defaultTextStyle.merge(label)

    operator fun get(role: NativeTypographyRole): TextStyle = roles[role.ordinal]

    /** An unstyled button label inherits body then label. Explicit role styles remain distinct. */
    fun buttonLabelStyle(labelStyle: TextStyle): TextStyle =
        if (labelStyle === label) buttonLabelStyle else defaultTextStyle.merge(labelStyle)

    companion object {
        val Default = NativeTypography()
    }
}

private fun defaultParagraphStyle(role: NativeTypographyRole): TextStyle {
    val metadata = role.style
    return when (role) {
        // These are fresh TextStyles in ReverbTheme, not copies of Material tokens.
        NativeTypographyRole.TITLE_LARGE,
        NativeTypographyRole.TITLE_MEDIUM,
        NativeTypographyRole.BODY_LARGE,
        NativeTypographyRole.BODY_MEDIUM,
        NativeTypographyRole.BODY_SMALL,
        NativeTypographyRole.LABEL_LARGE -> TextStyle(
            fontWeight = FontWeight(metadata.fontWeight),
            fontSize = metadata.fontSizeSp.sp,
            lineHeight = metadata.lineHeightSp.sp,
            letterSpacing = if (role == NativeTypographyRole.LABEL_LARGE)
                metadata.letterSpacingSp.sp else TextUnit.Unspecified,
        )
        // The other nine roles inherit the pinned TypographyTokens style unchanged.
        NativeTypographyRole.DISPLAY_LARGE,
        NativeTypographyRole.DISPLAY_MEDIUM,
        NativeTypographyRole.DISPLAY_SMALL,
        NativeTypographyRole.HEADLINE_LARGE,
        NativeTypographyRole.HEADLINE_MEDIUM,
        NativeTypographyRole.HEADLINE_SMALL,
        NativeTypographyRole.TITLE_SMALL,
        NativeTypographyRole.LABEL_MEDIUM,
        NativeTypographyRole.LABEL_SMALL -> inheritedTextStyle.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight(metadata.fontWeight),
            fontSize = metadata.fontSizeSp.sp,
            lineHeight = metadata.lineHeightSp.sp,
            letterSpacing = metadata.letterSpacingSp.sp,
        )
    }
}
