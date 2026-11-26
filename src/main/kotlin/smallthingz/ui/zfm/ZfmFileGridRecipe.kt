package smallthingz.ui.zfm

import android.content.Context
import android.graphics.Paint
import android.text.Layout
import android.text.TextPaint
import smallthingz.ui.components.NativeTextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.FontStyle
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Shared filename/metadata hierarchy for grid rendering and adaptive width measurement. */
data class ZfmFileGridRecipe(
    val titleStyle: NativeTextStyle = NativeTextStyle(12f, weight = 500),
    val detailStyle: NativeTextStyle = NativeTextStyle(11f),
    val metadataGapDp: Int = 2,
) {
    init { require(metadataGapDp >= 0) }

    /** Cache per configuration; callers subtract their own viewport insets before choosing spans. */
    fun minimumCellWidthPx(context: Context, horizontalPaddingDp: Int = 8): Int {
        require(horizontalPaddingDp >= 0)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        val compactWidth = dp(108)
        val density = Density(context)
        val fonts = createFontFamilyResolver(context)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        fun useTypography(style: NativeTextStyle) {
            // Keep configuration weight adjustment and match NativeText's italic/tracking.
            paint.typeface = fonts.resolve(FontFamily.system(style.fontFamily), FontWeight(style.weight),
                if (style.italic) FontStyle.Italic else FontStyle.Normal).value
            paint.textSize = with(density) { style.sizeSp.sp.toPx() }
            paint.letterSpacing = style.letterSpacingSp?.let {
                val emWidth = paint.textSize * paint.textScaleX
                if (emWidth.isFinite() && emWidth != 0f) with(density) { it.sp.toPx() } / emWidth else 0f
            } ?: style.letterSpacing
        }
        useTypography(titleStyle)
        // Fit a usual folder name and keep a size or status word intact on its own line.
        // TextLine metrics match NativeText's Layout, including tracked run edges.
        val nameWidth = Layout.getDesiredWidth("Documents", paint)
        useTypography(detailStyle)
        val textWidth = maxOf(nameWidth, Layout.getDesiredWidth("1023 MiB", paint), Layout.getDesiredWidth("99.9 MiB", paint),
            Layout.getDesiredWidth("incomplete", paint), Layout.getDesiredWidth("unavailable", paint))
        return maxOf(compactWidth, ceil(textWidth).toInt() + 2 * dp(horizontalPaddingDp))
    }

    companion object {
        val Default = ZfmFileGridRecipe()
    }
}
