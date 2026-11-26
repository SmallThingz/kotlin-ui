package android.view

/** Only data/API fixtures needed to execute the real controller on a JVM. No Android dispatch. */
open class View {
    var defaultFocusHighlightEnabled = true
    var isAttachedToWindow = true
    var width = 100
    var height = 48
    var parent: Any? = null
    fun performHapticFeedback(@Suppress("UNUSED_PARAMETER") kind: Int): Boolean =
        error("pointer haptics are outside keyboard fixture")
}
open class ViewGroup : View() {
    fun shouldDelayChildPressedState(): Boolean = error("pointer dispatch is outside keyboard fixture")
}
class KeyEvent(val action: Int, val keyCode: Int, val repeatCount: Int = 0, val isCanceled: Boolean = false) {
    companion object {
        const val ACTION_DOWN = 0; const val ACTION_UP = 1
        const val KEYCODE_DPAD_CENTER = 23; const val KEYCODE_ENTER = 66
        const val KEYCODE_NUMPAD_ENTER = 160; const val KEYCODE_SPACE = 62
    }
}
class MotionEvent {
    val actionMasked: Int get() = error("pointer fixture unused")
    val pointerCount: Int get() = error("pointer fixture unused")
    val classification: Int get() = error("pointer fixture unused")
    val x: Float get() = error("pointer fixture unused")
    val y: Float get() = error("pointer fixture unused")
    fun getToolType(@Suppress("UNUSED_PARAMETER") i: Int): Int = error("pointer fixture unused")
    fun getX(@Suppress("UNUSED_PARAMETER") i: Int): Float = error("pointer fixture unused")
    fun getY(@Suppress("UNUSED_PARAMETER") i: Int): Float = error("pointer fixture unused")
    companion object {
        const val ACTION_DOWN = 0; const val ACTION_UP = 1; const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3; const val ACTION_POINTER_DOWN = 5; const val ACTION_POINTER_UP = 6
        const val ACTION_HOVER_ENTER = 9; const val ACTION_HOVER_EXIT = 10
        const val TOOL_TYPE_FINGER = 1; const val CLASSIFICATION_DEEP_PRESS = 2
    }
}
object HapticFeedbackConstants { const val LONG_PRESS = 0 }
object ViewConfiguration {
    @JvmStatic fun getTapTimeout() = 100
    @JvmStatic fun getLongPressTimeout() = 500
}
