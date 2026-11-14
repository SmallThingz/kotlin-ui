package smallthingz.ui.util

inline fun closePreservingPrimaryFailure(
    primaryFailure: Throwable?,
    close: () -> Unit,
): Throwable? {
    var failure = primaryFailure
    try {
        close()
    } catch (closeError: Throwable) {
        val primary = failure
        if (primary == null) {
            failure = closeError
        } else if (closeError !== primary) {
            primary.addSuppressed(closeError)
        }
    }
    return failure
}
