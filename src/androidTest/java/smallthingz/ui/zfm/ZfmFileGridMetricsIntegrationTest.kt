package smallthingz.ui.zfm

import android.content.Context
import android.content.res.Configuration
import android.graphics.Paint
import android.os.Build
import android.text.BoringLayout
import android.text.Layout
import android.view.View
import android.view.ViewGroup
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
        val detail = NativeText(context, palette, recipe.detailStyle).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val cellWidth = recipe.minimumCellWidthPx(context, paddingDp)
        val padding = (paddingDp * context.resources.displayMetrics.density).roundToInt()
        val contentWidth = cellWidth - 2 * padding
        val titleWidth = Layout.getDesiredWidth("Documents", title.paint)
        val detailWidth = detailSamples.maxOf { Layout.getDesiredWidth(it, detail.paint) }
        val titleIntrinsic = intrinsicContentWidth(title)
        val detailIntrinsic = detailSamples.maxOf { sample ->
            detail.text = sample
            intrinsicContentWidth(detail)
        }
        val titleBoundsPolicy = if (Build.VERSION.SDK_INT >= 35) title.useBoundsForWidth.toString() else "unavailable"
        val metrics = "cell=$cellWidth content=$contentWidth padding=$padding " +
            "fontScale=${context.resources.configuration.fontScale} " +
            "titleDesired=$titleWidth detailDesired=$detailWidth " +
            "titleIntrinsic=$titleIntrinsic detailIntrinsic=$detailIntrinsic " +
            "titleUseBounds=$titleBoundsPolicy targetSdk=${context.applicationInfo.targetSdkVersion} " +
            "titleAdvance=${title.paint.measureText("Documents")} " +
            "titleSpacing=${title.letterSpacing} detailSpacing=${detail.letterSpacing}"
        assertEquals("Recipe width matches the actual title and detail intrinsic widths: $metrics",
            maxOf((108 * context.resources.displayMetrics.density).roundToInt(),
                maxOf(titleIntrinsic, detailIntrinsic) + 2 * padding), cellWidth)
        assertTrue("Documents desired width fits the padded helper width: $metrics", titleWidth <= contentWidth)
        assertLineFits(title, "Documents", contentWidth, metrics)
        for (sample in detailSamples) {
            detail.text = sample
            assertLineFits(detail, sample, contentWidth, metrics)
        }
    }

    private fun intrinsicContentWidth(view: NativeText): Int {
        view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        return view.measuredWidth - view.compoundPaddingLeft - view.compoundPaddingRight
    }

    private fun assertLineFits(view: NativeText, expectedText: String, width: Int, metrics: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val layout = checkNotNull(view.layout)
        val content = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        val advance = view.paint.measureText(expectedText)
        val desired = Layout.getDesiredWidth(expectedText, view.paint)
        val lineWidth = layout.getLineWidth(0)
        val boring = BoringLayout.isBoring(expectedText, view.paint)
        val bounds = if (Build.VERSION.SDK_INT >= 35) boring?.drawingBoundingBox else null
        val boundsPolicy = if (Build.VERSION.SDK_INT >= 35) view.useBoundsForWidth.toString() else "unavailable"
        val diagnostic = "$metrics text=$expectedText viewWidth=${view.width} " +
            "compoundContent=$content advance=$advance desired=$desired line=$lineWidth " +
            "boringAdvance=${boring?.width} bounds=$bounds useBounds=$boundsPolicy " +
            "targetSdk=${view.context.applicationInfo.targetSdkVersion} sdk=${Build.VERSION.SDK_INT} " +
            "layout=${layout.javaClass.simpleName} paintWeight=${view.paint.typeface.weight} " +
            "textSize=${view.textSize} textScaleX=${view.textScaleX} spacing=${view.letterSpacing}"
        assertEquals("Exact padded width: $diagnostic", width, view.width)
        assertEquals("Sample must not wrap: $diagnostic", 1, layout.lineCount)
        assertEquals("No ellipsis: $diagnostic", 0, layout.getEllipsisCount(0))
        assertEquals("Full sample retained: $diagnostic", expectedText, view.text.toString())
        assertTrue("Paint advance fits: $diagnostic", advance <= content)
        assertTrue("Desired layout width fits: $diagnostic", desired <= content)
        assertTrue("Actual rendered line fits: $diagnostic", lineWidth <= content)
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
        val diagnostic = "config=${adjusted.resources.configuration.fontWeightAdjustment} " +
            "original=${adjustedTitle.typeface.weight} paint=${adjustedTitle.paint.typeface.weight} " +
            "ordinaryAdvance=${normalTitle.paint.measureText("Documents")} " +
            "adjustedAdvance=${adjustedTitle.paint.measureText("Documents")}"
        assertEquals("Exercise the requested configuration: $diagnostic", 300,
            adjusted.resources.configuration.fontWeightAdjustment)
        assertEquals(400, normalTitle.typeface.weight)
        assertEquals("TextView getter retains the original face: $diagnostic", 400, adjustedTitle.typeface.weight)
        assertEquals("Ordinary renderer weight: $diagnostic", 400, normalTitle.paint.typeface.weight)
        assertEquals("Exercise the adjusted renderer weight: $diagnostic", 700, adjustedTitle.paint.typeface.weight)
        assertTrue("The chosen font must expose the metric regression: $diagnostic",
            adjustedTitle.paint.measureText("Documents") != normalTitle.paint.measureText("Documents"))
        assertRendererFits(adjusted, ZfmFileGridRecipe(titleStyle = style))
        assertRendererFits(adjusted, ZfmFileGridRecipe.Default)
    }
}
