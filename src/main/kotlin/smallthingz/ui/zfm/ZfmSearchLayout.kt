package smallthingz.ui.zfm

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.text.Editable
import android.text.Layout
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import smallthingz.ui.components.NativeSurface
import smallthingz.ui.components.NativeSurfaceStyle
import smallthingz.ui.components.NativeTextField
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Responsive placement for a retained native search field, its actions and result status.
 *
 * [preferredEditorWidth] is a fit budget, not an exact field width. The editor keeps the
 * remaining width of its row. [inputWidth] includes the close control, actions and input
 * container padding; status occupies a separate row unless [inlineStatus] is true.
 * No text, selection, focus or native horizontal scrolling is changed by this calculation.
 */
data class ZfmSearchLayout(
    val preferredEditorWidth: Int,
    val inputWidth: Int,
    val stackActions: Boolean,
    val inlineStatus: Boolean,
) {
    companion object {
        /**
         * All dimensions are nonnegative physical pixels, rounded up when measuring text.
         * [availableWidth] excludes the outer chrome's padding, but includes [inputPaddingWidth].
         * [editorPaddingWidth] is the field's compound start plus end padding; [cursorWidth]
         * reserves room for its native cursor. Measure query/hint with the field's actual
         * typography and single-line transformation. Only visible controls contribute widths.
         *
         * Call once for the whole chrome width, before allocating an inline status width.
         * Both fit decisions use that same budget. A long query can use the entire editor row
         * after actions/status move out; it never demands unbounded width. Recompute on text,
         * font/configuration, visible-action or status-size changes, retaining the existing
         * NativeTextField and common controls. The caller owns normal View measurement/layout.
         */
        fun measure(
            availableWidth: Int,
            queryTextWidth: Int,
            hintTextWidth: Int,
            editorPaddingWidth: Int,
            cursorWidth: Int,
            closeWidth: Int,
            actionsWidth: Int,
            inputPaddingWidth: Int = 0,
            minimumEditorWidth: Int = 0,
            statusWidth: Int = 0,
            allowInlineStatus: Boolean = false,
        ): ZfmSearchLayout {
            require(availableWidth >= 0 && queryTextWidth >= 0 && hintTextWidth >= 0 &&
                editorPaddingWidth >= 0 && cursorWidth >= 0 && closeWidth >= 0 &&
                actionsWidth >= 0 && inputPaddingWidth >= 0 && minimumEditorWidth >= 0 &&
                statusWidth >= 0) { "Search layout dimensions must be nonnegative" }

            val fixedWidth = inputPaddingWidth.toLong() + closeWidth
            val maximumEditorWidth = (availableWidth.toLong() - fixedWidth).coerceAtLeast(0)
            val naturalEditorWidth = maxOf(minimumEditorWidth.toLong(),
                maxOf(queryTextWidth, hintTextWidth).toLong() + editorPaddingWidth + cursorWidth)
            val editorWidth = minOf(naturalEditorWidth, maximumEditorWidth)
            val inlineInputWidth = fixedWidth + editorWidth + actionsWidth
            val inlineStatus = allowInlineStatus && inlineInputWidth + statusWidth <= availableWidth
            val inputWidth = availableWidth - if (inlineStatus) statusWidth else 0
            return ZfmSearchLayout(
                preferredEditorWidth = editorWidth.toInt(),
                inputWidth = inputWidth,
                stackActions = actionsWidth > 0 && inlineInputWidth > inputWidth,
                inlineStatus = inlineStatus,
            )
        }
    }
}

/**
 * Retained query row. Reflow changes layout parameters, never the editor's parent, text,
 * selection, focus or scroll offset. The caller owns action callbacks and query state.
 */
