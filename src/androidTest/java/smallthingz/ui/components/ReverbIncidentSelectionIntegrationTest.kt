package smallthingz.ui.components

import android.app.Activity
import android.content.Intent
import android.graphics.Path
import android.graphics.Region
import android.view.View
import android.view.MotionEvent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.filters.SdkSuppress
import smallthingz.ui.input.phase.NativeClickPhaseHost
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.reverb.*
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import java.util.IdentityHashMap
import smallthingz.ui.util.closePreservingPrimaryFailure
import kotlin.math.roundToInt

/** Selection through real title/occurrence actions and the current adapter; no storage or selection model copy. */
@RunWith(AndroidJUnit4::class)
class ReverbIncidentSelectionIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test @SdkSuppress(minSdkVersion = 29)
    fun titlePhaseLongPressAddsOnlySameDateTopicAndPreservesOtherSelectionsAndCorners() = withFixture { f ->
        // Equal display text intentionally has a different error identity.
        val ambiguous = ReverbIncidentGroup("different-error", "Error A", listOf(Row("not-a")))
        f.bind(day(2, "Today", group("A", "a1", "a2"), group("B", "b")),
            day(1, "Yesterday", group("A", "old-a"), ambiguous))
        f.click("Multi-select")
        f.activateOccurrence(5) // Keep an unrelated occurrence selected.
        val heading = f.rowAt(1)
        assertTrue(heading is NativeClickPhaseHost)
        f.phase(heading, MotionEvent.ACTION_DOWN)
        f.phase(heading, MotionEvent.ACTION_MOVE, deep = true)
        f.phase(heading, MotionEvent.ACTION_UP)
        f.place()
        assertTrue(f.panel.selectionActive)
        // Observe the pointer result before any semantic action can mask a broken phase hookup.
        for (position in listOf(2, 3, 5)) f.assertSelected(position, true)
        f.assertSelected(8, false)
        f.assertSelected(10, false)
        // Repeating the title action adds, never toggles the group back off.
        assertTrue(heading.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
        f.assertRows(dayLabel("Today"), top("Error A"), square("a1"), square("a2", gap = 2),
            square("Error B"), bottom("b", gap = 2), dayLabel("Yesterday"), top("Error A"),
            square("old-a", gap = 2), square("Error A"), bottom("not-a"))
        f.click("Delete selected")
        assertEquals(setOf("b", "a1", "a2"), f.deleted.single().map { it.identity }.toSet())
        assertEquals(3, f.deleted.single().size)
        assertEquals(0, f.acknowledgements)
    }

    @Test fun semanticTitleSelectionIsDateAndTopicScopedWhileRowAndSelectAllActionsRemainIndividual() = withFixture { f ->
        val sameLabelOtherTopic = ReverbIncidentGroup("different-error", "Error A", listOf(Row("not-a")))
        // Labels are presentation only: even identical date/topic labels must use their actual keys.
        f.bind(day(2, "Same date label", group("A", "a1", "a2"), sameLabelOtherTopic),
            day(1, "Same date label", group("A", "old-a")))
        f.activateOccurrence(2)
        assertEquals(1, f.acknowledgements)
        assertFalse(f.panel.selectionActive)
        assertTrue(f.rowAt(1).performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
        f.place()
        f.assertSelected(2, true)
        f.assertSelected(3, true)
        f.assertSelected(5, false)
        f.assertSelected(8, false)
        f.activateOccurrence(2)
        f.assertSelected(2, false)
        f.assertSelected(3, true)
        assertEquals(1, f.acknowledgements)
        f.click("Select all")
        for (position in listOf(2, 3, 5, 8)) f.assertSelected(position, true)
        f.click("Deselect all")
        for (position in listOf(2, 3, 5, 8)) f.assertSelected(position, false)
        assertTrue(f.rowAt(7).performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
        f.place()
        for (position in listOf(2, 3, 5)) f.assertSelected(position, false)
        f.assertSelected(8, true)
        f.click("Delete selected")
        assertEquals(listOf("old-a"), f.deleted.single().map { it.identity })
    }

    @Test fun titleSelectionPinsOccurrenceIdentityThroughEnrichmentAndConcurrentArrivals() = withFixture { f ->
        f.bind(day(2, "Today", group("A", "a1", "a2")))
        assertTrue(f.rowAt(1).performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
        assertTrue(f.panel.selectionActive)
        val enriched = Row("a2-new-key", identity = "a2", revision = 1)
        val replacement = Row("a1", identity = "replacement")
        val arrival = Row("new-arrival")
        f.bind(day(1, "Yesterday", ReverbIncidentGroup("A", "Error A", listOf(replacement, enriched, arrival))))
        f.click("Delete selected")
        val accepted = f.deleted.single()
        assertEquals(1, accepted.size)
        assertSame("Use the current target for the exact selected identity", enriched, accepted.single())
        assertEquals(0, f.acknowledgements)
    }

    @Test fun unavailableHistoryAndRemovedDayHeadingCannotSelectSurvivingError() = withFixture { f ->
        val today = day(2, "Today", group("A", "a"))
        val yesterday = day(1, "Yesterday", group("A", "old-a"))
        f.bind(today, yesterday)
        val staleHeading = f.rowAt(1)
        // Deliberately invoke before RecyclerView consumes its rebind: authority must be current now.
        f.publish(ReverbIncidentLoadStatus.UNAVAILABLE, today, yesterday)
        assertFalse(staleHeading.performLongClick())
        assertFalse(f.panel.selectionActive)
        f.place()
        // UNAVAILABLE prepends a status row: status=0, day=1, error heading=2.
        // Layout may replace the holder; inspect the current disabled error action, not the day label.
        val disabledHeading = f.rowAt(2)
        assertTrue(disabledHeading is NativeMergedRowAccessibilityHost)
        assertTrue(disabledHeading is NativeClickPhaseHost)
        val info = checkNotNull(disabledHeading.createAccessibilityNodeInfo())
        try { assertFalse(info.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }) }
        finally { @Suppress("DEPRECATION") info.recycle() }
        f.bind(today, yesterday)
        val removed = f.rowAt(1)
        f.publish(ReverbIncidentLoadStatus.READY, yesterday)
        assertFalse(removed.performLongClick())
        assertFalse(f.panel.selectionActive)
        f.place()
        assertTrue(f.rowAt(1).performLongClick())
        f.click("Delete selected")
        assertEquals(listOf("old-a"), f.deleted.single().map { it.identity })
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun parentConsumedTitlePressCannotStartSelection() = withFixture { f ->
        f.bind(day(2, "Today", group("A", "a")))
        val heading = f.rowAt(1)
        f.phase(heading, MotionEvent.ACTION_DOWN)
        (heading as NativeClickPhaseHost).nativeClickEndpoint.afterParentDispatch(true)
        f.phase(heading, MotionEvent.ACTION_MOVE, deep = true)
        f.phase(heading, MotionEvent.ACTION_UP)
        assertFalse(f.panel.selectionActive)
        assertTrue(f.deleted.isEmpty())
    }

    private data class Row(override val key: String, val identity: String = key, val revision: Int = 0) : ReverbIncidentOccurrence, ReverbIncidentTarget {
        override val target: ReverbIncidentTarget get() = this
        override val acknowledged = false
        override val timeLabel get() = key
        override val processLabel = "PID 42"
        override val liveEnd: ReverbIncidentLiveEnd? = null
        override fun matches(reference: ReverbIncidentTarget) = reference is Row && identity == reference.identity
    }

    private fun group(key: String, vararg rows: String) = ReverbIncidentGroup(key, "Error $key", rows.map { Row(it) })
    private fun day(epoch: Long, label: String, vararg groups: ReverbIncidentGroup) =
        ReverbIncidentDay(epoch, label, groups.toList())

    // Expected silhouettes and gaps are explicit user-visible outcomes, not adapter edge flags.
    private data class Expected(val label: String, val topRounded: Boolean?, val bottomRounded: Boolean?, val gap: Int)
    private fun dayLabel(label: String) = Expected(label, null, null, 2)
    private fun top(label: String) = Expected(label, true, false, 0)
    private fun square(label: String, gap: Int = 0) = Expected(label, false, false, gap)
    private fun bottom(label: String, gap: Int = 0) = Expected(label, false, true, gap)

    private class Fixture(val activity: Activity, runtime: NativeModalWindowRuntime) {
        val deleted = mutableListOf<List<Row>>()
        var acknowledgements = 0
        val panel = ReverbIncidentsContentView(activity, ReverbDesign.theme(activity, false), texts,
            ReverbIncidentActions({ acknowledgements++ }, { error("Unexpected deletion") },
                { deleted += it.map { target -> target as Row } }, { error("Unexpected copy") }), {},
            runtime.frames, runtime.scope, { 0f }, activity.window.decorView)
        private val accessibility = IncidentFixtureAccessibility(panel, NativeTextSemanticsClipPolicy(panel::semanticsClip))
        init { panel.observeAccessibilityMembership(accessibility::refresh) }
        fun closeAccessibility() {
            panel.observeAccessibilityMembership {}
            accessibility.close()
        }
        private val list get() = checkNotNull(panel.pointerList)
        private val adapter get() = checkNotNull(list.adapter)
        private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()

        fun bind(vararg days: ReverbIncidentDay) {
            publish(ReverbIncidentLoadStatus.READY, *days)
            // Shape/layout regression, not animation timing: settle real RV updates without ghosts.
            panel.pointerList?.itemAnimator = null
            place()
        }

        fun publish(status: ReverbIncidentLoadStatus, vararg days: ReverbIncidentDay) {
            panel.bind(ReverbIncidentHistory(days.toList(), status,
                days.flatMap { day -> day.groups.flatMap { it.occurrences } }))
        }

        fun rowAt(position: Int): View = checkNotNull(list.findViewHolderForAdapterPosition(position)).itemView
        fun assertSelected(position: Int, expected: Boolean) {
            val body = descendants(rowAt(position)).first { it is NativeClickPhaseHost }
            val info = checkNotNull(body.createAccessibilityNodeInfo())
            try { assertEquals("Occurrence at $position", expected, info.isSelected) }
            finally { @Suppress("DEPRECATION") info.recycle() }
        }
        fun activateOccurrence(position: Int) {
            val body = descendants(rowAt(position)).first { it is NativeClickPhaseHost }
            assertTrue(body.performClick())
            place()
        }
        fun phase(view: View, action: Int, deep: Boolean = false) {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, view.width / 2f, view.height / 2f, 0)
            try {
                (view as NativeClickPhaseHost).nativeClickEndpoint.touch(event, false, {}, true,
                    if (deep) MotionEvent.CLASSIFICATION_DEEP_PRESS else 0)
            } finally { event.recycle() }
        }

        fun place() {
            // Fixed generous test viewport makes every asserted row materialize, independent of phone rotation.
            panel.requestLayout()
            panel.measure(View.MeasureSpec.makeMeasureSpec(dp(400), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(1600), View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            accessibility.refresh()
        }

        fun idAt(position: Int) = adapter.getItemId(position)
        fun ids() = List(adapter.itemCount, ::idAt)

        fun click(label: String) {
            val action = descendants(panel).filterIsInstance<ReverbToolbarActionView>()
                .single { action ->
                    // The rendered icon child owns the semantic description, not a tooltip.
                    descendants(action.accessibilityAction).any { it.contentDescription?.toString() == label }
                }.accessibilityAction
            assertTrue(action.isAttachedToWindow && action.isShown)
            assertTrue(action.performClick())
            place()
        }

        fun assertRows(vararg expected: Expected) {
            place()
            assertEquals("Actual adapter membership", expected.size, adapter.itemCount)
            val views = expected.indices.map { position ->
                checkNotNull(list.findViewHolderForAdapterPosition(position)) { "Row $position not laid out" }.itemView
            }
            expected.forEachIndexed { index, row ->
                val view = views[index]
                assertTrue("Missing displayed label ${row.label}",
                    descendants(view).filterIsInstance<ParagraphView>().any { it.getText() == row.label })
                if (row.topRounded == null) {
                    assertTrue("Day remains a separate heading", view is ParagraphView)
                } else {
                    val surface = (view as ViewGroup).getChildAt(0) as NativeGroupLayout
                    assertShape(row.label, surface, row.topRounded, checkNotNull(row.bottomRounded))
                }
                if (index < views.lastIndex) assertEquals("Compact join after ${row.label}",
                    view.bottom + dp(row.gap), views[index + 1].top)
            }
        }

        private fun assertShape(label: String, surface: NativeGroupLayout, topRounded: Boolean, bottomRounded: Boolean) {
            assertTrue(surface.width > 4 && surface.height > 4)
            val path = Path().also(surface::copyVisualPath)
            val fill = Region().apply { setPath(path, Region(0, 0, surface.width, surface.height)) }
            assertTrue("Nonempty painted surface: $label", fill.contains(surface.width / 2, surface.height / 2))
            fun corner(name: String, x: Int, y: Int, rounded: Boolean) {
                assertEquals("$label $name fill corner", !rounded, fill.contains(x, y))
                assertEquals("$label $name hit corner follows the fill", !rounded,
                    surface.containsVisualPoint(x + .5f, y + .5f))
            }
            corner("top-left", 1, 1, topRounded)
            corner("top-right", surface.width - 2, 1, topRounded)
            corner("bottom-left", 1, surface.height - 2, bottomRounded)
            corner("bottom-right", surface.width - 2, surface.height - 2, bottomRounded)
        }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var runtime: NativeModalWindowRuntime? = null
        var fixture: Fixture? = null
        var failure: Throwable? = null
        try {
            main {
                val owner = NativeModalWindowRuntime(activity).also { runtime = it }
                fixture = Fixture(activity, owner).also { activity.setContentView(it.panel) }
                owner.setStarted(true)
            }
            instrumentation.waitForIdleSync()
            main { test(checkNotNull(fixture)) }
        } catch (error: Throwable) {
            failure = error
        } finally {
            main {
                failure = closePreservingPrimaryFailure(failure) { fixture?.closeAccessibility() }
                failure = closePreservingPrimaryFailure(failure) { fixture?.panel?.dispose() }
                failure = closePreservingPrimaryFailure(failure) { runtime?.close() }
                failure = closePreservingPrimaryFailure(failure) { activity.finish() }
            }
        }
        failure?.let { throw it }
    }

    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    companion object {
        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        private val texts = ReverbIncidentTexts(
            "Incidents", "Back", "Clear selection", { "$it selected" }, "Multi-select", "Select all", "Deselect all",
            "Delete selected", "Clear all", "Clear incidents?", "Cancel", "No incidents", "Nothing to show",
            "Loading", "Unavailable", "Stale", "Select", "Deselect", "Mark checked", "Mark unchecked", "Options", "Copy", "Delete",
        )
    }
}

/**
 * The app's NativePageTextAccessibilityOwner normally supplies these leases. These standalone
 * component fixtures have no app window owner, so bind the real geometry for their measured root.
 * The selection root deliberately remains the existing 1600dp fixture viewport, not the device
 * decor clip. Presence still requires attached, measured bounds; no source-node presence is forged.
 */
internal class IncidentFixtureAccessibility(
    private val root: ViewGroup,
    clips: NativeTextSemanticsClipPolicy = NativeTextSemanticsClipPolicy { view ->
        if (view.parent is NativeGroupLayout && (view.parent as NativeGroupLayout).getChildAt(0) === view)
            NativeTextSemanticsClip.Rectangle(smallthingz.ui.geometry.Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
        else NativeTextSemanticsClip.Unclipped
    },
) : AutoCloseable {
    private val geometry = NativePlainTextViewGeometry(root, clips, rootContentClip = {
        smallthingz.ui.geometry.Rect(0f, 0f, root.width.toFloat(), root.height.toFloat())
    })
    private val leases = IdentityHashMap<View, AutoCloseable>()

    fun refresh() {
        val present = IdentityHashMap<View, Boolean>()
        fun visit(view: View) {
            if (view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) return
            if (view is NativeMergedRowAccessibilityHost) present[view] = true
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
        leases.keys.filter { !present.containsKey(it) }.forEach { leases.remove(it)?.close() }
        for (view in present.keys) if (!leases.containsKey(view)) {
            leases[view] = (view as NativeMergedRowAccessibilityHost).mergedRowAccessibility.bindGeometry(geometry) {
                view.isAttachedToWindow && view.isShown
            }
        }
    }

    override fun close() {
        leases.values.forEach { it.close() }
        leases.clear()
    }
}
