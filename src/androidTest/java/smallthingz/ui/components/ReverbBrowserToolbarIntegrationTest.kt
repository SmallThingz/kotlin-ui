package smallthingz.ui.components

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
import smallthingz.ui.reverb.*
import kotlin.math.roundToInt

/**
 * Browser-specific paint, geometry and native editor coverage. The existing
 * ReverbBrowserChromeIntegrationTest remains responsible for action retirement,
 * accessibility state and lossless address binding/user-input limits.
 * These are Android tests; direct InputConnection calls do not prove real IME visibility.
 */
@RunWith(AndroidJUnit4::class)
class ReverbBrowserToolbarIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun awaitToolbarTexture() {
        // Compare fully prepared material, not two frames straddling asynchronous grain arrival.
        NoiseTexture.prepare()
        val deadline = SystemClock.uptimeMillis() + 5000
        while (NoiseTexture.shader(NoiseDrawable.SEED_TOP_BAR) == null && SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(20)
        assertNotNull("Toolbar grain must be ready for pixel assertions", NoiseTexture.shader(NoiseDrawable.SEED_TOP_BAR))
    }

    @Test fun idleControlsPaintOnlyTheContinuousToolbarMaterial() = main {
        val context = fixedDensity(instrumentation.context)
        for (dark in listOf(false, true)) {
            val theme = ReverbDesign.theme(context, dark)
            val chrome = createChrome(context, theme)
            try {
                // Remove semantic content only. Any button/field fill, outline or shadow remains
                // in the actual raster, so it cannot hide behind a background-property assertion.
                val glyphs = mutableMapOf<ReverbBrowserChromeActionView, Rect>()
                actions(chrome).forEach { action ->
                    action.iconPainter = { _, bounds, _ -> glyphs[action] = Rect(bounds) }
                }
                chrome.addressEditor.hint = ""
                chrome.addressEditor.isCursorVisible = false
                layout(chrome, 320)
                val toolbar = toolbar(chrome)
                val actual = raster(toolbar)
                val material = backgroundRaster(toolbar)
                try {
                    assertTrue("Idle browser controls must not add boxed surfaces (dark=$dark)",
                        material.sameAs(actual))
                    for (action in actions(chrome)) {
                        val glyph = checkNotNull(glyphs[action])
                        assertTrue("Browser glyph must stay compact", glyph.width() in 20..24)
                        assertEquals(glyph.width(), glyph.height())
                        assertEquals(action.visual.width / 2f, glyph.exactCenterX(), .5f)
                        assertEquals(action.visual.height / 2f, glyph.exactCenterY(), .5f)
                    }
                } finally { actual.recycle(); material.recycle() }
            } finally { chrome.close() }
        }
    }

