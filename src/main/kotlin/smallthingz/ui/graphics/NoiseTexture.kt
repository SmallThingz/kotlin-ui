package smallthingz.ui.graphics

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.lang.ref.WeakReference

/**
 * Process-shared deterministic grain: one 1024-square ARGB bitmap and at most two cached shaders.
 *
 * [prepare] schedules at most one background daemon, including on failure; it never blocks drawing.
 * Generation reuses one 64 KiB pixel strip. There is no disk cache or asset copy.
 * Pending drawables are weakly held and invalidated on the main thread after generation settles.
 */
object NoiseTexture {
    private val tile = NoiseTileCache {
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
    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<WeakReference<NoiseDrawable>>()
    private val shaders = mutableMapOf<Int, BitmapShader>()

    fun prepare() {
        tile.start(execute = { work ->
            Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                } catch (error: SecurityException) {
                    Log.w("SmallthingzNoise", "Unable to lower grain worker priority", error)
                }
                work()
            }, "smallthingz-ui-noise").apply { isDaemon = true }.start()
        }, onComplete = {
            tile.failureOrNull()?.let { Log.e("SmallthingzNoise", "Unable to generate background grain", it) }
            main.post {
                val notify = synchronized(this) { pending.toList().also { pending.clear() } }
                notify.forEach { it.get()?.invalidateSelf() }
            }
        })
    }

    internal fun observe(drawable: NoiseDrawable) {
        synchronized(this) {
            if (tile.valueOrNull() == null && tile.failureOrNull() == null) {
                pending.removeAll { it.get() == null }
                pending.add(WeakReference(drawable))
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
        if (seed == NoiseDrawable.SEED_BACKGROUND || seed == NoiseDrawable.SEED_TOP_BAR)
            shaders.getOrPut(seed, ::createShader) else createShader()
    }
}
