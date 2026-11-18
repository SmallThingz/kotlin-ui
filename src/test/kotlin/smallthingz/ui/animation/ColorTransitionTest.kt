package smallthingz.ui.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import smallthingz.ui.animation.core.FastOutSlowInEasing
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.lerp
import smallthingz.ui.graphics.toArgb

class ColorTransitionTest {
    private val black=0xFF000000.toInt()
    private val white=0xFFFFFFFF.toInt()

    @Test fun packedOklabEndpointsMatchArgbInterpolationAcrossFramesAndRetargets() {
        val random=kotlin.random.Random(62)
        var start=IntArray(32) { random.nextInt() }
        val state=ColorTransition(start)
        var time=0L
        repeat(12) {
            val target=IntArray(start.size) { index ->
                if(index%4==0)start[index] else random.nextInt()
            }
            state.retarget(target,time)
            val frames=if(it%2==0)79 else 159
            for(step in 1..frames) {
                val fraction=FastOutSlowInEasing.transform(step/160f)
                state.frame(time+step*1_000_000L)
                for(index in start.indices) {
                    val expected=lerp(Color(start[index]),Color(target[index]),fraction).toArgb()
                    assertEquals("transition $it, frame $step, channel $index",expected,state.color(index))
                }
            }
            time+=frames*1_000_000L
            if(it%2==0) {
                val fraction=FastOutSlowInEasing.transform(frames/160f)
                start=IntArray(start.size) { index ->
                    lerp(Color(start[index]),Color(target[index]),fraction).toArgb()
                }
            } else {
                time+=1_000_000L
                state.frame(time)
                start=target
                for(index in start.indices)assertEquals(start[index],state.color(index))
            }
        }
    }

    @Test fun initialAndCompletedTuplesAreExact() {
        val state=ColorTransition(intArrayOf(black,white))
        assertFalse(state.running)
        assertEquals(black,state.color(0))
        state.retarget(intArrayOf(white,black),0)
        assertFalse(state.isInterpolating)
        state.frame(ColorTransition.DURATION_NANOS/2)
        assertTrue(state.isInterpolating)
        state.frame(ColorTransition.DURATION_NANOS)
        assertFalse(state.running)
        assertFalse(state.isInterpolating)
        assertEquals(white,state.color(0));assertEquals(black,state.color(1))
    }

    @Test fun repeatedTargetDoesNotRestartAndCallerMutationCannotChangeIt() {
        val target=intArrayOf(white)
        val state=ColorTransition(intArrayOf(black))
        state.retarget(target,0);target[0]=black
        state.retarget(intArrayOf(white),80_000_000)
        state.frame(160_000_000)
        assertFalse(state.running);assertEquals(white,state.color(0))
    }

    @Test fun disabledAnimationsSnapEvenForAnAlreadyRunningTarget() {
        val state=ColorTransition(intArrayOf(black))
        state.retarget(intArrayOf(white),0)
        state.frame(40_000_000)
        assertTrue(state.running)
        state.retarget(intArrayOf(white),50_000_000,0f)
        assertFalse(state.running);assertEquals(white,state.color(0))
    }

    @Test fun retargetSamplesTheDisplayedTupleWithoutAJump() {
        val reference=ColorTransition(intArrayOf(black))
        val state=ColorTransition(intArrayOf(black))
        reference.retarget(intArrayOf(white),0);state.retarget(intArrayOf(white),0)
        reference.frame(70_000_000)
        state.retarget(intArrayOf(black),70_000_000)
        assertEquals(reference.color(0),state.color(0))
        state.frame(230_000_000)
        assertEquals(black,state.color(0));assertFalse(state.running)
    }

    @Test fun staleVsyncAfterRetargetDoesNotAdvanceTheNextFrameEarly() {
        val expected=ColorTransition(intArrayOf(black))
        val actual=ColorTransition(intArrayOf(black))
        expected.retarget(intArrayOf(white),100_000_000)
        actual.retarget(intArrayOf(white),100_000_000)
        actual.frame(90_000_000)
        actual.frame(120_000_000);expected.frame(120_000_000)
        assertEquals(expected.color(0),actual.color(0))
        actual.retarget(intArrayOf(black),110_000_000)
        expected.retarget(intArrayOf(black),120_000_000)
        actual.frame(140_000_000);expected.frame(140_000_000)
        assertEquals(expected.color(0),actual.color(0))
    }

    @Test fun invalidTupleDoesNotAdvanceOrMutateTheCurrentAnimation() {
        val state=ColorTransition(intArrayOf(black))
        state.retarget(intArrayOf(white),0)
        try {state.retarget(intArrayOf(white,black),120_000_000);throw AssertionError("Expected invalid tuple")}
        catch(expected:IllegalArgumentException) { }
        assertEquals(black,state.color(0));assertTrue(state.running)
    }
}
