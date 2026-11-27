package smallthingz.ui.components

import android.view.ViewGroup

/**
 * Let a structural page/window owner park focus without painting a full-container focus overlay.
 * Call during construction, before attachment/focus. Child controls keep their own focus policies;
 * this does not request focus, change descendant traversal, or suppress control highlights.
 */
fun ViewGroup.configureNativeFocusParking() {
    isFocusable = true
    isFocusableInTouchMode = true
    defaultFocusHighlightEnabled = false
}
