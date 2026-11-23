package smallthingz.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.input.NativeAndroidInteropHost
import smallthingz.ui.input.NativeAndroidInteropRequests
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Title, retained rows and conditional footer of the tile settings surface. */
open class NativeQuickTileSectionLayout(
    context: Context,
    private var environment: NativeSettingsVisualEnvironment,
    titleText: String,
    loadingText: String,
    applyFirstText: String,
    inputRoot: () -> View?,
    onPaintedSurfaceChanged: (View) -> Unit,
    onRetry: () -> Unit,
) : ViewGroup(context) {
    private val recipe get() = environment.appearance.quickTiles
    private val title = environment.paragraph(context, titleText, NativeTypographyRole.TITLE_MEDIUM)
    private val surface = NativeGroupLayout(context, recipe.surfaceRadiusDp, onPaintedSurfaceChanged = onPaintedSurfaceChanged)
    private val rows = TileRowsColumn(context)
    private val loading = tileStatus(context, loadingText, environment)
    protected val loadingRow = NativeSingleChildInset(context, loading, 16, 16, 16, 16, environment, false)
    private val applyFirst = tileStatus(context, applyFirstText, environment)
    private val error = tileStatus(context, "", environment)
    private val retry = NativeProgressActionView(context, environment, inputRoot, action = onRetry)
    private var ordered = emptyList<View>()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        surface.addRow(rows, NativeGroupLayout.LayoutParams(-1, -2))
        addView(title)
        addView(surface)
        addView(applyFirst)
        addView(error)
        addView(retry)
    }

    protected fun bindListAppearance(next: NativeSettingsVisualEnvironment) {
        environment = next
        val colors = next.palette
        next.bindParagraphStyle(title, NativeTypographyRole.TITLE_MEDIUM, colors.ink)
        surface.setPalette(colors.field, colors.border)
        rows.dividerColor = colors.border
    }

    protected fun bindLoading() {
        environment.bindParagraphStyle(loading, NativeTypographyRole.BODY_SMALL, environment.palette.muted)
        loadingRow.setEnvironment(environment)
    }

    protected fun bindListContent(children: List<View>, showApplyFirst: Boolean,
                                  failure: String?, retryText: String, retryEnabled: Boolean) {
        rows.bind(children)
        applyFirst.visibility = if (showApplyFirst) VISIBLE else GONE
        environment.bindParagraphStyle(applyFirst, NativeTypographyRole.BODY_SMALL, environment.palette.muted)
        bindStatus(error, failure, environment)
        retry.visibility = error.visibility
        retry.bind(retryText, retryEnabled, environment)
        ordered = listOfNotNull(title, surface,
            applyFirst.takeIf { it.visibility != GONE },
            error.takeIf { it.visibility != GONE }, retry.takeIf { it.visibility != GONE })
        ordered.forEach(::bringChildToFront)
    }

    protected fun closeRetry() = retry.close()
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val inner = (width - 2 * dp(recipe.sectionHorizontalInsetDp)).coerceAtLeast(0)
        var height = 2 * dp(recipe.sectionVerticalInsetDp)
        val children = ordered
        for (index in children.indices) {
            val child = children[index]
            val fill = child === surface
            child.measure(if (fill) exact(inner) else atMost(inner), unspecified())
            height += child.measuredHeight + if (index == 0) 0 else dp(recipe.sectionGapDp)
        }
        setMeasuredDimension(resolveSize(width, w), resolveSize(height, h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = dp(recipe.sectionVerticalInsetDp)
        val children = ordered
        for (index in children.indices) {
            val child = children[index]
            if (index != 0) y += dp(recipe.sectionGapDp)
            placeTileLogical(child, dp(recipe.sectionHorizontalInsetDp), y)
            y += child.measuredHeight
        }
    }

}

/** Source Column has no inter-row gap; only one physical-pixel divider over later row content. */
private class TileRowsColumn(context: Context) : ViewGroup(context) {
    private var ordered = emptyList<View>()
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val colorMotion = ViewColorTransition(this, intArrayOf(divider.color), ::publishDivider)
    private fun publishDivider() {
        divider.color = colorMotion.color(0)
        invalidate()
    }
    var dividerColor: Int
        get() = divider.color
        set(value) {colorMotion.retarget(value)}
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun bind(next: List<View>) {
        for (old in ordered) if (old !in next) removeView(old)
        for (child in next) {
            if (child.parent == null) addView(child)
            bringChildToFront(child)
        }
        ordered = next
        requestLayout()
    }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        var height = 0
        val children = ordered
        for (index in children.indices) {
            val child = children[index]
            child.measure(exact(width), unspecified())
            height += child.measuredHeight
        }
        setMeasuredDimension(width, resolveSize(height, h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = 0
        val children = ordered
        for (index in children.indices) {
            val child = children[index]
            child.layout(0, y, width, y + child.measuredHeight)
            y += child.measuredHeight
        }
    }
    override fun drawChild(canvas: Canvas, child: View, time: Long): Boolean {
        val result = super.drawChild(canvas, child, time)
        if (ordered.indexOf(child) > 0) {
            divider.strokeWidth = 1f
            canvas.drawLine(0f, child.top + .5f, width.toFloat(), child.top + .5f, divider)
        }
        return result
    }
}

/** Icon, two-line description and retained trailing actions for one row. */
open class NativeQuickTileRowView(
    context: Context,
    private var environment: NativeSettingsVisualEnvironment,
    icon: Bitmap,
    private val pin: NativeStandardIconButtonView,
    private val remove: NativeStandardIconButtonView,
) : ViewGroup(context), AutoCloseable {
    private val recipe get() = environment.appearance.quickTiles
    private val bitmap = QuickTileBitmapView(context, icon, environment.palette.ink)
    private val buffer = environment.paragraph(context, "", NativeTypographyRole.BODY_LARGE, environment.palette.onSurface)
    private val duration = environment.paragraph(context, "", NativeTypographyRole.BODY_SMALL, environment.palette.muted)
    private var textHeight = 0
    private var columnWidth = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(bitmap)
        addView(buffer)
        addView(duration)
        addView(pin)
        addView(remove)
    }

