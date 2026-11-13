package smallthingz.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import smallthingz.ui.animation.ColorTransition

class TextSurfaceContrastTest {
    @Test fun opposingThemeRolesStayReadableOnFlatPaintInBothDirections() {
        val endpoints = arrayOf(
            intArrayOf(0xfffef7ff.toInt(), 0xff141218.toInt(), 0xff1d1b20.toInt(), 0xffe6e0e9.toInt()),
            intArrayOf(0xfff7f2fa.toInt(), 0xff1d1b20.toInt(), 0xff49454f.toInt(), 0xffcac4d0.toInt()),
        )
        for (colors in endpoints) for (reverse in listOf(false, true)) {
            val start = if (reverse) intArrayOf(colors[1], colors[3]) else intArrayOf(colors[0], colors[2])
            val target = if (reverse) intArrayOf(colors[0], colors[2]) else intArrayOf(colors[1], colors[3])
            val motion = ColorTransition(start)
            motion.retarget(target, 0L)
            var oldCrossing = false
            for (ms in 0..160) {
                motion.frame(ms * 1_000_000L)
                val fill = motion.color(0)
                val raw = motion.color(1)
                oldCrossing = oldCrossing || contrast(raw, fill) < 1.1
                val readable = textSurfaceForeground(textSurfaceRange(fill), raw)
                assertTrue("reverse=$reverse ms=$ms", contrast(readable, fill) >= 4.5)
            }
            assertTrue("The same real transition must expose the former near-invisible crossing", oldCrossing)
        }
    }

    @Test fun grainUsesBestAchievableBoundWithoutPretendingEveryPixelIsTheBase() {
        // Actual alpha17 black/white grain over neutral117; floor/ceiling also bound raster rounding.
        val darkest = 0xff6d6d6d.toInt()
        val lightest = 0xff7f7f7f.toInt()
        val range = textSurfaceRange(darkest, lightest)
        val chosen = textSurfaceForeground(range, 0xff757575.toInt())
        assertEquals(0xff000000.toInt(), chosen)
        val achieved = minOf(contrast(chosen, darkest), contrast(chosen, lightest))
        assertTrue(achieved > 4.0 && achieved < 4.5)
        assertTrue(achieved > minOf(contrast(-1, darkest), contrast(-1, lightest)))
        assertEquals(chosen, textSurfaceForeground(textSurfaceRange(lightest, darkest), 0xff757575.toInt()))
    }

    @Test fun readablePreferredInkIsPreserved() {
        val preferred = 0xff303038.toInt()
        assertEquals(preferred, textSurfaceForeground(textSurfaceRange(0xffdddddd.toInt(), -1), preferred))
        assertEquals(-1, textSurfaceForeground(textSurfaceRange(0xff111111.toInt(), 0xff333333.toInt()), -1))
    }

    @Test fun unknownSurfaceAndIntentionalOpacityKeepCallerColor() {
        val ink = 0xff777777.toInt()
        assertEquals(ink, textSurfaceForeground(0L, ink))
        assertEquals(ink, textSurfaceForeground(textSurfaceRange(0x7f777777, -1), ink))
        assertEquals(0x61777777, textSurfaceForeground(textSurfaceRange(0xff777777.toInt()), 0x61777777))
        assertEquals(0, textSurfaceForeground(textSurfaceRange(-1), 0))
    }

    private fun contrast(first: Int, second: Int): Double {
        val a = srgbLuminance(first)
        val b = srgbLuminance(second)
        return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
    }
}
