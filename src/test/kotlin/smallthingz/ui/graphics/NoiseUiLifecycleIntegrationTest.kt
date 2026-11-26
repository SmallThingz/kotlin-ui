// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.graphics

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.ComponentCallbacks2
import android.os.SystemClock
import android.os.Looper
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit.MILLISECONDS
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NoiseUiLifecycleIntegrationTest {
    private val controllers = IdentityHashMap<Activity, ActivityController<Activity>>()

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
        advanceMainBy(200)
        onMain {
            hiddenAt = SystemClock.uptimeMillis()
            callbacks.onActivityStopped(peer)
            callbacks.onActivityDestroyed(peer)
            callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            assertEquals(1L, released.count)
        }

        advanceMainBy(10_000)
        assertTrue("No timed release after the last stop", released.await(20, SECONDS))
        assertTrue("Release did not honor the current hidden interval", releasedAt >= hiddenAt + 10_000L)
    }

    private fun startHost(): Activity = onMain {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        controller.get().also { controllers[it] = controller }
    }

    private fun finishHost(activity: Activity) = onMain {
        val controller = requireNotNull(controllers.remove(activity))
        activity.finish()
        controller.pause().stop().destroy()
        assertTrue("Test activity did not reach Destroyed", activity.isDestroyed)
    }

    private fun advanceMainBy(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(millis, MILLISECONDS)
    }

    private fun <T> onMain(block: () -> T): T {
        assertSame(Looper.getMainLooper(), Looper.myLooper())
        return block()
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
