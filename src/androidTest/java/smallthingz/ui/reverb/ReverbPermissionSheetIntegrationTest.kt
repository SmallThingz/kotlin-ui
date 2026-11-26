package smallthingz.ui.reverb

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeActionSheet
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.NativePermissionDialog
import smallthingz.ui.components.SheetScrim
import smallthingz.ui.scroll.NativeVerticalViewport
import smallthingz.ui.text.view.ParagraphView
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Actual local window dispatch; no global input or permission changes. Root runs after integration. */
@RunWith(AndroidJUnit4::class)
class ReverbPermissionSheetIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun bothFactoriesUseTheActualRecoverySheetRecipeInBothThemes() {
        for (site in listOf(false, true)) for (dark in listOf(false, true)) withFixture(site, dark) { f ->
            main {
                val reference = ReverbPermissionDeniedSheet(f.activity.window.decorView, f.theme,
                    "Reference", "Permission needed", "Close", "Review", {}, {})
                try {
                    assertEquals(reference.dialog.style.palette, f.dialog.style.palette)
                    assertEquals(reference.dialog.style.recipe, f.dialog.style.recipe)
                    assertEquals(reference.dialog.style.titleStyle, f.dialog.style.titleStyle)
                    val actions = descendants(f.dialog.contentView).filterIsInstance<ReverbSheetActions>().single()
                    assertEquals(2, actions.buttons.size)
                    assertTrue(f.dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    assertTrue(f.dialog.contentView.height > 0)
                    assertTrue(f.dialog.contentView.width <= (640f * f.theme.density.density + 1).toInt())
                    val text = descendants(f.dialog.contentView).filterIsInstance<ParagraphView>().map { it.getText().toString() }
                    assertTrue("Request identity must be retained", "Fixture identity" in text)
                    assertTrue("Request heading must be retained", "Fixture request" in text)
                    if (!site) {
                        assertTrue("Fixture message" in text)
                        assertTrue("Fixture reply hint" in text)
                    }
                } finally { reference.close() }
            }
        }
    }

