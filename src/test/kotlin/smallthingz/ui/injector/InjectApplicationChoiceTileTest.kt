package smallthingz.ui.injector

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.ViewCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.components.NativeChip
import smallthingz.ui.components.NativeCircularProgress
import smallthingz.ui.components.NativeJoinedApplicationGridLayout
import smallthingz.ui.components.applicationGrid

/** Attached host coverage for the actual extracted tile, not a hand-built copy of its children. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InjectApplicationChoiceTileTest {
    @Test fun selectionExplanationAndPendingShareOneActionWithoutOptimisticMutation() = withTile { tile, root ->
        var selections = 0
        var explanations = 0
        tile.onSelectionRequest = { selections++; true }
        tile.onExplanationRequest = { explanations++; true }
        val choice = tile.getChildAt(0) as NativeChip
        val icon = tile.getChildAt(1) as ImageView
        val marker = tile.getChildAt(2) as ImageView
        val progress = tile.getChildAt(3) as NativeCircularProgress
        val info = tile.getChildAt(4) as NativeChip
        tile.bindChoice("Mail", "Mail, example.mail", selected = true, stateDescription = "Blocked")
        tile.bindIcon(ColorDrawable(Color.BLUE))
        layout(root)
        assertTrue(choice.requestFocus())
        assertTrue(choice.performClick())
        assertTrue(choice.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(2, selections)
        assertTrue("Only model rebinding changes checked state", choice.isChecked)
        assertEquals(View.GONE, info.visibility)
        assertEquals(View.VISIBLE, marker.visibility)
        assertEquals("Blocked", ViewCompat.getStateDescription(choice))

        tile.bindChoice("Mail", "Mail, example.mail", selected = true, pending = true,
            stateDescription = "Blocking")
        assertTrue(choice.hasFocus())
        assertTrue(choice.isChecked)
        assertEquals(View.GONE, marker.visibility)
        assertEquals(View.VISIBLE, progress.visibility)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, progress.importantForAccessibility)
        assertEquals("Blocking", ViewCompat.getStateDescription(choice))

        tile.bindChoice("Mail", "Mail, example.mail", selected = true,
            unavailableDescription = "Unavailable fixture", explanationDescription = "Mail, Why unavailable")
        layout(root)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, choice.importantForAccessibility)
        assertFalse(choice.isEnabled)
        assertTrue(choice.isChecked)
        assertFalse(choice.performClick())
        assertTrue(info.requestFocus())
        val node = info.createAccessibilityNodeInfo()
        assertEquals("android.widget.Button", node.className.toString())
        assertFalse(node.isCheckable)
        assertFalse(node.isSelected)
        assertEquals("Unavailable fixture", ViewCompat.getStateDescription(info))
        assertEquals(.55f, icon.alpha, 0f)
        assertEquals(View.VISIBLE, marker.visibility)
        assertEquals(View.GONE, progress.visibility)
        assertTrue(info.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(1, explanations)
        assertEquals(2, selections)

        // An explanation callback can reject stale admission without falling back to selection.
        tile.onExplanationRequest = { false }
        assertFalse(info.performClick())
        assertEquals(2, selections)
        tile.bindChoice("Mail", "Mail, example.mail", selected = false)
        assertFalse(choice.isChecked)
        assertEquals(1f, icon.alpha, 0f)
        assertEquals(View.GONE, info.visibility)
        assertTrue(choice.isFocusable)
    }

    @Test fun keyboardUsesControlledSelectionAndRecycleOrCloseRevokesActions() = withTile { tile, root ->
        var callbacks = 0
        tile.onSelectionRequest = { callbacks++; true }
        tile.bindChoice("Mail", "Mail", selected = false)
        layout(root)
        val choice = tile.getChildAt(0) as NativeChip
        assertTrue(choice.requestFocus())
        choice.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        choice.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(1, callbacks)
        assertFalse(choice.isChecked)
        tile.clearBinding()
        assertFalse(choice.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertFalse(choice.isFocusable)
        assertNull((tile.getChildAt(1) as ImageView).drawable)
        tile.bindChoice("Other", "Other", selected = true, pending = true)
        assertTrue(choice.performClick())
        assertEquals(2, callbacks)
        tile.close()
        tile.bindChoice("Late receipt", "Late receipt", selected = false)
        tile.bindIcon(ColorDrawable(Color.RED))
        assertFalse(choice.performClick())
        assertEquals(2, callbacks)
        assertNull((tile.getChildAt(1) as ImageView).drawable)
        assertEquals(View.GONE, tile.getChildAt(3).visibility)
    }

    @Test fun fourJoinedColumnsKeep36dpIconsAndPhysicalRightMarkersAtLargeFontAndRtl() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        try {
            for (fontScale in listOf(1f, 2f)) for (rtl in listOf(false, true)) {
                val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                    this.fontScale = fontScale
                    densityDpi = 160
                })
                val ui = InjectAppearance(context, InjectDesign.theme(context, false))
                val recipe = ui.defaults.applicationGrid
                val grid = NativeJoinedApplicationGridLayout(context, ui.scheme, recipe).apply {
                    columnCount = 4 // The host's exact four-column requirement is independent of font size.
                    layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                }
                val tiles = List(8) {
                    InjectApplicationChoiceTile(context, ui).apply {
                        bindChoice("Long تطبيق\nApplication", "Application", selected = true)
                        grid.addView(this)
                    }
                }
                try {
                    grid.measure(exact(360), exact(recipe.heightPx(context) * 2))
                    grid.layout(0, 0, grid.measuredWidth, grid.measuredHeight)
                    assertEquals(4, grid.columnCount)
                    val firstRow = tiles.take(4).sortedBy { it.left }
                    assertEquals(0, firstRow.first().left)
                    assertEquals(360, firstRow.last().right)
                    firstRow.zipWithNext().forEach { (left, right) -> assertEquals(left.right, right.left) }
                    for (tile in tiles) {
                        val choice = tile.getChildAt(0) as NativeChip
                        val icon = tile.getChildAt(1)
                        val marker = tile.getChildAt(2)
                        assertEquals(90, tile.width)
                        assertEquals(36, icon.width)
                        assertEquals(36, icon.height)
                        assertEquals(2, choice.maxLines)
                        assertEquals(0f, choice.choiceStyle.radiusDp, 0f)
                        assertFalse(choice.choiceStyle.outlined)
                        assertEquals(tile.width - 4, marker.right)
                        assertEquals(4, marker.top)
                        assertEquals(18, marker.width)
                        assertTrue("Icon stays inside enlarged label cell", icon.top >= 0)
                        assertTrue(icon.bottom <= tile.height)
                    }
                } finally { tiles.forEach { it.close() } }
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun withTile(block: (InjectApplicationChoiceTile, FrameLayout) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        val activity = controller.get()
        val root = FrameLayout(activity)
        val tile = InjectApplicationChoiceTile(activity, InjectAppearance(activity, InjectDesign.theme(activity, false)))
        root.addView(tile, FrameLayout.LayoutParams(90, 120))
        activity.setContentView(root)
        controller.windowFocusChanged(true)
        try { block(tile, root) }
        finally { tile.close(); controller.pause().stop().destroy() }
    }

    private fun layout(root: View) {
        root.measure(exact(360), exact(640))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
}
