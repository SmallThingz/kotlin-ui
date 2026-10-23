package smallthingz.ui.animation

import smallthingz.ui.animation.core.Animatable
import smallthingz.ui.animation.core.AnimationVector4D
import smallthingz.ui.graphics.Color

fun Animatable(initialValue: Color): Animatable<Color, AnimationVector4D> =
    Animatable(initialValue, (Color.VectorConverter)(initialValue.colorSpace))
