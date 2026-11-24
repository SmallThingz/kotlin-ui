package smallthingz.ui.components

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import kotlin.math.min

/** Uniform capsule ink follows the painted pill, not each child's unpainted selection tween. */
internal class NativeSelectionForeground(
    private val host: ViewGroup,
    private val indicator: NativeSelectionIndicator,
    private val radius: () -> Float,
    private val surfaceColor: () -> Int?,
    private val surfaceRange: (() -> Long)? = null,
) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener, AutoCloseable {
    private val bounds = RectF()
    private val path = Path()
    private var corner = 0f
    private var surface = 0
    private var fill = 0
    private var trackRange = 0L
    private var pillRange = 0L
    private var visible = false
    private var initialized = false
    private var closed = false
    var isAvailable = false
        private set
    private var observer: ViewTreeObserver? = null

    init {
        host.addOnAttachStateChangeListener(this)
        if (host.isAttachedToWindow) onViewAttachedToWindow(host)
    }

    override fun onPreDraw(): Boolean { prepare(); return true }

    fun prepare() {
        if (closed) return
        val nextBounds = indicator.paintedBounds
        val nextCorner = min(radius(), min(nextBounds.width(), nextBounds.height()) / 2f).coerceAtLeast(0f)
        val nextPaintedSurface = surfaceColor()?.takeIf { it ushr 24 == 255 }
        val nextSurface = nextPaintedSurface ?: 0
        val nextFill = indicator.paintedColor
        val nextTrackRange = surfaceRange?.invoke() ?: smallthingz.ui.controls.textSurfaceRange(nextSurface)
        val nextPillRange = indicator.surfaceRangeOver(nextTrackRange)
        val nextVisible = indicator.isVisible && nextFill ushr 24 != 0
        val geometryChanged = !initialized || bounds != nextBounds || corner != nextCorner ||
            surface != nextSurface || fill != nextFill || visible != nextVisible ||
            trackRange != nextTrackRange || pillRange != nextPillRange
        isAvailable = nextPaintedSurface != null && (nextTrackRange ushr 56).toInt() == 255 &&
            nextTrackRange.toInt() ushr 24 == 255
        if (geometryChanged) {
            initialized = true
            bounds.set(nextBounds)
            corner = nextCorner
            surface = nextSurface
            trackRange = nextTrackRange
            pillRange = nextPillRange
            fill = nextFill
            visible = nextVisible
            path.rewind()
            if (visible) path.addRoundRect(bounds, corner, corner, Path.Direction.CW)
        }
        for (index in 0 until host.childCount) {
            val child = host.getChildAt(index) as NativeChip
            // Unknown custom paint and explicit styled content retain their former renderer.
            child.uniformSelectionForeground = if (isAvailable && child.hasUniformSelectionContent()) this else null
            if (child.uniformSelectionForeground != null) child.prepareUniformSelectionInk(surface, fill, trackRange, pillRange)
            // Child display lists use the same sampled geometry and colors as the parent.
            if (geometryChanged) child.invalidate()
        }
    }

    fun draw(canvas: Canvas, child: NativeChip, contentBounds: RectF, content: (Canvas) -> Unit) {
        val ink = checkNotNull(child.uniformSelectionInk)
        val trackInk = ink.trackInk
        val pillInk = ink.pillInk
        val left = contentBounds.left + child.left
        val top = contentBounds.top + child.top
        val right = contentBounds.right + child.left
        val bottom = contentBounds.bottom + child.top
        if (!visible || right <= bounds.left || left >= bounds.right || bottom <= bounds.top || top >= bounds.bottom) {
            drawInk(canvas, child, trackInk, content)
        } else if (trackInk == pillInk) {
            drawInk(canvas, child, trackInk, content)
        } else if (contains(left, top) && contains(right, top) && contains(left, bottom) && contains(right, bottom)) {
            drawInk(canvas, child, pillInk, content)
        } else {
            drawClipped(canvas, child, trackInk, false, content)
            drawClipped(canvas, child, pillInk, true, content)
        }
    }

    private fun contains(x: Float, y: Float): Boolean {
        if (x < bounds.left || x > bounds.right || y < bounds.top || y > bounds.bottom) return false
        val dx = maxOf(bounds.left + corner - x, 0f, x - (bounds.right - corner))
        val dy = maxOf(bounds.top + corner - y, 0f, y - (bounds.bottom - corner))
        return dx * dx + dy * dy <= corner * corner
    }

    private fun drawClipped(canvas: Canvas, child: NativeChip, ink: Int, inside: Boolean, content: (Canvas) -> Unit) {
        val save = canvas.save()
        try {
            canvas.translate(-child.left.toFloat(), -child.top.toFloat())
            if (inside) canvas.clipPath(path) else canvas.clipOutPath(path)
            canvas.translate(child.left.toFloat(), child.top.toFloat())
            drawInk(canvas, child, ink, content)
        } finally { canvas.restoreToCount(save) }
    }

    private fun drawInk(canvas: Canvas, child: NativeChip, ink: Int, content: (Canvas) -> Unit) {
        if ((child.currentTextColor or -0x1000000) == ink) {
            content(canvas)
            return
        }
        // Tint only foreground pixels. Background, ripple and semantics are drawn/owned once.
        // SRC_IN preserves glyph antialiasing and disabled alpha without per-pass View setters.
        val save = canvas.saveLayer(0f, 0f, child.width.toFloat(), child.height.toFloat(), null)
        try {
            content(canvas)
            canvas.drawColor(ink, PorterDuff.Mode.SRC_IN)
        } finally { canvas.restoreToCount(save) }
    }

    override fun onViewAttachedToWindow(view: View) {
        if (closed) return
        observer = host.viewTreeObserver.also { it.addOnPreDrawListener(this) }
    }
    override fun onViewDetachedFromWindow(view: View) {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = null
        initialized = false
    }
    override fun close() {
        if (closed) return
        closed = true
        onViewDetachedFromWindow(host)
        host.removeOnAttachStateChangeListener(this)
        for (index in 0 until host.childCount) {
            val child = host.getChildAt(index) as NativeChip
            if (child.uniformSelectionForeground === this) child.uniformSelectionForeground = null
        }
    }
}

