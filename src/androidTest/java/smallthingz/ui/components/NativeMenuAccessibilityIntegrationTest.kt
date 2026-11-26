package smallthingz.ui.components

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import smallthingz.ui.util.closePreservingPrimaryFailure

/** Attached Android nodes/event requests; does not claim accessibility-service delivery. */
@RunWith(AndroidJUnit4::class)
class NativeMenuAccessibilityIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val colors = NativeMenuColors(-1, -1, 0xff202020.toInt(), 0xff606060.toInt(), 0xff305080.toInt())
    private val icons = object : NativeMenuIcons<Int> {
        override val check = 0
        override fun create(context: Context, icon: Int, color: Int) = View(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
        }
        override fun bind(view: View, icon: Int, color: Int) = Unit
    }
    private fun column(context: Context): NativeMenuColumn<Int> {
        return NativeMenuColumn(context, TextStyle.Default, Density(context.resources.displayMetrics.density),
            createFontFamilyResolver(context), icons) { false }
    }
    private fun withAttachedColumn(action: (NativeMenuColumn<Int>) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val attached = CountDownLatch(1)
        val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { attached.countDown() }
            override fun onViewDetachedFromWindow(view: View) = Unit
        }
        var ownedColumn: NativeMenuColumn<Int>? = null
        var failure: Throwable? = null
        var primaryFailed = false
        try {
            onMain {
                ownedColumn = column(activity).also { it.addOnAttachStateChangeListener(attachment) }
                activity.setContentView(requireNotNull(ownedColumn))
            }
            check(attached.await(5, TimeUnit.SECONDS)) { "Owned menu column did not attach" }
            onMain {
                val attached = requireNotNull(ownedColumn)
                check(attached.isAttachedToWindow)
                action(attached)
            }
        } catch (error: Throwable) {
            failure = error
            primaryFailed = true
            throw error
        } finally {
            try {
                onMain {
                    failure = closePreservingPrimaryFailure(failure) {
                        ownedColumn?.removeOnAttachStateChangeListener(attachment)
                    }
                    failure = closePreservingPrimaryFailure(failure) { ownedColumn?.close() }
                    failure = closePreservingPrimaryFailure(failure) { activity.finish() }
                }
            } catch (error: Throwable) {
                failure = closePreservingPrimaryFailure(failure) { throw error }
            }
            if (!primaryFailed) failure?.let { throw it }
        }
    }

    private fun onMain(block: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }

    private fun assertNode(row: NativeMenuRowView<Int>, selected: Boolean, enabled: Boolean, label: String) {
        val node = requireNotNull(row.createAccessibilityNodeInfo())
        val text = requireNotNull(row.getChildAt(0).createAccessibilityNodeInfo())
        try {
            assertEquals(selected, node.isSelected)
            assertEquals(enabled, node.isEnabled)
            assertEquals(View::class.java.name, node.className.toString())
            assertEquals(label, text.text.toString())
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, row.getChildAt(1).importantForAccessibility)
        } finally { node.recycle(); text.recycle() }
    }

    @Test fun duplicateLabelsKeepTypedSelectionAndClickIdentity() = withAttachedColumn { column ->
        data class Choice(val id: Int, val label: String)
        val first = Choice(1, "Same label")
        val second = Choice(2, "Same label")
        var selected: Choice? = null
        column.bind(NativeMenuChoices(second, listOf(first, second), Choice::label), colors) { selected = it }
        assertNode(column.rows[0], false, true, "Same label")
        assertNode(column.rows[1], true, true, "Same label")
        assertTrue(column.rows[1].performClick())
        assertSame(second, selected)
        assertTrue(column.rows[0].performClick())
        assertSame(first, selected)
        // A request does not change the owner's bound choice.
        assertNode(column.rows[1], true, true, "Same label")
    }

    @Test fun reboundSelectionAndActionReusePublishOnlyChangedSelection() = withAttachedColumn { column ->
        column.bind(NativeMenuChoices(2, listOf(1, 2)) { "Choice $it" }, colors) {}
        val row = column.rows[0]
        val requests = mutableListOf<Pair<Int, Boolean>>()
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View, eventType: Int) {
                val node = requireNotNull(host.createAccessibilityNodeInfo())
                try { requests += eventType to node.isSelected }
                finally { node.recycle() }
            }
        }
        column.bind(NativeMenuChoices(1, listOf(1, 2)) { "Choice $it" }, colors) {}
        assertSame(row, column.rows[0])
        assertNode(row, true, true, "Choice 1")
        assertFalse(row.isSelected) // Semantic selection does not alter drawable/pressed state.
        assertEquals(listOf(AccessibilityEvent.TYPE_VIEW_SELECTED to true), requests)
        requests.clear()
        column.bind(NativeMenuChoices(1, listOf(1, 2)) { "Choice $it" }, colors) {}
        assertTrue(requests.isEmpty())
        column.bind(NativeMenuChoices(2, listOf(1, 2)) { "Choice $it" }, colors) {}
        assertNode(row, false, true, "Choice 1")
        assertEquals(listOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED to false), requests)
        column.bind(NativeMenuChoices(1, listOf(1, 2)) { "Choice $it" }, colors) {}
        requests.clear()
        column.bindActions(listOf(ResolvedMenuAction("Disabled action", 7, colors.ink, colors.primary, false) {
            error("Disabled action executed")
        }), colors)
        assertSame(row, column.rows.single())
        assertNode(row, false, false, "Disabled action")
        assertFalse(row.performClick())
        assertTrue(requests.contains(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED to false))
        requests.clear()
        column.bindActions(listOf(ResolvedMenuAction("Enabled action", 7, colors.ink, colors.primary) {}), colors)
        assertNode(row, false, true, "Enabled action")
        assertFalse(requests.any { it.first == AccessibilityEvent.TYPE_VIEW_SELECTED })
        column.bind(NativeMenuChoices(1, listOf(1)) { "Choice $it" }, colors) {}
        assertSame(row, column.rows.single())
        assertNode(row, true, true, "Choice 1")
    }
}

/** Declared only in the test APK; owns the window needed for framework node initialization. */
class NativeMenuAccessibilityTestActivity : Activity()
