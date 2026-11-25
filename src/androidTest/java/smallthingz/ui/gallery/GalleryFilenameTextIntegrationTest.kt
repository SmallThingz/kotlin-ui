package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.text.style.LineBreakConfigSpan
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeText
import smallthingz.ui.text.NativeTypographyRole
import java.util.Locale
import kotlin.math.roundToInt

/** Detached native layout/selection only: no Activity, clipboard, media or global settings. */
@RunWith(AndroidJUnit4::class)
class GalleryFilenameTextIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val name = "turnbull-heal-preview-861084e2_edited_3.jpg"
    private val style = GalleryDesign.textStyle(NativeTypographyRole.TITLE_MEDIUM)

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

    @Test fun extensionAndExactNameAtNormalAndLargeFontWidths() = instrumentation.runOnMainSync {
        for (font in listOf(1f, 2f)) {
            val view = label(context(font), name)
            for (width in listOf(240, 280, 312, 360)) {
                measure(view, width)
                assertFits(view, name)
                if (Build.VERSION.SDK_INT >= 35) assertExtensionTogether(view)
                assertExactSelectionAndAccessibility(view, name)
            }
            if (Build.VERSION.SDK_INT >= 35) assertExtensionSpan(view, name.lastIndexOf('.'))
        }
    }

    @Test fun protectsAnActuallySplitNaturalExtensionWithoutRewriting() {
        // Pre35 intentionally has no no-break contract. The other tests still verify its
        // exact text and natural layout; do not record this API35 behavior as a pre35 pass.
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 35)
        instrumentation.runOnMainSync {
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

    @Test fun resizeAndStyleRefreshRetainSelectionAndMatchFreshLayout() = instrumentation.runOnMainSync {
        for (font in listOf(1f, 2f)) {
            val context = context(font)
            val retained = label(context, name)
            measure(retained, 312)
            val buffer = retained.text as Spannable
            Selection.setSelection(buffer, name.lastIndexOf('.'), name.length)
            for (nextStyle in listOf(style.copy(sizeSp = style.sizeSp + 4f,
                lineHeightSp = style.lineHeightSp?.plus(6f)), style)) {
                retained.applyStyle(nextStyle)
                for (width in listOf(312, 180, 360, 312)) {
                    measure(retained, width)
                    assertFits(retained, name)
                    if (Build.VERSION.SDK_INT >= 35) assertExtensionTogether(retained)
                    assertSame(buffer, retained.text)
                    assertEquals(name.lastIndexOf('.'), retained.selectionStart)
                    assertEquals(name.length, retained.selectionEnd)
                    val fresh = label(context, name).apply { applyStyle(nextStyle) }
                    measure(fresh, width)
                    assertFits(fresh, name)
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

    @Test fun overwideExtensionAndNamesWithoutExtensionKeepAllCharacters() = instrumentation.runOnMainSync {
        for (font in listOf(1f, 2f)) {
            val view = label(context(font), name)
            val longName = "fixture." + "longextension".repeat(12)
            for (value in listOf(longName, ".nomedia", "no-extension", "trailing.", "מסמך.jpg", "image.\ntext", "", name)) {
                view.bindName(value)
                measure(view, 120)
                assertFits(view, value)
                assertExactSelectionAndAccessibility(view, value)
                if (value == longName) {
                    val layout = checkNotNull(view.layout)
                    assertTrue("Overwide suffix must fall back to wrapping, not clipping", layout.lineCount > 1)
                }
            }
        }
    }

    @RequiresApi(35)
    private fun assertExtensionSpan(view: GalleryFilenameText, dot: Int) {
        val text = view.text as Spanned
        val spans = text.getSpans(0, text.length, LineBreakConfigSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(dot, text.getSpanStart(spans.single()))
        assertEquals(text.length, text.getSpanEnd(spans.single()))
    }
}
