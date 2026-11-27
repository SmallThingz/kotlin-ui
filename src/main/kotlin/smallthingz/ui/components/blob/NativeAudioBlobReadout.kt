// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import smallthingz.ui.components.CachedPathIconDrawable
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density

/** Per-view assets supplied by the host. The readout controls their tint, bounds and drawing. */
data class NativeAudioBlobIcons(val filled: Drawable, val active: Drawable, val ready: Drawable)

data class NativeAudioBlobDescriptions(val filled: String, val active: String, val ready: String)

/** Separate icon and label children keep their natural reading order and measure independently. */
class NativeAudioBlobReadout(
    context: Context,
    private val icons: NativeAudioBlobIcons,
    private var descriptions: NativeAudioBlobDescriptions,
    private var style: NativeAudioBlobStyle,
    private var textDensity: Density,
    fontResolver: FontFamily.Resolver,
) : ViewGroup(context) {
    private var contentBound = false
    private var active = false
    private var filled = false
    private var primaryText: String? = null
    private var secondaryText: String? = null
    private val icon = AudioBlobIcon(context, icons)
    private val primary = ParagraphView(context, style.primaryTextStyle, textDensity, fontResolver, style.focusInk)
    private val secondary = ParagraphView(context, style.secondaryTextStyle, textDensity, fontResolver, style.focusInk)
    private val darken = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
    private val lighten = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
    private val readoutPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        // Neutral support only affects startup geometry outside the readable material body.
        xfermode = darken
    }
    private var materialUsesDarkInk = false
    private var materialTransitioning = false
    private var materialNeedsSupport = true
    private var readoutSupportColor = AUDIO_BLOB_READOUT_SUPPORT
    private var readoutCenterX = 0f
    private var readoutCenterY = 0f
    private var readoutCoreRadius = 0f
    private var readoutOuterRadius = 0f
    private var readoutMaskWidth = 0
    private var readoutMaskHeight = 0
    private var readoutEdgeExtent = 0f
    private var supportViewportCenterX = 0f
    private var supportViewportCenterY = 0f
    private var supportViewportRadius = -1f // No renderer geometry supplied yet.
    private var readoutPrefersBackdropInk = false
    private var materialRenderer: AudioReactiveBlobView? = null
    private var materialBackdrop = Color.BLACK
    private var materialLayerOpacity = 1f
    private var compactSupportColor = AUDIO_BLOB_READOUT_SUPPORT
    private var radialReadoutShader: Shader? = null
    private var boundedReadoutShader: Shader? = null
    private var readoutLayoutRevision = 0
    private var projectedLayoutRevision = -1
    private var projectedWidth = 0
    private var projectedHeight = 0
    private var agslGradientMin = 0f
    private var agslGradientMax = 1f
    private var fallbackGradientMin = 0f
    private var fallbackGradientMax = 1f
    private var contentOpacity = 1f

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        addView(icon); addView(primary); addView(secondary)
        primary.setTextLayout(maxLines = 1)
        secondary.setTextLayout(maxLines = 1)
    }

    fun setAppearance(
        style: NativeAudioBlobStyle,
        textDensity: Density,
        fontResolver: FontFamily.Resolver,
        descriptions: NativeAudioBlobDescriptions,
    ) {
        this.style = style
        this.textDensity = textDensity
        this.descriptions = descriptions
        primary.setTypography(style.primaryTextStyle, textDensity, fontResolver)
        secondary.setTypography(style.secondaryTextStyle, textDensity, fontResolver)
        requestLayout()
        if (contentBound) bindContent()
    }

    fun setDescriptions(value: NativeAudioBlobDescriptions) {
        descriptions = value
    }

    fun setMaterialAppearance(darkInk: Boolean, transitioning: Boolean, needsSupport: Boolean) {
        if (materialUsesDarkInk == darkInk && materialTransitioning == transitioning && materialNeedsSupport == needsSupport) return
        materialUsesDarkInk = darkInk
        materialTransitioning = transitioning
        materialNeedsSupport = needsSupport
        readoutPaint.xfermode = if (darkInk) lighten else darken
        updateSupportPaint()
        if (contentBound) bindContent()
    }

    private fun updateSupportPaint() {
        val support = audioBlobReadoutSupport(materialUsesDarkInk, materialTransitioning)
        if (readoutSupportColor != support) {
            readoutSupportColor = support
            rebuildReadoutShader()
        } else {
            readoutPaint.shader = if (materialTransitioning) radialReadoutShader else boundedReadoutShader
        }
        if (readoutPrefersBackdropInk) {
            compactSupportColor = audioBlobSupportInLayer(
                support, materialUsesDarkInk, materialBackdrop, materialLayerOpacity,
            )
        }
        updateCompactSupport()
    }

    private fun showsReadoutSupport(): Boolean =
        materialNeedsSupport && (materialTransitioning || (active && !filled)) &&
            supportViewportRadius != 0f && readoutOuterRadius > 0f && readoutPaint.alpha != 0

    private fun updateCompactSupport() {
        val renderer = materialRenderer ?: return
        var correction = 0
        if (readoutPrefersBackdropInk && visibility == VISIBLE && showsReadoutSupport()) {
            val shade = Color.red(compactSupportColor)
            // White ink needs an RGB ceiling, black ink an RGB floor. Mix with black or white
            // on the existing material paint, keeping every transparent pixel untouched.
            val amount = if (materialUsesDarkInk) shade else 255 - shade
            val strength = (amount * readoutPaint.alpha + 127) / 255
            correction = if (materialUsesDarkInk) strength else -strength
        }
        renderer.setReadoutMaterialCorrection(correction)
    }

    fun bind(active: Boolean, filled: Boolean, primaryText: String?, secondaryText: String?, contentAlpha: Float) {
        contentBound = true
        this.active = active
        this.filled = filled
        this.primaryText = primaryText
        this.secondaryText = secondaryText
        setContentOpacity(contentAlpha)
        updateCompactSupport()
        bindContent()
    }

    private fun bindContent() {
        val tint = when {
            materialTransitioning -> if (materialUsesDarkInk) Color.BLACK else AUDIO_BLOB_READOUT_INK
            filled -> style.filledInk
            active -> if (materialUsesDarkInk) Color.BLACK else AUDIO_BLOB_READOUT_INK
            else -> style.idleInk
        }
        icon.bind(
            if (filled) icons.filled else if (active) icons.active else icons.ready,
            tint,
            if (filled) descriptions.filled else if (active) descriptions.active else descriptions.ready,
        )
        primary.visibility = if (primaryText != null) VISIBLE else GONE
        primary.setText(primaryText.orEmpty())
        primary.setTextColor(tint)
        secondary.visibility = if (secondaryText != null && (active || filled)) VISIBLE else GONE
        secondary.setText(secondaryText.orEmpty())
        secondary.setTextColor(tint)
        invalidate()
    }

    fun setContentOpacity(value: Float) {
        val opacity = value.coerceIn(0f, 1f)
        if (opacity == contentOpacity) return
        contentOpacity = opacity
        // Fade the correction and glyphs together without isolating the regular readout blend.
        readoutPaint.alpha = (opacity * 255f).roundToInt()
        icon.alpha = opacity
        primary.alpha = opacity
        secondary.alpha = opacity
        updateCompactSupport()
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this) updateCompactSupport()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (readoutPrefersBackdropInk || !showsReadoutSupport()) return
        if (materialTransitioning) {
            // A broad, diffuse correction follows the changing material without a rectangular plate.
            canvas.drawCircle(readoutCenterX, readoutCenterY, readoutOuterRadius, readoutPaint)
        } else {
            // Settled energetic folds need support only near the glyphs, not in empty concavities.
            canvas.drawRect(-readoutEdgeExtent, -readoutEdgeExtent,
                width + readoutEdgeExtent, height + readoutEdgeExtent, readoutPaint)
        }
    }

    private fun readoutCornerDistanceSquared(centerX: Float, centerY: Float): Float {
        var maximum = 0f
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val dx = max(abs(child.left - centerX), abs(child.right - centerX))
            val dy = max(abs(child.top - centerY), abs(child.bottom - centerY))
            maximum = max(maximum, dx * dx + dy * dy)
        }
        return maximum
    }

    fun readoutFootprintRadius(): Float = readoutCoreRadius + abs(readoutCenterY - height * .5f)

    fun updateMaterialGeometry(renderer: AudioReactiveBlobView, background: Int) {
        updateMaterialGeometry(renderer, background, opacity = 1f)
    }

    internal fun updateMaterialGeometry(renderer: AudioReactiveBlobView, background: Int, opacity: Float) {
        if (materialRenderer !== renderer) {
            materialRenderer?.setReadoutMaterialCorrection(0)
            materialRenderer = renderer
        }
        val previousInkPreference = readoutPrefersBackdropInk
        val paintInputsChanged = materialBackdrop != background || materialLayerOpacity != opacity
        materialBackdrop = background
        materialLayerOpacity = opacity
        val canvasWidth = renderer.width
        val canvasHeight = renderer.height
        val viewportChanged = updateSupportViewport(renderer.left - left + canvasWidth * .5f,
            renderer.top - top + canvasHeight * .5f, minOf(canvasWidth, canvasHeight) * .495f)
        if (readoutLayoutRevision == 0 || width <= 0 || height <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
            renderer.updateReadoutGeometry(0f, background)
            // Appearance callbacks may be deduplicated when the renderer regains a size.
            if (paintInputsChanged) updateSupportPaint() else updateCompactSupport()
            return
        }
        if (viewportChanged || projectedLayoutRevision != readoutLayoutRevision ||
            projectedWidth != canvasWidth || projectedHeight != canvasHeight) {
            // A constrained readout can straddle the actual body. Use ink carried by the page
            // outside it and correct only the renderer's material underneath.
            readoutPrefersBackdropInk = supportReachesViewport()
            val minSize = minOf(canvasWidth, canvasHeight).toFloat()
            val w = canvasWidth.toFloat()
            val h = canvasHeight.toFloat()
            val diagonalSquared = w * w + h * h
            val margin = 2f * textDensity.density
            var aMin = Float.POSITIVE_INFINITY
            var aMax = Float.NEGATIVE_INFINITY
            var fMin = Float.POSITIVE_INFINITY
            var fMax = Float.NEGATIVE_INFINITY
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == GONE) continue
                val left = child.left - width * .5f - margin
                val right = child.right - width * .5f + margin
                val top = child.top - height * .5f - margin
                val bottom = child.bottom - height * .5f + margin
                aMin = minOf(aMin, .46f + (.9f * left - .55f * bottom) / minSize)
                aMax = maxOf(aMax, .46f + (.9f * right - .55f * top) / minSize)
                fMin = minOf(fMin, .5f + (left * w + top * h) / diagonalSquared)
                fMax = maxOf(fMax, .5f + (right * w + bottom * h) / diagonalSquared)
            }
            agslGradientMin = if (aMin.isFinite()) aMin.coerceIn(0f, 1f) else 0f
            agslGradientMax = if (aMax.isFinite()) aMax.coerceIn(0f, 1f) else 1f
            fallbackGradientMin = if (fMin.isFinite()) fMin.coerceIn(0f, 1f) else 0f
            fallbackGradientMax = if (fMax.isFinite()) fMax.coerceIn(0f, 1f) else 1f
            projectedLayoutRevision = readoutLayoutRevision
            projectedWidth = canvasWidth
            projectedHeight = canvasHeight
        }
        renderer.updateReadoutGeometry(readoutFootprintRadius(), background,
            agslGradientMin, agslGradientMax, fallbackGradientMin, fallbackGradientMax, readoutPrefersBackdropInk,
            layerOpacity = if (readoutPrefersBackdropInk) opacity else 1f)
        val inkPreferenceChanged = previousInkPreference != readoutPrefersBackdropInk
        // A resize can leave a recorded support field with the same readout size and ink.
        if (inkPreferenceChanged) invalidate()
        if (paintInputsChanged || inkPreferenceChanged) updateSupportPaint()
        else updateCompactSupport()
    }

    private fun updateReadoutGeometry() {
        readoutLayoutRevision++
        val centerX = width * .5f
        var lower = 0f
        var upper = height.toFloat()
        // Minimize the enclosing circle using the actual narrow icon and wider text bounds.
        // The final radius is measured again, so optimization precision cannot reduce coverage.
        repeat(24) {
            val first = lower + (upper - lower) / 3f
            val second = upper - (upper - lower) / 3f
            if (readoutCornerDistanceSquared(centerX, first) < readoutCornerDistanceSquared(centerX, second))
                upper = second else lower = first
        }
        val centerY = (lower + upper) * .5f
        val core = sqrt(readoutCornerDistanceSquared(centerX, centerY)) + 2f * textDensity.density
        val outer = core + 24f * textDensity.density
        if (centerX == readoutCenterX && centerY == readoutCenterY &&
            core == readoutCoreRadius && outer == readoutOuterRadius &&
            width == readoutMaskWidth && height == readoutMaskHeight) return
        readoutCenterX = centerX
        readoutCenterY = centerY
        readoutCoreRadius = core
        readoutOuterRadius = outer
        readoutMaskWidth = width
        readoutMaskHeight = height
        rebuildReadoutShader()
    }

    private fun supportReachesViewport(): Boolean = supportViewportRadius > 0f &&
        readoutOuterRadius + hypot(readoutCenterX - supportViewportCenterX,
            readoutCenterY - supportViewportCenterY) > supportViewportRadius

    private fun updateSupportViewport(centerX: Float, centerY: Float, radius: Float): Boolean {
        if (supportViewportCenterX == centerX && supportViewportCenterY == centerY &&
            supportViewportRadius == radius) return false
        supportViewportCenterX = centerX
        supportViewportCenterY = centerY
        supportViewportRadius = radius
        return true
    }

    private fun rebuildReadoutShader() {
        if (readoutOuterRadius <= 0f) return
        val coreFraction = readoutCoreRadius / readoutOuterRadius
        val colors = IntArray(10)
        val stops = FloatArray(10)
        colors[0] = readoutSupportColor
        for (index in 1..9) {
            val t = (index - 1) / 8f
            val alpha = ((1f - t * t * (3f - 2f * t)) * 255f).roundToInt()
            colors[index] = (readoutSupportColor and 0x00ffffff) or (alpha shl 24)
            stops[index] = coreFraction + (1f - coreFraction) * t
        }
        val radial = RadialGradient(readoutCenterX, readoutCenterY, readoutOuterRadius,
            colors, stops, Shader.TileMode.CLAMP)
        // The enclosing circle contains empty space far beyond the glyphs. Confine its correction
        // to the measured readout plus a smooth fringe, so energetic concavities get no gray halo.
        val margin = 2f * textDensity.density
        val feather = 8f * textDensity.density
        readoutEdgeExtent = margin + feather
        val maskColors = IntArray(18)
        val horizontalStops = FloatArray(18)
        val verticalStops = FloatArray(18)
        val horizontalFringe = feather / (readoutMaskWidth + 2f * readoutEdgeExtent)
        val verticalFringe = feather / (readoutMaskHeight + 2f * readoutEdgeExtent)
        for (index in 0..8) {
            val t = index / 8f
            val alpha = (t * t * (3f - 2f * t) * 255f).roundToInt()
            maskColors[index] = (alpha shl 24) or 0x00ffffff
            maskColors[17 - index] = maskColors[index]
            horizontalStops[index] = t * horizontalFringe
            horizontalStops[17 - index] = 1f - t * horizontalFringe
            verticalStops[index] = t * verticalFringe
            verticalStops[17 - index] = 1f - t * verticalFringe
        }
        val horizontal = LinearGradient(-readoutEdgeExtent, 0f, readoutMaskWidth + readoutEdgeExtent, 0f,
            maskColors, horizontalStops, Shader.TileMode.CLAMP)
        val vertical = LinearGradient(0f, -readoutEdgeExtent, 0f, readoutMaskHeight + readoutEdgeExtent,
            maskColors, verticalStops, Shader.TileMode.CLAMP)
        radialReadoutShader = radial
        boundedReadoutShader = ComposeShader(ComposeShader(radial, horizontal, PorterDuff.Mode.DST_IN),
            vertical, PorterDuff.Mode.DST_IN)
        readoutPaint.shader = if (materialTransitioning) radialReadoutShader else boundedReadoutShader
        invalidate()
    }

    private fun dp(value: Int) = (value * textDensity.density).roundToInt()
    override fun onMeasure(w: Int, h: Int) {
        val maxWidth = MeasureSpec.getSize(w)
        val maxHeight = MeasureSpec.getSize(h)
        val iconWidth = dp(38).coerceAtMost(maxWidth)
        val iconHeight = dp(38).coerceAtMost(maxHeight)
        icon.measure(MeasureSpec.makeMeasureSpec(iconWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(iconHeight, MeasureSpec.EXACTLY))
        var usedHeight = icon.measuredHeight
        var usedWidth = icon.measuredWidth
        for (index in 0..1) {
            val label = if (index == 0) primary else secondary
            if (label.visibility == GONE) continue
            val gap = if (index == 0) 10 else 2
            usedHeight += dp(gap).coerceAtMost((maxHeight - usedHeight).coerceAtLeast(0))
            label.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec((maxHeight - usedHeight).coerceAtLeast(0), MeasureSpec.AT_MOST))
            usedHeight += label.measuredHeight
            usedWidth = max(usedWidth, label.measuredWidth)
        }
        setMeasuredDimension(resolveSize(usedWidth, w), resolveSize(usedHeight, h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = 0
        val iconX = ((width - icon.measuredWidth) / 2f).roundToInt()
        icon.layout(iconX, y, iconX + icon.measuredWidth, y + icon.measuredHeight)
        y += icon.measuredHeight
        for (index in 0..1) {
            val label = if (index == 0) primary else secondary
            if (label.visibility == GONE) continue
            val gap = if (index == 0) 10 else 2
            y += dp(gap).coerceAtMost((height - y).coerceAtLeast(0))
            val x = ((width - label.measuredWidth) / 2f).roundToInt()
            label.layout(x, y, x + label.measuredWidth, y + label.measuredHeight)
            y += label.measuredHeight
        }
        updateReadoutGeometry()
    }
}

private class AudioBlobIcon(context: Context, private val icons: NativeAudioBlobIcons) : View(context) {
    private var drawable = icons.ready
    private var boundIcon: Drawable? = null
    private var boundTint = 0
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun bind(icon: Drawable, color: Int, description: String) {
        val appearanceChanged = boundIcon !== icon || boundTint != color
        if (appearanceChanged) {
            boundIcon = icon
            boundTint = color
            drawable = icon
            drawable.setTint(color)
            drawable.setBounds(0, 0, width, height)
        }
        if (contentDescription != description) contentDescription = description
        if (appearanceChanged) invalidate()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateCachedIconVisibility(isVisible)
    }

    override fun onDetachedFromWindow() {
        updateCachedIconVisibility(false)
        super.onDetachedFromWindow()
    }

    private fun updateCachedIconVisibility(visible: Boolean) {
        // An inactive variant may still own the mask from its last visible pose.
        (icons.ready as? CachedPathIconDrawable)?.setVisible(visible, false)
        (icons.active as? CachedPathIconDrawable)?.setVisible(visible, false)
        (icons.filled as? CachedPathIconDrawable)?.setVisible(visible, false)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        drawable.setBounds(0, 0, w, h)
    }
    override fun onDraw(canvas: Canvas) { drawable.draw(canvas) }
}
