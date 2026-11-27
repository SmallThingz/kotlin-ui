package smallthingz.ui.components

import android.app.Activity
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.runtime.MonotonicFrameClock

@RunWith(AndroidJUnit4::class)
class NativeProgressSwitchCloseIntegrationTest {
    @Test fun attachedCloseDetachesProgressAndRejectsLateBinds() = withActivity { activity ->
        lateinit var parent: FrameLayout
        lateinit var mark: NativeMaterialSwitchView
        lateinit var control: NativeProgressSwitch
        var changes = 0
        onMain {
            val scheme = NativeColorScheme.fallback(darkTheme = false)
            mark = NativeMaterialSwitchView(activity, scheme)
            control = NativeProgressSwitch(activity, scheme, "Feature enabled", { changes++ }, mark = mark)
            parent = FrameLayout(activity).apply {
                addView(control, FrameLayout.LayoutParams(-2, -2))
            }
            control.bindProgress(checked = true, enabled = true, progress = "Applying")
            activity.setContentView(parent)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        onMain {
            assertTrue(control.isAttachedToWindow && control.isShown)
            val spinner = mark.getChildAt(0) as NativeCircularProgress
            assertTrue(spinner.isAttachedToWindow)
            val activeJob = frameJob(spinner)
            // Motion-disabled devices still exercise attachment/observer retirement.
            (control as AutoCloseable).close()
            assertTrue("Close must not depend on removing the host row", control.isAttachedToWindow)
            assertEquals(0, mark.childCount)
            assertNull(spinner.parent)
            assertFalse(spinner.isAttachedToWindow)
            assertNull(frameJob(spinner))
            activeJob?.let { assertTrue("Existing frame work must be cancelled", it.isCancelled) }
            assertFalse(control.isEnabled)
            assertFalse(control.performClick())
            assertFalse(control.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertNull(control.createAccessibilityNodeInfo())
            val retiredDescription = ViewCompat.getStateDescription(control)

            for (progress in listOf("Late progress", null)) {
                control.bindProgress(checked = false, enabled = true, progress = progress)
                control.close()
                assertEquals(0, mark.childCount)
                assertFalse(control.isEnabled)
                assertEquals(retiredDescription, ViewCompat.getStateDescription(control))
                assertNull(frameJob(spinner))
            }
            parent.removeView(control)
            parent.addView(control, FrameLayout.LayoutParams(-2, -2))
            assertTrue(control.isAttachedToWindow)
            control.bindProgress(checked = true, enabled = true, progress = "After reattach")
            assertSame(parent, control.parent)
            assertEquals(0, mark.childCount)
            assertFalse(spinner.isAttachedToWindow)
            assertFalse(control.performClick())
            assertEquals(0, changes)
        }
    }

    @Test fun unusedCloseAndLateBindDoNotCreateProgress() = onMain {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scheme = NativeColorScheme.fallback(darkTheme = false)
        val mark = NativeMaterialSwitchView(context, scheme)
        val control = NativeProgressSwitch(context, scheme, "Feature enabled", {}, mark = mark)
        val lazySpinner = NativeProgressSwitch::class.java.getDeclaredField("spinner\$delegate")
            .apply { isAccessible = true }.get(control) as Lazy<*>
        try {
            assertFalse(lazySpinner.isInitialized())
            control.close()
            control.close()
            control.bindProgress(checked = true, enabled = true, progress = "Too late")
            assertFalse("Terminal close/bind must not allocate the lazy spinner", lazySpinner.isInitialized())
            assertEquals(0, mark.childCount)
            assertFalse(control.isEnabled)
            assertNull(ViewCompat.getStateDescription(control))
        } finally {
            control.close()
        }
    }

    @Test fun detachFailureStillRetiresInputAndLateBinds() = withActivity { activity ->
        lateinit var mark: NativeMaterialSwitchView
        lateinit var control: NativeProgressSwitch
        lateinit var scope: CoroutineScope
        val detachFailure = IllegalStateException("Injected detach failure")
        var detachCalls = 0
        var changes = 0
        onMain {
            val scheme = NativeColorScheme.fallback(darkTheme = false)
            scope = CoroutineScope(Dispatchers.Main.immediate)
            mark = NativeMaterialSwitchView(activity, scheme)
            control = NativeProgressSwitch(activity, scheme, "Feature enabled", { changes++ }, mark = mark)
            control.bindProgress(checked = true, enabled = true, progress = null)
            val clock = object : MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
                    error("Zero-scale injected progress must not request frames")
            }
            mark.setThumbProgress(object : NativeCircularProgress(activity, scheme.ink, clock, scope, { 0f }) {
                override fun onDetachedFromWindow() {
                    super.onDetachedFromWindow()
                    if (++detachCalls == 1) throw detachFailure
                }
            })
            activity.setContentView(FrameLayout(activity).apply { addView(control) })
        }
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onMain {
                assertTrue(control.isAttachedToWindow)
                assertTrue(mark.getChildAt(0).isAttachedToWindow)
                assertSame(detachFailure, runCatching { control.close() }.exceptionOrNull())
                assertEquals(1, detachCalls)
                assertFalse(control.isEnabled)
                assertFalse(control.performClick())
                assertFalse(control.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertNull(control.createAccessibilityNodeInfo())
                control.bindProgress(checked = false, enabled = true, progress = "Too late")
                control.close()
                assertEquals("Terminal close must not replay failed detach", 1, detachCalls)
                assertFalse(control.isEnabled)
                assertEquals(0, changes)
            }
        } finally {
            onMain { scope.cancel() }
        }
    }

    private fun frameJob(spinner: NativeCircularProgress): Job? =
        NativeCircularProgress::class.java.getDeclaredField("frameJob")
            .apply { isAccessible = true }.get(spinner) as Job?

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }

    private fun withActivity(block: (Activity) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var primary: Throwable? = null
        try {
            block(activity)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                onMain { activity.finish() }
                instrumentation.waitForIdleSync()
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }
}