/** Per-capsule cache; no contrast scans or state-list mutation during foreground passes. */
internal class NativeSelectionInk {
    private var initialized = false
    private var surface = 0
    private var fill = 0
    private var preferred = 0
    private var indication = 0
    var trackInk = 0
        private set
    var pillInk = 0
        private set
    var rippleColor = 0
        private set

    private var trackRange = 0L
    private var pillRange = 0L

    fun update(surface: Int, fill: Int, preferred: Int, indication: Int,
        trackRange: Long = smallthingz.ui.controls.textSurfaceRange(surface),
        pillRange: Long = smallthingz.ui.graphics.noiseSurfaceRangeOver(trackRange, fill, 0),
    ): Boolean {
        if (initialized && this.surface == surface && this.fill == fill && this.preferred == preferred &&
            this.indication == indication && this.trackRange == trackRange && this.pillRange == pillRange) return false
        initialized = true
        this.surface = surface; this.fill = fill; this.preferred = preferred; this.indication = indication
        this.trackRange = trackRange; this.pillRange = pillRange
        val track = smallthingz.ui.controls.textSurfaceForegroundWithIndication(trackRange, preferred, indication)
        val pill = smallthingz.ui.controls.textSurfaceForegroundWithIndication(pillRange, preferred, indication)
        trackInk = track.toInt()
        pillInk = pill.toInt()
        val alpha = minOf((track ushr 32).toInt(), (pill ushr 32).toInt())
        rippleColor = (indication and 0x00ffffff) or (alpha shl 24)
        return true
    }
}
