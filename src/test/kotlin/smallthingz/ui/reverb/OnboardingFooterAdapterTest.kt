package smallthingz.ui.reverb

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.reverb.onboarding.OnboardingFooterPresentation
import smallthingz.ui.reverb.onboarding.OnboardingFooterView
import kotlin.math.roundToInt

/** Shared real geometry and input, without permission, preference or pager owners. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class OnboardingFooterAdapterTest {
    @Test fun twoPagePhaseLabelsAndBlockedActionsRetainTheSameButtons() = withFooter(1f) { footer, palette ->
        val back = footer.getChildAt(0) as NativeMaterialButtonView
        val next = footer.getChildAt(1) as NativeMaterialButtonView
        var backCalls = 0
        var nextCalls = 0
        val lease = footer.connect({ backCalls++ }, { nextCalls++ })
        footer.bind(OnboardingFooterPresentation(0, "Back", "Next", false, true), palette)
        measure(footer, 272)
        assertEquals("Back", back.label.getText())
        assertEquals("Next", next.label.getText())
        assertFalse(back.isEnabled)
        assertFalse(back.performClick())
        assertTrue(next.performClick())
        assertEquals(1, nextCalls)
        footer.bind(OnboardingFooterPresentation(1, "Back", "Finish", true, true), palette)
        measure(footer, 272)
        assertSame(back, footer.getChildAt(0))
        assertSame(next, footer.getChildAt(1))
        assertEquals("Finish", next.label.getText())
        assertTrue(back.performClick())
        assertEquals(1, backCalls)
        footer.bind(OnboardingFooterPresentation(1, "Back", "Finish", false, false), palette)
        assertFalse(back.performClick())
        assertFalse(next.performClick())
        assertEquals(1, backCalls)
        assertEquals(1, nextCalls)
        assertEquals("Indicator is drawn by the shared footer, not an extra semantic child", 2, footer.childCount)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, footer.importantForAccessibility)
        lease.close()
        footer.close()
        assertFalse(next.performClick())
    }

    @Test fun fontTwoAndRtlUseSharedGapsAndKeepBothTargetsInsideTheFooter() {
        for (scale in listOf(1f, 2f)) withFooter(scale) { footer, palette ->
            val recipe = NativeUiAppearance.Default.onboardingFooter
            val back = footer.getChildAt(0) as NativeMaterialButtonView
            val next = footer.getChildAt(1) as NativeMaterialButtonView
            footer.bind(OnboardingFooterPresentation(1, "Back", "Finish", true, true), palette)
            for (width in listOf(272, 180)) for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                footer.layoutDirection = direction
                measure(footer, width)
                assertEquals("Host must resolve the requested direction before geometry assertions",
                    direction, footer.layoutDirection)
                val density = footer.resources.displayMetrics.density
                val gap = (recipe.actionGapDp * density).roundToInt()
                assertEquals(recipe.actionsTopPx(density), back.top)
                for (button in listOf(back, next)) {
                    assertTrue(button.left >= 0 && button.right <= footer.width)
                    assertTrue(button.top >= 0 && button.bottom <= footer.height)
                    assertTrue(button.height >= (button.minimumTouchTargetDp * density).roundToInt())
                    assertTrue("Assigned height includes the enlarged label", button.height >= button.intrinsicHeight(button.width))
                }
                if (back.top == next.top) {
                    if (direction == View.LAYOUT_DIRECTION_RTL) assertEquals(gap, back.left - next.right)
                    else assertEquals(gap, next.left - back.right)
                } else {
                    assertEquals(gap, next.top - back.bottom)
                    assertEquals(width, back.width)
                    assertEquals(width, next.width)
                }
                assertTrue("Footer reserves space for the scrollable page", footer.height +
                    (recipe.minimumPageSpaceDp * density).roundToInt() <= 640)
            }
        }
    }

    @Test fun legacyReverbAdapterKeepsThreePageLabelsAndReadinessIsNotAnAdmissionGate() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        val activity = controller.get()
        val environment = ReverbEnvironment(activity, false)
        val footer = OnboardingFooterView(activity, environment) { null }
        try {
            val back = footer.getChildAt(0) as NativeMaterialButtonView
            val next = footer.getChildAt(1) as NativeMaterialButtonView
            footer.bind(0, 0, false, false, environment.palette)
            assertFalse(back.isEnabled)
            assertTrue(next.isEnabled)
            assertEquals(activity.getString(smallthingz.ui.R.string.onboarding_continue), next.label.getText())
            footer.bind(2, 2, true, false, environment.palette)
            assertTrue(back.isEnabled)
            assertEquals(activity.getString(smallthingz.ui.R.string.onboarding_get_started), next.label.getText())
            footer.bind(2, 2, true, true, environment.palette)
            assertFalse(back.isEnabled)
            assertFalse(next.isEnabled)
        } finally { footer.close(); controller.pause().stop().destroy() }
    }

    private fun withFooter(fontScale: Float, action: (OnboardingFooterView, ReverbPalette) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        val activity = controller.get()
        val applicationInfo = activity.applicationInfo
        val previousFlags = applicationInfo.flags
        // Match the existing horizontal-panel fixture: the library host manifest does not opt in.
        applicationInfo.flags = previousFlags or ApplicationInfo.FLAG_SUPPORTS_RTL
        var ownedFooter: OnboardingFooterView? = null
        try {
            val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                this.fontScale = fontScale
            })
            val theme = ControlTheme.resolve(context, false)
            val palette = NativeUiAppearance.Default.resolveColorScheme(context, false)
            val footer = OnboardingFooterView(context, theme, pageCount = 2).also { ownedFooter = it }
            val root = FrameLayout(activity)
            root.addView(footer)
            activity.setContentView(root)
            controller.windowFocusChanged(true)
            action(footer, palette)
        } finally {
            try { ownedFooter?.close() } finally {
                try { controller.pause().stop().destroy() } finally {
                    applicationInfo.flags = previousFlags
                }
            }
        }
    }

    private fun measure(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
}
