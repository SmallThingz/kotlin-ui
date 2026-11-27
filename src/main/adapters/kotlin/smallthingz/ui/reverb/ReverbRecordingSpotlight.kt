package smallthingz.ui.reverb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.animation.view.ViewFrameLoop
import smallthingz.ui.input.NativeAndroidInteropHost
import smallthingz.ui.input.NativeAndroidInteropRequests
import smallthingz.ui.input.phase.NativePointerLayerShape

/** Paints and admits input only outside the actual, clipped card. Never reparents or redraws it. */
class ReverbRecordingSpotlight(
    context: Context,
    environment: ReverbEnvironment,
    private val cardPath: (Path) -> Unit,
    animationScope: CoroutineScope,
    private val hidden: (ReverbRecordingSpotlight) -> Unit,
    dismiss: () -> Unit,
) : View(context), NativePointerLayerShape, NativeAndroidInteropHost, AutoCloseable {
    override val nativeInteropRequests = NativeAndroidInteropRequests()
    private var shown = false
    private var closed = false
    private val fade = ReverbFadeMotion()
    private val frames: ViewFrameLoop = ViewFrameLoop(this, animationScope,
        { !closed && fade.running }) { time, scale ->
        fade.frame(time, scale)
        publishFade()
    }
    private val hole = Path()
    private val holeBounds = RectF()
    private val hitRegion by lazy { Region() }
    private val hitClip by lazy { Region() }
    private var color = environment.appearance.backdrops.panelScrimColor(environment.palette)
    init {
        // Dismissal is exposed by the expanded summary and Rename's Cancel. A full-screen
        // semantic rectangle here would incorrectly occlude the card's actual accessible nodes.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        alpha = 0f
        setOnClickListener { if (shown && !closed) dismiss() }
        isEnabled = false
    }
    fun bindEnvironment(environment: ReverbEnvironment) {
        color = environment.appearance.backdrops.panelScrimColor(environment.palette)
        invalidate()
    }
    /** Freeze geometry while the former modal's input/owner retires immediately. */
    fun setShown(value: Boolean) {
        if (closed) return
        if (shown && !value) cardPath(hole)
        shown = value
        isEnabled = value
        fade.target(value, frames.durationScale)
        publishFade()
        frames.request()
    }
    private fun publishFade() {
        alpha = fade.alpha
        invalidate()
        if (!shown && !fade.running) hidden(this)
    }
    override fun onDraw(canvas: Canvas) {
        if (shown) cardPath(hole)
        val save = canvas.save()
        canvas.clipOutPath(hole)
        canvas.drawColor(color)
        canvas.restoreToCount(save)
    }
    override fun containsPointerLayer(x: Float, y: Float): Boolean {
        if (!shown || closed) return false
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= width || y >= height) return false
        cardPath(hole)
        hole.computeBounds(holeBounds, true)
        if (!holeBounds.contains(x, y)) return true
        hitClip.set(0, 0, width, height)
        hitRegion.setPath(hole, hitClip)
        return !hitRegion.contains(x.toInt(), y.toInt())
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!shown || closed) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            try { super.onTouchEvent(cancel) } finally { cancel.recycle() }
            return false
        }
        // A gesture starting outside must not activate anything if it ends back inside the card.
        if (event.actionMasked == MotionEvent.ACTION_UP && !containsPointerLayer(event.x, event.y)) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            return try { super.onTouchEvent(cancel) } finally { cancel.recycle() }
        }
        return super.onTouchEvent(event)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); publishFade(); frames.request() }
    override fun onDetachedFromWindow() {
        frames.cancel()
        fade.settle(shown)
        alpha = fade.alpha
        super.onDetachedFromWindow()
    }
    override fun close() {
        if (closed) return
        closed = true
        shown = false
        isEnabled = false
        setOnClickListener(null)
        frames.cancel()
        fade.settle(false)
        hole.rewind()
        alpha = 0f
    }
}
