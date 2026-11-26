package smallthingz.ui.scroll

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import smallthingz.ui.controls.textSurfaceForeground
import kotlin.math.roundToInt

/** Paint outside the source stretch layer, using the platform fade alpha where available. */
internal class NativeScrollIndicator(
    private val view: NativeSourceRecyclerView,
    private var style: NativeScrollIndicatorStyle,
    private var preferredColor: Int,
) : Drawable() {
    private var transitionUnderlay = 0L
    private var ink = preferredColor
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = RectF()
    private val density get() = view.resources.displayMetrics.density
    private var fadeAlpha = 255
    private var frameAlpha = 0
    // Pie has View scrollbar fading but no public API to install the styled thumb drawable.
    private val legacyFade = if (Build.VERSION.SDK_INT < 29) LegacyScrollbarFade(view) else null

    fun bind(style: NativeScrollIndicatorStyle, color: Int): Boolean {
        val styleChanged = this.style != style
        this.style = style
        if (preferredColor == color) return styleChanged
        preferredColor = color
        return updateInk() || styleChanged
    }

    fun bindTransitionUnderlay(surface: Long): Boolean {
        if (transitionUnderlay == surface) return false
        transitionUnderlay = surface
        return updateInk()
    }

    private fun updateInk(): Boolean {
        val color = textSurfaceForeground(transitionUnderlay, preferredColor)
        if (ink == color) return false
        ink = color
        return true
    }

    override fun getIntrinsicWidth(): Int = style.contentGutterPx(density)
    override fun setAlpha(alpha: Int) { fadeAlpha = alpha }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    override fun draw(canvas: Canvas) { frameAlpha = fadeAlpha }

    fun startFrame() {
        frameAlpha = 0
        thumb.setEmpty()
    }

    fun awakenLegacy() { legacyFade?.awaken() }

    fun reset() {
        startFrame()
        legacyFade?.reset()
    }

    fun paintThumb(canvas: Canvas, dragging: Boolean) {
        val fade = if (dragging) 255 else legacyFade?.alpha() ?: frameAlpha
        val alpha = ((ink ushr 24) * fade + 127) / 255
        if (alpha == 0) return
        val extent = view.computeVerticalScrollExtent()
        if (extent <= 0) return
        val range = view.computeVerticalScrollRange()
        if (range <= extent) return
        val density = density
        withNativeScrollIndicatorThumb(
            view.width, view.height, range, extent, view.computeVerticalScrollOffset(),
            style.widthDp * density, style.rightInsetDp * density, style.minThumbHeightDp * density,
        ) { left, top, right, bottom ->
            thumb.set(left, top, right, bottom)
            val radius = (right - left) / 2f
            paint.color = (ink and 0x00ffffff) or (alpha shl 24)
            canvas.drawRoundRect(thumb, radius, radius, paint)
        }
    }

    fun contains(x: Float, y: Float): Boolean {
        if (thumb.isEmpty || thumb.height() >= view.height) return false
        // Use the host's clear physical-right padding for touch, with the style's gutter
        // as a fallback when the host reserves less space than the painted thumb requires.
        val gutter = maxOf(view.paddingRight, intrinsicWidth)
        return y >= thumb.top && y < thumb.bottom &&
            x >= view.width - gutter && x < view.width &&
            (view.canScrollVertically(-1) || view.canScrollVertically(1))
    }

    fun grabOffset(y: Float): Float = y - thumb.top

    fun scrollToThumb(top: Float) {
        val extent = view.computeVerticalScrollExtent()
        val range = view.computeVerticalScrollRange()
        val offset = view.computeVerticalScrollOffset()
        if (!top.isFinite() || extent <= 0 || range <= extent) return
        val density = density
        withNativeScrollIndicatorThumb(
            view.width, view.height, range, extent, 0,
            style.widthDp * density, style.rightInsetDp * density, style.minThumbHeightDp * density,
        ) { _, _, _, bottom ->
            val travel = view.height - bottom
            if (travel <= 0f) return
            val overflow = range - extent
            val fraction = (top.toDouble() / travel).coerceIn(0.0, 1.0)
            val target = fraction * overflow
            val delta = target.roundToInt() - offset.coerceIn(0, overflow)
            val layout = view.layoutManager as? LinearLayoutManager
            if (layout != null && layout.orientation == RecyclerView.VERTICAL && !layout.reverseLayout) {
                seekLinear(layout, fraction, target, delta, range)
            } else {
                view.scrollBy(0, delta.coerceIn(-view.height, view.height))
            }
        }
    }

    private fun seekLinear(layout: LinearLayoutManager, fraction: Double, target: Double, delta: Int, range: Int) {
        val count = layout.itemCount
        if (count == 0) return
        val placement = when {
            fraction == 0.0 -> NativeScrollIndicatorPlacement.Start
            fraction == 1.0 -> NativeScrollIndicatorPlacement.End
            delta in -view.height..view.height && !view.isLayoutRequested -> {
                view.scrollBy(0, delta)
                return
            }
            else -> {
                // A large scrollBy would layout intermediate rows in this input event. Use the
                // same visible-average estimate as the scrollbar to place a single local anchor.
                val averageItemHeight = range.toDouble() / count
                val position = (target / averageItemHeight).toInt().coerceIn(0, count - 1)
                val offset = (position * averageItemHeight - target).roundToInt()
                NativeScrollIndicatorPlacement(position, offset)
            }
        }
        view.requestScrollIndicatorPlacement(layout, placement)
    }
}

