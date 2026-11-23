package smallthingz.ui.reverb.home


/** One body's released remember(readout inputs), independent of its changing flip transform. */
class ReverbHomeReadoutCache<M : Any>(
    private val isTimePrimary: (M) -> Boolean,
    private val formatTime: (Float) -> String,
    private val formatSize: (Long) -> String,
) {
    private var cached: ReverbHomeReadout? = null
    private var mode: M? = null
    private var disabled = false
    private var secondsBits = 0
    private var bytes = 0L
    private var disabledLabel = ""

    fun read(
        mode: M?,
        disabled: Boolean,
        seconds: Float,
        bytes: Long,
        disabledLabel: String,
    ): ReverbHomeReadout {
        val bits = seconds.toRawBits()
        val previous = cached
        if (previous != null && this.mode == mode && this.disabled == disabled &&
            secondsBits == bits && this.bytes == bytes && this.disabledLabel == disabledLabel
        ) return previous
        val next = reverbHomeReadout(mode, disabled, seconds, bytes, disabledLabel, isTimePrimary, formatTime, formatSize)
        this.mode = mode
        this.disabled = disabled
        secondsBits = bits
        this.bytes = bytes
        this.disabledLabel = disabledLabel
        cached = next
        return next
    }

    /** The window replaces its environment after configuration/theme changes. */
    fun invalidate() { cached = null }
}

data class ReverbHomeBufferMetrics(val seconds: Float, val bytes: Long)

data class ReverbHomeReadout(val primary: String, val secondary: String?)

fun <M : Any> reverbHomeReadout(
    mode: M?,
    disabled: Boolean,
    seconds: Float,
    bytes: Long,
    disabledLabel: String,
    isTimePrimary: (M) -> Boolean,
    formatTime: (Float) -> String,
    formatSize: (Long) -> String,
): ReverbHomeReadout {
    if (disabled) return ReverbHomeReadout(disabledLabel, null)
    if (mode == null) return ReverbHomeReadout("—", null)
    val time = formatTime(seconds.coerceAtLeast(0f))
    val size = formatSize(bytes.coerceAtLeast(0L))
    return if (isTimePrimary(mode)) ReverbHomeReadout(time, size) else ReverbHomeReadout(size, time)
}