    @Test fun compactRequestKeepsBothDecisionsVisibleWhileLongCopyScrolls() {
        val compactHeightDp = 300
        val message = List(40) { "The requester needs access to complete this operation." }.joinToString("\n")
        for (accept in listOf(true, false)) {
            withFixture(site = false, requestMessage = message, windowHeightDp = compactHeightDp) { f ->
                val viewport = main {
                    descendants(f.dialog.contentView).filterIsInstance<NativeVerticalViewport>().single()
                }
                val (actionBounds, initialBodyTop) = main {
                    val compactHeightPx = (compactHeightDp * f.theme.density.density).roundToInt()
                    assertTrue(f.dialog.window!!.decorView.height in 1..compactHeightPx)
                    assertTrue(viewport.height > 0)
                    assertTrue(viewport.canScrollVertically(1))
                    assertEquals(0, viewport.saveScrollPosition())
                    val bounds = listOf(f.allow, f.deny).map { fullActionBounds(f.dialog, it) }
                    val bodyTop = viewport.getChildAt(0).top
                    assertTrue(viewport.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
                    bounds to bodyTop
                }
                await {
                    viewport.saveScrollPosition() > 0 && !viewport.state.isScrollInProgress &&
                        viewport.getChildAt(0).top < initialBodyTop
                }
                main {
                    assertEquals(actionBounds, listOf(f.allow, f.deny).map { fullActionBounds(f.dialog, it) })
                    assertTrue(f.responses.isEmpty())
                    val action = if (accept) f.allow else f.deny
                    val location = IntArray(2).also(action::getLocationInWindow)
                    val x = location[0] + action.width / 2f
                    val y = location[1] + action.height / 2f
                    val downTime = SystemClock.uptimeMillis()
                    pointer(f.dialog, MotionEvent.ACTION_DOWN, x, y, 0, downTime)
                    pointer(f.dialog, MotionEvent.ACTION_UP, x, y, 0, downTime)
                    assertEquals(listOf(if (accept) "allow" else "deny"), f.responses)
                    assertTrue(f.dialog.isShowing)
                }
            }
        }
    }

    @Test fun acceptedActionLocksBothActionsUntilConsumerCompletes() = withFixture { f ->
        main {
            f.allow.performClick()
            f.allow.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
            f.deny.performClick()
            escape(f.dialog)
            assertEquals(listOf("allow"), f.responses)
            assertTrue(f.dialog.isShowing)
            assertFalse(f.allow.isEnabled)
            assertFalse(f.deny.isEnabled)
            f.prompt.setBusy(false)
            f.deny.performClick()
            assertEquals(listOf("allow", "deny"), f.responses)
            assertTrue("Response does not dismiss until consumer decides", f.dialog.isShowing)
        }
    }

    @Test fun allowAvailabilitySurvivesBusyAndPreShowUpdates() = withFixture(prepare = {
        it.setAllowEnabled(false)
        it.setBusy(true)
        it.setProgress("Fixture pending")
        it.setError("Fixture retry")
    }) { f ->
        main {
            assertFalse(f.allow.isEnabled)
            assertFalse(f.deny.isEnabled)
            f.prompt.setBusy(false)
            f.allow.performClick()
            assertTrue(f.responses.isEmpty())
            assertFalse(f.allow.isEnabled)
            assertTrue(f.deny.isEnabled)
            val text = descendants(f.dialog.contentView).filterIsInstance<ParagraphView>()
            assertTrue(text.any { it.getText().toString() == "Fixture pending" && it.visibility == View.VISIBLE })
            assertTrue(text.any { it.getText().toString() == "Fixture retry" && it.visibility == View.VISIBLE })
            f.prompt.setProgress(null)
            f.prompt.setError(null)
            f.prompt.setAllowEnabled(true)
            f.allow.performClick()
            assertEquals(listOf("allow"), f.responses)
        }
    }

    @Test fun backCancellationIsBlockedWhileBusyThenDeliveredOnce() = withFixture { f ->
        main {
            f.prompt.setBusy(true)
            f.dialog.onBackPressedDispatcher.onBackPressed()
            assertTrue(f.dialog.isShowing)
            assertTrue(f.responses.isEmpty())
            f.prompt.setBusy(false)
            f.dialog.onBackPressedDispatcher.onBackPressed()
        }
        await { f.responses == listOf("cancel") }
        main {
            assertFalse(f.dialog.isShowing)
            f.prompt.dismiss()
            f.deny.performClick()
            assertEquals(listOf("cancel"), f.responses)
        }
    }

    @Test fun busyEscapeDownCannotCancelAfterBusyClears() = withFixture { f ->
        main {
            f.prompt.setBusy(true)
            f.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
            f.prompt.setBusy(false)
            f.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
            assertTrue(f.responses.isEmpty())
            assertTrue(f.dialog.isShowing)
            escape(f.dialog)
            assertEquals(listOf("cancel"), f.responses)
            f.prompt.dismiss()
            f.allow.performClick()
            assertEquals(listOf("cancel"), f.responses)
        }
    }

    @Test fun outsideTapAndBackdropAccessibilityStayNonResponses() = withFixture { f ->
        main {
            val scrim = descendants(f.dialog.window!!.decorView).filterIsInstance<SheetScrim>().single()
            assertFalse(f.dialog.contentContext.accessibility.sourceNode(scrim).present)
            scrim.performClick()
            scrim.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
            assertTrue(f.responses.isEmpty())
            assertTrue(f.dialog.isShowing)
            assertTrue(f.dialog.canAcceptSensitiveAction)
            f.deny.performClick()
            assertEquals(listOf("deny"), f.responses)
        }
    }

    @Test fun focusLossRevokesHeldEscapeAndAuthorization() = withFixture { f ->
        val cover = main { Dialog(f.activity).apply { setContentView(View(f.activity), ViewGroup.LayoutParams(100, 100)) } }
        try {
            main {
                f.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                cover.show()
            }
            await { !f.dialog.hasCurrentWindowFocus && cover.window!!.decorView.hasWindowFocus() }
            main {
                f.allow.performClick()
                f.deny.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                assertTrue(f.responses.isEmpty())
                cover.dismiss()
            }
            await { f.dialog.hasCurrentWindowFocus }
            main {
                f.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
                assertTrue(f.responses.isEmpty())
                f.allow.performClick()
                assertEquals(listOf("allow"), f.responses)
            }
        } finally { main { cover.dismiss() } }
    }

    @Test fun obscuredPointerCannotAuthorizeAndFreshPointerCan() {
        val flags = listOf(MotionEvent.FLAG_WINDOW_IS_OBSCURED) +
            if (Build.VERSION.SDK_INT >= 29) listOf(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) else emptyList()
        for (flag in flags) withFixture { f ->
            main {
                val point = IntArray(2).also(f.allow::getLocationOnScreen)
                val root = IntArray(2).also(f.dialog.window!!.decorView::getLocationOnScreen)
                val x = point[0] - root[0] + f.allow.width / 2f
                val y = point[1] - root[1] + f.allow.height / 2f
                val firstDown = SystemClock.uptimeMillis()
                pointer(f.dialog, MotionEvent.ACTION_DOWN, x, y, 0, firstDown)
                pointer(f.dialog, MotionEvent.ACTION_MOVE, x, y, flag, firstDown)
                pointer(f.dialog, MotionEvent.ACTION_UP, x, y, 0, firstDown)
                assertTrue(f.responses.isEmpty())
                val secondDown = SystemClock.uptimeMillis()
                pointer(f.dialog, MotionEvent.ACTION_DOWN, x, y, 0, secondDown)
                pointer(f.dialog, MotionEvent.ACTION_UP, x, y, 0, secondDown)
                assertEquals(listOf("allow"), f.responses)
            }
        }
    }

    @Test fun programmaticRetirementSuppressesPendingAnimatedCancellation() = withFixture { f ->
        var responsesAtRetirement: List<String> = emptyList()
        main {
            f.dialog.requestAnimatedDismiss()
            // Zero animation scale may have completed cancellation synchronously already.
            responsesAtRetirement = f.responses.toList()
            f.prompt.dismiss()
            f.prompt.dismiss()
            f.allow.performClick()
            assertEquals(responsesAtRetirement, f.responses)
            assertFalse(f.dialog.isShowing)
            try { f.prompt.show(); fail("A retired permission presentation must not reopen") }
            catch (_: IllegalStateException) { }
        }
        instrumentation.waitForIdleSync()
        main { assertEquals(responsesAtRetirement, f.responses) }
    }

    @Test fun programmaticDismissIsSilentAndSinglePresentation() = withFixture { f ->
        main {
            f.prompt.dismiss()
            f.prompt.dismiss()
            f.allow.performClick()
            escape(f.dialog)
            assertTrue(f.responses.isEmpty())
            assertFalse(f.dialog.isShowing)
        }
    }

    private data class Fixture(val activity: Activity, val theme: ReverbTheme, val prompt: NativePermissionDialog,
        val dialog: NativeActionSheet, val responses: MutableList<String>,
        val allow: NativeMaterialButtonView, val deny: NativeMaterialButtonView)

    private fun withFixture(site: Boolean = true, dark: Boolean = false,
        requestMessage: String = "Fixture message", windowHeightDp: Int? = null,
        prepare: (NativePermissionDialog) -> Unit = {}, test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var prompt: NativePermissionDialog? = null
        try {
            val f = main {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                val themeContext = if (windowHeightDp == null) activity else activity.createConfigurationContext(
                    Configuration(activity.resources.configuration).apply { fontScale = 1.5f })
                val theme = ReverbDesign.theme(themeContext, dark)
                val responses = mutableListOf<String>()
                val owner: NativePermissionDialog = if (site) createReverbSitePermissionDialog(activity, theme, null,
                    "Fixture identity", "Fixture request", "Deny fixture", "Allow fixture",
                    { responses += "deny" }, { responses += "allow" }, { responses += "cancel" })
                else createReverbRequestPermissionDialog(activity, theme, null, "Fixture identity", "Fixture request",
                    requestMessage, "Fixture reply hint", "Deny fixture", "Allow fixture",
                    { responses += "deny" }, { responses += "allow" }, { responses += "cancel" })
                prompt = owner
                prepare(owner)
                owner.show()
                val presentation = field(owner, "presentation")
                val content = field(presentation, "content")
                val dialog = field(content, "sheet") as NativeActionSheet
                if (windowHeightDp != null) dialog.window!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    (windowHeightDp * theme.density.density).roundToInt())
                val buttons = descendants(dialog.contentView).filterIsInstance<NativeMaterialButtonView>()
                Fixture(activity, theme, owner, dialog, responses,
                    buttons.single { it.label.getText().toString() == "Allow fixture" },
                    buttons.single { it.label.getText().toString() == "Deny fixture" })
            }
            await { f.dialog.isShowing && f.dialog.canAcceptSensitiveAction }
            test(f)
        } finally { main { try { prompt?.dismiss() } finally { activity.finish() } } }
    }

    private fun field(owner: Any, name: String): Any =
        requireNotNull(owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner))
    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) addAll(descendants(root.getChildAt(i)))
    }
    private fun fullActionBounds(dialog: NativeActionSheet, action: NativeMaterialButtonView): Rect {
        val location = IntArray(2).also(action::getLocationOnScreen)
        val bounds = Rect(location[0], location[1], location[0] + action.width, location[1] + action.height)
        val node = dialog.contentContext.accessibility.sourceNode(action)
        assertFalse(bounds.isEmpty)
        assertTrue("Decision action must be visible", node.visibleToUser)
        assertEquals("Decision action must be fully visible", bounds, node.boundsInScreen)
        return bounds
    }
    private fun escape(dialog: NativeActionSheet) {
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
    }
    private fun pointer(dialog: NativeActionSheet, action: Int, x: Float, y: Float, flags: Int, downTime: Long) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, now, action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
            arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
        try { dialog.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (main(condition)) return
            SystemClock.sleep(16)
        }
        assertTrue("Owned permission window did not reach the required state", main(condition))
    }
    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try { result.set(block()) } catch (problem: Throwable) { failure.set(problem) } }
        failure.get()?.let { throw it }
        return result.get()
    }
}
