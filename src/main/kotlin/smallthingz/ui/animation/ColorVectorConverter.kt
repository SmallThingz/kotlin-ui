/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package smallthingz.ui.animation

import smallthingz.ui.animation.core.AnimationVector4D
import smallthingz.ui.animation.core.TwoWayConverter
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.colorspace.ColorSpace
import smallthingz.ui.graphics.colorspace.ColorSpaces
import smallthingz.ui.util.fastCoerceIn

private val ColorToVector: (colorSpace: ColorSpace) -> TwoWayConverter<Color, AnimationVector4D> =
    { colorSpace ->
        TwoWayConverter(
            convertToVector = { color ->
                val (l, a, b, alpha) = color.convert(ColorSpaces.Oklab)
                AnimationVector4D(alpha, l, a, b)
            },
            convertFromVector = { vector ->
                Color(
                        vector.v2.fastCoerceIn(0f, 1f), // L (red)
                        vector.v3.fastCoerceIn(-0.5f, 0.5f), // a (blue)
                        vector.v4.fastCoerceIn(-0.5f, 0.5f), // b (green)
                        vector.v1.fastCoerceIn(0f, 1f), // alpha
                        ColorSpaces.Oklab,
                    )
                    .convert(colorSpace)
            },
        )
    }

public val Color.Companion.VectorConverter:
    (colorSpace: ColorSpace) -> TwoWayConverter<Color, AnimationVector4D>
    get() = ColorToVector
