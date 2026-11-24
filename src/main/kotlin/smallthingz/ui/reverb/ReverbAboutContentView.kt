package smallthingz.ui.reverb

import smallthingz.ui.components.NativeModalWindowRuntime
import smallthingz.ui.components.NativeFloatingPanelSurface
import smallthingz.ui.components.NativeModalTextSurface
import smallthingz.ui.components.NativeDecorativeIconView

import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.geometry.roundedGroupContains
import smallthingz.ui.geometry.roundedGroupGeometry
import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.scroll.NativeVerticalScrollSource
import smallthingz.ui.scroll.NativeVerticalScrollState
import smallthingz.ui.scroll.NativeVerticalViewport
import smallthingz.ui.interaction.view.NativeClickBinding
import smallthingz.ui.interaction.view.NativeClickEnvironment
import smallthingz.ui.interaction.view.RowIndication
import smallthingz.ui.interaction.view.RowIndicationSink
import smallthingz.ui.interaction.view.ViewClickController
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.text.view.PlainTextAccessibility
import smallthingz.ui.text.view.PlainTextAccessibilitySnapshot
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.nativeMergedRoleBoundsInScreen
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import smallthingz.ui.geometry.Rect as ComposeRect
import smallthingz.ui.input.phase.PhasePointerRoot
import smallthingz.ui.input.phase.NativeWindowPointerCoordinates
import smallthingz.ui.input.phase.NativePointerCoordinates
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.input.phase.NativeClickPhaseNode
import smallthingz.ui.input.phase.ViewClickPhaseEndpoint
import smallthingz.ui.scroll.NativeVerticalPhaseBinding
import smallthingz.ui.text.TextStyle
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.FrameLayout
import smallthingz.ui.common.NativeModalDragNode
import smallthingz.ui.components.NativeSheetDragHandle
import smallthingz.ui.unit.Velocity
import smallthingz.ui.input.phase.NativeDragSink
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Localized presentation data for the reusable brand panel. */
data class ReverbAboutDetails(
    val name: String,
    val version: String,
    val repositoryTitle: String,
    val repositoryLabel: String,
    val repositoryError: String,
    val identity: ReverbProductIdentity = ReverbProductIdentity.Reverb,
    val paneLabel: String? = null,
)

private class AboutContentAppearance(val theme: ReverbTheme) {
    private val typography = ReverbDesign.appearance.typography
    val panel = ReverbDesign.appearance.modals.floating.copy(surfaceElevationDp = 0f)
    val palette get() = theme.palette
    val headlineSmall get() = typography[panel.headlineRole]
    val titleSmall get() = typography[panel.titleRole]
    val bodyMedium get() = typography[panel.summaryRole]
    val bodySmall get() = typography[panel.detailRole]
    val density get() = theme.density
    val fontResolver get() = theme.fontResolver
    val panelPalette = palette.copy(surfaceContainerHigh = ReverbDesign.modalSurface(palette))
}

/**
 * Concrete released About content only. The native dialog host must keep Gravity.TOP,
 * full-width/non-fitting decor, source entry/exit, and predictive Back ownership.
 *
 * [predictiveMotionTarget] excludes navigation-bar padding, matching the source graphicsLayer.
 * Entry/exit applies to this whole View, including navigation padding. requestDismiss is the
 * owner's idempotent exit request; this content never dismisses the Window prematurely.
 */