internal class NativeScrollIndicatorPlacement(private val position: Int, private val offset: Int) {
    fun apply(layout: LinearLayoutManager, itemCount: Int): Boolean {
        if (itemCount <= 0) return false
        if (position != RecyclerView.NO_POSITION) {
            layout.scrollToPositionWithOffset(position.coerceAtMost(itemCount - 1), offset)
        } else {
            val end = itemCount - 1
            // The offscreen end anchor is placed against the viewport end by LLM. If it
            // is already attached, anchor using its measured extent, decoration and margins.
            val last = layout.findViewByPosition(end)
            if (last == null) {
                layout.scrollToPosition(end)
            } else {
                val params = last.layoutParams as RecyclerView.LayoutParams
                val startAtEnd = layout.height - layout.paddingBottom - layout.paddingTop -
                    layout.getDecoratedMeasuredHeight(last) - params.topMargin - params.bottomMargin
                layout.scrollToPositionWithOffset(end, startAtEnd)
            }
        }
        return true
    }

    companion object {
        val Start = NativeScrollIndicatorPlacement(0, 0)
        val End = NativeScrollIndicatorPlacement(RecyclerView.NO_POSITION, 0)
    }
}

private class LegacyScrollbarFade(private val view: NativeSourceRecyclerView) {
    private val duration = ViewConfiguration.getScrollBarFadeDuration().coerceAtLeast(1)
    private var fadeAt = 0L
    private var wakePending = false
    private val canSchedule get() = view.scrollIndicatorPresented && view.isAttachedToWindow && view.isShown
    private val wake = Runnable {
        wakePending = false
        if (canSchedule) view.invalidate()
    }

    fun awaken() {
        if (!view.scrollIndicatorPresented || (view.isAttachedToWindow && !view.isShown)) return
        fadeAt = SystemClock.uptimeMillis() + view.scrollBarDefaultDelayBeforeFade
    }

    fun alpha(): Int {
        if (fadeAt == 0L) return 0
        val untilFade = fadeAt - SystemClock.uptimeMillis()
        if (untilFade > 0) {
            if (!wakePending && canSchedule) {
                wakePending = view.postDelayed(wake, untilFade)
            }
            return 255
        }
        if (untilFade <= -duration.toLong()) return 0
        if (canSchedule) view.postInvalidateOnAnimation()
        return (255L * (duration + untilFade) / duration).toInt()
    }

    fun reset() {
        if (wakePending) view.removeCallbacks(wake)
        wakePending = false
        fadeAt = 0L
    }
}
