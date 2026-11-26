package smallthingz.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.View.MeasureSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.ceil
import kotlin.math.min
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.input.phase.NativeViewPointerHitLayout

@RunWith(AndroidJUnit4::class)
class NativeCaptureBorderCacheIntegrationTest {
    @Test fun rgbUpdatesOnlyRetintTheExistingAlphaMask() = onMain { surface ->
        surface.place(256, 112)
        val red = palette(Color.RED)
        surface.bind(red)
        val first = assertBorderPixels(surface, red.border, density = 1.25f)
        val alpha = Color.alpha(red.border)
        assertTrue("The raster covers both antialiased and solid stroke pixels",
            first.any { Color.alpha(it) in 1 until alpha } && first.any { Color.alpha(it) == alpha })
        assertEquals(1, surface.borderMaskRasterizations)

        for (ink in listOf(Color.GREEN, Color.BLUE, Color.RED)) {
            val next = palette(ink)
            surface.bind(next)
            val frame = assertBorderPixels(surface, next.border, density = 1.25f)
            assertEquals(next.border, frame[surface.width / 2])
            assertTrue(first.indices.all { Color.alpha(first[it]) == Color.alpha(frame[it]) })
            assertEquals("RGB alone must never rerasterize the mask", 1, surface.borderMaskRasterizations)
        }
    }

    @Test fun alphaDensityStyleAndSizeEachInvalidateTheMask() = onMain { surface ->
        surface.place(256, 112)
        val translucent = palette(Color.BLACK)
        val opaque = translucent.copy(surfacePreset = NativeDarkSurfacePreset.Amoled.copy(
            backgroundGrain = 0f, foregroundGrain = 0f, borderLightness = 0f,
        ))
        surface.bind(translucent)
        val thinTranslucent = assertBorderPixels(surface, translucent.border, 1.25f)
        assertEquals(1, surface.borderMaskRasterizations)

        surface.bind(opaque)
        val thinOpaque = assertBorderPixels(surface, opaque.border, 1.25f)
        assertTrue(Color.alpha(thinOpaque[surface.width / 2]) >
            Color.alpha(thinTranslucent[surface.width / 2]))
        assertEquals(2, surface.borderMaskRasterizations)

        surface.bind(opaque, density = 2.25f)
        val thickOpaque = assertBorderPixels(surface, opaque.border, 2.25f)
        assertFalse(thinOpaque.contentEquals(thickOpaque))
        assertEquals(3, surface.borderMaskRasterizations)

        val narrow = NativeCaptureControlsStyle.Default.copy(selectorWidthDp = 205)
        surface.bind(opaque, density = 2.25f, style = narrow)
        assertFalse(thickOpaque.contentEquals(assertBorderPixels(surface, opaque.border, 2.25f)))
        assertEquals(4, surface.borderMaskRasterizations)

        surface.place(238, 100)
        assertBorderPixels(surface, opaque.border, 2.25f)
        assertEquals(5, surface.borderMaskRasterizations)
        surface.place(280, 126)
        assertBorderPixels(surface, opaque.border, 2.25f)
        assertEquals(6, surface.borderMaskRasterizations)
        assertBorderPixels(surface, opaque.border, 2.25f)
        assertEquals(6, surface.borderMaskRasterizations)
    }

    @Test fun filledBorderFallbackPreservesTintWhenReturningToTheMask() = onMain { surface ->
        val red = palette(Color.RED)
        val blue = palette(Color.BLUE)
        surface.place(256, 112)
        surface.bind(red)
        assertBorderPixels(surface, red.border, 1.25f)
        assertEquals(1, surface.borderMaskRasterizations)

        surface.place(18, 3)
        assertBorderPixels(surface, red.border, 1.25f)
        surface.bind(blue)
        assertBorderPixels(surface, blue.border, 1.25f)
        surface.bind(red)
        assertBorderPixels(surface, red.border, 1.25f)
        assertEquals(1, surface.borderMaskRasterizations)

        surface.place(256, 112)
        assertBorderPixels(surface, red.border, 1.25f)
        assertEquals(2, surface.borderMaskRasterizations)
    }

    private fun assertBorderPixels(surface: Surface, color: Int, density: Float): IntArray {
        val expected = pixels(surface) { drawOriginalBorder(it, surface, color, density) }
        val actual = pixels(surface, surface::draw)
        assertArrayEquals("Border paint must match the original alpha-mask and tint composition", expected, actual)
        return actual
    }

    /** Replay the original uncached raster on the surface's exact code-derived outline. */
    private fun drawOriginalBorder(canvas: Canvas, surface: Surface, color: Int, density: Float) {
        val shape = NativeCaptureControlSurface::class.java.getDeclaredField("shape").apply {
            isAccessible = true
        }.get(surface) as Path
        val paint = Paint(FLAGS).apply { this.color = color }
        val shortest = min(surface.width, surface.height)
        val strokeWidth = min(ceil(density), ceil(shortest / 2f)) * 2f
        if (strokeWidth > shortest) {
            canvas.drawPath(shape, paint)
            return
        }
        val bounds = RectF().also { shape.computeBounds(it, false) }
        val width = ceil(bounds.width()).toInt()
        val height = ceil(bounds.height()).toInt()
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        try {
            val mask = Path().apply {
                addRect(bounds, Path.Direction.CCW)
                op(shape, Path.Op.DIFFERENCE)
            }
            val raster = Canvas(image)
            raster.clipRect(0, 0, width, height)
            raster.translate(-bounds.left, -bounds.top)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.strokeCap = Paint.Cap.BUTT
            paint.strokeJoin = Paint.Join.MITER
            paint.strokeMiter = 4f
            raster.drawPath(shape, paint)
            raster.scale((width + 1f) / width, (height + 1f) / height, width / 2f, height / 2f)
            paint.style = Paint.Style.FILL
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            raster.drawPath(mask, paint)
            val tint = Paint(FLAGS).apply {
                this.color = Color.BLACK
                colorFilter = PorterDuffColorFilter(color or Color.BLACK, PorterDuff.Mode.SRC_IN)
            }
            canvas.translate(bounds.left, bounds.top)
            canvas.drawBitmap(image, Rect(0, 0, width, height), RectF(0f, 0f, width.toFloat(), height.toFloat()), tint)
        } finally { image.recycle() }
    }

    private fun pixels(surface: Surface, draw: (Canvas) -> Unit): IntArray {
        val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        return try {
            draw(Canvas(bitmap))
            IntArray(surface.width * surface.height).also {
                bitmap.getPixels(it, 0, surface.width, 0, 0, surface.width, surface.height)
            }
        } finally { bitmap.recycle() }
    }

    private fun palette(ink: Int) = NativeColorScheme.fallback(false).copy(
        onSurface = ink, surfaceContainerHigh = Color.TRANSPARENT,
    )

    private fun onMain(test: (Surface) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync {
            result = runCatching { test(Surface(instrumentation.targetContext)) }
        }
        checkNotNull(result).getOrThrow()
    }

    private class Surface(context: Context) : NativeCaptureControlSurface(context) {
        override fun childHitLayouts(): List<NativeViewPointerHitLayout> = emptyList()

        fun bind(
            palette: NativeColorScheme,
            density: Float = 1.25f,
            style: NativeCaptureControlsStyle = NativeCaptureControlsStyle.Default,
        ) {
            bindSurface(palette, density, style, NativeBufferSegmentStyle())
        }

        fun place(width: Int, height: Int) {
            measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            layout(0, 0, width, height)
        }
    }

    private companion object {
        const val FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG
    }
}
