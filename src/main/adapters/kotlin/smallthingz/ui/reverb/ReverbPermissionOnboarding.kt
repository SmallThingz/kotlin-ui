package smallthingz.ui.reverb

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import smallthingz.ui.components.CachedPathIconDrawable
import smallthingz.ui.components.NativeButtonTouchTarget
import smallthingz.ui.components.NativeDetailCardMarker
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativePermissionRow
import smallthingz.ui.reverb.onboarding.ReverbOnboardingScreenLayout
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

enum class ReverbPermissionOnboardingKind { NOTIFICATIONS, ACCESSIBILITY, BATTERY }

/** Facts and localized copy only. The component never checks, grants, or requests OS permissions. */
data class ReverbPermissionOnboardingState(
    val kind: ReverbPermissionOnboardingKind,
    val title: String,
    val detail: String,
    val requestLabel: String,
    val allowed: Boolean?,
    val status: String,
    val canRequest: Boolean,
)

/** Reverb's existing detail-card/permission-row recipe, also usable in an ordinary Settings host. */
class ReverbPermissionOnboardingRows(
    private val context: Context,
    private val theme: ReverbTheme,
    private val manageGranted: Boolean = false,
    private val onRequest: (ReverbPermissionOnboardingKind) -> Unit,
) : AutoCloseable {
    private val cards = theme.appearance.detailCards
    private val environment = ReverbEnvironment(theme)
    private val rows = linkedMapOf<ReverbPermissionOnboardingKind, Row>()
    private var states = emptyMap<ReverbPermissionOnboardingKind, ReverbPermissionOnboardingState>()
    private var closed = false
    private var bound: List<ReverbPermissionOnboardingState>? = null
    val view = cards.createGroup(context, ReverbTextSurface::invalidate).apply {
        setSurfacePreset(theme.palette.surfacePreset)
        setPalette(theme.palette.field, theme.palette.border)
    }

    private val focusLease = cancelInformationInputOnFocusLoss(view) { rows.values.forEach { it.cancel() } }

    fun bind(values: List<ReverbPermissionOnboardingState>) {
        if (closed || bound == values) return
        val next = values.associateBy { it.kind }
        require(next.size == values.size)
        bound = values.toList()
        val changed = rows.keys.toList() != values.map { it.kind }
        if (changed) rows.values.forEach { it.cancel(); view.removeRow(it.view) }
        rows.keys.filterNot(next::containsKey).forEach { rows.remove(it)?.cancel() }
        states = next
        val ordered = linkedMapOf<ReverbPermissionOnboardingKind, Row>()
        values.forEachIndexed { index, state ->
            val row = rows[state.kind] ?: Row(state.kind)
            row.bind(state)
            ordered[state.kind] = row
            if (changed) view.addRow(row.view, NativeGroupLayout.LayoutParams(-1, -2).apply { dividerTop = index > 0 })
        }
        rows.clear()
        rows.putAll(ordered)
    }

    private fun request(kind: ReverbPermissionOnboardingKind) {
        val state = states[kind] ?: return
        val row = rows[kind] ?: return
        if (!closed && state.canRequest && (manageGranted || state.allowed != true) &&
            reverbInformationInputAllowed(row.view) && states[kind] === state && rows[kind] === row) onRequest(kind)
    }

    override fun close() {
        if (closed) return
        closed = true
        focusLease.close()
        bound = null
        states = emptyMap()
        rows.values.forEach { it.cancel(); view.removeRow(it.view) }
        rows.clear()
    }

    private inner class Row(private val kind: ReverbPermissionOnboardingKind) {
        private val leadingDrawable = CachedPathIconDrawable({ permissionOnboardingGlyph(kind) },
            theme.density.density, theme.palette.onPrimaryContainer)
        private val leading = ImageView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageDrawable(leadingDrawable)
        }
        private val marker = NativeDetailCardMarker(context, theme.appearance.typography[NativeTypographyRole.TITLE_MEDIUM],
            theme.density, theme.fontResolver, cards, glyph = leading, onGlyphTint = leadingDrawable::setTint,
            onPaintedSurfaceChanged = ReverbTextSurface::invalidate)
        private val indicator = ReverbGlyphView(context, ReverbIcon.Unchecked, theme.palette.muted).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val manage = if (manageGranted) ReverbStandardIconButtonView(context, environment, { null }) else null
        val view = NativePermissionRow(context, theme.appearance.typography[NativeTypographyRole.TITLE_MEDIUM],
            theme.appearance.typography[NativeTypographyRole.BODY_MEDIUM], theme.density, theme.fontResolver,
            marker, manage ?: indicator, independentAccessory = manage != null, style = cards).apply {
            filterTouchesWhenObscured = true
        }
        private var bound: ReverbPermissionOnboardingState? = null

        fun bind(state: ReverbPermissionOnboardingState) {
            if (state == bound) return
            cancel()
            bound = state
            val granted = state.allowed == true
            val glyph = when (state.allowed) { null -> ReverbIcon.Settings; true -> ReverbIcon.Checked; false -> ReverbIcon.Unchecked }
            val ink = if (granted) theme.palette.primary else theme.palette.muted
            val accessory = manage ?: indicator
            accessory.visibility = if (state.allowed != null || state.canRequest) View.VISIBLE else View.INVISIBLE
            if (manage != null) {
                manage.filterTouchesWhenObscured = true
                manage.bind(environment, glyph, state.requestLabel, state.canRequest, ink, ink) { request(kind) }
            } else indicator.bind(glyph, ink)
            view.bindPermission(state.title, state.detail, granted, state.status, theme.palette,
                requestEnabled = state.canRequest, requestLabel = state.requestLabel,
                onRequest = if (manage == null) ({ request(kind) }) else null)
        }

        fun cancel() { view.cancelPendingInputEvents(); manage?.cancelInput() }
    }
}

