// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.ViewGroup
import kotlin.math.roundToInt

/** Audio-reactive body with a centered readout, focus decoration and visual transform. */
open class NativeAudioBlobControlView(
    context: Context,
    protected val overlay: NativeAudioBlobReadout,
    private var style: NativeAudioBlobStyle,
    private var density: Float,
) : ViewGroup(context) {
    protected val renderer = AudioReactiveBlobView(context)
    protected open val focusDecorationEnabled: Boolean get() = true
    private var focusVisible = false
    private val focusHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val focusStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var focusCenterX = 0f
    private var focusCenterY = 0f
    private var focusRadius = 0f

    init {
        clipChildren = false
        clipToPadding = false
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(renderer)
        addView(overlay)
        renderer.onReadoutAppearanceChanged = overlay::setMaterialAppearance
    }

    protected fun setControlAppearance(style: NativeAudioBlobStyle, density: Float) {
        this.style = style
        this.density = density
    }

    protected fun updateBlobDecoration(dimmed: Boolean) {
        val opacity = if (dimmed) .56f else 1f
        overlay.updateMaterialGeometry(renderer, style.readoutBackground, opacity)
        alpha = opacity
    }

    protected fun applyBlobTransform(pressScale: Float, depthScale: Float, degrees: Float, pivotFractionX: Float) {
        scaleX = pressScale
        scaleY = pressScale * depthScale
        rotationY = degrees
        pivotX = width * pivotFractionX
        pivotY = height * .5f
        // ViewLayer.cameraDistancePx multiplies by densityDpi before setting View.cameraDistance.
        cameraDistance = 24f * density * resources.displayMetrics.densityDpi
    }

    protected fun setFocusVisible(value: Boolean) {
        if (focusVisible == value) return
        focusVisible = value
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!focusVisible || !isFocused || !isEnabled || !isAttachedToWindow || !focusDecorationEnabled || focusRadius <= 0f) return
        canvas.drawCircle(focusCenterX, focusCenterY, focusRadius, focusHalo)
        canvas.drawCircle(focusCenterX, focusCenterY, focusRadius, focusStroke)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        renderer.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        overlay.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        focusCenterX = width * .5f
        focusCenterY = height * .5f
        focusRadius = (minOf(width, height) * .5f - 4f * density).coerceAtLeast(0f)
        focusHalo.strokeWidth = 4f * density
        focusStroke.strokeWidth = 2f * density
        focusHalo.color = style.readoutBackground
        focusStroke.color = style.focusInk
        renderer.layout(0, 0, width, height)
        val x = ((width - overlay.measuredWidth) / 2f).roundToInt()
        val y = ((height - overlay.measuredHeight) / 2f).roundToInt()
        overlay.layout(x, y, x + overlay.measuredWidth, y + overlay.measuredHeight)
        overlay.updateMaterialGeometry(renderer, style.readoutBackground, alpha)
    }
}
