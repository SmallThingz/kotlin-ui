package smallthingz.ui.graphics

import smallthingz.ui.graphics.colorspace.ColorSpaces
import smallthingz.ui.graphics.colorspace.connect
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeColorEncodingTest {
    @Test fun everyHalfEncodingSurvivesFloatRoundTripWithCanonicalNaNs() {
        for (bits in 0..0xffff) {
            val expected = if (bits and 0x7c00 == 0x7c00 && bits and 0x03ff != 0)
                (bits and 0x8000) or 0x7e00 else bits
            assertEquals("half=$bits", expected, floatToHalf(halfToFloat(bits.toShort())).toInt() and 0xffff)
        }
    }

    @Test fun argbStorageAndIdentityConversionRetainEveryChannelByte() {
        for (value in 0..255) {
            val argb = (value shl 24) or ((255 - value) shl 16) or (value shl 8) or (value xor 0x55)
            val color = Color(argb)
            assertEquals(argb, color.toArgb())
            assertEquals(color.value, color.convert(ColorSpaces.Srgb).value)
            assertEquals(color.value, Color(color.red, color.green, color.blue, color.alpha).value)
        }
    }

    @Test fun unspecifiedIsDistinctFromTransparentAndOklabIdentityRetainsPackedBits() {
        assertEquals(0x10UL, Color.Unspecified.value)
        assertEquals(0UL, Color.Transparent.value)
        val color = Color(0.526f, -0.12f, 0.18f, 0.37f, ColorSpaces.Oklab)
        assertEquals(color.value, color.convert(ColorSpaces.Oklab).value)
        assertEquals(19UL, color.value and 0x3fUL)
    }

    @Test fun identityConversionPreservesRawPayloadsInEveryRegisteredSpace() {
        val random = Random(0x434f4c4f52L)
        for (space in listOf(ColorSpaces.Srgb, ColorSpaces.Unspecified, ColorSpaces.Oklab)) {
            repeat(4096) {
                // Include arbitrary half encodings and sRGB's unused payload bits: identity
                // conversion must not normalize or reconstruct any part of the packed value.
                val color = Color((random.nextLong().toULong() and 0x3fUL.inv()) or space.id.toULong())
                assertEquals(color.value, color.convert(space).value)
            }
        }
    }

    @Test fun packedArgbMatchesConnectorWithUnusedBitsAndEveryChannelByte() {
        for (channel in 0..255) {
            val argb = (channel shl 24) or ((255 - channel) shl 16) or (channel shl 8) or (channel xor 0x55)
            for (unused in listOf(0UL, 0x40UL, 0x7fffffc0UL, 0xffffffc0UL)) {
                val color = Color((argb.toUInt().toULong() shl 32) or unused)
                assertEquals(argb, color.toArgb())
                assertEquals((color.colorSpace.connect(ColorSpaces.Srgb).transformToColor(color).value shr 32).toInt(),
                    color.toArgb())
            }
        }
    }

    @Test fun conversionResultsAndRejectionsMatchOriginalConnectorPath() {
        val random = Random(0x5041434b4544L)
        val targets = listOf(ColorSpaces.Srgb, ColorSpaces.Unspecified, ColorSpaces.Oklab)
        for (spaceId in 0..63) {
            repeat(128) {
                val color = Color((random.nextLong().toULong() and 0x3fUL.inv()) or spaceId.toULong())
                for (target in targets) {
                    val expected = runCatching { color.colorSpace.connect(target).transformToColor(color).value }
                    val actual = runCatching { color.convert(target).value }
                    assertEquals("space=$spaceId target=${target.id}", expected.getOrNull(), actual.getOrNull())
                    assertEquals(expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass)
                    assertEquals(expected.exceptionOrNull()?.message, actual.exceptionOrNull()?.message)
                }
                val expectedArgb = runCatching {
                    (color.colorSpace.connect(ColorSpaces.Srgb).transformToColor(color).value shr 32).toInt()
                }
                val actualArgb = runCatching { color.toArgb() }
                assertEquals("ARGB space=$spaceId", expectedArgb.getOrNull(), actualArgb.getOrNull())
                assertEquals(expectedArgb.exceptionOrNull()?.javaClass, actualArgb.exceptionOrNull()?.javaClass)
                assertEquals(expectedArgb.exceptionOrNull()?.message, actualArgb.exceptionOrNull()?.message)
            }
        }
    }
}
