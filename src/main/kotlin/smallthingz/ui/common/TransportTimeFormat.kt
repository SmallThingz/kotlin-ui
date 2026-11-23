package smallthingz.ui.common

import kotlin.math.floor
import kotlin.math.max

fun formatRangeTimeInput(seconds: Double): String {
    val safeSeconds = if (seconds.isFinite()) seconds.coerceAtLeast(0.0) else 0.0
    // Display exactly tenths, and floor rather than round so the rendered end time can
    // never jump beyond the actual retained audio boundary.
    val totalTenths = floor(safeSeconds * 10.0).toLong()
    val totalSeconds = totalTenths / 10L
    val tenth = (totalTenths % 10L).toInt()
    val hours = totalSeconds / 3_600L
    val minutes = ((totalSeconds % 3_600L) / 60L).toInt()
    val secs = (totalSeconds % 60L).toInt()
    val base = if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
    } else {
        "${minutes}:${secs.toString().padStart(2, '0')}"
    }
    return "$base.$tenth"
}

private val durationDigits = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')

fun formatDurationInput(seconds: Int): String = formatDurationInput(seconds.toLong())

fun formatDurationInput(seconds: Long): String {
    val total = max(0, seconds)
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val secs = total % 60
    return if (hours > 0) {
        val hs = hours.toString()
        val chars = CharArray(hs.length + 6)
        var i = 0
        for (c in hs) chars[i++] = c
        chars[i++] = ':'; chars[i++] = durationDigits[minutes.toInt() / 10]; chars[i++] = durationDigits[minutes.toInt() % 10]
        chars[i++] = ':'; chars[i++] = durationDigits[secs.toInt() / 10]; chars[i] = durationDigits[secs.toInt() % 10]
        String(chars)
    } else {
        val m = minutes.toInt()
        val s = secs.toInt()
        if (m >= 10) {
            val chars = CharArray(5)
            chars[0] = durationDigits[m / 10]; chars[1] = durationDigits[m % 10]
            chars[2] = ':'; chars[3] = durationDigits[s / 10]; chars[4] = durationDigits[s % 10]
            String(chars)
        } else {
            val chars = CharArray(4)
            chars[0] = durationDigits[m]; chars[1] = ':'; chars[2] = durationDigits[s / 10]; chars[3] = durationDigits[s % 10]
            String(chars)
        }
    }
}

fun formatPlaybackTime(durationMillis: Int): String =
    formatDurationInput(durationMillis.coerceAtLeast(0) / 1000)
