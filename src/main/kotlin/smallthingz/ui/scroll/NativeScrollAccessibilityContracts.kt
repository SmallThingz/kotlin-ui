package smallthingz.ui.scroll

import android.view.View

typealias NativeHorizontalAccessibilityRange = NativeAccessibilityScrollRange

/**
 * Mandatory WINDOW-owned event boundary. Do not implement this as a per-pager timer or stock
 * View.onScrollChanged delivery: ui1.11.2 sends subtree then scrolled events in batches separated
 * by 100ms, shared with all vertical scrollers. Reads must happen at dispatch, not at scheduling.
 *
 * The window owner must retire a closed registration (including queued callbacks), preserve the
 * source isEnabled/attached admission, and send className=android.view.View / enabled=true with
 * only scrollX and maxScrollX from this range, no invented item indexes or scroll deltas.
 * Native accessibility focus bounds invalidation and semantic structure diffing stay window-owned.
 */
fun interface NativeWindowHorizontalAccessibilityEvents {
    fun register(
        source: View,
        sourcePresent: () -> Boolean,
        readRange: () -> NativeHorizontalAccessibilityRange,
    ): NativeHorizontalAccessibilityEventRegistration
}
interface NativeHorizontalAccessibilityEventRegistration : AutoCloseable {
    fun scrollChanged()
    fun layoutChanged()
}
