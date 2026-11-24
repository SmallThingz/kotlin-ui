package app.smallthingz.dropdowndemo

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeDropdown
import smallthingz.ui.text.view.ParagraphView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real demo Activity and input. Only the exact guarded private Activity window is copied. */
@RunWith(AndroidJUnit4::class)
class EmailDemoPrivateCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private val decor get() = activity.window.decorView as ViewGroup

    @Test
    fun exerciseCommonAndEmail() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("visualCapture") == "true")
        check(args.getString("visualDisplayIsolation") == "private-own-focus")
        check(args.getString("visualFixtureScope") == "synthetic-email-library-demo")
        check(args.getString("visualSourceRevision")?.matches(Regex("[0-9a-f]{40}")) == true)
        val context = instrumentation.targetContext
        check(context.packageName == "app.smallthingz.dropdowndemo.emailvisual")
        DemoPrivateDisplay.runOwned {
            activity = DemoPrivateDisplay.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val (common, commonSlot) = open("Sample format")
            val commonInitial = onMain { option(rows(common).single { it.isSelected }) }
            val commonNext = browse(common, commonSlot, commonInitial)
            cancel(common)
            check(onMain { hasValue(field("Sample format"), commonNext) })
            val (sort, sortSlot) = open("Message sort")
            val original = "Newest first"
            check(onMain { option(rows(sort).single { it.isSelected }) == original })
            capture("email-demo-sort-open")
            dragAcrossAndReturn(sort)
            check(onMain { sort.isAttachedToWindow && option(rows(sort).single { it.isSelected }) == original })
            val next = browse(sort, sortSlot, original)
            cancel(sort)
            check(onMain { hasValue(field("Message sort"), original) })
            val (again, slot) = open("Message sort")
            check(browse(again, slot, original) == next)
            tapRow(again, next)
            check(onMain { hasValue(field("Message sort"), next) })

            val (archive, _) = open("Archive folder", missing = true)
            check(onMain { rows(archive).map(::option) == listOf("Archive") && rows(archive).none { it.isSelected } })
            capture("email-demo-archive-recovery")
            cancel(archive)
            check(onMain { hasValue(field("Archive folder"), "Old archive") })
            tapRow(open("Archive folder", missing = true).first, "Archive")
            await("read-only recovered folder") { views(decor).any { it.isShown && text(it) == "Archive folder: Archive" }.takeIf { it } }
            val heading = onMain { views(decor).single { it.isShown && text(it) == "Email" } }
            onMain { heading.requestRectangleOnScreen(Rect(0, 0, heading.width, heading.height), true) }
            check(onMain { listOf("team@example.test", "Team update").all { value -> views(decor).any { text(it) == value } } })
            capture("email-demo-section")
        }
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
        onMain { anchor.requestRectangleOnScreen(Rect(0, 0, anchor.width, anchor.height), true) }
        val slot = await("fully visible " + label) {
            rect(anchor).takeIf { area -> Rect().also { anchor.getGlobalVisibleRect(it) } == area }
        }
        onMain { DemoPrivateDisplay.beforeLocalInput(activity, decor); check(anchor.performClick()) }
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
            DemoPrivateDisplay.beforeLocalInput(activity, decor)
            val actions = checkNotNull(wheel.createAccessibilityNodeInfo()).actionList.map { it.id }
            val action = listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD).first { it in actions }
            check(wheel.performAccessibilityAction(action, null))
        }
        return await("next centred wheel value") { rows(wheel).singleOrNull { rect(it) == slot }?.let(::option)?.takeUnless { it == current } }
    }
    private fun cancel(wheel: ViewGroup) {
        onMain {
            DemoPrivateDisplay.beforeLocalInput(activity, decor)
            decor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            check(decor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        }
        await("wheel dismissed") { true.takeIf { !wheel.isAttachedToWindow && decor.hasWindowFocus() } }
    }
    private fun dragAcrossAndReturn(wheel: ViewGroup) {
        onMain {
            DemoPrivateDisplay.beforeLocalInput(activity, decor)
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
            DemoPrivateDisplay.beforeLocalInput(activity, decor)
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

    private fun capture(name: String) {
        fun state() = onMain { checkNotNull(DemoPrivateDisplay.snapshot(activity, decor)) }
        val before = state()
        val initial = JSONObject(before)
        fun copy(): Bitmap {
            val bitmap = Bitmap.createBitmap(initial.getInt("decorWidth"), initial.getInt("decorHeight"), Bitmap.Config.ARGB_8888)
            val done = CountDownLatch(1)
            var result = -1
            PixelCopy.request(activity.window, bitmap, { code -> result = code; done.countDown() }, Handler(Looper.getMainLooper()))
            check(done.await(3, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS)
            check(state() == before)
            return bitmap
        }
        var frame = copy()
        val deadline = SystemClock.uptimeMillis() + 6_000
        try {
            var stable = 0
            while (stable < 2) {
                check(SystemClock.uptimeMillis() < deadline) { "Demo frame did not settle" }
                SystemClock.sleep(120)
                val next = copy()
                stable = if (next.sameAs(frame)) stable + 1 else 0
                frame.recycle()
                frame = next
            }
            val directory = File(activity.filesDir, "visual-captures").apply { mkdirs() }
            val stem = name + "-" + SystemClock.uptimeMillis()
            val png = File(directory, stem + ".png")
            png.outputStream().use { check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val sha = MessageDigest.getInstance("SHA-256").digest(png.readBytes()).joinToString("") { "%02x".format(it) }
            val metadata = File(directory, stem + ".json").apply {
                writeText(JSONObject().put("fixture", name).put("png", png.name).put("pngSha256", sha)
                    .put("sourceRevision", InstrumentationRegistry.getArguments().getString("visualSourceRevision"))
                    .put("ownedDisplay", initial).put("captureMethod", "PixelCopy.Window").put("widthPx", frame.width)
                    .put("heightPx", frame.height).put("fontScale", activity.resources.configuration.fontScale).toString())
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("visualCapturePng", png.absolutePath); putString("visualCaptureMetadata", metadata.absolutePath)
            })
        } finally { frame.recycle() }
    }
}
