package smallthingz.ui.zfm

import android.content.Context
import android.graphics.Paint
import smallthingz.ui.components.NativeTextStyle
import smallthingz.ui.text.font.FontFamily
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun useTypography(style: NativeTextStyle) {
            paint.typeface = fonts.resolve(FontFamily.system(style.fontFamily), FontWeight(style.weight)).value
            paint.textSize = with(density) { style.sizeSp.sp.toPx() }
        }
        useTypography(titleStyle)
        // Fit a usual folder name and keep a size or status word intact on its own line.
        val nameWidth = paint.measureText("Documents")
        useTypography(detailStyle)
        val textWidth = maxOf(nameWidth, paint.measureText("1023 MiB"), paint.measureText("99.9 MiB"),
            paint.measureText("incomplete"), paint.measureText("unavailable"))
        return maxOf(compactWidth, ceil(textWidth).toInt() + 2 * dp(horizontalPaddingDp))
    }

    companion object {
        val Default = ZfmFileGridRecipe()
    }
}
