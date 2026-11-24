package smallthingz.ui.components

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.GridLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.common.ToggleState
import smallthingz.ui.reverb.*

@RunWith(AndroidJUnit4::class)
class ReverbBrowserPanelIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    // Test-only opt-in; normal instrumentation retains the platform's real IME behavior.
    private val excludeIme = when (InstrumentationRegistry.getArguments().getString("reverb_semantic_exclude_ime")) {
        null, "false" -> false
        "true" -> true
        else -> error("reverb_semantic_exclude_ime must be true or false")
    }
    private class Gate(activity: Activity) : FrameLayout(activity), NativeInputGate {
        var admitted = true
        var probe: (() -> Unit)? = null
        override val acceptsNativeInput: Boolean get() { probe?.invoke(); return admitted }
    }
    private fun activity() = instrumentation.startActivitySync(Intent(instrumentation.context,
        NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun idle() = instrumentation.waitForIdleSync()

    @Test fun settingsBindSilentlyAndRetainedRowsRequireFreshOwnerAdmission() {
        val activity = activity()
        lateinit var gate: Gate
        lateinit var panel: ReverbBrowserPanelSettings
        var installed: AutoCloseable? = null
        lateinit var row: ReverbBrowserPanelSettingRow
        lateinit var toggle: ReverbBrowserPanelToggle
        lateinit var choice: ReverbBrowserPanelChoice
        val events = mutableListOf<String>()
        try {
            main {
                gate = Gate(activity)
                panel = ReverbBrowserPanelSettings(activity, ReverbDesign.theme(activity, false), "Settings", "Back", "Undo", "Apply")
                installed = panel
                row = panel.createSetting(ReverbBrowserPanelSetting("Clear", actionId = "clear")) { events.add(it) }
                toggle = panel.createToggle("cookies", ToggleState("Cookies", false, true)) { id, value -> events.add("$id:$value") }
                choice = panel.createChoice("search", "Search", listOf(ReverbBrowserPanelOption("a", "A", "Search, A"), ReverbBrowserPanelOption("b", "B", "Search, B")), "a",
                    onSelect = { id, value -> events.add("$id:$value"); true }, onAction = { events.add(it) })
                panel.addGroup(null, listOf(row, toggle, choice))
                panel.onPanelAction = { events.add(it.name) }
                gate.addView(panel)
                activity.setContentView(gate)
            }
            idle()
            main {
                assertTrue(panel.hasWindowFocus())
                toggle.bind(ToggleState("Cookies", true, true))
                choice.bind(listOf(ReverbBrowserPanelOption("a", "A", "Search, A"), ReverbBrowserPanelOption("b", "B", "Search, B")), "b")
                panel.bindPendingActions(true)
                assertTrue(events.isEmpty())
                val chip = choice.descendants().filterIsInstance<NativeChip>().single { it.text.toString() == "A" }
                assertEquals("Search, A", chip.contentDescription)
                row.performClick(); toggle.view.performClick()
                assertEquals(listOf("clear", "cookies:false"), events)
                events.clear()
                val options = listOf(ReverbBrowserPanelOption("a", "A", "Search, A"), ReverbBrowserPanelOption("b", "B", "Search, B"))
                choice.bind(options, "b", ReverbBrowserPanelSetting("Open Android settings", actionId = "recover"))
                val recovery = choice.descendants().filterIsInstance<ReverbBrowserPanelSettingRow>().single()
                recovery.performClick()
                assertEquals(listOf("recover"), events)
                events.clear()
                choice.bind(options, "b")
                recovery.performClick()
                assertTrue(events.isEmpty())
                val apply = panel.descendants().filterIsInstance<ReverbToolbarActionView>().single { it.accessibilityAction.tooltipText == "Apply" }
                panel.bindPendingActions(false)
                apply.accessibilityAction.performClick()
                gate.admitted = false
                row.performClick(); toggle.view.performClick()
                choice.descendants().filterIsInstance<NativeChip>().single { it.text.toString() == "A" }.performClick()
                assertTrue(events.isEmpty())
                gate.admitted = true
                panel.visibility = View.GONE
                row.performClick(); toggle.view.performClick()
                panel.visibility = View.VISIBLE
                // Admission itself may retire the current panel; no callback may follow it.
                gate.probe = { panel.visibility = View.GONE }
                row.performClick()
                gate.probe = null
                panel.visibility = View.VISIBLE
                gate.removeView(panel)
                row.performClick(); toggle.view.performClick()
                assertTrue(events.isEmpty())
                gate.addView(panel)
                row.performClick()
                assertEquals(listOf("clear"), events)
                choice.descendants().filterIsInstance<NativeChip>().single { it.text.toString() == "A" }.performClick()
                assertEquals(listOf("clear", "search:a"), events)
                events.clear()
                panel.close()
                row.performClick(); toggle.view.performClick(); apply.accessibilityAction.performClick()
                assertTrue(events.isEmpty())
            }
        } finally { main { installed?.close(); activity.finish() } }
    }

    @Test fun windowFocusLossCancelsPendingKeysAndDirectActions() {
        val activity = activity()
        lateinit var panel: ReverbBrowserPanelSettings
        var installed: AutoCloseable? = null
        lateinit var row: ReverbBrowserPanelSettingRow
        var modal: Dialog? = null
        val events = mutableListOf<String>()
        try {
            main {
                panel = ReverbBrowserPanelSettings(activity, ReverbDesign.theme(activity, false), "Settings", "Back", "Undo", "Apply")
                installed = panel
                row = panel.createSetting(ReverbBrowserPanelSetting("Clear", actionId = "clear")) { events.add(it) }
                panel.addGroup(null, listOf(row))
                activity.setContentView(panel)
            }
            idle()
            main {
                assertTrue(panel.hasWindowFocus())
                row.isFocusableInTouchMode = true
                assertTrue(row.requestFocus())
                row.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                modal = Dialog(activity).apply { setContentView(FrameLayout(activity)) }
                showFixture(requireNotNull(modal))
            }
            idle()
            main {
                assertFalse(panel.hasWindowFocus())
                row.performClick()
                assertTrue(events.isEmpty())
                modal?.dismiss()
            }
            idle()
            main {
                assertTrue(panel.hasWindowFocus())
                row.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                assertTrue(events.isEmpty())
                row.performClick()
                assertEquals(listOf("clear"), events)
            }
        } finally { main { modal?.dismiss(); installed?.close(); activity.finish() } }
    }

    @Test fun throwingFocusRetirementReleasesAllTabPreviewsWithoutRecyclingHostBitmaps() {
        val activity = activity()
        var installed: ReverbBrowserPanelTabs<String>? = null
        var focused: View? = null
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val failure = IllegalStateException("focus retirement")
        var focusLosses = 0
        try {
            main {
                val panel = ReverbBrowserPanelTabs<String>(activity, ReverbDesign.theme(activity, false),
                    "Tabs", "Back", "New tab").also { installed = it }
                val a = ReverbBrowserPanelTab("a", "Alpha", null, bitmap, null, true, "Current", "Close Alpha")
                panel.bindTabs("Tabs", listOf(a, a.copy(key = "b", title = "Beta")))
                panel.onTabAction = { _, _ -> fail("Retired tab action") }
                activity.setContentView(panel)
            }
            idle()
            main {
                val panel = checkNotNull(installed)
                val images = panel.descendants().filterIsInstance<ImageView>()
                    .filter { (it.drawable as? BitmapDrawable)?.bitmap === bitmap }.toList()
                assertEquals(2, images.size)
                assertTrue(images.all { it.drawable != null })
                val row = panel.descendants().filterIsInstance<ReverbBrowserPanelSettingRow>().first()
                focused = row
                row.isFocusableInTouchMode = true
                assertTrue(row.requestFocus())
                row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        focusLosses++
                        panel.close() // Already retired; cannot retain or restart ownership.
                        throw failure
                    }
                }
                assertSame(failure, runCatching { panel.close() }.exceptionOrNull())
                assertNull(panel.onTabAction)
                assertTrue(images.all { it.drawable == null })
                assertFalse(bitmap.isRecycled)
                assertEquals(0, panel.descendants().filterIsInstance<GridLayout>().single().childCount)
                // The closed panel must release its snapshot registry as well as painted images.
                val cards = ReverbBrowserPanelTabs::class.java.getDeclaredField("cards").apply { isAccessible = true }
                assertTrue((cards.get(panel) as Map<*, *>).isEmpty())
                row.performClick()
                panel.close()
                assertEquals(1, focusLosses)
            }
        } finally {
            main {
                focused?.onFocusChangeListener = null
                try { installed?.close() } finally { activity.finish() }
            }
            bitmap.recycle()
        }
    }

    @Test fun tabReorderRemovalAndDisabledBindingsKeepIdentityAndFocus() {
        val activity = activity()
        lateinit var panel: ReverbBrowserPanelTabs<String>
        lateinit var gate: Gate
        var installed: AutoCloseable? = null
        val events = mutableListOf<Pair<String?, ReverbBrowserPanelTabAction>>()
        val a = ReverbBrowserPanelTab("a", "Alpha", "https://example.test", null, null, true, "Current", "Close Alpha")
        val b = a.copy(key = "b", title = "Beta", current = false, closeLabel = "Close Beta")
        try {
            main {
                panel = ReverbBrowserPanelTabs(activity, ReverbDesign.theme(activity, false), "Tabs", "Back", "New tab")
                installed = panel
                panel.onTabAction = { key, action -> events.add(key to action) }
                panel.bindTabs("Two tabs", listOf(a, b))
                gate = Gate(activity)
                gate.addView(panel)
                activity.setContentView(gate)
            }
            idle()
            main {
                val alpha = panel.descendants().filterIsInstance<ReverbBrowserPanelSettingRow>().single { it.titleView.text == "Alpha" }
                val beta = panel.descendants().filterIsInstance<ReverbBrowserPanelSettingRow>().single { it.titleView.text == "Beta" }
                // Exercise native focus without changing the device/display's global touch mode.
                alpha.isFocusableInTouchMode = true
                beta.isFocusableInTouchMode = true
                assertTrue(alpha.requestFocus())
                panel.bindTabs("Two tabs", listOf(b, a.copy(title = "Renamed")))
                assertSame(alpha, panel.findFocus())
                alpha.performClick()
                assertEquals(listOf("a" to ReverbBrowserPanelTabAction.SELECT), events)
                events.clear()
                val close = panel.descendants().filterIsInstance<ReverbStandardIconButtonView>().single { it.visual.glyph.contentDescription == "Close Alpha" }
                gate.admitted = false
                close.visual.performClick(); alpha.performClick()
                assertTrue(events.isEmpty())
                gate.admitted = true
                close.visual.performClick()
                assertEquals(listOf("a" to ReverbBrowserPanelTabAction.CLOSE), events)
                events.clear()
                panel.visibility = View.GONE
                close.visual.performClick(); alpha.performClick()
                panel.visibility = View.VISIBLE
                assertTrue(events.isEmpty())
                panel.updateTab(a.copy(enabled = false))
                close.visual.performClick(); alpha.performClick()
                assertTrue(events.isEmpty())
                // Hiding/disabling a card can clear its focus. Re-establish ownership so the
                // removal assertion exercises focus transfer, not an unrelated visibility reset.
                panel.updateTab(a.copy(title = "Renamed"))
                assertTrue(alpha.isEnabled)
                assertTrue(alpha.requestFocus())
                assertSame(alpha, panel.findFocus())
                assertTrue(events.isEmpty())
                panel.bindTabs("One tab", listOf(b))
                close.visual.performClick(); alpha.performClick()
                assertTrue(events.isEmpty())
                assertSame(beta, panel.findFocus())
                panel.close()
                panel.descendants().filterIsInstance<ReverbToolbarActionView>().first().accessibilityAction.performClick()
                assertTrue(events.isEmpty())
            }
        } finally { main { installed?.close(); activity.finish() } }
    }

    @Test fun attachedNarrowRtlPanelsRetainActionTargetsAndContainment() {
        val activity = activity()
        val installed = mutableListOf<AutoCloseable>()
        try {
            main {
                val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { fontScale = 2f })
                val theme = ReverbDesign.theme(context, false)
                val host = FrameLayout(activity)
                activity.setContentView(host)
                for (width in listOf(224, 320)) {
                    val panel = ReverbBrowserPanelTabs<String>(context, theme, "Tabs", "Back", "New tab")
                    installed.add(panel)
                    panel.layoutDirection = View.LAYOUT_DIRECTION_RTL
                    panel.bindTabs("Two tabs", listOf("a", "b").map {
                        ReverbBrowserPanelTab(it, "A long localized tab title", null, null, null, it == "a", "Current", "Close $it")
                    })
                    host.addView(panel)
                    val pixels = (width * theme.density.density).toInt()
                    val height = (900 * theme.density.density).toInt()
                    panel.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    panel.layout(0, 0, pixels, height)
                    panel.descendants().filterIsInstance<ReverbStandardIconButtonView>().forEach {
                        val rect = Rect(0, 0, it.width, it.height)
                        panel.offsetDescendantRectToMyCoords(it, rect)
                        assertTrue(rect.left >= 0 && rect.right <= pixels)
                        assertTrue(it.width >= (48 * theme.density.density).toInt())
                    }
                    host.removeView(panel)
                    val settings = ReverbBrowserPanelSettings(context, theme, "Browser Settings", "Back", "Undo", "Apply")
                    installed.add(settings)
                    settings.layoutDirection = View.LAYOUT_DIRECTION_RTL
                    val selector = settings.createChoice("permission", "A long localized permission label",
                        listOf(ReverbBrowserPanelOption("ask", "Always ask"), ReverbBrowserPanelOption("allow", "Allow"),
                            ReverbBrowserPanelOption("block", "Block")), "ask", onSelect = { _, _ -> true })
                    settings.addGroup("Permissions", listOf(selector))
                    host.addView(settings)
                    settings.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    settings.layout(0, 0, pixels, height)
                    val chips = selector.descendants().filterIsInstance<NativeChip>().toList()
                    assertEquals(3, chips.size)
                    chips.forEach {
                        val rect = Rect(0, 0, it.width, it.height)
                        settings.offsetDescendantRectToMyCoords(it, rect)
                        assertTrue(rect.left >= 0 && rect.right <= pixels)
                        assertTrue(it.width > 0 && it.height > 0)
                    }
                    host.removeView(settings)
                }
            }
        } finally { main { installed.forEach { it.close() }; activity.finish() } }
    }

    @Test fun fullscreenSlotReturnsBorrowedMediaWithoutDestroyingIt() {
        val activity = activity()
        try {
            main {
                val container = FrameLayout(activity)
                activity.setContentView(container)
                val layer = ReverbBrowserPanelFullscreen(activity)
                val media = FrameLayout(activity)
                repeat(2) {
                    layer.attach(container, media)
                    assertSame(layer, media.parent)
                    assertSame(container, layer.parent)
                    layer.detach(container)
                    assertNull(media.parent)
                    assertNull(layer.parent)
                }
            }
        } finally { main { activity.finish() } }
    }

    private fun showFixture(dialog: Dialog) {
        prepareFixtureWindow(dialog)
        dialog.show()
        assertFixtureWindow(dialog)
    }

    private fun prepareFixtureWindow(dialog: Dialog) {
        if (!excludeIme) return
        val window = requireNotNull(dialog.window)
        check(!dialog.isShowing && window.peekDecorView()?.isAttachedToWindow != true) {
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

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).descendants())
    }
}