    protected fun bindRow(next: NativeSettingsVisualEnvironment, title: String, value: String, showPin: Boolean) {
        environment = next
        bitmap.tint = next.palette.ink
        buffer.setText(title)
        duration.setText(value)
        next.bindParagraphStyle(buffer, NativeTypographyRole.BODY_LARGE, next.palette.onSurface)
        next.bindParagraphStyle(duration, NativeTypographyRole.BODY_SMALL, next.palette.muted)
        if (!showPin && pin.visibility != GONE) pin.cancelInput()
        pin.visibility = if (showPin) VISIBLE else GONE
    }

    protected fun bindIcon(icon: Bitmap) = bitmap.bind(icon)

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        var remaining = (width - dp(recipe.rowStartInsetDp) - dp(recipe.rowEndInsetDp)).coerceAtLeast(0)
        bitmap.measure(exact(min(dp(recipe.iconSizeDp), remaining)), exact(dp(recipe.iconSizeDp)))
        remaining -= bitmap.measuredWidth
        if (pin.visibility != GONE) {
            pin.measure(atMost(remaining), unspecified())
            remaining -= pin.measuredWidth
        }
        remove.measure(atMost(remaining), unspecified())
        remaining -= remove.measuredWidth
        columnWidth = remaining.coerceAtLeast(0)
        val textWidth = (columnWidth - 2 * dp(recipe.rowLabelInsetDp)).coerceAtLeast(0)
        buffer.measure(atMost(textWidth), unspecified())
        duration.measure(atMost(textWidth), unspecified())
        textHeight = buffer.measuredHeight + duration.measuredHeight
        val rowHeight = max(max(bitmap.measuredHeight, textHeight),
            max(remove.measuredHeight, if (pin.visibility != GONE) pin.measuredHeight else 0))
        setMeasuredDimension(width, resolveSize(rowHeight + 2 * dp(recipe.rowVerticalInsetDp), h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var start = dp(recipe.rowStartInsetDp)
        placeTileLogical(bitmap, start, ((height - bitmap.measuredHeight) / 2f).roundToInt())
        start += bitmap.measuredWidth
        val y = ((height - textHeight) / 2f).roundToInt()
        placeTileLogical(buffer, start + dp(recipe.rowLabelInsetDp), y)
        placeTileLogical(duration, start + dp(recipe.rowLabelInsetDp), y + buffer.measuredHeight)
        start += columnWidth
        if (pin.visibility != GONE) {
            placeTileLogical(pin, start, ((height - pin.measuredHeight) / 2f).roundToInt())
            start += pin.measuredWidth
        }
        placeTileLogical(remove, start, ((height - remove.measuredHeight) / 2f).roundToInt())
    }
    override fun close() { pin.cancelInput(); remove.cancelInput() }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}

private class QuickTileBitmapView(context: Context, initialBitmap: Bitmap, color: Int) : View(context) {
    private var bitmap = initialBitmap
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = Rect()
    var tint: Int = color
        set(value) {
            if (field == value) return
            field = value
            paint.colorFilter = PorterDuffColorFilter(value, PorterDuff.Mode.SRC_IN)
            invalidate()
        }
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }
    fun bind(next: Bitmap) {
        if (bitmap === next) return
        bitmap = next
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        destination.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, null, destination, paint)
    }
}

internal fun tileStatus(context: Context, value: String, environment: NativeSettingsVisualEnvironment) =
    environment.paragraph(context, value, NativeTypographyRole.BODY_SMALL, environment.palette.muted).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
internal fun bindStatus(view: ParagraphView, text: String?, environment: NativeSettingsVisualEnvironment) {
    view.setText(text.orEmpty())
    view.visibility = if (text == null) View.GONE else View.VISIBLE
    environment.bindParagraphStyle(view, NativeTypographyRole.BODY_SMALL, environment.palette.muted)
}
private fun ViewGroup.placeTileLogical(child: View, start: Int, top: Int) {
    val x = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
    child.layout(x, top, x + child.measuredWidth, top + child.measuredHeight)
}
