package smallthingz.ui.gallery

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.components.NativeSurface
import smallthingz.ui.components.NativeSurfaceStyle
import smallthingz.ui.components.modalEdgeWidthDp
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
import smallthingz.ui.reverb.ReverbDesign
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.max

/** Self-contained opt-in SDK35 fixture; production Android dependencies remain unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryDialogSurfaceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun publicHostHasNormalChildApiAndReadOnlyThemePaint() {
        assertFalse(NativeSurface::class.java.isAssignableFrom(GalleryDialogSurface::class.java))
        val methods = GalleryDialogSurface::class.java.methods.map { it.name }
        for (name in listOf("applyStyle", "setNoiseIntensity", "setPaintedFillColor", "setPaintedTextSurface")) {
            assertFalse("Raw component paint API leaked: $name", methods.contains(name))
        }
        assertTrue(methods.containsAll(listOf("addView", "removeView", "bindTheme", "getPaintedFillColor", "getPaintedTextSurface")))
        val host = GalleryDialogSurface(context, false)
        assertEquals(0, host.childCount)
        assertTrue(host.clipToOutline)
        assertFalse(host.isForceDarkAllowed)
        assertNull(host.background)
    }

    @Test fun allLegacyMountOverloadsAndOrdinaryRemovalKeepRealChildOwnership() {
        val host = GalleryDialogSurface(context, false)
        val additions = listOf<(View) -> Unit>(
            { host.addView(it) },
            { host.addView(it, 0) },
            { host.addView(it, 71, 37) },
            { host.addView(it, FrameLayout.LayoutParams(71, 37)) },
            { host.addView(it, 0, FrameLayout.LayoutParams(71, 37)) },
        )
        var clicks = 0
        for (add in additions) {
            val body = View(context).apply { setOnClickListener { clicks++ } }
            add(body)
            assertSame(host, body.parent)
            assertSame(body, host.getChildAt(0))
            assertEquals(0, host.indexOfChild(body))
            assertEquals(1, host.childCount)
            body.performClick()
            host.removeView(body)
            assertNull(body.parent)
            assertEquals(0, host.childCount)
        }
        assertEquals(5, clicks)
        val first = View(context)
        val second = View(context)
        val third = View(context)
        host.addView(first)
        host.addView(second, 0)
        host.addView(third, 1)
        assertEquals(listOf(second, third, first), (0 until host.childCount).map(host::getChildAt))
        host.removeViewAt(1)
        assertNull(third.parent)
        host.removeViews(0, 1)
        assertNull(second.parent)
        host.removeAllViews()
        assertNull(first.parent)
        assertEquals(0, host.childCount)
    }

    @Test fun contentGeometryMatchesOriginalSurfaceForMeasureModesPaddingAndRtl() {
        for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            for (mode in listOf(MeasureSpec.EXACTLY, MeasureSpec.AT_MOST, MeasureSpec.UNSPECIFIED)) {
                val host = GalleryDialogSurface(context, false).apply { layoutDirection = direction; setPadding(3, 5, 7, 11) }
                val original = reference(false).apply { layoutDirection = direction; setPadding(3, 5, 7, 11) }
                val actual = SizedBody(context, 73, 41)
                val expected = SizedBody(context, 73, 41)
                fun params() = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply {
                    setMargins(5, 7, 11, 13)
                }
                host.addView(actual, params())
                original.addView(expected, params())
                val width = spec(if (mode == MeasureSpec.UNSPECIFIED) 0 else 240, mode)
                val height = spec(if (mode == MeasureSpec.UNSPECIFIED) 0 else 180, mode)
                measure(host, width, height)
                measure(original, width, height)
                assertEquals(original.measuredWidth, host.measuredWidth)
                assertEquals(original.measuredHeight, host.measuredHeight)
                assertSame(host, actual.parent)
                assertEquals(boundsIn(original, expected), boundsIn(host, actual))
            }
        }
    }

    @Test fun sameSpecRemeasureAndUnchangedBoundedConsumerRetainDirectSlots() {
        val host = GalleryDialogSurface(context, false)
        val body = SizedBody(context, 160, 43)
        host.addView(body, FrameLayout.LayoutParams(-1, -1))
        val width = spec(220, MeasureSpec.EXACTLY)
        val height = spec(0, MeasureSpec.UNSPECIFIED)
        measure(host, width, height)
        assertEquals(43, host.measuredHeight)
        val count = body.measures
        body.preferredHeight = 91
        body.forceLayout()
        host.forceLayout()
        measure(host, width, height)
        assertEquals(91, host.measuredHeight)
        assertTrue(body.measures > count)
        assertSame(host, body.parent)

        val heading = SizedBody(context, 100, 51)
        val scrollingBody = SizedBody(context, 100, 1000)
        val footer = SizedBody(context, 100, 47)
        val minimumBodyDp = 96
        val bounded = GalleryBoundedDialogLayout(context, false, heading, scrollingBody, footer,
            minimumBodyHeightDp = minimumBodyDp)
        val parents = listOf(heading.parent, scrollingBody.parent, footer.parent)
        for (mode in listOf(MeasureSpec.EXACTLY, MeasureSpec.UNSPECIFIED, MeasureSpec.EXACTLY)) {
            measure(bounded, spec(300, MeasureSpec.EXACTLY), spec(if (mode == MeasureSpec.UNSPECIFIED) 0 else 180, mode))
            assertEquals(300, bounded.measuredWidth)
            if (mode == MeasureSpec.EXACTLY) assertEquals(180, bounded.measuredHeight)
            assertEquals(MeasureSpec.EXACTLY, scrollingBody.lastHeightMode)
            assertTrue(scrollingBody.height >= (minimumBodyDp * context.resources.displayMetrics.density).roundToInt())
            assertTrue(heading.bottom <= scrollingBody.top)
            assertTrue(scrollingBody.bottom <= footer.top)
            assertEquals(parents, listOf(heading.parent, scrollingBody.parent, footer.parent))
        }
        val surface = bounded.getChildAt(0) as GalleryDialogSurface
        assertEquals(1, surface.childCount)
        assertTrue(surface.getChildAt(0) is androidx.core.widget.NestedScrollView)
    }

    @Test fun themeRenderAndSamplesUseSharedWindowBackgroundRecipe() {
        awaitGrain()
        val host = GalleryDialogSurface(context, false)
        val body = View(context)
        host.addView(body, FrameLayout.LayoutParams(-1, -1))
        measure(host, spec(240, MeasureSpec.EXACTLY), spec(160, MeasureSpec.EXACTLY))
        for (dark in listOf(false, true, false)) {
            host.bindTheme(dark)
            assertSame(host, body.parent)
            assertEquals(ReverbDesign.appearance.resolveColorScheme(context, dark).background, host.paintedFillColor)
            assertSharedMaterial(host, dark)
            val before = pixels(host)
            host.bindTheme(dark)
            assertNull(host.background)
            assertArrayEquals(before, pixels(host))
        }
    }

    @Test fun callerBackgroundReplacementTintAndMutationCannotChangeThemePaintOrSamples() {
        awaitGrain()
        val host = GalleryDialogSurface(context, true)
        measure(host, spec(240, MeasureSpec.EXACTLY), spec(160, MeasureSpec.EXACTLY))
        val expected = pixels(host)
        val expectedFill = host.paintedFillColor
        val expectedText = host.paintedTextSurface
        val foreign = ColorDrawable(Color.WHITE)
        val overrides = listOf<() -> Unit>(
            { host.setBackgroundColor(Color.WHITE) },
            { host.background = foreign },
            { host.backgroundTintList = ColorStateList.valueOf(Color.WHITE) },
            { foreign.color = Color.RED; foreign.alpha = 17 },
            { host.setBackgroundResource(android.R.color.white) },
        )
        for (operation in overrides) {
            operation()
            // Test before and after the no-op same-theme bind that exposed the original bug.
            for (rebind in listOf(false, true)) {
                if (rebind) host.bindTheme(true)
                assertNull(host.background)
                assertEquals(expectedFill, host.paintedFillColor)
                assertEquals(expectedText, host.paintedTextSurface)
                assertArrayEquals(expected, pixels(host))
                assertSharedMaterial(host, true)
            }
        }
        assertNull(foreign.callback)
        host.bindTheme(false)
        assertSharedMaterial(host, false)
    }

    @Test fun callerForegroundOverlayRetainsIdentityWithoutReplacingPrivateOutline() {
        awaitGrain()
        val host = GalleryDialogSurface(context, false)
        val temporary = ColorDrawable(Color.MAGENTA)
        host.foreground = temporary
        host.bindTheme(true)
        measure(host, spec(32, MeasureSpec.EXACTLY), spec(16, MeasureSpec.EXACTLY))
        host.isEnabled = false
        assertSame(temporary, host.foreground)
        val overlay = pixels(host)
        assertEquals(Color.MAGENTA, overlay[(host.height / 2) * host.width + host.width / 2])
        val palette = ReverbDesign.appearance.resolveColorScheme(context, true)
        assertTrue("Fixture requires the theme's visible outline", palette.modalEdgeWidthDp() > 0f)
        assertNotEquals("Content foreground must not replace the private outline", Color.MAGENTA, overlay[host.width / 2])
        host.foreground = null
        host.isEnabled = true
        assertNull(host.foreground)
        val clean = GalleryDialogSurface(context, true)
        measure(clean, spec(32, MeasureSpec.EXACTLY), spec(16, MeasureSpec.EXACTLY))
        assertArrayEquals(pixels(clean), pixels(host))

        val original = reference(true)
        measure(original, spec(32, MeasureSpec.EXACTLY), spec(16, MeasureSpec.EXACTLY))
        val actualOutline = Outline()
        val referenceOutline = Outline()
        host.outlineProvider.getOutline(host, actualOutline)
        original.outlineProvider.getOutline(original, referenceOutline)
        val actualRect = Rect()
        val referenceRect = Rect()
        assertTrue(actualOutline.getRect(actualRect))
        assertTrue(referenceOutline.getRect(referenceRect))
        assertEquals(referenceRect, actualRect)
        assertEquals(referenceOutline.radius, actualOutline.radius, 0f)
        assertTrue(actualOutline.radius <= host.height / 2f)
    }

    @Test fun attachedInvalidationNeverExposesTargetMaterialWithSampledFill() {
        awaitGrain()
        val originalScale = ValueAnimator.getDurationScale()
        val setScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        try {
            setScale.invoke(null, 1f)
            assertEquals(1f, ValueAnimator.getDurationScale(), 0f)
            assertTrue(ValueAnimator.areAnimatorsEnabled())
            val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
            try {
                val activity = controller.get()
                val host = GalleryDialogSurface(activity, false)
                activity.setContentView(host)
                measure(host, spec(240, MeasureSpec.EXACTLY), spec(160, MeasureSpec.EXACTLY))
                assertTrue(host.isAttachedToWindow)
                assertTrue(host.isShown)
                val light = ReverbDesign.appearance.resolveColorScheme(activity, false)
                val dark = ReverbDesign.appearance.resolveColorScheme(activity, true)
                assertNotEquals("Fixture must exercise a changed fill", light.background, dark.background)
                // Observe the private Drawable's real invalidateSelf callback synchronously and
                // forward every call to its actual host. This does not depend on host GPU flags.
                val ownerField = GalleryDialogSurface::class.java.getDeclaredField("paintOwner").apply { isAccessible = true }
                val owner = checkNotNull(ownerField.get(host))
                val fillField = owner.javaClass.getDeclaredField("fill").apply { isAccessible = true }
                val fill = fillField.get(owner) as NoiseDrawable
                val callback = checkNotNull(fill.callback)
                assertSame(host, callback)
                // Equal theme grain/seed and unchanged bounds otherwise make every fill setter
                // a no-op at the first positive-duration retarget. Require a real metadata bind.
                assertTrue((dark.surfacePreset?.backgroundGrain ?: 1f) > 0f)
                fill.setNoiseIntensity(0f)
                val samples = mutableListOf<Pair<Int, Long>>()
                val observer = object : Drawable.Callback {
                    override fun invalidateDrawable(who: Drawable) {
                        samples += host.paintedFillColor to host.paintedTextSurface
                        callback.invalidateDrawable(who)
                    }
                    override fun scheduleDrawable(who: Drawable, what: Runnable, time: Long) =
                        callback.scheduleDrawable(who, what, time)
                    override fun unscheduleDrawable(who: Drawable, what: Runnable) =
                        callback.unscheduleDrawable(who, what)
                }
                fill.callback = observer
                try {
                    host.bindTheme(true)
                    // Drawable holds its callback weakly; this read keeps the observer alive.
                    assertSame(observer, fill.callback)
                    assertEquals("First animated retarget must retain its start fill",
                        light.background, host.paintedFillColor)
                } finally { fill.callback = callback }
                assertTrue("Must observe synchronous grain-policy invalidation", samples.isNotEmpty())
                val oldMaterial = ReverbDesign.appearance.backdrops.windowBackground(light).apply {
                    setNoiseIntensity(0f)
                }
                val newMaterial = ReverbDesign.appearance.backdrops.windowBackground(dark)
                for ((sampledFill, sampledSurface) in samples) {
                    oldMaterial.setBackgroundColor(sampledFill)
                    newMaterial.setBackgroundColor(sampledFill)
                    // Grain may be deliberately zero or newly bound; fill must always be sampled.
                    assertTrue("Drawable invalidation exposed a different fill than the public sample",
                        sampledSurface == oldMaterial.paintedTextSurface || sampledSurface == newMaterial.paintedTextSurface)
                }
                assertSharedMaterial(host, true)
            } finally { controller.pause().stop().destroy() }
        } finally { setScale.invoke(null, originalScale) }
    }

    private fun assertSharedMaterial(host: GalleryDialogSurface, dark: Boolean) {
        val palette = ReverbDesign.appearance.resolveColorScheme(context, dark)
        val expected = ReverbDesign.appearance.backdrops.windowBackground(palette)
        // A running transition paints the sampled color with the new theme's shared grain policy.
        expected.setBackgroundColor(host.paintedFillColor)
        assertEquals(expected.paintedTextSurface, host.paintedTextSurface)
        val actualPixels = pixels(host)
        val expectedPixels = pixels(expected, host.width, host.height)
        val density = context.resources.displayMetrics.density
        val inset = ceil(max(ReverbDesign.appearance.modals.sheet.radiusDp,
            palette.modalEdgeWidthDp()) * density).toInt() + 1
        assertTrue(host.width > inset * 2 && host.height > inset * 2)
        for (y in inset until host.height - inset) {
            for (x in inset until host.width - inset) {
                assertEquals("Shared background material at ($x,$y)",
                    expectedPixels[y * host.width + x], actualPixels[y * host.width + x])
            }
        }
    }

    private fun style(dark: Boolean): NativeSurfaceStyle {
        val palette = ReverbDesign.appearance.resolveColorScheme(context, dark)
        val recipe = ReverbDesign.appearance.modals.sheet
        val grain = palette.surfacePreset?.foregroundGrain ?: recipe.grainIntensity
        return NativeSurfaceStyle(
            fillColor = recipe.surfaceColor(palette), cornerRadiusDp = recipe.radiusDp,
            outlineColor = palette.border, outlineWidthDp = palette.modalEdgeWidthDp(),
            noise = recipe.grainSeed != null && grain > 0f,
            noiseSeed = recipe.grainSeed ?: NoiseDrawable.SEED_BACKGROUND,
        )
    }
    private fun reference(dark: Boolean): NativeSurface {
        val palette = ReverbDesign.appearance.resolveColorScheme(context, dark)
        val grain = palette.surfacePreset?.foregroundGrain ?: ReverbDesign.appearance.modals.sheet.grainIntensity
        return NativeSurface(context, style(dark)).apply { setNoiseIntensity(grain) }
    }
    private fun awaitGrain() {
        NoiseTexture.prepare()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) == null && System.nanoTime() < deadline) Thread.sleep(1)
        assertNotNull("Shared grain did not become ready", NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND))
    }
    private fun measure(view: View, width: Int, height: Int) {
        view.measure(width, height)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun spec(size: Int, mode: Int) = MeasureSpec.makeMeasureSpec(size, mode)
    private fun boundsIn(host: ViewGroup, child: View) = Rect(0, 0, child.width, child.height).also {
        host.offsetDescendantRectToMyCoords(child, it)
    }
    private fun pixels(view: View): IntArray {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(bitmap))
            IntArray(view.width * view.height).also { bitmap.getPixels(it, 0, view.width, 0, 0, view.width, view.height) }
        } finally { bitmap.recycle() }
    }
    private fun pixels(drawable: Drawable, width: Int, height: Int): IntArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
        } finally { bitmap.recycle() }
    }
    private class SizedBody(context: Context, val preferredWidth: Int, var preferredHeight: Int) : View(context) {
        var measures = 0
        var lastHeightMode = -1
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            measures++
            lastHeightMode = MeasureSpec.getMode(heightMeasureSpec)
            setMeasuredDimension(resolveSize(preferredWidth, widthMeasureSpec), resolveSize(preferredHeight, heightMeasureSpec))
        }
    }
}
