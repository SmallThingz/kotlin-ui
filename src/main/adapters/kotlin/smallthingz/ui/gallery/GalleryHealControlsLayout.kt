package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.OneShotPreDrawListener
import kotlin.math.roundToInt

/** Spends only spare, already-measured Heal panel space; never changes its measurement. */
open class GalleryHealControlsLayout(context: Context) : LinearLayout(context) {
    private var pendingDecisionPlacement: OneShotPreDrawListener? = null
    private var attachmentGeneration = 0

    /** The app owns phase/admission; the enclosing scroll view owns viewport measurement. */
    var selectionViewportState = GalleryHealViewportState.Natural
        set(value) {
            if (field == value) return
            field = value
            if (value != GalleryHealViewportState.Retained) selectionViewportEpoch++
            requestLayout()
        }
    internal var selectionViewportEpoch = 0L
        private set

    var reservedDecisionGroup: GalleryHealDecisionGroup? = null
    /** Retained status row; placement changes neither its text nor accessibility identity. */
    var reservedReadyGuidance: View? = null
    var anchorReadyDecision = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (pendingDecisionPlacement != null || decisionWithSpareSpace() == null) return
        // ScrollView clamps its offset after laying out its children.
        val generation = attachmentGeneration
        pendingDecisionPlacement = OneShotPreDrawListener.add(this) {
            if (generation != attachmentGeneration) return@add
            pendingDecisionPlacement = null
            val group = decisionWithSpareSpace()
            if (group != null && fullyFitsScrollViewport()) {
                placeReadyGuidance(group)
                group.extendDecisionTo(height - paddingBottom)
            }
        }
    }

    override fun onDetachedFromWindow() {
        attachmentGeneration++
        pendingDecisionPlacement?.removeListener()
        pendingDecisionPlacement = null
        super.onDetachedFromWindow()
    }

    private fun decisionWithSpareSpace(): GalleryHealDecisionGroup? {
        val group = reservedDecisionGroup ?: return null
        if (!anchorReadyDecision || group.parent !== this || group.visibility != View.VISIBLE ||
            height - paddingBottom <= group.bottom) return null
        // Details or any other later visible content must retain ordinary vertical flow.
        val lastVisible = (childCount - 1 downTo 0).firstOrNull { getChildAt(it).visibility != View.GONE }
        return if (lastVisible != null && getChildAt(lastVisible) === group) group else null
    }

    private fun placeReadyGuidance(group: GalleryHealDecisionGroup) {
        val guidance = reservedReadyGuidance ?: return
        if (guidance.parent !== this || guidance.visibility != View.VISIBLE) return
        val guidanceIndex = indexOfChild(guidance)
        val groupIndex = indexOfChild(group)
        if (guidanceIndex >= groupIndex) return
        // Never move guidance past progress, a warning or another retained flow item.
        if ((guidanceIndex + 1 until groupIndex).any { getChildAt(it).visibility != View.GONE }) return
        val gap = maxOf(group.top - guidance.bottom,
            (GalleryDesign.appearance.modals.sheet.actionsGapDp *
                resources.displayMetrics.density).roundToInt().coerceAtLeast(0))
        val anchoredGroupTop = height - paddingBottom - group.height
        val displacement = anchoredGroupTop - gap - guidance.bottom
        // Spend spare space only. super.onLayout restores natural positions every time.
        if (displacement > 0) guidance.offsetTopAndBottom(displacement)
    }

    private fun fullyFitsScrollViewport(): Boolean {
        var ancestor = parent
        var foundViewport = false
        while (ancestor is ViewGroup) {
            if (ancestor is ScrollView) {
                foundViewport = true
                val panel = Rect(0, 0, width, height)
                ancestor.offsetDescendantRectToMyCoords(this, panel)
                // Drawing rect includes the actual scroll offset; compare in the same
                // content coordinates. A partial/offscreen panel keeps natural flow.
                val viewport = Rect()
                ancestor.getDrawingRect(viewport)
                viewport.left += ancestor.paddingLeft
                viewport.top += ancestor.paddingTop
                viewport.right -= ancestor.paddingRight
                viewport.bottom -= ancestor.paddingBottom
                if (!viewport.contains(panel)) return false
            }
            ancestor = ancestor.parent
        }
        return foundViewport
    }
}

/** Scroll-content end clearance for an already EXACT-height Heal sidebar.
 * Owns only the supplied body's bottom padding until close; creates no view/scroll owner.
 * The caller disables this binding before intrinsic/non-sidebar measurement.
 */
class GalleryHealScrollEndInset(private val body: View) : AutoCloseable {
    private val originalBottom = body.paddingBottom
    private var appliedInset = 0
    private var closed = false

    fun refresh(enabled: Boolean) {
        if (closed) return
        val inset = if (enabled) (GalleryDesign.appearance.modals.sheet.actionsGapDp *
            body.resources.displayMetrics.density).roundToInt().coerceAtLeast(0) else 0
        if (inset == appliedInset) return
        appliedInset = inset
        body.setPadding(body.paddingLeft, body.paddingTop, body.paddingRight, originalBottom + inset)
    }

    override fun close() {
        if (closed) return
        refresh(false)
        closed = true
    }
}

/** Natural content remains scrollable when an owned Heal job returns a longer error. */
enum class GalleryHealViewportState { Natural, Selecting, Retained }

/** Retains only an intrinsic portrait viewport, never the error body's measured height. */
open class GalleryHealControlsScrollView(context: Context) : ScrollView(context) {
    var healControls: GalleryHealControlsLayout? = null
        set(value) {
            if (field === value) return
            field = value
            selectingHeight = 0
            requestLayout()
        }
    private var selectingHeight = 0
    private var measuredWidthSpec = 0
    private var measuredDirection = -1
    private var measuredPadding = Rect()
    private var measuredEpoch = -1L
    private val measuredConfiguration = Configuration()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val controls = healControls
        if (controls == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val configuration = resources.configuration
        val state = controls.selectionViewportState
        val padding = Rect(paddingLeft, paddingTop, paddingRight, paddingBottom)
        val intrinsicPortrait = !isFillViewport &&
            configuration.screenWidthDp <= configuration.screenHeightDp &&
            MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED &&
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST
        if (!intrinsicPortrait || state == GalleryHealViewportState.Natural ||
            measuredWidthSpec != widthMeasureSpec || measuredDirection != layoutDirection ||
            measuredPadding != padding || measuredConfiguration != configuration ||
            measuredEpoch != controls.selectionViewportEpoch) selectingHeight = 0
        measuredWidthSpec = widthMeasureSpec
        measuredDirection = layoutDirection
        measuredPadding = padding
        measuredConfiguration.setTo(configuration)
        measuredEpoch = controls.selectionViewportEpoch
        val retained = if (intrinsicPortrait && state == GalleryHealViewportState.Retained)
            selectingHeight else 0
        // A real height constraint clamps this viewport without replacing the baseline.
        // ScrollView still measures the body naturally; error text and Details can scroll.
        val boundedHeight = if (retained > 0) MeasureSpec.makeMeasureSpec(
            minOf(retained, MeasureSpec.getSize(heightMeasureSpec)), MeasureSpec.EXACTLY)
            else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, boundedHeight)
        if (intrinsicPortrait && state == GalleryHealViewportState.Selecting)
            selectingHeight = measuredHeight
    }

    override fun onDetachedFromWindow() {
        selectingHeight = 0
        super.onDetachedFromWindow()
    }
}
