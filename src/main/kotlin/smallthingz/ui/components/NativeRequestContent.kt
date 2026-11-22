package smallthingz.ui.components

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/**
 * View-only content for a plain-text request. The host supplies its trusted identity, actions and
 * action-input cleanup via [header], [footer] and [dispose]. Create and close on the main thread.
 *
 * The header may scroll with the request. The footer stays outside that scroll region and only
 * scrolls independently when it cannot fit. Neither construction nor close dispatches decisions.
 */
class NativeRequestContent(
    context: Context,
    colors: NativeColorScheme,
    title: CharSequence,
    body: CharSequence,
    footer: View,
    header: View? = null,
    hint: CharSequence? = null,
    appearance: NativeUiAppearance = NativeUiAppearance.Default,
    maxHeightDp: Int = 480,
    dispose: () -> Unit = {},
) : AutoCloseable {
    private var release: (() -> Unit)?
    val view: View

    init {
        require(maxHeightDp > 0)
        require(footer.parent == null && header?.parent == null && footer !== header)
        val requestTitle = boundedRequestText(title, 120)
        val requestBody = boundedRequestText(body, 4000)
        val recipe = appearance.modals.permissionDialog
        fun text(value: String, role: NativeTypographyRole, color: Int) =
            NativeText(context, colors.pageControls.copy(content = color),
                appearance.modals.typography.nativeTextStyle(role)).apply {
                text = value
                gravity = Gravity.START
            }
        fun row(gapDp: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = (gapDp * context.resources.displayMetrics.density).roundToInt()
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            header?.let { addView(it, row()) }
            addView(text(requestTitle, NativeTypographyRole.TITLE_LARGE, colors.ink).apply {
                isAccessibilityHeading = true
            }, row(if (header == null) 0 else recipe.identityGapDp))
            addView(text(requestBody, NativeTypographyRole.BODY_MEDIUM, colors.ink), row(recipe.identityGapDp))
            hint?.toString()?.takeIf { it.isNotEmpty() }?.let {
                addView(text(it, NativeTypographyRole.BODY_SMALL, colors.muted), row(recipe.scopeGapDp))
            }
        }
        val bodyScroll = ScrollView(context).apply { addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        val footerScroll = ScrollView(context).apply { addView(footer, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        val panel = RequestContentLayout(context, bodyScroll, footerScroll, maxHeightDp,
            recipe.contentInsetDp, recipe.scopeGapDp)
        view = panel
        release = {
            try {
                dispose()
            } finally {
                content.removeAllViews()
                footerScroll.removeAllViews()
                panel.removeAllViews()
            }
        }
    }

    override fun close() {
        val cleanup = release ?: return
        release = null
        cleanup()
    }
}

internal fun boundedRequestText(value: CharSequence, maxScalars: Int): String {
    require(value.length.toLong() <= maxScalars.toLong() * 2) { "Request text is too long." }
    val text = value.toString()
    require(text.codePointCount(0, text.length) <= maxScalars) { "Request text is too long." }
    require(text.codePoints().noneMatch { it in 0xD800..0xDFFF }) { "Request text contains an invalid Unicode scalar." }
    return text
}

/** Give the footer the first height allocation, and leave the remaining viewport to request text. */
private class RequestContentLayout(
    context: Context,
    private val body: ScrollView,
    private val footer: ScrollView,
    private val maxHeightDp: Int,
    private val minimumBodyDp: Int,
    private val gapDp: Int,
) : ViewGroup(context) {
    private var gap = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(body)
        addView(footer)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (childCount == 0) {
            setMeasuredDimension(resolveSize(0, widthSpec), resolveSize(0, heightSpec))
            return
        }
        val metrics = resources.displayMetrics
        val width = if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) metrics.widthPixels
            else MeasureSpec.getSize(widthSpec)
        val maxHeight = (maxHeightDp * metrics.density).roundToInt().coerceIn(1, MEASURED_SIZE_MASK)
        val limit = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) maxHeight
            else minOf(maxHeight, MeasureSpec.getSize(heightSpec))
        val innerWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val verticalPadding = paddingTop + paddingBottom
        val innerHeight = (limit - verticalPadding).coerceAtLeast(0)
        val minimumBody = minOf((minimumBodyDp * metrics.density).roundToInt(), innerHeight / 3)
        val gapBudget = minOf((gapDp * metrics.density).roundToInt(), innerHeight / 6)
        val exactWidth = MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY)
        val footerLimit = innerHeight - minimumBody - gapBudget
        footer.measure(exactWidth, MeasureSpec.makeMeasureSpec(footerLimit, MeasureSpec.AT_MOST))
        gap = if (footer.measuredHeight > 0) gapBudget else 0
        val bodyLimit = innerHeight - footer.measuredHeight - gap
        body.measure(exactWidth, MeasureSpec.makeMeasureSpec(bodyLimit, MeasureSpec.AT_MOST))
        if (body.measuredHeight == 0) gap = 0
        setMeasuredDimension(resolveSize(width, widthSpec),
            resolveSize(minOf(limit, verticalPadding + body.measuredHeight + gap + footer.measuredHeight), heightSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (childCount == 0) return
        val start = paddingLeft
        val end = (width - paddingRight).coerceAtLeast(start)
        body.layout(start, paddingTop, end, paddingTop + body.measuredHeight)
        val footerTop = body.bottom + gap
        footer.layout(start, footerTop, end, footerTop + footer.measuredHeight)
    }
}
