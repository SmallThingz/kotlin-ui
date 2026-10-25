package smallthingz.ui.graphics

/** Version 1 grain: fixed xorshift32 stream, unchanged 1024-pixel period and released ARGB palette. */
const val NOISE_TILE_SIZE = 1024
internal const val NOISE_STRIP_HEIGHT = 16
private const val NOISE_SEED = 0x52455642 // "REVB"; never use a time-, theme- or device-dependent seed.

/** Returns a fresh deterministic ARGB tile. Drawables use the shared [NoiseTexture] instead. */
fun generateNoisePixels(): IntArray = IntArray(NOISE_TILE_SIZE * NOISE_TILE_SIZE).also {
    fillNoisePixels(it, NOISE_SEED)
}

/** The writer consumes each strip synchronously; the same scratch array is reused for every row group. */
internal inline fun writeNoiseStrips(write: (y: Int, pixels: IntArray) -> Unit) {
    val pixels = IntArray(NOISE_TILE_SIZE * NOISE_STRIP_HEIGHT)
    var state = NOISE_SEED
    for (y in 0 until NOISE_TILE_SIZE step NOISE_STRIP_HEIGHT) {
        state = fillNoisePixels(pixels, state)
        write(y, pixels)
    }
}

private fun fillNoisePixels(pixels: IntArray, initialState: Int): Int {
    var state = initialState
    for (index in pixels.indices) {
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)
        val sample = (state ushr 1) % 26
        val alpha = 5 + sample % 13
        pixels[index] = (alpha shl 24) or (if (sample < 13) 0 else 0x00ffffff)
    }
    return state
}

/** One process-owned generation. Scheduling/factory failure is terminal, never a per-frame retry. */
internal class NoiseTileCache<T>(private val generate: () -> T) {
    private var started = false
    @Volatile private var terminal: Result<T>? = null

    fun valueOrNull(): T? = terminal?.getOrNull()
    fun failureOrNull(): Throwable? = terminal?.exceptionOrNull()

    fun start(execute: (() -> Unit) -> Unit, onComplete: () -> Unit) {
        synchronized(this) {
            if (started) return
            started = true
        }
        try {
            execute work@{
                if (terminal != null) return@work
                val result = runCatching(generate)
                if (settle(result)) onComplete()
                // A failure must not leave waiters hanging, but fatal VM/thread errors still propagate.
                result.exceptionOrNull()?.let { if (it is VirtualMachineError || it is ThreadDeath) throw it }
            }
        } catch (error: Throwable) {
            if (settle(Result.failure(error))) onComplete() else throw error
            if (error is VirtualMachineError || error is ThreadDeath) throw error
        }
    }

    private fun settle(result: Result<T>): Boolean = synchronized(this) {
        if (terminal != null) false else {
            terminal = result
            true
        }
    }
}
