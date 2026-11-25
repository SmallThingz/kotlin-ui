package smallthingz.ui.zfm

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import smallthingz.ui.components.NativeSurface
import smallthingz.ui.components.NativeSurfaceStyle
import smallthingz.ui.components.NativeTextField
import kotlin.math.roundToInt

/**
 * Stable width budget for the bottom query row.
 *
 * [preferredEditorWidth] includes the editor's own padding and cursor. [inputWidth] includes
 * the close control, reserved primary-action slots and container padding. Result status and
 * secondary actions always occupy a separate row above it; both legacy fit flags stay false.
 * No text, selection, focus or native horizontal scrolling is changed by this calculation.
 */
data class ZfmSearchLayout(
    val preferredEditorWidth: Int,
    val inputWidth: Int,
    val stackActions: Boolean,
    val inlineStatus: Boolean,
    val contentWidth: Int = inputWidth,
) {
    companion object {
        /**
         * All dimensions are nonnegative physical pixels.
         * [availableWidth] excludes the outer chrome's padding, but includes [inputPaddingWidth].
         * [actionsWidth] reserves every supplied primary-action slot, including a hidden Clear.
         * [minimumEditorWidth] admits horizontal scrolling of the row in exceptionally narrow
         * windows; [contentWidth] can exceed [inputWidth], which stays within the viewport.
         * Query/hint, editor padding/cursor and status arguments cannot change this budget.
         * The retained single-line editor handles long text by scrolling;
         * neither typing nor asynchronous status updates relocate the controls.
         */
        @Suppress("UNUSED_PARAMETER") // Legacy inline-status opt-in no longer changes placement.
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

            val fixedWidth = inputPaddingWidth.toLong() + closeWidth + actionsWidth
            val contentWidth = maxOf(availableWidth.toLong(), fixedWidth + minimumEditorWidth)
                .coerceAtMost(Int.MAX_VALUE.toLong())
            val editorWidth = (contentWidth - fixedWidth).coerceAtLeast(0)
            return ZfmSearchLayout(
                preferredEditorWidth = editorWidth.toInt(),
                inputWidth = availableWidth,
                stackActions = false,
                inlineStatus = false,
                contentWidth = contentWidth.toInt(),
            )
        }
    }
}

/**
 * Retained query row: Close, editor, primary actions (normally Clear only). Put secondary
 * actions in [ZfmSearchChrome.trailingStatusAction]. Hidden primary actions keep their slots
 * so the first typed character does not resize the editor. Layout never changes its parent,
 * text, selection, focus or scroll offset. The caller owns callbacks and query state.
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
    private val contentRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(editorRow, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(actionRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    private val inputScroll = HorizontalScrollView(context).apply {
        isFocusable = false
        addView(contentRow, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(inputScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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

    private fun actionWidth(): Int = (0 until actionRow.childCount).sumOf { index ->
        actionRow.getChildAt(index).layoutParams.width.toLong()
    }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val actionsWidth = actionWidth()
        val layout = if (bounded) ZfmSearchLayout.measure(
            availableWidth = MeasureSpec.getSize(widthMeasureSpec),
            queryTextWidth = 0, hintTextWidth = 0, editorPaddingWidth = 0, cursorWidth = 0,
            closeWidth = close?.takeUnless { it.visibility == GONE }?.layoutParams?.width ?: 0,
            actionsWidth = actionsWidth, inputPaddingWidth = paddingLeft + paddingRight,
            minimumEditorWidth = dp(theme.appearance.toolbars.action.minimumTargetDp),
        ) else null
        (editorRow.layoutParams as LayoutParams).apply {
            width = if (bounded) 0 else LayoutParams.WRAP_CONTENT
            weight = if (bounded) 1f else 0f
        }
        (editor.layoutParams as LayoutParams).apply {
            width = layout?.preferredEditorWidth ?: LayoutParams.WRAP_CONTENT
            weight = 0f
        }
        actionRow.visibility = if (actionsWidth == 0) GONE else VISIBLE
        actionRow.layoutParams.width = actionsWidth
        contentRow.layoutParams.width = layout?.let {
            (it.contentWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        } ?: LayoutParams.WRAP_CONTENT
        inputScroll.isHorizontalScrollBarEnabled = layout != null && layout.contentWidth > layout.inputWidth
        val fullWidth = layout?.let { MeasureSpec.makeMeasureSpec(it.inputWidth, MeasureSpec.EXACTLY) }
        super.onMeasure(fullWidth ?: widthMeasureSpec, heightMeasureSpec)
    }
}

/**
 * Status and an optional secondary action sit above the full-width bottom query row.
 * [trailingStatusAction] is normally the single Search options control; the caller owns its
 * visibility, enabled state, accessible label and callback. Both rows retain their children.
 */
class ZfmSearchChrome @JvmOverloads constructor(
    context: Context,
    theme: ZfmTheme,
    private val queryHost: ZfmSearchInput,
    private val statusHost: View,
    private val trailingStatusAction: View? = null,
) : LinearLayout(context) {
    private var theme = theme
    private val surfaceStyle = NativeSurfaceStyle(Color.TRANSPARENT, cornerRadiusDp = 0f)
    private val querySurface = NativeSurface(context, surfaceStyle).apply {
        addView(queryHost, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val statusRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(statusHost, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        trailingStatusAction?.let { addView(it, LayoutParams(0, 0)) }
    }
    private var compactSearch = false
    private var status = ""

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(statusRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(querySurface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        bindTheme(theme)
    }

    fun bindTheme(theme: ZfmTheme) {
        this.theme = theme
        queryHost.bindTheme(theme)
        theme.bindCard(querySurface, surfaceStyle)
        applyGeometry()
        requestLayout()
    }

    private fun applyGeometry() {
        val toolbar = theme.appearance.toolbars
        val density = resources.displayMetrics.density
        val target = (toolbar.action.minimumTargetDp * density).roundToInt()
        trailingStatusAction?.layoutParams?.apply { width = target; height = target }
        // Align Options with Clear without insetting an ordinary navigation/status host.
        val inset = if (compactSearch && trailingStatusAction != null && trailingStatusAction.visibility != GONE)
            (toolbar.centeredPaddingDp * density).roundToInt() else 0
        statusRow.setPaddingRelative(0, 0, inset, 0)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyGeometry()
        requestLayout()
    }

    fun bind(compactSearch: Boolean, status: String, statusLabel: TextView?) {
        val statusChanged = this.status != status
        if (this.compactSearch == compactSearch && !statusChanged) return
        this.compactSearch = compactSearch
        this.status = status
        applyGeometry()
        if (statusChanged) statusLabel?.requestLayout()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        applyGeometry() // Visibility can change while the status string stays the same.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
