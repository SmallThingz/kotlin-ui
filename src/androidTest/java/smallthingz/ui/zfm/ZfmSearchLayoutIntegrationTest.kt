package smallthingz.ui.zfm

import android.content.res.Configuration
import android.graphics.Rect
import android.text.Spanned
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeTextField

/** Real Android measurement; execution belongs to the coordinated library qualification run. */
@RunWith(AndroidJUnit4::class)
class ZfmSearchLayoutIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private class Scene(val chrome: ZfmSearchChrome, val input: ZfmSearchInput,
                        val editor: NativeTextField, val close: View, val clear: View,
                        val options: View, val status: TextView, val details: View)

    private fun scene(font: Float = 1f, rtl: Boolean = false): Scene {
        val base = instrumentation.targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            densityDpi = 160
            fontScale = font
        })
        val theme = ZfmTheme.resolve(context, dark = false)
        val editor = theme.appearance.compactField.createField(context, theme.colors).apply {
            setSingleLine()
            hint = "Search files"
            contentDescription = "Search files"
        }
        fun action(label: String) = View(context).apply { contentDescription = label; isFocusable = true }
        val close = action("Close search")
        val clear = action("Clear search")
        val options = action("Search options")
        val status = TextView(context).apply { text = "0 results"; textSize = 14f }
        val details = theme.button(context, "Details") {}
        val statusHost = LinearLayout(context).apply {
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            addView(details, LinearLayout.LayoutParams(-2, -2))
        }
        val input = ZfmSearchInput(context, theme, editor, close, listOf(clear))
        val chrome = ZfmSearchChrome(context, theme, input, statusHost, options).apply {
            layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            bind(true, status.text.toString(), status)
        }
        return Scene(chrome, input, editor, close, clear, options, status, details)
    }

    private fun measure(view: View, width: Int, mode: Int = View.MeasureSpec.EXACTLY) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, mode),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun bounds(scene: Scene, view: View) = Rect(0, 0, view.width, view.height).also {
        scene.chrome.offsetDescendantRectToMyCoords(view, it)
    }

    @Test fun typingClearVisibilityAndStatusDetailsKeepFullQueryWidthAtBothFontScales() = main {
        for (font in listOf(1f, 2f)) for (rtl in listOf(false, true)) {
            val s = scene(font, rtl)
            for (width in listOf(320, 360, 600)) {
                for (query in listOf("", "a", "long query ".repeat(40))) {
                    s.editor.setText(query)
                    for (clear in listOf(View.VISIBLE, View.INVISIBLE, View.GONE)) {
                        s.clear.visibility = clear
                        for (details in listOf(View.GONE, View.VISIBLE)) {
                            s.details.visibility = details
                            s.status.text = if (details == View.VISIBLE) "Partial results; some folders unavailable" else "0 results"
                            s.chrome.bind(true, s.status.text.toString(), s.status)
                            measure(s.chrome, width)
                            assertEquals(width, s.input.width)
                            assertEquals(width - 112, s.editor.width)
                            assertEquals(48, s.close.width)
                            assertEquals(48, s.options.width)
                            assertTrue(s.chrome.getChildAt(0).bottom <= s.chrome.getChildAt(1).top)
                            if (clear == View.VISIBLE) {
                                val editor = bounds(s, s.editor)
                                val closeBounds = bounds(s, s.close)
                                val clearBounds = bounds(s, s.clear)
                                if (rtl) {
                                    assertTrue(closeBounds.left >= editor.right)
                                    assertTrue(clearBounds.right <= editor.left)
                                } else {
                                    assertTrue(closeBounds.right <= editor.left)
                                    assertTrue(clearBounds.left >= editor.right)
                                }
                                assertEquals(clearBounds.left, bounds(s, s.options).left)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun statusAndWidthChangesRetainEditorParentSelectionAndComposition() = main {
        val s = scene(font = 2f)
        s.editor.setText("retained search")
        s.editor.setSelection(2, 5)
        val text = requireNotNull(s.editor.text)
        val composition = Any()
        text.setSpan(composition, 1, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING)
        val parent = s.editor.parent
        s.editor.requestFocus()
        val focused = s.editor.hasFocus()
        for (width in listOf(360, 80, 600, 320)) {
            s.status.text = "Changing result status ".repeat(8)
            s.chrome.bind(true, s.status.text.toString(), s.status)
            measure(s.chrome, width)
            assertSame(parent, s.editor.parent)
            assertSame(text, s.editor.text)
            assertEquals(2, s.editor.selectionStart)
            assertEquals(5, s.editor.selectionEnd)
            assertEquals(1, BaseInputConnection.getComposingSpanStart(text))
            assertEquals(4, BaseInputConnection.getComposingSpanEnd(text))
            assertEquals(focused, s.editor.hasFocus())
        }
    }

    @Test fun narrowAtMostViewportRetainsScrollableTargetsWithoutExceedingParent() = main {
        val s = scene()
        measure(s.input, 80, View.MeasureSpec.AT_MOST)
        assertEquals(80, s.input.width)
        assertEquals(48, s.editor.width)
        assertEquals(48, s.close.width)
        assertEquals(48, s.clear.width)
        val scroll = s.input.getChildAt(0) as HorizontalScrollView
        assertTrue(scroll.getChildAt(0).width > scroll.width)
        scroll.scrollTo(scroll.getChildAt(0).width, 0)
        assertTrue(scroll.scrollX > 0)
    }
}
