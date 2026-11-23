package smallthingz.ui.components

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.common.SegmentOption
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.settings.ReverbLabeledSegmentedSelector

@RunWith(AndroidJUnit4::class)
class ReverbLabeledSegmentedSelectorIntegrationTest {
    private enum class Mode { TIME, SIZE, AUTOMATIC }

    @Test fun retainedChoicesBindSilentlyAndReportOnlyCurrentDisabledReasons() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val requests = mutableListOf<Mode>()
        val options = listOf(
            SegmentOption(Mode.TIME, "Time"),
            SegmentOption(Mode.SIZE, "Size"),
            SegmentOption(Mode.AUTOMATIC, "Automatic", enabled = false, disabledReason = "Requires setup"),
        )
        lateinit var environment: ReverbEnvironment
        var installed: ReverbLabeledSegmentedSelector<Mode>? = null
        try {
            instrumentation.runOnMainSync {
                environment = ReverbEnvironment(activity, false).forSettings()
                val selector = ReverbLabeledSegmentedSelector(activity, environment, "Retention", options, Mode.TIME,
                    enabled = false, inputAllowed = { true }) { requests.add(it) }
                installed = selector
                activity.setContentView(selector, ViewGroup.LayoutParams(-1, -2))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val selector = requireNotNull(installed)
                assertTrue(selector.hasWindowFocus())
                val time = selector.chip("Time")
                val size = selector.chip("Size")
                val automatic = selector.chip("Automatic")
                assertTrue(time.isChecked)
                assertFalse(time.isEnabled)
                assertFalse(size.isEnabled)
                assertFalse(size.performClick())

                selector.bind("Retention", options, Mode.SIZE, true, environment)
                assertSame(size, selector.chip("Size"))
                assertSame(automatic, selector.chip("Automatic"))
                assertFalse(time.isChecked)
                assertTrue(size.isChecked)
                assertTrue(time.isEnabled)
                assertTrue(size.isEnabled)
                assertFalse(automatic.isEnabled)
                assertFalse(automatic.performClick())
                assertEquals("Automatic. Requires setup",
                    automatic.createAccessibilityNodeInfo().contentDescription?.toString())
                assertNull(time.createAccessibilityNodeInfo().contentDescription)
                assertTrue(requests.isEmpty())

                val available = options.map { if (it.value == Mode.AUTOMATIC) it.copy(enabled = true) else it }
                selector.bind("Retention", available, Mode.SIZE, true, environment)
                assertTrue(automatic.isEnabled)
                assertEquals("Automatic", automatic.createAccessibilityNodeInfo().text?.toString())
                assertNull(automatic.createAccessibilityNodeInfo().contentDescription)
                assertTrue(requests.isEmpty())

                assertTrue(automatic.performClick())
                assertEquals(listOf(Mode.AUTOMATIC), requests)
                assertTrue(size.isChecked)
                selector.close()
                assertFalse(automatic.performClick())
                assertEquals(listOf(Mode.AUTOMATIC), requests)
            }
        } finally {
            instrumentation.runOnMainSync {
                installed?.close()
                activity.finish()
            }
        }
    }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        val group = this@descendants as? ViewGroup
        if (group != null) {
            for (index in 0 until group.childCount) yieldAll(group.getChildAt(index).descendants())
        }
    }

    private fun ViewGroup.chip(label: String): NativeChip =
        descendants().filterIsInstance<NativeChip>().single { it.text.toString() == label }
}
