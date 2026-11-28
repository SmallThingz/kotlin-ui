package smallthingz.ui.gallery

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import smallthingz.ui.components.NativeText

/** Retains the existing status and alternate widgets. App supplies localized owner-confirmed copy.
 * Measurement is confined to a detached inaccessible probe with the same typography as the labels.
 * Normal Preparing/Ready alternate reservation remains unchanged. Completion never enlarges the
 * ordinary guidance budget: full -> short -> empty ink, with full completion semantics retained.
 */
class GalleryHealStatusRow(
    context: Context,
    private val status: NativeText,
    private val alternate: NativeText,
    private val probe: NativeText,
) : FrameLayout(context) {
    data class Completion(val full: String, val short: String)
    private data class Copy(val guidance: String, val alternate: String?, val completion: Completion?)
    private var copy: Copy? = null
    /** Keep confirmed completion available to accessibility without adding canvas captions. */
    var completionTextVisible: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    init {
        require(status !== alternate && status !== probe && alternate !== probe)
        require(status.parent == null && alternate.parent == null && probe.parent == null)
        probe.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        probe.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_NONE
        probe.isFocusable = false
        probe.visibility = INVISIBLE
        // TextView relayout reads LayoutParams when changing already-measured probe text.
        probe.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        alternate.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alternate.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_NONE
        alternate.isFocusable = false
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(alternate, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(guidance: String, alternate: String? = null, completion: Completion? = null) {
        val next = Copy(guidance, alternate, completion)
        if (copy == next) return
        copy = next
        val reserved = if (completion == null) alternate else guidance
        if (reserved != null && this.alternate.text.toString() != reserved) this.alternate.text = reserved
        this.alternate.visibility = if (reserved == null) GONE else INVISIBLE
        if (completion == null) publish(guidance, null)
        requestLayout()
    }

    private fun publish(text: String, description: String?) {
        // Only the selected final pair reaches the exposed live region; never candidate probes.
        // Android may coalesce or emit multiple notifications for the final text/description pair.
        if (status.contentDescription?.toString() != description) status.contentDescription = description
        if (status.text.toString() != text) status.text = text
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val current = copy
        val completion = current?.completion
        if (current != null && completion != null) {
            probe.layoutDirection = layoutDirection
            probe.textDirection = status.textDirection
            probe.textAlignment = status.textAlignment
            probe.gravity = status.gravity
            probe.setPadding(status.paddingLeft, status.paddingTop, status.paddingRight, status.paddingBottom)
            val width = getChildMeasureSpec(widthMeasureSpec, paddingLeft + paddingRight, LayoutParams.MATCH_PARENT)
            val height = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            fun measured(text: String): Int {
                if (probe.text.toString() != text) probe.text = text
                probe.measure(width, height)
                return probe.measuredHeight
            }
            val budget = measured(current.guidance)
            val chosen = when {
                !completionTextVisible -> ""
                measured(completion.full) <= budget -> completion.full
                measured(completion.short) <= budget -> completion.short
                else -> "" // Never revive tutorial ink; the full completion remains the accessible description.
            }
            publish(chosen, completion.full)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
