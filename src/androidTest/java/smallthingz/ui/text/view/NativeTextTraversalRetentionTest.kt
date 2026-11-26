package smallthingz.ui.text.view

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.withRetiringTestActivity
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.LayoutDirection

@RunWith(AndroidJUnit4::class)
class NativeTextTraversalRetentionTest {
    @Test fun lineAndPageActionsKeepCaretOffsetsAndReleaseShapedText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var root: FrameLayout? = null
        var binding: AutoCloseable? = null
        lateinit var label: ParagraphView
        val text = "One\nTwo\nThree"
        withRetiringTestActivity(activity, root = { root }, cleanup = { binding?.close() }) {
            onMain {
                val container = FrameLayout(activity).also { root = it }
                label = ParagraphView(activity, TextStyle.Default.copy(layoutDirection = LayoutDirection.Ltr),
                    Density(1f), createFontFamilyResolver(activity), Color.BLACK)
                label.setText(text)
                container.addView(label, FrameLayout.LayoutParams(240, 120))
                binding = label.bindPlainTextAccessibility(PlainTextAccessibilityWindowScope(container),
                    NativePlainTextViewGeometry(container, NativeTextSemanticsClipPolicy {
                        NativeTextSemanticsClip.Unclipped
                    })) { true }
                activity.setContentView(container)
            }
            instrumentation.waitForIdleSync()
            onMain {
                assertTrue(label.isAttachedToWindow)
                label.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
                label.layout(0, 0, 240, 120)

                val next = AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                val previous = AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
                val line = traversalArgs(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE)
                assertTrue(label.performAccessibilityAction(next, line))
                assertCaret(label, 4)
                assertTrue(label.performAccessibilityAction(next, line))
                assertCaret(label, 8)
                assertTrue(label.performAccessibilityAction(previous, line))
                assertCaret(label, 4)

                assertTrue(label.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 0)
                }))
                val page = traversalArgs(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE)
                assertTrue(label.performAccessibilityAction(next, page))
                assertCaret(label, text.length)
                assertFalse(label.performAccessibilityAction(next, page))
                assertCaret(label, text.length)

                binding?.close()
                binding = null
                checkNotNull(root).removeView(label)
                assertFalse(label.isAttachedToWindow)
                assertNoRetainedIterator(NativePlainTextSegments.LineTextSegmentIterator::class.java)
                assertNoRetainedIterator(NativePlainTextSegments.PageTextSegmentIterator::class.java)
            }
        }
    }

    private fun traversalArgs(granularity: Int) = Bundle().apply {
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity)
        putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, true)
    }

    @Suppress("DEPRECATION")
    private fun assertCaret(label: ParagraphView, offset: Int) {
        val info = checkNotNull(label.createAccessibilityNodeInfo())
        try {
            assertEquals(offset, info.textSelectionStart)
            assertEquals(offset, info.textSelectionEnd)
        } finally { info.recycle() }
    }

    private fun assertNoRetainedIterator(type: Class<*>) {
        // Detect the former process-wide owner deterministically, without forcing a GC.
        for (owner in sequenceOf(type) + type.declaredClasses.asSequence()) {
            for (field in owner.declaredFields) {
                if (!Modifier.isStatic(field.modifiers) || field.type != type) continue
                field.isAccessible = true
                assertNull("Idle traversal retains an iterator through ${field.name}", field.get(null))
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }
}
