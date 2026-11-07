package smallthingz.ui.scroll

import smallthingz.ui.geometry.Rect
import kotlinx.coroutines.CancellableContinuation

class NativeBringIntoViewRequest(
    val currentBounds: () -> Rect?,
    val continuation: CancellableContinuation<Unit>,
)
