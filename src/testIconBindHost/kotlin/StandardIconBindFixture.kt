// The runner inserts the exact production bindAction method. No Android rendering is emulated.
private class Glyph { var contentDescription = "" }
private class Visual(private val click: () -> Unit) {
    var isEnabled = false
    var isFocusable = false
    val glyph = Glyph()
    fun performClick() { if (isEnabled) click() }
}
private class Indication {
    var color = 0
    var updates = 0
    fun updateColor(value: Int) { color = value; updates++ }
}
private class NativeClickBinding(val identity: Any, val enabled: Boolean, val onClick: () -> Unit)
private class Controller {
    var latest: NativeClickBinding? = null
    var updates = 0
    fun bind(value: NativeClickBinding) { latest = value; updates++ }
}
private class Harness(initialDensity: Float) {
    private var density = initialDensity
    private var currentClick: () -> Unit = {}
    private val identity = Any()
    val visual = Visual { currentClick() }
    val indication = Indication()
    val controller = Controller()
    var layoutRequests = 0
    private fun requestLayout() { layoutRequests++ }
    fun densityValue() = density

// @@ACTUAL_BIND_ACTION@@
}

fun main() {
    var passed = 0
    var failed = 0
    fun test(name: String, body: () -> Unit) {
        try { body(); passed++; println("PASS $name") }
        catch (error: Throwable) { failed++; println("FAIL $name: $error") }
    }
    test("same-density-first-bind-publishes-every-state-without-layout") {
        val h = Harness(1f)
        var clicks = 0
        h.bindAction(1f, "Copy", true, 0x1234) { clicks++ }
        check(h.layoutRequests == 0)
        check(h.visual.isEnabled && h.visual.isFocusable)
        check(h.visual.glyph.contentDescription == "Copy")
        check(h.indication.color == 0x1234 && h.indication.updates == 1)
        check(h.controller.updates == 1 && h.controller.latest!!.enabled)
        h.controller.latest!!.onClick()
        check(clicks == 1)
    }
    test("density-change-requests-layout-once-and-persists-new-density") {
        val h = Harness(1f)
        h.bindAction(421f / 160f, "Copy", true, 1) {}
        check(h.layoutRequests == 1 && h.densityValue() == 421f / 160f)
        h.bindAction(421f / 160f, "Copy", true, 1) {}
        check(h.layoutRequests == 1)
        h.bindAction(2f, "Copy", true, 1) {}
        check(h.layoutRequests == 2 && h.densityValue() == 2f)
        check(h.controller.updates == 3 && h.indication.updates == 3)
    }
    test("callback-only-rebind-keeps-identity-and-dispatches-latest-callback") {
        val h = Harness(2f)
        var old = 0
        var fresh = 0
        h.bindAction(2f, "Copy", true, 5) { old++ }
        val identity = h.controller.latest!!.identity
        h.bindAction(2f, "Copy", true, 5) { fresh++ }
        check(h.controller.updates == 2 && h.controller.latest!!.identity === identity)
        h.controller.latest!!.onClick()
        check(old == 0 && fresh == 1 && h.layoutRequests == 0)
    }
    test("same-density-disable-still-updates-controller-description-and-ripple") {
        val h = Harness(1f)
        var old = 0
        h.bindAction(1f, "Copy", true, 3) { old++ }
        h.bindAction(1f, "", false, 8) { old += 10 }
        check(!h.visual.isEnabled && !h.visual.isFocusable)
        check(h.visual.glyph.contentDescription == "")
        check(h.indication.color == 8 && h.indication.updates == 2)
        check(!h.controller.latest!!.enabled && h.controller.updates == 2)
        h.controller.latest!!.onClick()
        check(old == 0 && h.layoutRequests == 0)
    }
    test("reenable-at-same-density-rebinds-all-state-and-callback") {
        val h = Harness(2f)
        var calls = 0
        h.bindAction(2f, "", false, 2) {}
        h.bindAction(2f, "Reroll", true, 9) { calls++ }
        check(h.visual.isEnabled && h.visual.isFocusable)
        check(h.visual.glyph.contentDescription == "Reroll" && h.indication.color == 9)
        check(h.controller.updates == 2 && h.indication.updates == 2)
        h.controller.latest!!.onClick()
        check(calls == 1 && h.layoutRequests == 0)
    }
    test("repeated-disabled-bind-does-not-skip-independent-input-updates") {
        val h = Harness(1f)
        repeat(3) { h.bindAction(1f, "", false, it) {} }
        check(h.controller.updates == 3 && h.indication.updates == 3)
        check(h.indication.color == 2 && !h.controller.latest!!.enabled)
        check(h.layoutRequests == 0)
    }
    println("RESULT passed=$passed failed=$failed")
    check(failed == 0) { "Focused bind controls failed" }
}
