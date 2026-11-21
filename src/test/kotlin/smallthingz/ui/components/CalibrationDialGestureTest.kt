package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CalibrationDialGestureTest {
    private val range = NativeArcDialRange(-6f, 18f, 6f)

    @Test fun clockwiseAndCounterclockwiseFollowTheKnobThroughoutTheArc() {
        for(value in listOf(-6f,-2f,2f,6f,10f,14f,18f)) {
            val angle=Math.toRadians(135.0+270.0*(value.toDouble()+6.0)/24.0)
            val dx=(-sin(angle)*20).toFloat()
            val dy=(cos(angle)*20).toFloat()
            assertEquals(1f,range.delta(value,dx,dy,2f),.00001f)
            assertEquals(-1f,range.delta(value,-dx,-dy,2f),.00001f)
        }
    }

    @Test fun inwardAndOutwardMovementDoNotChangeTheValue() {
        for(value in -60..180) {
            val current=value/10f
            // Shift in Double: Float addition invents a different angle at fractional positions.
            val angle=Math.toRadians(135.0+270.0*(current.toDouble()+6.0)/24.0)
            val dx=(cos(angle)*24).toFloat()
            val dy=(sin(angle)*24).toFloat()
            assertEquals(0f,range.delta(current,dx,dy,1f),0f)
            assertEquals(0f,range.delta(current,-dx,-dy,1f),0f)
        }
    }

    @Test fun changingTheKnobPositionChangesWhichDirectionIncreases() {
        assertEquals(1f,range.delta(6f,10f,0f,1f),.00001f)
        assertEquals(0f,range.delta(-2f,10f,0f,1f),0f)
        assertEquals(1f,range.delta(-2f,0f,-10f,1f),.00001f)
        assertEquals(-1f,range.delta(14f,0f,-10f,1f),.00001f)
    }

    @Test fun rangeEndpointsAndDefaultMapToArcEndpointsAndCenter() {
        assertEquals(0.0,range.fraction(-6f),0.0)
        assertEquals(.5,range.fraction(6f),0.0)
        assertEquals(1.0,range.fraction(18f),0.0)
        assertEquals(0.0,range.fraction(-7f),0.0)
        assertEquals(1.0,range.fraction(19f),0.0)
    }

    @Test fun invalidInputCannotMoveTheDial() {
        for(density in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY)) {
            assertEquals(0f,range.delta(6f,10f,0f,density),0f)
        }
        assertEquals(0f,range.delta(Float.NaN,10f,0f,1f),0f)
        assertEquals(0f,range.delta(6f,Float.POSITIVE_INFINITY,0f,1f),0f)
    }
}
