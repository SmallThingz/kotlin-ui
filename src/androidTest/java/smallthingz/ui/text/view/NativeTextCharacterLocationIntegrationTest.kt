package smallthingz.ui.text.view

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

/** Finite own-label callback checks, not accessibility-service delivery or an API28 crash replay. */
@RunWith(AndroidJUnit4::class)
class NativeTextCharacterLocationIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY

    private fun withLabel(action: (ParagraphView) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val attached = CountDownLatch(1)
        var label: ParagraphView? = null
        var binding: AutoCloseable? = null
        var failure: Throwable? = null
        fun failed(error: Throwable) {
            val primary = failure
            if (primary == null) failure = error else if (primary !== error) primary.addSuppressed(error)
        }
        try {
            instrumentation.runOnMainSync {
                val root = FrameLayout(activity)
                val view = ParagraphView(activity, TextStyle.Default,
                    Density(activity.resources.displayMetrics.density), createFontFamilyResolver(activity), -16777216)
                label = view
                view.setText("AB")
                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) { attached.countDown() }
                    override fun onViewDetachedFromWindow(v: View) = Unit
                })
                root.addView(view, FrameLayout.LayoutParams(400, 100))
                binding = view.bindPlainTextAccessibility(PlainTextAccessibilityWindowScope(root),
                    NativePlainTextViewGeometry(root, NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })) { true }
                activity.setContentView(root)
            }
            check(attached.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val view = requireNotNull(label)
                check(view.isAttachedToWindow)
                view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 400, 100)
                action(view)
            }
        } catch (error: Throwable) { failed(error) }
        finally {
            try {
                instrumentation.runOnMainSync {
                    try { binding?.close() } catch (error: Throwable) { failed(error) }
                    try { activity.finish() } catch (error: Throwable) { failed(error) }
                }
            } catch (error: Throwable) { failed(error) }
        }
        failure?.let { throw it }
    }

    @Suppress("DEPRECATION")
    @Test fun callbackCapsCountAndPreservesLastCharacterAndNullTail() = withLabel { view ->
        var admittedCharacter: android.os.Parcelable? = null
        for (count in listOf(3, 20_001)) {
            val info = AccessibilityNodeInfo.obtain()
            try {
                val args = Bundle().apply {
                    putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 1)
                    putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, count)
                }
                view.addExtraDataToAccessibilityNodeInfo(info, key, args)
                val boxes = requireNotNull(info.extras.getParcelableArray(key))
                assertEquals(if (count == 3) 3 else 20_000, boxes.size)
                assertNotNull(boxes[0])
                assertTrue((1 until boxes.size).all { boxes[it] == null })
                if (count == 3) admittedCharacter = boxes[0] else assertEquals(admittedCharacter, boxes[0])
            } finally { info.recycle() }
        }
    }

    @Suppress("DEPRECATION")
    @Test fun invalidRequestsLeavePayloadAbsent() = withLabel { view ->
        for ((start, count) in listOf(0 to 0, -1 to 3, 2 to 3)) {
            val info = AccessibilityNodeInfo.obtain()
            try {
                view.addExtraDataToAccessibilityNodeInfo(info, key, Bundle().apply {
                    putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start)
                    putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, count)
                })
                assertFalse(info.extras.containsKey(key))
            } finally { info.recycle() }
        }
    }
}
