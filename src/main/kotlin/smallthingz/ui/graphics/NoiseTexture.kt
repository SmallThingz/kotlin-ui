package smallthingz.ui.graphics

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.MainThread
import java.lang.ref.WeakReference

/**
 * Process-shared deterministic grain: one 1024-square ARGB bitmap and at most two cached shaders.
 *
 * [prepare] schedules at most one background daemon per generation, including on failure.
 * Explicit UI-hidden retirement permits a new generation; drawing never waits for its worker.
 * Generation reuses one 64 KiB pixel strip. There is no disk cache or asset copy.
 * Pending drawables are weakly held and invalidated on the main thread after generation settles.
 */
object NoiseTexture {
    private fun newTile() = NoiseTileCache {
        val bitmap = Bitmap.createBitmap(NOISE_TILE_SIZE, NOISE_TILE_SIZE, Bitmap.Config.ARGB_8888)
        try {
            bitmap.density = Bitmap.DENSITY_NONE
            writeNoiseStrips { y, pixels ->
                bitmap.setPixels(pixels, 0, NOISE_TILE_SIZE, 0, y, NOISE_TILE_SIZE, NOISE_STRIP_HEIGHT)
            }
            bitmap
        } catch (failure: Throwable) {
            // No observer can own this bitmap until every strip has been written successfully.
            try {
                bitmap.recycle()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }
    private var tile = newTile()
    private val main = Handler(Looper.getMainLooper())
    private val drawables = mutableListOf<WeakReference<NoiseDrawable>>()
    private val pending = mutableListOf<WeakReference<NoiseDrawable>>()
    private var backgroundShader: BitmapShader? = null
    private var topBarShader: BitmapShader? = null

    fun prepare() {
        val generation = synchronized(this) { tile }
        generation.start(execute = { work ->
            Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                } catch (error: SecurityException) {
                    Log.w("SmallthingzNoise", "Unable to lower grain worker priority", error)
                }
                work()
            }, "smallthingz-ui-noise").apply { isDaemon = true }.start()
        }, onComplete = {
            generation.failureOrNull()?.let { Log.e("SmallthingzNoise", "Unable to generate background grain", it) }
            main.post {
                val notify = synchronized(this) {
                    if (tile !== generation) return@post
                    pending.toList().also { pending.clear() }
                }
                notify.forEach { it.get()?.invalidateSelf() }
            }
        })
    }

    internal fun register(drawable: NoiseDrawable) {
        synchronized(this) {
            drawables.removeAll { it.get() == null }
            drawables.add(WeakReference(drawable))
        }
    }

    /**
     * Release reconstructible grain only after the host establishes that ALL windows in this
     * process are hidden (for example its TRIM_MEMORY_UI_HIDDEN boundary). Never call on a single
     * Activity pause/stop or focus loss: a floating/overlay window may still be visible.
     * Published bitmaps are not recycled; rendering can retain old display-list references.
     */
    @MainThread
    fun releaseUiResources() {
        check(Looper.myLooper() === Looper.getMainLooper()) { "Grain retirement requires the UI thread" }
        val owners = synchronized(this) {
            tile.retire()
            tile = newTile()
            backgroundShader = null
            topBarShader = null
            pending.clear()
            drawables.removeAll { it.get() == null }
            drawables.mapNotNull { it.get() }
        }
        owners.forEach { it.releaseUiResources() }
    }

    internal fun observe(drawable: NoiseDrawable) {
        synchronized(this) {
            if (tile.valueOrNull() == null && tile.failureOrNull() == null) {
                pending.removeAll { it.get() == null }
                if (pending.none { it.get() === drawable }) pending.add(WeakReference(drawable))
            }
        }
        prepare()
    }

    /**
     * Returns null until generation succeeds. The background/top-bar phases return shared shaders;
     * callers must not change their local matrices. Other phases are caller-owned, not cached.
     */
    fun shader(seed: Int): BitmapShader? = synchronized(this) {
        val bitmap = tile.valueOrNull() ?: return null
        fun createShader() =
            BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                if (seed != NoiseDrawable.SEED_BACKGROUND) {
                    val x = Math.floorMod(seed * 37, NOISE_TILE_SIZE).toFloat()
                    val y = Math.floorMod(seed * 61, NOISE_TILE_SIZE).toFloat()
                    setLocalMatrix(Matrix().apply { setTranslate(x, y) })
                }
            }
        // Only the two standard phases occupy the process cache; arbitrary callers own their shader.
        when (seed) {
            NoiseDrawable.SEED_BACKGROUND -> backgroundShader ?: createShader().also { backgroundShader = it }
            NoiseDrawable.SEED_TOP_BAR -> topBarShader ?: createShader().also { topBarShader = it }
            else -> createShader()
        }
    }
}
