// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.view.NativeMergedRowRole
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import kotlin.math.max
import kotlin.math.roundToInt

/** Geometry for related detail cards, including the compact and stacked text fallbacks. */
data class NativeDetailCardStyle(
    val groupRadiusDp: Float = 20f,
    val markerRadiusDp: Float = 14f,
    val markerSizeDp: Int = 44,
    val markerGlyphSizeDp: Int = 22,
    val paddingDp: Int = 16,
    val gapDp: Int = 14,
    val compactPaddingDp: Int = 12,
    val compactGapDp: Int = 8,
    val bodyGapDp: Int = 2,
) {
    init {
        require(groupRadiusDp.isFinite() && groupRadiusDp >= 0f && markerRadiusDp.isFinite() && markerRadiusDp >= 0f)
        require(markerSizeDp >= 0 && markerGlyphSizeDp >= 0 && paddingDp >= 0 && gapDp >= 0)
        require(compactPaddingDp >= 0 && compactGapDp >= 0 && bodyGapDp >= 0)
    }

    fun createGroup(context: Context, onPaintedSurfaceChanged: (View) -> Unit = {}): NativeGroupLayout =
        NativeGroupLayout(context, cornerRadiusDp = groupRadiusDp, onPaintedSurfaceChanged = onPaintedSurfaceChanged)
}

/** A centered marker in the detail card's leading surface. */
class NativeDetailCardMarker(
    context: Context,
    textStyle: TextStyle,
    density: Density,
    resolver: FontFamily.Resolver,
    style: NativeDetailCardStyle = NativeDetailCardStyle(),
    markerText: String? = null,
    glyph: View? = null,
    private val onGlyphTint: ((Int) -> Unit)? = null,
    onPaintedSurfaceChanged: (View) -> Unit = {},
) {
    val view = NativeGroupLayout(context, cornerRadiusDp = style.markerRadiusDp,
        onPaintedSurfaceChanged = onPaintedSurfaceChanged)
    internal val text = if (glyph == null) ParagraphView(context, textStyle, density, resolver, 0).apply {
        setText(markerText.orEmpty())
    } else null
    private val content = object : ViewGroup(context) {
        private val item: View = text ?: checkNotNull(glyph)
        init { clipChildren = false; clipToPadding = false; addView(item) }
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            val height = MeasureSpec.getSize(h)
            if (glyph != null) {
                val size = (style.markerGlyphSizeDp * resources.displayMetrics.density).roundToInt()
                item.measure(cardExact(size.coerceAtMost(width)), cardExact(size.coerceAtMost(height)))
            } else {
                item.measure(cardAtMost(width), cardAtMost(height))
            }
            setMeasuredDimension(width, height)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val x = ((width - item.measuredWidth) / 2f).roundToInt()
            val y = ((height - item.measuredHeight) / 2f).roundToInt()
            item.layout(x, y, x + item.measuredWidth, y + item.measuredHeight)
        }
    }
    init {
        view.addRow(content, NativeGroupLayout.LayoutParams(-1, -1))
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        text?.isScreenReaderFocusable = false
    }
    fun bind(palette: NativeColorScheme) {
        view.setSurfacePreset(palette.surfacePreset)
        view.setPalette(palette.primaryContainer, Color.TRANSPARENT)
        text?.animateTextColor(palette.onPrimaryContainer)
        onGlyphTint?.invoke(palette.onPrimaryContainer)
    }
}

