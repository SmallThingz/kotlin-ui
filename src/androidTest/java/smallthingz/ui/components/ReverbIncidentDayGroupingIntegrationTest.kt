package smallthingz.ui.components

import android.app.Activity
import android.content.Intent
import android.graphics.Path
import android.graphics.Region
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.reverb.*
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.util.closePreservingPrimaryFailure
import kotlin.math.roundToInt

/** Actual incident adapter/surfaces in the existing host; no storage or copied grouping model. */
@RunWith(AndroidJUnit4::class)
class ReverbIncidentDayGroupingIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun threeMessagesShareOnlyTheDayOuterCornersThroughSelectionUpdates() = withFixture { f ->
        f.bind(day(2, "Today", group("A", "a"), group("B", "b"), group("C", "c")))
        val expected = arrayOf(dayLabel("Today"), top("Error A"), square("a"),
            square("Error B"), square("b"), square("Error C"), bottom("c"))
        f.assertRows(*expected)
        val ids = f.ids()
        f.click("Multi-select")
        assertTrue(f.panel.selectionActive)
        f.click("Select all")
        f.assertRows(*expected)
        assertEquals(ids, f.ids())
        assertTrue(f.panel.consumeSelectionBack())
        f.assertRows(*expected)
    }

    @Test fun singletonAndMultipleOccurrencesStopAtEachDayHeader() = withFixture { f ->
        // Equal message/group keys on different days must never merge their outer surfaces.
        f.bind(day(2, "Today", group("A", "a1", "a2")),
            day(1, "Yesterday", group("A", "old-a")))
        f.assertRows(dayLabel("Today"), top("Error A"), square("a1"), bottom("a2", gap = 2),
            dayLabel("Yesterday"), top("Error A"), bottom("old-a"))
        f.bind(day(1, "Yesterday", group("A", "old-a")))
        f.assertRows(dayLabel("Yesterday"), top("Error A"), bottom("old-a"))
    }

    @Test fun deletingFirstAndLastMessagesRebindsSurvivingCornersAndReinsertionFlattensThem() = withFixture { f ->
        val a = group("A", "a")
        val b = group("B", "b1", "b2")
        val c = group("C", "c")
        f.bind(day(2, "Today", a, b, c))
        f.assertRows(dayLabel("Today"), top("Error A"), square("a"), square("Error B"),
            square("b1"), square("b2"), square("Error C"), bottom("c"))
        val bHeadingId = f.idAt(3)
        val bLastId = f.idAt(5)
        f.bind(day(2, "Today", b, c)) // Accepted history after removal of the first message.
        f.assertRows(dayLabel("Today"), top("Error B"), square("b1"), square("b2"),
            square("Error C"), bottom("c"))
        assertEquals(bHeadingId, f.idAt(1))
        assertEquals(bLastId, f.idAt(3))
        f.bind(day(2, "Today", b)) // Then remove the last message.
        f.assertRows(dayLabel("Today"), top("Error B"), square("b1"), bottom("b2"))
        assertEquals(bHeadingId, f.idAt(1))
        assertEquals(bLastId, f.idAt(3))
        f.bind(day(2, "Today", a, b, c))
        f.assertRows(dayLabel("Today"), top("Error A"), square("a"), square("Error B"),
            square("b1"), square("b2"), square("Error C"), bottom("c"))
        assertEquals(bHeadingId, f.idAt(3))
        assertEquals(bLastId, f.idAt(5))
    }

    @Test fun emptyGroupsCannotStealCornersAndRecreatedListRestoresSingletonShape() = withFixture { f ->
        f.bind(day(3, "Empty day", group("none")),
            day(2, "Today", group("empty-first"), group("A", "a"), group("empty-middle"),
                group("B", "b"), group("empty-last")), day(1, "Another empty day"))
        f.assertRows(dayLabel("Today"), top("Error A"), square("a"), square("Error B"), bottom("b"))
        f.bind(day(2, "Today", group("empty-first"), group("A", "a"), group("B")))
        f.assertRows(dayLabel("Today"), top("Error A"), bottom("a"))
        f.bind()
        assertNull(f.panel.pointerList)
        f.bind(day(1, "Yesterday", group("B", "new-b")))
        f.assertRows(dayLabel("Yesterday"), top("Error B"), bottom("new-b"))
    }

    private data class Row(override val key: String) : ReverbIncidentOccurrence, ReverbIncidentTarget {
        override val target: ReverbIncidentTarget get() = this
        override val acknowledged = false
        override val timeLabel get() = key
        override val processLabel = "PID 42"
        override val liveEnd: ReverbIncidentLiveEnd? = null
        override fun matches(reference: ReverbIncidentTarget) = reference is Row && key == reference.key
    }

    private fun group(key: String, vararg rows: String) = ReverbIncidentGroup(key, "Error $key", rows.map(::Row))
    private fun day(epoch: Long, label: String, vararg groups: ReverbIncidentGroup) =
        ReverbIncidentDay(epoch, label, groups.toList())

    // Expected silhouettes and gaps are explicit user-visible outcomes, not adapter edge flags.
    private data class Expected(val label: String, val topRounded: Boolean?, val bottomRounded: Boolean?, val gap: Int)
    private fun dayLabel(label: String) = Expected(label, null, null, 2)
    private fun top(label: String) = Expected(label, true, false, 0)
    private fun square(label: String) = Expected(label, false, false, 0)
    private fun bottom(label: String, gap: Int = 0) = Expected(label, false, true, gap)

    private class Fixture(val activity: Activity, runtime: NativeModalWindowRuntime) {
        val panel = ReverbIncidentsContentView(activity, ReverbDesign.theme(activity, false), texts,
            ReverbIncidentActions({ error("Unexpected acknowledgement") }, { error("Unexpected deletion") },
                { error("Unexpected batch deletion") }, { error("Unexpected copy") }), {},
            runtime.frames, runtime.scope, { 0f }, activity.window.decorView)
        private val list get() = checkNotNull(panel.pointerList)
        private val adapter get() = checkNotNull(list.adapter)
        private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()

        fun bind(vararg days: ReverbIncidentDay) {
            panel.bind(ReverbIncidentHistory(days.toList(), ReverbIncidentLoadStatus.READY,
                days.flatMap { day -> day.groups.flatMap { it.occurrences } }))
            // Shape/layout regression, not animation timing: settle real RV updates without ghosts.
            panel.pointerList?.itemAnimator = null
            place()
        }

        fun place() {
            // Fixed generous test viewport makes every asserted row materialize, independent of phone rotation.
            panel.requestLayout()
            panel.measure(View.MeasureSpec.makeMeasureSpec(dp(400), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(1600), View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
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
