package smallthingz.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.textSurfaceForeground

@RunWith(AndroidJUnit4::class)
class NativeCommonActionPublicationIntegrationTest {
    @Test fun ownToolbarFillAndGrainPublicationDirtyTheActualInkLeafWithoutChangingItsSemanticTint() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var action: NativeThemedToolbarAction
        var paintedTint = Color.TRANSPARENT
        var callbacks = 0
        var publications = 0
        val palette = NativeColorScheme.fallback(false).copy(
            surfaceContainerLow = Color.WHITE, onSurface = Color.BLACK, onSurfaceVariant = Color.BLACK,
        )
        withLaidOutPaintActivity(mount = { activity ->
            val scope = object : FrameLayout(activity), NativeTextSurfaceScope {}
            action = NativeThemedToolbarAction(activity, 1f, palette, { scope }, "Action",
                onPaintedSurfaceChanged = { publications++; NativeTextSurface.invalidate(it) })
            action.iconPainter = { _, _, tint -> paintedTint = tint }
            action.bindToolbarAction(1f, palette, true, NativeToolbarActionTone.MUTED) { callbacks++ }
            scope.addView(action, FrameLayout.LayoutParams(64, 64))
            listOf(scope, action, action.visual)
        }, assertions = {
            instrumentation.runOnMainSync {
                val bitmap = Bitmap.createBitmap(action.width, action.height, Bitmap.Config.ARGB_8888)
                try {
                    fun paint() = action.draw(Canvas(bitmap))
                    val surface = action.getChildAt(0) as NativeGroupLayout
                    paint()
                    assertFalse(action.visual.isDirty)
                    assertEquals(Color.BLACK, paintedTint)
                    val before = publications
                    action.setSurfacePaletteImmediately(Color.BLACK, Color.BLACK)
                    assertTrue(publications > before)
                    assertTrue("Owned fill publication must dirty the retained glyph", action.visual.isDirty)
                    paint()
                    assertEquals(textSurfaceForeground(surface.paintedTextSurface, Color.BLACK), paintedTint)
                    assertNotEquals(Color.BLACK, paintedTint)
                    assertFalse(action.visual.isDirty)
                    val grainBefore = publications
                    action.bindSurfacePreset(NativeGrainPreset(0f, .5f))
                    assertTrue(publications > grainBefore)
                    assertTrue("Grain-only publication must also dirty ink", action.visual.isDirty)
                    paint()
                    assertEquals(textSurfaceForeground(surface.paintedTextSurface, Color.BLACK), paintedTint)
                    assertEquals(0, callbacks)
                    assertTrue(action.accessibilityAction.performClick())
                    assertEquals(1, callbacks)
                } finally { bitmap.recycle() }
            }
        })
    }

    @Test fun fullWidthMergedActionsKeepEqualSharesVisibilityAndCompactOptOut() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val theme = smallthingz.ui.reverb.ReverbDesign.theme(context, false)
            val widgets = smallthingz.ui.reverb.ReverbApplicationWidgets(context, theme)
            var callbacks = 0
            val actions = listOf("One", "A longer action", "Third").map { widgets.button(it) { callbacks++ } }
            val layout = widgets.mergedActions(actions, fillAvailableWidth = true)
            val wide = 3 * actions.maxOf { it.preferredWidth(false) } + 5
            fun measure(width: Int) {
                layout.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                layout.layout(0, 0, layout.measuredWidth, layout.measuredHeight)
            }
            try {
                for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                    layout.layoutDirection = direction
                    actions.forEach { it.visibility = View.VISIBLE }
                    measure(wide)
                    assertEquals(wide, layout.actionSurface.width)
                    assertEquals(wide, actions.sumOf { it.width })
                    assertTrue(actions.maxOf { it.width } - actions.minOf { it.width } <= 1)
                    actions[1].visibility = View.GONE
                    measure(wide)
                    assertEquals(wide, actions[0].width + actions[2].width)
                    actions[0].visibility = View.GONE
                    measure(wide)
                    assertEquals(wide, actions[2].width)
                    actions[2].visibility = View.GONE
                    measure(wide)
                    assertEquals(0, layout.height)
                    actions.forEach { it.visibility = View.VISIBLE }
                    val narrow = (120 * theme.density.density).toInt()
                    measure(narrow)
                    actions.forEach { assertEquals(narrow, it.width) }
                    assertEquals(actions[0].bottom, actions[1].top)
                    assertEquals(actions[1].bottom, actions[2].top)
                }
                assertEquals(0, callbacks)
            } finally { actions.forEach { it.close() } }
            val compactButton = widgets.button("Compact") {}
            try {
                val compact = NativeMergedActionLayout(context, listOf(compactButton), theme.palette)
                compact.measure(View.MeasureSpec.makeMeasureSpec(wide, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                compact.layout(0, 0, compact.measuredWidth, compact.measuredHeight)
                assertTrue(compact.actionSurface.width < compact.width)
                assertEquals(compact.width, compact.actionSurface.right)
            } finally { compactButton.close() }
        }
    }
}
