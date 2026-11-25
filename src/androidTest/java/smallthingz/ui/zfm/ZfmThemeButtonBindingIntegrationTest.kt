package smallthingz.ui.zfm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeButtonRecipe
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity

/** The retained public adapter is exercised through native paint and live click admission. */
@RunWith(AndroidJUnit4::class)
class ZfmThemeButtonBindingIntegrationTest {
    @Test fun legacyPositionalFactoryAndDefaultJvmEntryPointRemainAvailable() = withWindow { activity, root ->
        val theme = ZfmTheme.resolve(activity, dark = false)
        var clicks = 0
        val callback: () -> Unit = { clicks++ }
        // This exact old six-argument call must resolve without named arguments or a trailing lambda.
        val legacy = theme.button(activity, "Legacy", false, true, theme.buttons.text, callback)
        val defaults = theme.button(activity, "Defaults") { clicks++ }
        mount(root, legacy)
        mount(root, defaults)
        assertTrue(legacy.performClick())
        assertTrue(defaults.performClick())
        assertEquals(2, clicks)
        assertEquals(theme.accent, ink(legacy))
        assertEquals(theme.accent, ink(defaults))
        val entry = ZfmTheme::class.java.getDeclaredMethod("button\$default",
            ZfmTheme::class.java, Context::class.java, String::class.java,
            Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
            NativeButtonRecipe::class.java, Function0::class.java,
            Int::class.javaPrimitiveType!!, Any::class.java)
        assertEquals(NativeMaterialButtonView::class.java, entry.returnType)
    }

    @Test fun constructionForegroundFollowsItsThemeRoleAndBindOverrideIsTransient() = withWindow { activity, root ->
        val light = ZfmTheme.resolve(activity, dark = false)
        val dark = ZfmTheme.resolve(activity, dark = true)
        val explicit = Color.rgb(17, 93, 141)
        assertFalse(explicit in setOf(light.accent, light.ink, light.error, dark.accent, dark.ink, dark.error))
        // Verify these inputs denote distinct roles on the device instead of testing an ambiguous color collision.
        assertEquals(3, setOf(light.accent, light.ink, light.error).size)
        val roles: List<(ZfmTheme) -> Int> = listOf({ it.accent }, { it.ink }, { it.error }, { explicit })
        // Keyed tags require an app resource ID; use one unrelated to button binding state.
        val callerTagKey = smallthingz.ui.R.string.tooltip_label
        for (role in roles) {
            val button = light.button(activity, "Action", foreground = role(light)) {}
            mount(root, button)
            val label = button.label
            val endpoint = button.nativeClickEndpoint
            val callerTag = Any()
            val keyedTag = Any()
            button.tag = callerTag
            button.setTag(callerTagKey, keyedTag)
            assertEquals(role(light), ink(button))
            for (next in listOf(dark, light)) {
                rebind(root, button) { next.bindButton(button) }
                assertEquals("Construction role follows the new theme", role(next), ink(button))
                assertEquals(Color.TRANSPARENT, fill(button))
                rebind(root, button) { next.bindButton(button, foreground = Color.MAGENTA) }
                assertEquals(Color.MAGENTA, ink(button))
                rebind(root, button) { next.bindButton(button) }
                assertEquals("Omitting a transient override restores construction semantics", role(next), ink(button))
                assertSame(label, button.label)
                assertSame(endpoint, button.nativeClickEndpoint)
                assertSame(callerTag, button.tag)
                assertSame(keyedTag, button.getTag(callerTagKey))
                assertEquals("Action", button.label.getText())
            }
        }
    }

