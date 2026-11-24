package smallthingz.ui.components.waveform

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.animation.ColorTransition
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale

@RunWith(AndroidJUnit4::class)
class NativeWaveformMarkerRetargetTest {
    @Test fun themeReversalBeforeFirstFrameRestoresBothMarkerParts() = checkReversal(false)

    @Test fun activeReversalBeforeFirstFrameRestoresLineAsWellAsGrip() = checkReversal(true)

    private fun checkReversal(changeActive: Boolean) {
        assumeTrue("Requires Android animations enabled", ValueAnimator.areAnimatorsEnabled())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var host: FrameLayout? = null
        lateinit var marker: WaveformMarkerView
        val original = WaveformStyle(0xff336699.toInt(), 0xff224466.toInt(),
            0xffee6633.toInt(), 0xff112233.toInt())
        try {
            instrumentation.runOnMainSync {
                marker = WaveformMarkerView(activity, 1f).apply {
                    bind(original, 1f, active = false, cursor = false, visualAlpha = 1f)
                }
                host = FrameLayout(activity).apply { addView(marker, FrameLayout.LayoutParams(40, 160)) }
                activity.setContentView(checkNotNull(host))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val scale = readCachedAnimatorDurationScale(activity)
                assumeTrue("Requires a positive finite duration scale", scale.isFinite() && scale > 0f)
                assertTrue(marker.isAttachedToWindow && marker.isShown && marker.width > 0)
                val before = Bitmap.createBitmap(marker.width, marker.height, Bitmap.Config.ARGB_8888)
                val after = Bitmap.createBitmap(marker.width, marker.height, Bitmap.Config.ARGB_8888)
                try {
                    marker.draw(Canvas(before))
                    if (changeActive) marker.bind(original, 1f, true, false, 1f)
                    else marker.bind(original.copy(markerColor = 0xffddeeff.toInt()), 1f, false, false, 1f)
                    // Same main-thread turn: reversal precedes the first scheduled frame.
                    marker.bind(original, 1f, false, false, 1f)
                    val terminal = System.nanoTime() +
                        (ColorTransition.DURATION_NANOS.toDouble() * scale).toLong() + 1_000_000L
                    for (index in 0 until marker.childCount) {
                        val part = marker.getChildAt(index)
                        val motion = part.javaClass.getDeclaredField("colorMotion").apply { isAccessible = true }
                            .get(part) as ViewColorTransition?
                        // Deliver the real paint owner's terminal frame without changing device settings.
                        checkNotNull(motion).doFrame(terminal)
                    }
                    marker.draw(Canvas(after))
                    assertTrue("Reversed marker must match its original line and grip pixels", before.sameAs(after))
                } finally { before.recycle(); after.recycle() }
            }
        } finally {
            instrumentation.runOnMainSync {
                try { host?.removeAllViews() } finally { activity.finish() }
            }
        }
    }
}
