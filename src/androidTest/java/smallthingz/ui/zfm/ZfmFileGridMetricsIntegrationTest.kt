package smallthingz.ui.zfm

import android.content.Context
import android.content.res.Configuration
import android.graphics.Paint
import android.os.Build
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeText
import smallthingz.ui.components.NativeTextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Actual Android typography/layout metrics; no screenshot sizing or provider access. */
@RunWith(AndroidJUnit4::class)
class ZfmFileGridMetricsIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val detailSamples = listOf("1023 MiB", "99.9 MiB", "incomplete", "unavailable")
    private fun main(block: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }

    private fun context(fontScale: Float, weightAdjustment: Int = 0): Context {
        val base = instrumentation.targetContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            densityDpi = 160
            this.fontScale = fontScale
            if (Build.VERSION.SDK_INT >= 31) fontWeightAdjustment = weightAdjustment
        })
    }

    private fun assertRendererFits(context: Context, recipe: ZfmFileGridRecipe, paddingDp: Int = 8) {
        val palette = ZfmTheme.resolve(context, dark = false).palette
        val title = ZfmFilenameText(context, palette, recipe.titleStyle).apply {
            bindName("Documents", directory = true)
        }
        val detail = NativeText(context, palette, recipe.detailStyle)
        val cellWidth = recipe.minimumCellWidthPx(context, paddingDp)
        val padding = (paddingDp * context.resources.displayMetrics.density).roundToInt()
        val contentWidth = cellWidth - 2 * padding
        val titleWidth = title.paint.measureText("Documents")
        val detailWidth = detailSamples.maxOf { detail.paint.measureText(it) }
        assertEquals("Recipe width uses the actual title and detail typography",
            maxOf((108 * context.resources.displayMetrics.density).roundToInt(),
                ceil(maxOf(titleWidth, detailWidth)).toInt() + 2 * padding), cellWidth)
        assertTrue("Tracked Documents advance fits the padded helper width", titleWidth <= contentWidth)
        title.measure(View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        title.layout(0, 0, title.measuredWidth, title.measuredHeight)
        val layout = checkNotNull(title.layout)
        assertEquals("Documents must not wrap at the helper's minimum width", 1, layout.lineCount)
        assertEquals(0, layout.getEllipsisCount(0))
        assertEquals("Documents", title.text.toString())
        assertTrue("Actual rendered line fits", layout.getLineWidth(0) <= contentWidth)
    }

    @Test fun emTrackedItalicTitleFitsPaddedMinimumAtBothFontScales() = main {
        for (fontScale in listOf(1f, 2f)) {
            val context = context(fontScale)
            val recipe = ZfmFileGridRecipe(
                titleStyle = NativeTextStyle(24f, weight = 500, letterSpacing = 1f,
                    fontFamily = "serif", italic = true),
            )
            assertTrue("Exercise width beyond the untracked recipe",
                recipe.minimumCellWidthPx(context) >
                    recipe.copy(titleStyle = recipe.titleStyle.copy(letterSpacing = 0f)).minimumCellWidthPx(context))
            assertRendererFits(context, recipe, paddingDp = 13)
        }
    }

    @Test fun spTrackingOverridesEmAndDetailTypographyResetsTheSharedPaint() = main {
        for (fontScale in listOf(1f, 2f)) {
            val context = context(fontScale)
            val title = NativeTextStyle(24f, letterSpacing = 1f, letterSpacingSp = 6f,
                fontFamily = "serif", italic = true)
            assertRendererFits(context, ZfmFileGridRecipe(titleStyle = title))
            // The detail wins the width calculation; leaked title tracking/typeface changes it.
            val detail = NativeTextStyle(48f, weight = 700, fontFamily = "monospace")
            assertRendererFits(context, ZfmFileGridRecipe(title, detail))
            assertRendererFits(context, ZfmFileGridRecipe(
                title.copy(letterSpacingSp = 0f),
                detail.copy(letterSpacing = .5f, letterSpacingSp = 3f, italic = true),
            ))
        }
    }

    @Test fun defaultRecipeKeepsPriorWidthAtBothFontScales() = main {
        for (fontScale in listOf(1f, 2f)) {
            val context = context(fontScale)
            val recipe = ZfmFileGridRecipe.Default
            // Freeze the previous untracked/default-font width contract independently.
            val density = Density(context)
            val fonts = createFontFamilyResolver(context)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            fun oldWidth(style: NativeTextStyle, text: String): Float {
                paint.typeface = fonts.resolve(FontFamily.system(style.fontFamily), FontWeight(style.weight)).value
                paint.textSize = with(density) { style.sizeSp.sp.toPx() }
                return paint.measureText(text)
            }
            val textWidth = maxOf(oldWidth(recipe.titleStyle, "Documents"),
                detailSamples.maxOf { oldWidth(recipe.detailStyle, it) })
            val scale = context.resources.displayMetrics.density
            val previous = maxOf((108 * scale).roundToInt(), ceil(textWidth).toInt() + 2 * (8 * scale).roundToInt())
            assertEquals(previous, recipe.minimumCellWidthPx(context))
            assertRendererFits(context, recipe)
        }
    }

    @Test @SdkSuppress(minSdkVersion = 31)
    fun configurationWeightAdjustmentMatchesNativeText() = main {
        val ordinary = context(1f)
        val adjusted = context(1f, weightAdjustment = 300)
        val style = NativeTextStyle(48f, weight = 400, letterSpacing = .2f,
            fontFamily = "sans-serif", italic = true)
        fun title(context: Context) = ZfmFilenameText(context,
            ZfmTheme.resolve(context, dark = false).palette, style)
        val normalTitle = title(ordinary)
        val adjustedTitle = title(adjusted)
        assertEquals(400, normalTitle.typeface.weight)
        assertEquals("Exercise the native configuration weight adjustment", 700, adjustedTitle.typeface.weight)
        assertTrue("The chosen font must expose the metric regression",
            adjustedTitle.paint.measureText("Documents") != normalTitle.paint.measureText("Documents"))
        assertRendererFits(adjusted, ZfmFileGridRecipe(titleStyle = style))
        assertRendererFits(adjusted, ZfmFileGridRecipe.Default)
    }
}
