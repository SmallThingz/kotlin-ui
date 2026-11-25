package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.icu.text.BreakIterator
import android.os.Build
import android.text.Layout
import android.text.Selection
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeText
import smallthingz.ui.text.NativeTypographyRole
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Detached native layout/selection only: no Activity, clipboard, media or global settings. */
@RunWith(AndroidJUnit4::class)
class GalleryFilenameTextIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val name = "turnbull-heal-preview-861084e2_edited_3.jpg"
    private val style = GalleryDesign.textStyle(NativeTypographyRole.TITLE_MEDIUM)

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }

    private fun context(font: Float): Context {
        val base = instrumentation.targetContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            fontScale = font
            densityDpi = 160
            setLocale(Locale.US)
        })
    }

    private fun label(context: Context, name: String) = GalleryFilenameText(
        context, GalleryDesign.palette(context, true).pageControls,
    ).apply {
        layoutParams = ViewGroup.LayoutParams(-1, -2)
        bindName(name)
    }

    private fun measure(view: NativeText, widthDp: Int) {
        val width = (widthDp * view.resources.displayMetrics.density).roundToInt()
        // Deliberately no forceLayout: resize/style/bind must invalidate native measurement.
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun diagnostics(view: NativeText): String {
        val layout = checkNotNull(view.layout)
        return "font=${view.resources.configuration.fontScale} size=${view.textSize} " +
            "view=${view.width}x${view.height} layout=${layout.width}x${layout.height} " +
            "padding=${view.compoundPaddingLeft},${view.compoundPaddingTop}," +
            "${view.compoundPaddingRight},${view.compoundPaddingBottom} " +
            (0 until layout.lineCount).joinToString { line ->
                "$line:${layout.getLineStart(line)}..${layout.getLineEnd(line)} " +
                    "visible=${layout.getLineMax(line)} text=${view.text.subSequence(layout.getLineStart(line), layout.getLineEnd(line))}"
            }
    }

    private fun assertFits(view: NativeText, expected: String) {
        val layout = checkNotNull(view.layout)
        val detail = diagnostics(view)
        assertEquals(expected, view.text.toString())
        assertEquals(detail, expected.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue(detail, layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
        val width = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        for (line in 0 until layout.lineCount) {
            assertEquals(detail, 0, layout.getEllipsisCount(line))
            assertTrue(detail, layout.getLineMax(line) <= width + .5f)
            assertTrue(detail, layout.getLineLeft(line) >= -.5f && layout.getLineRight(line) <= width + .5f)
        }
    }

    private fun assertExtensionTogether(view: NativeText) {
        val text = view.text.toString()
        val dot = text.lastIndexOf('.')
        val layout = checkNotNull(view.layout)
        assertTrue("Fixture extension itself must fit: ${diagnostics(view)}",
            view.paint.measureText(text, dot, text.length) <= layout.width)
        assertEquals("Dot through final character must share a line: ${diagnostics(view)}",
            layout.getLineForOffset(dot), layout.getLineForOffset(text.lastIndex))
    }

    private fun assertExactSelectionAndAccessibility(view: GalleryFilenameText, expected: String) {
        assertTrue(view.isTextSelectable)
        val buffer = view.text as Spannable
        val spans = buffer.getSpans(0, buffer.length, ReplacementSpan::class.java)
        if (spans.isNotEmpty()) {
            val unitStart = buffer.getSpanStart(spans.single())
            // Noneditable Details deliberately treats the fitting terminal unit as atomic.
            // Do not promise per-character caret positions inside a ReplacementSpan.
            if (expected.all { it.code < 128 }) {
                assertEquals("Native cursor must step across the atomic terminal unit: ${diagnostics(view)}",
                    unitStart, checkNotNull(view.layout).getOffsetToLeftOf(expected.length))
            }
            Selection.setSelection(buffer, unitStart, expected.length)
            assertEquals(expected.substring(unitStart), buffer.subSequence(view.selectionStart, view.selectionEnd).toString())
        }
        Selection.setSelection(buffer, 0, expected.length)
        assertEquals(expected, buffer.subSequence(view.selectionStart, view.selectionEnd).toString())
        // This checks the native copy source without writing the device clipboard.
        val node = AccessibilityNodeInfo.obtain()
        try {
            view.onInitializeAccessibilityNodeInfo(node)
            assertEquals(expected, node.text?.toString().orEmpty())
            assertNull("No synthesized accessibility substitute", node.contentDescription)
        } finally { node.recycle() }
        view.bindName(expected)
        assertSame("Same-name binding retains buffer and selection", buffer, view.text)
        assertEquals(0, view.selectionStart)
        assertEquals(expected.length, view.selectionEnd)
    }

    @Test fun extensionAndExactNameAtNormalAndLargeFontWidths() = onMain {
        for (font in listOf(1f, 2f)) {
            val context = context(font)
            for ((filename, unitStart) in listOf(name to name.lastIndexOf('_'),
                "export-copy-27.jpg" to "export-copy-27.jpg".lastIndexOf('-'))) {
                val view = label(context, filename)
                for (width in listOf(240, 280, 312, 360)) {
                    measure(view, width)
                    assertExtensionSpan(view, unitStart)
                    assertFits(view, filename)
                    assertExtensionTogether(view)
                    assertBasenameOnExtensionLine(view)
                    assertExactSelectionAndAccessibility(view, filename)

                    // Old extension-only policy, using the same native span drawing/metrics.
                    // This compares actual layouts, not the new range-selection algorithm.
                    val span = (view.text as Spanned).getSpans(0, filename.length,
                        ReplacementSpan::class.java).single()
                    val baseline = NativeText(context, GalleryDesign.palette(context, true).pageControls, style).apply {
                        layoutParams = ViewGroup.LayoutParams(-1, -2)
                        setSingleLine(false)
                        ellipsize = null
                        setTextIsSelectable(true)
                        text = SpannableString(filename).apply {
                            setSpan(span, filename.lastIndexOf('.'), filename.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    measure(baseline, width)
                    assertFits(baseline, filename)
                    assertTrue("No extra line for these fixtures: ${diagnostics(view)} baseline=${diagnostics(baseline)}",
                        checkNotNull(view.layout).lineCount <= checkNotNull(baseline.layout).lineCount)
                }
            }
        }
    }

    @Test fun protectsAnActuallySplitNaturalExtensionWithoutRewriting() {
        onMain {
            for (font in listOf(1f, 2f)) {
                val context = context(font)
                val natural = NativeText(context, GalleryDesign.palette(context, true).pageControls, style).apply {
                    layoutParams = ViewGroup.LayoutParams(-1, -2)
                    setTextIsSelectable(true)
                    text = name
                }
                val guarded = label(context, name)
                val dot = name.lastIndexOf('.')
                var exercised = false
                for (width in 180..360) {
                    measure(natural, width)
                    val layout = checkNotNull(natural.layout)
                    if (layout.getLineForOffset(dot) == layout.getLineForOffset(name.lastIndex)) continue
                    measure(guarded, width)
                    assertExtensionSpan(guarded, name.lastIndexOf('_'))
                    assertBasenameOnExtensionLine(guarded)
                    assertFits(guarded, name)
                    assertExtensionTogether(guarded)
                    assertExactSelectionAndAccessibility(guarded, name)
                    exercised = true
                    break
                }
                assertTrue("Must exercise a naturally split extension at font=$font, not a vacuous happy path", exercised)
            }
        }
    }

    @Test fun atomicExtensionKeepsPairedCjkSuffixTogether() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 35)
        onMain {
            // Ordinary space/CJK boundaries avoid Minikin's separate hard-hyphen policy.
            val filename = "head longword.画像"
            val earlierBreak = filename.indexOf(' ') + 1
            val suffixBreak = filename.lastIndex
            val breaks = BreakIterator.getLineInstance(Locale.US).apply { setText(filename) }
            assertTrue("Ordinary break before the final segment", breaks.isBoundary(earlierBreak))
            assertTrue("Ordinary break inside the extension", breaks.isBoundary(suffixBreak))

            for (font in listOf(1f, 2f)) {
                val context = context(font)
                val natural = NativeText(context, GalleryDesign.palette(context, true).pageControls, style).apply {
                    layoutParams = ViewGroup.LayoutParams(-1, -2)
                    setSingleLine(false)
                    ellipsize = null
                    setTextIsSelectable(true)
                    breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
                    text = filename
                }
                val guarded = label(context, filename).apply {
                    breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
                }
                val minimumWidth = maxOf(natural.paint.measureText(filename, 0, suffixBreak),
                    natural.paint.measureText(filename, earlierBreak, filename.length))
                val fullWidth = natural.paint.measureText(filename)
                val contentWidth = ((minimumWidth + fullWidth) / 2).roundToInt()
                assertTrue("Plain prefix and complete tail must fit, with the full name wrapping at font=$font",
                    contentWidth >= minimumWidth && contentWidth < fullWidth)
                // The fixture context is mdpi, so the pixel width is also the dp width.
                val width = contentWidth + natural.compoundPaddingLeft + natural.compoundPaddingRight
                measure(natural, width)
                val naturalLayout = checkNotNull(natural.layout)
                assertEquals(contentWidth, naturalLayout.width)
                assertFits(natural, filename)
                assertEquals(diagnostics(natural), 2, naturalLayout.lineCount)
                assertEquals(diagnostics(natural), suffixBreak, naturalLayout.getLineStart(1))

                measure(guarded, width)
                val guardedLayout = checkNotNull(guarded.layout)
                assertEquals(contentWidth, guardedLayout.width)
                assertFits(guarded, filename)
                assertEquals(diagnostics(guarded), 2, guardedLayout.lineCount)
                // A replacement object may break before the dot, not only at the earlier word gap.
                assertNotEquals(diagnostics(guarded), naturalLayout.getLineStart(1), guardedLayout.getLineStart(1))
                assertTrue(diagnostics(guarded), guardedLayout.getLineStart(1) <= filename.lastIndexOf('.'))
                assertExtensionSpan(guarded, filename.lastIndexOf('.'))
                assertExtensionTogether(guarded)
                assertExactSelectionAndAccessibility(guarded, filename)
            }
        }
    }

    @Test fun resizeAndStyleRefreshRetainSelectionAndMatchFreshLayout() = onMain {
        for (font in listOf(1f, 2f)) {
            val context = context(font)
            val retained = label(context, name)
            measure(retained, 312)
            val buffer = retained.text as Spannable
            Selection.setSelection(buffer, name.lastIndexOf('.'), name.length)
            for (nextStyle in listOf(style.copy(sizeSp = style.sizeSp + 4f,
                lineHeightSp = style.lineHeightSp?.plus(6f)), style)) {
                retained.applyStyle(nextStyle)
                // Refresh style metrics, then exercise larger unit -> suffix only -> no span -> restored.
                measure(retained, 312)
                val dot = name.lastIndexOf('.')
                val unitStart = name.lastIndexOf('_')
                val suffixWidth = ceil(retained.paint.measureText(name, dot, name.length).toDouble()).toInt()
                val unitWidth = ceil(retained.paint.measureText(name, unitStart, name.length).toDouble()).toInt()
                assertTrue("Distinct width fallback fixtures", unitWidth > suffixWidth && unitWidth <= 180)
                val padding = retained.compoundPaddingLeft + retained.compoundPaddingRight
                for ((width, expectedStart) in listOf(312 to unitStart, 180 to unitStart,
                    360 to unitStart, 312 to unitStart, (suffixWidth + padding) to dot,
                    (suffixWidth - 1 + padding) to -1, 312 to unitStart)) {
                    measure(retained, width)
                    if (expectedStart >= 0) {
                        assertExtensionSpan(retained, expectedStart)
                        assertExtensionTogether(retained)
                        if (expectedStart == unitStart) assertBasenameOnExtensionLine(retained)
                    } else assertNoExtensionSpan(retained)
                    assertFits(retained, name)
                    assertSame(buffer, retained.text)
                    assertEquals(name.lastIndexOf('.'), retained.selectionStart)
                    assertEquals(name.length, retained.selectionEnd)
                    val fresh = label(context, name).apply { applyStyle(nextStyle) }
                    measure(fresh, width)
                    assertFits(fresh, name)
                    val measuredHeight = retained.measuredHeight
                    val ends = checkNotNull(retained.layout).let { layout ->
                        (0 until layout.lineCount).map { layout.getLineEnd(it) }
                    }
                    measure(retained, width)
                    assertEquals("Repeated ordinary measure is stable", measuredHeight, retained.measuredHeight)
                    assertEquals(ends, checkNotNull(retained.layout).let { layout ->
                        (0 until layout.lineCount).map { layout.getLineEnd(it) }
                    })
                    assertSame(buffer, retained.text)
                    assertEquals(dot, retained.selectionStart)
                    assertEquals(name.length, retained.selectionEnd)
                    val actual = checkNotNull(retained.layout)
                    val expected = checkNotNull(fresh.layout)
                    assertEquals(diagnostics(retained), fresh.measuredHeight, retained.measuredHeight)
                    assertEquals(expected.lineCount, actual.lineCount)
                    for (line in 0 until actual.lineCount) {
                        assertEquals(expected.getLineStart(line), actual.getLineStart(line))
                        assertEquals(expected.getLineEnd(line), actual.getLineEnd(line))
                    }
                }
            }
        }
    }

    @Test fun overwideExtensionAndNamesWithoutExtensionKeepAllCharacters() = onMain {
        for (font in listOf(1f, 2f)) {
            val view = label(context(font), name)
            measure(view, 120)
            val buffer = view.text as Spannable
            val dot = name.lastIndexOf('.')
            Selection.setSelection(buffer, dot, name.length)
            view.setPadding(7, 0, 5, 0)
            val density = view.resources.displayMetrics.density
            val tightWidth = (view.paint.measureText(name, dot, name.length) / density).toInt()
            measure(view, tightWidth) // Padding makes the suffix itself too wide.
            assertNoExtensionSpan(view)
            assertFits(view, name)
            assertSame(buffer, view.text)
            assertEquals(dot, view.selectionStart)
            assertEquals(name.length, view.selectionEnd)
            measure(view, 120)
            assertExtensionSpan(view, name.lastIndexOf('_'))
            assertBasenameOnExtensionLine(view)
            assertExtensionTogether(view)
            assertFits(view, name)
            assertSame(buffer, view.text)
            assertEquals(dot, view.selectionStart)
            assertEquals(name.length, view.selectionEnd)
            assertExactSelectionAndAccessibility(view, name)
            view.setPadding(0, 0, 0, 0)
            val longName = "fixture." + "longextension".repeat(12)
            for (value in listOf(longName, ".nomedia", "no-extension", "trailing.",
                "מסמך.jpg", "صورة.jpg", "image.תמונה", "image.صورة", "מסמך_3.jpg", "image_3.תמונה",
                "image-final part.jpg", "image-part!.jpg", "_3.jpg", "image_.jpg", "image.\ntext", "", name)) {
                view.bindName(value)
                measure(view, 120)
                assertFits(view, value)
                assertExactSelectionAndAccessibility(view, value)
                if (value == longName) {
                    val layout = checkNotNull(view.layout)
                    assertTrue("Overwide suffix must fall back to wrapping, not clipping", layout.lineCount > 1)
                    assertNoExtensionSpan(view)
                } else if (value in listOf("מסמך.jpg", "صورة.jpg", "image.תמונה", "image.صورة",
                    "מסמך_3.jpg", "image_3.תמונה", "image-final part.jpg", "image-part!.jpg", "_3.jpg", "image_.jpg", name)) {
                    assertExtensionSpan(view, if (value == name) name.lastIndexOf('_') else value.lastIndexOf('.'))
                    assertExtensionTogether(view)
                    assertNativeSuffixDrawing(view)
                } else assertNoExtensionSpan(view)
            }

            // The wider unit would hide the first strong letter from Android's bidi analysis.
            val numericPrefix = "123-abc.jpg"
            val hyphen = numericPrefix.indexOf('-')
            view.textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            view.bindName(numericPrefix)
            measure(view, 312)
            val layout = checkNotNull(view.layout)
            assertTrue("The wider unit must fit in the bidi fixture",
                ceil(view.paint.measureText(numericPrefix, hyphen, numericPrefix.length).toDouble()) <= layout.width)
            assertEquals(diagnostics(view), Layout.DIR_LEFT_TO_RIGHT, layout.getParagraphDirection(0))
            assertExtensionSpan(view, numericPrefix.lastIndexOf('.'))
            assertExtensionTogether(view)
            assertFits(view, numericPrefix)
            assertExactSelectionAndAccessibility(view, numericPrefix)
        }
    }

    private fun assertBasenameOnExtensionLine(view: GalleryFilenameText) {
        val dot = view.text.toString().lastIndexOf('.')
        val layout = checkNotNull(view.layout)
        assertEquals("Final basename character must accompany the extension: ${diagnostics(view)}",
            layout.getLineForOffset(dot - 1), layout.getLineForOffset(view.text.length - 1))
    }

    private fun assertExtensionSpan(view: GalleryFilenameText, start: Int) {
        val text = view.text as Spanned
        val spans = text.getSpans(0, text.length, ReplacementSpan::class.java)
        assertEquals(diagnostics(view), 1, spans.size)
        assertEquals(start, text.getSpanStart(spans.single()))
        assertEquals(text.length, text.getSpanEnd(spans.single()))
    }

    private fun assertNoExtensionSpan(view: GalleryFilenameText) {
        val text = view.text as Spanned
        assertEquals(diagnostics(view), 0, text.getSpans(0, text.length, ReplacementSpan::class.java).size)
    }

    /** Native standalone suffix is the shaping/bidi oracle, including non-Latin extensions. */
    private fun assertNativeSuffixDrawing(view: GalleryFilenameText) {
        val buffer = view.text as Spanned
        val span = buffer.getSpans(0, buffer.length, ReplacementSpan::class.java).single()
        val start = buffer.getSpanStart(span)
        val natural = NativeText(view.context, GalleryDesign.palette(view.context, true).pageControls, style).apply {
            text = buffer.subSequence(start, buffer.length).toString()
            setTextColor(view.currentTextColor)
        }
        measure(natural, 120)
        val layout = checkNotNull(natural.layout)
        assertEquals(1, layout.lineCount)
        val expected = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        try {
            layout.draw(Canvas(expected))
            val pixels = IntArray(expected.width * expected.height)
            expected.getPixels(pixels, 0, expected.width, 0, 0, expected.width, expected.height)
            assertTrue("Native suffix oracle must contain ink", pixels.any { it ushr 24 != 0 })
            val actual = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
            try {
                span.draw(Canvas(actual), buffer, start, buffer.length, layout.getLineLeft(0),
                    layout.getLineTop(0), layout.getLineBaseline(0), layout.getLineBottom(0), natural.paint)
                assertTrue("Suffix must render as native text, including bidi shaping: ${natural.text}", expected.sameAs(actual))
            } finally { actual.recycle() }
        } finally { expected.recycle() }
    }
}
