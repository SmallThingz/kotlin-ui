package smallthingz.ui.reverb

import android.content.Context
import android.os.Bundle
import android.text.Layout
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import smallthingz.ui.components.*
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/** Localized labels, separate from the application's command/status model. */
data class ReverbCommandRecordLabels(
    val readFull: String,
    val outputHeading: String,
    val expanded: String,
    val collapsed: String,
)

/**
 * Presentation proposal: the owner supplies already-redacted display strings and expansion state.
 * [key] is opaque and only retires recycled visual/input state. It is never interpreted as a
 * command ID. [detailsWhenStatusClipped] is an owner-formatted alternative to [details]; the view
 * selects it only when its actual status layout is ellipsized. [fullCommandAvailable] represents
 * owner-side preview shortening; additional width clipping is this component's responsibility.
 * No commands, phases, history, byte limits, counting, security policy or asynchronous data enter
 * this API. The host retains those responsibilities and the decision to open any action.
 */
data class ReverbCommandRecord(
    val key: Long,
    val title: String,
    val preview: String?,
    val technicalPreview: Boolean,
    val status: String?,
    val accessibilityDescription: String,
    val details: String,
    val detailsWhenStatusClipped: String,
    val output: String,
    val outputOmission: String?,
    val fullCommandAvailable: Boolean,
    val expanded: Boolean,
)

/**
 * Command-specific content on the canonical recording group surface, retained settings row and
 * NativeExpansionView lifetime/motion. Audio recording content is deliberately not a dependency.
 * All geometry, typography, clipping and disclosure work is here. The host controls expansion;
 * neither bind nor accessibility activation changes the supplied state implicitly.
 *
 * Identical/ callback-only binds do not call NativeSettingsRow.bind (which requests layout),
 * recreate disclosure or replace text. A recycled identity cancels old input and releases output.
 * Theme is window-scoped: construct with the replacement window after configuration recreation.
 */
