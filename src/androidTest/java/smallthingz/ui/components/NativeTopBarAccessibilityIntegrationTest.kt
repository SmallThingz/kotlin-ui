package smallthingz.ui.components

import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class NativeTopBarAccessibilityIntegrationTest {
    @Test fun standaloneActionExposesTheCurrentLabelAndCallback() = withToolbar { fixture ->
        val (root, action, density) = fixture
        val visual = action.accessibilityAction
        assertNull(visual.createAccessibilityNodeInfo())
        assertFalse(visual in nativeAccessibilityChildren(visual.parent as ViewGroup))

        var clicks = 0
        action.bindAction(density, true, Color.BLACK, Color.BLACK) { clicks++ }
        val node = checkNotNull(visual.createAccessibilityNodeInfo())
        assertEquals("Einstellungen", node.contentDescription.toString())
        assertTrue(node.isVisibleToUser)
        assertTrue(node.isScreenReaderFocusable)
        assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        assertTrue(visual in nativeAccessibilityChildren(visual.parent as ViewGroup))
        val location = IntArray(2).also(visual::getLocationOnScreen)
        assertEquals(Rect(location[0], location[1], location[0] + visual.width, location[1] + visual.height),
            Rect().also(node::getBoundsInScreen))
        assertTrue(visual.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(1, clicks)

        action.setDescription("Zurueck")
        action.bindAction(density, false, Color.BLACK, Color.BLACK) { clicks++ }
        val disabled = checkNotNull(visual.createAccessibilityNodeInfo())
        assertEquals("Zurueck", disabled.contentDescription.toString())
        assertFalse(disabled.isEnabled)
        assertFalse(disabled.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        assertFalse(visual.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(1, clicks)

        root.visibility = View.INVISIBLE
        assertNull(visual.createAccessibilityNodeInfo())
    }

    @Test fun sourceLeaseOwnsChildrenAndNeverFallsBackAfterRetirement() = withToolbar(rolePresent = true) { fixture ->
        val (root, action, density) = fixture
        val visual = action.accessibilityAction as ViewGroup
        var clicks = 0
        action.bindAction(density, true, Color.BLACK, Color.BLACK) { clicks++ }
        assertEquals("Einstellungen", checkNotNull(visual.createAccessibilityNodeInfo()).contentDescription.toString())

        val cursor = PlainTextAccessibilityWindowScope(root)
        val geometry = NativePlainTextViewGeometry(root,
            NativeTextSemanticsClipPolicy { action.sourceClip(it) ?: NativeTextSemanticsClip.Unclipped })
        val sourceBounds = Rect(10, 20, 80, 90)
        var present = true
        val sourceNode: (View) -> NativeSourceAccessibilityNode = {
            if (present) NativeSourceAccessibilityNode(true, false, true, sourceBounds)
            else NativeSourceAccessibilityNode.absent()
        }
        val previous = action.bindAccessibility(cursor, geometry, sourceNode)
        val active = action.bindAccessibility(cursor, geometry, sourceNode)
        try {
            previous.close()
            val source = checkNotNull(visual.createAccessibilityNodeInfo())
            assertNull(source.contentDescription)
            assertEquals(sourceBounds, Rect().also(source::getBoundsInScreen))
            val children = nativeAccessibilityChildren(visual)
            assertEquals(2, children.size)
            assertEquals("Einstellungen", checkNotNull(children[0].createAccessibilityNodeInfo()).contentDescription.toString())
            assertEquals("android.widget.Button", checkNotNull(children[1].createAccessibilityNodeInfo()).className.toString())
            assertTrue(visual.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(1, clicks)

            present = false
            assertNull(visual.createAccessibilityNodeInfo())
            assertFalse(visual.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            present = true
        } finally {
            active.close()
        }
        assertTrue(visual.isShown)
        assertNull(visual.createAccessibilityNodeInfo())
        assertFalse(visual in nativeAccessibilityChildren(visual.parent as ViewGroup))
        assertFalse(visual.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(1, clicks)
    }

    @Test fun standaloneContentActionExposesOneFullSizeButtonAndGuardsActivation() =
        withAttachedView(::contentToolbar) { (root, action, mark, density) ->
            var clicks = 0
            assertNull(action.createAccessibilityNodeInfo())
            assertFalse(action.isFocusable)
            assertFalse(action.performClick())

            action.bindAction(density, 48f, Color.BLACK) { clicks++ }
            val node = checkNotNull(action.createAccessibilityNodeInfo())
            assertEquals("Ueber Control", node.contentDescription.toString())
            assertEquals("android.widget.Button", node.className.toString())
            assertEquals(0, node.childCount)
            assertTrue(nativeAccessibilityChildren(action).isEmpty())
            assertTrue(node.isVisibleToUser)
            assertTrue(node.isScreenReaderFocusable)
            val size = (48f * density).roundToInt()
            assertEquals(size, action.width)
            assertSame(mark, action.getChildAt(0))
            assertEquals(action.width, mark.width)
            assertEquals(action.height, mark.height)
            val location = IntArray(2).also(action::getLocationOnScreen)
            assertEquals(Rect(location[0], location[1], location[0] + size, location[1] + size),
                Rect().also(node::getBoundsInScreen))

            assertTrue(action.requestFocusFromTouch())
            assertTrue(checkNotNull(action.createAccessibilityNodeInfo()).isFocused)
            assertFalse(mark.hasFocus())
            assertTrue(action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(1, clicks)
            for (eventAction in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                assertTrue(action.dispatchKeyEvent(KeyEvent(eventAction, KeyEvent.KEYCODE_DPAD_CENTER)))
            }
            assertEquals(2, clicks)
            dispatchContentTap(root, action)
            assertEquals(3, clicks)

            action.setDescription("Info")
            action.isEnabled = false
            val disabled = checkNotNull(action.createAccessibilityNodeInfo())
            assertEquals("Info", disabled.contentDescription.toString())
            assertEquals("android.widget.Button", disabled.className.toString())
            assertFalse(disabled.isEnabled)
            assertFalse(disabled.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            assertFalse(action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertFalse(action.performClick())

            action.isEnabled = true
            root.visibility = View.INVISIBLE
            assertNull(action.createAccessibilityNodeInfo())
            assertFalse(action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertFalse(action.performClick())

            root.visibility = View.VISIBLE
            root.removeView(action)
            assertFalse(action.isAttachedToWindow)
            assertSame(mark, action.getChildAt(0))
            assertNull(action.createAccessibilityNodeInfo())
            assertFalse(action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertFalse(action.performClick())
            assertEquals(3, clicks)
        }

    @Test fun standaloneContentActionOwnsParentDispatchedTouchAndHover() =
        withAttachedView(::contentToolbar) { (root, action, mark, density) ->
            var clicks = 0
            var childTouches = 0
            var childHovers = 0
            val decoration = (mark as ViewGroup).getChildAt(0)
            decoration.setOnTouchListener { _, _ -> childTouches++; true }
            decoration.setOnHoverListener { _, _ -> childHovers++; true }
            action.bindAction(density, 48f, Color.BLACK) { clicks++ }

            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, mark.importantForAccessibility)
            assertNull(action.getChildAt(1).createAccessibilityNodeInfo())
            dispatchContentTap(root, action)
            assertEquals(1, clicks)
            action.isEnabled = false
            dispatchContentTap(root, action)
            assertEquals(1, clicks)
            assertEquals(0, childTouches)

            action.isEnabled = true
            val time = SystemClock.uptimeMillis()
            for (kind in intArrayOf(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_EXIT)) {
                val hover = MotionEvent.obtain(time, time, kind,
                    action.left + action.width / 2f, action.top + action.height / 2f, 0)
                try {
                    hover.source = InputDevice.SOURCE_TOUCHSCREEN
                    root.dispatchGenericMotionEvent(hover)
                } finally {
                    hover.recycle()
                }
            }
            assertEquals(0, childHovers)
            assertNull(action.getChildAt(1).createAccessibilityNodeInfo())
        }

    @Test fun enabledContentRelabelPublishesHostAccessibilityChange() =
        withAttachedView(::contentToolbar, runOnMainThread = false) { (_, action, _, density) ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            instrumentation.runOnMainSync {
                action.bindAction(density, 48f, Color.BLACK) {}
                assertEquals("Ueber Control", checkNotNull(action.createAccessibilityNodeInfo()).contentDescription.toString())
            }
            automation.waitForIdle(100, 5_000)
            val changed = automation.executeAndWaitForEvent(
                { instrumentation.runOnMainSync { action.setDescription("Control information") } },
                { event ->
                    if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                        (event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION) == 0) {
                        false
                    } else {
                        val source = event.source
                        try {
                            source != null && source.className.toString() == "android.widget.Button" &&
                                source.contentDescription?.toString() == "Control information"
                        } finally {
                            source?.recycle()
                        }
                    }
                },
                5_000,
            )
            changed.recycle()
            instrumentation.runOnMainSync {
                val node = checkNotNull(action.createAccessibilityNodeInfo())
                assertTrue(node.isEnabled)
                assertEquals("Control information", node.contentDescription.toString())
                assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            }
        }

    @Test fun scopedContentActionRetainsItsDescriptionChildAfterStandaloneTakeover() =
        withAttachedView(::contentToolbar) { (root, action, _, density) ->
            action.bindAction(density, 48f, Color.BLACK) {}
            assertEquals("android.widget.Button",
                checkNotNull(action.createAccessibilityNodeInfo()).className.toString())

            val cursor = PlainTextAccessibilityWindowScope(root)
            val geometry = NativePlainTextViewGeometry(root, NativeTextSemanticsClipPolicy {
                NativeTextSemanticsClip.Unclipped
            })
            val bounds = Rect(10, 20, 80, 90)
            val sourceNode = { _: View -> NativeSourceAccessibilityNode(true, false, true, bounds) }
            val previous = action.bindAccessibility(cursor, geometry, sourceNode)
            val lease = action.bindAccessibility(cursor, geometry, sourceNode)
            val description = action.getChildAt(1)
            try {
                previous.close()
                val source = checkNotNull(action.createAccessibilityNodeInfo())
                assertEquals("android.view.View", source.className.toString())
                assertNull(source.contentDescription)
                assertEquals(bounds, Rect().also(source::getBoundsInScreen))
                assertSame(description, nativeAccessibilityChildren(action).single())
                assertEquals("Ueber Control", checkNotNull(description.createAccessibilityNodeInfo()).contentDescription.toString())
            } finally {
                lease.close()
            }
            assertNull(description.createAccessibilityNodeInfo())
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, description.importantForAccessibility)
            assertNull(action.createAccessibilityNodeInfo())
            assertFalse(action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        }

    private data class Fixture(val root: FrameLayout, val action: NativeTopBarAction, val density: Float)

    private data class ContentFixture(
        val root: FrameLayout,
        val action: NativeToolbarContentAction,
        val mark: View,
        val density: Float,
    )

    private fun nativeAccessibilityChildren(parent: ViewGroup): List<View> =
        arrayListOf<View>().also(parent::addChildrenForAccessibility)

    private fun withToolbar(rolePresent: Boolean = false, test: (Fixture) -> Unit) =
        withAttachedView({ toolbar(it, rolePresent) }, test = test)

    private fun <T : Any> withAttachedView(
        build: (Activity) -> T,
        runOnMainThread: Boolean = true,
        test: (T) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            lateinit var fixture: T
            instrumentation.runOnMainSync { fixture = build(activity) }
            instrumentation.waitForIdleSync()
            if (runOnMainThread) instrumentation.runOnMainSync { test(fixture) } else test(fixture)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun contentToolbar(activity: Activity): ContentFixture {
        val density = activity.resources.displayMetrics.density
        val root = FrameLayout(activity)
        val mark = FrameLayout(activity).apply {
            contentDescription = "Decorative mark"
            addView(View(activity).apply {
                contentDescription = "Decorative detail"
                isFocusableInTouchMode = true
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val action = NativeToolbarContentAction(activity, mark, "Ueber Control", inputRoot = null,
            density = density, minimumTargetDp = 48f, initialIndicationColor = Color.BLACK, standaloneAccessibility = true)
        val size = (48f * density).roundToInt()
        root.addView(action, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        activity.setContentView(root)
        return ContentFixture(root, action, mark, density)
    }

    private fun dispatchContentTap(root: FrameLayout, action: NativeToolbarContentAction) {
        val time = SystemClock.uptimeMillis()
        for (kind in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time, kind,
                action.left + action.width / 5f, action.top + action.height / 2f, 0)
            try {
                assertTrue(root.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
    }

    private fun toolbar(activity: Activity, rolePresent: Boolean): Fixture {
        val density = activity.resources.displayMetrics.density
        val root = FrameLayout(activity)
        val bar = NativeTopBarLayout(activity).apply { bindMetrics(density) }
        val action = NativeTopBarAction(activity, density, { root }, "Einstellungen",
            rolePresent = rolePresent, initialTint = Color.BLACK)
        bar.addView(action, NativeTopBarLayout.ActionLayoutParams(NativeTopBarLayout.Slot.END))
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(root)
        return Fixture(root, action, density)
    }
}
