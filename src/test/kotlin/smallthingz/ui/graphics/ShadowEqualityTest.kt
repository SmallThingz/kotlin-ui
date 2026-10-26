package smallthingz.ui.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowEqualityTest {
    @Test fun signedZeroBlurRadiiProduceTheSameSetEntryAndHash() {
        val positive = Shadow(blurRadius = 0f)
        val negative = Shadow(blurRadius = -0f)

        assertEquals(positive, negative)
        assertEquals(negative, positive)
        assertEquals(positive.hashCode(), negative.hashCode())
        assertEquals(1, hashSetOf(positive, negative).size)
        assertEquals("shadow", hashMapOf(positive to "shadow")[negative])
        assertEquals((-0f).toRawBits(), negative.blurRadius.toRawBits())
    }

    @Test fun nonzeroCopiesRemainInterchangeableMapKeys() {
        val original = Shadow(blurRadius = 3.5f)
        val copy = original.copy()

        assertEquals(original, copy)
        assertEquals(original.hashCode(), copy.hashCode())
        assertEquals("copy", hashMapOf(original to "copy")[copy])
        assertFalse(original == original.copy(blurRadius = 4f))
    }

    @Test fun nanBlurEqualityPolicyRemainsUnchanged() {
        val original = Shadow(blurRadius = Float.NaN)

        assertTrue(original == original)
        assertFalse(original == original.copy())
        assertTrue(original.blurRadius.isNaN())
    }
}
