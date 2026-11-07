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

import android.graphics.Path

/** Exact AndroidX Material Rounded 1.7.8 paths; Float32 commands retain their source geometry. */
class NativeThemeIconDrawable(mode: NativeThemeMode, density: Float, tint: Int) :
    CachedPathIconDrawable(density, tint, drawMask = { canvas, paint ->
        paint.color = android.graphics.Color.BLACK
        paint.shader = null
        paint.colorFilter = null
        paint.pathEffect = null
        paint.strokeMiter = 4f
        paint.style = android.graphics.Paint.Style.FILL
        canvas.drawPath(NativeThemeIconPaths.path(mode), paint)
    })

private object NativeThemeIconPaths {
    fun path(mode: NativeThemeMode): Path = when (mode) {
        NativeThemeMode.SYSTEM -> themeSystemPath0
        NativeThemeMode.LIGHT -> themeLightPath0
        NativeThemeMode.DARK -> themeDarkPath0
    }

    private val themeSystemPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(4.0f, 7.0f)
            rCubicTo(0.0f, -0.550000012f, 0.449999988f, -1.0f, 1.0f, -1.0f)
            rLineTo(16.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            rCubicTo(0.0f, -0.550000191f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            lineTo(4.0f, 4.0f)
            rCubicTo(-1.10000002f, 0.0f, -2.0f, 0.899999976f, -2.0f, 2.0f)
            rLineTo(0.0f, 11.0f)
            rLineTo(-0.5f, 0.0f)
            rCubicTo(-0.829999983f, 0.0f, -1.5f, 0.670000017f, -1.5f, 1.5f)
            cubicTo(0.0f, 19.3299999f, 0.670000017f, 20.0f, 1.5f, 20.0f)
            lineTo(14.0f, 20.0f)
            rLineTo(0.0f, -3.0f)
            lineTo(4.0f, 17.0f)
            lineTo(4.0f, 7.0f)
            close()
            moveTo(23.0f, 8.0f)
            rLineTo(-6.0f, 0.0f)
            rCubicTo(-0.550000012f, 0.0f, -1.0f, 0.449999988f, -1.0f, 1.0f)
            rLineTo(0.0f, 10.0f)
            rCubicTo(0.0f, 0.550000012f, 0.449999988f, 1.0f, 1.0f, 1.0f)
            rLineTo(6.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            lineTo(24.0f, 9.0f)
            rCubicTo(0.0f, -0.550000012f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            close()
            moveTo(22.0f, 17.0f)
            rLineTo(-4.0f, 0.0f)
            rLineTo(0.0f, -7.0f)
            rLineTo(4.0f, 0.0f)
            rLineTo(0.0f, 7.0f)
            close()
        }
    }

    private val themeLightPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(12.0f, 7.0f)
            rCubicTo(-2.75999999f, 0.0f, -5.0f, 2.24000001f, -5.0f, 5.0f)
            rCubicTo(0.0f, 2.76000023f, 2.24000001f, 5.0f, 5.0f, 5.0f)
            rCubicTo(2.76000023f, 0.0f, 5.0f, -2.24000001f, 5.0f, -5.0f)
            cubicTo(17.0f, 9.23999977f, 14.7600002f, 7.0f, 12.0f, 7.0f)
            lineTo(12.0f, 7.0f)
            close()
            moveTo(2.0f, 13.0f)
            rLineTo(2.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            rCubicTo(0.0f, -0.550000191f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            rLineTo(-2.0f, 0.0f)
            rCubicTo(-0.550000012f, 0.0f, -1.0f, 0.449999988f, -1.0f, 1.0f)
            cubicTo(1.0f, 12.5500002f, 1.45000005f, 13.0f, 2.0f, 13.0f)
            close()
            moveTo(20.0f, 13.0f)
            rLineTo(2.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            rCubicTo(0.0f, -0.550000191f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            rLineTo(-2.0f, 0.0f)
            rCubicTo(-0.550000012f, 0.0f, -1.0f, 0.449999988f, -1.0f, 1.0f)
            cubicTo(19.0f, 12.5500002f, 19.4500008f, 13.0f, 20.0f, 13.0f)
            close()
            moveTo(11.0f, 2.0f)
            rLineTo(0.0f, 2.0f)
            rCubicTo(0.0f, 0.550000012f, 0.449999988f, 1.0f, 1.0f, 1.0f)
            rCubicTo(0.550000191f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            lineTo(13.0f, 2.0f)
            rCubicTo(0.0f, -0.550000012f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            cubicTo(11.4499998f, 1.0f, 11.0f, 1.45000005f, 11.0f, 2.0f)
            close()
            moveTo(11.0f, 20.0f)
            rLineTo(0.0f, 2.0f)
            rCubicTo(0.0f, 0.550000012f, 0.449999988f, 1.0f, 1.0f, 1.0f)
            rCubicTo(0.550000191f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            rLineTo(0.0f, -2.0f)
            rCubicTo(0.0f, -0.550000012f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            cubicTo(11.4499998f, 19.0f, 11.0f, 19.4500008f, 11.0f, 20.0f)
            close()
            moveTo(5.98999977f, 4.57999992f)
            rCubicTo(-0.389999986f, -0.389999986f, -1.02999997f, -0.389999986f, -1.40999997f, 0.0f)
            rCubicTo(-0.389999986f, 0.389999986f, -0.389999986f, 1.02999997f, 0.0f, 1.40999997f)
            rLineTo(1.05999994f, 1.05999994f)
            rCubicTo(0.389999986f, 0.389999986f, 1.02999997f, 0.389999986f, 1.40999997f, 0.0f)
            rCubicTo(0.379999638f, -0.389999866f, 0.389999986f, -1.02999997f, 0.0f, -1.40999997f)
            lineTo(5.98999977f, 4.57999992f)
            close()
            moveTo(18.3600006f, 16.9500008f)
            rCubicTo(-0.389999986f, -0.389999986f, -1.02999997f, -0.389999986f, -1.40999997f, 0.0f)
            rCubicTo(-0.389999986f, 0.389999986f, -0.389999986f, 1.02999997f, 0.0f, 1.40999997f)
            rLineTo(1.05999994f, 1.05999994f)
            rCubicTo(0.389999986f, 0.389999986f, 1.02999997f, 0.389999986f, 1.40999997f, 0.0f)
            rCubicTo(0.389999986f, -0.389999986f, 0.389999986f, -1.02999997f, 0.0f, -1.40999997f)
            lineTo(18.3600006f, 16.9500008f)
            close()
            moveTo(19.4200001f, 5.98999977f)
            rCubicTo(0.389999986f, -0.389999986f, 0.389999986f, -1.02999997f, 0.0f, -1.40999997f)
            rCubicTo(-0.389999986f, -0.389999986f, -1.02999997f, -0.389999986f, -1.40999997f, 0.0f)
            rLineTo(-1.05999994f, 1.05999994f)
            rCubicTo(-0.389999986f, 0.389999986f, -0.389999986f, 1.02999997f, 0.0f, 1.40999997f)
            rCubicTo(0.38999939f, 0.379999638f, 1.02999997f, 0.389999986f, 1.40999997f, 0.0f)
            lineTo(19.4200001f, 5.98999977f)
            close()
            moveTo(7.05000019f, 18.3600006f)
            rCubicTo(0.389999986f, -0.389999986f, 0.389999986f, -1.02999997f, 0.0f, -1.40999997f)
            rCubicTo(-0.389999986f, -0.389999986f, -1.02999997f, -0.389999986f, -1.40999997f, 0.0f)
            rLineTo(-1.05999994f, 1.05999994f)
            rCubicTo(-0.389999986f, 0.389999986f, -0.389999986f, 1.02999997f, 0.0f, 1.40999997f)
            rCubicTo(0.389999866f, 0.379999161f, 1.02999997f, 0.389999986f, 1.40999997f, 0.0f)
            lineTo(7.05000019f, 18.3600006f)
            close()
        }
    }

    private val themeDarkPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(11.0100002f, 3.04999995f)
            cubicTo(6.51000023f, 3.53999996f, 3.0f, 7.36000013f, 3.0f, 12.0f)
            rCubicTo(0.0f, 4.96999979f, 4.03000021f, 9.0f, 9.0f, 9.0f)
            rCubicTo(4.63000011f, 0.0f, 8.44999981f, -3.5f, 8.94999981f, -8.0f)
            rCubicTo(0.0900000036f, -0.790000021f, -0.779999971f, -1.41999996f, -1.53999996f, -0.949999988f)
            rCubicTo(-0.839999974f, 0.540000021f, -1.84000003f, 0.850000024f, -2.91000009f, 0.850000024f)
            rCubicTo(-2.98000002f, 0.0f, -5.4000001f, -2.42000008f, -5.4000001f, -5.4000001f)
            rCubicTo(0.0f, -1.05999994f, 0.310000002f, -2.05999994f, 0.839999974f, -2.8900001f)
            cubicTo(12.3900003f, 3.94000006f, 11.8999996f, 2.98000002f, 11.0100002f, 3.04999995f)
            close()
        }
    }

}
