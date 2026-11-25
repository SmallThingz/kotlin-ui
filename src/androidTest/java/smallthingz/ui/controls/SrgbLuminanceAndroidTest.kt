package smallthingz.ui.controls

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Modifier
import kotlin.math.pow

/** Numeric qualification on the Android runtime; no views, input, or global settings changes. */
@RunWith(AndroidJUnit4::class)
class SrgbLuminanceAndroidTest {
    @Test fun everyRuntimeTableEntryMatchesTheOriginalChannelBits() {
        // Inspect the facade in the same library classloader as the production caller.
        // Test-only reflection keeps the table private; never write through this reference.
        val field = Class.forName("smallthingz.ui.controls.SelectionContrastKt", true,
            SelectionContrast::class.java.classLoader).getDeclaredField("SRGB_CHANNEL_LUMINANCE")
        assertTrue("Table remains private", Modifier.isPrivate(field.modifiers))
        assertTrue("Table is shared by the classloader", Modifier.isStatic(field.modifiers))
        assertTrue("Table reference remains final", Modifier.isFinal(field.modifiers))
        assertEquals(DoubleArray::class.java, field.type)
        field.isAccessible = true
        val table = field.get(null) as DoubleArray
        assertEquals(256, table.size)
        for (channel in 0..255) {
            val value = channel / 255.0
            val original = if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
            assertEquals("channel=$channel", original.toRawBits(), table[channel].toRawBits())
        }
        srgbLuminance(0xff123456.toInt())
        assertSame("Calls share the initialized array", table, field.get(null))
    }

    @Test fun fullArgbLuminanceMatchesOriginalBitsAcrossRepresentativeColors() {
        // Include both sides of the transfer-function branch (10/11) in every RGB position.
        val edges = intArrayOf(0, 1, 9, 10, 11, 12, 127, 128, 254, 255)
        val alphas = intArrayOf(0, 1, 127, 128, 254, 255)
        for (r in edges) for (g in edges) for (b in edges) for (alpha in alphas) {
            assertOriginal((alpha shl 24) or (r shl 16) or (g shl 8) or b)
        }
        var argb = 0x13579bdf
        repeat(4096) {
            argb = argb * 1664525 + 1013904223
            assertOriginal(argb)
        }
    }

    @Test fun alphaIsIgnoredForEveryPrefixAcrossRepresentativeRgb() {
        val colors = intArrayOf(0, 0xffffff, 0x010a0b, 0x0b0a01, 0x0a010b, 0x808080,
            0x3458a0, 0xdbe5ff, 0xa9c0ff, 0x747474, 0x85c854, 0x454349,
            0xb5b0b9, 0xb8a4eb, 0xf75815, 0x3f0166)
        for (rgb in colors) {
            val expected = originalLuminance(rgb).toRawBits()
            for (alpha in 0..255) {
                assertEquals("rgb=$rgb alpha=$alpha", expected,
                    srgbLuminance((alpha shl 24) or rgb).toRawBits())
            }
        }
    }

    private fun assertOriginal(argb: Int) =
        assertEquals("argb=$argb", originalLuminance(argb).toRawBits(), srgbLuminance(argb).toRawBits())

    // Frozen d3aced implementation, including operation order; no epsilon comparison.
    private fun originalLuminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val value = (argb ushr shift and 255) / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
}
