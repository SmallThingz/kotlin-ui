package smallthingz.ui.zfm

import android.content.Intent
import android.text.Selection
import android.text.Spannable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.NativeText
import smallthingz.ui.components.withRetiringTestActivity

/** Authored regression source only; execution requires separate Android-test authorization. */
@RunWith(AndroidJUnit4::class)
class ZfmFileInfoPanelIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val name = "full_selectable_filename_".repeat(12) + ".txt"
    private val fields get() = listOf(ZfmFileInfoField("name", name)) +
        (0 until 24).map { ZfmFileInfoField("field-$it", "Metadata $it: retained value") }

    @Test fun checksumPublicationKeepsFullNameSelectionAndScroll() = withPanel { panel ->
        panel.bind(fields + ZfmFileInfoField("status", "Calculating SHA-256…"), emptyList())
        layout(panel)
        assertTrue(panel.closeAction.requestFocusFromTouch())
        val nameView = descendants(panel).filterIsInstance<NativeText>().single { it.text.toString() == name }
        assertTrue(nameView.isTextSelectable)
        assertNull(nameView.ellipsize)
        val selectedText = nameView.text as Spannable
        Selection.setSelection(selectedText, 5, 48)
        val scroll = panel.scroll
        scroll.scrollTo(0, 120)
        val position = scroll.scrollY
        assertTrue("Exercise a nonzero scroll position", position > 0)

        repeat(3) { iteration ->
            panel.bind(fields + listOf(ZfmFileInfoField("sha256", "SHA-256: result-$iteration"),
                ZfmFileInfoField("status", "Completed $iteration")), emptyList())
            layout(panel)
            assertSame(scroll, panel.scroll)
            assertSame(selectedText, nameView.text)
            assertEquals(name, nameView.text.toString())
            assertEquals(5, nameView.selectionStart)
            assertEquals(48, nameView.selectionEnd)
            assertEquals(position, scroll.scrollY)
            assertTrue(panel.closeAction.hasFocus())
        }
    }

    @Test fun retainedActionsKeepFocusAndDisabledChecksumMovesToCloseWithoutStealingItBack() = withPanel { panel ->
        var oldCopies = 0
        var newCopies = 0
        var hashes = 0
        fun actions(busy: Boolean, copy: () -> Unit) = listOf(
            ZfmFileInfoAction("copy", "Copy location", true, copy),
            ZfmFileInfoAction("hash", "SHA-256", !busy) { hashes++ },
        )
        panel.bind(fields, actions(false) { oldCopies++ })
        layout(panel)
        fun button(label: String) = descendants(panel).filterIsInstance<NativeMaterialButtonView>()
            .single { it.label.getText() == label }
        val copy = button("Copy location")
        val hash = button("SHA-256")
        assertTrue(copy.requestFocusFromTouch())
        panel.bind(fields + ZfmFileInfoField("status", "Calculating…"), actions(true) { newCopies++ })
        assertSame(copy, button("Copy location"))
        assertSame(hash, button("SHA-256"))
        assertTrue(copy.hasFocus())
        copy.performClick()
        assertEquals(0, oldCopies)
        assertEquals(1, newCopies)
        hash.performClick()
        assertEquals(0, hashes)

        panel.bind(fields, actions(false) { newCopies++ })
        assertTrue(hash.requestFocusFromTouch())
        panel.bind(fields, actions(true) { newCopies++ })
        assertTrue(panel.closeAction.hasFocus())
        assertFalse(hash.isEnabled)
        panel.bind(fields + ZfmFileInfoField("hash-value", "SHA-256: completed"), actions(false) { newCopies++ })
        assertTrue(hash.isEnabled)
        assertTrue(panel.closeAction.hasFocus())
        panel.close()
        copy.performClick()
        hash.performClick()
        assertEquals(1, newCopies)
        assertEquals(0, hashes)
    }

    private fun layout(panel: ZfmFileInfoPanel) {
        val density = panel.resources.displayMetrics.density
        panel.measure(View.MeasureSpec.makeMeasureSpec((393 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((600 * density).toInt(), View.MeasureSpec.AT_MOST))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun withPanel(assertions: (ZfmFileInfoPanel) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var root: FrameLayout? = null
        var panel: ZfmFileInfoPanel? = null
        withRetiringTestActivity(activity, root = { root }, cleanup = { panel?.close() }) {
            instrumentation.runOnMainSync {
                panel = ZfmFileInfoPanel(activity, ZfmTheme.resolve(activity, dark = false), "File information") {}
                root = FrameLayout(activity).also {
                    it.addView(panel, FrameLayout.LayoutParams(-1, -2))
                    activity.setContentView(it)
                }
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertions(checkNotNull(panel)) }
        }
    }
}
