package smallthingz.ui.reverb

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import smallthingz.ui.components.NativeDividerLayout
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.atMost
import smallthingz.ui.components.exact
import smallthingz.ui.components.unspecified
import smallthingz.ui.reverb.settings.ReverbSettingsActionView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Reverb's compact warning surface: one group, only missing permissions, independent review actions.
 * The host supplies live facts on resume/focus/permission results; this view never queries or grants access.
 * Owns its external insets, including when mounted directly between a header and weighted content.
 * The complete notice uses at most half the offered height and 168dp, with internal scrolling.
 */
class ReverbPermissionNotices(
    context: Context,
    theme: ReverbTheme,
    private val onRequest: (ReverbPermissionOnboardingKind) -> Unit,
) : AutoCloseable {
    private val environment = ReverbEnvironment(theme).forSettings()
    private val state = ReverbPermissionNoticeState()
    private val rows = linkedMapOf<ReverbPermissionOnboardingKind, NoticeRow>()
    private var summaryBound = false
    private var closed = false
    private val recipe = environment.appearance.groupSurfaces.settingsWarning
    private val group = recipe.create(context, ReverbTextSurface::invalidate).also { recipe.bind(it, environment.palette) }
    // Layout-only content leaves paint and text-underlay ownership with the warning group.
    private val details = NativeDividerLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        visibility = View.GONE
    }
    private val scrollContent = NativeDividerLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
    }
    private val viewport = ScrollView(context).apply { addView(scrollContent) }
    private val summary = NoticeRow(context) {
        if (!closed && reverbInformationInputAllowed(view)) {
            state.setExpanded(!state.expanded)
            bindSummary()
        }
    }
    init {
        scrollContent.addView(summary, NativeGroupLayout.LayoutParams(-1, -2))
        scrollContent.addView(details, NativeGroupLayout.LayoutParams(-1, -2).apply { dividerTop = true })
        group.addRow(viewport, NativeGroupLayout.LayoutParams(-1, -1))
    }
    val view: ViewGroup = object : ViewGroup(context) {
        private var sideInset = 0
        private var verticalInset = 0
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = GONE
            addView(group)
        }
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            val offered = MeasureSpec.getSize(h).takeIf { MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED }
            val budget = permissionNoticeHeightBudget(offered, dp(168))
            sideInset = min(dp(16), width / 2)
            verticalInset = min(dp(12), budget / 2)
            val insideWidth = (width - 2 * sideInset).coerceAtLeast(0)
            // Measure natural text only INSIDE the scroll viewport. The painted group itself gets
            // the viewport's bounded size, never its overflowing content height.
            viewport.measure(exact(insideWidth), atMost((budget - 2 * verticalInset).coerceAtLeast(0)))
            group.measure(exact(insideWidth), exact(viewport.measuredHeight))
            setMeasuredDimension(resolveSize(width, w), resolveSize(group.measuredHeight + 2 * verticalInset, h))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            group.layout(sideInset, verticalInset, sideInset + group.measuredWidth, verticalInset + group.measuredHeight)
        }
    }

    /** Stable semantic handles for host validation; no caller-owned layout or styling. */
    val isExpanded: Boolean get() = state.expanded
    val summaryView: View get() = summary
    fun noticeView(kind: ReverbPermissionOnboardingKind): View? = rows[kind]

    private val focusLease = cancelInformationInputOnFocusLoss(view) {
        summary.cancelInput()
        rows.values.forEach { it.cancelInput() }
    }

    fun bind(values: List<ReverbPermissionOnboardingState>, shown: Boolean = true) {
        if (closed || !state.bind(values, shown)) return
        val next = state.items
        // Keep unchanged rows attached so a live refresh does not steal keyboard/TalkBack focus.
        rows.keys.filter { key -> next.none { it.kind == key } }.toList().forEach { key ->
            rows.remove(key)?.let { details.removeView(it); it.close() }
        }
        next.forEachIndexed { index, item ->
            val row = rows.getOrPut(item.kind) {
                NoticeRow(view.context) {
                    val expected = state.revision
                    if (!closed && state.admits(item.kind, expected) && reverbInformationInputAllowed(view) &&
                        state.admits(item.kind, expected) && state.expanded) onRequest(item.kind)
                }.also { details.addView(it, index, NativeGroupLayout.LayoutParams(-1, -2)) }
            }
            row.bind("${item.title}\n${item.detail}", item.requestLabel,
                view.context.getString(smallthingz.ui.R.string.reverb_settings_background_review),
                item.allowed == false && item.canRequest)
            (row.layoutParams as NativeGroupLayout.LayoutParams).dividerTop = index > 0
        }
        view.visibility = if (next.isEmpty()) View.GONE else View.VISIBLE
        bindSummary()
    }

    private fun bindSummary() {
        rows.values.forEach { it.cancelInput() }
        val count = state.items.size
        val title = if (count == 1) state.items.single().title else view.resources.getQuantityString(
            smallthingz.ui.R.plurals.reverb_permission_notices_summary, count, count)
        val action = view.context.getString(if (state.expanded) smallthingz.ui.R.string.reverb_permission_notices_hide
            else smallthingz.ui.R.string.reverb_settings_background_review)
        val description = view.context.getString(if (state.expanded) smallthingz.ui.R.string.reverb_permission_notices_hide
            else smallthingz.ui.R.string.reverb_permission_notices_review)
        summary.bind(title, description, action, count > 0)
        details.visibility = if (state.expanded) View.VISIBLE else View.GONE
        if (!state.expanded && summaryBound) viewport.scrollTo(0, 0)
        summaryBound = true
        group.requestLayout()
    }

    private inner class NoticeRow(context: Context, request: () -> Unit) : ViewGroup(context), AutoCloseable {
        private val icon = ReverbGlyphView(context, ReverbIcon.BackgroundWarning, environment.palette.tertiary)
        private val message = environment.paragraph(context, "", ReverbTextRole.BODY_MEDIUM, environment.palette.onSurface)
        private val action = ReverbSettingsActionView(context, environment, { null }, action = request).apply {
            enableStandaloneAccessibility()
        }
        private var rowHeight = 0
        private var stacked = false
        private var gap = 0
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            message.isScreenReaderFocusable = true
            action.filterTouchesWhenObscured = true
            addView(icon); addView(message); addView(action)
        }
        fun cancelInput() = action.cancelPendingInputEvents()
        fun bind(text: String, description: String, label: String, enabled: Boolean) {
            cancelInput()
            message.setText(text)
            action.setActionDescription(description)
            action.bind(label, enabled, environment)
        }
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            val available = (width - 2 * dp(15)).coerceAtLeast(0)
            icon.measure(exact(min(dp(22), available)), exact(dp(22)))
            gap = min(dp(12), (available - icon.measuredWidth).coerceAtLeast(0))
            action.measure(atMost(available), unspecified())
            val inline = (available - icon.measuredWidth - gap - action.measuredWidth - dp(12)).coerceAtLeast(0)
            message.measure(exact(inline), unspecified())
            stacked = inline == 0 || (message.currentParagraph?.lineCount ?: Int.MAX_VALUE) > 2
            if (stacked) message.measure(exact((available - icon.measuredWidth - gap).coerceAtLeast(0)), unspecified())
            rowHeight = max(icon.measuredHeight, message.measuredHeight)
            if (!stacked) rowHeight = max(rowHeight, action.measuredHeight)
            setMeasuredDimension(resolveSize(width, w), resolveSize(rowHeight + 2 * dp(6) +
                if (stacked) dp(4) + action.measuredHeight else 0, h))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            fun place(child: View, start: Int, top: Int) {
                val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
                child.layout(x, top, x + child.measuredWidth, top + child.measuredHeight)
            }
            place(icon, dp(15), dp(6) + (rowHeight - icon.measuredHeight) / 2)
            place(message, dp(15) + icon.measuredWidth + gap, dp(6) + (rowHeight - message.measuredHeight) / 2)
            place(action, width - dp(15) - action.measuredWidth,
                if (stacked) dp(6) + rowHeight + dp(4) else dp(6) + (rowHeight - action.measuredHeight) / 2)
        }
        override fun close() = action.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        focusLease.close()
        state.bind(emptyList(), false)
        rows.values.forEach { details.removeView(it); it.close() }
        summary.close()
        rows.clear()
        view.visibility = View.GONE
    }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}
