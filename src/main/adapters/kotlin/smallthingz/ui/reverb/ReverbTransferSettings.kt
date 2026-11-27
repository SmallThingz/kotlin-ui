package smallthingz.ui.reverb

import android.graphics.Path
import android.view.View
import android.widget.FrameLayout
import smallthingz.ui.components.CachedPathIconDrawable
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativePathIconMask
import smallthingz.ui.components.NativeSettingsRow
import smallthingz.ui.components.NativeSettingsRowStyle
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.view.ParagraphView
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

enum class ReverbTransferDirection { IMPORT, EXPORT }
data class ReverbTransferAction<K : Any>(
    val id: K,
    val title: String,
    val detail: String,
    val direction: ReverbTransferDirection,
    val enabled: Boolean,
)

/** Ongoing work or recovery belongs to its operation row, never a terminal-result footer. */
data class ReverbTransferProgress<K : Any>(val id: K, val message: String)

/** Semantic rows installed into the host's existing shared Settings section.
 * The host owns disclosures, document pickers, transfers and all authorization decisions.
 */
class ReverbTransferSettings<K : Any>(
    private val group: NativeGroupLayout,
    private val theme: ReverbTheme,
    initial: List<ReverbTransferAction<K>>,
    private val onAction: (K) -> Unit,
) : AutoCloseable {
    private val recipe = NativeSettingsRowStyle()
    private val actions = ReverbInformationActions<K>()
    private val rows = linkedMapOf<K, NativeSettingsRow>()
    private val directions = initial.associate { it.id to it.direction }
    private val icons = initial.associate { action ->
        action.id to CachedPathIconDrawable({ transferGlyph(action.direction) }, theme.density.density, theme.palette.muted)
    }
    private var closed = false
    private var bound: List<ReverbTransferAction<K>>? = null
    private var boundProgress: ReverbTransferProgress<K>? = null
    private var boundMessage: String? = null
    private var legacyStatus: LegacyStatus? = null
    // An empty settings block has no owned row to mark the footer's construction position.
    private val emptyStatusPredecessors = if (initial.isEmpty()) {
        val content = group.pointerContent
        List(content.childCount) { WeakReference(content.getChildAt(it)) }
    } else {
        emptyList()
    }
    private val focusLease = cancelInformationInputOnFocusLoss(group) { rows.values.forEach(View::cancelPendingInputEvents) }

    init {
        require(initial.map { it.id }.distinct().size == initial.size)
        initial.forEachIndexed { index, action ->
            val row = NativeSettingsRow(group.context, theme.palette.pageControls, recipe).apply {
                filterTouchesWhenObscured = true
            }
            rows[action.id] = row
            group.addRow(row, NativeGroupLayout.LayoutParams(-1, -2).apply { dividerTop = index > 0 })
        }
        bind(initial)
    }

    /** Anchor access is for the host's secure confirmation window, never for presentation changes. */
    fun anchor(id: K): View = checkNotNull(rows[id])

    /** Publish the legacy status below the transfer actions. An empty message hides the status. */
    fun bind(values: List<ReverbTransferAction<K>>, message: String) {
        bind(values, progress = null, message = message)
    }

    fun bind(values: List<ReverbTransferAction<K>>, progress: ReverbTransferProgress<K>? = null) {
        bind(values, progress, message = null)
    }

    private fun bind(
        values: List<ReverbTransferAction<K>>,
        progress: ReverbTransferProgress<K>?,
        message: String?,
    ) {
        if (closed || (bound == values && boundProgress == progress && boundMessage == message)) return
        require(values.map { it.id } == rows.keys.toList() && values.all { directions[it.id] == it.direction }) {
            "Transfer action identities and directions must remain stable."
        }
        require(progress == null || (progress.id in rows && progress.message.isNotBlank()))
        bound = values.toList()
        boundProgress = progress
        boundMessage = message
        val version = actions.bind(values.filter { it.enabled }.map { it.id })
        values.forEach { action ->
            val row = checkNotNull(rows[action.id])
            val operation = progress?.takeIf { it.id == action.id }
            row.detailView.accessibilityLiveRegion = if (operation == null) View.ACCESSIBILITY_LIVE_REGION_NONE
                else View.ACCESSIBILITY_LIVE_REGION_POLITE
            row.bind(action.title, operation?.message ?: action.detail, icons.getValue(action.id), onClick = if (action.enabled) ({
                if (actions.admits(version, action.id) && reverbInformationInputAllowed(row) &&
                    actions.admits(version, action.id)) onAction(action.id)
            }) else null)
        }
        if (message == null) {
            removeLegacyStatus()
        } else {
            val status = legacyStatus ?: addLegacyStatus()
            status.paragraph.setText(message)
            status.host.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun addLegacyStatus(): LegacyStatus {
        val paragraph = createReverbSupportingParagraph(
            group.context, "", NativeTypographyRole.BODY_SMALL, theme,
        ).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val host = FrameLayout(group.context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
            val side = (recipe.horizontalPaddingDp * theme.density.density).roundToInt()
            val vertical = (recipe.verticalPaddingDp * theme.density.density).roundToInt()
            setPaddingRelative(side, vertical, side, vertical)
            addView(paragraph, FrameLayout.LayoutParams(-1, -2))
        }
        val status = LegacyStatus(host, paragraph)
        legacyStatus = status
        val content = group.pointerContent
        val precedingIndex = rows.values.lastOrNull()?.let(content::indexOfChild)
            ?: emptyStatusPredecessors.asReversed().firstNotNullOfOrNull { predecessor ->
                predecessor.get()?.let(content::indexOfChild)?.takeIf { it >= 0 }
            }
        group.addRow((precedingIndex ?: -1) + 1, host, NativeGroupLayout.LayoutParams(-1, -2))
        return status
    }

    private fun removeLegacyStatus() {
        val status = legacyStatus ?: return
        legacyStatus = null
        group.removeRow(status.host)
    }

    override fun close() {
        if (closed) return
        closed = true
        focusLease.close()
        bound = null
        boundProgress = null
        boundMessage = null
        actions.close()
        rows.values.forEach { it.cancelPendingInputEvents(); group.removeRow(it) }
        rows.clear()
        removeLegacyStatus()
    }

    private class LegacyStatus(val host: FrameLayout, val paragraph: ParagraphView)
}

// Google Material Icons, Apache-2.0, material-design-icons 10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7:
// src/file/{file_download,file_upload}/materialicons/24px.svg. Retains the host's original glyphs.
private val importGlyph by lazy { NativePathIconMask(Path().apply {
    moveTo(19f, 9f); lineTo(15f, 9f); lineTo(15f, 3f); lineTo(9f, 3f); lineTo(9f, 9f)
    lineTo(5f, 9f); lineTo(12f, 16f); close()
    moveTo(5f, 18f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 18f); close()
}) }
private val exportGlyph by lazy { NativePathIconMask(Path().apply {
    moveTo(9f, 16f); lineTo(15f, 16f); lineTo(15f, 10f); lineTo(19f, 10f)
    lineTo(12f, 3f); lineTo(5f, 10f); lineTo(9f, 10f); close()
    moveTo(5f, 18f); lineTo(19f, 18f); lineTo(19f, 20f); lineTo(5f, 20f); close()
}) }
private fun transferGlyph(direction: ReverbTransferDirection) = when (direction) {
    ReverbTransferDirection.IMPORT -> importGlyph
    ReverbTransferDirection.EXPORT -> exportGlyph
}
