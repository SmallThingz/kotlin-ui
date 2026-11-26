package smallthingz.ui.gallery

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ScrollView
import androidx.annotation.RequiresApi
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.controls.textSurfaceForeground
import smallthingz.ui.scroll.NativeScrollIndicatorStyle
import kotlin.math.roundToInt

/** A persistent overflow cue on the caller's platform ScrollView, not another scroll owner.
 * The caller retains sufficient physical-right content inset; this binding changes no padding,
 * measurement, input listener or accessibility node. Platform range/length rules remain in charge.
 * API28 keeps the stock themed artwork; API29+ uses a solid Gallery thumb (no grain parity claim).
 * Close with the owning panel. Temporary detach only suspends underlay observation.
 */
class GalleryPlatformScrollIndicator(
    private val scroll: ScrollView,
    private val palette: () -> ControlPalette,
    private val paintedSurface: () -> Long,
    private val style: () -> NativeScrollIndicatorStyle = { GalleryDesign.appearance.scrollIndicator },
) : AutoCloseable {
    private val originalEnabled = scroll.isVerticalScrollBarEnabled
    private val originalFading = scroll.isScrollbarFadingEnabled
    private val originalPosition = scroll.verticalScrollbarPosition
    private val originalStyle = scroll.scrollBarStyle
    private val thumb = if (Build.VERSION.SDK_INT >= 29) Thumb(scroll) else null
    private var observer: ViewTreeObserver? = null
    private var closed = false
    private val beforeDraw = ViewTreeObserver.OnPreDrawListener { refresh(); true }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) { observe(); refresh() }
        override fun onViewDetachedFromWindow(view: View) { stopObserving() }
    }

    init {
        scroll.scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        scroll.verticalScrollbarPosition = View.SCROLLBAR_POSITION_RIGHT
        scroll.isScrollbarFadingEnabled = false
        scroll.addOnAttachStateChangeListener(attachment)
        refresh()
        if (scroll.isAttachedToWindow) observe()
    }

    /** Also covers detached rendering and an owner's explicit appearance refresh. */
    fun refresh() {
        if (closed) return
        val recipe = style()
        val density = scroll.resources.displayMetrics.density
        val valid = recipe.contentGutterPx(density) > 0
        if (scroll.isVerticalScrollBarEnabled != valid) scroll.isVerticalScrollBarEnabled = valid
        if (valid && Build.VERSION.SDK_INT >= 29) {
            thumb?.bind(textSurfaceForeground(paintedSurface(), palette().muted),
                (recipe.widthDp * density).roundToInt().coerceAtLeast(1),
                (recipe.rightInsetDp * density).roundToInt().coerceAtLeast(0))
        }
    }

    private fun observe() {
        if (closed) return
        stopObserving()
        observer = scroll.viewTreeObserver.also { it.addOnPreDrawListener(beforeDraw) }
    }

    private fun stopObserving() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(beforeDraw)
        observer = null
    }

    override fun close() {
        if (closed) return
        closed = true
        stopObserving()
        scroll.removeOnAttachStateChangeListener(attachment)
        if (Build.VERSION.SDK_INT >= 29) thumb?.restore()
        scroll.isVerticalScrollBarEnabled = originalEnabled
        scroll.isScrollbarFadingEnabled = originalFading
        scroll.verticalScrollbarPosition = originalPosition
        scroll.scrollBarStyle = originalStyle
    }

    @RequiresApi(29)
    private class Thumb(private val scroll: ScrollView) {
        private val originalThumb = scroll.verticalScrollbarThumbDrawable
        private val originalTrack = scroll.verticalScrollbarTrackDrawable
        private var shape: GradientDrawable? = null
        private var width = -1
        private var inset = -1
        private var color: Int? = null

        fun bind(ink: Int, widthPx: Int, insetPx: Int) {
            if (width != widthPx || inset != insetPx) {
                width = widthPx
                inset = insetPx
                shape = GradientDrawable().apply {
                    this.shape = GradientDrawable.RECTANGLE
                    cornerRadius = widthPx / 2f
                    setSize(widthPx, 0) // Platform, not this drawable, owns thumb height.
                    setColor(ink)
                }
                scroll.verticalScrollbarTrackDrawable = null
                scroll.verticalScrollbarThumbDrawable = InsetDrawable(checkNotNull(shape), 0, 0, insetPx, 0)
                color = ink
            } else if (color != ink) {
                shape?.setColor(ink)
                color = ink
            }
        }

        fun restore() {
            scroll.verticalScrollbarThumbDrawable = originalThumb
            scroll.verticalScrollbarTrackDrawable = originalTrack
        }
    }
}