    @Test fun joinedControlsLeaveAUsableAddressViewportAtNarrowAndWideRtlSizes() = main {
        for (fontScale in listOf(1f, 2f)) {
            val context = fixedDensity(instrumentation.context, fontScale)
            val chrome = createChrome(context, ReverbDesign.theme(context, false))
            try {
                val editor = chrome.addressEditor
                editor.textDirection = View.TEXT_DIRECTION_LTR
                editor.setText("https://example.test/long/path?query=browser")
                for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                    chrome.layoutDirection = direction
                    for (width in listOf(224, 320, 600)) {
                        layout(chrome, width)
                        val toolbar = toolbar(chrome)
                        val ordered = listOf(chrome.siteAction, editor, chrome.tabsAction, chrome.menuAction)
                            .map { bounds(toolbar, it) }
                            .let { if (direction == View.LAYOUT_DIRECTION_RTL) it.reversed() else it }
                        val label = "width=$width, font=$fontScale, direction=$direction"
                        assertEquals(label, toolbar.paddingLeft, ordered.first().left)
                        assertEquals(label, toolbar.width - toolbar.paddingRight, ordered.last().right)
                        ordered.zipWithNext().forEach { (left, right) ->
                            assertEquals("Joined controls must neither gap nor overlap: $label", left.right, right.left)
                        }
                        assertTrue("Address must retain an editable viewport: $label",
                            editor.width - editor.compoundPaddingLeft - editor.compoundPaddingRight >= 48)
                        assertTrue("Single-line text must not be vertically clipped: $label",
                            editor.height - editor.compoundPaddingTop - editor.compoundPaddingBottom >= editor.layout.height)
                        actions(chrome).forEach { action ->
                            val target = bounds(toolbar, action.accessibilityAction)
                            assertTrue("Full action target: $label", target.width() >= 48 && target.height() >= 48)
                            assertTrue("Action stays within toolbar: $label",
                                target.left >= 0 && target.right <= toolbar.width &&
                                    target.top >= 0 && target.bottom <= toolbar.height)
                        }
                    }
                }
            } finally { chrome.close() }
        }
    }

    @Test fun focusedAddressRuleStaysAtTheViewportWhenTheUrlScrollsAndRetiresOnBlur() = withWindow { chrome, sink, theme ->
        val editor = chrome.addressEditor
        val density = editor.resources.displayMetrics.density
        editor.setText("https://example.test/" + "segment/".repeat(100))
        editor.isCursorVisible = false
        assertTrue(editor.requestFocus())
        editor.setSelection(0)
        layout(chrome, (320 * density).roundToInt())
        val toolbar = toolbar(chrome)
        val rect = bounds(toolbar, editor)
        // A sample through the center of the focused 2dp baseline, below the text/selection.
        val y = rect.bottom - (3 * density).roundToInt()
        val start = rect.left + editor.paddingLeft + (2 * density).roundToInt()
        val end = rect.right - editor.paddingRight - (2 * density).roundToInt()
        assertTrue(start < end)
        for (scroll in listOf(0, (120 * density).roundToInt())) {
            editor.scrollTo(scroll, 0)
            val frame = raster(toolbar)
            try {
                assertEquals("The test must actually exercise horizontal scroll", scroll, editor.scrollX)
                for (x in start until end) assertEquals("Focused rule must stay at viewport x=$x, scroll=$scroll",
                    theme.palette.pageControls.accent, frame.getPixel(x, y))
            } finally { frame.recycle() }
        }
        assertTrue(sink.requestFocus())
        assertFalse(editor.hasFocus())
        val blurred = raster(toolbar)
        val material = backgroundRaster(toolbar)
        try {
            for (x in start until end) assertEquals("Blur must retire the focus rule at x=$x",
                material.getPixel(x, y), blurred.getPixel(x, y))
        } finally { blurred.recycle(); material.recycle() }
    }

    @Test fun nativeCompositionHardwareEditingAndGoKeepTheHostsEditorContract() = withWindow { chrome, sink, _ ->
        val editor = chrome.addressEditor
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val focusEvents = mutableListOf<Boolean>()
        val submissions = mutableListOf<String>()
        // These settings/listeners belong to PhoneBrowserView, not to the shared skin.
        editor.inputType = type
        editor.setSingleLine(true)
        editor.setSelectAllOnFocus(true)
        editor.textDirection = View.TEXT_DIRECTION_LTR
        editor.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        editor.setOnFocusChangeListener { _, focused -> focusEvents.add(focused) }
        editor.setOnEditorActionListener { _, action, event ->
            when {
                event != null && event.keyCode == KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) submissions.add(editor.text.toString())
                    true
                }
                event == null && action == EditorInfo.IME_ACTION_GO -> { submissions.add(editor.text.toString()); true }
                else -> false
            }
        }
        editor.setText("example.test")
        assertTrue(editor.requestFocus())
        assertEquals(listOf(true), focusEvents)
        val editable = editor.text
        val info = EditorInfo()
        val connection = checkNotNull(editor.onCreateInputConnection(info))
        try {
            assertEquals(type, info.inputType)
            assertEquals(EditorInfo.IME_ACTION_GO, info.imeOptions and EditorInfo.IME_MASK_ACTION)
            assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            assertTrue(connection.setSelection(0, editor.length()))
            assertTrue(connection.setComposingText("draft.test", 1))
            chrome.bindTabCount("2 tabs")
            chrome.bindLoading(.5f, 1L, true, true)
            chrome.bindBrowserStatus("Loading", true)
            layout(chrome, chrome.width)
            assertSame("Chrome updates must preserve the live Editable", editable, editor.text)
            assertTrue(editor.hasFocus())
            assertEquals("draft.test", editor.text.toString())
            assertEquals(0, BaseInputConnection.getComposingSpanStart(editable))
            assertEquals(10, BaseInputConnection.getComposingSpanEnd(editable))
            assertEquals(10, editor.selectionStart)
            assertEquals(10, editor.selectionEnd)
            assertEquals(listOf(true), focusEvents)
            assertTrue(connection.finishComposingText())
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(editable))

            key(chrome, KeyEvent.KEYCODE_DPAD_LEFT)
            assertEquals(9, editor.selectionStart)
            key(chrome, KeyEvent.KEYCODE_DEL)
            assertEquals("draft.tet", editor.text.toString())
            assertEquals(8, editor.selectionStart)
            assertTrue("Editing keys must not submit", submissions.isEmpty())
            assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_GO))
            assertEquals(listOf("draft.tet"), submissions)
            key(chrome, KeyEvent.KEYCODE_ENTER)
            assertEquals("A hardware Enter pair must submit only once", listOf("draft.tet", "draft.tet"), submissions)
            assertEquals("Single-line submission must not insert a newline", "draft.tet", editor.text.toString())
            assertTrue(sink.requestFocus())
            assertEquals("The host keeps its focus listener", listOf(true, false), focusEvents)
            assertEquals("Losing focus must not replace the draft in the shared skin", "draft.tet", editor.text.toString())
        } finally { connection.closeConnection() }
    }

    private fun createChrome(context: Context, theme: ReverbTheme): ReverbBrowserChrome {
        fun action(id: String) = ReverbBrowserChromeAction(id, id, ReverbBrowserChromeIcon.Shared(ReverbIcon.Browser))
        return ReverbBrowserChrome(context, theme, "Address", action("site"), action("tabs"), action("menu"))
    }

    private fun actions(chrome: ReverbBrowserChrome) = listOf(chrome.siteAction, chrome.tabsAction, chrome.menuAction)
    private fun toolbar(chrome: ReverbBrowserChrome) = (chrome.chromeToolbar as ViewGroup).getChildAt(0) as ViewGroup
    private fun bounds(owner: ViewGroup, child: View) = Rect().also {
        // Drawing coordinates include the editor's own scroll; the ancestor conversion removes it.
        child.getDrawingRect(it)
        owner.offsetDescendantRectToMyCoords(child, it)
    }
    private fun layout(view: View, width: Int) {
        view.measure(exact(width), exact((640 * view.resources.displayMetrics.density).roundToInt()))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun raster(view: View) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
        view.draw(Canvas(it))
    }
    private fun backgroundRaster(view: View): Bitmap {
        val background = checkNotNull(view.background)
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            background.setBounds(0, 0, view.width, view.height)
            background.draw(Canvas(it))
        }
    }
    private fun key(root: View, code: Int) {
        root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
    private fun fixedDensity(context: Context, scale: Float = 1f) = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { densityDpi = 160; fontScale = scale })
    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
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
                assertTrue(sink.requestFocus())
                activity.setContentView(chrome)
            }
            val deadline = SystemClock.uptimeMillis() + 5000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                main {
                    ready = chrome.isAttachedToWindow && chrome.isShown && chrome.hasWindowFocus() &&
                        chrome.isLaidOut && !chrome.isLayoutRequested && chrome.width > 0 && chrome.height > 0
                }
                if (!ready) SystemClock.sleep(20)
            }
            assertTrue("Browser fixture must be attached, laid out and own window focus", ready)
            main { assertTrue(sink.requestFocus()); block(chrome, sink, theme) }
        } finally { main { installed?.close(); activity.finish() } }
    }
}
