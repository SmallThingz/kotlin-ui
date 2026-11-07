package smallthingz.ui.components

import android.graphics.Paint
import android.text.Editable
import android.text.NoCopySpan
import android.text.Spannable
import android.text.SpannableString
import android.text.SpanWatcher
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.widget.TextView
import smallthingz.ui.R
import smallthingz.ui.text.NativeLineHeightSpan
import smallthingz.ui.text.style.LineHeightStyle
import java.lang.ref.WeakReference

/** Opt-in edge leading, using the same span primitive as the shared paragraph renderer. */
internal fun TextView.applyComponentLineHeight(heightPx: Float?, style: LineHeightStyle?) {
    val previous = getTag(R.id.native_component_line_height_state) as? ComponentLineHeightState
    if (heightPx == null || style == null) {
        if (previous != null) {
            // Retire the tag before callbacks from removing spans can bind a successor.
            setTag(R.id.native_component_line_height_state, null)
            previous.release(text as? Spannable)
        }
        return
    }
    require(heightPx.isFinite() && heightPx > 0f)
    val state = previous ?: ComponentLineHeightState()
    // Reject deliberate mutable sharing before changing this helper's retained state.
    (text as? Spannable)?.let { state.checkBufferOwnership(it) }
    if (previous == null) setTag(R.id.native_component_line_height_state, state)
    // Typography may change the typeface while keeping the same requested height.
    // NativeLineHeightSpan caches its first metrics, so every typography bind invalidates it.
    state.configure(heightPx, style)
    refreshComponentLineHeight()
}

/** Called immediately before the owning native TextView's measure pass. */
internal fun TextView.refreshComponentLineHeight() {
    val state = getTag(R.id.native_component_line_height_state) as? ComponentLineHeightState ?: return
    val current = text
    if (current !is Spannable) {
        // Editable already implements Spannable and never enters this branch. A display-only
        // immutable value needs a mutable span buffer, without installing a TextView listener.
        setText(SpannableString(current ?: ""), TextView.BufferType.SPANNABLE)
    }
    // setText can invoke an existing app callback which changes text or retires this style.
    if (getTag(R.id.native_component_line_height_state) !== state) return
    val buffer = text as? Spannable ?: return
    if (state.refresh(buffer)) requestLayout()
}

/** No View reference is retained by a marker that may outlive its editor's attachment. */
private class ComponentLineHeightState {
    private var heightPx = 0f
    private var style = LineHeightStyle.Default
    private var active = true
    private var revision = 0L
    private var dirty = true
    private var refreshing = false
    private var bufferRef: WeakReference<Spannable>? = null
    private var ownedSpan: OwnedLineHeightSpan? = null
    private val marker = ChangeMarker(this)

    fun configure(heightPx: Float, style: LineHeightStyle) {
        this.heightPx = heightPx
        this.style = style
        markDirty()
    }

    fun markDirty() {
        dirty = true
        revision++
    }

    fun markDirty(buffer: CharSequence?) {
        if (active && bufferRef?.get() === buffer) markDirty()
    }

    // A retained binding claims its buffer until the next refresh or release.
    fun ownsBuffer(buffer: Spannable): Boolean = active && bufferRef?.get() === buffer

    fun checkBufferOwnership(buffer: Spannable): List<ComponentLineHeightMetadata> {
        val foreign = buffer.getSpans(0, buffer.length, ComponentLineHeightMetadata::class.java)
            .filter { it.owner !== this }
        check(foreign.none { it.owner.ownsBuffer(buffer) }) {
            "Native component line height requires one owner per mutable text buffer"
        }
        return foreign
    }

    fun metricSpanChanged(buffer: Spannable, what: Any) {
        // SpannableString copies NoCopySpan; copied markers must not dirty their source owner.
        if (!ownsBuffer(buffer)) return
        if (what !== ownedSpan && what !== marker &&
            (what is MetricAffectingSpan || what is LineHeightSpan ||
                what is ComponentLineHeightMetadata)) markDirty()
    }

