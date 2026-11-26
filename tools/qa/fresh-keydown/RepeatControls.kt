import android.os.HostQueue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import smallthingz.ui.interaction.view.*

private class Sink : NativeClickIndication {
    val presses = mutableListOf<NativePressToken>()
    val releases = mutableListOf<NativePressToken>()
    val cancels = mutableListOf<NativePressToken>()
    override fun press(token: NativePressToken) { presses += token }
    override fun release(token: NativePressToken) { releases += token }
    override fun cancel(token: NativePressToken) { cancels += token }
    override fun stateEnter(token: NativeStateToken) = Unit
    override fun stateExit(token: NativeStateToken) = Unit
}
private class Fixture {
    val view = View()
    val sink = Sink()
    val controller = ViewClickController(view, object : NativeClickEnvironment {
        override fun admitDown(event: MotionEvent) = error("not a pointer test")
        override fun interestedParent(event: MotionEvent) = error("not a pointer test")
        override fun longPressOwnershipChanged(owns: Boolean) = error("not a pointer test")
        override val minimumTouchWidthPx = 0f
        override val minimumTouchHeightPx = 0f
    }, sink)
    var clicks = 0
    var latestClicks = 0
    var longs = 0
    init { controller.attached(); bind(); controller.focusChanged(true); HostQueue.advance() }
    fun bind(owner: String = "owner", enabled: Boolean = true, latest: Boolean = false,
             long: Boolean = true) = controller.bind(NativeClickBinding(owner, enabled,
        { if (latest) latestClicks++ else clicks++ }, if (long) ({ longs++ }) else null))
    fun key(code: Int, action: Int, repeat: Int = 0, canceled: Boolean = false) =
        controller.key(KeyEvent(action, code, repeat, canceled))
}
private fun requireThat(value: Boolean, label: String) { check(value) { label } }

fun main() {
    var failures = 0
    var cases = 0
    fun test(label: String, body: (Fixture) -> Unit) {
        HostQueue.reset(); val f = Fixture(); cases++
        try { body(f); println("PASS $label") }
        catch (e: Throwable) { failures++; println("FAIL $label: ${e.message}") }
        finally { f.controller.detached(); HostQueue.reset() }
    }
    val keys = listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE)
    for (code in keys) {
        test("repeat-without-initial-$code") { f ->
            val down = f.key(code, KeyEvent.ACTION_DOWN, 2)
            val up = f.key(code, KeyEvent.ACTION_UP, 2)
            HostQueue.advance(1000)
            requireThat(f.clicks == 0 && f.longs == 0 && f.sink.presses.isEmpty(), "unowned repeat activated")
            requireThat(!down && !up, "unowned return contract")
        }
        for (retirement in listOf("focus-loss", "focus-regain", "cancel", "owner-change", "disable", "detach")) {
            test("repeat-after-$retirement-$code") { f ->
                requireThat(f.key(code, KeyEvent.ACTION_DOWN), "initial down")
                HostQueue.advance(); val original = f.sink.presses.single()
                when (retirement) {
                    "focus-loss" -> f.controller.focusChanged(false)
                    "focus-regain" -> { f.controller.focusChanged(false); f.controller.focusChanged(true) }
                    "cancel" -> f.controller.cancel()
                    "owner-change" -> f.bind("replacement", latest = true)
                    "disable" -> { f.bind(enabled = false); f.bind() }
                    "detach" -> { f.controller.detached(); f.controller.attached(); f.bind() }
                }
                HostQueue.advance()
                val repeated = f.key(code, KeyEvent.ACTION_DOWN, 1)
                val up = f.key(code, KeyEvent.ACTION_UP, 3)
                HostQueue.advance(1000)
                requireThat(f.clicks + f.latestClicks == 0 && f.longs == 0, "retired gesture rearmed callback")
                requireThat(f.sink.presses.size == 1 && f.sink.releases.isEmpty(), "retired gesture rearmed indication")
                requireThat(f.sink.cancels.single() === original, "original cancel identity")
                requireThat(!repeated && !up, "retired event return contract")
                requireThat(f.key(code, KeyEvent.ACTION_DOWN), "next independent initial down")
                requireThat(f.key(code, KeyEvent.ACTION_UP), "next independent up")
                HostQueue.advance()
                requireThat(f.clicks + f.latestClicks == 1, "fresh gesture remains usable")
            }
        }
        test("owned-repeat-repeatUP-current-rebind-$code") { f ->
            requireThat(f.key(code, KeyEvent.ACTION_DOWN), "initial down")
            HostQueue.advance(); val original = f.sink.presses.single()
            requireThat(!f.key(code, KeyEvent.ACTION_DOWN, 1), "owned repeat stays unhandled")
            requireThat(!f.key(code, KeyEvent.ACTION_DOWN, 0), "duplicate initial down stays unhandled")
            f.bind(latest = true)
            requireThat(f.key(code, KeyEvent.ACTION_UP, 4), "owned repeated UP remains accepted")
            HostQueue.advance(1000)
            requireThat(f.clicks == 0 && f.latestClicks == 1 && f.longs == 0, "same-owner current callback")
            requireThat(f.sink.presses.size == 1 && f.sink.releases.single() === original && f.sink.cancels.isEmpty(), "paired single press")
        }
        test("owned-longpress-repeatUP-$code") { f ->
            requireThat(f.key(code, KeyEvent.ACTION_DOWN), "initial down")
            requireThat(!f.key(code, KeyEvent.ACTION_DOWN, 3), "owned repeat")
            HostQueue.advance(500)
            requireThat(f.longs == 1 && f.clicks == 0, "one original long callback")
            requireThat(f.key(code, KeyEvent.ACTION_UP, 8), "long owned up")
            HostQueue.advance(1000)
            requireThat(f.longs == 1 && f.clicks == 0 && f.sink.releases.size == 1, "longpress suppresses click")
        }
        test("canceledUP-$code") { f ->
            requireThat(f.key(code, KeyEvent.ACTION_DOWN), "initial down")
            HostQueue.advance()
            requireThat(f.key(code, KeyEvent.ACTION_UP, 1, canceled = true), "canceled owned up")
            HostQueue.advance(1000)
            requireThat(f.clicks == 0 && f.longs == 0 && f.sink.cancels.size == 1, "canceled no callback")
            requireThat(!f.key(code, KeyEvent.ACTION_UP), "orphan up")
        }
    }
    test("unrelated-key") { f ->
        requireThat(!f.key(19, KeyEvent.ACTION_DOWN) && !f.key(19, KeyEvent.ACTION_UP), "unrelated unhandled")
        HostQueue.advance(1000); requireThat(f.clicks == 0 && f.sink.presses.isEmpty(), "unrelated no action")
    }
    println("RESULT cases=$cases failures=$failures; real-controller/JVM-fixtures, not Android runtime")
    if (failures != 0) kotlin.system.exitProcess(1)
}
