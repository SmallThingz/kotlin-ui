package smallthingz.ui.components.dropdown

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeDropdown
import smallthingz.ui.text.view.ParagraphView
import java.util.concurrent.atomic.AtomicReference

/** Choice, label, and composer assertions against a focused synthetic library test window. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class EmailDropdownWindowIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: DropdownInteractionHostActivity
    private val decor get() = activity.window.decorView as ViewGroup

    @Test
    fun commonAndEmailChoicesKeepTheirSelectionAndLabels() {
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.context, DropdownInteractionHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as DropdownInteractionHostActivity
        try {
            val (common, commonSlot) = open("Sample format")
            val commonInitial = onMain { option(rows(common).single { it.isSelected }) }
            val commonNext = browse(common, commonSlot, commonInitial)
            dismiss(common)
            check(onMain { hasValue(field("Sample format"), commonNext) })
            val (sort, sortSlot) = open("Message sort")
            val original = "Newest first"
            check(onMain { option(rows(sort).single { it.isSelected }) == original })
            dragAcrossAndReturn(sort)
            check(onMain { sort.isAttachedToWindow && option(rows(sort).single { it.isSelected }) == original })
            val next = browse(sort, sortSlot, original)
            dismiss(sort, KeyEvent.KEYCODE_ESCAPE)
            check(onMain { hasValue(field("Message sort"), original) })
            val (again, slot) = open("Message sort")
            check(browse(again, slot, original) == next)
            tapRow(again, next)
            check(onMain { hasValue(field("Message sort"), next) })

            val (archive, _) = open("Archive folder", missing = true)
            check(onMain { rows(archive).map(::option) == listOf("Archive") && rows(archive).none { it.isSelected } })
            dismiss(archive)
            check(onMain { hasValue(field("Archive folder"), "Old archive") })
            tapRow(open("Archive folder", missing = true).first, "Archive")
            await("read-only recovered folder") { views(decor).any { it.isShown && text(it) == "Archive folder: Archive" }.takeIf { it } }
            val (from, fromSlot) = open("From")
            await("both sender options fully revealed") {
                true.takeIf { rows(from).size == 2 && rows(from).all { it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES } }
            }
            onMain {
                val senders = rows(from)
                check(senders.map(::option).toSet() == setOf("Personal <one@example.invalid>", "Projects <two@example.invalid>"))
                for (row in senders) {
                    val address = option(row)
                    val label = views(row).filterIsInstance<ParagraphView>().single { text(it) == address }
                    val layout = checkNotNull(label.createOriginalSemanticsParagraph())
                    val last = layout.lineCount - 1
                    check(!layout.didExceedMaxLines && !layout.isLineEllipsized(last) &&
                        layout.getLineEnd(last, visibleEnd = true) == address.length) { "Incomplete sender: $address" }
                    check(layout.height <= label.height + 1f && rect(row).height() == fromSlot.height())
                    val visible = Rect()
                    check(label.getLocalVisibleRect(visible) && visible.contains(0, 0, label.width, label.height))
                }
            }
            dismiss(from)
            check(onMain { listOf("team@example.test", "Team update").all { value -> views(decor).any { text(it) == value } } })
        } finally {
            onMain { activity.finish() }
        }
    }

    private fun requireFocusedWindow() {
        check(!activity.isFinishing && !activity.isDestroyed && decor.isAttachedToWindow && decor.hasWindowFocus())
    }

    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(views(root.getChildAt(i)))
    }
    private fun text(view: View): String? = when (view) {
        is ParagraphView -> view.getText().toString()
        is TextView -> view.text.toString()
        else -> null
    }
    private fun hasValue(field: View, value: String) = views(field).any { text(it) == value }
    private fun field(label: String) = views(decor).filterIsInstance<NativeDropdown<*>>().single {
        it.isShown && views(it).any { child -> child.contentDescription?.toString() == label || text(child) == label }
    }
    private fun rect(view: View): Rect {
        val p = IntArray(2).also(view::getLocationOnScreen)
        return Rect(p[0], p[1], p[0] + view.width, p[1] + view.height)
    }
    private fun rows(wheel: ViewGroup) = (0 until wheel.childCount).map(wheel::getChildAt).filterIsInstance<NativeDropdown<*>>()
    private fun option(row: View) = checkNotNull(row.createAccessibilityNodeInfo()?.contentDescription).toString()

    private fun open(label: String, missing: Boolean = false): Pair<ViewGroup, Rect> {
        val anchor = await(label) { if (!decor.hasWindowFocus()) null else field(label).takeIf { it.isLaidOut } }
        onMain {
            // Keep space below the slot for explicitly selected Email choices.
            anchor.requestRectangleOnScreen(Rect(0, -anchor.height, anchor.width, 3 * anchor.height), true)
        }
        val slot = await("fully visible " + label) {
            rect(anchor).takeIf { area -> Rect().also { anchor.getGlobalVisibleRect(it) } == area }
        }
        onMain { requireFocusedWindow(); check(anchor.performClick()) }
        val wheel = await("same-window " + label) {
            val pane = views(decor).filterIsInstance<ViewGroup>().singleOrNull { it.accessibilityPaneTitle == label }
            val result = pane?.let { views(it).filterIsInstance<ViewGroup>().singleOrNull { v ->
                v.createAccessibilityNodeInfo()?.className == "android.widget.NumberPicker"
            } }
            result?.takeIf { it.windowToken === decor.windowToken && rows(it).any { row ->
                (missing || row.isSelected) && rect(row) == slot
            } }
        }
        return wheel to slot
    }

    private fun browse(wheel: ViewGroup, slot: Rect, current: String): String {
        onMain {
            requireFocusedWindow()
            val actions = checkNotNull(wheel.createAccessibilityNodeInfo()).actionList.map { it.id }
            val action = listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD).first { it in actions }
            check(wheel.performAccessibilityAction(action, null))
        }
        return await("next centred wheel value") { rows(wheel).singleOrNull { rect(it) == slot }?.let(::option)?.takeUnless { it == current } }
    }
    private fun dismiss(wheel: ViewGroup, keyCode: Int = KeyEvent.KEYCODE_BACK) {
        onMain {
            requireFocusedWindow()
            decor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            check(decor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode)))
        }
        await("wheel dismissed") { true.takeIf { !wheel.isAttachedToWindow && decor.hasWindowFocus() } }
    }
    private fun dragAcrossAndReturn(wheel: ViewGroup) {
        onMain {
            requireFocusedWindow()
            val row = rows(wheel).first { !it.isSelected && it.isShown && it.getGlobalVisibleRect(Rect()) }
            val box = rect(row)
            val root = rect(decor)
            val startX = box.exactCenterX() - root.left
            val outsideX = (box.left - root.left - 1).toFloat()
            val y = box.exactCenterY() - root.top
            check(outsideX >= 0 && y >= 0 && y < decor.height)
            val start = SystemClock.uptimeMillis()
            for ((action, x) in listOf(MotionEvent.ACTION_DOWN to startX, MotionEvent.ACTION_MOVE to outsideX,
                MotionEvent.ACTION_MOVE to startX, MotionEvent.ACTION_UP to startX)) {
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
                try { event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; check(decor.dispatchTouchEvent(event)) }
                finally { event.recycle() }
            }
        }
        // A mistaken click would close the overlay after its exit animation.
        SystemClock.sleep(500)
    }

    private fun tapRow(wheel: ViewGroup, value: String) {
        onMain {
            requireFocusedWindow()
            val row = rows(wheel).single { option(it) == value }
            val center = rect(row)
            val origin = rect(decor)
            val x = center.exactCenterX() - origin.left
            val y = center.exactCenterY() - origin.top
            check(x >= 0 && y >= 0 && x < decor.width && y < decor.height)
            val t = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(t, SystemClock.uptimeMillis(), action, x, y, 0)
                try { event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; check(decor.dispatchTouchEvent(event)) }
                finally { event.recycle() }
            }
        }
        await("confirmed option") { true.takeIf { !wheel.isAttachedToWindow && decor.hasWindowFocus() } }
    }

    private fun <T> onMain(action: () -> T): T {
        val value = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { value.set(runCatching(action)) }
        return value.get().getOrThrow()
    }
    private fun <T : Any> await(label: String, find: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + 8_000
        do { onMain(find)?.let { return it }; SystemClock.sleep(40) } while (SystemClock.uptimeMillis() < deadline)
        error("Timed out waiting for " + label)
    }

}
