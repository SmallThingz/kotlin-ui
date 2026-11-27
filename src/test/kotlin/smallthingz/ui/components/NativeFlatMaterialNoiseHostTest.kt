package smallthingz.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.gallery.GalleryDesign
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
import smallthingz.ui.graphics.NoiseTileCache
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

/** Real owners/cache/native pixels. No cache model or changes to the release fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeFlatMaterialNoiseHostTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val fill = Color.rgb(71, 83, 97)
    private fun palette(intensity: Float) = NativeColorScheme.fallback(false).copy(
        background = fill, surfacePreset = NativeGrainPreset(intensity, intensity))

    @Test fun coldFlatFactoriesAndDrawingNeverStartAGeneration() = isolated {
        val style = NativeBackdropStyle(pageGrainSeed = 17, windowGrainSeed = 29)
        val flat = palette(0f)
        val page = View(context)
        val pageBinding = style.attachPage(page, flat)
        val menu = menu()
        val owners = listOf(style.pageBackground(flat), style.windowBackground(flat),
            page.background as NoiseDrawable, NativeForeground.foregroundBackground(flat.pageControls, fill),
            NativeForeground.foregroundBackground(palette(.5f).pageControls, Color.TRANSPARENT))
        assertFalse(started()) // Constructor demand is synchronous even if its worker has not finished.
        owners.forEach { owner ->
            render(owner)
            assertNull(paint(owner).shader)
            assertFalse(started())
        }
        render(menu)
        // Increasing opacity while disabling the configured seed must not request old grain.
        style.copy(pageGrainSeed = null).bindPage(pageBinding, palette(.5f))
        val window = style.windowBackground(flat)
        style.copy(windowGrainSeed = null).bindWindowBackground(window, palette(.5f))
        render(window)
        render(page.background)
        assertFalse(started())
        assertNull(tile().valueOrNull())
    }

    @Test fun coldFlatFloatingPanelIsTheFirstNoiseOwner() = isolated {
        assertFalse(started())
        val surface = NativeFloatingPanelSurface(context, 1f,
            NativeFloatingPanelRecipe(grainSeed = 17), palette(0f))
        assertFlatModalWithoutGeneration(surface, 17)
    }

    @Test fun coldFlatActionSheetIsTheFirstNoiseOwner() = isolated {
        val style = NativeModalRecipes().actionSheetStyle(Density(1f),
            createFontFamilyResolver(context), palette(0f), 29)
        assertFalse(started())
        assertEquals(0f, style.recipe.grainIntensity, 0f)
        val surface = NativeSheetSurface.PaintedSurface(context, style, NativeDialogEdge.BOTTOM)
        assertFlatModalWithoutGeneration(surface, 29)
    }

    @Test fun retainedModalsChangingSeedWhileGoingFlatLeaveColdCacheIdle() {
        isolated {
            val recipe = NativeFloatingPanelRecipe(grainSeed = null)
            val surface = NativeFloatingPanelSurface(context, 1f, recipe, palette(1f))
            val owner = field(surface, "grain") as NoiseDrawable
            val fillBefore = surface.paintedTextSurface
            assertEquals(255, paint(owner).alpha)
            assertFalse(started())

            surface.bindSurface(1f, recipe.copy(grainSeed = 17), palette(0f))

            assertFlatModalWithoutGeneration(surface, 17)
            assertSame(owner, field(surface, "grain"))
            assertEquals(fillBefore, surface.paintedTextSurface)
        }
        isolated {
            val modalRecipes = NativeModalRecipes()
            val density = Density(1f)
            val resolver = createFontFamilyResolver(context)
            val style = modalRecipes.actionSheetStyle(density, resolver, palette(1f), null)
            val surface = NativeSheetSurface.PaintedSurface(context, style, NativeDialogEdge.BOTTOM)
            val owner = field(surface, "grain") as NoiseDrawable
            val fillBefore = surface.paintedTextSurface
            assertEquals(255, paint(owner).alpha)
            assertFalse(started())

            surface.updateStyle(modalRecipes.actionSheetStyle(density, resolver, palette(0f), 29))

            assertFlatModalWithoutGeneration(surface, 29)
            assertSame(owner, field(surface, "grain"))
            assertEquals(fillBefore, surface.paintedTextSurface)
        }
    }

    private fun assertFlatModalWithoutGeneration(surface: View, configuredSeed: Int) {
        // Check the synchronous latch immediately: no async completion can hide eager demand.
        assertFalse(started())
        val owner = field(surface, "grain") as NoiseDrawable
        assertEquals(configuredSeed, field(owner, "seed"))
        assertEquals(0, paint(owner).alpha)
        assertNull(paint(owner).shader)
        surface.measure(View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY))
        surface.layout(0, 0, 64, 64)
        render(surface)
        assertFalse(started())
        assertNull(paint(owner).shader)
        assertNull(tile().valueOrNull())
    }

    @Test fun coldGalleryLibraryStartsFlatAndRetainedRebindDisablesGrain() = isolated {
        val galleryPalette = palette(1f)
        assertTrue(galleryPalette.backgroundGrain > 0f)
        assertFalse(started())
        val background = GalleryDesign.libraryBackground(galleryPalette)
        assertFalse(started())
        assertTrue(render(background).all { it == galleryPalette.background })
        assertFalse(started())
        assertNull(tile().valueOrNull())

        // Warm the tile through a nonstandard seed, leaving the shared background shader empty.
        background.setGrainSeed(17)
        background.setNoiseIntensity(1f)
        render(background)
        awaitTile()
        render(background)
        assertNotNull(paint(background).shader)
        assertNull(field(NoiseTexture, "backgroundShader"))

        val callback = CounterCallback()
        background.callback = callback
        val bounds = background.copyBounds()
        val nextPalette = galleryPalette.copy(background = Color.rgb(89, 53, 29))
        GalleryDesign.bindLibraryBackground(background, nextPalette)
        assertSame(callback, background.callback)
        assertEquals(bounds, background.bounds)
        assertEquals(NoiseDrawable.SEED_BACKGROUND, field(background, "seed"))
        assertNull(paint(background).shader)
        assertNull(field(NoiseTexture, "backgroundShader"))
        assertTrue(render(background).all { it == nextPalette.background })
    }

    @Test fun retainedBackdropRestoresArbitrarySeedAndKeepsCallbackCounts() = isolated {
        val view = View(context)
        var changes = 0
        val binding = NativeBackdropBinding(view, 17, fill, 0f) { changes++ }
        val owner = view.background as NoiseDrawable
        val callback = CounterCallback()
        owner.callback = callback
        binding.bind(17, fill, 0f)
        assertEquals(0, changes)
        assertFalse(started())
        binding.bind(17, fill, .37f)
        assertSame(owner, view.background)
        assertEquals(1, changes)
        assertFalse(started())
        render(owner)
        assertTrue(started())
        awaitTile()
        val before = render(owner)
        assertTrue(before.toSet().size > 1)
        assertEquals(17, field(owner, "seed"))
        val phase = phase(owner)
        assertArrayEquals(render(NoiseDrawable(fill, 17).apply { setNoiseIntensity(.37f) }), before)
        binding.bind(17, fill, .37f)
        assertEquals(1, changes)
        binding.bind(17, fill, 0f)
        assertEquals(2, changes)
        assertNull(paint(owner).shader)
        assertTrue(render(owner).all { it == fill })
        val invalidations = callback.invalidations
        binding.bind(17, fill, .37f)
        assertEquals(3, changes)
        assertTrue(callback.invalidations > invalidations)
        assertArrayEquals(before, render(owner))
        assertArrayEquals(phase, phase(owner), 0f)
    }

    @Test fun windowAndForegroundRebindPreserveConfiguredAndCallerSeeds() = isolated {
        val flat = palette(0f)
        val textured = palette(.5f)
        val style = NativeBackdropStyle(windowGrainSeed = 29)
        val window = style.windowBackground(flat)
        val foreground = NativeForeground.foregroundBackground(flat.pageControls, fill)
        val arbitrary = NoiseDrawable(fill, 37, 0f)
        assertFalse(started())
        style.bindWindowBackground(window, textured)
        NativeForeground.bindForegroundBackground(foreground, textured.pageControls, fill)
        NativeForeground.bindForegroundBackground(arbitrary, textured.pageControls, fill)
        listOf(window, foreground, arbitrary).forEach { render(it) }
        awaitTile()
        listOf(window to 29, foreground to NoiseDrawable.SEED_BACKGROUND, arbitrary to 37).forEach { (owner, seed) ->
            val pixels = render(owner)
            assertTrue(pixels.toSet().size > 1)
            assertEquals(seed, field(owner, "seed"))
            assertArrayEquals(render(NoiseDrawable(fill, seed).apply { setNoiseIntensity(.5f) }), pixels)
        }
        style.bindWindowBackground(window, flat)
        NativeForeground.bindForegroundBackground(arbitrary, flat.pageControls, fill)
        assertNull(paint(window).shader)
        assertNull(paint(arbitrary).shader)
        assertEquals(37, field(arbitrary, "seed"))
        // A caller changes phase while flat; no binder may reset it to the standard phase.
        arbitrary.setGrainSeed(73)
        NativeForeground.bindForegroundBackground(arbitrary, textured.pageControls, fill)
        assertArrayEquals(render(NoiseDrawable(fill, 73).apply { setNoiseIntensity(.5f) }), render(arbitrary))
        val noSeed = NoiseDrawable(fill, null, 0f)
        NativeForeground.bindForegroundBackground(noSeed, textured.pageControls, fill)
        assertTrue(render(noSeed).all { it == fill })
        assertNull(field(noSeed, "seed"))
    }

    @Test fun samePopupOwnerEnablesGrainAndReturnsToIdenticalFlatPixels() = isolated {
        val menu = menu()
        val flat = render(menu)
        assertFalse(started())
        val owner = field(menu, "grain") as NoiseDrawable
        menu.bindGrain(.5f)
        render(menu)
        awaitTile()
        val textured = render(menu)
        assertFalse(flat.contentEquals(textured))
        assertSame(owner, field(menu, "grain"))
        menu.bindGrain(0f)
        assertNull(paint(owner).shader)
        assertArrayEquals(flat, render(menu))
        menu.bindGrain(.5f)
        assertArrayEquals(textured, render(menu))
    }

    @Test fun publicDrawableConstructorAndKotlinDefaultBridgeRemainAvailable() {
        val type = NoiseDrawable::class.java
        type.getConstructor()
        type.getConstructor(Int::class.javaObjectType, Int::class.javaObjectType)
        type.getConstructor(Int::class.javaObjectType, Int::class.javaObjectType,
            Int::class.javaPrimitiveType!!, Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"))
    }

    private fun menu() = NativeMenuSurface(context, Density(1f), NativeAnchoredMenuRecipe(), fill).apply {
        setBody(View(context))
        measure(View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY))
        layout(0, 0, 64, 64)
    }

    private fun isolated(check: () -> Unit) {
        NoiseTexture.releaseUiResources()
        try { check() } finally { NoiseTexture.releaseUiResources() }
    }

    private class CounterCallback : Drawable.Callback {
        var invalidations = 0
        override fun invalidateDrawable(who: Drawable) { invalidations++ }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
        override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
    }

    @Suppress("UNCHECKED_CAST")
    private fun tile() = field(NoiseTexture, "tile") as NoiseTileCache<Bitmap>
    private fun started() = synchronized(tile()) { field(tile(), "started") as Boolean }
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
    private fun phase(drawable: NoiseDrawable): FloatArray = FloatArray(9).also {
        val matrix = Matrix()
        checkNotNull(paint(drawable).shader).getLocalMatrix(matrix)
        matrix.getValues(it)
    }
    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun render(drawable: Drawable): IntArray {
        drawable.setBounds(0, 0, 64, 64)
        return pixels { drawable.draw(it) }
    }
    private fun render(view: View): IntArray = pixels { view.draw(it) }
    private fun pixels(draw: (Canvas) -> Unit): IntArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bitmap))
            return IntArray(64 * 64).also { bitmap.getPixels(it, 0, 64, 0, 0, 64, 64) }
        } finally { bitmap.recycle() }
    }
}
