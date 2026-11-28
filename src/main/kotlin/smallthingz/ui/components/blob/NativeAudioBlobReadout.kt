// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
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
    private var dimmedReady = false
    private var dimmedReadyInk = pageInk(style.readoutBackground)
    private var primaryText: String? = null
    private var secondaryText: String? = null
    private val icon = AudioBlobIcon(context, icons)
    private val primary = ParagraphView(context, style.primaryTextStyle, textDensity, fontResolver, style.focusInk)
    private val secondary = ParagraphView(context, style.secondaryTextStyle, textDensity, fontResolver, style.focusInk)
    private var materialUsesDarkInk = false
    private var materialTransitioning = false
    private var materialNeedsSupport = true
    private var materialRenderer: AudioReactiveBlobView? = null
    private var materialBackdrop = Color.BLACK
    private var materialLayerOpacity = 1f
    private var materialSupportShade = Color.red(AUDIO_BLOB_READOUT_SUPPORT)
    private var readoutLayoutRevision = 0
    private var projectedLayoutRevision = -1
    private var projectedWidth = 0
    private var projectedHeight = 0
    private var projectedCenterX = 0f
    private var projectedCenterY = 0f
    private var projectedRadius = 0f
    private var agslGradientMin = 0f
    private var agslGradientMax = 1f
    private var fallbackGradientMin = 0f
    private var fallbackGradientMax = 1f
    private var contentOpacity = 1f
    private var primaryGap = 0

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
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
        dimmedReadyInk = pageInk(style.readoutBackground)
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
        val shadeChanged = materialUsesDarkInk != darkInk || materialTransitioning != transitioning
        materialUsesDarkInk = darkInk
        materialTransitioning = transitioning
        materialNeedsSupport = needsSupport
        if (shadeChanged) {
            updateMaterialSupportShade()
            if (contentBound) bindContent()
        }
        updateMaterialSupport()
    }

    private fun updateMaterialSupportShade() {
        val support = audioBlobReadoutSupport(materialUsesDarkInk, materialTransitioning)
        materialSupportShade = Color.red(if (materialLayerOpacity < 1f) {
            audioBlobSupportInLayer(support, materialUsesDarkInk, materialBackdrop, materialLayerOpacity)
        } else support)
    }

    private fun updateMaterialSupport() {
        val renderer = materialRenderer ?: return
        var correction = 0
        if (materialNeedsSupport && (materialTransitioning || (active && !filled)) &&
            projectedRadius > 0f && visibility == VISIBLE && contentOpacity > 0f
        ) {
            // White ink needs an RGB ceiling, black ink an RGB floor.
            val amount = if (materialUsesDarkInk) materialSupportShade else 255 - materialSupportShade
            val alpha = (contentOpacity * 255f).roundToInt()
            val strength = (amount * alpha + 127) / 255
            correction = if (materialUsesDarkInk) strength else -strength
        }
        renderer.setReadoutMaterialCorrection(correction)
    }

    fun bind(active: Boolean, filled: Boolean, primaryText: String?, secondaryText: String?, contentAlpha: Float) =
        bind(active, filled, primaryText, secondaryText, contentAlpha, dimmedReady = false)

    internal fun bind(active: Boolean, filled: Boolean, primaryText: String?, secondaryText: String?,
        contentAlpha: Float, dimmedReady: Boolean) {
        contentBound = true
        this.active = active
        this.filled = filled
        this.dimmedReady = dimmedReady
        this.primaryText = primaryText
        this.secondaryText = secondaryText
        setContentOpacity(contentAlpha)
        updateMaterialSupport()
        bindContent()
    }

    private fun bindContent() {
        val tint = when {
            materialTransitioning -> if (materialUsesDarkInk) Color.BLACK else AUDIO_BLOB_READOUT_INK
            filled -> style.filledInk
            active -> if (materialUsesDarkInk) Color.BLACK else AUDIO_BLOB_READOUT_INK
            // Keep the Home dimming layer, and give the still-actionable idle glyphs stronger ink.
            dimmedReady -> dimmedReadyInk
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
        // Fade the material correction and glyphs together.
        icon.alpha = opacity
        primary.alpha = opacity
        secondary.alpha = opacity
        updateMaterialSupport()
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this) updateMaterialSupport()
    }

    private fun readoutCornerDistanceSquared(centerX: Float, centerY: Float): Float {
        var maximum = 0f
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE || child.width == 0 || child.height == 0) continue
            val dx = max(abs(child.left - centerX), abs(child.right - centerX))
            val dy = max(abs(child.top - centerY), abs(child.bottom - centerY))
            maximum = max(maximum, dx * dx + dy * dy)
        }
        return maximum
    }

    fun readoutFootprintRadius(): Float = if (readoutLayoutRevision == 0) 0f else
        sqrt(readoutCornerDistanceSquared(width * .5f, height * .5f)) + 2f * textDensity.density

    fun updateMaterialGeometry(renderer: AudioReactiveBlobView, background: Int) {
        updateMaterialGeometry(renderer, background, opacity = 1f)
    }

    internal fun updateMaterialGeometry(renderer: AudioReactiveBlobView, background: Int, opacity: Float) {
        if (materialRenderer !== renderer) {
            materialRenderer?.setReadoutMaterialCorrection(0)
            materialRenderer = renderer
        }
        val paintInputsChanged = materialBackdrop != background || materialLayerOpacity != opacity
        materialBackdrop = background
        materialLayerOpacity = opacity
        if (paintInputsChanged) updateMaterialSupportShade()
        val canvasWidth = renderer.width
        val canvasHeight = renderer.height
        if (readoutLayoutRevision == 0 || width <= 0 || height <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
            projectedRadius = 0f
            projectedLayoutRevision = -1
            renderer.updateReadoutGeometry(0f, background)
            updateMaterialSupport()
            return
        }
        val centerX = renderer.left - left + canvasWidth * .5f
        val centerY = renderer.top - top + canvasHeight * .5f
        if (projectedLayoutRevision != readoutLayoutRevision || projectedWidth != canvasWidth ||
            projectedHeight != canvasHeight || projectedCenterX != centerX || projectedCenterY != centerY
        ) {
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
                if (child.visibility == GONE || child.width == 0 || child.height == 0) continue
                val left = child.left - centerX - margin
                val right = child.right - centerX + margin
                val top = child.top - centerY - margin
                val bottom = child.bottom - centerY + margin
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
            projectedCenterX = centerX
            projectedCenterY = centerY
            projectedRadius = sqrt(readoutCornerDistanceSquared(centerX, centerY)) + margin
        }
        renderer.updateReadoutGeometry(projectedRadius, background,
            agslGradientMin, agslGradientMax, fallbackGradientMin, fallbackGradientMax,
            useBackdropInk = true, layerOpacity = opacity)
        // Paint inputs can change even when the renderer has no new appearance to publish.
        updateMaterialSupport()
    }

    private fun dp(value: Int) = (value * textDensity.density).roundToInt()
    private fun pageInk(background: Int) =
        if (audioBlobMaterialLuminance(background) >= .179) Color.BLACK else AUDIO_BLOB_READOUT_INK

    override fun onMeasure(w: Int, h: Int) {
        val maxWidth = MeasureSpec.getSize(w)
        val maxHeight = MeasureSpec.getSize(h)
        val labelWidth = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST)
        val hasPrimary = primary.visibility != GONE
        if (hasPrimary) primary.measure(labelWidth, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        val primaryHeight = if (hasPrimary) primary.measuredHeight else 0
        val roomAbovePrimary = (maxHeight - primaryHeight).coerceAtLeast(0)
        // Reserve the actual primary line first so the fixed icon and gap cannot clip it.
        primaryGap = if (hasPrimary) dp(10).coerceAtMost(roomAbovePrimary) else 0
        val iconHeight = dp(38).coerceAtMost(roomAbovePrimary - primaryGap)
        val iconWidth = minOf(iconHeight, maxWidth)
        icon.measure(MeasureSpec.makeMeasureSpec(iconWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(iconHeight, MeasureSpec.EXACTLY))
        var usedHeight = icon.measuredHeight + primaryGap + primaryHeight
        var usedWidth = max(icon.measuredWidth, if (hasPrimary) primary.measuredWidth else 0)
        if (secondary.visibility != GONE) {
            usedHeight += dp(2).coerceAtMost((maxHeight - usedHeight).coerceAtLeast(0))
            secondary.measure(labelWidth,
                MeasureSpec.makeMeasureSpec((maxHeight - usedHeight).coerceAtLeast(0), MeasureSpec.AT_MOST))
            usedHeight += secondary.measuredHeight
            usedWidth = max(usedWidth, secondary.measuredWidth)
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
            val gap = if (index == 0) primaryGap else dp(2)
            y += gap.coerceAtMost((height - y).coerceAtLeast(0))
            val x = ((width - label.measuredWidth) / 2f).roundToInt()
            label.layout(x, y, x + label.measuredWidth, y + label.measuredHeight)
            y += label.measuredHeight
        }
        readoutLayoutRevision++
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
