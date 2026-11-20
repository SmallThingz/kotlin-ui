/*
 * Copyright 2020 The Android Open Source Project
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

package smallthingz.ui.graphics

/** Import the planar transform from [android.graphics.Matrix]. */
fun Matrix.setFrom(matrix: android.graphics.Matrix) {
    val v = values
    matrix.getValues(v)
    val scaleX = v[android.graphics.Matrix.MSCALE_X]
    val skewX = v[android.graphics.Matrix.MSKEW_X]
    val translateX = v[android.graphics.Matrix.MTRANS_X]
    val skewY = v[android.graphics.Matrix.MSKEW_Y]
    val scaleY = v[android.graphics.Matrix.MSCALE_Y]
    val translateY = v[android.graphics.Matrix.MTRANS_Y]
    val persp0 = v[android.graphics.Matrix.MPERSP_0]
    val persp1 = v[android.graphics.Matrix.MPERSP_1]
    val persp2 = v[android.graphics.Matrix.MPERSP_2]

    v[Matrix.ScaleX] = scaleX // 0
    v[Matrix.SkewY] = skewY // 1
    v[2] = 0f // 2
    v[Matrix.Perspective0] = persp0 // 3
    v[Matrix.SkewX] = skewX // 4
    v[Matrix.ScaleY] = scaleY // 5
    v[6] = 0f // 6
    v[Matrix.Perspective1] = persp1 // 7
    v[8] = 0f // 8
    v[9] = 0f // 9
    v[Matrix.ScaleZ] = 1.0f // 10
    v[11] = 0f // 11
    v[Matrix.TranslateX] = translateX // 12
    v[Matrix.TranslateY] = translateY // 13
    v[14] = 0f // 14
    v[Matrix.Perspective2] = persp2 // 15
}