    @Test fun replacementCallbackSurvivesOmittedRebindAndDisabledPaintRejectsInput() = withWindow { activity, root ->
        val light = ZfmTheme.resolve(activity, dark = false)
        val dark = ZfmTheme.resolve(activity, dark = true)
        for (filled in listOf(false, true)) {
            var oldClicks = 0
            var newClicks = 0
            val button = light.button(activity, "Action", filled = filled) { oldClicks++ }
            mount(root, button)
            assertTrue(button.performClick())
            assertEquals(1, oldClicks)
            rebind(root, button) { light.bindButton(button, onClick = { newClicks++ }) }
            assertTrue(button.performClick())
            assertEquals(1, newClicks)
            for (next in listOf(dark, light)) {
                rebind(root, button) { next.bindButton(button, enabled = false) }
                assertFalse(button.isEnabled)
                assertFalse(button.performClick())
                val recipe = if (filled) next.buttons.filled else next.buttons.text
                // Independent common-control reference: no ZFM binder participates in expected state paint.
                val reference = NativeMaterialButtonView(activity, recipe, next.density, next.fontResolver) {}
                try {
                    reference.bind("Action", false, next.buttonPalette)
                    assertEquals(fill(reference), fill(button))
                    assertEquals(ink(reference), ink(button))
                } finally { reference.close() }
                assertEquals(recipe.contentColor(next.buttonPalette, enabled = false), ink(button))
                rebind(root, button) { next.bindButton(button) }
                assertFalse("Omitted enabled argument retains disabled state", button.isEnabled)
                assertFalse(button.performClick())
                rebind(root, button) { next.bindButton(button, enabled = true) }
                assertTrue(button.performClick())
            }
            assertEquals("Construction callback was replaced", 1, oldClicks)
            assertEquals("Replacement survives both theme and enabled-state changes", 3, newClicks)
        }
    }

    @Test fun changingFilledIdentityRejectsBeforeTypographyStateOrCallbackMutation() = withWindow { activity, root ->
        val original = ZfmTheme.resolve(activity, dark = false)
        val changed = ZfmTheme.resolve(activity.createConfigurationContext(
            Configuration(activity.resources.configuration).apply { fontScale = 2f }), dark = true)
        for (filled in listOf(false, true)) {
            var accepted = 0
            var rejected = 0
            val button = original.button(activity, "Original", filled = filled) { accepted++ }
            mount(root, button)
            val label = button.label
            val endpoint = button.nativeClickEndpoint
            val density = nativeField(button, "density")
            val originalFill = fill(button)
            val originalInk = ink(button)
            val width = button.measuredWidth
            val height = button.measuredHeight
            val callerTag = Any()
            button.tag = callerTag
            assertThrows(IllegalArgumentException::class.java) {
                changed.bindButton(button, label = "Rejected", enabled = false,
                    filled = !filled, foreground = changed.error, onClick = { rejected++ })
            }
            assertSame("Rejected bind must not install new typography", density, nativeField(button, "density"))
            assertSame(label, button.label)
            assertSame(endpoint, button.nativeClickEndpoint)
            assertSame(root, button.parent)
            assertSame(callerTag, button.tag)
            assertEquals("Original", label.getText())
            assertTrue(button.isEnabled)
            assertEquals(originalFill, fill(button))
            assertEquals(originalInk, ink(button))
            measure(button)
            assertEquals(width, button.measuredWidth)
            assertEquals(height, button.measuredHeight)
            assertTrue(button.performClick())
            assertEquals(1, accepted)
            assertEquals(0, rejected)
        }
    }

    private fun nativeField(button: NativeMaterialButtonView, name: String): Any? =
        NativeMaterialButtonView::class.java.getDeclaredField(name).let { it.isAccessible = true; it.get(button) }

    // Read settled native paint rather than sampling antialiased glyph edges or altering renderer tolerances.
    private fun ink(button: NativeMaterialButtonView) = nativeField(button, "displayedInk") as Int
    private fun fill(button: NativeMaterialButtonView) = (nativeField(button, "paint") as Paint).color

    private fun rebind(root: FrameLayout, button: NativeMaterialButtonView, action: () -> Unit) {
        // Settle the retained color transition through normal detach/rebind; no clocks or global animation settings.
        root.removeView(button)
        try { action() } finally { mount(root, button) }
    }

    private fun mount(root: FrameLayout, button: NativeMaterialButtonView) {
        root.addView(button, FrameLayout.LayoutParams(-2, -2))
        measure(button)
        assertTrue(button.isAttachedToWindow && button.isShown)
    }

    private fun measure(button: NativeMaterialButtonView) {
        button.measure(View.MeasureSpec.makeMeasureSpec(4096, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        button.layout(0, 0, button.measuredWidth, button.measuredHeight)
    }

    private fun withWindow(assertions: (Activity, FrameLayout) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var root: FrameLayout
        try {
            instrumentation.runOnMainSync {
                root = FrameLayout(activity)
                activity.setContentView(root)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertions(activity, root) }
        } finally {
            instrumentation.runOnMainSync {
                for (index in 0 until root.childCount) (root.getChildAt(index) as? NativeMaterialButtonView)?.close()
                activity.finish()
            }
        }
    }
}
