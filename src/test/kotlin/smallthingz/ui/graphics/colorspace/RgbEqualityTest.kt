package smallthingz.ui.graphics.colorspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RgbEqualityTest {
    @Test fun customTransferFunctionsDistinguishColorSpaces() {
        val linear = DoubleFunction { it }
        val squared = DoubleFunction { it * it }
        val original = custom(linear, linear)
        val differentOetf = custom(squared, linear)
        val differentEotf = custom(linear, squared)

        for (different in listOf(differentOetf, differentEotf)) {
            assertFalse(original == different)
            assertFalse(different == original)
        }
        assertEquals(3, setOf(original, differentOetf, differentEotf).size)
        val labels = mapOf(original to "linear", differentOetf to "encoded", differentEotf to "decoded")
        assertEquals("linear", labels[original])
        assertEquals("encoded", labels[differentOetf])
        assertEquals("decoded", labels[differentEotf])
    }

    @Test fun sharedCustomFunctionsProduceInterchangeableMapKeys() {
        val encode = DoubleFunction { it * it }
        val decode = DoubleFunction { kotlin.math.sqrt(it) }
        val first = custom(encode, decode)
        val second = custom(encode, decode)

        assertNotSame(first, second)
        assertEquals(first, second)
        assertEquals(second, first)
        assertEquals(first.hashCode(), second.hashCode())
        val labels = hashMapOf(first to "first")
        assertEquals("first", labels[second])
        labels[second] = "replacement"
        assertEquals(1, labels.size)
        assertEquals("replacement", labels[first])
    }

    @Test fun parameterBackedSpacesKeepValueEquality() {
        val first = Rgb("test RGB", ColorSpaces.SrgbPrimaries, Illuminant.D65, 2.2)
        val second = Rgb("test RGB", ColorSpaces.SrgbPrimaries, Illuminant.D65,
            TransferParameters(2.2, 1.0, 0.0, 0.0, 0.0))
        val different = Rgb("test RGB", ColorSpaces.SrgbPrimaries, Illuminant.D65, 2.4)

        assertEquals(first, second)
        assertEquals(second, first)
        assertEquals(first.hashCode(), second.hashCode())
        assertFalse(first == different)
        assertEquals("value", hashMapOf(first to "value")[second])
    }

    @Test fun customAndParameterBackedEqualityIsSymmetricWithSharedFunctions() {
        val parameterBacked = Rgb("test RGB", ColorSpaces.SrgbPrimaries, Illuminant.D65, 2.2)
        val custom = custom(parameterBacked.oetfOrig, parameterBacked.eotfOrig)

        assertFalse(custom == parameterBacked)
        assertFalse(parameterBacked == custom)
    }

    @Test fun registeredRgbSpacesRetainTheirEqualityAndHashContract() {
        for (space in listOf(ColorSpaces.Srgb, ColorSpaces.Unspecified)) {
            val equivalent = Rgb(space.name, space.getPrimaries(), space.whitePoint,
                requireNotNull(space.transferParameters), space.id)
            assertTrue(space == equivalent)
            assertTrue(equivalent == space)
            assertEquals(space.hashCode(), equivalent.hashCode())
            assertEquals("registered", hashMapOf(space to "registered")[equivalent])
        }
    }

    private fun custom(oetf: DoubleFunction, eotf: DoubleFunction): Rgb = Rgb(
        name = "test RGB",
        primaries = ColorSpaces.SrgbPrimaries,
        whitePoint = Illuminant.D65,
        transform = null,
        oetf = oetf,
        eotf = eotf,
        min = 0f,
        max = 1f,
        transferParameters = null,
        id = ColorSpace.MinId,
    )
}
