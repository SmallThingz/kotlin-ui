package smallthingz.ui.text.view

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.NativeToolbarContentAction

/** Actual attached native focus, with source retirement and cleanup in the same main-thread turn. */
@RunWith(AndroidJUnit4::class)
class NativeRetiredTextFocusTest {
    @Test fun absentDescriptionClearsHeldFocusWithoutReadmittingActions() = withFocusWindow { root ->
        val description = NativeActionDescriptionView(root.context, "Description")
        root.addView(description, FrameLayout.LayoutParams(240, 80))
        var present = true
        val lease = description.bindAccessibility(PlainTextAccessibilityWindowScope(root)) {
            if (present) sourceNode() else NativeSourceAccessibilityNode.absent()
        }
        try {
            place(description)
            acquire(description)
            present = false
            assertRetiredFocusCleanup(description)
        } finally { lease.close() }
    }

    @Test fun retiredRoleClearsHeldFocusForPresenceAndRoleChanges() = withFocusWindow { root ->
        val row = object : FrameLayout(root.context), NativeMergedRowAccessibilityHost {
            var sourcePresent = true
            var sourceRolePresent = true
            private val label = View(context)
            override val mergedRowAccessibility = MergedRowAccessibility(
                this, NativeMergedRowRole.BUTTON,
                sourceState = { NativeMergedRowAccessibilityState(true, null, null,
                    listOf(label), rolePresent = sourceRolePresent) },
                invokeSourceClick = { error("A role-only leaf must not activate its parent") },
            )
            init {
                accessibilityDelegate = mergedRowAccessibility
                addView(label, LayoutParams(240, 80))
                addView(mergedRowAccessibility.roleView, LayoutParams(240, 80))
            }
            override fun addChildrenForAccessibility(outChildren: ArrayList<View>) =
                mergedRowAccessibility.addChildrenForAccessibility(outChildren)
        }
        root.addView(row, FrameLayout.LayoutParams(240, 80))
        val lease = row.mergedRowAccessibility.bindSourceGeometry(geometry(root), {
            if (row.sourcePresent) sourceNode() else NativeSourceAccessibilityNode.absent()
        }) { true }
        try {
            place(row)
            val role = row.mergedRowAccessibility.roleView
            acquire(role)
            row.sourcePresent = false
            assertRetiredFocusCleanup(role)

            row.sourcePresent = true
            acquire(role)
            row.sourceRolePresent = false
            assertRetiredFocusCleanup(role)
        } finally { lease.close() }
    }

    @Test fun retiredToolbarGeometryStillAllowsDescriptionFocusCleanup() = withFocusWindow { root ->
        val action = NativeToolbarContentAction(root.context, View(root.context), "Toolbar description",
            inputRoot = null, density = 1f, minimumTargetDp = 48f,
            initialIndicationColor = Color.BLACK, standaloneAccessibility = true)
        root.addView(action, FrameLayout.LayoutParams(240, 80))
        action.bindAction(1f, 48f, Color.BLACK) { error("Retired toolbar must not activate") }
        val geometry = geometry(root)
        val source: (View) -> NativeSourceAccessibilityNode = { sourceNode() }
        val lease = action.bindAccessibility(PlainTextAccessibilityWindowScope(root), geometry, source)
        // An independently owned parent geometry lease supersedes only the parent's token. Its
        // retirement must not prevent cleanup on the still-attached, still-bound description.
        val parentLease = action.mergedRowAccessibility.bindSourceGeometry(geometry, source) { true }
        try {
            place(action)
            val description = action.getChildAt(1)
            acquire(description)
            parentLease.close()
            assertRetiredFocusCleanup(description)
            assertFalse(action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        } finally {
            parentLease.close()
            lease.close()
        }
    }

    private fun acquire(view: View) {
        assertTrue("Fixture must be attached and visible", view.isAttachedToWindow && view.isShown)
        assertTrue("Must acquire actual Android accessibility focus",
            view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        assertTrue(view.isAccessibilityFocused)
    }

    private fun assertRetiredFocusCleanup(view: View) {
        assertTrue("Source retirement must leave held native focus for the regression", view.isAccessibilityFocused)
        assertNull(view.createAccessibilityNodeInfo())
        assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertTrue("Retirement must permit clearing previously held focus",
            view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null))
        assertFalse(view.isAccessibilityFocused)
        assertFalse("Cleanup must not readmit focus acquisition",
            view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        assertFalse(view.isAccessibilityFocused)
        assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
    }

    private fun sourceNode() = NativeSourceAccessibilityNode(true, false, true, Rect(0, 0, 240, 80))

    private fun geometry(root: View) = NativePlainTextViewGeometry(root,
        NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })

    private fun place(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 240, 80)
    }

    private fun withFocusWindow(test: (FrameLayout) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val serviceInfo = checkNotNull(automation.serviceInfo)
        val originalFlags = serviceInfo.flags
        val manager = checkNotNull(instrumentation.targetContext.getSystemService(AccessibilityManager::class.java))
        val ready = CountDownLatch(1)
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
            if (enabled) ready.countDown()
        }
        var activity: NativeMenuAccessibilityTestActivity? = null
        try {
            instrumentation.runOnMainSync {
                manager.addTouchExplorationStateChangeListener(listener)
                if (manager.isTouchExplorationEnabled) ready.countDown()
            }
            // Set only this test automation service's capability, never secure/system settings.
            serviceInfo.flags = originalFlags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            automation.serviceInfo = serviceInfo
            assertTrue("UiAutomation touch exploration must become available", ready.await(5, TimeUnit.SECONDS))
            activity = instrumentation.startActivitySync(Intent(instrumentation.context,
                NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                as NativeMenuAccessibilityTestActivity
            lateinit var root: FrameLayout
            val host = checkNotNull(activity)
            instrumentation.runOnMainSync {
                root = FrameLayout(host)
                host.setContentView(root)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(manager.isEnabled && manager.isTouchExplorationEnabled)
                assertTrue(root.isAttachedToWindow)
                test(root)
            }
        } finally {
            try {
                instrumentation.runOnMainSync {
                    try { activity?.finish() }
                    finally { manager.removeTouchExplorationStateChangeListener(listener) }
                }
            } finally {
                serviceInfo.flags = originalFlags
                automation.serviceInfo = serviceInfo
            }
        }
    }
}
