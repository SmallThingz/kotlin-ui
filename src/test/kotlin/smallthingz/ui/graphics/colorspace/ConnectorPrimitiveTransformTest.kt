package smallthingz.ui.graphics.colorspace

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test
import smallthingz.ui.graphics.Color

class ConnectorPrimitiveTransformTest {
    private val intents = listOf(
        RenderIntent.Perceptual, RenderIntent.Relative, RenderIntent.Saturation, RenderIntent.Absolute,
    )
    private val spaces = listOf(ColorSpaces.Srgb, ColorSpaces.Unspecified, ColorSpaces.Oklab)

    @Test fun denseRegisteredPaletteMatchesArrayTransformExactly() {
        for (source in spaces) {
            for (destination in spaces) {
                if (source === destination) continue
                for (intent in intents) {
                    val connector = source.connect(destination, intent)
                    for (r in 0..16) for (g in 0..16) for (b in 0..16) {
                        val color = Color(
                            component(source, 0, r), component(source, 1, g), component(source, 2, b),
                            ((r + g + b) % 17) / 16f, source,
                        )
                        assertMatchesArray(connector, color)
                    }
                }
            }
        }
    }

    @Test fun rawHalfBoundariesAndPayloadsMatchArrayTransformExactly() {
        for (intent in intents) {
            val connector = ColorSpaces.Oklab.connect(ColorSpaces.Srgb, intent)
            for (half in 0..0xffff) {
                // Exercise all finite encodings, signed zero, infinities and NaNs before clamping.
                val raw = (half.toULong() shl 48) or
                    ((half xor 0x8000).toULong() shl 32) or
                    ((half xor 0x3555).toULong() shl 16) or
                    ((half and 0x3ff).toULong() shl 6) or 19UL
                assertMatchesArray(connector, Color(raw))
            }
        }
        val random = Random(0x58595a434f4cL)
        for (source in spaces) for (destination in spaces) {
            if (source === destination) continue
            for (intent in intents) {
                val connector = source.connect(destination, intent)
                repeat(1024) {
                    val raw = (random.nextLong().toULong() and 0x3fUL.inv()) or source.id.toULong()
                    assertMatchesArray(connector, Color(raw))
                }
            }
        }
    }

    private fun component(space: ColorSpace, channel: Int, step: Int): Float =
        space.getMinValue(channel) + (space.getMaxValue(channel) - space.getMinValue(channel)) * (step / 16f)

    private fun assertMatchesArray(connector: Connector, color: Color) {
        val expected = runCatching {
            val values = connector.transform(floatArrayOf(color.red, color.green, color.blue))
            Color(values[0], values[1], values[2], color.alpha, connector.destination).value
        }
        val actual = runCatching { connector.transformToColor(color).value }
        assertEquals(expected.getOrNull(), actual.getOrNull())
        assertEquals(expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass)
        assertEquals(expected.exceptionOrNull()?.message, actual.exceptionOrNull()?.message)
    }
}
