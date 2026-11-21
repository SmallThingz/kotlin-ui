package smallthingz.ui.text

import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.unit.sp

/** Shared text styles for editor readouts, timelines and library labels. */
data class NativeReadoutTypography(
    val retentionLabel: TextStyle,
    val retentionEstimate: TextStyle,
    val libraryHeader: TextStyle,
    val playerTimestamp: TextStyle,
    val timelineTimestamp: TextStyle,
    val timelineBubble: TextStyle,
    val rangeBufferLabel: TextStyle,
) {
    constructor(typography: NativeTypography) : this(
        retentionLabel = typography.defaultTextStyle.copy(fontSize = 14.sp, lineHeight = 20.sp),
        retentionEstimate = typography.defaultTextStyle.copy(fontSize = 13.sp, lineHeight = 20.sp),
        libraryHeader = typography[NativeTypographyRole.LABEL_LARGE].copy(letterSpacing = 0.04.sp),
        playerTimestamp = typography[NativeTypographyRole.BODY_SMALL].copy(fontFamily = FontFamily.Monospace),
        timelineTimestamp = typography[NativeTypographyRole.LABEL_SMALL].copy(fontFamily = FontFamily.Monospace),
        timelineBubble = typography[NativeTypographyRole.LABEL_MEDIUM].copy(
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp, lineHeight = 13.sp, platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
        rangeBufferLabel = typography[NativeTypographyRole.LABEL_SMALL].copy(fontWeight = FontWeight.SemiBold),
    )
}