internal class ReverbAboutContentView private constructor(
    context: Context,
    initialAppearance: AboutContentAppearance,
    initialDetails: ReverbAboutDetails,
    private val requestDismiss: () -> Unit,
    openRepository: () -> Boolean,
    runtime: NativeModalWindowRuntime,
    initialScrollPosition: Int,
    private val input: AboutInputGeometry,
) : PhasePointerRoot(context, input::rootToScreen, input::coordinates, input.rootHit, input::prepare) {
    constructor(context: Context, theme: ReverbTheme, details: ReverbAboutDetails,
                requestDismiss: () -> Unit, openRepository: () -> Boolean,
                runtime: NativeModalWindowRuntime, initialScrollPosition: Int) :
        this(context, AboutContentAppearance(theme), details, requestDismiss, openRepository,
            runtime, initialScrollPosition, AboutInputGeometry(theme.density.density))

    private var appearance = initialAppearance
    private var details = initialDetails
    private var navigation = Insets.NONE
    private var status = Insets.NONE
    private var linkOpenFailed = false
    private var inputRoot: View? = null
    private val dragLayer = FrameLayout(context)
    private val surface = AboutSurface(context, initialAppearance, runtime, initialScrollPosition)
    private val handle = NativeSheetDragHandle(context, reverbActionSheetStyle(initialAppearance.theme),
        { inputRoot }, runtime, requestDismiss, requestDismiss)
    private val content: AboutColumn = AboutColumn(context, initialAppearance, initialDetails, handle) {
        linkOpenFailed = !openRepository()
        setLinkError()
    }
    val predictiveMotionTarget: View get() = surface
    private var accessibilityOwner: AboutContentAccessibilityOwner? = null

    fun scrollPosition(): Int = surface.viewport.saveScrollPosition()

    fun bindWindowAccessibility(windowRoot: View) {
        check(accessibilityOwner == null) { "About content already has its native Window cursor" }
        inputRoot = windowRoot
        ViewCompat.setAccessibilityPaneTitle(windowRoot, details.paneLabel)
        content.repository.row.nativeInputRoot = windowRoot
        accessibilityOwner = AboutContentAccessibilityOwner(windowRoot, this, surface,
            surface.clipHost, surface.viewport, content, handle)
    }

    fun dispose() {
        input.close()
        dragLayer.animate().cancel()
        handle.close()
        inputRoot = null
        accessibilityOwner?.close()
        accessibilityOwner = null
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        surface.setContent(content)
        dragLayer.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        dragLayer.addView(surface, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(dragLayer)
        input.bind(this, surface, content, handle)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val nextNavigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val rawStatus = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nextStatus = Insets.of(
                max(0, rawStatus.left - nextNavigation.left),
                max(0, rawStatus.top - nextNavigation.top),
                max(0, rawStatus.right - nextNavigation.right),
                max(0, rawStatus.bottom - nextNavigation.bottom),
            )
            if (navigation != nextNavigation || status != nextStatus) {
                navigation = nextNavigation
                status = nextStatus
                surface.setStatusInsets(status)
                requestLayout()
            }
            insets
        }
    }

    fun bind(theme: ReverbTheme, details: ReverbAboutDetails) {
        val value = AboutContentAppearance(theme)
        this.details = details
        inputRoot?.let { ViewCompat.setAccessibilityPaneTitle(it, details.paneLabel) }
        appearance = value
        surface.setAppearance(value)
        handle.updateStyle(reverbActionSheetStyle(theme))
        content.setAppearance(value, details)
        setLinkError()
        requestLayout()
    }

    private fun setLinkError() {
        content.setLinkError(details.repositoryError.takeIf { linkOpenFailed })
    }

    fun beginModalDrag() { dragLayer.animate().cancel() }

    fun dragModalBy(dy: Float) {
        dragLayer.translationY = (dragLayer.translationY + dy).coerceIn(-dragLayer.height.toFloat(), 0f)
    }

    fun releaseModalDrag(velocityY: Float) {
        val density = appearance.density.density
        val distance = -dragLayer.translationY
        if (distance >= minOf(dragLayer.height * .20f, 56f * density) ||
            (distance > 0f && velocityY <= -720f * density)) requestDismiss()
        else cancelModalDrag()
    }

    fun cancelModalDrag() {
        dragLayer.animate().translationY(0f).setDuration(180L).start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val contentWidth = max(0, width - navigation.left - navigation.right)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val availableHeight = if (heightMode == MeasureSpec.UNSPECIFIED) 0
            else max(0, MeasureSpec.getSize(heightMeasureSpec) - navigation.top - navigation.bottom)
        dragLayer.measure(MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(availableHeight,
                if (heightMode == MeasureSpec.UNSPECIFIED) MeasureSpec.UNSPECIFIED else MeasureSpec.AT_MOST))
        setMeasuredDimension(width, resolveSize(dragLayer.measuredHeight + navigation.top + navigation.bottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        dragLayer.layout(navigation.left, navigation.top,
            navigation.left + dragLayer.measuredWidth, navigation.top + dragLayer.measuredHeight)
    }
}

private class AboutSurface(context: Context, appearance: AboutContentAppearance, runtime: NativeModalWindowRuntime, initialScrollPosition: Int) :
    NativeFloatingPanelSurface(context, appearance.density.density, appearance.panel, appearance.panelPalette) {
    val viewport = NativeVerticalViewport(context, NativeVerticalScrollState(initialScrollPosition), appearance.density,
        runtime.frames, runtime.scope, object : NativeVerticalNestedHost {
            // This dialog's scroll container has no scrollable ancestor; window motion is not nested scroll.
            override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
            override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
            override suspend fun preFling(availableY: Float) = 0f
            override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
        })
    init { setPanelViewport(viewport) }
    fun setContent(view: View) {
        viewport.setContent(view)
        NativeModalTextSurface.bindTree(view)
    }
    fun setStatusInsets(value: Insets) = setContentInsets(value.left, value.top, value.right, value.bottom)
    fun setAppearance(value: AboutContentAppearance) {
        bindSurface(value.density.density, value.panel, value.panelPalette)
        requestLayout()
    }
}

private class AboutColumn(
    context: Context,
    private var appearance: AboutContentAppearance,
    details: ReverbAboutDetails,
    private val handle: NativeSheetDragHandle,
    openRepository: () -> Unit,
) : ViewGroup(context) {
    val header = AboutBrandHeader(context, appearance, details)
    val name = aboutParagraph(context, appearance, appearance.headlineSmall, appearance.palette.ink)
    val version = aboutParagraph(context, appearance, appearance.bodyMedium, appearance.palette.muted)
    val repository = AboutRepositoryCard(context, appearance, details, openRepository)
    val error = AboutErrorCard(context, appearance).apply { visibility = GONE }
    private var contentHeight = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(header); addView(name); addView(version); addView(repository); addView(error); addView(handle)
        updateLabels(details)
    }

    private fun updateLabels(details: ReverbAboutDetails) {
        name.setText(details.name)
        version.setText(details.version)
    }

    fun setAppearance(value: AboutContentAppearance, details: ReverbAboutDetails) {
        appearance = value
        header.bind(value, details)
        name.setTypography(value.headlineSmall, value.density, value.fontResolver)
        name.animateTextColor(value.palette.ink)
        version.setTypography(value.bodyMedium, value.density, value.fontResolver)
        version.animateTextColor(value.palette.muted)
        repository.setAppearance(value, details)
        error.setAppearance(value)
        updateLabels(details)
        requestLayout()
    }

    fun setLinkError(message: String?) {
        error.visibility = if (message == null) GONE else VISIBLE
        message?.let(error::setText)
        requestLayout()
    }

    private fun dp(value: Float) = (value * appearance.density.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val panel = appearance.panel
        val horizontalPadding = dp(panel.horizontalPaddingDp)
        val childWidth = max(0, width - 2 * horizontalPadding)
        val exactWidth = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY)
        val wrapWidth = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.AT_MOST)
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        header.measure(exactWidth, unbounded)
        name.measure(wrapWidth, unbounded)
        version.measure(wrapWidth, unbounded)
        repository.measure(exactWidth, unbounded)
        handle.measure(wrapWidth, unbounded)
        contentHeight = dp(panel.topPaddingDp) + header.measuredHeight + dp(panel.headingGapDp) + name.measuredHeight +
            dp(panel.summaryGapDp) + version.measuredHeight + dp(panel.linkGapDp) + repository.measuredHeight +
            handle.measuredHeight + handle.verticalTouchInsetPx
        if (error.visibility != GONE) {
            error.measure(exactWidth, unbounded)
            contentHeight += dp(panel.errorGapDp) + error.measuredHeight
        }
        setMeasuredDimension(width, contentHeight)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val panel = appearance.panel
        var y = dp(panel.topPaddingDp)
        fun place(child: View) {
            val x = ((width - child.measuredWidth) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            y += child.measuredHeight
        }
        place(header); y += dp(panel.headingGapDp)
        place(name); y += dp(panel.summaryGapDp)
        place(version); y += dp(panel.linkGapDp)
        place(repository)
        if (error.visibility != GONE) { y += dp(panel.errorGapDp); place(error) }
        place(handle)
    }
}

