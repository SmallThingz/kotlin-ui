/*
 * Copyright 2025 The Android Open Source Project
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

package smallthingz.ui.components

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

// AndroidX Material Rounded 1.7.8 PlayArrow and Pause, in their original 24 x 24 viewport.
internal fun playbackGlyph(density: Float, playing: Boolean, tint: Int): CachedPathIconDrawable =
    CachedPathIconDrawable(density, tint) { canvas, paint ->
        paint.color = Color.BLACK
        paint.style = Paint.Style.FILL
        canvas.drawPath(if (playing) pausePath0 else playPath0, paint)
    }

private val playPath0: Path by lazy {
    Path().apply {
        fillType = Path.FillType.WINDING
        moveTo(8.0f, 6.82000017f)
        rLineTo(0.0f, 10.3599997f)
        rCubicTo(0.0f, 0.790000021f, 0.870000005f, 1.26999998f, 1.53999996f, 0.839999974f)
        rLineTo(8.14000034f, -5.17999983f)
        rCubicTo(0.620000005f, -0.389999986f, 0.620000005f, -1.28999996f, 0.0f, -1.69000006f)
        lineTo(9.53999996f, 5.98000002f)
        cubicTo(8.86999989f, 5.55000019f, 8.0f, 6.03000021f, 8.0f, 6.82000017f)
        close()
    }
}

private val pausePath0: Path by lazy {
    Path().apply {
        fillType = Path.FillType.WINDING
        moveTo(8.0f, 19.0f)
        rCubicTo(1.10000002f, 0.0f, 2.0f, -0.899999976f, 2.0f, -2.0f)
        lineTo(10.0f, 7.0f)
        rCubicTo(0.0f, -1.10000002f, -0.899999976f, -2.0f, -2.0f, -2.0f)
        rCubicTo(-1.10000038f, 0.0f, -2.0f, 0.899999976f, -2.0f, 2.0f)
        rLineTo(0.0f, 10.0f)
        rCubicTo(0.0f, 1.10000002f, 0.899999976f, 2.0f, 2.0f, 2.0f)
        close()
        moveTo(14.0f, 7.0f)
        rLineTo(0.0f, 10.0f)
        rCubicTo(0.0f, 1.10000002f, 0.899999976f, 2.0f, 2.0f, 2.0f)
        rCubicTo(1.10000038f, 0.0f, 2.0f, -0.899999976f, 2.0f, -2.0f)
        lineTo(18.0f, 7.0f)
        rCubicTo(0.0f, -1.10000002f, -0.899999976f, -2.0f, -2.0f, -2.0f)
        rCubicTo(-1.10000038f, 0.0f, -2.0f, 0.899999976f, -2.0f, 2.0f)
        close()
    }
}