/** A card that keeps measured labels and its trailing control at their source placements. */
open class NativeDetailCardRow(
    context: Context,
    titleStyle: TextStyle,
    bodyStyle: TextStyle,
    density: Density,
    resolver: FontFamily.Resolver,
    private val marker: NativeDetailCardMarker,
    protected val trailing: View,
    private val trailingWidthDp: Int,
    private val trailingHeightDp: Int,
    inputRoot: () -> View? = { null },
    private val style: NativeDetailCardStyle = NativeDetailCardStyle(),
    accessibilityRole: NativeMergedRowRole = NativeMergedRowRole.BUTTON,
) : NativeControlRow(context, accessibilityRole, inputRoot) {
    private val title = ParagraphView(context, titleStyle, density, resolver, 0)
    private val body = ParagraphView(context, bodyStyle, density, resolver, 0)
    private var textHeight = 0
    private var rowPadding = 0
    private var rowGap = 0
    private var stackedText = false
    private var stackedHeader = false
    private var headerHeight = 0
    private var bodyVisible = false
    var onActivate: (() -> Unit)? = null
    override val hasRowAction get() = onActivate != null
    override fun activateRow() { onActivate?.invoke() }
    protected open val includeTrailingAccessibility: Boolean get() = !trailing.contentDescription.isNullOrEmpty()
    override fun accessibilityChildren(): List<View> = buildList {
        marker.text?.let(::add)
        add(title)
        if (bodyVisible) add(body)
        if (includeTrailingAccessibility && trailing.visibility == VISIBLE) add(trailing)
    }

    init {
        clipChildren = false
        clipToPadding = false
        title.isScreenReaderFocusable = false
        body.isScreenReaderFocusable = false
        listOf(marker.view, title, body, trailing).forEach(::addView)
        addRowAccessibilityRole()
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    protected fun bindCard(titleText: String, bodyText: String?, enabled: Boolean, palette: NativeColorScheme,
        checked: Boolean? = null) {
        bindRow(enabled, palette.ink, checked)
        title.setText(titleText)
        title.animateTextColor(palette.ink)
        bodyVisible = !bodyText.isNullOrEmpty()
        body.visibility = if (bodyVisible) VISIBLE else GONE
        body.setText(bodyText.orEmpty())
        body.animateTextColor(palette.muted)
        marker.bind(palette)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        rowPadding = dp(style.paddingDp)
        rowGap = dp(style.gapDp)
        val markerSize = dp(style.markerSizeDp)
        val trailWidth = dp(trailingWidthDp)
        val trailHeight = dp(trailingHeightDp)
        val titleWidth = title.maxIntrinsicTextWidth()
        var textWidth = (width - rowPadding * 2 - markerSize - trailWidth - rowGap * 2).coerceAtLeast(0)
        // Keep released geometry while it fits; narrow or enlarged text gets space, not a smaller font.
        if (titleWidth > textWidth) {
            rowPadding = dp(style.compactPaddingDp)
            rowGap = dp(style.compactGapDp)
            textWidth = (width - rowPadding * 2 - markerSize - trailWidth - rowGap * 2).coerceAtLeast(0)
        }
        stackedText = titleWidth > textWidth
        stackedHeader = stackedText && width - rowPadding * 2 < markerSize + trailWidth + rowGap
        headerHeight = if (stackedHeader) markerSize + rowGap + trailHeight else max(markerSize, trailHeight)
        if (stackedText) textWidth = (width - rowPadding * 2).coerceAtLeast(0)
        marker.view.measure(cardExact(markerSize), cardExact(markerSize))
        trailing.measure(cardExact(trailWidth), cardExact(trailHeight))
        title.measure(cardAtMost(textWidth), cardUnspecified())
        if (bodyVisible) body.measure(cardAtMost(textWidth), cardUnspecified())
        textHeight = title.measuredHeight + if (bodyVisible) dp(style.bodyGapDp) + body.measuredHeight else 0
        val desired = rowPadding * 2 + if (stackedText) headerHeight + rowGap + textHeight
            else max(headerHeight, textHeight)
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
        measureRowRole(measuredWidth, measuredHeight)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val markerY = if (stackedText) {
            rowPadding + if (stackedHeader) 0 else ((headerHeight - marker.view.measuredHeight) / 2f).roundToInt()
        } else ((height - marker.view.measuredHeight) / 2f).roundToInt()
        val trailingY = if (stackedText) {
            rowPadding + if (stackedHeader) marker.view.measuredHeight + rowGap
                else ((headerHeight - trailing.measuredHeight) / 2f).roundToInt()
        } else ((height - trailing.measuredHeight) / 2f).roundToInt()
        placeStart(marker.view, rowPadding, markerY)
        val textX = if (stackedText) rowPadding else rowPadding + marker.view.measuredWidth + rowGap
        val textY = if (stackedText) rowPadding + headerHeight + rowGap else ((height - textHeight) / 2f).roundToInt()
        placeStart(title, textX, textY)
        if (bodyVisible) placeStart(body, textX, textY + title.measuredHeight + dp(style.bodyGapDp))
        placeStart(trailing, width - rowPadding - trailing.measuredWidth, trailingY)
        layoutRowRole()
    }
    private fun placeStart(child: View, start: Int, top: Int) {
        val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
        child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
    }
    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

private fun cardExact(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
private fun cardAtMost(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), View.MeasureSpec.AT_MOST)
private fun cardUnspecified(): Int = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