private class AboutBrandHeader(context: Context, private var appearance: AboutContentAppearance, details: ReverbAboutDetails) : ViewGroup(context) {
    val mark = ReverbIdentityMarkView(context)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(mark)
        bind(appearance, details)
    }

    fun bind(value: AboutContentAppearance, details: ReverbAboutDetails) {
        appearance = value
        mark.bind(details.identity, value.palette)
        mark.contentDescription = details.name
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val size = (appearance.panel.brandSizeDp * appearance.density.density).roundToInt()
        mark.measure(MeasureSpec.makeMeasureSpec(min(width, size), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, mark.measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = ((width - mark.measuredWidth) / 2f).roundToInt()
        mark.layout(x, 0, x + mark.measuredWidth, mark.measuredHeight)
    }
}

private class AboutRepositoryCard(context: Context, appearance: AboutContentAppearance, details: ReverbAboutDetails, onClick: () -> Unit) : ViewGroup(context) {
    private val group = NativeGroupLayout(context, appearance.panel.link.radiusDp, onPaintedSurfaceChanged = NativeModalTextSurface::invalidate)
    val row = AboutRepositoryRow(context, appearance, details, onClick)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        group.addRow(row, NativeGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(group); setAppearance(appearance, details)
    }
    fun setAppearance(value: AboutContentAppearance, details: ReverbAboutDetails) {
        group.setCornerRadiusDp(value.panel.link.radiusDp)
        group.setSurfacePreset(value.palette.surfacePreset)
        group.setPalette(ReverbDesign.modalLinkSurface(value.palette), 0)
        row.setAppearance(value, details)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        group.measure(widthMeasureSpec, heightMeasureSpec); setMeasuredDimension(group.measuredWidth, group.measuredHeight)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) { group.layout(0, 0, width, height) }
}

private class AboutRepositoryRow(context: Context, private var appearance: AboutContentAppearance,
                                 details: ReverbAboutDetails, onClick: () -> Unit) : ViewGroup(context) {
    var nativeInputRoot: View? = null
    private val key = Any()
    private val sourceClick = onClick
    fun invokeSourceClick(): Boolean { sourceClick(); return true }
    private val icon = NativeDecorativeIconView(context,
        ReverbIconDrawable(ReverbIcon.GitHub, resources.displayMetrics.density, appearance.palette.ink), appearance.palette.ink)
    val title = aboutParagraph(context, appearance, appearance.titleSmall,
        appearance.panel.link.titleColor(appearance.palette, appearance.titleSmall))
    val label = aboutParagraph(context, appearance, appearance.bodySmall, appearance.palette.muted)
    private val indication = RowIndication.install(this,
        appearance.panel.link.content(appearance.palette), { readCachedAnimatorDurationScale(context) })
    private val controller = ViewClickController(this, object : NativeClickEnvironment {
        override val nativeInputRoot: View? get() = this@AboutRepositoryRow.nativeInputRoot
        override val minimumTouchWidthPx get() = 48f * appearance.density.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent) = roundedGroupGeometry(width.toFloat(), height.toFloat(),
            appearance.density.density, appearance.panel.link.radiusDp)
            .acceptsPointer(event.x, event.y, event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, minimumTouchWidthPx)
        override fun interestedParent(event: MotionEvent) = false // Shared touch phases supply actual vertical interest.
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, RowIndicationSink(indication) { x, y, output -> output.set(x, y) })
    val clickEndpoint = ViewClickPhaseEndpoint(controller)
    init {
        isClickable = true; isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this, true)
        addView(icon); addView(title); addView(label)
        title.setText(details.repositoryTitle); label.setText(details.repositoryLabel)
        setOnClickListener { onClick() }
    }
    fun setAppearance(value: AboutContentAppearance, details: ReverbAboutDetails) {
        appearance = value
        icon.setColor(value.palette.ink)
        title.setTypography(value.titleSmall, value.density, value.fontResolver)
        title.animateTextColor(value.panel.link.titleColor(value.palette, value.titleSmall))
        label.setTypography(value.bodySmall, value.density, value.fontResolver); label.animateTextColor(value.palette.muted)
        title.setText(details.repositoryTitle)
        label.setText(details.repositoryLabel)
        indication.updateColor(value.panel.link.content(value.palette)); requestLayout()
    }
    private fun dp(value: Float) = (value * appearance.density.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val link = appearance.panel.link
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val inner = max(0, w - 2 * dp(link.horizontalPaddingDp))
        val glyphSize = dp(link.glyphSizeDp)
        icon.measure(MeasureSpec.makeMeasureSpec(min(glyphSize, inner), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(glyphSize, MeasureSpec.EXACTLY))
        val textWidth = max(0, inner - icon.measuredWidth - dp(link.textGapDp))
        val textSpec = MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.AT_MOST)
        title.measure(textSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        label.measure(textSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val contentHeight = max(icon.measuredHeight, title.measuredHeight + label.measuredHeight)
        setMeasuredDimension(w, max(dp(link.minimumHeightDp), contentHeight + 2 * dp(link.verticalPaddingDp)))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val horizontalPadding = dp(appearance.panel.link.horizontalPaddingDp)
        val iconX = if (rtl) width - horizontalPadding - icon.measuredWidth else horizontalPadding
        val iconY = ((height - icon.measuredHeight) / 2f).roundToInt()
        icon.layout(iconX, iconY, iconX + icon.measuredWidth, iconY + icon.measuredHeight)
        val textGap = dp(appearance.panel.link.textGapDp)
        val textStart = if (rtl) icon.left - textGap else icon.right + textGap
        val titleX = if (rtl) textStart - title.measuredWidth else textStart
        val labelX = if (rtl) textStart - label.measuredWidth else textStart
        val textY = ((height - title.measuredHeight - label.measuredHeight) / 2f).roundToInt()
        title.layout(titleX, textY, titleX + title.measuredWidth, textY + title.measuredHeight)
        label.layout(labelX, title.bottom, labelX + label.measuredWidth, title.bottom + label.measuredHeight)
        indication.onMeasured(width, height, appearance.density.density)
    }
    fun containsVisualPoint(x: Float, y: Float): Boolean =
        roundedGroupContains(width.toFloat(), height.toFloat(), appearance.density.density, appearance.panel.link.radiusDp, x, y)

    override fun getAccessibilityClassName(): CharSequence = "android.view.View"

    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); controller.attached(); controller.bind(NativeClickBinding(key, true, { performClick() }))
    }
    override fun onDetachedFromWindow() { controller.detached(); super.onDetachedFromWindow() }
    override fun onTouchEvent(event: MotionEvent) = controller.touch(event)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> controller.key(event)
        else -> super.dispatchKeyEvent(event)
    }
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val handled = controller.hover(event)
        return super.onHoverEvent(event) || handled
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect); controller.focusChanged(gainFocus)
    }
}

