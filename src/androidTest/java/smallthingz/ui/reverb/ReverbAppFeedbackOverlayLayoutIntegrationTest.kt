package smallthingz.ui.reverb

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity

@RunWith(AndroidJUnit4::class)
class ReverbAppFeedbackOverlayLayoutIntegrationTest {
    @Test @SdkSuppress(minSdkVersion = 30)
    fun transientOverlayUsesLargerBottomInsetAndLeavesContentBoundsUnchanged() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        AutoCloseable { main { activity.finish() } }.use {
            main {
                val host = InsetsHost(activity)
                val content = View(activity)
                host.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                activity.setContentView(host)
                assertTrue(host.isAttachedToWindow)
                measureAndLayout(host)
                val contentBounds = Rect(0, 0, 320, 500)
                assertEquals(contentBounds, content.bounds())

                val feedback = object : View(activity) {
                    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                        setMeasuredDimension(resolveSize(400, widthSpec), resolveSize(400, heightSpec))
                    }
                }
                val overlay = object : ReverbAppFeedbackOverlayLayout(activity) {
                    override val feedbackView = feedback
                    override val feedbackDensity = 1f
                    override val feedbackBottomClearanceDp = 20
                }
                overlay.addView(feedback)
                overlay.attachTo(host)
                assertSame(host, overlay.parent)

                host.suppliedInsets = windowInsets(imeBottom = 0)
                overlay.refreshInsets(host)
                measureAndLayout(host)
                assertEquals(contentBounds, overlay.bounds())
                assertEquals(Rect(29, 49, 279, 449), feedback.bounds())
                assertEquals(contentBounds, content.bounds())

                host.suppliedInsets = windowInsets(imeBottom = 180)
                overlay.refreshInsets(host)
                measureAndLayout(host)
                assertEquals(Rect(29, 17, 279, 300), feedback.bounds())
                assertEquals(contentBounds, content.bounds())

                host.suppliedInsets = windowInsets(imeBottom = 0)
                overlay.refreshInsets(host)
                measureAndLayout(host)
                assertEquals(Rect(29, 49, 279, 449), feedback.bounds())
                assertEquals(contentBounds, content.bounds())

                host.removeView(overlay)
                measureAndLayout(host)
                assertNull(overlay.parent)
                assertEquals(1, host.childCount)
                assertSame(content, host.getChildAt(0))
                assertEquals(contentBounds, content.bounds())
            }
        }
    }

    // Supply root-window insets through the same ViewCompat entry point used by the overlay.
    private class InsetsHost(context: Context) : FrameLayout(context) {
        var suppliedInsets: WindowInsets? = null
        override fun getRootWindowInsets(): WindowInsets? = suppliedInsets
    }

    private fun windowInsets(imeBottom: Int): WindowInsets {
        val bars = WindowInsetsCompat.Type.systemBars()
        val cutout = WindowInsetsCompat.Type.displayCutout()
        val ime = WindowInsetsCompat.Type.ime()
        return checkNotNull(WindowInsetsCompat.Builder()
            .setInsets(bars, Insets.of(0, 7, 23, 31)).setVisible(bars, true)
            .setInsets(cutout, Insets.of(11, 17, 0, 0)).setVisible(cutout, true)
            .setInsets(ime, Insets.of(0, 0, 0, imeBottom)).setVisible(ime, imeBottom > 0)
            .build().toWindowInsets())
    }

    private fun measureAndLayout(host: FrameLayout) {
        host.measure(MeasureSpec.makeMeasureSpec(320, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(500, MeasureSpec.EXACTLY))
        host.layout(0, 0, host.measuredWidth, host.measuredHeight)
    }

    private fun View.bounds() = Rect(left, top, right, bottom)

    private fun main(block: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }
}
