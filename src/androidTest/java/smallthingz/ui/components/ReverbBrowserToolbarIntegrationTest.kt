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

    @Test fun idleAddressHasOneRoundedSurfaceAndActionsKeepTheContinuousToolbarMaterial() = main {
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
                val field = bounds(toolbar, chrome.addressEditor)
                val actual = raster(toolbar)
                val material = backgroundRaster(toolbar)
                try {
                    var addressPixels = 0
                    for (y in 0 until actual.height) for (x in 0 until actual.width) {
                        if (material.getPixel(x, y) != actual.getPixel(x, y)) {
                            assertTrue("Only the address adds an idle surface (dark=$dark)", field.contains(x, y))
                            addressPixels++
                        }
                    }
                    assertTrue("Address must have a persistent silhouette (dark=$dark)", addressPixels > 0)
                    assertEquals("Rounded address corners expose toolbar material",
                        material.getPixel(field.left, field.top), actual.getPixel(field.left, field.top))
                    assertEquals("The editor has no button elevation", 0f, chrome.addressEditor.elevation, 0f)
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

    @Test fun focusedAddressOutlineStaysAtTheViewportWhenTheUrlScrollsAndRetiresOnBlur() = withWindow { chrome, sink, theme ->
        val editor = chrome.addressEditor
        val density = editor.resources.displayMetrics.density
        editor.setText("https://example.test/" + "segment/".repeat(100))
        editor.isCursorVisible = false
        editor.setSelection(0)
        layout(chrome, (320 * density).roundToInt())
        val toolbar = toolbar(chrome)
        val field = bounds(toolbar, editor)
        assertFalse(editor.hasFocus())
        val beforeFocus = raster(toolbar)
        assertTrue(editor.requestFocus())
        // Sample the top and sides of the rounded focus outline, away from text and caret.
        val inset = density.roundToInt().coerceAtLeast(1)
        val samples = listOf(editor.width / 2 to inset, inset to editor.height / 2,
            editor.width - 1 - inset to editor.height / 2)
        for (scroll in listOf(0, (120 * density).roundToInt())) {
            editor.scrollTo(scroll, 0)
            // A scrolled child needs its parent's drawChild translation. Direct editor.draw()
            // incorrectly shifts even its background and cannot qualify viewport-fixed paint.
            val frame = raster(toolbar)
            try {
                assertEquals("The test must actually exercise horizontal scroll", scroll, editor.scrollX)
                for ((x, y) in samples) assertEquals("Focused outline must stay at viewport x=$x,y=$y,scroll=$scroll",
                    theme.palette.pageControls.accent, frame.getPixel(field.left + x, field.top + y))
            } finally { frame.recycle() }
        }
        assertTrue(sink.requestFocus())
        assertFalse(editor.hasFocus())
        val blurred = raster(toolbar)
        try {
            for ((x, y) in samples) assertEquals("Blur retires the outline but retains the address surface at x=$x,y=$y",
                beforeFocus.getPixel(field.left + x, field.top + y), blurred.getPixel(field.left + x, field.top + y))
        } finally { blurred.recycle(); beforeFocus.recycle() }
    }

    @Test fun nativeCompositionHardwareEditingAndGoKeepTheHostsEditorContract() = withWindow { chrome, sink, _ ->
        val editor = chrome.addressEditor
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
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
            assertEquals("Native word selection keeps a plain-text editor", type, editor.inputType)
            assertEquals("Only keyboard metadata uses URI variation", type or InputType.TYPE_TEXT_VARIATION_URI, info.inputType)
            assertEquals(EditorInfo.IME_ACTION_GO, info.imeOptions and EditorInfo.IME_MASK_ACTION)
            assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            assertTrue(connection.setSelection(0, editor.length()))
            assertTrue(connection.setComposingText("draft.test", 1))
            chrome.bindTabCount("2 tabs")
            chrome.bindLoading(.5f, 1L, true, true)
            chrome.bindTheme(ReverbDesign.theme(chrome.context, true))
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

    @Test fun attachedThemeRebindPreservesEditorCompositionAndRendererWithNewPalette() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var installed: ReverbBrowserChrome? = null
        var connection: android.view.inputmethod.InputConnection? = null
        withRetiringTestActivity(activity, root = { installed }, cleanup = {
            try { connection?.closeConnection() } finally { installed?.close() }
        }) {
            lateinit var chrome: ReverbBrowserChrome
            lateinit var renderer: View
            lateinit var editor: NativeTextField
            lateinit var editable: android.text.Editable
            lateinit var composing: List<Pair<Any, Triple<Int, Int, Int>>>
            val focusEvents = mutableListOf<Boolean>()
            var rendererAttaches = 0
            var rendererDetaches = 0
            var rendererCancels = 0
            var admittedRendererCancels = 0
            var submissions = 0
            fun awaitMain(message: String, condition: () -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 5000
                var ready = false
                while (!ready && SystemClock.uptimeMillis() < deadline) {
                    main { ready = condition() }
                    if (!ready) SystemClock.sleep(16)
                }
                assertTrue(message, ready)
            }
            fun assertRetained() {
                assertSame(editor, chrome.addressEditor)
                assertSame(editable, editor.text)
                assertEquals("draft.test", editable.toString())
                assertEquals(2, editor.selectionStart)
                assertEquals(7, editor.selectionEnd)
                assertEquals(0, BaseInputConnection.getComposingSpanStart(editable))
                assertEquals(10, BaseInputConnection.getComposingSpanEnd(editable))
                for ((span, before) in composing) {
                    assertEquals(before.first, editable.getSpanStart(span))
                    assertEquals(before.second, editable.getSpanEnd(span))
                    assertEquals(before.third, editable.getSpanFlags(span))
                }
                assertTrue(editor.hasFocus())
                assertEquals(listOf(true), focusEvents)
                assertSame(chrome.pageHost, renderer.parent)
                assertEquals(1, chrome.pageHost.childCount)
                assertSame(renderer, chrome.pageHost.getChildAt(0))
                assertTrue(renderer.isAttachedToWindow)
                assertEquals(1, rendererAttaches)
                assertEquals(0, rendererDetaches)
                assertEquals("Paint-only binding must not cancel caller-owned renderer input",
                    admittedRendererCancels, rendererCancels)
            }
            main {
                chrome = createChrome(activity, ReverbDesign.theme(activity, false))
                installed = chrome
                renderer = object : View(activity) {
                    override fun onAttachedToWindow() { super.onAttachedToWindow(); rendererAttaches++ }
                    override fun onDetachedFromWindow() { rendererDetaches++; super.onDetachedFromWindow() }
                    override fun onCancelPendingInputEvents() { rendererCancels++; super.onCancelPendingInputEvents() }
                }.apply { isFocusableInTouchMode = true }
                chrome.attachRenderer(renderer)
                activity.setContentView(chrome)
            }
            awaitMain("Browser must be attached, laid out and own window focus") {
                chrome.isAttachedToWindow && chrome.isShown && chrome.hasWindowFocus() &&
                    chrome.isLaidOut && !chrome.isLayoutRequested && chrome.width > 0
            }
            main {
                editor = chrome.addressEditor
                editor.setText("example.test")
                editor.onFocusChangeListener = View.OnFocusChangeListener { _, focused -> focusEvents.add(focused) }
                editor.imeOptions = EditorInfo.IME_ACTION_GO
                editor.setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_GO) { submissions++; true } else false
                }
                assertTrue(renderer.requestFocus())
                focusEvents.clear()
                assertTrue(editor.requestFocus())
                editable = editor.text
                connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
                assertTrue(checkNotNull(connection).setSelection(0, editor.length()))
                assertTrue(checkNotNull(connection).setComposingText("draft.test", 1))
                assertTrue(checkNotNull(connection).setSelection(2, 7))
                composing = editable.getSpans(0, editable.length, Any::class.java)
                    .filter { editable.getSpanFlags(it) and android.text.Spanned.SPAN_COMPOSING != 0 }
                    .map { it to Triple(editable.getSpanStart(it), editable.getSpanEnd(it), editable.getSpanFlags(it)) }
                assertTrue("Precondition: an actual native composing span is present", composing.isNotEmpty())
                admittedRendererCancels = rendererCancels
                assertRetained()
            }
            // Both directions repaint the live retained objects. Wait off Main for native ink motion.
            for (dark in listOf(true, false)) {
                lateinit var next: ReverbTheme
                main {
                    next = ReverbDesign.theme(activity, dark)
                    assertNotEquals("Precondition: rebind changes text ink", next.palette.pageControls.content, editor.currentTextColor)
                    chrome.bindTheme(next)
                    assertRetained()
                }
                awaitMain("Live editor must publish the new palette (dark=$dark)") {
                    editor.currentTextColor == next.palette.pageControls.content &&
                        editor.currentHintTextColor == next.palette.pageControls.muted &&
                        editor.highlightColor == next.palette.pageControls.selection
                }
                main {
                    assertRetained()
                    assertEquals(next.palette.pageControls.content, editor.currentTextColor)
                    assertEquals(next.palette.pageControls.muted, editor.currentHintTextColor)
                    assertEquals(next.palette.pageControls.selection, editor.highlightColor)
                }
            }
            main {
                assertEquals(0, submissions)
                assertTrue(checkNotNull(connection).performEditorAction(EditorInfo.IME_ACTION_GO))
                assertEquals("The original InputConnection and host action listener remain live", 1, submissions)
                assertTrue(checkNotNull(connection).finishComposingText())
                assertEquals(-1, BaseInputConnection.getComposingSpanStart(editable))
                assertTrue(renderer.requestFocus())
                assertEquals(listOf(true, false), focusEvents)
            }
        }
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
