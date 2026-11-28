package smallthingz.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
import smallthingz.ui.graphics.compositeOver
import smallthingz.ui.graphics.toArgb
import smallthingz.ui.reverb.ReverbEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeCalibrationActionGrainHostTest {
    @Test fun filledProgressOwnsForegroundToneAndOneThemeGrainLayerAcrossStates() {
        awaitTexture()
        val context = RuntimeEnvironment.getApplication()
        val defaults = NativeUiAppearance.Default
        for (appearance in listOf(defaults, defaults.copy(calibration = defaults.calibration.copy(
            actionOpacity = .31f, disabledActionOpacity = .09f)))) {
            val initial = ReverbEnvironment.withTestAppearance(context, true, appearance)
            NativeProgressActionView(context, initial, { null }, progressSurface = true, action = {}).use { action ->
                val visual = action.getChildAt(0) as NativeMaterialButtonView
                for (dark in listOf(true, false, true)) {
                    val environment = ReverbEnvironment.withTestAppearance(context, dark, appearance)
                    val palette = environment.palette
                    assertNotEquals("Fixture distinguishes group from page fill", palette.background, palette.foregroundFill)
                    for ((enabled, running) in listOf(true to false, false to false, false to true)) {
                        action.bind("", enabled, environment)
                        // A zero progress fraction exercises running color selection without overlay paint.
                        action.bindProgress(0f, running)
                        place(action)
                        assertSame(visual, action.getChildAt(0))
                        val opacity = if (enabled || running) appearance.calibration.actionOpacity
                            else appearance.calibration.disabledActionOpacity
                        val expectedFill = Color(palette.primary).copy(alpha = opacity)
                            .compositeOver(Color(palette.foregroundFill)).toArgb()
                        assertEquals(expectedFill, visual.sampledContainerColor)
                        assertEquals(255, AndroidColor.alpha(visual.sampledContainerColor))
                        val intensity = requireNotNull(palette.surfacePreset).foregroundGrain
                        assertEquals(intensity, visual.sampledContainerGrainIntensity, 0f)

                        val actual = pixels(AndroidColor.MAGENTA, visual::draw)
                        assertArrayEquals("Parent paint cannot add a second grain layer",
                            actual, pixels(AndroidColor.GREEN, visual::draw))
                        val expected = NoiseDrawable(expectedFill).apply {
                            setNoiseIntensity(intensity)
                            setBounds(0, 0, WIDTH, HEIGHT)
                        }
                        assertArrayEquals("Use the theme grain once at full surface coverage",
                            pixels(AndroidColor.TRANSPARENT, expected::draw), actual)
                        val flat = environment.withPalette(palette.copy(surfacePreset = null))
                        action.bind("", enabled, flat)
                        action.bindProgress(0f, running)
                        val flatPixels = pixels(AndroidColor.MAGENTA, visual::draw)
                        assertTrue("Flat fill retains the exact configured tone", flatPixels.all { it == expectedFill })
                        assertTrue("Grain remains visible, including disabled Light fill",
                            actual.indices.count { actual[it] != flatPixels[it] } > actual.size / 20)
                        action.bind("", enabled, environment)
                        action.bindProgress(0f, running)
                        assertArrayEquals("Rebinding restores deterministic grain", actual,
                            pixels(AndroidColor.MAGENTA, visual::draw))
                    }
                }
            }
        }
    }

    @Test fun transparentActionsKeepRevealingTheirParentInBothThemesAndEnabledStates() {
        val context = RuntimeEnvironment.getApplication()
        for (dark in listOf(false, true)) {
            val environment = ReverbEnvironment(context, dark)
            NativeProgressActionView(context, environment, { null }, action = {}).use { action ->
                val visual = action.getChildAt(0) as NativeMaterialButtonView
                for (enabled in listOf(true, false, true)) {
                    action.bind("", enabled, environment)
                    place(action)
                    assertEquals(AndroidColor.TRANSPARENT, visual.sampledContainerColor)
                    for (underlay in listOf(AndroidColor.TRANSPARENT, AndroidColor.MAGENTA, AndroidColor.GREEN)) {
                        assertTrue("Transparent action must add neither fill nor grain",
                            pixels(underlay, visual::draw).all { it == underlay })
                    }
                }
            }
        }
    }

    private fun place(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, WIDTH, HEIGHT)
    }

    private fun pixels(underlay: Int, draw: (Canvas) -> Unit): IntArray {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(underlay)
            draw(canvas)
            // Blank interior strip excludes the rounded edge and empty label's geometry.
            IntArray(32 * 4).also { bitmap.getPixels(it, 0, 32, WIDTH / 2 - 16, 4, 32, 4) }
        } finally { bitmap.recycle() }
    }

    private fun awaitTexture() {
        NoiseTexture.prepare()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) == null && System.nanoTime() < deadline) Thread.sleep(5)
        assertNotNull("Texture preparation completed", NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND))
    }

    private companion object {
        const val WIDTH = 160
        const val HEIGHT = 64
    }
}