/** Single-page permission setup using Reverb's screen insets, permission cards and footer action.
 * This does not infer permission readiness: the host supplies Continue admission independently.
 */
open class ReverbPermissionOnboardingScreen(
    context: Context,
    private val theme: ReverbTheme,
    title: String,
    continueLabel: String,
    unavailableMessage: String,
    onRequest: (ReverbPermissionOnboardingKind) -> Unit,
    private val onContinue: () -> Unit,
    groupMissingPermissions: Boolean = false,
) : ViewGroup(context) {
    // Preserve the original constructor, including Kotlin trailing-lambda and JVM callers.
    constructor(context: Context, theme: ReverbTheme, title: String, continueLabel: String,
        unavailableMessage: String, onRequest: (ReverbPermissionOnboardingKind) -> Unit,
        onContinue: () -> Unit) : this(context, theme, title, continueLabel, unavailableMessage,
            onRequest, onContinue, false)

    private val geometry = ReverbOnboardingScreenLayout(theme.density)
    private val permissions = if (groupMissingPermissions) null else ReverbPermissionOnboardingRows(context, theme, onRequest = onRequest)
    private val notices = if (groupMissingPermissions) ReverbPermissionNotices(context, theme, onRequest) else null
    private var canContinue = false
    private val unavailable = createReverbSupportingParagraph(context, unavailableMessage, NativeTypographyRole.BODY_SMALL, theme).apply {
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    // This footer wraps its action instead of assigning the pager footer's exact button size.
    // Retain the owner's complete footer recipe while measuring its surface intrinsically.
    private val continueRecipe = theme.appearance.buttons.footerContinue.let {
        it.copy(surface = it.surface.copy(assignedSize = false))
    }
    private val done = NativeMaterialButtonView(context, continueRecipe,
        theme.density, theme.fontResolver, onClick = {
            if (canContinue && reverbInformationInputAllowed(this) && canContinue) onContinue()
        }).apply { filterTouchesWhenObscured = true }
    private val page = ScrollView(context).apply {
        isFillViewport = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Same icon/title/card order and spacing as ReverbOnboardingPageLayout's permission page.
            addView(ReverbGlyphView(context, ReverbIcon.BackgroundWarning, theme.palette.primary),
                LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(theme.paragraph(context, title, NativeTypographyRole.HEADLINE_MEDIUM).apply {
                isAccessibilityHeading = true
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            addView(notices?.view ?: requireNotNull(permissions).view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
            addView(unavailable, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(theme.appearance.outlinedField.supportingPaddingDp)
            })
        }, FrameLayout.LayoutParams(-1, -2))
    }
    private val footer = FrameLayout(context).apply {
        setPadding(0, dp(theme.appearance.onboardingFooter.actionsTopGapDp), 0, 0)
        addView(NativeButtonTouchTarget(done), FrameLayout.LayoutParams(-2, -2, Gravity.END))
    }
    private val label = continueLabel
    private var externalFooter = false

    /** A containing Reverb onboarding host owns the whole footer and outer page spacing. */
    fun useExternalFooter() {
        if (externalFooter) return
        externalFooter = true
        done.cancelInput()
        removeView(footer)
        requestLayout()
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(page)
        addView(footer)
        done.bind(label, false, theme.palette.buttonPalette)
    }

    fun bindPermissions(values: List<ReverbPermissionOnboardingState>, continueEnabled: Boolean) {
        permissions?.bind(values)
        notices?.bind(values)
        if (canContinue != continueEnabled) done.cancelInput()
        canContinue = continueEnabled
        done.bind(label, continueEnabled, theme.palette.buttonPalette)
    }

    fun showUnavailable() { unavailable.visibility = View.VISIBLE }

    /** Retire a previous launch failure only after the host observes a successful retry. */
    fun clearUnavailable() { unavailable.visibility = View.GONE }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        if (externalFooter) {
            page.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        } else geometry.measure(width, height, Rect(), page, footer)
        setMeasuredDimension(resolveSize(width, w), resolveSize(height, h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (externalFooter) page.layout(0, 0, r - l, b - t)
        else geometry.layout(page, footer)
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) cancelPendingInputEvents()
        super.onWindowFocusChanged(hasWindowFocus)
    }
    override fun onDetachedFromWindow() {
        cancelPendingInputEvents()
        super.onDetachedFromWindow()
    }
    private fun dp(value: Int) = dp(value.toFloat())
    private fun dp(value: Float) = (value * theme.density.density).roundToInt()
}
