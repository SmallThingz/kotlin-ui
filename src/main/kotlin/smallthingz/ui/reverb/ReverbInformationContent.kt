package smallthingz.ui.reverb

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import smallthingz.ui.common.nativeSurfaceInputAllowed
import smallthingz.ui.components.NativeActionSheetContentContext
import smallthingz.ui.components.NativeButtonTouchTarget
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.acceptsNativeInput
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.util.closePreservingPrimaryFailure
import kotlin.math.roundToInt

/** Paragraphs and navigational text actions using Reverb's typography and shared button recipes.
 * A sheet supplies its existing input/accessibility host; this component never creates a window.
 */
class ReverbInformationContent<K : Any>(
    context: Context,
    private val theme: ReverbTheme,
    private val linkError: String,
    private val sheet: NativeActionSheetContentContext? = null,
    private val onOpen: (K) -> Boolean,
) : AutoCloseable {
    private val actions = ReverbInformationActions<K>()
    private val leases = mutableListOf<AutoCloseable>()
    private val hits = mutableListOf<NativeViewPointerHitLayout>()
    private var bound: List<ReverbInformationStep<K>>? = null
    private var closed = false
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val focusLease = cancelInformationInputOnFocusLoss(view, view::cancelPendingInputEvents)
    private val error = createReverbSupportingParagraph(context, linkError, NativeTypographyRole.BODY_SMALL, theme).apply {
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun bind(steps: List<ReverbInformationStep<K>>) {
        if (closed || bound == steps) return
        val version = actions.bind(steps.mapNotNull { it.link?.id })
        clearContent()
        bound = steps.toList()
        error.visibility = View.GONE
        steps.forEachIndexed { index, step ->
            val paragraph = theme.paragraph(view.context, step.paragraph, NativeTypographyRole.BODY_MEDIUM)
            view.addView(paragraph, LinearLayout.LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = (theme.appearance.modals.floating.headingGapDp * theme.density.density).roundToInt()
            })
            bindText(paragraph)
            step.link?.let { link ->
                val activate: () -> Unit = {
                    if (actions.admits(version, link.id) && reverbInformationInputAllowed(view) && actions.admits(version, link.id)) {
                        val opened = onOpen(link.id)
                        if (actions.admits(version, link.id)) error.visibility = if (opened) View.GONE else View.VISIBLE
                    }
                }
                if (sheet != null) {
                    val button = ReverbSheetButton(view.context, sheet, theme, false, link.label, activate)
                    view.addView(button, LinearLayout.LayoutParams(-2, -2))
                    leases += button
                    hits += button.hitLayout
                } else {
                    val button = NativeMaterialButtonView(view.context, theme.appearance.buttons.text,
                        theme.density, theme.fontResolver, onClick = activate).apply {
                        filterTouchesWhenObscured = true
                        bind(link.label, true, theme.palette.buttonPalette, onClick = activate)
                    }
                    view.addView(NativeButtonTouchTarget(button), LinearLayout.LayoutParams(-2, -2))
                    leases += button
                }
            }
        }
        view.addView(error, LinearLayout.LayoutParams(-1, -2))
        bindText(error)
    }

    private fun bindText(text: ParagraphView) {
        val host = sheet ?: return
        leases += text.bindPlainTextAccessibility(host.accessibility.cursor, host.accessibility.geometry,
            { host.accessibility.sourceNode(text) }) { host.accessibility.sourceNode(text).present }
        hits += host.hit(text, emptyList())
    }

    fun hitLayouts(host: NativeActionSheetContentContext): List<NativeViewPointerHitLayout> {
        require(host === sheet)
        return hits.toList()
    }

    private fun clearContent() {
        view.cancelPendingInputEvents()
        var failure: Throwable? = null
        leases.asReversed().forEach { failure = closePreservingPrimaryFailure(failure, it::close) }
        leases.clear()
        hits.clear()
        bound = null
        view.removeAllViews()
        failure?.let { throw it }
    }

    override fun close() {
        if (closed) return
        closed = true
        actions.close()
        focusLease.close()
        clearContent()
        bound = null
    }
}

internal fun reverbInformationInputAllowed(view: View): Boolean =
    view.isAttachedToWindow && view.isShown && view.hasWindowFocus() && view.acceptsNativeInput() &&
        nativeSurfaceInputAllowed(view) && view.isAttachedToWindow && view.isShown && view.hasWindowFocus()
