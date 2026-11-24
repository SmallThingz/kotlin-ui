package smallthingz.ui.text.view

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Actual source Icon/Image semantic child; drawing stays on the existing unchanged renderer. */
open class NativeActionDescriptionView(context: Context, description: String) : View(context) {
    private var sourceNode: (() -> NativeSourceAccessibilityNode)? = null
    private var text: PlainTextAccessibility? = null
    private var token: Any? = null
    init {
        contentDescription = description
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = false
    }
    fun bindAccessibility(cursor: PlainTextAccessibilityWindowScope,
        node: () -> NativeSourceAccessibilityNode): AutoCloseable {
        val next = Any(); token = next; sourceNode = node
        text = PlainTextAccessibility(this, cursor,
            snapshot = { PlainTextAccessibilitySnapshot(contentDescription.toString(), { null }, { null }) },
            exposed = { sourceNode?.invoke()?.present == true },
            descriptionOnly = true)
        return AutoCloseable {
            if (token === next) { token = null; sourceNode = null; text = null }
        }
    }
    /** Bind description-only actions when the containing control already owns the node geometry. */
    fun bindDescriptionExposure(cursor: PlainTextAccessibilityWindowScope,
        exposed: () -> Boolean): AutoCloseable {
        val next = Any()
        token = next
        sourceNode = null
        text = PlainTextAccessibility(this, cursor,
            snapshot = { PlainTextAccessibilitySnapshot(contentDescription?.toString().orEmpty(), { null }, { null }) },
            exposed = exposed, descriptionOnly = true)
        return AutoCloseable {
            if (token === next) { token = null; text = null }
        }
    }
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (sourceNode?.invoke()?.present == false) null else super.createAccessibilityNodeInfo()
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // The pinned delegate suppresses Image class/focus under a merging Surface ancestor.
        info.className = "android.view.View"
        info.text = null
        info.isScreenReaderFocusable = false
        info.isFocusable = false
        info.isClickable = false
        info.isLongClickable = false
        info.isEnabled = true
        val actions = info.actionList
        for (index in actions.size - 1 downTo 0) info.removeAction(actions[index])
        info.addAction(if (isAccessibilityFocused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        text?.populate(info)
        sourceNode?.invoke()?.populate(info)
    }
    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        // A retained native leaf can still own focus after its source node has retired.
        if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            return super.performAccessibilityAction(action, args)
        if (sourceNode?.invoke()?.present == false) return false
        return text?.performAction(action, args) ?: super.performAccessibilityAction(action, args)
    }
    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.className = "android.view.View"
        event.isEnabled = true
        event.text.clear()
        event.contentDescription = null
    }
    override fun getAccessibilityClassName(): CharSequence = "android.view.View"
}

fun bindActionDescriptionAccessibility(host: ViewGroup, parent: MergedRowAccessibility,
    description: NativeActionDescriptionView, cursor: PlainTextAccessibilityWindowScope,
    geometry: NativePlainTextViewGeometry, sourceNode: (View) -> NativeSourceAccessibilityNode): AutoCloseable {
    val parentLease = parent.bindSourceGeometry(geometry, sourceNode) { sourceNode(host).present }
    val childLease = try { description.bindAccessibility(cursor) { sourceNode(description) } }
    catch (error: Throwable) {
        closeAccessibilityLeases(listOf(parentLease), error)
        throw error
    }
    var closed = false
    return AutoCloseable {
        if (!closed) {
            closed = true
            closeAccessibilityLeases(listOf(childLease, parentLease))
        }
    }
}

fun closeAccessibilityLeases(leases: List<AutoCloseable>, primary: Throwable? = null) {
    var failure = primary
    for (lease in leases) try { lease.close() } catch (error: Throwable) {
        if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
    }
    failure?.let { throw it }
}

