package smallthingz.ui.zfm

import android.content.Intent
import android.graphics.Rect
import android.text.Selection
import android.text.Spannable
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.NativeText
import smallthingz.ui.components.withRetiringTestActivity

/** Authored, unrun: standalone body under a host-owned scroll, without a modal/Close wrapper. */
@RunWith(AndroidJUnit4::class)
class ZfmFileInfoBodyIntegrationTest {
    @Test fun temporaryDetachKeepsSelectionAndCurrentActionsUntilExplicitRetirement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var root: FrameLayout? = null
        var body: ZfmFileInfoBody? = null
        lateinit var content: ZfmFileInfoBody
        lateinit var scroll: ScrollView
        lateinit var label: NativeText
        lateinit var selected: Spannable
        lateinit var action: NativeMaterialButtonView
        var retiredOwnerCalls = 0
        var currentOwnerCalls = 0
        val fullName = "unabridged_information_name_".repeat(12) + ".txt"
        val fields = listOf(ZfmFileInfoField("name", fullName),
            ZfmFileInfoField("location", "Location: /full/path/$fullName"))
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()
        fun layoutHost() {
            val host = checkNotNull(root)
            assertTrue("Activity must supply a real laid-out viewport", host.width > 0 && host.height > 0)
            host.measure(View.MeasureSpec.makeMeasureSpec(host.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(host.height, View.MeasureSpec.EXACTLY))
            host.layout(host.left, host.top, host.right, host.bottom)
            assertTrue(scroll.width > 0 && scroll.height > 0)
        }
        withRetiringTestActivity(activity, root = { root }, cleanup = { body?.close() }) {
            instrumentation.runOnMainSync {
                content = ZfmFileInfoBody(activity, ZfmTheme.resolve(activity, dark = false))
                body = content
                scroll = ScrollView(activity).apply { addView(content) }
                root = FrameLayout(activity).also {
                    it.addView(scroll, FrameLayout.LayoutParams(-1, -1))
                    activity.setContentView(it)
                }
                content.bind(fields, listOf(ZfmFileInfoAction("open-with", "Open with", true) {
                    retiredOwnerCalls++
                }))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                layoutHost()
                label = descendants(content).filterIsInstance<NativeText>()
                    .single { it.text.toString() == fullName }
                selected = label.text as Spannable
                Selection.setSelection(selected, 3, 40)
                action = descendants(content).filterIsInstance<NativeMaterialButtonView>().single()
                assertTrue(content.isAttachedToWindow)
                scroll.removeView(content)
                assertFalse(content.isAttachedToWindow)
                scroll.addView(content)
                assertTrue(content.isAttachedToWindow)
                content.bind(fields, listOf(ZfmFileInfoAction("open-with", "Open with", true) {
                    currentOwnerCalls++
                }))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                layoutHost()
                assertSame(selected, label.text)
                assertEquals(fullName, label.text.toString())
                assertEquals(3, label.selectionStart)
                assertEquals(40, label.selectionEnd)
                assertSame(action, descendants(content).filterIsInstance<NativeMaterialButtonView>().single())
                assertTrue(action.isLaidOut && action.width > 0 && action.height > 0)
                // Reveal through the actual host ScrollView; long metadata may put this offscreen.
                val target = Rect()
                action.getDrawingRect(target)
                action.requestRectangleOnScreen(target, true)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val visible = Rect()
                assertTrue(action.isAttachedToWindow && action.isShown && action.isEnabled)
                assertTrue("Action must be onscreen before accessibility activation", action.getGlobalVisibleRect(visible))
                assertEquals("Full action width must be visible", action.width, visible.width())
                assertEquals("Full action height must be visible", action.height, visible.height())
                assertSame(selected, label.text)
                assertEquals(3, label.selectionStart)
                assertEquals(40, label.selectionEnd)
                assertTrue(action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(0, retiredOwnerCalls)
                assertEquals(1, currentOwnerCalls)
                content.close()
                content.close() // Host and owner retirement may both observe the closed window.
                action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                assertEquals(1, currentOwnerCalls)
            }
        }
    }
}
