package smallthingz.ui.reverb

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.components.NativeModalWindowRuntime
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReverbIncidentListAccessibilityHostTest {
    @Test fun staleLeasePreservesReplacementAndCloseRestoresAttachedDayHeading() = withPanel { panel ->
        val history = history("Today")
        val heading = showDay(panel, history)
        val list = checkNotNull(panel.pointerList)
        val occurrence = requireNotNull(list.findViewHolderForAdapterPosition(2)).itemView
        val mergedLabel = paragraphs(occurrence).first { it.getText() == "PID 42" }
        assertFalse(heading.isScreenReaderFocusable)
        // An opt-in caller can already own this flag. Exercise restoring true as well as false.
        mergedLabel.isScreenReaderFocusable = true
        val originalHeading = node(heading)
        val originalMerged = node(mergedLabel)
        val firstBounds = Rect(12, 20, 140, 44)
        val nextBounds = Rect(24, 30, 160, 54)
        bind(panel, firstBounds).use { first ->
            assertSourceHeading(heading, firstBounds)
            assertFalse(mergedLabel.isScreenReaderFocusable)
            assertTrue(node(mergedLabel).hasSourceTextActions)
            panel.bind(history)
            assertSourceHeading(heading, firstBounds)
            assertFalse(mergedLabel.isScreenReaderFocusable)
            bind(panel, nextBounds).use {
                assertSourceHeading(heading, nextBounds)
                first.close()
                assertSourceHeading(heading, nextBounds)
                assertFalse(mergedLabel.isScreenReaderFocusable)
            }
            assertTrue(heading.isAttachedToWindow && mergedLabel.isAttachedToWindow)
            assertFalse(heading.isScreenReaderFocusable)
            assertTrue(mergedLabel.isScreenReaderFocusable)
            assertEquals(originalHeading, node(heading))
            assertEquals(originalMerged, node(mergedLabel))
        }
    }

    @Test fun detachedDayRetiresFlagAndReattachedListStillAdmitsNewHeading() = withPanel { panel ->
        val old = showDay(panel, history("Today"))
        val bounds = Rect(12, 20, 140, 44)
        assertFalse(old.isScreenReaderFocusable)
        bind(panel, bounds).use {
            assertSourceHeading(old, bounds)
            panel.bind(ReverbIncidentHistory(status = ReverbIncidentLoadStatus.READY))
            place(panel)
            assertFalse(old.isAttachedToWindow)
            assertFalse("Retirement must return the original flag before owner close", old.isScreenReaderFocusable)

            val current = showDay(panel, history("Tomorrow"))
            assertSourceHeading(current, bounds)
        }
        val currentList = checkNotNull(panel.pointerList)
        val current = requireNotNull(currentList.findViewHolderForAdapterPosition(0)).itemView as ParagraphView
        assertFalse(current.isScreenReaderFocusable)
    }

    private fun bind(panel: ReverbIncidentsContentView, bounds: Rect): AutoCloseable =
        panel.bindListAccessibility(
            PlainTextAccessibilityWindowScope(panel),
            NativePlainTextViewGeometry(panel, NativeTextSemanticsClipPolicy(panel::semanticsClip)),
        ) { NativeSourceAccessibilityNode(true, false, true, bounds) }

    private fun history(label: String): ReverbIncidentHistory {
        val row = object : ReverbIncidentOccurrence, ReverbIncidentTarget {
            override val key = label
            override val target: ReverbIncidentTarget get() = this
            override val acknowledged = false
            override val timeLabel = "10:00"
            override val processLabel = "PID 42"
            override val liveEnd: ReverbIncidentLiveEnd? = null
            override fun matches(reference: ReverbIncidentTarget) = reference === this
        }
        val day = ReverbIncidentDay(1, label, listOf(ReverbIncidentGroup("error", "Error", listOf(row))))
        return ReverbIncidentHistory(listOf(day), ReverbIncidentLoadStatus.READY, listOf(row))
    }

    private fun showDay(panel: ReverbIncidentsContentView, history: ReverbIncidentHistory): ParagraphView {
        panel.bind(history)
        val list = checkNotNull(panel.pointerList)
        list.itemAnimator = null
        place(panel)
        val heading = requireNotNull(list.findViewHolderForAdapterPosition(0)).itemView as ParagraphView
        assertEquals(history.days.single().label, heading.getText())
        assertTrue(heading.isAttachedToWindow)
        return heading
    }

    private fun place(panel: ReverbIncidentsContentView) {
        panel.requestLayout()
        panel.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        panel.layout(0, 0, 400, 800)
    }

    private fun assertSourceHeading(heading: ParagraphView, bounds: Rect) {
        assertTrue(heading.isScreenReaderFocusable)
        val state = node(heading)
        assertTrue(state.readerFocusable)
        assertTrue(state.hasSourceTextActions)
        assertEquals(bounds, state.bounds)
    }

    private data class NodeState(
        val readerFocusable: Boolean, val hasSourceTextActions: Boolean, val bounds: Rect,
    )

    @Suppress("DEPRECATION")
    private fun node(view: ParagraphView): NodeState {
        val info = requireNotNull(view.createAccessibilityNodeInfo())
        return try {
            NodeState(info.isScreenReaderFocusable,
                info.actionList.any { it.id == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY },
                Rect().also(info::getBoundsInScreen))
        } finally {
            info.recycle()
        }
    }

    private fun paragraphs(view: View): Sequence<ParagraphView> = sequence {
        if (view is ParagraphView) yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(paragraphs(view.getChildAt(index)))
    }

    private fun withPanel(test: (ReverbIncidentsContentView) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        try {
            NativeModalWindowRuntime(activity).use { runtime ->
                val panel = ReverbIncidentsContentView(activity, ReverbDesign.theme(activity, false),
                    ReverbIncidentTexts("Incidents", "Back", "Clear selection", { "$it selected" }, "Multi-select",
                        "Select all", "Deselect all", "Delete selected", "Clear all", "Clear incidents?", "Cancel",
                        "No incidents", "Nothing to show", "Loading", "Unavailable", "Stale", "Select", "Deselect",
                        "Mark checked", "Mark unchecked", "Options", "Copy", "Delete"),
                    ReverbIncidentActions({}, {}, {}, {}), {}, runtime.frames, runtime.scope, { 0f },
                    activity.window.decorView)
                try {
                    activity.setContentView(panel)
                    test(panel)
                } finally {
                    panel.dispose()
                }
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