open class ReverbCommandRecordView(
    context: Context,
    private val theme: ReverbTheme,
    private val motionOwner: ReverbCommandTranscriptFrame,
    private val labels: ReverbCommandRecordLabels,
) : FrameLayout(context) {
    private val appearance = theme.appearance
    private val recipe = appearance.groupSurfaces.recordingRow.surface
    private val palette = theme.palette.pageControls.copy(surface = recipe.fillColor(theme.palette))
    private fun dp(value: Int) = (value * theme.density.density).roundToInt()
    private val card = recipe.create(context, ReverbTextSurface::invalidate).apply {
        recipe.bind(this, theme.palette)
        setJoinedSeparatorColor(recipe.borderColor(theme.palette))
    }
    private var record: ReverbCommandRecord? = null
    private var toggle: (() -> Unit)? = null
    private var read: ((View) -> Unit)? = null
    private var expansion: NativeExpansionView? = null
    private val caret = ReverbGlyphView(context, ReverbIcon.ArrowDropDown, palette.content)
    private val header = object : NativeSettingsRow(context, palette, NativeSettingsRowStyle(
        minHeightDp = 48f, verticalPaddingDp = 8f, minTextWidthDp = 92f, trailingGapDp = 8f,
        titleTextStyle = appearance.typography.nativeTextStyle(NativeTypographyRole.TITLE_MEDIUM),
    )) {
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            if (record == null) return
            info.addAction(if (record?.expanded == true) AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (record == null) return false
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id && record?.expanded == false ||
                action == AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE.id && record?.expanded == true) {
                return performClick()
            }
            return super.performAccessibilityAction(action, arguments)
        }
    }.apply {
        titleView.maxLines = 2
        titleView.ellipsize = TextUtils.TruncateAt.END
        setAccessory(caret, LayoutParams(dp(24), dp(24))) { caret.setTintImmediately(it) }
    }

    private fun text(role: NativeTextRole = NativeTextRole.CONTENT,
                     typography: NativeTypographyRole = NativeTypographyRole.BODY_MEDIUM) =
        NativeText(context, palette, appearance.typography.nativeTextStyle(typography), role).apply {
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        }

    private val preview = text(NativeTextRole.MUTED).apply {
        textAlignment = TEXT_ALIGNMENT_TEXT_START
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 2
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val status = text(typography = NativeTypographyRole.LABEL_MEDIUM).apply {
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(128)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val summary = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        setPaddingRelative(header.paddingStart, 0, header.paddingEnd, dp(12))
        addView(preview, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(status, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
        setOnClickListener { header.performClick() }
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val readFull = NativeMaterialButtonView(context, appearance.buttons.neutralText,
        theme.density, theme.fontResolver, onClick = ::requestFullText).apply {
        bind(labels.readFull, true, theme.palette.buttonPalette)
        filterTouchesWhenObscured = true
    }
    private val readFullTarget = NativeButtonTouchTarget(readFull).apply { visibility = GONE }
    private val details = text().apply { inset(); visibility = GONE }
    private val output = text().apply {
        textDirection = TEXT_DIRECTION_LTR
        textAlignment = TEXT_ALIGNMENT_TEXT_START
    }
    private val outputBlock = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        inset()
        addView(text().apply { text = labels.outputHeading })
        addView(output, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }
    private val omission = text().apply { inset(); visibility = GONE }
    private val disclosure = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        listOf(readFullTarget, details, outputBlock, omission).forEach {
            addView(it, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        card.addRow(header, rowParams())
        card.addRow(summary, rowParams())
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun View.inset() = setPaddingRelative(header.paddingStart, 0, header.paddingEnd, dp(12))
    private fun rowParams() = NativeGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    fun setGroupEdges(first: Boolean, last: Boolean) = card.setJoinedEdges(NativeGroupEdges(first, last))

    fun bind(value: ReverbCommandRecord, onToggle: () -> Unit, onReadFull: (View) -> Unit,
             animateExpansion: Boolean = false) {
        val previous = record
        toggle = onToggle
        read = onReadFull
        if (previous == value) return
        val rebound = previous?.key != value.key
        if (rebound) {
            retireInput()
            discardExpansion()
        }
        record = value
        if (rebound || previous?.title != value.title) header.bind(value.title, onClick = ::requestToggle)
        if (header.contentDescription?.toString() != value.accessibilityDescription) {
            header.contentDescription = value.accessibilityDescription
        }
        preview.textDirection = if (value.technicalPreview) TEXT_DIRECTION_LTR else TEXT_DIRECTION_LOCALE
        preview.bindText(value.preview.orEmpty())
        status.bindText(value.status.orEmpty())
        summary.visibility = if (value.preview.isNullOrEmpty() && value.status == null) GONE else VISIBLE
        val state = if (value.expanded) labels.expanded else labels.collapsed
        if (ViewCompat.getStateDescription(header) != state) ViewCompat.setStateDescription(header, state)
        caret.rotation = if (value.expanded) 180f else 0f
        if (rebound || previous?.expanded != value.expanded) {
            showDisclosure(animateExpansion && !rebound && isAttachedToWindow &&
                readCachedAnimatorDurationScale(context) > 0f)
        } else if (value.expanded) updateDisclosure()
    }

    private fun NativeText.bindText(value: String) {
        if (!value.contentEquals(text)) text = value
        visibility = if (value.isEmpty()) GONE else VISIBLE
    }

    private fun admits(value: ReverbCommandRecord): Boolean =
        isAttachedToWindow && isShown && hasWindowFocus() && isEnabled && acceptsNativeInput() &&
            record === value && isAttachedToWindow && isShown && hasWindowFocus() && isEnabled

    private fun requestToggle() {
        val current = record ?: return
        if (admits(current)) toggle?.invoke()
    }

    private fun requestFullText() {
        val current = record ?: return
        if (current.expanded && readFullTarget.visibility == VISIBLE && admits(current)) read?.invoke(readFull)
    }

    private fun cancelInput() {
        header.cancelPendingInputEvents()
        summary.cancelPendingInputEvents()
        header.isPressed = false
        summary.isPressed = false
        readFull.cancelInput()
    }

    private fun retireInput() {
        cancelInput()
        clearFocus()
        header.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
    }

    fun recycle() {
        retireInput()
        record = null
        toggle = null
        read = null
        discardExpansion()
        header.bind("")
        ViewCompat.setStateDescription(header, null)
        preview.bindText("")
        status.bindText("")
        summary.visibility = GONE
    }

    private fun showDisclosure(animate: Boolean) {
        val expanded = record?.expanded == true
        if (!expanded && disclosure.hasFocus()) header.requestFocus()
        if (!animate) discardExpansion()
        disclosure.importantForAccessibility = if (expanded) IMPORTANT_FOR_ACCESSIBILITY_NO
            else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (!expanded) {
            readFull.cancelInput()
            expansion?.visible(false)
            return
        }
        updateDisclosure()
        val effect = expansion ?: NativeExpansionView(context, !animate, motionOwner.expansionScope()) {
            object : NativeExpansionContent {
                override val view: View get() = disclosure
                override fun close() {
                    readFull.cancelInput()
                    readFullTarget.visibility = GONE
                    details.bindText("")
                    output.bindText("")
                    outputBlock.visibility = GONE
                    omission.bindText("")
                }
            }
        }.also { expansion = it; card.addRow(it, rowParams()) }
        effect.visible(true)
    }

    private fun discardExpansion() {
        val previous = expansion ?: return
        expansion = null
        previous.close()
        card.removeRow(previous)
    }

    /** Host scrolling waits only while actual disclosure geometry is changing. */
    fun expansionResizing(): Boolean {
        val effect = expansion ?: return false
        val content = effect.content?.view ?: return false
        return record?.expanded != true || effect.isLayoutRequested || content.isLayoutRequested ||
            effect.height != content.measuredHeight
    }

    private fun updateDisclosure() {
        val value = record?.takeIf { it.expanded } ?: return
        readFullTarget.visibility = if (value.fullCommandAvailable || preview.isClipped()) VISIBLE else GONE
        details.bindText(if (status.isClipped()) value.detailsWhenStatusClipped else value.details)
        output.bindText(value.output)
        outputBlock.visibility = if (value.output.isEmpty()) GONE else VISIBLE
        omission.bindText(value.outputOmission.orEmpty())
    }

    private fun NativeText.isClipped(): Boolean = visibility == VISIBLE && layout?.let { layout ->
        (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }
    } == true

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateDisclosure()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (record?.expanded == true && expansion == null) showDisclosure(animate = false)
    }

    override fun onDetachedFromWindow() {
        // A detached window can return with the same reader position and keyboard focus.
        // Only a recycled identity retires focus; detached input and motion still stop here.
        cancelInput()
        discardExpansion()
        super.onDetachedFromWindow()
    }
}