private class AboutErrorCard(context: Context, private var appearance: AboutContentAppearance) : ViewGroup(context) {
    private val group = NativeGroupLayout(context, appearance.panel.error.radiusDp, onPaintedSurfaceChanged = NativeModalTextSurface::invalidate)
    private val text = aboutParagraph(context, appearance, appearance.bodyMedium, appearance.palette.onErrorContainer).apply {
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val body = object : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val error = appearance.panel.error
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val contentWidth = max(0, w - dp(error.startPaddingDp) - dp(error.endPaddingDp))
            text.measure(MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            setMeasuredDimension(w, text.measuredHeight + 2 * dp(error.verticalPaddingDp))
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val error = appearance.panel.error
            val x = dp(if (layoutDirection == LAYOUT_DIRECTION_RTL) error.endPaddingDp else error.startPaddingDp)
            val y = dp(error.verticalPaddingDp)
            text.layout(x, y, x + text.measuredWidth, y + text.measuredHeight)
        }
    }
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        body.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        body.addView(text)
        group.addRow(body, NativeGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(group); setAppearance(appearance)
    }
    private fun dp(value: Float) = (value * appearance.density.density).roundToInt()
    fun setText(value: String) = text.setText(value)
    fun setAppearance(value: AboutContentAppearance) {
        appearance = value
        group.setCornerRadiusDp(value.panel.error.radiusDp)
        group.setSurfacePreset(value.palette.surfacePreset)
        group.setPalette(value.panel.error.fill(value.palette), 0)
        text.setTypography(value.bodyMedium, value.density, value.fontResolver)
        text.animateTextColor(value.palette.onErrorContainer); requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        group.measure(widthMeasureSpec, heightMeasureSpec); setMeasuredDimension(group.measuredWidth, group.measuredHeight)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) { group.layout(0, 0, width, height) }
}

