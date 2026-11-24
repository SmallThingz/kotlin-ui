package smallthingz.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.reverb.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Attached production-theme/ListView tests. Subjects are never manually measured or laid out.
 * These are source-only candidates until the root's immutable Android probe executes them.
 * They qualify the shared presentation, not Control's history limits or tail-follow state machine.
 */
@RunWith(AndroidJUnit4::class)
class ReverbCommandRecordIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private val labels = ReverbCommandRecordLabels("Read full command", "Output", "Expanded", "Collapsed")
    private fun record(key: Long = 1, expanded: Boolean = false) = ReverbCommandRecord(
        key, "Android · task $key", "printf 'hello'", true, "Running",
        "Android task $key, Running, printf hello", "Agent: test", "Running\nAgent: test",
        "hello\n", null, false, expanded,
    )

    @Test fun identicalAndCallbackOnlyBindsAvoidRealTraversalAndKeepFocusedLeaves() = withFixture(1) { f ->
        lateinit var row: ReverbCommandRecordView
        lateinit var header: NativeSettingsRow
        lateinit var geometry: List<Rect>
        main {
            row = f.row(0)
            header = row.header()
            header.isFocusableInTouchMode = true
            assertTrue(header.requestFocus())
            geometry = row.geometry()
        }
        f.settle()
        // Positive control: counters observe an actual ViewRoot measure/layout, not just flags.
        main { f.frame.reset(); row.requestLayout() }
        f.settle()
        main { assertTrue(f.frame.requests > 0); assertTrue(f.frame.measures > 0); assertTrue(f.frame.layouts > 0) }
        repeat(3) {
            main {
                f.frame.reset()
                f.bindVisible()
            }
            f.settle()
            main {
                assertEquals(0, f.frame.requests)
                assertEquals(0, f.frame.measures)
                assertEquals(0, f.frame.layouts)
                assertSame(row, f.row(0))
                assertSame(header, row.header())
                assertTrue(header.hasFocus())
                assertEquals(geometry, row.geometry())
            }
        }
        var old = 0
        var fresh = 0
        main { row.bind(f.records[0], { old++ }, {}) }
        f.settle()
        main { f.frame.reset(); row.bind(f.records[0], { fresh++ }, {}) }
        f.settle()
        main {
            assertEquals(0, f.frame.requests)
            header.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id, null)
            assertEquals(0, old)
            assertEquals(1, fresh)
            // Activation requests state; it does not mutate the authoritative bind.
            assertEquals("Collapsed", ViewCompat.getStateDescription(header))
        }
    }

    @Test fun expandedOutputUpdatesKeepIdentityFocusAndUnchangedBindsStayIdle() = withFixture(1) { f ->
        val text = "stdout: α\tשלום\nline 2\n".repeat(40)
        main {
            f.records[0] = f.records[0].copy(expanded = true, output = text, outputOmission = "Earlier output omitted")
            f.bindVisible()
        }
        f.settle()
        lateinit var header: NativeSettingsRow
        lateinit var leaf: NativeText
        main {
            val row = f.row(0)
            header = row.header()
            leaf = row.text(text)
            assertEquals(View.TEXT_DIRECTION_LTR, leaf.textDirection)
            assertEquals(text, leaf.text.toString())
            assertEquals(View.VISIBLE, row.text("Earlier output omitted").visibility)
            header.isFocusableInTouchMode = true
            assertTrue(header.requestFocus())
            assertEquals("Expanded", ViewCompat.getStateDescription(header))
        }
        f.settle()
        main { f.frame.reset(); f.bindVisible() }
        f.settle()
        main {
            assertEquals(0, f.frame.requests)
            assertEquals(0, f.frame.measures)
            assertEquals(0, f.frame.layouts)
            f.records[0] = f.records[0].copy(output = text + "last line\n")
            f.bindVisible()
        }
        f.settle()
        main {
            assertSame(leaf, f.row(0).text(text + "last line\n"))
            assertSame(header, f.row(0).header())
            assertTrue(header.hasFocus())
        }
    }

    @Test fun actualClippingRevealsFullTextAndStatusFallbackAcrossResizeAndRtl() = withFixture(1) { f ->
        main {
            f.records[0] = f.records[0].copy(expanded = true, preview = "long technical command ".repeat(80),
                status = "A very long localized status that cannot fit", detailsWhenStatusClipped = "Full status\nAgent: test")
            f.bindVisible()
        }
        f.settle()
        lateinit var action: NativeMaterialButtonView
        lateinit var row: ReverbCommandRecordView
        main {
            row = f.row(0)
            action = row.descendants().filterIsInstance<NativeMaterialButtonView>().single()
            assertTrue(action.isShown)
            assertEquals("Full status\nAgent: test", row.text("Full status\nAgent: test").text.toString())
            assertTrue((action.parent as View).height >= f.dp(48))
            assertTrue(action.performClick())
            assertEquals(listOf(1L), f.fullTextRequests)
            assertSame(action, f.lastTrigger)
            val width = minOf(f.frame.width, f.dp(320))
            assertTrue("Fixture needs at least 180dp of actual window width", width >= f.dp(180))
            f.frame.layoutParams = f.frame.layoutParams.apply { this.width = width }
        }
        f.settle()
        for (rtl in listOf(false, true)) {
            for (width in listOf(f.dp(180), f.dp(320).coerceAtMost(f.root.width))) {
                main {
                    f.frame.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    f.frame.layoutParams = f.frame.layoutParams.apply { this.width = width }
                }
                f.settle()
                main {
                    assertSame(row, f.row(0))
                    assertSame(action, row.descendants().filterIsInstance<NativeMaterialButtonView>().single())
                    assertTrue(action.isShown)
                    assertTrue(row.width <= width)
                    assertEquals("Full status\nAgent: test", row.text("Full status\nAgent: test").text.toString())
                }
            }
        }
        main {
            f.records[0] = f.records[0].copy(preview = "ok", status = "Done", details = "metadata",
                detailsWhenStatusClipped = "Done\nmetadata", output = "")
            f.bindVisible()
        }
        f.settle()
        main {
            assertFalse(action.isShown)
            assertEquals("metadata", row.text("metadata").text.toString())
            assertFalse(row.descendants().filterIsInstance<NativeText>().any {
                it.isShown && it.text.toString() == "Done\nmetadata"
            })
        }
    }

    @Test fun collapseRecycleAndDetachReleaseDisclosureAndRejectRetiredActions() = withFixture(1) { f ->
        main {
            f.records[0] = f.records[0].copy(expanded = true, fullCommandAvailable = true)
            f.bindVisible(animate = true)
        }
        f.awaitExpansionSettled()
        lateinit var row: ReverbCommandRecordView
        lateinit var oldAction: NativeMaterialButtonView
        main {
            row = f.row(0)
            oldAction = row.descendants().filterIsInstance<NativeMaterialButtonView>().single()
            f.records[0] = f.records[0].copy(expanded = false)
            f.bindVisible(animate = true)
            oldAction.performClick()
            assertTrue(f.fullTextRequests.isEmpty())
        }
        f.awaitExpansionSettled()
        main {
            assertTrue(row.descendants().filterIsInstance<NativeExpansionView>().all { it.content == null })
            f.records[0] = record(expanded = true).copy(fullCommandAvailable = true)
            f.bindVisible()
        }
        f.settle()
        main { f.root.removeView(f.frame) }
        main {
            assertTrue(row.descendants().none { it is NativeExpansionView })
            oldAction.performClick()
            row.header().performClick()
            assertTrue(f.fullTextRequests.isEmpty())
            assertTrue(f.toggleRequests.isEmpty())
            f.root.addView(f.frame, FrameLayout.LayoutParams(-1, -1))
        }
        f.settle()
        main {
            assertEquals("hello\n", f.row(0).text("hello\n").text.toString())
            row = f.row(0)
            row.recycle()
            oldAction.performClick()
            row.header().performClick()
            assertTrue(f.fullTextRequests.isEmpty())
            assertTrue(f.toggleRequests.isEmpty())
            assertTrue(row.descendants().none { it is NativeExpansionView })
            row.bind(record(key = 999), { f.toggleRequests.add(999) }, {})
            row.header().performClick()
            assertEquals(listOf(999L), f.toggleRequests)
        }
    }

    @Test fun hundredRecordsUseOnlyViewportRowsAndKeepAccessibilityScrolling() = withFixture(100) { f ->
        main {
            assertTrue(f.createdRows > 0)
            assertTrue("A bounded viewport must not instantiate all history", f.createdRows < 100)
            assertTrue(f.list.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        }
        val deadline = SystemClock.uptimeMillis() + 5000
        var advanced = false
        while (!advanced && SystemClock.uptimeMillis() < deadline) {
            f.settle()
            main { advanced = f.list.firstVisiblePosition > 0 && f.scrollIdle }
        }
        main {
            assertTrue("Accessibility scroll reaches history", advanced)
            assertTrue(f.createdRows < 100)
            val before = f.list.firstVisiblePosition to f.list.getChildAt(0).top
            f.bindVisible()
            assertEquals(before, f.list.firstVisiblePosition to f.list.getChildAt(0).top)
            assertTrue(f.toggleRequests.isEmpty())
        }
    }

    @Test fun actionAdmissionRechecksHiddenAndReboundOwners() = withFixture(1) { f ->
        main {
            val row = f.row(0)
            f.root.allowed = false
            row.header().performClick()
            f.root.allowed = true
            f.root.probe = { row.bind(record(key = 9), { f.toggleRequests.add(9) }, {}) }
            row.header().performClick()
            assertTrue(f.toggleRequests.isEmpty())
            f.root.probe = { f.frame.visibility = View.GONE }
            row.header().performClick()
            assertTrue(f.toggleRequests.isEmpty())
            f.root.probe = null
            f.frame.visibility = View.VISIBLE
            row.header().performClick()
            assertEquals(listOf(9L), f.toggleRequests)
        }
    }

    @Test fun fullTextBodyDoesNotInterpretOrShortenDisplayContent() {
        val context = instrumentation.targetContext
        val command = "printf 'α\\nשלום'\n".repeat(2000)
        main {
            val body = ReverbCommandFullTextBody(context, ReverbDesign.theme(context, true),
                ReverbCommandFullText("Folder: /a b", command, technical = true))
            val leaves = body.descendants().filterIsInstance<NativeText>().toList()
            assertEquals(listOf("Folder: /a b", command), leaves.map { it.text.toString() })
            assertEquals(View.TEXT_DIRECTION_LTR, leaves.last().textDirection)
            val notice = ReverbCommandFullTextBody(context, ReverbDesign.theme(context, false),
                ReverbCommandFullText(null, "Localized notice", technical = false))
            assertEquals(View.TEXT_DIRECTION_LOCALE, notice.descendants().filterIsInstance<NativeText>().single().textDirection)
        }
    }

    private class Gate(context: Context) : FrameLayout(context), NativeInputGate {
        var allowed = true
        var probe: (() -> Unit)? = null
        override val acceptsNativeInput: Boolean get() { probe?.invoke(); return allowed }
    }

    private class CountedFrame(context: Context, theme: ReverbTheme, list: ReverbCommandTranscriptList) :
        ReverbCommandTranscriptFrame(context, theme, list, "Empty transcript") {
        var requests = 0
        var measures = 0
        var layouts = 0
        fun reset() { requests = 0; measures = 0; layouts = 0 }
        override fun requestLayout() { requests++; super.requestLayout() }
        override fun onMeasure(w: Int, h: Int) { measures++; super.onMeasure(w, h) }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            layouts++; super.onLayout(changed, l, t, r, b)
        }
    }

    private inner class Fixture(val activity: Activity, count: Int) {
        val theme = ReverbDesign.theme(activity, false)
        val records = MutableList(count) { record(it.toLong() + 1) }
        val list = ReverbCommandTranscriptList(activity)
        val frame = CountedFrame(activity, theme, list)
        val root = Gate(activity)
        var createdRows = 0
        var scrollIdle = true
        val toggleRequests = mutableListOf<Long>()
        val fullTextRequests = mutableListOf<Long>()
        var lastTrigger: View? = null
        init {
            list.isSaveEnabled = false
            list.setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, state: Int) {
                    scrollIdle = state == AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                }
                override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) = Unit
            })
            list.setRecyclerListener { (it as? ReverbCommandRecordView)?.recycle() }
            list.adapter = object : BaseAdapter() {
                override fun getCount() = records.size
                override fun getItem(position: Int) = records[position]
                override fun getItemId(position: Int) = records[position].key
                override fun hasStableIds() = true
                override fun areAllItemsEnabled() = false
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = convertView as? ReverbCommandRecordView ?: ReverbCommandRecordView(activity, theme, frame, labels)
                        .also { createdRows++ }
                    row.setGroupEdges(position == 0, position == records.lastIndex)
                    bind(row, position, false)
                    return row
                }
            }
            frame.showEmpty(false)
            root.background = ReverbDesign.windowBackdrop(theme.palette)
            root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        }
        fun dp(value: Int) = (value * theme.density.density).roundToInt()
        fun row(index: Int) = list.getChildAt(index) as ReverbCommandRecordView
        private fun bind(row: ReverbCommandRecordView, position: Int, animate: Boolean) {
            val value = records[position]
            row.bind(value, { toggleRequests.add(value.key) }, {
                lastTrigger = it; fullTextRequests.add(value.key)
            }, animate)
        }
        fun bindVisible(animate: Boolean = false) {
            for (index in 0 until list.childCount) bind(row(index), list.firstVisiblePosition + index, animate)
        }
        fun settle() { repeat(3) { awaitDraw(root) } }
        fun awaitExpansionSettled() {
            val deadline = SystemClock.uptimeMillis() + 5000
            var settled = false
            while (!settled && SystemClock.uptimeMillis() < deadline) {
                settle()
                main { settled = !row(0).expansionResizing() }
            }
            assertTrue("Expansion must settle in the attached owner", settled)
        }
    }

    private fun withFixture(count: Int, block: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var fixture: Fixture? = null
        try {
            main { fixture = Fixture(activity, count).also { activity.setContentView(it.root) } }
            val f = requireNotNull(fixture)
            f.settle()
            main { assertTrue(f.frame.hasWindowFocus()); assertTrue(f.list.childCount > 0) }
            block(f)
        } finally { main { fixture?.root?.removeAllViews(); activity.finish() } }
    }

    private fun awaitDraw(view: View) {
        val fence = CountDownLatch(1)
        var posted = false
        val listener = ViewTreeObserver.OnDrawListener {
            if (!posted) { posted = true; view.post { fence.countDown() } }
        }
        main { view.viewTreeObserver.addOnDrawListener(listener); view.postInvalidateOnAnimation() }
        try { assertTrue("Attached hierarchy must draw", fence.await(5, TimeUnit.SECONDS)) }
        finally { main { view.viewTreeObserver.removeOnDrawListener(listener) } }
    }

    private fun ViewGroup.descendants(): Sequence<View> = sequence {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            yield(child)
            if (child is ViewGroup) yieldAll(child.descendants())
        }
    }
    private fun ViewGroup.header() = descendants().filterIsInstance<NativeSettingsRow>().single()
    private fun ViewGroup.text(value: String) = descendants().filterIsInstance<NativeText>()
        .single { it.text.toString() == value }
    private fun ViewGroup.geometry() = descendants().map { child ->
        Rect(0, 0, child.width, child.height).also { offsetDescendantRectToMyCoords(child, it) }
    }.toList()
}
