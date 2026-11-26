package smallthingz.ui.components

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import androidx.core.view.ViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.reverb.*

/** New browser address contract. Native InputConnection coverage is not physical IME proof. */
@RunWith(AndroidJUnit4::class)
class ReverbBrowserAddressSurfaceIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun numericCountSurvivesLegacyDescriptionAndAppearanceBindings() = main {
        val context = fixedDensity(instrumentation.context, 1f)
        val light = ReverbDesign.theme(context, false)
        val dark = ReverbDesign.theme(context, true)
        val chrome = createChrome(context, light)
        val reference = createChrome(context, dark)
        try {
            layout(chrome, 320)
            layout(reference, 320)
            chrome.bindTabCount(1, "1 tab")
            val one = raster(chrome.tabsAction)
            chrome.bindTabCount(2, "2 tabs")
            val two = raster(chrome.tabsAction)
            try {
                assertFalse("Description-only parent paints the same glyph for both counts", one.sameAs(two))
                chrome.bindTabCount("two open tabs")
                val legacy = raster(chrome.tabsAction)
                try { assertTrue("Legacy overload must retain the numeric glyph", two.sameAs(legacy)) }
                finally { legacy.recycle() }
            } finally { one.recycle(); two.recycle() }
            chrome.bindTheme(dark)
            reference.bindTabCount(2, "2 tabs")
            val rebound = raster(chrome.tabsAction)
            val expected = raster(reference.tabsAction)
            try { assertTrue("Theme binding must not restore the original static icon", expected.sameAs(rebound)) }
            finally { rebound.recycle(); expected.recycle() }
            assertEquals("two open tabs", ViewCompat.getStateDescription(chrome.tabsAction.accessibilityAction)?.toString())
            chrome.close()
            chrome.bindTabCount(99, "99 tabs")
            assertEquals("Closed chrome must not publish new count state", "two open tabs",
                ViewCompat.getStateDescription(chrome.tabsAction.accessibilityAction)?.toString())
        } finally { chrome.close(); reference.close() }
    }

    @Test fun keyboardGetsUriMetadataWithoutChangingNativeEditorOrComposition() = withWindow { chrome, _, _ ->
        val editor = chrome.addressEditor
        val nativeType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        editor.inputType = nativeType
        editor.setSingleLine(true)
        editor.textDirection = View.TEXT_DIRECTION_INHERIT
        editor.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        var submissions = 0
        editor.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { submissions++; true } else false
        }
        assertTrue(editor.requestFocus())
        val info = EditorInfo()
        val connection = checkNotNull(editor.onCreateInputConnection(info))
        try {
            assertEquals(nativeType, editor.inputType)
            assertEquals(InputType.TYPE_TEXT_VARIATION_URI, info.inputType and InputType.TYPE_MASK_VARIATION)
            assertEquals(EditorInfo.IME_ACTION_GO, info.imeOptions and EditorInfo.IME_MASK_ACTION)
            assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            assertTrue(connection.setComposingText("alpha beta", 1))
            assertTrue(connection.setSelection(2, 7))
            val editable = editor.text
            val composition = BaseInputConnection.getComposingSpanStart(editable) to BaseInputConnection.getComposingSpanEnd(editable)
            assertTrue(composition.first >= 0)
            chrome.bindTheme(ReverbDesign.theme(editor.context, true))
            assertSame(editable, editor.text)
            assertEquals("alpha beta", editor.text.toString())
            assertEquals(2 to 7, editor.selectionStart to editor.selectionEnd)
            assertEquals(composition,
                BaseInputConnection.getComposingSpanStart(editable) to BaseInputConnection.getComposingSpanEnd(editable))
            assertEquals(View.TEXT_DIRECTION_INHERIT, editor.textDirection)
            assertEquals(0, submissions)
            assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_GO))
            assertEquals(1, submissions)
        } finally { connection.closeConnection() }
    }

    private fun createChrome(context: Context, theme: ReverbTheme): ReverbBrowserChrome {
        fun action(id: String) = ReverbBrowserChromeAction(id, id, ReverbBrowserChromeIcon.Shared(ReverbIcon.Browser))
        return ReverbBrowserChrome(context, theme, "Address", action("site"), action("tabs"), action("menu"))
    }
    private fun fixedDensity(context: Context, font: Float) = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { densityDpi = 160; fontScale = font })
    private fun layout(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun raster(view: View) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
        view.draw(Canvas(it))
    }
    private fun main(block: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }
    private fun withWindow(block: (ReverbBrowserChrome, View, ReverbTheme) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var installed: ReverbBrowserChrome? = null
        try {
            lateinit var chrome: ReverbBrowserChrome
            lateinit var sink: View
            lateinit var theme: ReverbTheme
            main {
                theme = ReverbDesign.theme(activity, false)
                chrome = createChrome(activity, theme)
                installed = chrome
                sink = View(activity).apply { isFocusableInTouchMode = true }
                chrome.attachRenderer(sink)
                activity.setContentView(chrome)
            }
            val deadline = SystemClock.uptimeMillis() + 5000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                main { ready = chrome.isLaidOut && !chrome.isLayoutRequested && chrome.hasWindowFocus() }
                if (!ready) SystemClock.sleep(20)
            }
            assertTrue("Fixture must own a laid-out window", ready)
            main { assertTrue(sink.requestFocus()); block(chrome, sink, theme) }
        } finally { main { installed?.close(); activity.finish() } }
    }
}
