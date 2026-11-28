package smallthingz.ui.components

import android.app.Activity
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.injector.InjectCompositeHostApplication
import smallthingz.ui.injector.InjectDesign
import smallthingz.ui.injector.InjectPermissionRow
import smallthingz.ui.interaction.view.NativeClickBinding
import smallthingz.ui.interaction.view.ViewClickController

@RunWith(RobolectricTestRunner::class)
@Config(application = InjectCompositeHostApplication::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativePermissionRowBindingHostTest {
    @Test fun enabledChangesKeepTheBindingAlreadyPublishedBySetEnabled() {
        val row = ObservedPermissionRow(RuntimeEnvironment.getApplication())
        row.captureBindings = true
        assertTrue(row.isEnabled)
        assertNull(inputBinding(row))

        bind(row, granted = false)
        val first = checkNotNull(inputBinding(row))
        assertTrue("First enabled bind still installs input despite an unchanged View flag", first.enabled)
        assertSame(row, first.ownerKey)

        bind(row, granted = true)
        val disabled = checkNotNull(inputBinding(row))
        assertFalse(disabled.enabled)
        assertSame("Disable must not allocate/publish a second identical input binding",
            row.bindingAfterSetEnabled, disabled)

        bind(row, granted = false)
        val enabled = checkNotNull(inputBinding(row))
        assertTrue(enabled.enabled)
        assertSame("Enable must keep the binding already published by setEnabled",
            row.bindingAfterSetEnabled, enabled)

        bind(row, granted = false)
        assertNotSame("An unchanged View flag still takes the existing rebind path",
            enabled, inputBinding(row))
    }

    @Test fun permissionRebindRetiresPendingInputAndUsesTheLatestActionAndStatus() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val root = FrameLayout(activity)
            val row = ObservedPermissionRow(activity) { root }
            root.addView(row, FrameLayout.LayoutParams(360, 120))
            activity.setContentView(root)
            controller.windowFocusChanged(true)
            root.measure(exact(360), exact(240))
            root.layout(0, 0, 360, 240)
            var oldCalls = 0
            var newCalls = 0
            bind(row, granted = false) { oldCalls++ }
            row.isFocusableInTouchMode = true
            assertTrue(row.requestFocus())
            assertTrue(row.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)))

            bind(row, granted = true) { oldCalls++ }
            row.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
            assertEquals("Disabling retires the pending key without invoking a request", 0, oldCalls)
            assertFalse(row.canRequestNow)
            assertFalse(row.performClick())
            assertEquals("Allowed", ViewCompat.getStateDescription(row).toString())

            bind(row, granted = false) { oldCalls++ }
            bind(row, granted = false, status = "Denied again") { newCalls++ }
            assertTrue(row.canRequestNow)
            assertTrue(row.performClick())
            assertEquals(0, oldCalls)
            assertEquals(1, newCalls)
            val node = checkNotNull(row.createAccessibilityNodeInfo())
            try {
                assertEquals("Root", node.contentDescription.toString())
                assertEquals("Denied again", ViewCompat.getStateDescription(row).toString())
                assertTrue(node.isClickable)
                assertEquals("Allow Root", node.actionList.single {
                    it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                }.label.toString())
            } finally { node.recycle() }
        } finally { controller.close() }
    }

    private fun bind(row: InjectPermissionRow, granted: Boolean,
        status: String = if (granted) "Allowed" else "Not allowed", action: () -> Unit = {}) =
        row.bindStatus("Root", status, showStatus = false, granted = granted,
            enabled = true, requestEnabled = true, requestLabel = "Allow Root", onRequest = action)

    /** Observe the real binding at the end of the superclass setter; never replace its controller. */
    private class ObservedPermissionRow(context: Context, inputRoot: () -> View? = { null }) :
        InjectPermissionRow(context, InjectDesign.theme(context, false), android.R.drawable.sym_def_app_icon,
            inputRoot = inputRoot) {
        var captureBindings = false
        var bindingAfterSetEnabled: NativeClickBinding? = null
        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            if (captureBindings) bindingAfterSetEnabled = inputBinding(this)
        }
    }

    private companion object {
        fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        fun inputBinding(row: NativeClickableRow): NativeClickBinding? {
            val input = NativeClickableRow::class.java.getDeclaredField("input").apply {
                isAccessible = true
            }.get(row)
            return ViewClickController::class.java.getDeclaredField("binding").apply {
                isAccessible = true
            }.get(input) as NativeClickBinding?
        }
    }
}
