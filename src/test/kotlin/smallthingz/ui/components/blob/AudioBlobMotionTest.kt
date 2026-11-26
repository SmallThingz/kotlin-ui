// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioBlobMotionTest {
    @Test fun silenceCalmsMotionAndDigitalZeroHasNoNewMotion() {
        assertEquals(0f, audioBlobMotionEnergy(0f, 0f), 0f)
        val audible = audioBlobMotionEnergy(.5f, 0f)
        val suppressed = audioBlobMotionEnergy(.5f, 1f)
        assertEquals(audible * .08f, suppressed, .00001f)
        assertEquals(0f, audioBlobFold(1f, 1f, audioBlobMotionEnergy(0f, 0f), 1f, 1f), 0f)
    }

    @Test fun quietOnsetIsGentleAndResponseIsMonotoneAndBounded() {
        assertTrue(audioBlobMotionEnergy(.04f, 0f) in 0f..0.005f)
        assertEquals(.5f, audioBlobMotionEnergy(.5f, 0f), .00001f)
        var previous = 0f
        for (step in -1..101) {
            val energy = audioBlobMotionEnergy(step / 100f, 0f)
            assertTrue(energy in previous..1f)
            previous = energy
        }
        assertEquals(1f, previous, 0f)
    }

    @Test fun audibleMaterialEvolvesAndStaysInsideExistingFootprint() {
        var largestChange = 0f
        for (low in listOf(0f, .2f, 1f)) for (high in listOf(0f, .2f, 1f)) {
            val energy = audioBlobMotionEnergy(1f, 0f)
            for (step in 0..360) {
                val angle = step * PI.toFloat() / 180f
                val a = audioBlobFold(sin(angle * 3f), sin(angle * 7f + 1.8f), energy, low, high)
                val b = audioBlobFold(sin(angle * 3f + 1.3f * .78f),
                    sin(angle * 7f - 1.3f * .39f + 1.8f), energy, low, high)
                assertTrue(abs(a) <= .09601f && abs(b) <= .09601f)
                // Largest base radius + bounded fold + glow stays within the .495 clip.
                assertTrue(.348f + abs(a) + .024f < .495f)
                largestChange = maxOf(largestChange, abs(a - b))
            }
        }
        assertTrue(largestChange > .03f)
    }
}