private fun aboutParagraph(context: Context, appearance: AboutContentAppearance, style: TextStyle, color: Int) =
    ParagraphView(context, style, appearance.density, appearance.fontResolver, color)

/** A direct geometry owner for existing Views, not an independently measured/rendered layout tree. */
private class AboutInputGeometry(private val density: Float) : AutoCloseable {
    private lateinit var root: ReverbAboutContentView
    private val coordinates = NativeWindowPointerCoordinates { root }
    private var binding: NativeVerticalPhaseBinding? = null
    private var surfaces: List<NativeViewPointerHitLayout> = emptyList()
    val rootHit = object : NativeViewPointerHitLayout {
        override val placed get() = ::root.isInitialized && root.isAttachedToWindow && root.isLaidOut
        override val size get() = Size(root.width.toFloat(), root.height.toFloat())
        override val minimumTouchTarget = Size.Zero
        override val nodes = emptyList<NativeViewPhasePointerNode>()
        override val childrenInHitOrder get() = surfaces
        override fun screenToLocal(screen: Offset) = coordinates.coordinates(root).local(screen)
        override fun withinLayerBounds(local: Offset) = true
    }

    private var modal: NativeModalDragNode? = null

    fun bind(root: ReverbAboutContentView, surface: AboutSurface, content: AboutColumn, handle: NativeSheetDragHandle) {
        check(!::root.isInitialized)
        this.root = root
        val viewConfiguration = ViewConfiguration.get(root.context)
        val viewport = surface.viewport
        val panelDrag = NativeModalDragNode(surface, { root.isShown }, { false }, object : NativeDragSink {
            override fun start(position: Offset) = root.beginModalDrag()
            override fun delta(physicalDelta: Offset) = root.dragModalBy(physicalDelta.y)
            override fun stop(physicalVelocity: Velocity) = root.releaseModalDrag(physicalVelocity.y)
            override fun cancel() = root.cancelModalDrag()
        }, canStartDrag = { it.y < 0f })
        modal = panelDrag
        val vertical = NativeVerticalPhaseBinding(viewport,
            { viewConfiguration.scaledTouchSlop.toFloat() },
            { viewConfiguration.scaledMaximumFlingVelocity.toFloat() }, panelDrag::interested).apply {
            canStartDrag = { delta -> viewport.canScrollVertically(if (delta.y < 0f) 1 else -1) }
        }
        binding = vertical
        val repository = content.repository.row
        val repositoryNode = NativeClickPhaseNode(repository, repository.clickEndpoint) {
            vertical.interested(it) || panelDrag.interested(it)
        }
        val repositoryHit = Hit(repository, listOf(repositoryNode), emptyList()) { p ->
            repository.containsVisualPoint(p.x, p.y)
        }
        val handleHit = Hit(handle, handle.pointerNodes { vertical.interested(it) || panelDrag.interested(it) },
            emptyList()) { p -> p.x in 0f..handle.width.toFloat() && p.y in 0f..handle.height.toFloat() }
        val inflation = (30f * density).roundToInt()
        val viewportHit = Hit(viewport, vertical.nodes, listOf(handleHit, repositoryHit)) { p ->
            p.x >= -inflation && p.x < viewport.width + inflation && p.y >= 0f && p.y < viewport.height
        }
        surfaces = listOf(Hit(surface, listOf(panelDrag), listOf(viewportHit), surface::containsShape))
    }

