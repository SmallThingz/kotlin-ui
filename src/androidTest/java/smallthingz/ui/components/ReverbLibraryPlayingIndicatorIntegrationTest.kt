package smallthingz.ui.components

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.doOnLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.R
import smallthingz.ui.geometry.Rect
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.library.LibraryRecordingRow
import smallthingz.ui.reverb.library.LibraryRecordingRowActions
import smallthingz.ui.reverb.library.ReverbLibraryRecordingRow
import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ReverbLibraryPlayingIndicatorIntegrationTest {
    @Test fun playingSurvivesSelectionAndThemeWithoutGrowingAndClearsOnReuse() = onMain {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        for (fontScale in listOf(1f, 2f)) {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                densityDpi = 160
                this.fontScale = fontScale
            })
            val runtime = NativeModalWindowRuntime(context)
            val environment = ReverbEnvironment(context, false)
            val row = ReverbLibraryRecordingRow(context, environment, runtime.scope, View(context))
            var activations = 0
            val actions = LibraryRecordingRowActions({ activations++ }, null, null)
            val model = LibraryRecordingRow("one", "Recording", "12 seconds", null,
                false, false, true, null)
            try {
                for (dark in listOf(false, true, false)) {
                    // Flat fixture isolates glyph pixels from asynchronous grain readiness.
                    // The attached accessibility case retains the default grained palette.
                    val theme = ReverbEnvironment(context, dark)
                    row.setEnvironment(theme.withPalette(theme.palette.copy(surfacePreset = null)))
                    for (rtl in listOf(false, true)) {
                        row.unbind()
                        row.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                        row.bind(model.copy(expanded = true), actions)
                        layout(row)
                        val height = row.height
                        assertEquals(model.subtitle, row.paragraphLeaves[1].getText())
                        val disc = descendants(row).filterIsInstance<NativeLeadingDisc>().single()
                        val stoppedIcon = pixels(disc)

                        row.bind(model.copy(isPlaying = true), actions)
                        layout(row)
                        assertEquals("Playing must not add a row of height", height, row.height)
                        assertEquals(context.getString(R.string.recording_playing_details, model.subtitle),
                            row.paragraphLeaves[1].getText())
                        assertFalse("The existing leading glyph must visibly change", stoppedIcon.contentEquals(pixels(disc)))

                        row.bind(model.copy(expanded = true), actions)
                        layout(row)
                        assertEquals(model.subtitle, row.paragraphLeaves[1].getText())
                        assertTrue("Pause must restore the stopped glyph", stoppedIcon.contentEquals(pixels(disc)))

                        // Selection/custom leading content must not erase the visible text indicator.
                        row.setSummarySlots(View(context), null)
                        row.bind(model.copy(isPlaying = true, selected = true, selectionActive = true,
                            interactionsEnabled = false, expanded = true), actions)
                        layout(row)
                        assertEquals(height, row.height)
                        assertEquals(context.getString(R.string.recording_playing_details, model.subtitle),
                            row.paragraphLeaves[1].getText())
                        row.setSummarySlots(null, null)

                        // Pause/end is authoritative even while the player remains expanded.
                        row.bind(model.copy(expanded = true), actions)
                        layout(row)
                        assertEquals(model.subtitle, row.paragraphLeaves[1].getText())

                        row.bind(model.copy(isPlaying = true), actions)
                        row.unbind()
                        assertEquals(View.INVISIBLE, row.visibility)
                        assertTrue(row.paragraphLeaves.all { it.getText().isEmpty() })
                        row.bind(model.copy(recordingId = "two", title = "Another recording"), actions)
                        layout(row)
                        assertEquals("two", row.recordingId)
                        assertEquals(model.subtitle, row.paragraphLeaves[1].getText())
                        assertTrue(stoppedIcon.contentEquals(pixels(disc)))
                    }
                }
                assertEquals("Presentation updates must never invoke playback", 0, activations)
            } finally {
                try { row.unbind() } finally { runtime.close() }
            }
        }
    }

    @Test fun attachedPlayingStateIsAccessibleAndClearsWhileExpanded() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var root: FrameLayout? = null
        var row: ReverbLibraryRecordingRow? = null
        var runtime: NativeModalWindowRuntime? = null
        val laidOut = CountDownLatch(1)
        val geometryLeases = mutableListOf<AutoCloseable>()
        withRetiringTestActivity(activity, root = { root }, cleanup = {
            try { geometryLeases.forEach { it.close() } }
            finally { try { row?.unbind() } finally { runtime?.close() } }
        }) {
            val model = LibraryRecordingRow("one", "Recording", "12 seconds", null,
                true, true, true, null, expanded = true, isPlaying = true)
            val actions = LibraryRecordingRowActions({}, null, null)
            onMain {
                val owner = NativeModalWindowRuntime(activity).also { runtime = it }
                root = FrameLayout(activity)
                row = ReverbLibraryRecordingRow(activity, ReverbEnvironment(activity, true), owner.scope,
                    checkNotNull(root)).also {
                    checkNotNull(root).addView(it, FrameLayout.LayoutParams(
                        (320f * activity.resources.displayMetrics.density).roundToInt(), -2))
                    it.bind(model, actions)
                    it.doOnLayout { laidOut.countDown() }
                }
                activity.setContentView(checkNotNull(root))
            }
            assertTrue("The recording row must be laid out before sampling accessibility",
                laidOut.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            val hosts = mutableListOf<View>()
            val hostNodes = mutableListOf<AccessibilityNodeInfo>()
            fun assertState(info: AccessibilityNodeInfo, expected: String?) {
                assertTrue("Both action nodes must be visible", info.isVisibleToUser)
                assertTrue("Both action nodes must be clickable", info.isClickable)
                assertTrue("Selection semantics must survive playback state", info.isSelected)
                assertEquals(expected, AccessibilityNodeInfoCompat.wrap(info).stateDescription?.toString())
            }
            try {
                onMain {
                    val view = checkNotNull(row)
                    assertTrue(view.isAttachedToWindow && view.isShown)
                    hosts += descendants(view).filterIsInstance<NativeMergedRowAccessibilityHost>().map { it as View }
                    assertEquals(2, hosts.size)
                    // The app window supplies these leases. This fixture binds the same delegate
                    // to its real measured bounds without supplying synthetic node presence.
                    val fixtureRoot = checkNotNull(root)
                    val geometry = NativePlainTextViewGeometry(fixtureRoot,
                        NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped },
                        rootContentClip = { Rect(0f, 0f, fixtureRoot.width.toFloat(), fixtureRoot.height.toFloat()) })
                    hosts.forEach { host ->
                        geometryLeases += (host as NativeMergedRowAccessibilityHost).mergedRowAccessibility
                            .bindGeometry(geometry) { host.isAttachedToWindow && host.isShown }
                    }
                    hosts.forEachIndexed { index, host ->
                        val info = checkNotNull(host.createAccessibilityNodeInfo()) { "Missing action node ${index + 1}" }
                        hostNodes += info
                        assertState(info, activity.getString(R.string.recording_playing))
                    }
                }
                fun assertTransition(playing: Boolean) {
                    val notified = mutableSetOf<Int>()
                    val event = automation.executeAndWaitForEvent({
                        onMain { checkNotNull(row).bind(model.copy(isPlaying = playing), actions) }
                    }, { event ->
                        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                            event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION != 0) {
                            val source = event.source
                            try {
                                val index = hostNodes.indexOf(source)
                                if (index >= 0) notified += index
                            } finally { source?.recycle() }
                        }
                        notified.size == hostNodes.size
                    }, 5_000L)
                    event.recycle()
                    assertEquals("Both retained action hosts must announce playback changes", 2, notified.size)
                    onMain {
                        hosts.forEachIndexed { index, host ->
                            val info = checkNotNull(host.createAccessibilityNodeInfo()) { "Missing action node ${index + 1}" }
                            try {
                                assertState(info, if (playing) activity.getString(R.string.recording_playing) else null)
                            } finally { info.recycle() }
                        }
                    }
                }
                assertTransition(false)
                assertTransition(true)
                onMain {
                    checkNotNull(row).unbind()
                    assertFalse(checkNotNull(row).isShown)
                }
            } finally { hostNodes.forEach { it.recycle() } }
        }
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun pixels(view: View): IntArray {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(bitmap))
            IntArray(view.width * view.height).also { bitmap.getPixels(it, 0, view.width, 0, 0, view.width, view.height) }
        } finally { bitmap.recycle() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun onMain(block: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }
}
