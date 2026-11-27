package smallthingz.ui.zfm

import android.app.Activity
import android.app.Application
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Native flag ownership/focus preservation only; real first-key qualification is separate. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ZfmChooserDefaultFocusTest {
    @Test fun existingFocusSurvivesDisableReenableAndClose() = withFixture { f ->
        assertTrue(f.primary.isFocusedByDefault)
        assertSame(f.management, f.boundary.findFocus())
        f.primary.isEnabled = false
        assertTrue(f.viewport.publish(f.items))
        assertFalse(f.primary.isFocusedByDefault)
        assertFalse(f.primary.isEnabled)
        assertSame(f.management, f.boundary.findFocus())

        f.primary.isEnabled = true
        assertTrue(f.viewport.publish(f.items.toList()))
        assertTrue(f.primary.isFocusedByDefault)
        f.primary.isFocusable = false
        assertTrue(f.viewport.publish(f.items))
        assertFalse(f.primary.isFocusedByDefault)
        assertFalse(f.primary.isFocusable)
        f.primary.isFocusable = true
        assertTrue(f.viewport.publish(f.items))
        assertTrue(f.primary.isFocusedByDefault)
        f.viewport.close()
        assertFalse(f.primary.isFocusedByDefault)
        assertSame(f.management, f.boundary.findFocus())
    }

    @Test fun removalRestoresOwnedHintAndReadditionUsesNativeDefaultChain() = withFixture { f ->
        assertTrue(f.viewport.publish(emptyList()))
        assertFalse(f.primary.isFocusedByDefault)
        assertNull(f.primary.parent)
        assertSame(f.management, f.boundary.findFocus())
        assertTrue(f.viewport.publish(f.items))
        assertTrue(f.primary.isFocusedByDefault)
        f.layout()
        // Explicitly exercise native default-chain propagation after reparenting. This is not
        // input dispatch or evidence that the first physical Tab has been qualified.
        assertTrue(f.boundary.restoreDefaultFocus())
        assertSame(f.primary, f.boundary.findFocus())
        f.viewport.close()
        assertFalse(f.primary.isFocusedByDefault)
        assertSame(f.primary, f.boundary.findFocus())
    }

    @Test fun callerOwnedTrueFlagSurvivesRemovalAndClose() = withFixture(priorDefault = true) { f ->
        assertTrue(f.viewport.publish(emptyList()))
        assertTrue(f.primary.isFocusedByDefault)
        assertTrue(f.viewport.publish(f.items))
        f.viewport.close()
        assertTrue(f.primary.isFocusedByDefault)
        assertSame(f.management, f.boundary.findFocus())
    }

    @Test fun nullPreferenceLeavesBothOriginalFlagValuesAndFocusAlone() {
        for (prior in listOf(false, true)) withFixture(preferredKey = null, priorDefault = prior) { f ->
            assertEquals(prior, f.primary.isFocusedByDefault)
            assertSame(f.management, f.boundary.findFocus())
            assertTrue(f.viewport.publish(emptyList()))
            assertEquals(prior, f.primary.isFocusedByDefault)
            f.viewport.close()
            assertEquals(prior, f.primary.isFocusedByDefault)
            assertSame(f.management, f.boundary.findFocus())
        }
    }

    private fun withFixture(
        preferredKey: String? = "internal",
        priorDefault: Boolean = false,
        test: (Fixture) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
            .windowFocusChanged(true)
        var fixture: Fixture? = null
        try {
            val next = Fixture(controller.get(), preferredKey, priorDefault)
            fixture = next
            test(next)
        } finally {
            fixture?.viewport?.close()
            controller.pause().stop().destroy()
        }
    }

    private class Fixture(activity: Activity, preferredKey: String?, priorDefault: Boolean) {
        val management = View(activity).apply {
            minimumHeight = 40
            isFocusableInTouchMode = true
        }
        val primary = View(activity).apply {
            minimumHeight = 40
            isFocusableInTouchMode = true
            isFocusedByDefault = priorDefault
        }
        val items = listOf(ZfmChooserRoot("internal", primary))
        val viewport = ZfmChooserViewport(activity, management, 1L, { it == 1L },
            preferredDefaultRootKey = preferredKey)
        private val panel = FrameLayout(activity).apply { addView(viewport) }
        val boundary = viewport.wrapPanel(panel)
        private val host = FrameLayout(activity)

        init {
            activity.setContentView(host)
            host.addView(boundary, FrameLayout.LayoutParams(320, 240))
            layout()
            assertTrue(boundary.isAttachedToWindow)
            assertTrue(management.requestFocus())
            assertTrue(viewport.publish(items))
        }

        fun layout() {
            host.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, host.measuredWidth, host.measuredHeight)
        }
    }
}
