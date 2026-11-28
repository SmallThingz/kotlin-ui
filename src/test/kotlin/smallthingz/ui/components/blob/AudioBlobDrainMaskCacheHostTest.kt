package smallthingz.ui.components.blob

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNativeComposeShader
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [AudioBlobDrainMaskCacheHostTest.ComposeOperands::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioBlobDrainMaskCacheHostTest {
    private data class Palette(val primary: Int, val tertiary: Int, val paused: Int)

    private val palettes = listOf(
        Palette(0xffad87ff.toInt(), 0xffdd96b8.toInt(), 0xff30263b.toInt()),
        Palette(0xff987eef.toInt(), 0xffcb8cae.toInt(), 0xff3a2f44.toInt()),
        Palette(0xff806bd9.toInt(), 0xffb880a6.toInt(), 0xff43374c.toInt()),
    )
    private val bands = floatArrayOf(.6f, .4f, .5f, .3f, .7f, .5f, .3f, .2f)

    @Test fun fixedSizePaletteChangesReuseActualDrainOperandAndResizeRebuildsIt() {
        val renderer = AudioReactiveBlobView.FallbackRenderer()
        var priorSizeMask: RadialGradient? = null
        // A width-only resize also changes the mask center even if the radius is unchanged.
        for ((width, height) in listOf(112 to 112, 144 to 112, 508 to 508)) {
            renderer.resize(width, height)
            val masks = palettes.map { palette ->
                assertTrue(renderer.setPalette(palette.primary, palette.tertiary, palette.paused))
                render(renderer, width, height)
                drainOperand(renderer)
            }

            priorSizeMask?.let { assertNotSame("Resize must replace the drain operand", it, masks.first()) }
            for (mask in masks.drop(1)) {
                assertSame("Fixed-size palette changes rebuilt the radial drain operand at ${width}x$height",
                    masks.first(), mask)
            }
            priorSizeMask = masks.first()
        }
    }

    @Test fun compactAndLargeDrainPixelsMatchFreshBaselineGradientForEveryPalette() {
        val renderer = AudioReactiveBlobView.FallbackRenderer()
        for (size in listOf(112, 508)) {
            renderer.resize(size, size)
            for (palette in palettes) {
                renderer.setPalette(palette.primary, palette.tertiary, palette.paused)
                val actual = render(renderer, size, size)
                val baseline = render(renderer, size, size, baselinePalette = palette)
                assertFalse("The fixture must exercise visible silence drain",
                    actual.contentEquals(render(renderer, size, size, silence = 0f)))
                assertArrayEquals("Active drain pixels at ${size}x$size for $palette", baseline, actual)
            }
        }
    }

    private fun drainOperand(renderer: AudioReactiveBlobView.FallbackRenderer): RadialGradient {
        val field = renderer.javaClass.getDeclaredField("silencePaint").apply { isAccessible = true }
        val composite = (field.get(renderer) as Paint).shader as ComposeShader
        return Shadow.extract<ComposeOperands>(composite).drain
    }

    private fun render(
        renderer: AudioReactiveBlobView.FallbackRenderer,
        width: Int,
        height: Int,
        baselinePalette: Palette? = null,
        silence: Float = .9f,
    ): IntArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = if (baselinePalette == null) Canvas(bitmap) else object : Canvas(bitmap) {
            private var paths = 0

            override fun drawPath(path: Path, paint: Paint) {
                if (++paths == 3) {
                    // Keep the actual pose and alpha. Replace only the drain pass with the original recipe.
                    val reference = Paint(paint).apply {
                        val muted = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                            silenceBlobMutedColor(baselinePalette.primary), silenceBlobMutedColor(baselinePalette.tertiary),
                            Shader.TileMode.CLAMP)
                        val stops = floatArrayOf(0f, .175f, .35f, .525f, .7f, 1f)
                        val drain = RadialGradient(width * .5f, height * .5f, minOf(width, height) * .33f,
                            IntArray(stops.size) { silenceBlobDrainMaskColor(stops[it]) }, stops, Shader.TileMode.CLAMP)
                        shader = ComposeShader(muted, drain, PorterDuff.Mode.DST_IN)
                    }
                    super.drawPath(path, reference)
                } else {
                    super.drawPath(path, paint)
                }
            }
        }
        bitmap.eraseColor(Color.TRANSPARENT)
        renderer.draw(canvas, width, height, .7f, .35f, .2f, bands, 1f, 1f, silence)
        return IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
    }

    /** Observe the public constructor operands while keeping Robolectric's native shader path. */
    @Implements(value = ComposeShader::class, minSdk = 26, callNativeMethodsByDefault = true)
    class ComposeOperands : ShadowNativeComposeShader() {
        @RealObject private lateinit var shader: ComposeShader
        lateinit var drain: RadialGradient
            private set

        @Implementation
        protected fun __constructor__(first: Shader, second: Shader, mode: PorterDuff.Mode) {
            Shadow.invokeConstructor(ComposeShader::class.java, shader,
                ClassParameter.from(Shader::class.java, first),
                ClassParameter.from(Shader::class.java, second),
                ClassParameter.from(PorterDuff.Mode::class.java, mode))
            if (second is RadialGradient) drain = second
        }
    }
}
