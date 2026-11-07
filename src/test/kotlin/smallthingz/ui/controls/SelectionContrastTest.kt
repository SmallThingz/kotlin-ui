package smallthingz.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class SelectionContrastTest {
    @Test fun defaultInkHandlesAccentFillTintedFillAndTranslucentCandidates() {
        for (surface in listOf(-1, 0xff181818.toInt())) {
            for (fill in listOf(0xff3458a0.toInt(), 0xffdbe5ff.toInt(), 0xffa9c0ff.toInt(), 0x803458a0.toInt())) {
                val ink = selectionForeground(fill, surface, fill, 0x40202020)
                assertEquals(255, ink ushr 24)
                assertTrue(contrast(ink, composite(fill, surface, fill ushr 24)) >= 4.5)
            }
        }
    }

    @Test fun everyFillOpacityAndOverlappingStatesRemainReadableAcrossPaletteReassignment() {
        val palettes = listOf(intArrayOf(0xfffafafa.toInt(), 0xff3458a0.toInt(), -1, 0xff606060.toInt()),
            intArrayOf(0xff181818.toInt(), 0xffa9c0ff.toInt(), 0xff182950.toInt(), 0xffaaaaaa.toInt()))
        for (p in palettes) {
            val policy = SelectionContrast(p[0], p[1], p[3], p[2], p[3])
            assertEquals(p[3], policy.foreground(0))
            assertEquals(p[2], policy.foreground(p[1]))
            for (oldFill in palettes.map { it[1] }) for (alpha in 0..255) {
                val fill = oldFill and 0xffffff or (alpha shl 24)
                val ink = policy.foreground(fill)
                var background = composite(fill, p[0], alpha)
                assertTrue(contrast(ink, background) >= 4.5)
                repeat(2) {
                    background = composite(policy.indicationColor, background, 26)
                    assertTrue(contrast(ink, background) >= 4.5)
                }
            }
        }
    }

    @Test fun chromaticIndicationMustPassOneLayerEvenWhenTwoLayersAlreadyPass() {
        val field = 0xfff75815.toInt()
        val indication = 0xff85c854.toInt()
        val idle = 0xff3f0166.toInt()
        val first = composite(indication, field, 26)
        val second = composite(indication, first, 26)
        assertTrue(contrast(idle, field) >= 4.5)
        assertTrue(contrast(idle, first) < 4.5)
        assertTrue(contrast(idle, second) >= 4.5)
        val policy = SelectionContrast(field, -1, indication, -0x1000000, idle)
        assertEquals(idle, policy.foreground(0))
        val correctedFirst = composite(policy.indicationColor, field, 26)
        assertTrue(contrast(idle, correctedFirst) >= 4.5)
        assertTrue(contrast(idle, composite(policy.indicationColor, correctedFirst, 26)) >= 4.5)
    }

    @Test fun staticSelectedIndicationProtectsEveryIntegerRippleOpacity() {
        val gray = 0xff747474.toInt()
        assertTrue(contrast(-1, gray) >= 4.5)
        assertTrue(contrast(-1, composite(-1, gray, 10)) < 4.5)
        for (surface in listOf(-1, -0x1000000)) {
            for (fill in listOf(gray, 0xff3458a0.toInt(), 0xffdbe5ff.toInt(), 0x80747474.toInt())) {
                val ink = selectionForeground(fill, surface, -0x1000000, -1)
                for (preferred in listOf(-1, -0x1000000, 0xff85c854.toInt())) {
                    val indication = selectionIndication(fill, surface, ink, preferred)
                    val background = composite(fill, surface, fill ushr 24)
                    for (alpha in 0..40) assertTrue(contrast(ink, composite(indication, background, alpha)) >= 4.5)
                }
            }
        }
    }

    @Test fun logicalTransparentSelectionQualifiesLayersAgainstSelectedInk() {
        val blue = 0xff3458a0.toInt()
        val oldDoubleLayer = composite(-0x1000000, composite(-0x1000000, -1, 26), 26)
        assertTrue(contrast(blue, oldDoubleLayer) < 4.5)
        for (transparent in listOf(0, 0x00123456, 0x00dbe5ff)) {
            val policy = SelectionContrast(-1, transparent, -0x1000000, blue, -0x1000000)
            assertEquals(blue, policy.foreground(transparent, selectedWhenTransparent = true))
            val one = composite(policy.indicationColor, -1, 26)
            assertTrue(contrast(blue, one) >= 4.5)
            assertTrue(contrast(blue, composite(policy.indicationColor, one, 26)) >= 4.5)
            assertEquals(-0x1000000, policy.foreground(transparent, selectedWhenTransparent = false))
        }
    }

    private fun contrast(a: Int, b: Int): Double {
        val first = srgbLuminance(a); val second = srgbLuminance(b)
        return (max(first, second) + .05) / (min(first, second) + .05)
    }
    private fun composite(fill: Int, surface: Int, alpha: Int): Int {
        fun channel(shift: Int) = (((fill ushr shift and 255) * alpha + (surface ushr shift and 255) * (255 - alpha) + 127) / 255) shl shift
        return -0x1000000 or channel(16) or channel(8) or channel(0)
    }
}
