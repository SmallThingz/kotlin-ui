// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.graphics

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity

@RunWith(AndroidJUnit4::class)
class NoiseUiLifecycleIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun installRegistersOneOwnerForBothCallbacks() = onMain {
        val application = CallbackRecordingApplication()
        NoiseUiLifecycle.install(application)
        NoiseUiLifecycle.install(application)

        assertSame(application.activityCallbacks.single(), application.componentCallbacks.single())
    }

    @Test fun lifecycleDispatchKeepsVisibleWindowsAndAllowsHiddenPressureRelease() {
        val bootstrap = startHost()
        val application = bootstrap.application
        finishHost(bootstrap)
        var releases = 0
        var warmups = 0
        val callbacks = NoiseUiLifecycleCallbacks(prewarm = {
            warmups++
            true
        }) { releases++ }
        onMain {
            application.registerActivityLifecycleCallbacks(callbacks)
            application.registerComponentCallbacks(callbacks)
        }
        try {
            onMain {
                application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
                assertEquals(0, releases)
            }
            repeat(2) { completed ->
                val host = startHost()
                try {
                    onMain {
                        assertSame(application, host.application)
                        assertEquals(completed + 1, warmups)
                        application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
                        assertEquals(completed, releases)
                    }
                } finally {
                    finishHost(host)
                }
                onMain {
                    application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
                    application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
                    assertEquals(completed + 1, releases)
                }
            }
        } finally {
            onMain {
                application.unregisterComponentCallbacks(callbacks)
                application.unregisterActivityLifecycleCallbacks(callbacks)
            }
        }
    }

    @Test fun pausedPeerAndQuickReturnCancelReleaseUntilAllWindowsStayHidden() {
        val released = CountDownLatch(1)
        lateinit var first: Activity
        lateinit var peer: Activity
        var hiddenAt = 0L
        var releasedAt = 0L
        var warmups = 0
        val callbacks = NoiseUiLifecycleCallbacks(prewarm = { activity ->
            if (activity === first) false else {
                warmups++
                true
            }
        }) {
            releasedAt = SystemClock.uptimeMillis()
            released.countDown()
        }
        onMain {
            first = Activity()
            peer = Activity()
            callbacks.onActivityStarted(first)
            assertEquals(0, warmups)
            callbacks.onActivityStarted(peer)
            callbacks.onActivityStarted(peer)
            assertEquals(1, warmups)
            val third = Activity()
            callbacks.onActivityStarted(third)
            assertEquals(1, warmups)
            callbacks.onActivityStopped(third)
            callbacks.onActivityPaused(peer)
            callbacks.onActivityStopped(first)
            callbacks.onActivityDestroyed(first)
            callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
            assertEquals(1L, released.count)

            callbacks.onActivityStopped(peer)
            callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            callbacks.onActivityStarted(peer)
            callbacks.onActivityResumed(peer)
            assertEquals(2, warmups)
            callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
            assertEquals(1L, released.count)
        }

        // Separate the two deadlines so a stale queued release cannot satisfy the second grace.
        SystemClock.sleep(200)
        onMain {
            hiddenAt = SystemClock.uptimeMillis()
            callbacks.onActivityStopped(peer)
            callbacks.onActivityDestroyed(peer)
            callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            assertEquals(1L, released.count)
        }

        assertTrue("No timed release after the last stop", released.await(20, SECONDS))
        assertTrue("Release did not honor the current hidden interval", releasedAt >= hiddenAt + 10_000L)
    }

    private fun startHost(): Activity = instrumentation.startActivitySync(
        Intent(instrumentation.context, NativeMenuAccessibilityTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun finishHost(activity: Activity) {
        onMain { activity.finish() }
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain { activity.isDestroyed }) return
            SystemClock.sleep(16)
        }
        throw AssertionError("Test activity did not reach Destroyed")
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private class CallbackRecordingApplication : Application() {
        val activityCallbacks = mutableListOf<Application.ActivityLifecycleCallbacks>()
        val componentCallbacks = mutableListOf<ComponentCallbacks>()

        override fun registerActivityLifecycleCallbacks(callback: Application.ActivityLifecycleCallbacks) {
            activityCallbacks += callback
        }

        override fun registerComponentCallbacks(callback: ComponentCallbacks) {
            componentCallbacks += callback
        }
    }
}
