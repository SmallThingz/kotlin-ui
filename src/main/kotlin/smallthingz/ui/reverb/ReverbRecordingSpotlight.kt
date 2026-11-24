package smallthingz.ui.reverb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.view.MotionEvent
import android.view.View
import smallthingz.ui.input.NativeAndroidInteropHost
import smallthingz.ui.input.NativeAndroidInteropRequests
import smallthingz.ui.input.phase.NativePointerLayerShape

/** Paints and admits input only outside the actual, clipped card. Never reparents or redraws it. */
class ReverbRecordingSpotlight(
    context: Context,
    environment: ReverbEnvironment,
    private val cardPath: (Path) -> Unit,
    dismiss: () -> Unit,
) : View(context), NativePointerLayerShape, NativeAndroidInteropHost {
    override val nativeInteropRequests = NativeAndroidInteropRequests()
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
        setOnClickListener { dismiss() }
    }
    fun bindEnvironment(environment: ReverbEnvironment) {
        color = environment.appearance.backdrops.panelScrimColor(environment.palette)
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        cardPath(hole)
        val save = canvas.save()
        canvas.clipOutPath(hole)
        canvas.drawColor(color)
        canvas.restoreToCount(save)
    }
    override fun containsPointerLayer(x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= width || y >= height) return false
        cardPath(hole)
        hole.computeBounds(holeBounds, true)
        if (!holeBounds.contains(x, y)) return true
        hitClip.set(0, 0, width, height)
        hitRegion.setPath(hole, hitClip)
        return !hitRegion.contains(x.toInt(), y.toInt())
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A gesture starting outside must not activate anything if it ends back inside the card.
        if (event.actionMasked == MotionEvent.ACTION_UP && !containsPointerLayer(event.x, event.y)) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            return try { super.onTouchEvent(cancel) } finally { cancel.recycle() }
        }
        return super.onTouchEvent(event)
    }
}
