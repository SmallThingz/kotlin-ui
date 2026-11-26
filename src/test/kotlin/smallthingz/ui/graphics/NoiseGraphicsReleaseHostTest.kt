package smallthingz.ui.graphics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.components.CachedPathIconDrawable
import smallthingz.ui.components.NativeBufferSegmentStyle
import smallthingz.ui.components.NativeCaptureControlSurface
import smallthingz.ui.components.NativeCaptureControlsStyle
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeDecorativeIconView
import smallthingz.ui.input.phase.NativeViewPointerHitLayout

/** Real production holders and native graphics; no injected release callback or cache model. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoiseGraphicsReleaseHostTest {
    @Test fun releaseClearsEveryRetainedNoisePaintAndLazyDrawRestoresPhaseAndPixels() {
        NoiseTexture.releaseUiResources()
        try {
            NoiseTexture.prepare()
            val originalTile = awaitTile()
            assertEquals(1024, originalTile.width)
            assertEquals(1024, originalTile.height)
            assertEquals(Bitmap.Config.ARGB_8888, originalTile.config)
            val seeds = listOf(NoiseDrawable.SEED_BACKGROUND, NoiseDrawable.SEED_BACKGROUND,
                NoiseDrawable.SEED_TOP_BAR, 17)
            val owners = seeds.map { NoiseDrawable(Color.rgb(71, 83, 97), it) }.toMutableList()
            // A holder registered without grain must still be released after later activation.
            owners += NoiseDrawable(Color.rgb(71, 83, 97), null).apply { setGrainSeed(29) }
            val callbacks = owners.map { CounterCallback() }
            owners.forEachIndexed { index, owner ->
                owner.callback = callbacks[index]
                owner.setNoiseIntensity(.37f)
                owner.setBounds(0, 0, 64, 64)
            }
            val before = owners.map(::render)
            val phases = owners.map(::phase)
            val shaders = owners.map { checkNotNull(paint(it).shader) }
            assertSame(shaders[0], shaders[1])
            assertTrue("Actual grain must alter pixels", before[0].toSet().size > 1)
            val surfaceRanges = owners.map { it.paintedTextSurface }
            val beforeInvalidations = callbacks.map { it.invalidations }
            // Even an intensity-zero holder must drop an already-held shader.
            owners.last().setNoiseIntensity(0f)
            NoiseTexture.releaseUiResources()
            owners.forEachIndexed { index, owner ->
                assertNull(paint(owner).shader)
                assertTrue(callbacks[index].invalidations > beforeInvalidations[index])
            }
            assertNull(tile().valueOrNull())
            assertNull(field(NoiseTexture, "backgroundShader"))
            assertNull(field(NoiseTexture, "topBarShader"))
            assertFalse(originalTile.isRecycled)
            owners.last().setNoiseIntensity(.37f)
            // Demand resumes through the actual drawable, without a direct prepare() call.
            render(owners.first())
            val replacement = awaitTile()
            assertNotSame(originalTile, replacement)
            owners.forEachIndexed { index, owner ->
                assertArrayEquals(before[index], render(owner))
                assertArrayEquals(phases[index], phase(owner), 0f)
                assertNotSame(shaders[index], paint(owner).shader)
                assertEquals(surfaceRanges[index], owner.paintedTextSurface)
                assertEquals(94, paint(owner).alpha)
            }
            assertFalse(originalTile.isRecycled)
        } finally {
            NoiseTexture.releaseUiResources()
        }
    }

    @Test fun captureMaskReleasesOnHiddenWindowAndDetachAndRebuildsExactBorder() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val root = FrameLayout(controller.get())
            controller.get().setContentView(root)
            val view = CaptureSurface(controller.get())
            root.addView(view, FrameLayout.LayoutParams(320, 180))
            size(view, 320, 180)
            assertTrue(view.isAttachedToWindow)
            val before = render(view)
            val original = checkNotNull(field(view, "bitmap") as Bitmap?)
            val count = view.borderMaskRasterizations
            assertTrue(count > 0)
            assertNotNull(field(view, "maskCanvas"))
            view.dispatchWindowVisibilityChanged(View.INVISIBLE)
            assertNull(field(view, "bitmap"))
            assertNull(field(view, "maskCanvas"))
            assertEquals(true, field(view, "borderGeometryDirty"))
            assertFalse(original.isRecycled)
            view.dispatchWindowVisibilityChanged(View.VISIBLE)
            assertArrayEquals(before, render(view))
            val replacement = checkNotNull(field(view, "bitmap") as Bitmap?)
            assertNotSame(original, replacement)
            assertEquals(count + 1, view.borderMaskRasterizations)
            render(view)
            assertEquals(count + 1, view.borderMaskRasterizations)
            root.removeView(view)
            assertFalse(view.isAttachedToWindow)
            assertNull(field(view, "bitmap"))
            assertNull(field(view, "maskCanvas"))
            assertFalse(replacement.isRecycled)
            root.addView(view, FrameLayout.LayoutParams(320, 180))
            size(view, 320, 180)
            assertArrayEquals(before, render(view))
            assertEquals(count + 2, view.borderMaskRasterizations)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun decorativeIconForwardsHiddenAndDetachAndRebuildsExactGlyph() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val root = FrameLayout(controller.get())
            controller.get().setContentView(root)
            var rasterizations = 0
            val glyph = CachedPathIconDrawable(1f, Color.RED) { canvas, paint ->
                rasterizations++
                canvas.drawCircle(12f, 12f, 8f, paint)
            }
            val view = NativeDecorativeIconView(controller.get(), glyph, Color.RED)
            root.addView(view, FrameLayout.LayoutParams(48, 48))
            size(view, 48, 48)
            assertTrue(view.isAttachedToWindow)
            val before = render(view)
            assertTrue(before.any { Color.alpha(it) != 0 })
            val original = checkNotNull(field(glyph, "maskBitmap") as Bitmap?)
            assertEquals(1, rasterizations)
            view.visibility = View.INVISIBLE
            assertFalse(glyph.isVisible)
            assertNull(field(glyph, "maskBitmap"))
            assertNull(field(glyph, "maskCanvas"))
            assertFalse(original.isRecycled)
            view.visibility = View.VISIBLE
            assertTrue(glyph.isVisible)
            assertArrayEquals(before, render(view))
            assertEquals(2, rasterizations)
            val replacement = checkNotNull(field(glyph, "maskBitmap") as Bitmap?)
            assertNotSame(original, replacement)
            render(view)
            assertEquals(2, rasterizations)
            root.removeView(view)
            assertNull(field(glyph, "maskBitmap"))
            assertNull(field(glyph, "maskCanvas"))
            assertFalse(glyph.isVisible)
            assertFalse(replacement.isRecycled)
            root.addView(view, FrameLayout.LayoutParams(48, 48))
            size(view, 48, 48)
            assertTrue(glyph.isVisible)
            assertArrayEquals(before, render(view))
            assertEquals(3, rasterizations)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private class CaptureSurface(context: Context) : NativeCaptureControlSurface(context) {
        init { bindSurface(NativeColorScheme.fallback(false), 1f,
            NativeCaptureControlsStyle.Default, NativeBufferSegmentStyle()) }
        override fun childHitLayouts(): List<NativeViewPointerHitLayout> = emptyList()
    }

    private class CounterCallback : Drawable.Callback {
        var invalidations = 0
        override fun invalidateDrawable(who: Drawable) { invalidations++ }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
        override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
    }

    @Suppress("UNCHECKED_CAST")
    private fun tile() = field(NoiseTexture, "tile") as NoiseTileCache<Bitmap>

    private fun awaitTile(): Bitmap {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            tile().failureOrNull()?.let { throw AssertionError("Actual grain generation failed", it) }
            tile().valueOrNull()?.let { return it }
            Thread.sleep(1)
        }
        throw AssertionError("Actual grain generation did not finish within five seconds")
    }

    private fun paint(drawable: NoiseDrawable) = field(drawable, "noisePaint") as Paint
    private fun phase(drawable: NoiseDrawable): FloatArray {
        val matrix = Matrix()
        checkNotNull(paint(drawable).shader).getLocalMatrix(matrix)
        return FloatArray(9).also(matrix::getValues)
    }
    private fun field(owner: Any, name: String): Any? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            val current = type
            try { return current.getDeclaredField(name).apply { isAccessible = true }.get(owner) }
            catch (_: NoSuchFieldException) { type = current.superclass }
        }
        error("Missing production field $name on ${owner.javaClass.name}")
    }
    private fun size(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    private fun render(drawable: Drawable): IntArray = pixels(64, 64) { drawable.draw(it) }
    private fun render(view: View): IntArray = pixels(view.width, view.height) { view.draw(it) }
    private fun pixels(width: Int, height: Int, draw: (Canvas) -> Unit): IntArray {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(image))
        return IntArray(width * height).also { image.getPixels(it, 0, width, 0, 0, width, height) }
    }
}
