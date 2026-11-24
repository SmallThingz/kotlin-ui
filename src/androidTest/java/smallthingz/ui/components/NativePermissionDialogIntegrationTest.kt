package smallthingz.ui.components

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Binder
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.ScrollView
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

class NativeLargeFontPermissionActivity : Activity() {
    override fun attachBaseContext(base: Context) {
        val local = Configuration(base.resources.configuration).apply {
            fontScale = 1.8f
            setLocale(Locale.GERMAN)
        }
        super.attachBaseContext(base.createConfigurationContext(local))
    }
}

/**
 * Attached real-device coverage. This suite must not run in an emulator or a simulated View host.
 * Set permission_dialog_exclude_ime=true for an IME-excluded isolated-display fixture. That mode
 * verifies window focus and local input ownership, not system IME composition or keyboard behavior.
 */
@RunWith(AndroidJUnit4::class)
class NativePermissionDialogIntegrationTest {
    private val excludeIme = when (InstrumentationRegistry.getArguments().getString("permission_dialog_exclude_ime")) {
        null, "false" -> false
        "true" -> true
        else -> error("permission_dialog_exclude_ime must be true or false")
    }

    @Test fun silentTeardownPreservesCallerAndMatchedEscapeCancelsOnlyOnce() = attached { activity ->
        var cancelled = 0
        val first = prompt(activity, cancel = { cancelled++ })
        showFixture(first)
        first.dismiss()
        assertEquals(0, cancelled)
        val next = prompt(activity, cancel = { cancelled++ })
        showFixture(next)
        next to {
            val window = platform(next)
            val decor = requireNotNull(window.window).decorView
            assertTrue(decor.isAttachedToWindow)
            assertNotNull(decor.keyDispatcherState)
            window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
            assertTrue(window.isShowing)
            next.setBusy(true)
            window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
            next.setBusy(false)
            window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
            assertTrue(window.isShowing)
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)
            val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
            window.dispatchKeyEvent(down)
            assertTrue(window.isShowing)
            assertEquals(0, cancelled)
            window.dispatchKeyEvent(KeyEvent.changeFlags(up, KeyEvent.FLAG_CANCELED))
            assertTrue(window.isShowing)
            window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
            assertTrue(window.isShowing)
            window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
            assertEquals(1, cancelled)
            assertFalse(window.isShowing)
            window.cancel()
            assertEquals(1, cancelled)
        }
    }

    @Test fun focusedScopeConsumesEscapeBeforeTheDialogFallback() = attached { activity ->
        var cancels = 0
        val consumed = mutableListOf<Int>()
        val editor = EditText(activity).apply {
            showSoftInputOnFocus = false
            setOnKeyListener { _, key, event ->
                if (key != KeyEvent.KEYCODE_ESCAPE) false else {
                    consumed += event.action
                    true
                }
            }
        }
        val prompt = prompt(activity, body = editor, cancel = { cancels++ })
        showFixture(prompt)
        prompt to {
            assertTrue(editor.requestFocus())
            val dialog = platform(prompt)
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                dialog.dispatchKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_ESCAPE))
            }
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), consumed)
            // Let the dialog track the DOWN, but let the child take its UP.
            editor.setOnKeyListener { _, key, event -> key == KeyEvent.KEYCODE_ESCAPE && event.action == KeyEvent.ACTION_UP }
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
            editor.setOnKeyListener(null)
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
            assertTrue(dialog.isShowing)
            assertEquals(0, cancels)
        }
    }

    @Test fun showReadsClearedSecureFlagAndExplicitRecipeOverridesWindowPreset() = withActivity { activity ->
        val secure = WindowManager.LayoutParams.FLAG_SECURE
        activity.window.addFlags(secure)
        val defaults = NativeUiAppearance.Default
        val inherited = defaults.modals.permissionDialog.copy(dimAmount = .35f)
        val appearance = defaults.copy(modals = defaults.modals.copy(permissionDialog = inherited))
        val prompt = NativePermissionDialog(activity, NativeColorScheme.fallback(false), null, "Reader", "Request",
            "Deny", "Allow", onDeny = {}, onAllow = {}, onCancel = {}, appearance = appearance,
            recipe = inherited.copy(dimAmount = .6f))
        activity.window.clearFlags(secure)
        try {
            showFixture(prompt)
            val attrs = requireNotNull(platform(prompt).window).attributes
            assertEquals(0, attrs.flags and secure)
            assertEquals(.6f, attrs.dimAmount, .001f)
        } finally { prompt.dismiss() }
    }

    @Test fun invalidWindowTokenSilentlyRetiresTheFailedPresentation() = withActivity { activity ->
        var allows = 0
        var denies = 0
        var cancels = 0
        val prompt = prompt(activity, allow = { allows++ }, deny = { denies++ }, cancel = { cancels++ })
        val dialog = platform(prompt)
        val window = requireNotNull(dialog.window)
        window.attributes = window.attributes.apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
            token = Binder()
        }
        assertThrows(WindowManager.BadTokenException::class.java) { showFixture(prompt) }
        // Dialog.isShowing can see a visible DecorView even when WindowManager rejected its token.
        val decor = window.decorView
        assertFalse(decor.isAttachedToWindow)
        assertNull(decor.windowToken)
        val buttons = nodes(decor).filterIsInstance<NativeMaterialButtonView>()
        assertEquals(setOf("Allow", "Deny"), buttons.map { it.label.getText() }.toSet())
        assertEquals(2, buttons.size)
        for (button in buttons) {
            assertFalse(button.isEnabled)
            assertFalse(button.performClick())
            assertFalse(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        }
        assertThrows(IllegalStateException::class.java) { showFixture(prompt) }
        dialog.cancel()
        prompt.dismiss()
        assertEquals(0, allows)
        assertEquals(0, denies)
        assertEquals(0, cancels)
    }

    @Test fun busyRejectsRepeatGrantAndTerminalErrorKeepsDenyAvailable() = attached { activity ->
        var allows = 0
        var denies = 0
        val prompt = prompt(activity, allow = { allows++ }, deny = { denies++ })
        showFixture(prompt)
        prompt to {
            val buttons = nodes(platform(prompt).window!!.decorView).filterIsInstance<NativeMaterialButtonView>()
            val allow = buttons.single { it.label.getText() == "Allow" }
            val deny = buttons.single { it.label.getText() == "Deny" }
            assertTrue(allow.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            allow.performClick()
            assertEquals(1, allows)
            assertFalse(allow.isEnabled || deny.isEnabled)
            platform(prompt).cancel()
            assertTrue(platform(prompt).isShowing)
            prompt.setAllowEnabled(false)
            prompt.setError("Request expired")
            prompt.setBusy(false)
            assertFalse(allow.isEnabled)
            assertTrue(deny.isEnabled)
            deny.performClick()
            deny.performClick()
            assertEquals(1, denies)
        }
    }

    @Test fun unfocusedPermissionWindowRejectsRetainedActionsAndFocusedActionRespondsOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (selected in listOf("Allow", "Deny")) {
            val responses = mutableListOf<String>()
            withFocusWindows({ activity ->
                prompt(activity, allow = { responses += "Allow" }, deny = { responses += "Deny" },
                    cancel = { responses += "Cancel" })
            }) { prompt, cover, decor ->
                val dialog = platform(prompt)
                lateinit var buttons: List<NativeMaterialButtonView>
                instrumentation.runOnMainSync {
                    buttons = nodes(decor).filterIsInstance<NativeMaterialButtonView>()
                    assertEquals(2, buttons.size)
                }
                changeWindowFocus(decor, false) { showFixture(cover) }
                instrumentation.runOnMainSync {
                    assertTrue(requireNotNull(cover.window).decorView.hasWindowFocus())
                    assertTrue(dialog.isShowing && decor.isAttachedToWindow && decor.isShown)
                    for (button in buttons) {
                        assertTrue(button.isEnabled)
                        assertFalse(button.performClick())
                        assertFalse(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                        // A retained semantic delegate bypasses the View entry point. The
                        // permission callback still owns its independent focus admission.
                        button.mergedRowAccessibility.performAccessibilityAction(button,
                            AccessibilityNodeInfo.ACTION_CLICK, null)
                        assertTrue(responses.isEmpty())
                    }
                    dialog.cancel()
                    dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                    dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
                    assertTrue(dialog.isShowing)
                    assertTrue(responses.isEmpty())
                }
                changeWindowFocus(decor, true) { cover.dismiss() }
                instrumentation.runOnMainSync {
                    val button = buttons.single { it.label.getText() == selected }
                    assertTrue(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                    assertFalse(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                    assertFalse(button.performClick())
                    assertEquals(listOf(selected), responses)
                }
            }
        }
    }

    @Test fun activationKeyCannotSurvivePermissionWindowFocusLossOrRestoration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var allows = 0
        withFocusWindows({ activity -> prompt(activity, allow = { allows++ }) }) { prompt, cover, decor ->
            val dialog = platform(prompt)
            lateinit var allow: NativeMaterialButtonView
            instrumentation.runOnMainSync {
                allow = nodes(decor).filterIsInstance<NativeMaterialButtonView>()
                    .single { it.label.getText() == "Allow" }
                assertTrue(allow.requestFocusFromTouch())
            }
            var expected = 0
            for (key in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE)) {
                for (startFocused in listOf(true, false)) {
                    instrumentation.runOnMainSync {
                        assertTrue(allow.requestFocusFromTouch())
                        if (startFocused) assertTrue(dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
                    }
                    changeWindowFocus(decor, false) { showFixture(cover) }
                    instrumentation.runOnMainSync {
                        // Local View focus is retained: ordinary onFocusChanged(false) cannot
                        // accidentally supply the cancellation this regression requires.
                        assertTrue(allow.isFocused)
                        if (!startFocused) {
                            // A retained child endpoint can receive stale input directly. Even
                            // that pending input must be retired before the window is readmitted.
                            allow.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                        }
                        assertEquals(expected, allows)
                    }
                    changeWindowFocus(decor, true) { cover.dismiss() }
                    instrumentation.runOnMainSync {
                        assertTrue(allow.isFocused)
                        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                        assertEquals("Stale key UP crossed window focus", expected, allows)
                        assertTrue(dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
                        assertTrue(dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)))
                        expected++
                        assertEquals(expected, allows)
                        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                        assertEquals(expected, allows)
                        prompt.setBusy(false)
                    }
                }
            }
        }
    }

    @Test fun trackedEscapeCannotCrossPermissionWindowFocusAndFreshEscapeCancelsOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var cancels = 0
        withFocusWindows({ activity -> prompt(activity, cancel = { cancels++ }) }) { prompt, cover, decor ->
            val dialog = platform(prompt)
            instrumentation.runOnMainSync {
                assertTrue(dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)))
            }
            changeWindowFocus(decor, false) { showFixture(cover) }
            changeWindowFocus(decor, true) { cover.dismiss() }
            instrumentation.runOnMainSync {
                // Retain tracking explicitly so framework dispatcher cleanup cannot mask stale
                // Escape state in the permission owner itself.
                val up = KeyEvent.changeFlags(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE), KeyEvent.FLAG_TRACKING)
                assertTrue(dialog.onKeyUp(KeyEvent.KEYCODE_ESCAPE, up))
                assertTrue(dialog.isShowing)
                assertEquals(0, cancels)
                dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
                assertFalse(dialog.isShowing)
                assertEquals(1, cancels)
                dialog.cancel()
                assertEquals(1, cancels)
            }
        }
    }

    @Test fun secureNarrowWindowKeepsEditorAndActionsInsideScrollableBounds() = attached { activity ->
        val editor = EditText(activity).apply { setText("retained"); setSelection(3) }
        val defaults = NativeUiAppearance.Default
        val appearance = defaults.copy(modals = defaults.modals.copy(permissionDialog =
            defaults.modals.permissionDialog.copy(dimAmount = .35f)))
        val prompt = prompt(activity, body = editor, summary = "Allow access? ".repeat(25), appearance = appearance)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        showFixture(prompt)
        prompt to {
            val dialog = platform(prompt)
            assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertEquals(.35f, dialog.window!!.attributes.dimAmount, .001f)
            val surface = dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            val density = activity.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            for ((width, height) in listOf(180 to 140, 280 to 210)) {
                surface.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(height), View.MeasureSpec.AT_MOST))
                surface.layout(0, 0, surface.measuredWidth, surface.measuredHeight)
                assertTrue(surface.height <= dp(height))
                val buttons = nodes(surface).filterIsInstance<NativeMaterialButtonView>()
                assertEquals(2, buttons.size)
                for (button in buttons) {
                    assertTrue(button.width >= dp(48))
                    assertTrue(button.height >= dp(48))
                    assertTrue(button.isFocusable)
                }
                val scrolls = nodes(surface).filterIsInstance<ScrollView>()
                assertEquals(2, scrolls.size)
                val footer = scrolls.last()
                assertTrue(scrolls.first().height > 0 && footer.height > 0)
                if (height == 210 && footer.getChildAt(0).height <= dp(137)) {
                    assertFalse("Both complete actions fit", footer.canScrollVertically(1))
                }
                val deny = buttons.single { it.label.getText() == "Deny" }
                footer.scrollTo(0, footer.getChildAt(0).height)
                val visibleDeny = Rect()
                assertTrue(deny.getLocalVisibleRect(visibleDeny))
                assertEquals(deny.width, visibleDeny.width())
                assertEquals(minOf(deny.height, footer.height), visibleDeny.height())
                assertEquals(deny.height, visibleDeny.bottom)
                for (scroll in scrolls) {
                    val bounds = Rect(0, 0, scroll.width, scroll.height)
                    surface.offsetDescendantRectToMyCoords(scroll, bounds)
                    assertTrue(bounds.left >= 0 && bounds.right <= surface.width)
                    assertTrue(bounds.bottom <= surface.height)
                }
            }
            assertTrue(nodes(surface).contains(editor))
            assertEquals("retained", editor.text.toString())
            assertEquals(3, editor.selectionStart)
        }
    }

    @Test fun largeGermanActionsWrapCompletelyAndBothEndsOfFooterCanBeReached() {
        val rasterRun = InstrumentationRegistry.getArguments().getString("permission_dialog_rasters")
        val rasterDir = rasterRun?.let { name ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == InstrumentationRegistry.getInstrumentation().context.packageName)
            require(name.matches(Regex("[A-Za-z0-9_-]{1,48}")))
            File(context.filesDir, "permission-dialog-rasters-$name").also { check(it.mkdir()) }
        }
        val manifest = mutableListOf<String>()
        if (rasterDir != null) {
            for ((theme, dark) in listOf("light" to false, "dark" to true)) {
                attached { activity ->
                    val prompt = NativePermissionDialog(activity, NativeColorScheme.fallback(dark), null,
                        "Folder access", "Allow access to the selected folder?", "Deny", "Allow",
                        onDeny = {}, onAllow = {}, onCancel = {})
                    showFixture(prompt)
                    prompt to {
                        val surface = platform(prompt).findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                        manifest += writePermissionRaster(surface, rasterDir, "english-$theme.png")
                    }
                }
            }
        }
        attached(NativeLargeFontPermissionActivity::class.java) { activity ->
            val allow = "Zugriff auf die ausgewählten Inhalte erlauben"
            val deny = "Zugriff auf die ausgewählten Inhalte verweigern"
            val prompt = NativePermissionDialog(activity, NativeColorScheme.fallback(false), null, "Lesegerät",
                "Zugriff auf die ausgewählten Inhalte zulassen?", deny, allow, onDeny = {}, onAllow = {}, onCancel = {})
            showFixture(prompt)
            prompt to {
                val configuration = activity.resources.configuration
                assertEquals(1.8f, configuration.fontScale, .001f)
                assertEquals("de", configuration.locales[0].language)
                val surface = platform(prompt).findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
                val density = activity.resources.displayMetrics.density
                surface.measure(View.MeasureSpec.makeMeasureSpec((280 * density).roundToInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((200 * density).roundToInt(), View.MeasureSpec.AT_MOST))
                surface.layout(0, 0, surface.measuredWidth, surface.measuredHeight)
                val footer = nodes(surface).filterIsInstance<ScrollView>().last()
                val buttons = nodes(footer).filterIsInstance<NativeMaterialButtonView>()
                assertEquals(listOf(allow, deny), buttons.map { it.label.getText() })
                for (button in buttons) {
                    val paragraph = checkNotNull(button.label.currentParagraph)
                    assertTrue(paragraph.lineCount > 1)
                    assertFalse(paragraph.didExceedMaxLines)
                    assertEquals(button.label.getText().length, paragraph.getLineEnd(paragraph.lineCount - 1))
                    assertTrue(paragraph.height <= button.label.height + 1f)
                    assertTrue(button.label.top >= 0 && button.label.bottom <= button.height)
                }
                footer.scrollTo(0, 0)
                assertTrue(footer.canScrollVertically(1))
                val visible = Rect()
                assertTrue(buttons.first().getLocalVisibleRect(visible))
                assertEquals(0, visible.top)
                if (rasterDir != null) manifest += writePermissionRaster(surface, rasterDir, "german-top.png")
                footer.scrollTo(0, footer.getChildAt(0).height)
                assertFalse(footer.canScrollVertically(1))
                assertTrue(buttons.last().getLocalVisibleRect(visible))
                assertEquals(buttons.last().height, visible.bottom)
                if (rasterDir != null) manifest += writePermissionRaster(surface, rasterDir, "german-bottom.png")
            }
        }
        if (rasterDir != null) {
            check(manifest.size == 4)
            File(rasterDir, "manifest.tsv").writeText(
                "file\twidth\theight\tsha256\n" + manifest.joinToString("\n", postfix = "\n"),
            )
        }
    }

    private fun writePermissionRaster(view: View, directory: File, name: String): String {
        check(view.isAttachedToWindow && view.isShown && view.isLaidOut)
        check(view.width in 1..2048 && view.height in 1..2048)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val file = File(directory, name).also { check(it.createNewFile()) }
        try {
            view.draw(Canvas(bitmap))
            val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            check(center ushr 24 != 0) { "Permission prompt surface raster is transparent" }
            val colorVariation = (1..15).any { row ->
                (1..15).any { column ->
                    val pixel = bitmap.getPixel(bitmap.width * column / 16, bitmap.height * row / 16)
                    pixel ushr 24 != 0 && pixel != center
                }
            }
            check(colorVariation) { "Permission prompt raster has no interior drawn detail" }
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "$name\t${bitmap.width}\t${bitmap.height}\t$digest"
        } finally {
            bitmap.recycle()
        }
    }

    @Suppress("DEPRECATION")
    @Test fun platformBackIgnoresBusyAndCancelsIdlePresentationOnlyOnce() = attached { activity ->
        var cancels = 0
        val prompt = prompt(activity, cancel = { cancels++ })
        showFixture(prompt)
        prompt to {
            val dialog = platform(prompt)
            prompt.setBusy(true)
            dialog.onBackPressed()
            assertTrue(dialog.isShowing)
            assertEquals(0, cancels)
            prompt.setBusy(false)
            dialog.onBackPressed()
            assertFalse(dialog.isShowing)
            assertEquals(1, cancels)
            dialog.onBackPressed()
            assertEquals(1, cancels)
        }
    }

    @Test fun obscuredGestureThroughDialogCannotGrantAndOutsideTapDoesNotDiscardRequest() = attached { activity ->
        var allows = 0
        var cancels = 0
        val scopeEvents = mutableListOf<Int>()
        val scope = View(activity).apply {
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            filterTouchesWhenObscured = true
            setOnTouchListener { _, event -> scopeEvents += event.actionMasked; true }
        }
        val prompt = prompt(activity, body = scope, allow = { allows++ }, cancel = { cancels++ })
        showFixture(prompt)
        prompt to {
            val dialog = platform(prompt)
            val allow = nodes(dialog.window!!.decorView).filterIsInstance<NativeMaterialButtonView>()
                .single { it.label.getText() == "Allow" }
            fun touch(action: Int, flags: Int = 0, view: View = allow) {
                val target = IntArray(2).also(view::getLocationInWindow)
                val x = target[0] + view.width / 2f
                val y = target[1] + view.height / 2f
                val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y }
                val event = MotionEvent.obtain(1, 2, action, 1, arrayOf(properties), arrayOf(coords),
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, flags)
                try { dialog.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            val flags = mutableListOf(MotionEvent.FLAG_WINDOW_IS_OBSCURED)
            if (Build.VERSION.SDK_INT >= 29) flags += MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
            for (flag in flags) {
                scopeEvents.clear()
                touch(MotionEvent.ACTION_DOWN, view = scope)
                touch(MotionEvent.ACTION_MOVE, flag, scope)
                touch(MotionEvent.ACTION_UP, view = scope)
                assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), scopeEvents)
                touch(MotionEvent.ACTION_DOWN)
                touch(MotionEvent.ACTION_MOVE, flag)
                touch(MotionEvent.ACTION_UP)
                assertEquals(0, allows)
            }
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_UP)
            assertEquals(1, allows)
            prompt.setBusy(false)
            val outside = MotionEvent.obtain(1, 2, MotionEvent.ACTION_OUTSIDE, -100f, -100f, 0)
            try { dialog.dispatchTouchEvent(outside) } finally { outside.recycle() }
            assertTrue(dialog.isShowing)
            assertEquals(0, cancels)
            // A clean keyboard path remains available after rejected pointer input.
            assertTrue(allow.requestFocusFromTouch())
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
            assertEquals(2, allows)
        }
    }

    private fun prompt(activity: Activity, body: View? = null, summary: String = "Allow access to Injector?",
        appearance: NativeUiAppearance = NativeUiAppearance.Default,
        allow: () -> Unit = {}, deny: () -> Unit = {}, cancel: () -> Unit = {}) =
        NativePermissionDialog(activity, NativeColorScheme.fallback(false), null, "Reader", summary,
            "Deny", "Allow", body, deny, allow, cancel, appearance)

    private fun platform(prompt: NativePermissionDialog): Dialog =
        NativePermissionDialog::class.java.getDeclaredField("dialog").apply { isAccessible = true }.get(prompt) as Dialog

    private fun showFixture(prompt: NativePermissionDialog) {
        val dialog = platform(prompt)
        prepareFixtureWindow(dialog)
        prompt.show()
        assertFixtureWindow(dialog)
    }

    private fun showFixture(dialog: Dialog) {
        prepareFixtureWindow(dialog)
        dialog.show()
        assertFixtureWindow(dialog)
    }

    private fun prepareFixtureWindow(dialog: Dialog) {
        if (!excludeIme) return
        val window = requireNotNull(dialog.window)
        check(window.peekDecorView()?.isAttachedToWindow != true) {
            "IME exclusion must be set before the fixture window is attached"
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.setSoftInputMode((window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        assertFixtureWindow(dialog)
    }

    private fun assertFixtureWindow(dialog: Dialog) {
        if (!excludeIme) return
        val attrs = requireNotNull(dialog.window).attributes
        assertTrue(attrs.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM != 0)
        assertEquals(0, attrs.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
            attrs.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE)
    }

    private fun nodes(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(nodes(view.getChildAt(i)))
    }

    private fun withActivity(block: (Activity) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { instrumentation.runOnMainSync { block(activity) } }
        finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    /** Both windows belong to this Activity; no global input or system window is used. */
    private fun withFocusWindows(mount: (Activity) -> NativePermissionDialog,
        assertions: (NativePermissionDialog, Dialog, View) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var prompt: NativePermissionDialog? = null
        var cover: Dialog? = null
        lateinit var decor: View
        try {
            instrumentation.runOnMainSync {
                val owned = mount(activity)
                prompt = owned
                showFixture(owned)
                decor = requireNotNull(platform(owned).window).decorView
                cover = Dialog(activity).apply {
                    setContentView(View(activity), ViewGroup.LayoutParams(100, 100))
                }
            }
            changeWindowFocus(decor, true) {}
            assertions(requireNotNull(prompt), requireNotNull(cover), decor)
        } finally {
            instrumentation.runOnMainSync {
                try { cover?.dismiss() } finally {
                    try { prompt?.dismiss() } finally { activity.finish() }
                }
            }
        }
    }

    private fun changeWindowFocus(view: View, focused: Boolean, change: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val changed = CountDownLatch(1)
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { if (it == focused) changed.countDown() }
        instrumentation.runOnMainSync { view.viewTreeObserver.addOnWindowFocusChangeListener(listener) }
        try {
            instrumentation.runOnMainSync {
                change()
                if (view.hasWindowFocus() == focused) changed.countDown()
            }
            assertTrue("Permission window focus did not become $focused", changed.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertEquals(focused, view.hasWindowFocus()) }
        } finally {
            instrumentation.runOnMainSync { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
        }
    }

    private fun attached(activityClass: Class<out Activity> = NativeMenuAccessibilityTestActivity::class.java,
        mount: (Activity) -> Pair<NativePermissionDialog, () -> Unit>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            activityClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var prompt: NativePermissionDialog? = null
        try {
            lateinit var assertions: () -> Unit
            instrumentation.runOnMainSync {
                val result = mount(activity)
                prompt = result.first
                assertions = result.second
            }
            lateinit var decor: View
            instrumentation.runOnMainSync { decor = requireNotNull(platform(requireNotNull(prompt)).window).decorView }
            changeWindowFocus(decor, true) {}
            instrumentation.runOnMainSync(assertions)
        } finally {
            instrumentation.runOnMainSync { prompt?.dismiss(); activity.finish() }
        }
    }
}
