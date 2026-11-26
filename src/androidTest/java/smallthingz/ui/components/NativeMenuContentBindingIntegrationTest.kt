package smallthingz.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import smallthingz.ui.util.closePreservingPrimaryFailure
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual attached rows, framework accessibility nodes and input; no accessibility-service claim. */
@RunWith(AndroidJUnit4::class)
class NativeMenuContentBindingIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val colors = NativeMenuColors(-1, -1, 0xff202020.toInt(), 0xff606060.toInt(), 0xff305080.toInt())
    private val typography = ReverbDesign.appearance.typography
    private val detailStyle get() = typography[NativeTypographyRole.BODY_SMALL]

    @Test fun iconlessInformationBoundBeforeAttachExposesBothLabelsWithoutAnAction() = withFixture { f ->
        // Binding before attachment also exercises the information-only onAttachedToWindow path.
        val row = f.row {
            it.bindContent("Connection", "Encrypted with TLS", detailStyle, null, true, colors, null)
        }
        layout(row)
        assertTrue(row.isAttachedToWindow)
        assertFalse(row.isClickable)
        assertFalse(row.isFocusable)
        assertFalse(row.performClick())
        assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        node(row) {
            assertFalse(it.isClickable)
            assertFalse(it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK })
        }
        val labels = labels(row)
        assertEquals(listOf("Connection", "Encrypted with TLS"), labels.map(::nodeText))
        assertTrue("Information title must be important to Android accessibility", labels[0].isImportantForAccessibility)
        assertTrue("Information detail must be important to Android accessibility", labels[1].isImportantForAccessibility)
        val exposed = arrayListOf<View>().also(row::addChildrenForAccessibility)
        assertTrue("Framework child enumeration must expose the title", exposed.contains(labels[0]))
        assertTrue("Framework child enumeration must expose the detail", exposed.contains(labels[1]))
        assertEquals(View.GONE, glyph(row).visibility)
        assertEquals("Iconless information must not reserve a leading icon slot", 16, labels[0].left)
    }

    @Test fun disabledCustomActionCannotDispatchAndEnabledReuseUsesOnlyTheNewCallback() = withFixture { f ->
        var disabledCalls = 0
        var enabledCalls = 0
        val row = f.row {
            it.bindContent("Open", "Unavailable", detailStyle, 7, false, colors) { disabledCalls++ }
        }
        layout(row)
        node(row) { assertFalse(it.isEnabled) }
        assertFalse(row.performClick())
        assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        tap(row)
        assertEquals(0, disabledCalls)
        assertEquals("Disabled content keeps the supplied ordinary icon ink", colors.ink, glyph(row).color)

        row.bindContent("Open", "Available", detailStyle, 7, true, colors) { enabledCalls++ }
        layout(row)
        node(row) {
            assertTrue(it.isEnabled)
            assertTrue(it.isClickable)
            assertEquals("Open, Available", it.contentDescription.toString())
        }
        assertEquals(listOf("Open", "Available"), labels(row).map(::nodeText))
        tap(row)
        assertEquals(1, enabledCalls)
        assertTrue(row.performClick())
        assertEquals(2, enabledCalls)
        assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(3, enabledCalls)
        assertEquals(0, disabledCalls)
        row.close()
        assertFalse(row.performClick())
        tap(row)
        assertEquals(3, enabledCalls)
        assertEquals(0, disabledCalls)
    }

    @Test fun optionalDetailAddsMeasuredHeightAndMirrorsTheCanonicalTextColumnInRtl() = withFixture { f ->
        val title = "A long menu title that must wrap across several lines in this narrow menu"
        val detail = "Supporting detail stays available to accessibility even when visually ellipsized"
        for (icon in listOf<Int?>(null, 7)) {
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                val plain = f.row()
                if (icon == null) plain.bind(title, false, colors) {}
                else plain.bindAction(NativeMenuAction(title, icon, colors.ink, colors.ink) {}, colors)
                val rich = f.row {
                    it.bindContent(title, detail, detailStyle, icon, true, colors) {}
                }
                plain.layoutDirection = direction
                rich.layoutDirection = direction
                layout(plain, 140)
                layout(rich, 140)
                val titleView = labels(rich)[0]
                val detailView = labels(rich)[1]
                val plainTitle = labels(plain).single()
                assertTrue("Fixture must exceed the minimum row height", plainTitle.measuredHeight > 48)
                assertEquals(plainTitle.measuredWidth, titleView.measuredWidth)
                assertEquals(plainTitle.measuredHeight, titleView.measuredHeight)
                assertEquals("Detail must participate in measurement", plain.height + detailView.height, rich.height)
                assertEquals(titleView.bottom, detailView.top)
                assertTrue(titleView.top >= 0 && detailView.bottom <= rich.height)
                val start = if (icon == null) 16 else 12 + 24 + 12
                if (direction == View.LAYOUT_DIRECTION_RTL) {
                    assertEquals(start, rich.width - titleView.right)
                    assertEquals(titleView.right, detailView.right)
                } else {
                    assertEquals(start, titleView.left)
                    assertEquals(titleView.left, detailView.left)
                }
                assertEquals(detail, nodeText(detailView))
                node(rich) { assertEquals("$title, $detail", it.contentDescription.toString()) }
                if (icon == null) assertEquals(View.GONE, glyph(rich).visibility)
                else {
                    assertEquals(24, glyph(rich).width)
                    assertEquals(24, glyph(rich).height)
                    val inset = if (direction == View.LAYOUT_DIRECTION_RTL) rich.width - glyph(rich).right else glyph(rich).left
                    assertEquals(12, inset)
                }
                rich.bindContent(title, null, detailStyle, icon, true, colors) {}
                layout(rich, 140)
                assertEquals("Removing detail restores the original row geometry", plain.height, rich.height)
                assertEquals(1, labels(rich).size)
                assertEquals(bounds(plainTitle), bounds(labels(rich).single()))
                node(rich) { assertEquals(title, it.contentDescription.toString()) }
            }
        }
    }

    @Test fun choiceBindingAfterCustomContentRestoresFreshRowAccessibilitySelectionAndGeometry() = withFixture { f ->
        var staleCalls = 0
        var choiceCalls = 0
        val reused = f.row {
            it.bindContent("Old action", "A very wide obsolete detail that must not affect intrinsic width", detailStyle,
                7, false, colors) { staleCalls++ }
        }
        val fresh = f.row()
        for (selected in listOf(true, false)) {
            reused.bind("Choice", selected, colors) { choiceCalls++ }
            fresh.bind("Choice", selected, colors) {}
            layout(reused)
            layout(fresh)
            assertOriginalBindingMatchesFresh(fresh, reused)
            node(reused) { assertEquals(selected, it.isSelected) }
            assertEquals(if (selected) View.VISIBLE else View.GONE, glyph(reused).visibility)
            assertEquals(colors.primary, glyph(reused).color)
            assertTrue(reused.performClick())
        }
        assertEquals(2, choiceCalls)
        assertEquals(0, staleCalls)
    }

    @Test fun actionBindingAfterInformationRestoresFreshRowAndDoesNotRetainItsDetail() = withFixture { f ->
        var calls = 0
        val reused = f.row {
            it.bindContent("Old information", "A very wide obsolete detail that must not affect intrinsic width",
                detailStyle, null, false, colors, null)
        }
        val fresh = f.row()
        reused.bindAction(NativeMenuAction("Share", 9, colors.ink, colors.primary) { calls++ }, colors)
        fresh.bindAction(NativeMenuAction("Share", 9, colors.ink, colors.primary) {}, colors)
        layout(reused)
        layout(fresh)
        assertOriginalBindingMatchesFresh(fresh, reused)
        assertEquals(9, glyph(reused).icon)
        assertEquals(colors.primary, glyph(reused).color)
        assertEquals(View.VISIBLE, glyph(reused).visibility)
        assertTrue(reused.performClick())
        assertEquals(1, calls)
        reused.bindAction(NativeMenuAction("Share", 9, colors.ink, colors.primary, false) { calls++ }, colors)
        assertFalse(reused.performClick())
        tap(reused)
        assertEquals(1, calls)
    }

    private fun assertOriginalBindingMatchesFresh(fresh: NativeMenuRowView<Int>, reused: NativeMenuRowView<Int>) {
        assertEquals(fresh.isEnabled, reused.isEnabled)
        assertEquals(fresh.isClickable, reused.isClickable)
        assertEquals(fresh.isFocusable, reused.isFocusable)
        assertEquals(fresh.measuredHeight, reused.measuredHeight)
        assertEquals(fresh.maxIntrinsicWidth(), reused.maxIntrinsicWidth())
        assertEquals(1, labels(reused).size)
        val label = labels(reused).single()
        val reference = labels(fresh).single()
        assertEquals(nodeText(reference), nodeText(label))
        assertEquals(bounds(reference), bounds(label))
        assertEquals("Rebinding must not suppress the original label's accessibility importance",
            reference.isImportantForAccessibility, label.isImportantForAccessibility)
        val freshChildren = arrayListOf<View>().also(fresh::addChildrenForAccessibility)
        val reusedChildren = arrayListOf<View>().also(reused::addChildrenForAccessibility)
        assertTrue("Canonical fresh label is exposed by the fixture", freshChildren.contains(reference))
        assertTrue("Rebound label must be exposed just like a fresh label", reusedChildren.contains(label))
        assertEquals(glyph(fresh).visibility, glyph(reused).visibility)
        if (glyph(fresh).visibility == View.VISIBLE) assertEquals(bounds(glyph(fresh)), bounds(glyph(reused)))
        node(fresh) { expected -> node(reused) { actual ->
            assertEquals(expected.className.toString(), actual.className.toString())
            assertEquals(expected.isSelected, actual.isSelected)
            assertEquals(expected.isClickable, actual.isClickable)
            assertEquals(expected.contentDescription?.toString(), actual.contentDescription?.toString())
            assertNull("Custom merged description must be retired", actual.contentDescription)
        } }
    }

    private inner class Fixture(activity: Activity) : AutoCloseable {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        private val resolver = createFontFamilyResolver(activity)
        private val owned = mutableListOf<NativeMenuRowView<Int>>()
        // Explicit density makes 12/16/24/48dp assertions independent of the test display's dpi.
        fun row(bindBeforeAttach: (NativeMenuRowView<Int>) -> Unit = {}): NativeMenuRowView<Int> =
            NativeMenuRowView(root.context, typography.buttonLabelStyle, Density(1f), resolver, colors, icons) { false }.also {
                owned.add(it)
                bindBeforeAttach(it)
                root.addView(it, LinearLayout.LayoutParams(-1, -2))
                check(it.isAttachedToWindow)
            }
        override fun close() {
            var failure: Throwable? = null
            owned.forEach { failure = closePreservingPrimaryFailure(failure, it::close) }
            failure?.let { throw it }
        }
    }

    private fun withFixture(assertions: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val attached = CountDownLatch(1)
        var fixture: Fixture? = null
        var failure: Throwable? = null
        try {
            instrumentation.runOnMainSync {
                fixture = Fixture(activity).also { f ->
                    f.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: View) {
                            view.removeOnAttachStateChangeListener(this)
                            attached.countDown()
                        }
                        override fun onViewDetachedFromWindow(view: View) = Unit
                    })
                    activity.setContentView(f.root)
                }
            }
            assertTrue("Menu test host did not attach", attached.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { assertions(checkNotNull(fixture)) }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val primary = failure
            instrumentation.runOnMainSync {
                failure = closePreservingPrimaryFailure(failure) { fixture?.close() }
                failure = closePreservingPrimaryFailure(failure, activity::finish)
            }
            if (primary == null) failure?.let { throw it }
        }
    }

    private fun layout(row: NativeMenuRowView<Int>, width: Int = 260) {
        row.forceLayout()
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
    }
    private fun labels(row: NativeMenuRowView<Int>): List<ParagraphView> =
        (0 until row.childCount).map(row::getChildAt).filterIsInstance<ParagraphView>().filter { it.visibility == View.VISIBLE }
    private fun glyph(row: NativeMenuRowView<Int>) = (0 until row.childCount).map(row::getChildAt).filterIsInstance<Glyph>().single()
    private fun bounds(view: View) = Rect(view.left, view.top, view.right, view.bottom)
    private fun nodeText(view: View): String {
        var text = ""
        node(view) { text = it.text?.toString().orEmpty() }
        return text
    }
    private fun node(view: View, assertNode: (AccessibilityNodeInfo) -> Unit) {
        val node = checkNotNull(view.createAccessibilityNodeInfo())
        try { assertNode(node) } finally { node.recycle() }
    }
    private fun tap(row: View) {
        val down = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, down + if (action == MotionEvent.ACTION_UP) 1L else 0L,
                action, row.width / 2f, row.height / 2f, 0)
            try { row.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }
    private class Glyph(context: Context, var icon: Int, var color: Int) : View(context) {
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable = false }
    }
    private val icons = object : NativeMenuIcons<Int> {
        override val check = 0
        override fun create(context: Context, icon: Int, color: Int) = Glyph(context, icon, color)
        override fun bind(view: View, icon: Int, color: Int) {
            (view as Glyph).also { it.icon = icon; it.color = color }
        }
    }
}
