package smallthingz.ui.text.view

import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/** Optional whole-tree capture for one synchronous parent/children accessibility request. */
interface NativeSourceAccessibilityNodeProvider : (View) -> NativeSourceAccessibilityNode {
    fun snapshotNodes(): Map<View, NativeSourceAccessibilityNode>
}

/** On-demand facts for an existing View; absence, explicit hiding and clipping are independent. */
data class NativeSourceAccessibilityNode(
    val present: Boolean,
    val hidden: Boolean,
    val visibleToUser: Boolean,
    val boundsInScreen: Rect,
) {
    fun populate(info: AccessibilityNodeInfo) {
        info.isImportantForAccessibility = !hidden
        info.isVisibleToUser = present && visibleToUser
        if (hidden) info.isScreenReaderFocusable = false
        info.setBoundsInScreen(boundsInScreen)
    }
    companion object {
        /** Captures the clipped screen rectangle of an ordinary Android View. */
        fun fromView(view: View): NativeSourceAccessibilityNode {
            val bounds = Rect()
            if (!view.isShown || !view.getGlobalVisibleRectInScreen(bounds)) return absent()
            return NativeSourceAccessibilityNode(true, false, true, bounds)
        }

        fun absent() = NativeSourceAccessibilityNode(false, false, false, Rect())
    }
}

/** Android's global visible rectangle is root-relative. Accessibility expects screen coordinates. */
internal fun View.getGlobalVisibleRectInScreen(bounds: Rect): Boolean {
    if (!getGlobalVisibleRect(bounds)) return false
    val rootLocation = IntArray(2)
    rootView.getLocationOnScreen(rootLocation)
    bounds.offset(rootLocation[0], rootLocation[1])
    return true
}
