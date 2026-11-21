package smallthingz.ui.components

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Bounds and reset belong to the caller. The arc uses the same projection for drawing and touch. */
data class NativeArcDialRange(val minimum: Float, val maximum: Float, val reset: Float) {
    internal fun fraction(value:Float):Double =
        (value.coerceIn(minimum,maximum).toDouble()-minimum)/
            (maximum-minimum)

    /** Clockwise motion follows the displayed arc; radial motion has no adjustment authority. */
    internal fun delta(value:Float,dx:Float,dy:Float,density:Float):Float {
        if(!value.isFinite()||!dx.isFinite()||!dy.isFinite()||!density.isFinite()||density<=0f)return 0f
        val angle=Math.toRadians(135.0+270.0*fraction(value))
        val tangent=-sin(angle)*dx+cos(angle)*dy
        // Float pointer coordinates can leave a rounding residual on an exactly radial movement.
        if(abs(tangent)<=hypot(dx.toDouble(),dy.toDouble())*1e-7)return 0f
        return (tangent/(density*10.0)).toFloat()
    }
}
