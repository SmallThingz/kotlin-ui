package smallthingz.ui.reverb

import android.app.Activity
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.doOnLayout
import smallthingz.ui.components.NativeActionSheet
import smallthingz.ui.components.NativeDialogEdge
import smallthingz.ui.components.NativePermissionPresentation
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.util.closePreservingPrimaryFailure
import kotlin.math.roundToInt

/** Permission lifetime over the actual Reverb sheet, with no caller-owned window styling. */
internal class ReverbPermissionSheet(
    private val activity: Activity,
    private val theme: ReverbTheme,
    private val icon: Drawable?,
    private val appName: CharSequence,
    private val summary: CharSequence,
    private val denyLabel: CharSequence,
    private val allowLabel: CharSequence,
    private val onDeny: () -> Unit,
    private val onAllow: () -> Unit,
    private val onCancel: () -> Unit,
    private val message: String? = null,
    private val replyHint: String? = null,
) : NativePermissionPresentation {
    private var content: Content? = null
    private var shown = false
    private var ended = false
    private var busy = false
    private var allowEnabled = true
    private var progressMessage: CharSequence? = null
    private var errorMessage: CharSequence? = null

    override fun show() = showAtEdge(NativeDialogEdge.BOTTOM)
    override fun showAtBottom() = showAtEdge(NativeDialogEdge.BOTTOM)

    override fun showAtEdge(edge: NativeDialogEdge) {
        check(!ended) { "Create a new permission dialog for each presentation" }
        if (shown) return
        shown = true
        try {
            val next = Content(edge)
            content = next
            // Match the legacy permission window's current host security inheritance.
            val secure = WindowManager.LayoutParams.FLAG_SECURE
            next.sheet.window?.setFlags(activity.window.attributes.flags and secure, secure)
            next.sheet.show()
        } catch (failure: Throwable) {
            throw checkNotNull(closePreservingPrimaryFailure(failure, ::dismiss))
        }
    }

    private fun respond(allow: Boolean) {
        val current = content ?: return
        if (ended || !shown || busy || allow && !allowEnabled ||
            !current.sheet.isShowing || !current.sheet.canAcceptSensitiveAction) return
        // The callback may dismiss synchronously or begin async work; both actions stay locked.
        setBusy(true)
        if (allow) onAllow() else onDeny()
    }

    private fun cancelFromSheet() {
        val current = content ?: return
        // A completed hide is no longer an action-admitting sheet, but must still own focus.
        if (ended || !shown || busy || !current.sheet.isShowing || !current.sheet.hasCurrentWindowFocus) return
        dismiss()
        onCancel()
    }

    override fun dismiss() {
        if (ended) return
        ended = true
        val previous = content
        content = null
        previous?.close()
    }

    override fun setBusy(value: Boolean) {
        busy = value
        content?.let {
            // Busy changes revoke held Escape/activation/pointer input, including a busy DOWN
            // followed by an idle UP. Focus changes are already owned by the sheet's input gate.
            it.sheet.protectSensitiveInput()
            it.sheet.refreshDismissalAdmission()
            it.bindActions()
        }
    }

    override fun setAllowEnabled(enabled: Boolean) {
        allowEnabled = enabled
        content?.bindActions()
    }

    override fun setProgress(message: CharSequence?) {
        progressMessage = message
        content?.bindProgress()
    }

    override fun setError(message: CharSequence?) {
        errorMessage = message
        content?.bindError()
    }

    private inner class Content(edge: NativeDialogEdge) : AutoCloseable {
        val sheet: NativeActionSheet = if (edge == NativeDialogEdge.BOTTOM)
            createReverbActionSheet(activity.window.decorView, theme, summary.toString(), ::cancelFromSheet)
        else NativeActionSheet(activity.window.decorView, reverbActionSheetStyle(theme), summary.toString(),
            ::cancelFromSheet, contentBackground = null,
            statusBarProtection = ReverbDesign.statusBarProtection(activity, theme), entryEdge = edge)
        private val host = sheet.contentContext
        private val leases = mutableListOf<AutoCloseable>()
        private val body = LinearLayout(sheet.context).apply { orientation = LinearLayout.VERTICAL }
        private val error = createReverbParagraph(sheet.context, "", NativeTypographyRole.BODY_MEDIUM,
            theme.density, theme.fontResolver, theme.palette.error)
        private val progress = createReverbSupportingParagraph(sheet.context, "", NativeTypographyRole.BODY_SMALL, theme)
        private val deny = ReverbSheetButton(sheet.context, host, theme, false, denyLabel.toString(), { respond(false) },
            leadingIcon = ReverbIcon.Close)
        private val allow = ReverbSheetButton(sheet.context, host, theme, true, allowLabel.toString(), { respond(true) },
            leadingIcon = ReverbIcon.Check)
        private val actions = ReverbSheetActions(sheet.context, host, theme, listOf(deny, allow))
        private var closed = false

        init {
            try {
                sheet.protectSensitiveInput()
                sheet.setDismissalAdmission { !ended && shown && !busy && sheet.hasCurrentWindowFocus }
                // Permission requests never treated an accidental outside tap as a response.
                sheet.setDismissOnScrimTap(false)
                sheet.setCanceledOnTouchOutside(false)
                val identity = LinearLayout(sheet.context).apply { gravity = Gravity.CENTER_VERTICAL }
                val titleStyle = theme.appearance.toolbars
                icon?.let { drawable ->
                    identity.addView(ImageView(sheet.context).apply {
                        setImageDrawable(drawable)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(titleStyle.leadingTitleGlyphDp), dp(titleStyle.leadingTitleGlyphDp)))
                }
                val name = createReverbSupportingParagraph(sheet.context, appName.toString(), NativeTypographyRole.BODY_MEDIUM, theme)
                bindText(name)
                identity.addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (icon != null) marginStart = dp(titleStyle.leadingTitleGapDp)
                })
                body.addView(identity, LinearLayout.LayoutParams(-1, -2))
                message?.let { addParagraph(createReverbSupportingParagraph(sheet.context, it, NativeTypographyRole.BODY_MEDIUM, theme)) }
                replyHint?.let { addParagraph(createReverbSupportingParagraph(sheet.context, it, NativeTypographyRole.BODY_SMALL, theme)) }
                for (text in listOf(progress, error)) {
                    text.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                    addParagraph(text)
                }
                bindActions()
                bindProgress()
                bindError()
                sheet.setContent(body, actions, listOf(actions.hitLayout, host.hit(body, emptyList())), scrollable = true)
                // Host lifecycle disposal is terminal and silent, just like explicit dismiss().
                sheet.setOnDismissListener { dismiss() }
            } catch (failure: Throwable) {
                throw checkNotNull(closePreservingPrimaryFailure(failure, ::close))
            }
        }

        private fun dp(value: Float) = (value * theme.density.density).roundToInt()
        private fun addParagraph(text: ParagraphView) {
            bindText(text)
            body.addView(text, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(sheet.style.recipe.bodyGapDp) })
        }
        private fun bindText(text: ParagraphView) {
            leases += text.bindPlainTextAccessibility(host.accessibility.cursor, host.accessibility.geometry,
                { host.accessibility.sourceNode(text) }) { host.accessibility.sourceNode(text).present }
        }
        fun bindActions() {
            allow.bind(allowLabel.toString(), !ended && allowEnabled && !busy) { respond(true) }
            deny.bind(denyLabel.toString(), !ended && !busy) { respond(false) }
        }
        fun bindProgress() {
            progress.setText(progressMessage?.toString().orEmpty())
            progress.visibility = if (progressMessage.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        fun bindError() {
            error.setText(errorMessage?.toString().orEmpty())
            error.visibility = if (errorMessage.isNullOrEmpty()) View.GONE else View.VISIBLE
            if (error.visibility == View.VISIBLE) error.doOnLayout {
                if (!closed && error.visibility == View.VISIBLE)
                    error.requestRectangleOnScreen(Rect(0, 0, error.width, error.height))
            }
        }
        override fun close() {
            if (closed) return
            closed = true
            var failure = closePreservingPrimaryFailure(null, sheet::dismiss)
            failure = closePreservingPrimaryFailure(failure, actions::close)
            leases.asReversed().forEach { failure = closePreservingPrimaryFailure(failure, it::close) }
            leases.clear()
            failure?.let { throw it }
        }
    }
}