class ZfmSearchInput(
    context: Context,
    theme: ZfmTheme,
    private val editor: NativeTextField,
    private val close: View?,
    actions: List<View>,
) : LinearLayout(context) {
    private var theme = theme
    private val editorRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        close?.let { addView(it, LayoutParams(0, 0)) }
        addView(editor, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }
    private val actionRow = LinearLayout(context).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        actions.forEach { addView(it, LayoutParams(0, 0)) }
    }
    private val actionScroll = HorizontalScrollView(context).apply {
        isFillViewport = true
        isFocusable = false
        addView(actionRow, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    private var observingText = false
    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            // TextView can repaint within the same size without requesting parent measurement.
            requestLayout()
        }
    }
    // Supplied only for the enclosing chrome's current measure pass; never reused after it.
    internal var chromeLayout: ZfmSearchLayout? = null

    init {
        gravity = Gravity.CENTER_VERTICAL
        addView(editorRow, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(actionScroll, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        bindTheme(theme)
    }

    fun bindTheme(theme: ZfmTheme) {
        this.theme = theme
        theme.appearance.compactField.bind(editor, theme.colors)
        applyGeometry()
        requestLayout()
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density).roundToInt()

    private fun applyGeometry() {
        val toolbar = theme.appearance.toolbars
        val inset = dp(toolbar.centeredPaddingDp)
        setPaddingRelative(dp(toolbar.centeredTitleStartDp), inset, inset, inset)
        val target = dp(toolbar.action.minimumTargetDp)
        close?.layoutParams?.apply { width = target; height = target }
        for (index in 0 until actionRow.childCount) {
            actionRow.getChildAt(index).layoutParams.apply { width = target; height = target }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyGeometry()
        requestLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!observingText) {
            editor.addTextChangedListener(textWatcher)
            observingText = true
        }
        requestLayout()
    }

    override fun onDetachedFromWindow() {
        if (observingText) {
            editor.removeTextChangedListener(textWatcher)
            observingText = false
        }
        chromeLayout = null
        super.onDetachedFromWindow()
    }

    private fun actionWidth(): Int = (0 until actionRow.childCount).sumOf { index ->
        val action = actionRow.getChildAt(index)
        if (action.visibility == GONE) 0 else action.layoutParams.width
    }

    internal fun measureChrome(availableWidth: Int, statusWidth: Int, allowInlineStatus: Boolean): ZfmSearchLayout {
        val text = editor.text ?: ""
        val displayed = editor.transformationMethod?.getTransformation(text, editor) ?: text
        fun textWidth(value: CharSequence) = ceil(Layout.getDesiredWidth(value, editor.paint).toDouble())
            .coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
        val cursorWidth = if (Build.VERSION.SDK_INT >= 29) editor.textCursorDrawable?.intrinsicWidth else null
        return ZfmSearchLayout.measure(
            availableWidth = availableWidth,
            queryTextWidth = textWidth(displayed), hintTextWidth = textWidth(editor.hint ?: ""),
            editorPaddingWidth = editor.compoundPaddingStart + editor.compoundPaddingEnd,
            cursorWidth = cursorWidth?.takeIf { it > 0 } ?: dp(2f).coerceAtLeast(1),
            closeWidth = close?.takeUnless { it.visibility == GONE }?.layoutParams?.width ?: 0,
            actionsWidth = actionWidth(), inputPaddingWidth = paddingLeft + paddingRight,
            minimumEditorWidth = editor.minimumWidth.coerceAtLeast(0),
            statusWidth = statusWidth, allowInlineStatus = allowInlineStatus,
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val availableWidth = if (bounded) MeasureSpec.getSize(widthMeasureSpec) else Int.MAX_VALUE
        val layout = chromeLayout?.takeIf { bounded && it.inputWidth == availableWidth }
            ?: measureChrome(availableWidth, 0, false)
        val stacked = bounded && layout.stackActions
        orientation = if (stacked) VERTICAL else HORIZONTAL
        (editorRow.layoutParams as LayoutParams).apply {
            width = if (stacked) LayoutParams.MATCH_PARENT else if (bounded) 0 else LayoutParams.WRAP_CONTENT
            weight = if (stacked || !bounded) 0f else 1f
        }
        (editor.layoutParams as LayoutParams).apply {
            width = if (bounded) 0 else LayoutParams.WRAP_CONTENT
            weight = if (bounded) 1f else 0f
        }
        val actionsWidth = actionWidth()
        actionScroll.visibility = if (actionsWidth == 0) GONE else VISIBLE
        actionScroll.layoutParams.width = if (stacked) LayoutParams.MATCH_PARENT else actionsWidth
        actionScroll.isHorizontalScrollBarEnabled = stacked && actionsWidth >
            (availableWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** Result status and the retained input share a row only when the same query budget fits both. */
class ZfmSearchChrome(
    context: Context,
    theme: ZfmTheme,
    private val queryHost: ZfmSearchInput,
    private val statusHost: View,
) : LinearLayout(context) {
    private val surfaceStyle = NativeSurfaceStyle(Color.TRANSPARENT, cornerRadiusDp = 0f)
    private val querySurface = NativeSurface(context, surfaceStyle).apply {
        addView(queryHost, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private var compactSearch = false
    private var status = ""

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(querySurface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(statusHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        bindTheme(theme)
    }

    fun bindTheme(theme: ZfmTheme) {
        queryHost.bindTheme(theme)
        theme.bindCard(querySurface, surfaceStyle)
    }

    fun bind(compactSearch: Boolean, status: String, statusLabel: TextView?) {
        val statusChanged = this.status != status
        if (this.compactSearch == compactSearch && !statusChanged) return
        this.compactSearch = compactSearch
        this.status = status
        if (statusChanged) statusLabel?.requestLayout()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val eligible = compactSearch && queryHost.visibility == VISIBLE &&
            MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
        var statusWidth = 0
        var statusExtent = 0
        if (eligible && statusHost.visibility != GONE) {
            val natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            statusHost.measure(natural, natural)
            statusWidth = statusHost.measuredWidth
            val margins = statusHost.layoutParams as? ViewGroup.MarginLayoutParams
            statusExtent = (statusWidth.toLong() + (margins?.leftMargin ?: 0) +
                (margins?.rightMargin ?: 0)).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        }
        val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val layout = queryHost.measureChrome(if (bounded) available else Int.MAX_VALUE, statusExtent, eligible)
        orientation = if (layout.inlineStatus) HORIZONTAL else VERTICAL
        (querySurface.layoutParams as LayoutParams).apply {
            width = if (layout.inlineStatus) layout.inputWidth else LayoutParams.MATCH_PARENT
            weight = 0f
        }
        statusHost.layoutParams.width = if (layout.inlineStatus) statusWidth else LayoutParams.MATCH_PARENT
        queryHost.chromeLayout = layout.takeIf { bounded }
        try {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        } finally {
            queryHost.chromeLayout = null
        }
    }
}