    private inner class Hit(
        private val view: View,
        override val nodes: List<NativeViewPhasePointerNode>,
        override val childrenInHitOrder: List<NativeViewPointerHitLayout>,
        private val clip: (Offset) -> Boolean,
    ) : NativeViewPointerHitLayout {
        override val placed get() = view.isAttachedToWindow && view.isLaidOut && view.visibility == View.VISIBLE
        override val size get() = Size(view.width.toFloat(), view.height.toFloat())
        override val minimumTouchTarget get() = Size(48f * density, 48f * density)
        override fun screenToLocal(screen: Offset) = coordinates.coordinates(view).local(screen)
        override fun withinLayerBounds(local: Offset) = clip(local)
    }

    fun prepare(event: MotionEvent) = coordinates.begin(event)
    fun rootToScreen(point: Offset) = coordinates.rootToScreen(point)
    fun coordinates(view: View): NativePointerCoordinates = coordinates.coordinates(view)
    override fun close() { binding?.close(); binding = null; modal?.cancel(); modal = null; surfaces = emptyList() }
}

/** One native About-window cursor with direct existing-label leases. */
private class AboutContentAccessibilityOwner(
    windowRoot: View,
    private val root: ReverbAboutContentView,
    surface: View,
    clipHost: View,
    viewport: View,
    content: AboutColumn,
    handle: NativeSheetDragHandle,
) : AutoCloseable {
    private val window = PlainTextAccessibilityWindowScope(windowRoot)
    private val leases = ArrayList<AutoCloseable>()
    private val geometry = NativePlainTextViewGeometry(windowRoot, NativeTextSemanticsClipPolicy { view ->
        when {
            view === clipHost || view === viewport ||
                (view.parent is NativeGroupLayout && (view.parent as NativeGroupLayout).getChildAt(0) === view) ->
                NativeTextSemanticsClip.Rectangle(ComposeRect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
            else -> NativeTextSemanticsClip.Unclipped
        }
    })

    init {
        fun exposed(view: View): Boolean {
            var current: View? = view
            while (current != null) {
                if (current.visibility != View.VISIBLE) return false
                if (current === windowRoot) return view.isAttachedToWindow
                current = current.parent as? View
            }
            return false
        }
        fun visit(view: View) {
            if (view is ParagraphView) {
                view.isScreenReaderFocusable = view !== content.repository.row.title && view !== content.repository.row.label
                leases += view.bindPlainTextAccessibility(window, geometry) { exposed(view) }
                leases += bindAboutVisibility(view, { exposed(view) },
                    view !== content.repository.row.title && view !== content.repository.row.label)
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(content)
        leases += bindAboutRepository(content.repository.row, geometry) { exposed(content.repository.row) }
        leases += bindAboutDescription(content.header.mark, window) { exposed(content.header.mark) }
        handle.bindSourceGeometry { view ->
            val bounds = geometry.capture(view, Offset.Zero,
                ComposeRect(0f, 0f, view.width.toFloat(), view.height.toFloat()))?.let {
                nativeMergedRoleBoundsInScreen(it, view.isLaidOut)
            } ?: Rect()
            val present = exposed(view)
            val visible = present && !aboutHasTransparentAncestor(view)
            NativeSourceAccessibilityNode(present, !visible, visible && !bounds.isEmpty, bounds)
        }
    }

    override fun close() {
        leases.forEach(AutoCloseable::close)
        leases.clear()
    }
}

/** Retains native click/focus actions while adding the shared source description cursor actions. */
private fun bindAboutDescription(
    host: View,
    window: PlainTextAccessibilityWindowScope,
    speakingLeaf: Boolean = true,
    exposed: () -> Boolean,
): AutoCloseable {
    val description = PlainTextAccessibility(host, window,
        { PlainTextAccessibilitySnapshot(host.contentDescription?.toString().orEmpty(), { null }, { null }) },
        exposed, descriptionOnly = true)
    val delegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(view: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(view, info)
            val clickable = info.isClickable; val focusable = info.isFocusable; val focused = info.isFocused
            val actions = info.actionList.toList()
            description.populate(info)
            val visible = exposed() && !aboutHasTransparentAncestor(host)
            info.isVisibleToUser = info.isVisibleToUser && visible
            info.isImportantForAccessibility = visible
            info.isScreenReaderFocusable = speakingLeaf && visible
            info.isClickable = clickable; info.isFocusable = focusable; info.isFocused = focused
            for (action in actions) {
                if (action.id == AccessibilityNodeInfo.ACTION_CLICK ||
                    action.id == AccessibilityNodeInfo.ACTION_FOCUS || action.id == AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                    info.addAction(action)
            }
        }
        override fun performAccessibilityAction(view: View, action: Int, args: Bundle?): Boolean =
            description.performAction(action, args) ?: super.performAccessibilityAction(view, action, args)
    }
    host.accessibilityDelegate = delegate
    return AutoCloseable { host.accessibilityDelegate = null } // Exclusive private About-View ownership; API28-safe.
}


/** Material3 Surface(onClick) has no explicit Role and retains its two unmerged text children. */
private fun bindAboutRepository(
    host: AboutRepositoryRow,
    geometry: NativePlainTextViewGeometry,
    exposed: () -> Boolean,
): AutoCloseable {
    val manager = host.context.getSystemService(AccessibilityManager::class.java)
    val delegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(view: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(view, info)
            info.className = "android.view.View"
            info.text = null; info.contentDescription = null
            info.isClickable = true; info.isLongClickable = false
            info.isFocusable = true; info.isFocused = host.isFocused
            info.isEnabled = true; info.isSelected = false
            info.isCheckable = false; info.isChecked = false
            val visible = exposed() && !aboutHasTransparentAncestor(host)
            info.isScreenReaderFocusable = visible
            info.isVisibleToUser = info.isVisibleToUser && visible
            info.isImportantForAccessibility = visible
            info.maxTextLength = -1; info.movementGranularities = 0
            geometry.capture(host, Offset.Zero,
                ComposeRect(0f, 0f, host.width.toFloat(), host.height.toFloat()))?.let {
                info.setBoundsInScreen(nativeMergedRoleBoundsInScreen(it, host.isLaidOut))
            }
            val actions = info.actionList
            for (index in actions.size - 1 downTo 0) info.removeAction(actions[index])
            info.addAction(if (host.isAccessibilityFocused)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
            info.addAction(if (host.isFocused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_FOCUS
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            info.removeChild(host.title); info.removeChild(host.label)
            info.addChild(host.title); info.addChild(host.label)
        }
        override fun performAccessibilityAction(view: View, action: Int, args: Bundle?): Boolean {
            if (!exposed()) return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    val result = host.invokeSourceClick()
                    if (manager?.isEnabled == true && host.isAttachedToWindow) {
                        @Suppress("DEPRECATION")
                        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
                            className = "android.view.View"; isEnabled = true
                            packageName = host.context.packageName; setSource(host)
                        }
                        host.parent?.requestSendAccessibilityEvent(host, event)
                    }
                    result
                }
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
                AccessibilityNodeInfo.ACTION_FOCUS, AccessibilityNodeInfo.ACTION_CLEAR_FOCUS ->
                    super.performAccessibilityAction(view, action, args)
                else -> false
            }
        }
    }
    host.accessibilityDelegate = delegate
    return AutoCloseable { host.accessibilityDelegate = null } // Exclusive private About-View ownership; API28-safe.
}

private fun aboutHasTransparentAncestor(view: View): Boolean {
    var current: View? = view
    while (current != null) {
        if (current.alpha == 0f) return true
        current = current.parent as? View
    }
    return false
}

/** Transparency changes node visibility/importance, not its retained text/cursor semantics. */
private fun bindAboutVisibility(host: View, present: () -> Boolean, speakingLeaf: Boolean): AutoCloseable {
    host.accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(view: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(view, info)
            val visible = present() && !aboutHasTransparentAncestor(host)
            info.isVisibleToUser = info.isVisibleToUser && visible
            info.isImportantForAccessibility = visible
            info.isScreenReaderFocusable = speakingLeaf && visible
        }
        override fun onInitializeAccessibilityEvent(view: View, event: AccessibilityEvent) {
            super.onInitializeAccessibilityEvent(view, event)
            event.className = "android.view.View"; event.isEnabled = true
            event.text.clear(); event.contentDescription = null
        }
    }
    return AutoCloseable { host.accessibilityDelegate = null }
}
