package app.smallthingz.dropdownviewportqa

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
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
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeDropdown
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.text.view.ParagraphView
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One normal-screen/middle-field/40-option case. Root owns execution and visual review. */
@RunWith(AndroidJUnit4::class)
class DropdownLifecycleVisualTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun backBackgroundRecreationAndLightDarkWindowReceipts() {
        val app = instrumentation.targetContext.applicationContext as Application
        assertEquals("app.smallthingz.dropdownviewportqa", app.packageName)
        val directory = File(app.filesDir, "dropdown-viewport-qa/lifecycle-visual/${UUID.randomUUID()}")
        check(directory.mkdirs())
        val steps = JSONArray()
        val screenshots = JSONArray()
        val receipt = JSONObject().put("status", "running").put("steps", steps).put("screenshots", screenshots)
            .put("package", app.packageName).put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL)
            .put("scenario", "normal screen, middle field, 40 options, system text, LTR")
            .put("visual_review", "pending root review; PNG creation is not an appearance assertion")
        fun save() = File(directory, "receipt.json").writeText(receipt.toString(2))
        fun passed(step: String) { steps.put(step); save() }
        val ledger = LifecycleLedger()
        save()
        println("DROPDOWN_LIFECYCLE_VISUAL_RECEIPT=${directory.absolutePath}")
        onMain { app.registerActivityLifecycleCallbacks(ledger) }
        try {
            val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            await("Initial Activity did not resume") { ledger.resumed === activity && ready(activity) }
            click(activity, "Try this dropdown")
            awaitPreview(activity, "Choice 21 of 40")
            val originalField = onMain { field(activity) }
            open(activity)
            screenshots.put(capture(activity, directory, "light-open.png")); save()

            back(activity)
            awaitPreview(activity, "Choice 21 of 40")
            onMain { assertSame(originalField, field(activity)) }
            passed("Actual Back key closed only the list; original preview field survived")

            open(activity)
            onMain {
                requireReady(activity)
                assertTrue("QA task did not move to background", activity.moveTaskToBack(true))
            }
            await("Background did not stop the QA Activity and retire its list") {
                ledger.stopped === activity && ledger.resumed !== activity && pane(activity) == null
            }
            // Bring only this isolated QA task forward; do not send HOME or start another app.
            instrumentation.targetContext.startActivity(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            await("Same QA Activity did not resume") { ledger.resumed === activity && ready(activity) }
            awaitPreview(activity, "Choice 21 of 40")
            onMain { assertSame(originalField, field(activity)) }
            open(activity)
            advance(activity, "Choice 22 of 40")
            passed("Background/stop retired the list; same Activity/field resumed, reopened and published a new choice")

            // Recreate while open, after changing selection: stale modal must not survive state restore.
            onMain { requireReady(activity); activity.recreate() }
            await("Recreation did not destroy old owner and resume a replacement") {
                activity.isDestroyed && ledger.resumed?.let { it !== activity && ready(it) } == true
            }
            val replacement = onMain { checkNotNull(ledger.resumed) }
            awaitPreview(replacement, "Choice 22 of 40")
            onMain { assertNotSame(originalField, field(replacement)) }
            open(replacement)
            advance(replacement, "Choice 23 of 40")
            back(replacement)
            awaitPreview(replacement, "Choice 23 of 40")
            passed("Recreation replaced Activity/field, restored selection without modal, and new owner published another choice")

            click(replacement, "Change scenario")
            click(replacement, "Theme: Light")
            onMain { assertTrue(hasText(replacement, "Theme: Dark")) }
            click(replacement, "Try this dropdown")
            awaitPreview(replacement, "Choice 21 of 40")
            open(replacement)
            screenshots.put(capture(replacement, directory, "dark-open.png")); save()
            back(replacement)
            awaitPreview(replacement, "Choice 21 of 40")
            passed("Dark scenario opened and actual Back retained its preview; light/dark window PNGs saved")
            receipt.put("status", "passed"); save()
        } catch (failure: Throwable) {
            receipt.put("status", "failed").put("failure", failure.toString())
            try { save() } catch (writeFailure: Throwable) { failure.addSuppressed(writeFailure) }
            throw failure
        } finally {
            onMain {
                try { ledger.created.forEach { if (!it.isDestroyed) it.finish() } }
                finally { app.unregisterActivityLifecycleCallbacks(ledger) }
            }
        }
    }

    private fun back(activity: MainActivity) {
        onMain { requireReady(activity); assertNotNull("No list for Back to dismiss", pane(activity)) }
        // Instrumentation delivers DOWN/UP through the focused window. Never call onBackPressed().
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }

    private fun open(activity: MainActivity) {
        await("Field was not ready to open") {
            ready(activity) && pane(activity) == null && !activity.window.decorView.isLayoutRequested &&
                field(activity).let { it.isShown && it.width > 0 && it.height > 0 && !it.isLayoutRequested }
        }
        onMain {
            requireReady(activity)
            val anchor = field(activity)
            val origin = IntArray(2).also(activity.window.decorView::getLocationOnScreen)
            val point = IntArray(2).also(anchor::getLocationOnScreen)
            val x = point[0] - origin[0] + anchor.width / 2f
            val y = point[1] - origin[1] + anchor.height / 2f
            val down = SystemClock.uptimeMillis()
            for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(activity.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
        }
        await("Dropdown did not fully expand") {
            ready(activity) && pane(activity)?.getChildAt(0)?.alpha == NativeUiAppearance.Default.anchoredDropdown.scrimOpacity
        }
    }

    private fun advance(activity: MainActivity, expected: String) {
        onMain {
            requireReady(activity)
            assertTrue(checkNotNull(pane(activity)).getChildAt(1)
                .performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        }
        await("Reopened owner did not publish $expected") { ready(activity) && hasText(activity, "Selected: $expected") }
    }

    private fun awaitPreview(activity: MainActivity, expected: String) = await("Preview/selection did not survive: $expected") {
        ready(activity) && pane(activity) == null && hasText(activity, "Try the list") &&
            hasText(activity, "Change scenario") && hasText(activity, "Selected: $expected") &&
            descendants(activity.window.decorView).filterIsInstance<NativeDropdown<*>>().count() == 1
    }

    private fun click(activity: MainActivity, label: String) {
        onMain {
            requireReady(activity)
            val button = descendants(activity.window.decorView).filterIsInstance<NativeMaterialButtonView>().single {
                descendants(it).filterIsInstance<ParagraphView>().any { text -> text.getText().toString() == label }
            }
            assertTrue(button.performClick())
        }
        instrumentation.waitForIdleSync()
    }

    /** Capture this QA Window's actual hardware buffer, never a software View.draw or full-display capture. */
    private fun capture(activity: MainActivity, directory: File, name: String): JSONObject {
        val committed = CountDownLatch(1)
        val commit = Runnable { committed.countDown() }
        val observer = onMain {
            requireReady(activity)
            val root = activity.window.decorView
            check(root.isHardwareAccelerated)
            root.viewTreeObserver.also { it.registerFrameCommitCallback(commit); root.invalidate() }
        }
        try { check(committed.await(5, TimeUnit.SECONDS)) { "QA window frame did not commit" } }
        finally { onMain { if (observer.isAlive) observer.unregisterFrameCommitCallback(commit) } }
        val bounds = onMain {
            requireReady(activity)
            assertNotNull(pane(activity))
            Rect(0, 0, activity.window.decorView.width, activity.window.decorView.height)
        }
        val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var status = Int.MIN_VALUE
        var submitted = false
        var recycleLater = false // Accessed only on main, including the PixelCopy callback.
        var bitmapOwned = true
        try {
            onMain {
                requireReady(activity)
                PixelCopy.request(activity.window, bounds, bitmap, { code ->
                    status = code; done.countDown()
                    if (recycleLater) bitmap.recycle()
                }, Handler(Looper.getMainLooper()))
                submitted = true
            }
            check(done.await(5, TimeUnit.SECONDS)) { "QA window PixelCopy timed out" }
            check(status == PixelCopy.SUCCESS) { "QA window PixelCopy failed: $status" }
            onMain { requireReady(activity); assertNotNull(pane(activity)) }
            val file = File(directory, name)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return onMain {
                JSONObject().put("file", name).put("width", bitmap.width).put("height", bitmap.height)
                    .put("capture", "Window PixelCopy after frame commit").put("pixel_copy_status", status)
                    .put("activity_identity", System.identityHashCode(activity)).put("task_id", activity.taskId)
                    .put("density_dpi", activity.resources.configuration.densityDpi)
                    .put("font_scale", activity.resources.configuration.fontScale.toDouble())
            }
        } finally {
            // Timeout does not cancel PixelCopy: its destination must survive until callback completion.
            onMain {
                if (submitted && done.count != 0L) { recycleLater = true; bitmapOwned = false }
            }
            if (bitmapOwned) bitmap.recycle()
        }
    }

    private fun ready(activity: MainActivity) = !activity.isFinishing && !activity.isDestroyed &&
        activity.hasWindowFocus() && activity.window.decorView.let { it.isAttachedToWindow && it.isShown && it.width > 0 && it.height > 0 }

    private fun requireReady(activity: MainActivity) { check(ready(activity)) { "QA Activity no longer owns a visible focused window" } }
    private fun field(activity: MainActivity) = descendants(activity.window.decorView).filterIsInstance<NativeDropdown<*>>().single()
    private fun pane(activity: MainActivity) = descendants(activity.window.decorView).filterIsInstance<FrameLayout>()
        .firstOrNull { it.accessibilityPaneTitle == "Choose an option" }
    private fun hasText(activity: MainActivity, text: String) = descendants(activity.window.decorView)
        .filterIsInstance<ParagraphView>().any { it.getText().toString() == text }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(predicate)) return
            SystemClock.sleep(16)
        }
        fail(message)
    }

    /** All observations and reads are on main. The bounded two-Activity fixture owns these references. */
    private class LifecycleLedger : Application.ActivityLifecycleCallbacks {
        val created = ArrayList<MainActivity>()
        var resumed: MainActivity? = null
        var stopped: MainActivity? = null
        override fun onActivityCreated(activity: Activity, state: Bundle?) { if (activity is MainActivity) created += activity }
        override fun onActivityResumed(activity: Activity) { if (activity is MainActivity) resumed = activity }
        override fun onActivityPaused(activity: Activity) { if (resumed === activity) resumed = null }
        override fun onActivityStopped(activity: Activity) { if (activity is MainActivity) stopped = activity }
        override fun onActivityDestroyed(activity: Activity) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }
}
