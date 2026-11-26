package smallthingz.ui.components

/** Internal presentation boundary; the public permission owner keeps its existing API. */
internal interface NativePermissionPresentation {
    fun show()
    fun showAtBottom()
    fun showAtEdge(edge: NativeDialogEdge)
    fun dismiss()
    fun setBusy(value: Boolean)
    fun setAllowEnabled(enabled: Boolean)
    fun setProgress(message: CharSequence?)
    fun setError(message: CharSequence?)
}
