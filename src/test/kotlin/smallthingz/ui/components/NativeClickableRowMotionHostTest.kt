package smallthingz.ui.components

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.common.DropdownContent
import smallthingz.ui.common.ToggleRow
import smallthingz.ui.common.ToggleState
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.view.ParagraphView

/** Real row effects only: no popup, plugin, model, permissions or external navigation. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeClickableRowMotionHostTest {
    @Test fun uncheckedToggleDropdownAndDetailsWaitForMotionButBackRemainsAvailable() = verify(false)
    @Test fun checkedToggleDropdownAndDetailsWaitForMotionButBackRemainsAvailable() = verify(true)

    private fun verify(checked: Boolean) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val context = activity.get()
        val host = NativePageTransition.InputRoot(context)
        val background = FrameLayout(context)
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        host.addView(background, FrameLayout.LayoutParams(-1, -1))
        host.addView(page, FrameLayout.LayoutParams(-1, -1))
        val theme = ControlTheme.resolve(context, false)
        val values = mutableListOf<Boolean>()
        var opens = 0
        var details = 0
        var backs = 0
        var lease: AutoCloseable? = null
        val panels = NativePageTransition.Panels(host)
        val toggle = ToggleRow(context, ToggleState("Fixture toggle", checked, true, "Details"), theme) {
            values += it
        }
        toggle.bindDetails(1, "Details") { details++; true }
        fun paragraph() = ParagraphView(context, theme.appearance.typography[NativeTypographyRole.BODY_LARGE],
            theme.density, theme.fontResolver, theme.colors.ink)
        val colors = NativeDropdownColors(theme.colors.field, theme.colors.raised, theme.colors.muted,
            theme.colors.ink, theme.colors.border)
        val dropdown = NativeDropdown(context, paragraph(), paragraph(),
            NativeDropdownIcon(View(context)) {}, colors, { page }, onBinding = { _, _ -> },
            open = { _: View, content: DropdownContent<Int> ->
                assertEquals(listOf(1, 2), content.options)
                assertEquals(1, content.selected)
                opens++
            })
        dropdown.bind(DropdownContent("Fixture choice", 1, listOf(1, 2)) { "Option $it" }, false, colors)
        val back = NativeTopBarAction(context, 1f, { page }, "Back", initialTint = theme.colors.ink).apply {
            bindAction(1f, true, theme.colors.ink, theme.colors.ink) {
                backs++
                panels.cancelPreview(onFinished = { lease?.close(); lease = null })
            }
        }
        page.addView(back, LinearLayout.LayoutParams(48, 48))
        page.addView(toggle.view, LinearLayout.LayoutParams(-1, 72))
        page.addView(dropdown, LinearLayout.LayoutParams(-1, 80))
        context.setContentView(host)
        activity.visible().windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 360, 600)
        val oldScale = ValueAnimator.getDurationScale()
        setScale(1f)
        fun activate(view: View, action: Int = AccessibilityNodeInfo.ACTION_CLICK) {
            assertTrue("Exercise an attached, partly visible real control", view.isAttachedToWindow)
            assertTrue(view.getGlobalVisibleRect(Rect()))
            view.performAccessibilityAction(action, null)
        }
        fun rowActions() {
            activate(toggle.view)
            activate(dropdown)
            activate(toggle.view, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }
        fun counts(expected: Int) {
            assertEquals(List(expected) { !checked }, values)
            assertEquals(expected, opens)
            assertEquals(expected, details)
        }
        fun preview() {
            lease = checkNotNull(NativePageTransition.InputRoot.blockFrom(page))
            assertTrue(panels.preview(background, page, NativePageTransition.Direction.BACK))
            panels.seekPreview(.25f)
            assertTrue(page.translationY > 0f)
        }
        fun settle() {
            val animator: ValueAnimator = ReflectionHelpers.getField(panels, "animator")
            animator.end()
            assertNull(lease)
        }
        try {
            assertTrue(toggle.view.hasWindowFocus()) // Details retains its existing focus condition.
            rowActions()
            counts(1)
            preview()
            rowActions()
            counts(1)
            activate(back.accessibilityAction)
            assertEquals(1, backs)
            assertNotNull(lease)
            rowActions() // Reversal has started; input remains blocked until its completion.
            counts(1)
            settle()
            rowActions()
            counts(2)

            preview()
            rowActions()
            counts(2)
            panels.cancel() // Uncommitted preview restores the same page; owner retires its lease.
            lease?.close()
            lease = null
            rowActions()
            counts(3)
            assertEquals(1, backs)
        } finally {
            panels.close()
            lease?.close()
            dropdown.close()
            toggle.close()
            activity.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
            setScale(oldScale)
        }
    }

    private fun setScale(value: Float) = ValueAnimator::class.java
        .getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(null, value)
}
