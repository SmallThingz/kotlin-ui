package smallthingz.ui.injector

import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.ViewCompat
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.components.NativeChip
import smallthingz.ui.components.NativeCircularProgress
import smallthingz.ui.components.NativeJoinedApplicationGridLayout
import smallthingz.ui.components.applicationGrid

/** Attached host coverage for the actual extracted tile, not a hand-built copy of its children. */
@RunWith(RobolectricTestRunner::class)
@Config(application = InjectCompositeHostApplication::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
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
        focusForKeyboard(choice)
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
        focusForKeyboard(choice)
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
                    activity.setContentView(grid)
                    assertTrue(grid.isAttachedToWindow)
                    grid.measure(exact(360), exact(recipe.heightPx(context) * 2))
                    grid.layout(0, 0, grid.measuredWidth, grid.measuredHeight)
                    val direction = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    assertEquals("Grid resolves the requested host direction", direction, grid.layoutDirection)
                    assertEquals(4, grid.columnCount)
                    val firstRow = tiles.take(4).sortedBy { it.left }
                    assertEquals(0, firstRow.first().left)
                    assertEquals(360, firstRow.last().right)
                    firstRow.zipWithNext().forEach { (left, right) -> assertEquals(left.right, right.left) }
                    for (tile in tiles) {
                        assertEquals("Tile inherits grid direction", direction, tile.layoutDirection)
                        val choice = tile.getChildAt(0) as NativeChip
                        assertEquals("Label inherits tile direction", direction, choice.layoutDirection)
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

    @Test fun fallbackStartsOnlyWhenBoundAndRetiresWithTheIconOwner() = withTile { tile, root ->
        val icon = tile.getChildAt(1) as ImageView
        assertNull(icon.drawable)
        tile.bindIcon(null)
        assertNull("An unbound recycled tile must remain blank", icon.drawable)
        val staged = ColorDrawable(Color.BLUE)
        tile.bindIcon(staged)
        assertSame("Existing callers may supply real artwork before choice binding", staged, icon.drawable)
        tile.bindChoice("Staged app", "Staged app", selected = false)
        assertSame(staged, icon.drawable)
        tile.clearBinding()
        assertNull(icon.drawable)
        // Live app-page binding renders state before async icon completion: no bindIcon call here.
        tile.bindChoice("Mail", "Mail", selected = false)
        layout(root)
        val fallback = checkNotNull(icon.drawable)
        assertEquals(1f, icon.alpha, 0f)
        val real = ColorDrawable(Color.MAGENTA)
        tile.bindIcon(real)
        assertSame("Actual artwork replaces the placeholder without tinting or copying", real, icon.drawable)
        tile.bindChoice("Mail", "Mail", selected = true, unavailableDescription = "Unavailable")
        assertSame(real, icon.drawable)
        assertEquals(.55f, icon.alpha, 0f)
        tile.bindIcon(null)
        assertSame("Reuse this tile's owned fallback after a failed lookup", fallback, icon.drawable)
        tile.clearBinding()
        assertNull(icon.drawable)
        tile.bindChoice("Next app", "Next app", selected = false)
        assertSame(fallback, icon.drawable)
        assertEquals(1f, icon.alpha, 0f)
        tile.clearBinding()
        assertNull(icon.drawable)
        tile.bindChoice("Closing app", "Closing app", selected = false)
        assertSame(fallback, icon.drawable)
        tile.close()
        assertNull("Closing a bound owner clears its artwork", icon.drawable)
        tile.bindChoice("Late", "Late", selected = false)
        tile.bindIcon(real)
        assertNull("Closed owners cannot restore either supplied or fallback artwork", icon.drawable)
    }

    /** Actual latest controlled palettes, shared joined surface and tile; parent reviews the PNGs. */
    @Test fun fallbackAvailabilityRendersInLightAndDarkWithoutChangingSelectedInk() {
        for (dark in listOf(false, true)) withTile(dark = dark) { tile, root ->
            val theme = InjectDesign.theme(root.context, dark)
            val grid = NativeJoinedApplicationGridLayout(root.context, theme.palette,
                theme.appearance.applicationGrid).apply { columnCount = 1 }
            root.removeView(tile)
            grid.addView(tile)
            root.addView(grid, FrameLayout.LayoutParams(80, 88))
            root.isFocusableInTouchMode = true
            assertTrue(root.requestFocus())
            val oldScale = ValueAnimator.getDurationScale()
            fun scale(value: Float) = ValueAnimator::class.java
                .getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(null, value)
            scale(0f)
            val frames = mutableListOf<Bitmap>()
            fun capture(): Bitmap = Bitmap.createBitmap(grid.width, grid.height, Bitmap.Config.ARGB_8888).also {
                frames += it
                val canvas = Canvas(it)
                canvas.drawColor(theme.palette.field)
                grid.draw(canvas)
            }
            try {
                val choice = tile.getChildAt(0) as NativeChip
                val icon = tile.getChildAt(1) as ImageView
                val check = tile.getChildAt(2)
                val progress = tile.getChildAt(3)
                val info = tile.getChildAt(4) as NativeChip
                var selections = 0
                var explanations = 0
                tile.onSelectionRequest = { selections++; true }
                tile.onExplanationRequest = { explanations++; true }
                for (selected in listOf(false, true)) {
                    tile.clearBinding()
                    tile.bindChoice("Example app", "Example app", selected = selected)
                    layout(root)
                    assertTrue(root.requestFocus())
                    assertNotNull(icon.drawable) // No supplied artwork, including no explicit null bind.
                    assertEquals(36, icon.width)
                    assertEquals(36, icon.height)
                    val originalInk = choice.currentTextColor
                    val textTop = choice.baseline - choice.layout.getLineBaseline(0)
                    // Drain binding/focus work and retain one preparation draw after changing
                    // alpha/selection. Resting evidence still requires two exact matching draws.
                    shadowOf(Looper.getMainLooper()).idle()
                    val availablePreparation = capture()
                    val available = capture()
                    val availableControl = capture()
                    tile.bindChoice("Example app", "Example app", selected = selected,
                        unavailableDescription = "Unavailable fixture", explanationDescription = "Why unavailable")
                    layout(root)
                    assertTrue(root.requestFocus())
                    shadowOf(Looper.getMainLooper()).idle()
                    val unavailablePreparation = capture()
                    val unavailable = capture()
                    val unavailableControl = capture()
                    val prefix = "${if (dark) "dark" else "light"}-${if (selected) "selected" else "unselected"}"
                    val output = File("build/reports/grid-null-artwork").apply { mkdirs() }
                    for ((name, frame) in listOf("available-preparation" to availablePreparation,
                        "available" to available, "available-control" to availableControl,
                        "unavailable-preparation" to unavailablePreparation,
                        "unavailable" to unavailable, "unavailable-control" to unavailableControl)) {
                        File(output, "$prefix-$name.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    var changed = 0
                    var labelChanged = 0
                    for (y in 0 until grid.height) for (x in 0 until grid.width) {
                        if (available.getPixel(x, y) != unavailable.getPixel(x, y)) {
                            changed++
                            if (y >= textTop && y < tile.height - choice.paddingBottom &&
                                x >= choice.paddingLeft && x < tile.width - choice.paddingRight) labelChanged++
                        }
                    }
                    File(output, "$prefix-measurements.txt").writeText(
                        "available_preparation_matches_sample=${availablePreparation.sameAs(available)}\n" +
                            "unavailable_preparation_matches_sample=${unavailablePreparation.sameAs(unavailable)}\n" +
                            "available_repeat_stable=${available.sameAs(availableControl)}\n" +
                            "unavailable_repeat_stable=${unavailable.sameAs(unavailableControl)}\n" +
                            "changed_pixels=$changed\nlabel_changed_pixels=$labelChanged\n" +
                            "available_text_argb=$originalInk\nunavailable_text_argb=${choice.currentTextColor}\n" +
                            "field_argb=${theme.palette.field}\nselected=$selected\ndark=$dark\n")
                    assertTrue("Stable available render: $prefix", available.sameAs(availableControl))
                    assertTrue("Stable unavailable render: $prefix", unavailable.sameAs(unavailableControl))
                    assertTrue("Fallback provides a visible availability cue: $prefix", changed > 0)
                    assertEquals("Preserve actual selected/normal ink; do not assume onAccent", originalInk, choice.currentTextColor)
                    assertEquals("Do not reduce label contrast or change its painted background: $prefix", 0, labelChanged)
                    assertEquals("Example app", choice.text.toString())
                    assertEquals(selected, choice.isChecked)
                    assertEquals(if (selected) View.VISIBLE else View.GONE, check.visibility)
                    assertEquals(View.GONE, progress.visibility)
                    assertFalse(choice.isEnabled)
                    assertFalse(choice.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                    assertTrue(info.isEnabled)
                    assertEquals("Unavailable fixture", ViewCompat.getStateDescription(info))
                    assertTrue(info.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                    assertEquals(0, selections)
                    tile.bindChoice("Example app", "Example app", selected = selected, pending = true,
                        unavailableDescription = "Unavailable fixture")
                    assertEquals(selected, choice.isChecked)
                    assertEquals(View.GONE, check.visibility)
                    assertEquals(View.VISIBLE, progress.visibility)
                    assertTrue(info.isEnabled)
                }
                assertEquals(2, explanations)
            } finally {
                frames.forEach { it.recycle() }
                scale(oldScale)
            }
        }
    }

    private fun focusForKeyboard(choice: View) {
        assertTrue("Keyboard target must be attached", choice.isAttachedToWindow)
        assertTrue("Keyboard target must have a focused window", choice.hasWindowFocus())
        assertTrue("Binding must publish a focusable choice", choice.isFocusable)
        // NativeChip is intentionally not focusable in touch mode. Enter keyboard mode like
        // the attached toolbar tests, without modifying the production control's focus flags.
        assertTrue("Fixture can leave touch mode and focus the choice", choice.requestFocusFromTouch())
        assertFalse("Keyboard fixture is outside touch mode", choice.isInTouchMode)
        assertTrue(choice.requestFocus())
        assertSame("Focus belongs to the actual choice", choice, choice.rootView.findFocus())
    }

    private fun withTile(dark: Boolean = false, block: (InjectApplicationChoiceTile, FrameLayout) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        val activity = controller.get()
        val root = FrameLayout(activity)
        val tile = InjectApplicationChoiceTile(activity, InjectAppearance(activity, InjectDesign.theme(activity, dark)))
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
