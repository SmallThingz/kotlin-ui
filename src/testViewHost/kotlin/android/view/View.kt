package android.view

/** Host linkage fixture for attachment ownership only; no Android rendering or input behavior. */
open class View {
    var attached: Boolean = true
    val isAttachedToWindow: Boolean get() = attached
}