    /** Returns true only when a callback changed the buffer/style during this refresh. */
    fun refresh(buffer: Spannable): Boolean {
        if (!active) return false
        if (refreshing) { markDirty(); return false }
        refreshing = true
        try {
            val changedBuffer = bufferRef?.get() !== buffer
            val existing = ownedSpan
            if (!changedBuffer && !dirty && existing != null && buffer.getSpanStart(existing) == 0 &&
                buffer.getSpanEnd(existing) == buffer.length &&
                buffer.getSpanFlags(existing) == Spanned.SPAN_INCLUSIVE_INCLUSIVE &&
                buffer.getSpanStart(marker) == 0 && buffer.getSpanEnd(marker) == buffer.length &&
                buffer.getSpanFlags(marker) == Spanned.SPAN_INCLUSIVE_INCLUSIVE) return false
            // Validate before retiring the previous binding or deleting another owner's metadata.
            val foreign = checkBufferOwnership(buffer)
            if (changedBuffer) {
                removeOwned(bufferRef?.get())
                if (!active) return false
                bufferRef = WeakReference(buffer)
                markDirty()
            }
            val epoch = revision
            // The marker is a span, not addTextChangedListener: display labels stay non-editable.
            buffer.setSpan(marker, 0, buffer.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            if (!active) return false
            // Remove only copied private metadata, observers first. Application spans stay intact.
            // Our retained observer catches callback-driven additions for the next refresh.
            for (metadata in foreign) {
                if (metadata is ChangeMarker) buffer.removeSpan(metadata)
                if (!active) return false
            }
            for (metadata in foreign) {
                if (metadata !is ChangeMarker) buffer.removeSpan(metadata)
                if (!active) return false
            }
            existing?.let { buffer.removeSpan(it) }
            if (!active) return false
            // Span callbacks may reenter; do not install a new span over a newly claimed buffer.
            checkBufferOwnership(buffer)
            val length = buffer.length
            val endIndex = if (length == 0 || buffer[length - 1] == '\n') length + 1 else length
            val span = OwnedLineHeightSpan(this, heightPx, endIndex, style)
            ownedSpan = span
            buffer.setSpan(span, 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            if (!active) {
                buffer.removeSpan(span)
                return false
            }
            dirty = revision != epoch || buffer.length != length
            return dirty
        } finally {
            refreshing = false
        }
    }

    fun release(current: Spannable?) {
        active = false
        revision++
        val remembered = bufferRef?.get()
        removeOwned(remembered)
        if (current !== remembered) removeOwned(current)
        bufferRef = null
        ownedSpan = null
    }

    private fun removeOwned(buffer: Spannable?) {
        if (buffer == null) return
        val span = ownedSpan
        buffer.removeSpan(marker)
        span?.let { buffer.removeSpan(it) }
    }
}

private interface ComponentLineHeightMetadata {
    val owner: ComponentLineHeightState
}

/** Editable builders skip NoCopySpan; component admission also cleans SpannableString copies. */
private class OwnedLineHeightSpan(override val owner: ComponentLineHeightState,
    heightPx: Float, endIndex: Int, style: LineHeightStyle) :
    LineHeightSpan, NoCopySpan, ComponentLineHeightMetadata {
    private val delegate = NativeLineHeightSpan(heightPx, endIndex, style)
    override fun chooseHeight(text: CharSequence, start: Int, end: Int,
        spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) =
        delegate.chooseHeight(text, start, end, spanstartv, v, fm)
}

private class ChangeMarker(override val owner: ComponentLineHeightState) :
    TextWatcher, SpanWatcher, NoCopySpan, ComponentLineHeightMetadata {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = owner.markDirty(s)
    override fun afterTextChanged(s: Editable?) = Unit
    override fun onSpanAdded(text: Spannable, what: Any, start: Int, end: Int) = owner.metricSpanChanged(text, what)
    override fun onSpanRemoved(text: Spannable, what: Any, start: Int, end: Int) = owner.metricSpanChanged(text, what)
    override fun onSpanChanged(text: Spannable, what: Any, oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int) =
        owner.metricSpanChanged(text, what)
}
