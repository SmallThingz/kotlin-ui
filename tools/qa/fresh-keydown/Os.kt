package android.os

/** Deterministic host queue fixture, not an Android Looper or timing model. */
object HostQueue {
    private data class Job(val at: Long, val sequence: Long, val callback: Runnable)
    private val jobs = mutableListOf<Job>()
    private var now = 0L
    private var sequence = 0L
    fun reset() { jobs.clear(); now = 0; sequence = 0 }
    fun post(r: Runnable, delay: Long) { jobs += Job(now + delay, sequence++, r) }
    fun remove(r: Runnable) { jobs.removeAll { it.callback === r } }
    fun advance(delta: Long = 0) {
        val until = now + delta
        var dispatched = 0
        while (true) {
            val next = jobs.filter { it.at <= until }.minWithOrNull(compareBy<Job> { it.at }.thenBy { it.sequence }) ?: break
            check(++dispatched <= 1000) { "fixture queue did not settle" }
            jobs.remove(next); now = next.at; next.callback.run()
        }
        now = until
    }
}
class Looper private constructor() {
    companion object { private val main = Looper(); @JvmStatic fun getMainLooper() = main }
}
class Handler(@Suppress("UNUSED_PARAMETER") looper: Looper) {
    fun post(r: Runnable): Boolean { HostQueue.post(r, 0); return true }
    fun postDelayed(r: Runnable, delay: Long): Boolean { HostQueue.post(r, delay); return true }
    fun removeCallbacks(r: Runnable) = HostQueue.remove(r)
}
object Build { object VERSION { const val SDK_INT = 36 } }
