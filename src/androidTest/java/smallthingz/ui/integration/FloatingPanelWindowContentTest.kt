package smallthingz.ui.integration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeBackdropStyle
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeDarkSurfacePreset
import smallthingz.ui.components.NativeDialogEdge
import smallthingz.ui.components.NativeFloatingDialog
import smallthingz.ui.components.NativeFloatingDialogContent
import smallthingz.ui.components.NativeFloatingPanelRecipe
import smallthingz.ui.components.NativeFloatingPanelSurface
import smallthingz.ui.components.NativeUiAppearance

/** Consumer-side public API through the real wrap-content window root, without showing a window. */
@RunWith(AndroidJUnit4::class)
class FloatingPanelWindowContentTest {
    @Test fun publicPanelAdapterReservesPaintOutsideContentAndInputAndTracksRebinds() =
        checkPanelPaintSpace(entryEdge = null)

    @Test fun bottomPanelAdapterReservesUpperPaintOutsideContentAndInputAndTracksRebinds() =
        checkPanelPaintSpace(entryEdge = NativeDialogEdge.BOTTOM)

    private fun checkPanelPaintSpace(entryEdge: NativeDialogEdge?) {
        val bottomEntry = entryEdge == NativeDialogEdge.BOTTOM
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching {
            val context = instrumentation.targetContext
            val preset = NativeDarkSurfacePreset.Amoled.copy(
                backgroundGrain = 0f, foregroundGrain = 0f, borderWidthDp = 2.25f)
            val palette = NativeColorScheme.fallback(true).copy(surfacePreset = preset)
            val original = NativeFloatingPanelRecipe(cornerRadiusDp = 24f, shadowDp = 7f, grainSeed = 11)
            val defaults = NativeUiAppearance.Default
            val appearance = defaults.copy(modals = defaults.modals.copy(floating = original),
                backdrops = NativeBackdropStyle(windowGrainSeed = 71))
            val recipe = appearance.floatingWindowRecipe
            assertEquals(original.copy(grainSeed = 71), recipe)

            lateinit var surface: TestPanel
            lateinit var root: ViewGroup
            var accessibilityBindings = 0
            var disposals = 0
            var dismissals = 0
            val dialog = NativeFloatingDialog(View(context), 1.5f, { dismissals++ },
                statusBarProtection = null, shadowDp = recipe.shadowDp, entryEdge = entryEdge) { panelContext, _, _ ->
                surface = TestPanel(panelContext, recipe, palette)
                NativeFloatingDialogContent.forPanel(surface, bindWindowAccessibility = {
                    root = it as ViewGroup
                    accessibilityBindings++
                }, dispose = { disposals++ }).also {
                    assertSame(surface, it.view)
                    assertSame(surface, it.predictiveMotionTarget)
                }
            }
            try {
                assertEquals(if (bottomEntry) Gravity.BOTTOM else Gravity.TOP,
                    requireNotNull(dialog.window).attributes.gravity)
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, requireNotNull(dialog.window).attributes.height)
                assertEquals(1, accessibilityBindings)
                measure(root)
                assertEquals(104, root.height) // ceil(2.25dp * 1.5), outside the 100px content.
                assertEquals(100, surface.height)
                assertEquals(100, surface.viewport.height)
                assertEquals(if (bottomEntry) 4 else 0, surface.top)
                assertEquals(0, surface.paddingTop)
                assertEquals(0, surface.paddingBottom)
                assertFalse(root.clipChildren)

                // Entry motion is intentionally bypassed only for this static paint/input probe.
                surface.alpha = 1f
                surface.translationY = 0f
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                try {
                    root.draw(Canvas(bitmap))
                    assertEquals("Straight exterior lip survives the window boundary", palette.border,
                        bitmap.getPixel(root.width / 2, if (bottomEntry) surface.top - 2 else surface.bottom + 1))
                    assertTrue("Rounded shoulder remains visible outside content",
                        bitmap.getPixel(30, if (bottomEntry) surface.top - 1 else surface.bottom) ushr 24 > 0)
                } finally { bitmap.recycle() }

                assertFalse("Exterior band must not dispatch to the panel",
                    touch(root, if (bottomEntry) surface.top - .5f else surface.bottom + 1f))
                // Window outside-hit classification must not round the upper paint band into content.
                val insideContent = root.javaClass.getDeclaredMethod("isInsideContent", MotionEvent::class.java)
                    .apply { isAccessible = true }
                val exterior = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 160f,
                    if (bottomEntry) surface.top - .25f else surface.bottom + .25f, 0)
                try { assertFalse(insideContent.invoke(root, exterior) as Boolean) } finally { exterior.recycle() }
                assertEquals(0, surface.viewportTouches)
                assertTrue(touch(root, 40f))
                assertEquals(1, surface.viewportTouches)

                // A retained owner must remeasure when either border width or density changes.
                surface.bindSurface(1.5f, recipe, palette.copy(surfacePreset = preset.copy(borderWidthDp = 0f)))
                assertTrue(root.isLayoutRequested)
                measure(root)
                assertEquals(100, root.height)
                assertEquals(0, surface.top)
                surface.bindSurface(2f, recipe, palette)
                assertTrue(root.isLayoutRequested)
                measure(root)
                assertEquals(105, root.height)
                assertEquals(if (bottomEntry) 5 else 0, surface.top)
                assertEquals(100, surface.height)
                assertEquals(100, surface.viewport.height)
                assertEquals(1, accessibilityBindings)
            } finally {
                dialog.dismiss()
                dialog.dismiss()
            }
            assertEquals(1, disposals)
            assertEquals(0, dismissals)
        } }
        checkNotNull(result).getOrThrow()
    }

    private fun measure(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun touch(root: ViewGroup, y: Float): Boolean {
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 160f, y, 0)
        val cancel = MotionEvent.obtain(0, 1, MotionEvent.ACTION_CANCEL, 160f, y, 0)
        return try { root.dispatchTouchEvent(down) } finally {
            root.dispatchTouchEvent(cancel)
            down.recycle()
            cancel.recycle()
        }
    }

    private class TestPanel(context: Context, recipe: NativeFloatingPanelRecipe, palette: NativeColorScheme) :
        NativeFloatingPanelSurface(context, 1.5f, recipe, palette) {
        var viewportTouches = 0
        val viewport = object : View(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                setMeasuredDimension(resolveSize(320, widthMeasureSpec), resolveSize(100, heightMeasureSpec))
            }
        }.apply {
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) viewportTouches++
                true
            }
        }
        init { setPanelViewport(viewport) }
    }
}
