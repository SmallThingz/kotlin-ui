package smallthingz.ui.gallery

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.NativeText
import smallthingz.ui.components.NativeTextStyle
import smallthingz.ui.components.withRetiringTestActivity
import smallthingz.ui.controls.ControlPalette
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class GalleryHealStatusRowIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun onMain(block: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }
    private val palette = ControlPalette(-1, -1, 0xff202020.toInt(), 0xff606060.toInt(),
        0xff3458a0.toInt(), -1, 0xff3458a0.toInt(), 0xffaaaaaa.toInt())
    private fun fixture(context: Context): Fixture {
        fun label() = NativeText(context, palette, NativeTextStyle(sizeSp = 14f))
        val status = label().apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val alternate = label()
        val probe = label()
        return Fixture(GalleryHealStatusRow(context, status, alternate, probe), status, alternate, probe)
    }
    private data class Fixture(val row: GalleryHealStatusRow, val status: NativeText,
        val alternate: NativeText, val probe: NativeText) {
        fun layout(width: Int) {
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
        }
    }
    private fun changes(view: NativeText): MutableList<String> {
        val values = mutableListOf<String>()
        view.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val value = s.toString()
                if (values.lastOrNull() != value) values += value
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        return values
    }

    @Test fun detachedProbePreservesGuidanceBudgetAndNeverPublishesCandidates() = onMain {
        val base = instrumentation.targetContext
        for (scale in listOf(1f, 2f)) for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { fontScale = scale })
            for (guidance in listOf("Cover a small spot completely, including its edges, then preview.",
                "Source selected. Preview will check it before applying.", "Quelle wählen.", "اختر المصدر.")) {
                val f = fixture(context); f.row.layoutDirection = direction
                val updates = changes(f.status)
                for (widthDp in listOf(96, 168, 360, 800)) {
                    val width = (widthDp * context.resources.displayMetrics.density).roundToInt()
                    f.row.bind(guidance); f.layout(width)
                    val baseline = f.row.height
                    updates.clear()
                    val short = "Preview cancelled. Selection kept."
                    val full = "$short $guidance"
                    val completion = GalleryHealStatusRow.Completion(full, short)
                    f.row.bind(guidance, completion = completion)
                    assertTrue("No exposed candidate before measurement", updates.isEmpty())
                    f.layout(width)
                    val selected = f.status.text.toString()
                    assertTrue(selected in listOf(full, short, guidance))
                    assertEquals(if (selected == guidance) emptyList<String>() else listOf(selected), updates)
                    assertEquals(full, f.status.contentDescription)
                    assertEquals(baseline, f.row.height)
                    assertEquals(2, f.row.childCount)
                    assertSame(f.status, f.row.getChildAt(0)); assertSame(f.alternate, f.row.getChildAt(1))
                    assertNull(f.probe.parent); assertFalse(f.probe.isAttachedToWindow)
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, f.probe.importantForAccessibility)
                    assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, f.probe.accessibilityLiveRegion)
                    assertEquals(View.INVISIBLE, f.alternate.visibility)
                    assertEquals(guidance, f.alternate.text.toString())
                    updates.clear()
                    f.row.bind(guidance, completion = completion); f.layout(width); f.layout(width)
                    assertTrue("Repeated layout/bind must not republish labels", updates.isEmpty())
                    f.row.bind(guidance); f.layout(width)
                    assertEquals(guidance, f.status.text.toString()); assertNull(f.status.contentDescription)
                    assertEquals(baseline, f.row.height)
                }
            }
        }
    }

    @Test fun ordinaryReadyAlternateStillReservesHeightAndErrorsClearCompletion() = onMain {
        val f = fixture(instrumentation.targetContext)
        f.row.bind("Preparing preview…", "Check the preview before applying. Nearby texture may not match the photo.")
        f.layout(320)
        val height = f.row.height
        f.row.bind("Check the preview before applying. Nearby texture may not match the photo.", "Preparing preview…")
        f.layout(320); assertEquals(height, f.row.height)
        f.row.bind("Guidance", completion = GalleryHealStatusRow.Completion("Preview discarded. Guidance", "Preview discarded."))
        f.layout(320)
        f.row.bind("The preview could not be closed. Open Details before continuing.")
        f.layout(320)
        assertNull(f.status.contentDescription)
        assertEquals("The preview could not be closed. Open Details before continuing.", f.status.text.toString())
        assertEquals(View.GONE, f.alternate.visibility)
    }

    @Test fun attachedStatusEventsExposeOnlyFinalCopyAndRetireTheOwnedWindow() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var root: FrameLayout? = null
        lateinit var f: Fixture
        lateinit var updates: MutableList<String>
        val laidOut = CountDownLatch(1)
        withRetiringTestActivity(activity, root = { root }) {
            onMain {
                f = fixture(activity)
                root = FrameLayout(activity).apply { addView(f.row, FrameLayout.LayoutParams(
                    (168 * activity.resources.displayMetrics.density).roundToInt(), -2)) }
                f.row.bind("Cover a small spot completely, including its edges, then preview.")
                f.row.doOnLayout { laidOut.countDown() }
                activity.setContentView(root)
            }
            assertTrue(laidOut.await(5, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
            val node = arrayOfNulls<android.view.accessibility.AccessibilityNodeInfo>(1)
            onMain { node[0] = f.status.createAccessibilityNodeInfo(); updates = changes(f.status) }
            try {
                val short = "Preview cancelled. Selection kept."
                val full = "$short Cover a small spot completely, including its edges, then preview."
                val event = instrumentation.uiAutomation.executeAndWaitForEvent({
                    onMain {
                        f.row.bind("Cover a small spot completely, including its edges, then preview.",
                            completion = GalleryHealStatusRow.Completion(full, short))
                    }
                }, { candidate ->
                    if (candidate.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                        candidate.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) false
                    else {
                        val source = candidate.source
                        try { source == node[0] && source?.contentDescription?.toString() == full }
                        finally { source?.recycle() }
                    }
                }, 5_000)
                try {
                    instrumentation.waitForIdleSync()
                    onMain {
                        val final = f.status.text.toString()
                        assertTrue(updates.size <= 1)
                        assertTrue(updates.isEmpty() || updates.single() == final)
                        assertTrue("No measured candidate leaked into the status event",
                            event.text.all { it.toString() == final })
                        assertEquals(full, f.status.contentDescription)
                        assertNull(f.probe.parent)
                        val finalNode = f.status.createAccessibilityNodeInfo()
                        try { assertEquals(final, finalNode.text.toString()); assertEquals(full, finalNode.contentDescription) }
                        finally { finalNode.recycle() }
                        // Platform notification counts may coalesce; assert no further source mutation.
                        updates.clear(); f.row.bind("Cover a small spot completely, including its edges, then preview.",
                            completion = GalleryHealStatusRow.Completion(full, short)); f.row.requestLayout()
                    }
                    instrumentation.waitForIdleSync()
                    onMain { assertTrue(updates.isEmpty()) }
                } finally { event.recycle() }
            } finally { node[0]?.recycle() }
        }
    }
}
