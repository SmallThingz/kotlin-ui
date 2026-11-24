package smallthingz.ui.components

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbPermissionOnboardingKind
import smallthingz.ui.reverb.ReverbPermissionOnboardingScreen
import smallthingz.ui.reverb.ReverbPermissionOnboardingState
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.roundToInt

/**
 * Real fresh-screen View measurement, not mocked dimensions or recipe flags.
 * No Activity/window, permission request, input injection or orientation change.
 * Focus assertions concern eligibility only; actual window-focus admission remains separate.
 */
@RunWith(AndroidJUnit4::class)
class ReverbPermissionOnboardingLayoutIntegrationTest {
    @Test fun permissionContentRemainsVisibleAcrossContinueStatesAndQualifiedHeights() = main {
        val context = configuredContext(fontScale = 1f)
        var actionSize: Pair<Int, Int>? = null
        for (height in listOf(1952, 1830)) {
            // Each qualified geometry starts with a fresh, uncompleted onboarding screen.
            val screen = screen(context)
            for (ready in listOf(false, true, false)) {
                screen.bindPermissions(states(), ready)
                measure(screen, 1220, height)
                val page = screen.getChildAt(0) as ScrollView
                val footer = screen.getChildAt(1) as ViewGroup
                val target = footer.getChildAt(0) as NativeButtonTouchTarget
                val body = page.getChildAt(0) as ViewGroup
                val heading = body.getChildAt(1)
                val permissions = (body.getChildAt(2) as NativeGroupLayout).pointerContent

                assertTrue("Permission viewport was consumed by Continue", page.height > footer.height)
                assertEquals(page.bottom, footer.top)
                assertTrue(footer.bottom <= height)
                assertTrue("Continue must remain intrinsic", target.button.width < page.width)
                assertTrue(target.height >= dp(context, target.button.minimumTouchTargetDp))
                assertTrue(target.width >= dp(context, target.button.minimumTouchTargetDp))
                val measuredAction = target.button.width to target.button.height
                if (actionSize == null) actionSize = measuredAction
                else assertEquals("Readiness or extra host height must not inflate Continue", actionSize, measuredAction)
                assertEquals(ready, target.button.isEnabled)
                assertEquals(ready, target.button.isFocusable)
                assertLabelFits(screen, target.button)
                assertWithinPage(screen, page, heading)
                assertEquals(3, permissions.childCount)
                for (index in 0 until permissions.childCount) {
                    val row = permissions.getChildAt(index) as NativePermissionRow
                    val state = states()[index]
                    assertWithinPage(screen, page, row)
                    // A nonempty viewport alone would miss missing or clipped permission copy.
                    for (copy in listOf(state.title, state.detail)) {
                        val text = descendants(row).filterIsInstance<ParagraphView>().single { it.getText() == copy }
                        assertWithinPage(screen, page, text)
                        assertTrue("Permission copy must fit its actual row", bounds(screen, row).contains(bounds(screen, text)))
                    }
                }
            }
        }
    }

    @Test fun enlargedTextKeepsAVisibleScrollablePageAndIntrinsicFooter() = main {
        val context = configuredContext(fontScale = 2f)
        val screen = screen(context)
        screen.bindPermissions(states(), true)
        measure(screen, 1220, 930)
        val page = screen.getChildAt(0) as ScrollView
        val footer = screen.getChildAt(1) as ViewGroup
        val target = footer.getChildAt(0) as NativeButtonTouchTarget
        val body = page.getChildAt(0) as ViewGroup
        val permissions = (body.getChildAt(2) as NativeGroupLayout).pointerContent

        assertTrue("Compact page must retain a visible viewport", page.height >= dp(context, 48))
        assertTrue("Fixture must exercise scrolling", body.height > page.height)
        assertEquals(page.bottom, footer.top)
        assertTrue(target.height >= dp(context, target.button.minimumTouchTargetDp))
        assertTrue("Font growth must not be clipped to a fixed 48dp button", target.button.height > dp(context, 48))
        assertLabelFits(screen, target.button)
        val footerBeforeScroll = bounds(screen, footer)
        val viewportBeforeScroll = Rect(page.left, page.top, page.right, page.bottom)
        assertWithinPage(screen, page, body.getChildAt(1))
        page.scrollTo(0, body.height)
        assertTrue(page.scrollY > 0)
        assertEquals("Scrolling content must not move the viewport", viewportBeforeScroll, bounds(screen, page))
        // A large-font row may exceed this compact viewport. Its final detail must
        // still be reachable in full; requiring the entire row at once is invalid.
        val last = permissions.getChildAt(permissions.childCount - 1) as NativePermissionRow
        val detail = descendants(last).filterIsInstance<ParagraphView>()
            .single { it.getText() == states().last().detail }
        assertWithinPage(screen, page, detail)
        assertEquals("Scrolling permissions must not move the stationary action", footerBeforeScroll, bounds(screen, footer))
        assertTrue(footer.bottom <= screen.height)
        page.scrollTo(0, 0)
        assertEquals(0, page.scrollY)
        assertWithinPage(screen, page, body.getChildAt(1))
    }

    private fun assertWithinPage(screen: ViewGroup, page: ScrollView, child: View) {
        assertTrue("Content must be measured", child.width > 0 && child.height > 0)
        assertEquals(View.VISIBLE, child.visibility)
        val childBounds = bounds(screen, child)
        assertTrue("Permission content must not be clipped: $childBounds", bounds(screen, page).contains(childBounds))
    }

    private fun assertLabelFits(screen: ViewGroup, button: NativeMaterialButtonView) {
        assertEquals("Continue", button.label.getText())
        assertTrue(button.label.width > 0 && button.label.height > 0)
        assertTrue("Continue label must fit its painted action", bounds(screen, button).contains(bounds(screen, button.label)))
    }

    private fun bounds(root: ViewGroup, child: View): Rect = Rect().also {
        // Coordinate conversion subtracts this descendant's own scroll as well
        // as ancestor scroll. Start in its scrolled drawing coordinates so this
        // is the visible View rectangle, while ancestor scrolling still applies.
        child.getDrawingRect(it)
        root.offsetDescendantRectToMyCoords(child, it)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun screen(context: Context) = ReverbPermissionOnboardingScreen(
        context, ReverbDesign.theme(context, dark = false), "Permissions", "Continue", "Unavailable",
        onRequest = { error("Measurement must not request a permission") },
        onContinue = { error("Measurement must not complete onboarding") },
    )

    private fun states() = listOf(
        ReverbPermissionOnboardingKind.NOTIFICATIONS to "Notifications",
        ReverbPermissionOnboardingKind.ACCESSIBILITY to "Accessibility",
        ReverbPermissionOnboardingKind.BATTERY to "Battery optimization exemption",
    ).map { (kind, title) ->
        ReverbPermissionOnboardingState(kind, title, "Permission description", "Manage $title",
            allowed = false, status = "Off", canRequest = true)
    }

    private fun configuredContext(fontScale: Float): Context {
        val context = InstrumentationRegistry.getInstrumentation().context
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            densityDpi = 464
            this.fontScale = fontScale
        })
    }

    private fun measure(screen: View, width: Int, height: Int) {
        screen.measure(exact(width), exact(height))
        screen.layout(0, 0, width, height)
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}
