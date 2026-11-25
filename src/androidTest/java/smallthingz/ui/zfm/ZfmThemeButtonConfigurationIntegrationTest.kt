package smallthingz.ui.zfm

import android.content.Intent
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity

/** Exercises the public theme factory/binder and actual retained native button, not palette tokens. */
@RunWith(AndroidJUnit4::class)
class ZfmThemeButtonConfigurationIntegrationTest {
    @Test fun retainedButtonUpdatesFontScaleAndDensityWithoutReplacingLabelOrInputState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        var retained: NativeMaterialButtonView? = null
        try {
            lateinit var root: FrameLayout
            lateinit var initial: ZfmTheme
            var clicks = 0
            fun theme(densityDpi: Int, fontScale: Float): ZfmTheme {
                val configuration = Configuration(activity.resources.configuration).apply {
                    this.densityDpi = densityDpi
                    this.fontScale = fontScale
                }
                return ZfmTheme.resolve(activity.createConfigurationContext(configuration), dark = true)
            }
            main {
                initial = theme(DisplayMetrics.DENSITY_DEFAULT, 1f)
                root = FrameLayout(activity)
                retained = initial.button(activity, "Search", enabled = false) { clicks++ }
                root.addView(checkNotNull(retained), FrameLayout.LayoutParams(-2, -2))
                activity.setContentView(root)
            }
            instrumentation.waitForIdleSync()
            main {
                val button = checkNotNull(retained)
                val label = button.label
                val endpoint = button.nativeClickEndpoint
                val role = button.mergedRowAccessibility.roleView
                assertTrue(button.isAttachedToWindow)
                assertSame(root, button.parent)
                assertEquals(1f, initial.density.density, 0f)
                assertEquals(1f, initial.density.fontScale, 0f)

                fun checkBinding(next: ZfmTheme): Metrics {
                    // Default arguments must preserve the retained label and disabled state.
                    next.bindButton(button)
                    val actual = measure(button)
                    val reference = next.button(activity, "Search", enabled = false) {
                        fail("Reference action must never run")
                    }
                    try {
                        assertEquals("Retained geometry must match fresh construction for this configuration",
                            measure(reference), actual)
                    } finally { reference.close() }
                    assertSame(button, root.getChildAt(0))
                    assertSame(label, button.label)
                    assertSame(endpoint, button.nativeClickEndpoint)
                    assertSame(role, button.mergedRowAccessibility.roleView)
                    assertEquals("Search", label.getText())
                    assertFalse(button.isEnabled)
                    assertFalse(button.performClick())
                    assertEquals(0, clicks)
                    return actual
                }

                val baseline = checkBinding(initial)
                val largeFontTheme = theme(DisplayMetrics.DENSITY_DEFAULT, 2f)
                assertEquals(1f, largeFontTheme.density.density, 0f)
                assertEquals(2f, largeFontTheme.density.fontScale, 0f)
                val largeFont = checkBinding(largeFontTheme)
                assertTrue("Font-scale-only rebind must widen the actual label", largeFont.labelWidth > baseline.labelWidth)
                assertTrue("Font-scale-only rebind must increase label height", largeFont.labelHeight > baseline.labelHeight)

                val denserTheme = theme(240, 2f)
                assertEquals(1.5f, denserTheme.density.density, 0f)
                assertEquals(2f, denserTheme.density.fontScale, 0f)
                val denser = checkBinding(denserTheme)
                assertTrue("Density-only rebind must widen the actual label", denser.labelWidth > largeFont.labelWidth)
                assertTrue("Density-only rebind must resize the button", denser.width > largeFont.width)
                assertEquals("Configuration round-trip must restore the original metrics", baseline, checkBinding(initial))

                initial.bindButton(button, enabled = true)
                measure(button)
                assertTrue(button.isEnabled)
                assertTrue(button.performClick())
                assertEquals("The original callback remains installed", 1, clicks)
            }
        } finally {
            main { retained?.close(); activity.finish() }
        }
    }

    private data class Metrics(val width: Int, val height: Int, val labelWidth: Int, val labelHeight: Int)

    private fun measure(button: NativeMaterialButtonView): Metrics {
        // A bounded but roomy width keeps label metrics independent of the test device's window size.
        button.measure(View.MeasureSpec.makeMeasureSpec(4096, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        button.layout(0, 0, button.measuredWidth, button.measuredHeight)
        return Metrics(button.measuredWidth, button.measuredHeight,
            button.label.measuredWidth, button.label.measuredHeight)
    }
}
