package smallthingz.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.reverb.ReverbApplicationWidgets
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.reverbActionSheetStyle
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class NativeModalSurfaceParityIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val density = Density(2f)
    private val topInset = 11
    private val bottomInset = 23

    @Test fun retainedActionSheetPaintsOnlyItsExposedEdgeInBothOrientations() = main {
        val context = instrumentation.targetContext
        val resolver = createFontFamilyResolver(context)
        val recipes = NativeUiAppearance.Default.modals
        fun style(palette: NativeColorScheme) =
            reverbActionSheetStyle(ReverbDesign.windowTheme(density, resolver, palette))
        for (entry in NativeDialogEdge.entries) {
            val light = palette(false)
            val sheet = NativeActionSheet(View(context), style(light), "Details", {}, entryEdge = entry)
            try {
                val surface = sheet.contentView as NativeSheetSurface
                val handle = surface.column.handle
                surface.insets.update(topInset, bottomInset)
                layout(surface)
                val originalMark = boundsIn(surface, handle.mark)
                val originalHandle = boundsIn(surface, handle)
                val originalHeight = surface.height
                val exposedTop = entry == NativeDialogEdge.BOTTOM
                val radiusPx = (recipes.sheet.radiusDp * density.density).toInt()

                fun checkPaint(palette: NativeColorScheme) {
                    assertSame(surface, sheet.contentView)
                    assertSame(handle, surface.column.handle)
                    assertEquals(originalHeight, surface.height)
                    assertEquals(originalMark, boundsIn(surface, handle.mark))
                    assertEquals(originalHandle, boundsIn(surface, handle))
                    render(surface) { pixels ->
                        assertDecoratedEdge(pixels, palette, exposedTop, radiusPx)
                        val markInk = pixels.getPixel(originalMark.centerX(), originalMark.centerY())
                        assertTrue(ColorUtils.calculateContrast(markInk, palette.background) >= 3.0)
                        // The touch padding at the exposed side of the mark stays unpainted.
                        val paddingY = if (exposedTop) (originalHandle.top + originalMark.top) / 2
                            else (originalHandle.bottom + originalMark.bottom) / 2
                        assertEquals(palette.background, pixels.getPixel(originalMark.centerX(), paddingY))
                    }
                }

                checkPaint(light)
                for (next in listOf(palette(false, borderLightness = .44f), palette(true), light)) {
                    sheet.updateStyle(style(next))
                    checkPaint(next)
                }
                val grain = palette(false, foregroundGrain = .5f)
                sheet.updateStyle(style(grain))
                assertEquals(grainRange(light.background), surface.painted.paintedTextSurface)
            } finally {
                sheet.dismiss()
            }
        }
    }

    @Test fun retainedFloatingPanelPaintsOnlyItsExposedEdgeAcrossPaletteAndEdgeBinds() = main {
        val context = instrumentation.targetContext
        val resolver = createFontFamilyResolver(context)
        val recipes = NativeUiAppearance.Default.modals
        val body = View(context).apply { minimumHeight = 180 }
        val light = palette(false)
        val theme = ReverbDesign.windowTheme(density, resolver, light)
        val panel = ReverbApplicationWidgets(context, theme).floatingPanel(body)
        val clip = panel.clipHost
        panel.setContentInsets(17, topInset, 25, bottomInset)
        layout(panel)
        val originalBody = boundsIn(panel, body)
        val originalHeight = panel.height
        val radiusPx = (recipes.floating.cornerRadiusDp * density.density).toInt()

        fun checkPaint(palette: NativeColorScheme, exposedTop: Boolean) {
            assertSame(clip, panel.clipHost)
            assertEquals(originalBody, boundsIn(panel, body))
            assertEquals(originalHeight, panel.height)
            assertEquals(textSurfaceRange(palette.background), panel.paintedTextSurface)
            render(panel) { assertDecoratedEdge(it, palette, exposedTop, radiusPx) }
        }

        checkPaint(light, exposedTop = false)
        val dark = palette(true)
        panel.bindSurface(density.density, recipes.floating.copy(surfaceElevationDp = 6f), dark)
        checkPaint(dark, exposedTop = false)
        panel.bindDialogEdge(NativeDialogEdge.BOTTOM)
        checkPaint(dark, exposedTop = true)
        panel.bindSurface(density.density, recipes.floating, light)
        checkPaint(light, exposedTop = true)
        val borderOnly = palette(false, borderLightness = .44f)
        panel.bindSurface(density.density, recipes.floating, borderOnly)
        checkPaint(borderOnly, exposedTop = true)
        panel.bindSurface(density.density, recipes.floating, palette(false, foregroundGrain = .5f))
        assertEquals(grainRange(light.background), panel.paintedTextSurface)
    }

    private fun assertDecoratedEdge(
        pixels: Bitmap,
        palette: NativeColorScheme,
        exposedTop: Boolean,
        radiusPx: Int,
    ) {
        val fill = palette.background
        val width = pixels.width
        val height = pixels.height
        fun fromExposedEdge(depth: Int) = if (exposedTop) depth else height - 1 - depth
        val seam = fromExposedEdge(height - 1)
        val thickness = ceil(NativeDefaultColorScheme.THIN_OUTLINE_WIDTH_DP * density.density).toInt()
        assertEquals(fill, pixels.getPixel(width * 3 / 4, height / 2))
        for (depth in 0 until thickness) {
            assertEquals("The exposed edge must use the current shared border", palette.border,
                pixels.getPixel(width / 2, fromExposedEdge(depth)))
        }
        assertEquals("The decoration stays within a thin stroke", fill,
            pixels.getPixel(width / 2, fromExposedEdge(thickness)))
        assertEquals("The attached seam stays undecorated", fill, pixels.getPixel(width / 2, seam))
        assertEquals("The attached corner remains filled", fill, pixels.getPixel(0, seam))
        assertEquals("The exposed corner stays clipped", Color.TRANSPARENT, pixels.getPixel(0, fromExposedEdge(0)))

        val arcY = fromExposedEdge(radiusPx / 2)
        for (fromLeft in listOf(true, false)) {
            fun x(depth: Int) = if (fromLeft) depth else width - 1 - depth
            val firstOpaque = (0 until radiusPx).first { Color.alpha(pixels.getPixel(x(it), arcY)) == 255 }
            assertTrue("Decoration must follow the exposed rounded shoulder",
                (firstOpaque until firstOpaque + thickness).any { pixels.getPixel(x(it), arcY) != fill })
        }
    }

    private fun palette(
        dark: Boolean,
        foregroundGrain: Float = 0f,
        borderLightness: Float = if (dark) .72f else .27f,
    ) = NativeColorScheme.fallback(dark).copy(
        background = if (dark) 0xff192439.toInt() else 0xffeadfc7.toInt(),
        surface = 0xff733869.toInt(),
        surfaceContainerLow = 0xff26465b.toInt(),
        surfaceContainerHigh = 0xff677f42.toInt(),
        surfacePreset = NativeDarkSurfacePreset.Amoled.copy(
            // Opaque, distinct borders make stale paint and an extra stroke pixel observable.
            borderLightness = borderLightness,
            backgroundGrain = .8f,
            foregroundGrain = foregroundGrain,
        ),
    )

    private fun grainRange(fill: Int) = NoiseDrawable(fill).apply { setNoiseIntensity(.5f) }.paintedTextSurface

    private fun layout(view: View) {
        view.measure(MeasureSpec.makeMeasureSpec(384, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(360, MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun boundsIn(parent: ViewGroup, view: View): Rect =
        Rect(0, 0, view.width, view.height).also { parent.offsetDescendantRectToMyCoords(view, it) }

    private fun render(view: View, check: (Bitmap) -> Unit) {
        val pixels = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(pixels))
            check(pixels)
        } finally {
            pixels.recycle()
        }
    }

    // Detached View.draw and visibility-snap binds need no focus, idle or animation waits.
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
