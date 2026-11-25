// Reverb application presentation source, retained under the Reverb project GPL-3.0 license.

package smallthingz.ui.components

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbIncidentActions
import smallthingz.ui.reverb.ReverbIncidentDay
import smallthingz.ui.reverb.ReverbIncidentGroup
import smallthingz.ui.reverb.ReverbIncidentHistory
import smallthingz.ui.reverb.ReverbIncidentLiveEnd
import smallthingz.ui.reverb.ReverbIncidentLoadStatus
import smallthingz.ui.reverb.ReverbIncidentOccurrence
import smallthingz.ui.reverb.ReverbIncidentTarget
import smallthingz.ui.reverb.ReverbIncidentTexts
import smallthingz.ui.reverb.ReverbIncidentsContentView
import smallthingz.ui.reverb.ReverbToolbarActionView
import smallthingz.ui.scroll.NativeSourceRecyclerView
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.util.closePreservingPrimaryFailure
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ReverbIncidentScrollIndicatorIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun overflowingHistoryRecolorsRetainedListAndKeepsScrollAndSelectionActions() = withFixture(insetAppearance) { f ->
        f.publish(today, olderHistory)
        val list = f.list
        val adapter = checkNotNull(list.adapter)
        val top = checkNotNull(f.panel.savePosition())
        assertTrue("History remains virtualized", list.childCount < adapter.itemCount)
        assertTrue("Populated history overflows the real viewport", list.canScrollVertically(1))
        assertTrue("Initial muted color paints the thumb", list.rightGutterContains(initialMuted))
        assertFalse("Initial theme supplies the wider inset",
            list.rightGutterContains(initialMuted, list.paddingRight / 2))

        val node = checkNotNull(list.createAccessibilityNodeInfo())
        try {
            assertTrue(node.isScrollable)
            assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
        list.scrollBy(0, list.height)
        val scrolled = checkNotNull(f.panel.savePosition())
        assertTrue("Existing RecyclerView scrolling moves visible rows",
            scrolled.firstVisibleItemIndex > top.firstVisibleItemIndex)

        f.recolor()
        assertSame(list, f.list)
        assertSame(adapter, f.list.adapter)
        assertEquals(scrolled, f.panel.savePosition())
        assertTrue("New muted color paints the thumb", list.rightGutterContains(updatedMuted))
        assertFalse("Palette rebind retains the inset",
            list.rightGutterContains(updatedMuted, list.paddingRight / 2))
        assertFalse("Old muted color is retired", list.rightGutterContains(initialMuted))

        list.scrollBy(0, -list.height)
        f.selectFirstTopic()
        assertTrue(f.panel.selectionActive)
        f.deleteSelected()
        assertEquals(listOf("today-1", "today-2"), f.deleted)
    }

    @Test
    fun thumbAppearsOnlyWhilePublishedHistoryOverflows() = withFixture { f ->
        f.publish(shortHistory)
        val shortList = f.list
        assertFalse(shortList.canScrollVertically(1))
        assertFalse(shortList.canScrollVertically(-1))
        assertFalse(shortList.rightGutterContains(initialMuted))

        f.publish(olderHistory)
        assertTrue(f.list.canScrollVertically(1))
        assertTrue(f.list.rightGutterContains(initialMuted))

        f.publish(shortHistory)
        assertFalse(f.list.canScrollVertically(1))
        assertFalse(f.list.canScrollVertically(-1))
        assertFalse("Previous overflow leaves no thumb", f.list.rightGutterContains(initialMuted))

        f.publish()
        assertNull("Empty history has no scroll surface", f.panel.pointerList)
        f.recolor()
        f.publish(olderHistory)
        assertTrue("Returning history uses the current theme", f.list.rightGutterContains(updatedMuted))
    }

    private data class Row(override val key: String) : ReverbIncidentOccurrence, ReverbIncidentTarget {
        override val target: ReverbIncidentTarget get() = this
        override val acknowledged = false
        override val timeLabel get() = key
        override val processLabel = "PID 42"
        override val liveEnd: ReverbIncidentLiveEnd? = null

        override fun matches(reference: ReverbIncidentTarget) = reference is Row && key == reference.key
    }

    private class Fixture(val activity: Activity, runtime: NativeModalWindowRuntime, appearance: NativeUiAppearance) {
        private val environment = ReverbEnvironment.withTestAppearance(activity, false, appearance).let {
            it.withPalette(it.palette.copy(onSurfaceVariant = initialMuted))
        }
        val deleted = mutableListOf<String>()
        val panel = ReverbIncidentsContentView(
            activity, environment.theme, texts,
            ReverbIncidentActions(
                { error("Unexpected acknowledgement") }, { error("Unexpected deletion") },
                { targets -> deleted += targets.map { (it as Row).key } }, { error("Unexpected copy") },
            ), {}, runtime.frames, runtime.scope, { 0f }, activity.window.decorView,
        )
        private val accessibility = IncidentFixtureAccessibility(panel, NativeTextSemanticsClipPolicy(panel::semanticsClip))
        val list get() = checkNotNull(panel.pointerList)

        fun publish(vararg days: ReverbIncidentDay) {
            panel.bind(ReverbIncidentHistory(days.toList(), ReverbIncidentLoadStatus.READY,
                days.flatMap { day -> day.groups.flatMap { it.occurrences } }))
            panel.pointerList?.itemAnimator = null
            place()
        }

        fun recolor() {
            panel.bindTheme(environment.withPalette(environment.palette.copy(onSurfaceVariant = updatedMuted)).theme)
            place()
        }

        fun selectFirstTopic() {
            accessibility.refresh()
            // Position 0 is the date heading. The first message's key also occurs on Yesterday.
            val heading = checkNotNull(list.findViewHolderForAdapterPosition(1)).itemView
            assertTrue(heading.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
        }

        fun deleteSelected() {
            place()
            val action = descendants(panel).filterIsInstance<ReverbToolbarActionView>()
                .single { toolbar ->
                    descendants(toolbar.accessibilityAction).any { it.contentDescription?.toString() == texts.deleteSelected }
                }.accessibilityAction
            assertTrue(action.performClick())
        }

        fun closeAccessibility() = accessibility.close()

        private fun place() {
            val density = activity.resources.displayMetrics.density
            fun spec(dp: Int) = View.MeasureSpec.makeMeasureSpec((dp * density).roundToInt(), View.MeasureSpec.EXACTLY)
            panel.requestLayout()
            panel.measure(spec(400), spec(480))
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            accessibility.refresh()
            panel.pointerList?.let { list ->
                assertTrue("List is attached, visible and laid out",
                    list.isAttachedToWindow && list.isShown && list.isLaidOut)
                assertTrue("List has a positive content viewport",
                    list.width > list.paddingLeft + list.paddingRight &&
                        list.height > list.paddingTop + list.paddingBottom)
                assertTrue("Published rows are materialized", list.childCount > 0)
                assertFalse("Adapter updates have reached layout", list.hasPendingAdapterUpdates())
            }
        }
    }

    private fun NativeSourceRecyclerView.rightGutterContains(color: Int, stripWidth: Int = paddingRight): Boolean {
        // Sample the strip reserved by existing row padding, clear of matching day-heading text.
        val bitmap = Bitmap.createBitmap(stripWidth, height, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            // Resolve width on this RecyclerView, outside a Canvas receiver scope.
            canvas.translate((stripWidth - width).toFloat(), 0f)
            draw(canvas)
            (0 until bitmap.height).any { y -> (0 until bitmap.width).any { x -> bitmap.getPixel(x, y) == color } }
        } finally {
            bitmap.recycle()
        }
    }

    private fun withFixture(appearance: NativeUiAppearance = ReverbDesign.appearance, test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var runtime: NativeModalWindowRuntime? = null
        var fixture: Fixture? = null
        withRetiringTestActivity(activity, root = { fixture?.panel }, cleanup = {
            var failure = closePreservingPrimaryFailure(null) { fixture?.closeAccessibility() }
            failure = closePreservingPrimaryFailure(failure) { fixture?.panel?.dispose() }
            failure = closePreservingPrimaryFailure(failure) { runtime?.close() }
            failure?.let { throw it }
        }) {
            main {
                val owner = NativeModalWindowRuntime(activity).also { runtime = it }
                fixture = Fixture(activity, owner, appearance).also { activity.setContentView(it.panel) }
                owner.setStarted(true)
            }
            instrumentation.waitForIdleSync()
            main { test(checkNotNull(fixture)) }
        }
    }

    private fun main(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }

    private companion object {
        private val initialMuted = 0xffe812a6.toInt()
        private val updatedMuted = 0xff098e32.toInt()
        private val insetAppearance = ReverbDesign.appearance.let {
            it.copy(scrollIndicator = it.scrollIndicator.copy(rightInsetDp = 10f))
        }
        private val shortHistory = ReverbIncidentDay(2, "Today",
            listOf(ReverbIncidentGroup("topic", "Error", listOf(Row("today-1")))))
        private val today = ReverbIncidentDay(2, "Today",
            listOf(
                ReverbIncidentGroup("topic", "Error", listOf(Row("today-1"), Row("today-2"))),
                ReverbIncidentGroup("other", "Other error", listOf(Row("other"))),
            ))
        private val olderHistory = ReverbIncidentDay(1, "Yesterday",
            listOf(ReverbIncidentGroup("topic", "Error", List(40) { Row("old-$it") })))

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
