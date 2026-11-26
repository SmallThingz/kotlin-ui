// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import android.animation.ValueAnimator
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity

@RunWith(AndroidJUnit4::class)
class AudioBlobSchedulingIntegrationTest {
    @Test fun relativeShapeSleepsUntilActivityWakesTheAttachedView() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var scale = 0f
        lateinit var view: AudioReactiveBlobView
        val posted = AudioReactiveBlobView::class.java.getDeclaredField("framePosted").apply { isAccessible = true }
        val signal = AudioReactiveBlobView::class.java.getDeclaredField("lastAudioSignalNanos").apply { isAccessible = true }
        val bins = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        fun <T> onMain(block: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            return requireNotNull(result).getOrThrow()
        }
        fun awaitView(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5000
            do {
                if (onMain(condition)) return
                SystemClock.sleep(16)
            } while (SystemClock.uptimeMillis() < deadline)
            throw AssertionError("Blob did not reach expected window or scheduling state")
        }
        try {
            onMain {
                view = AudioReactiveBlobView(host) { scale }
                host.setContentView(view)
                view.updateState(true, true, false, true,
                    NativeAudioBlobAppearance.Default.resolve(NativeColorScheme.fallback(false)))
            }
            awaitView { view.isAttachedToWindow && view.isShown && view.hasWindowFocus() }
            onMain {
                scale = 1f
                repeat(8) {
                    view.submitFrame(.001f, bins, false)
                    assertFalse(posted.getBoolean(view))
                    assertEquals(0L, signal.getLong(view))
                }
                view.submitFrame(.01f, bins, false)
                assertTrue(posted.getBoolean(view))
                view.submitFrame(.5f, bins, false)
                assertTrue(signal.getLong(view) != 0L)
            }
            awaitView { view.currentBaseRadiusFraction() > .331f }
            onMain {
                val lastVoice = signal.getLong(view)
                view.submitFrame(.001f, bins, false)
                assertTrue(posted.getBoolean(view))
                assertEquals(lastVoice, signal.getLong(view))
            }
            awaitView { !posted.getBoolean(view) }
            onMain {
                view.submitFrame(.001f, bins, false)
                assertFalse(posted.getBoolean(view))
                view.submitFrame(.001f, bins, true)
                assertTrue(posted.getBoolean(view))
            }
        } finally {
            onMain { host.finish() }
        }
    }
}
