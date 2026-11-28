package smallthingz.ui.gallery

import android.view.View
import android.view.ViewTreeObserver
import smallthingz.ui.components.NativeActionSheet
import smallthingz.ui.components.SheetRoot

/**
 * Reserve the expanded footprint of a bottom-entry editor sheet without following its drag or
 * entry animation. The existing preview is remeasured in place; no pixels, focus or model move.
 * The menu owner must close this binding on dismissal, including failed Window attachment.
 */
fun NativeActionSheet.reserveGalleryEditorPreview(anchor: View): AutoCloseable {
    var ancestor: View? = anchor
    while (ancestor != null && ancestor !is GalleryEditorLayout) ancestor = ancestor.parent as? View
    val editor = ancestor as? GalleryEditorLayout ?: return AutoCloseable { }
    val root = contentContext.inputRoot as? SheetRoot ?: return AutoCloseable { }
    return GalleryEditorSheetViewport(editor, anchor, root, contentView)
}

private class GalleryEditorSheetViewport(
    private val editor: GalleryEditorLayout,
    private val anchor: View,
    private val root: SheetRoot,
    private val surface: View,
) : AutoCloseable, View.OnAttachStateChangeListener {
    private var closed = false
    private val editorWindow = editor.windowToken
    private var sheetWindow = root.windowToken
    private val rootPosition = IntArray(2)
    private val editorPosition = IntArray(2)
    private var rootObserver: ViewTreeObserver? = null
    private var editorObserver: ViewTreeObserver? = null
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { update() }
    private val editorDraw = ViewTreeObserver.OnPreDrawListener { update(); true }
    private val firstFrame = GalleryEditorSheetFirstFrame()
    private val sheetDraw = ViewTreeObserver.OnPreDrawListener {
        val admitted = update()
        // At most two initial traversal deferrals. A suppressed/stalled host must never
        // hold the modal hostage: after fallback the reservation still follows layouts.
        // Acknowledgement is a View draw only, not an atomic cross-window presentation.
        closed || firstFrame.allowDraw(admitted && editor.previewReservationDrawn(this))
    }

    init {
        editor.reservePreview(this)
        listOf(editor, anchor, root).distinct().forEach { it.addOnAttachStateChangeListener(this) }
        if (editor.isAttachedToWindow) observeEditor()
        if (root.isAttachedToWindow) observeSheet()
    }

    private fun observeEditor() {
        if (editorObserver != null) return
        editor.viewTreeObserver.also {
            editorObserver = it
            it.addOnGlobalLayoutListener(layoutListener)
            it.addOnPreDrawListener(editorDraw)
        }
    }

    private fun observeSheet() {
        if (rootObserver != null) return
        root.viewTreeObserver.also {
            rootObserver = it
            it.addOnGlobalLayoutListener(layoutListener)
            it.addOnPreDrawListener(sheetDraw)
        }
    }

    private fun update(): Boolean {
        if (closed) return false
        if (!editor.ownsPreviewReservation(this)) { close(); return false }
        if (!editor.isAttachedToWindow || !anchor.isAttachedToWindow || !root.isAttachedToWindow ||
            !surface.isAttachedToWindow || !root.isLaidOut || surface.measuredHeight <= 0) return false
        var ancestor: View? = anchor
        while (ancestor != null && ancestor !== editor) ancestor = ancestor.parent as? View
        if (ancestor !== editor || root.display?.displayId != editor.display?.displayId ||
            editorWindow == null || editor.windowToken !== editorWindow ||
            sheetWindow == null || root.windowToken !== sheetWindow) {
            close(); return false
        }
        root.getLocationOnScreen(rootPosition)
        editor.getLocationOnScreen(editorPosition)
        // This is the sheet's applied animated-IME stream, not a separately sampled inset.
        val expandedTop = rootPosition[1] + (root.sourceSemanticsHeight - surface.measuredHeight).coerceAtLeast(0)
        editor.updatePreviewReservation(this, expandedTop - editorPosition[1])
        return true
    }

    override fun onViewAttachedToWindow(view: View) {
        if (closed) return
        if (view === editor) observeEditor()
        if (view === root) { sheetWindow = root.windowToken; observeSheet() }
    }

    override fun onViewDetachedFromWindow(view: View) = close()

    override fun close() {
        if (closed) return
        closed = true
        listOf(editor, anchor, root).distinct().forEach { it.removeOnAttachStateChangeListener(this) }
        rootObserver?.takeIf { it.isAlive }?.let {
            it.removeOnGlobalLayoutListener(layoutListener); it.removeOnPreDrawListener(sheetDraw)
        }
        editorObserver?.takeIf { it.isAlive }?.let {
            it.removeOnGlobalLayoutListener(layoutListener); it.removeOnPreDrawListener(editorDraw)
        }
        rootObserver = null; editorObserver = null
        editor.releasePreviewReservation(this)
    }
}

/** One binding gets one finite opportunity to coordinate its initial frame. Never rearmed. */
internal class GalleryEditorSheetFirstFrame {
    private var deferred = 0
    private var released = false
    fun allowDraw(reservationDrawn: Boolean): Boolean {
        if (released || reservationDrawn || deferred >= 2) {
            released = true
            return true
        }
        deferred++
        return false
    }
}
