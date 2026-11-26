package smallthingz.ui.components

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.Insets
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
import smallthingz.ui.graphics.ViewGrain
import smallthingz.ui.reverb.ReverbBottomActionDock
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbTheme
import kotlin.math.roundToInt

/** Software pixels exercise the real shared grain and retained button, without opening a window. */
@RunWith(AndroidJUnit4::class)
class NativeBottomActionDockGrainIntegrationTest {
    @Test fun presetOnlyRebindAndAnimatedAlphaKeepInsetsDisjointAndButtonUnchanged() {
        awaitTexture()
        onMain {
            val context = InstrumentationRegistry.getInstrumentation().targetContext.let {
                it.createConfigurationContext(Configuration(it.resources.configuration).apply { densityDpi = 160 })
            }
            val theme = ReverbDesign.theme(context, dark = false)
            for (legacy in listOf(false, true)) {
                val button = NativeMaterialButtonView(context, ReverbBottomActionDock.buttonStyle(theme), theme.density, theme.fontResolver) {}
                try {
                    button.bind("", true, Color.BLACK, Color.WHITE)
                    val label = button.label
                    val input = button.nativeClickEndpoint
                    val accessibility = button.mergedRowAccessibility
                    val dock = dock(context, theme, button, legacy)
                    val gutter = ((if (legacy) theme.appearance.settingsSelectors.retentionSection.sideInsetDp
                        else NativeScreenSpacing.HORIZONTAL_DP) * theme.density.density).roundToInt()
                    dock.applySafeInsets(Insets.of(16, 0, 24, 32))
                    layout(dock)
                    assertEquals(28f, dock.topCornerRadiusPx, 0f)
                    assertEquals(if (legacy) 48 else 64, dock.minimumActionHeightPx)
                    assertEquals(16 + gutter, button.left)
                    assertEquals(24 + gutter, dock.width - button.right)
                    assertEquals(dock.width - 40 - 2 * gutter, button.width)
                    assertEquals(32, dock.height - button.bottom)
                    val bounds = Rect(button.left, button.top, button.right, button.bottom)
                    val motion = field(button, "colorMotion") as ViewColorTransition
                    var retainedGrain: Any? = null
                    for (preset in listOf(null, NativeGrainPreset(0f, 0f), NativeGrainPreset(0f, .25f),
                        theme.palette.surfacePreset, NativeGrainPreset(0f, 0f))) {
                        // A clean prior frame lets this assert preset-only invalidation of the dock itself.
                        raster(dock).recycle()
                        raster(dock).recycle()
                        val previousIntensity = button.sampledContainerGrainIntensity
                        button.setSurfacePreset(preset)
                        if (previousIntensity != (preset?.foregroundGrain ?: 0f))
                            assertTrue("Preset-only changes must invalidate the extension owner", dock.isDirty)
                        val intensity = preset?.foregroundGrain ?: 0f
                        raster(dock).recycle()
                        val grain = field(dock, "grain") as ViewGrain?
                        if (retainedGrain != null) assertSame(retainedGrain, grain)
                        if (grain != null) retainedGrain = grain
                        if (grain == null) assertEquals(0f, intensity, 0f)
                        else assertEquals(NoiseDrawable(Color.BLACK).apply { setNoiseIntensity(intensity) }.paintedTextSurface,
                            grain.surfaceRange(Color.BLACK))
                        button.setSurfacePreset(preset)
                        assertSame(grain, field(dock, "grain"))
                        for (alpha in listOf(0, 64, 128, 255)) {
                            // Publish actual intermediate-fill samples through the existing motion owner.
                            val fill = Color.argb(alpha, 24, 40, 56)
                            motion.snap(fill, Color.WHITE)
                            val actual = raster(dock)
                            val actionOnly = raster(button)
                            val reference = Bitmap.createBitmap(dock.width, dock.height, Bitmap.Config.ARGB_8888)
                            try {
                                val canvas = Canvas(reference)
                                canvas.drawColor(fill)
                                NoiseDrawable().apply { setNoiseIntensity(intensity) }.drawGrain(canvas,
                                    0f, 0f, dock.width.toFloat(), dock.height.toFloat(), coverage = alpha)
                                // Side gutters stay transparent for every row, even alongside
                                // the navigation fill. Inside the surface, retain the full
                                // alpha/grain pixel oracle and the disjoint action comparison.
                                assertTransparentGutters(actual, button)
                                for (y in 32 until dock.height) for (x in button.left until button.right) {
                                    if (y >= button.bottom) {
                                        assertEquals("inset ($x,$y), grain=$intensity alpha=$alpha",
                                            reference.getPixel(x, y), actual.getPixel(x, y))
                                    } else {
                                        assertEquals("No extra paint underneath the retained button ($x,$y)",
                                            actionOnly.getPixel(x - button.left, y), actual.getPixel(x, y))
                                    }
                                }
                                assertEquals(Color.TRANSPARENT, actual.getPixel(0, 0))
                                assertEquals(Color.TRANSPARENT, actual.getPixel(dock.width - 1, 0))
                                assertEquals("Inset top-left corner is clipped", Color.TRANSPARENT,
                                    actual.getPixel(button.left, 0))
                                assertEquals("Inset top-right corner is clipped", Color.TRANSPARENT,
                                    actual.getPixel(button.right - 1, 0))
                            } finally { actual.recycle(); actionOnly.recycle(); reference.recycle() }
                        }
                        assertSame(button, dock.action)
                        assertSame(button, dock.getChildAt(0))
                        assertSame(label, button.label)
                        assertSame(input, button.nativeClickEndpoint)
                        assertSame(accessibility, button.mergedRowAccessibility)
                        assertEquals(bounds, Rect(button.left, button.top, button.right, button.bottom))
                        assertEquals(1, dock.childCount)
                        assertFalse(dock.isClickable)
                        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, dock.importantForAccessibility)
                    }
                    for ((x, y) in listOf(0f to 0f, 4f to 48f,
                        (button.left - 1f) to 48f, button.right.toFloat() to 48f,
                        button.left.toFloat() to 0f, (button.right - 1f) to 0f,
                        ((button.left + button.right) / 2f) to 90f)) {
                        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
                        try { assertFalse("Insets/cut corners are not action targets", dock.dispatchTouchEvent(down)) }
                        finally { down.recycle() }
                    }
                    button.setSurfacePreset(theme.palette.surfacePreset)
                    motion.snap(0x80182838.toInt(), Color.WHITE)
                    dock.applySafeInsets(Insets.NONE)
                    layout(dock)
                    assertEquals(Rect(gutter, 0, dock.width - gutter, dock.height),
                        Rect(button.left, button.top, button.right, button.bottom))
                    val actual = raster(dock)
                    val actionOnly = raster(button)
                    try {
                        assertTransparentGutters(actual, button)
                        for (y in 32 until dock.height) for (x in button.left until button.right)
                            assertEquals(actionOnly.getPixel(x - button.left, y), actual.getPixel(x, y))
                    } finally { actual.recycle(); actionOnly.recycle() }
                } finally { button.close() }
            }
        }
    }

    @Test fun presetBeforeFirstColorBindAndAlreadyBoundConstructionReachTheDock() = onMain {
        assertNotNull("Keep the shared JVM constructor", NativeBottomActionDock::class.java.getConstructor(
            Context::class.java, smallthingz.ui.unit.Density::class.java, NativeMaterialButtonView::class.java))
        assertNotNull("Keep the original JVM constructor", ReverbBottomActionDock::class.java.getConstructor(
            Context::class.java, ReverbTheme::class.java, NativeMaterialButtonView::class.java))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val theme = ReverbDesign.theme(context, dark = false)
        for (legacy in listOf(false, true)) for (bindBeforeDock in listOf(false, true)) {
            val button = NativeMaterialButtonView(context, ReverbBottomActionDock.buttonStyle(theme), theme.density, theme.fontResolver) {}
            try {
                if (bindBeforeDock) button.setSurfacePreset(NativeGrainPreset(0f, .25f))
                val dock = dock(context, theme, button, legacy)
                button.setSurfacePreset(NativeGrainPreset(0f, .25f))
                assertEquals(.25f, field(dock, "grainIntensity") as Float, 0f)
                assertNull("No layer needed before any visible inset", field(dock, "grain"))
                button.bind("", true, Color.BLACK, Color.WHITE)
                layout(dock)
                raster(dock).recycle()
                assertNull("Zero insets must not allocate a grain layer", field(dock, "grain"))
                dock.applySafeInsets(Insets.of(0, 0, 0, 24))
                layout(dock)
                raster(dock).recycle()
                val grain = field(dock, "grain") as ViewGrain
                val expected = NoiseDrawable(Color.BLACK).apply { setNoiseIntensity(.25f) }
                assertEquals(expected.paintedTextSurface, grain.surfaceRange(Color.BLACK))
                button.bind("", true, Color.BLACK, Color.WHITE)
                assertSame(grain, field(dock, "grain"))
                button.setSurfacePreset(null)
                assertEquals(NoiseDrawable(Color.BLACK).apply { setNoiseIntensity(0f) }.paintedTextSurface,
                    grain.surfaceRange(Color.BLACK))
            } finally { button.close() }
        }
    }

    private fun awaitTexture() {
        NoiseTexture.prepare()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) == null && System.nanoTime() < deadline) Thread.sleep(10)
        assertNotNull("Pixel assertions require the shared texture to be ready", NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND))
    }
    private fun dock(context: Context, theme: ReverbTheme, action: NativeMaterialButtonView, legacy: Boolean): NativeBottomActionDock =
        if (legacy) ReverbBottomActionDock(context, theme, action)
        else NativeBottomActionDock(context, theme.density, action)

    private fun layout(dock: NativeBottomActionDock) {
        dock.measure(View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.EXACTLY))
        dock.layout(0, 0, 160, 96)
    }
    private fun raster(view: View): Bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
        view.draw(Canvas(it))
    }
    private fun assertTransparentGutters(bitmap: Bitmap, button: View) {
        assertTrue("Fixture must expose a left gutter", button.left > 0)
        assertTrue("Fixture must expose a right gutter", button.right < bitmap.width)
        assertTrue("Fixture must retain a nonempty action surface", button.width > 0)
        for (y in 0 until bitmap.height) {
            for (x in 0 until button.left)
                assertEquals("Left gutter ($x,$y) must not receive fill or grain", Color.TRANSPARENT, bitmap.getPixel(x, y))
            for (x in button.right until bitmap.width)
                assertEquals("Right gutter ($x,$y) must not receive fill or grain", Color.TRANSPARENT, bitmap.getPixel(x, y))
        }
    }
    private fun field(owner: Any, name: String): Any? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == name }
            if (field != null) { field.isAccessible = true; return field.get(owner) }
            type = type.superclass
        }
        error("Missing field $name")
    }
    private fun onMain(block: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }
}
