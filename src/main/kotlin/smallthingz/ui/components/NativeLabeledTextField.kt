package smallthingz.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import smallthingz.ui.animation.SpringValue
import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.SpringSpec
import smallthingz.ui.animation.view.ViewSpringDriver
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.text.Paragraph
import smallthingz.ui.text.PlatformTextStyle
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.style.LineHeightStyle
import smallthingz.ui.text.style.TextOverflow
import smallthingz.ui.unit.Constraints
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.LayoutDirection
import smallthingz.ui.unit.TextUnit
import smallthingz.ui.unit.em
import smallthingz.ui.unit.sp
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import smallthingz.ui.graphics.Color as TextColor

data class NativeLabeledTextFieldStyle(
    val errorColor: Int,
    val editorStyle: NativeTextFieldStyle = NativeTextFieldStyle(
        radiusDp = 12f, minHeightDp = 56f, verticalPaddingDp = 16f,
        textStyle = NativeTextStyle(16f, 24f, letterSpacingSp = .5f),
    ),
    val floatingLabelStyle: NativeTextStyle = NativeTextStyle(12f, 16f, letterSpacingSp = .4f),
    val supportingStyle: NativeTextStyle = NativeTextStyle(12f, 16f, letterSpacingSp = .4f),
    val containerColor: Int = Color.TRANSPARENT,
    val outlineColor: Int? = null,
    val focusedOutlineColor: Int? = null,
    val labelColor: Int? = null,
    val supportingColor: Int? = null,
    val outlineWidthDp: Float = 1f,
    val focusedOutlineWidthDp: Float = 2f,
    val cutoutPaddingDp: Float = 4f,
    val supportingTopPaddingDp: Float = 4f,
    val supportingHorizontalPaddingDp: Float = 16f,
    val disabledContentAlpha: Float = .38f,
    val disabledOutlineAlpha: Float = .12f,
    val minimumWidthDp: Float = 280f,
    val minimumEditorWidthDp: Float = 96f,
    val trailingRowGapDp: Float = 4f,
    val animationsEnabled: Boolean = true,
    val labelMaxLines: Int = Int.MAX_VALUE,
    val expandedLabelStyle: NativeTextStyle? = null,
) {
    init {
        require(floatArrayOf(outlineWidthDp, focusedOutlineWidthDp, cutoutPaddingDp,
            supportingTopPaddingDp, supportingHorizontalPaddingDp, minimumWidthDp,
            minimumEditorWidthDp, trailingRowGapDp).all { it.isFinite() && it >= 0f })
        require(disabledContentAlpha.isFinite() && disabledContentAlpha in 0f..1f)
        require(disabledOutlineAlpha.isFinite() && disabledOutlineAlpha in 0f..1f)
        require(labelMaxLines >= 1)
    }
}

/**
 * Retains the supplied editor, input connection, text, hints, IDs and application listeners.
 * Callers own validation, input type, IME actions and trailing action callbacks.
 * Bind palette/style to this component rather than independently repainting its managed editor.
 * Allow wrap-content height for supporting text and a narrow trailing action row.
 */
open class NativeLabeledTextField(
    context: Context,
    palette: ControlPalette,
    label: CharSequence,
    style: NativeLabeledTextFieldStyle,
    val editor: NativeTextField = NativeTextField(context, palette, style.editorStyle.copy(outlined = false)),
) : ViewGroup(context) {
    private var componentPalette = palette
    private var componentStyle = style
    private var fieldLabel = label.toString()
    private var placeholderText: String? = editor.hint?.toString()?.takeIf { it.isNotEmpty() && it != fieldLabel }
    private var supportingText: String? = null
    private var validationError: String? = null
    private var density = Density(context)
    private var fontResolver = createFontFamilyResolver(context)
    private val surface = NativeSurface(context, surfaceStyle())
    private val placeholderView = NativeText(context, palette, placeholderStyle(), NativeTextRole.MUTED)
    val supportingView = NativeText(context, palette, style.supportingStyle, NativeTextRole.MUTED)
    val leadingHost = FrameLayout(context)
    val trailingHost = FrameLayout(context)
    private var leadingContent: View? = null
    private var trailingContent: View? = null
    private var leadingRequestedEnabled = true
    private var trailingRequestedEnabled = true
    private val leadingEnabledBeforeDisable = IdentityHashMap<View, Boolean>()
    private val trailingEnabledBeforeDisable = IdentityHashMap<View, Boolean>()
    private var editorRequestedEnabled = editor.isEnabled
    private var ready = false
    private var updatingState = false
    private var watcherAttached = false
    private val disabledState = intArrayOf(-android.R.attr.state_enabled)
    private var lastFocused = editor.hasFocus()
    private var lastEnabled = editor.isEnabled
    private val fieldBounds = Rect()
    private val cutoutBounds = RectF()
    private val border = NativeSurfaceBorderDrawable()
    private val borderCorners = FloatArray(8)
    private var decorationColors: smallthingz.ui.animation.view.ViewColorTransition? = null
    private var fieldHeight = 0
    private var editorBandHeight = 0
    private var topReserve = 0
    private var trailingWraps = false
    private var slotsStacked = false
    private var labelWidth = -1
    private var expandedLabelWidth = -1
    private var expandedParagraph: Paragraph? = null
    private var floatingParagraph: Paragraph? = null
    private var intermediateParagraph: Paragraph? = null
    private var intermediateProgress = Float.NaN
    private val labelFamilies = HashMap<String, FontFamily>()
    private var fontObservation: AutoCloseable? = null
    private val fontRefresh = Runnable {
        if (isAttachedToWindow) { clearLabelLayouts(); requestLayout(); invalidate() }
    }
    private val labelMotion = SpringValue.float(
        if (lastFocused || !editor.text.isNullOrEmpty()) 1f else 0f,
        SpringSpec<Float>(dampingRatio = .9f, stiffness = 1400f, visibilityThreshold = .01f),
    )
    private var motionDriver: ViewSpringDriver<Float, AnimationVector1D>? = null
    private var placeholderMotion = SpringValue.float(0f, SpringSpec<Float>(dampingRatio = 1f, stiffness = 800f, visibilityThreshold = .01f))
    private var placeholderDriver: ViewSpringDriver<Float, AnimationVector1D>? = null
    private var placeholderRequestedVisible = false
    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) { updateVisualState(animate = true) }
    }

    init {
        require(editor.parent == null) { "The retained editor already belongs to another parent" }
        try {
            editor.bindPlaceholderPresentation(this, ::updatePlaceholderTypography)
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            descendantFocusability = FOCUS_AFTER_DESCENDANTS
            surface.isFocusable = false
            surface.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            placeholderView.isFocusable = false
            placeholderView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            placeholderView.visibility = GONE
            placeholderView.text = placeholderText
            supportingView.isFocusable = false
            supportingView.visibility = GONE
            supportingView.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            leadingHost.isFocusable = false
            leadingHost.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            leadingHost.visibility = GONE
            trailingHost.isFocusable = false
            trailingHost.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            trailingHost.visibility = GONE
            addView(surface)
            addView(editor)
            addView(placeholderView)
            addView(leadingHost)
            addView(trailingHost)
            addView(supportingView)
            ready = true
            applyEditorStyle()
            updateSupport()
            applyPaint()
            updateVisualState(animate = false)
        } catch (failure: Throwable) {
            ready = false // Retire callbacks before focus/drawable changes during removal.
            // Failed construction must not strand the caller's retained editor or leases.
            // This is ownership cleanup, not rollback of view-local styling already applied.
            try { editor.releaseFieldAccessibility(this) } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            try { editor.releasePlaceholderPresentation(this) } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            try { if (editor.parent === this) removeView(editor) } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    fun setLabel(label: CharSequence) {
        val next = label.toString()
        if (fieldLabel == next) return
        fieldLabel = next
        clearLabelLayouts()
        requestLayout()
        updateVisualState(animate = false)
        invalidate()
    }

    fun setPlaceholder(text: CharSequence?) {
        val next = text?.toString()?.takeIf { it.isNotEmpty() }
        if (placeholderText == next) return
        placeholderText = next
        placeholderView.text = next
        updateVisualState(animate = false)
    }

    fun setSupportingText(text: CharSequence?) {
        val next = text?.toString()?.takeIf { it.isNotEmpty() }
        if (supportingText == next) return
        supportingText = next
        updateSupport()
    }

    /** Never invokes EditText.setError, its native popup, or a text mutation. */
    fun setError(message: CharSequence?) {
        val next = message?.toString()?.takeIf { it.isNotBlank() }
        if (validationError == next) return
        validationError = next
        updateSupport()
        applyPaint()
        updateAccessibility()
    }

    /** Reflow never replaces or reparents an admitted slot view. */
    fun setLeadingContent(view: View?) {
        if (leadingContent === view) return
        require(view?.parent == null) { "Leading content already belongs to another parent" }
        restoreSlotEnabled(leadingEnabledBeforeDisable)
        leadingHost.removeAllViews()
        leadingContent = view
        if (view != null) leadingHost.addView(view, view.layoutParams?.let { FrameLayout.LayoutParams(it) }
            ?: FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        leadingHost.visibility = if (view == null) GONE else VISIBLE
        syncLeadingEnabled()
        applyEditorPadding()
        requestLayout()
    }

    /** Reflow never replaces or reparents an admitted slot view. */
    fun setTrailingContent(view: View?) {
        if (trailingContent === view) return
        require(view?.parent == null) { "Trailing content already belongs to another parent" }
        restoreSlotEnabled(trailingEnabledBeforeDisable)
        trailingHost.removeAllViews()
        trailingContent = view
        if (view != null) trailingHost.addView(view, view.layoutParams?.let { FrameLayout.LayoutParams(it) }
            ?: FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        trailingHost.visibility = if (view == null) GONE else VISIBLE
        syncTrailingEnabled()
        applyEditorPadding()
        requestLayout()
    }

    /** Use these intent setters for updates while the whole component is disabled. */
    fun setEditorEnabled(enabled: Boolean) {
        editorRequestedEnabled = enabled
        editor.isEnabled = isEnabled && enabled
        updateVisualState(animate = false)
    }

    fun setTrailingContentEnabled(enabled: Boolean) {
        trailingRequestedEnabled = enabled
        syncTrailingEnabled()
    }

    fun setLeadingContentEnabled(enabled: Boolean) {
        leadingRequestedEnabled = enabled
        syncLeadingEnabled()
    }

    fun applyPalette(palette: ControlPalette) {
        if (componentPalette == palette) return
        componentPalette = palette
        applyPaint()
    }

    fun applyStyle(style: NativeLabeledTextFieldStyle) {
        if (componentStyle == style) return
        componentStyle = style
        applyEditorStyle()
        supportingView.applyStyle(style.supportingStyle)
        placeholderView.applyStyle(placeholderStyle())
        clearLabelLayouts()
        retireMotion()
        updateSupport()
        applyPaint()
        updateVisualState(animate = false)
        requestLayout()
    }

    override fun setEnabled(enabled: Boolean) {
        val previous = isEnabled
        super.setEnabled(enabled)
        if (!ready || previous == enabled) return
        if (previous && !enabled) editorRequestedEnabled = editor.isEnabled
        editor.isEnabled = enabled && editorRequestedEnabled
        syncLeadingEnabled()
        syncTrailingEnabled()
        updateVisualState(animate = false)
    }

    override fun childDrawableStateChanged(child: View) {
        super.childDrawableStateChanged(child)
        if (ready && child === editor && !updatingState) updateVisualState(animate = true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        editor.bindPlaceholderPresentation(this, ::updatePlaceholderTypography)
        if (!watcherAttached) { editor.addTextChangedListener(textWatcher); watcherAttached = true }
        updateVisualState(animate = false)
        observeFonts()
    }

    override fun onDetachedFromWindow() {
        retireMotion()
        if (watcherAttached) { editor.removeTextChangedListener(textWatcher); watcherAttached = false }
        fontObservation?.close()
        fontObservation = null
        removeCallbacks(fontRefresh)
        editor.releaseFieldAccessibility(this)
        editor.releasePlaceholderPresentation(this)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (ready && !isShown) retireMotion()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (ready && visibility != VISIBLE) retireMotion()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        density = Density(context)
        fontResolver = createFontFamilyResolver(context)
        editor.componentTypography(componentStyle.editorStyle.textStyle)
        supportingView.componentTypography(componentStyle.supportingStyle)
        placeholderView.componentTypography(placeholderStyle())
        applyEditorPadding()
        clearLabelLayouts()
        requestLayout()
        applyPaint()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        if (ready) { clearLabelLayouts(); requestLayout(); invalidate() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateVisualState(animate = isAttachedToWindow && isShown && windowVisibility == VISIBLE)
        val measuredWidth = resolveSizeAndState(max(suggestedMinimumWidth,
            dp(componentStyle.minimumWidthDp) + paddingLeft + paddingRight), widthMeasureSpec, 0)
        val available = ((measuredWidth and MEASURED_SIZE_MASK) - paddingLeft - paddingRight).coerceAtLeast(0)
        val startPadding = baseStartPadding()
        val endPadding = baseEndPadding()
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        if (leadingHost.visibility != GONE) leadingHost.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), unbounded)
        if (trailingHost.visibility != GONE) trailingHost.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), unbounded)
        val leadingWidth = if (leadingHost.visibility == GONE) 0 else leadingHost.measuredWidth
        val leadingHeight = if (leadingHost.visibility == GONE) 0 else leadingHost.measuredHeight
        val trailingWidth = if (trailingHost.visibility == GONE) 0 else trailingHost.measuredWidth
        val trailingHeight = if (trailingHost.visibility == GONE) 0 else trailingHost.measuredHeight
        val slotWidth = leadingWidth + trailingWidth
        trailingWraps = slotWidth > 0 && available < slotWidth + dp(componentStyle.minimumEditorWidthDp)
        slotsStacked = trailingWraps && leadingWidth > 0 && trailingWidth > 0 && slotWidth > available
        applyEditorPadding()
        val editorWidth = (available - if (trailingWraps) 0 else slotWidth).coerceAtLeast(0)
        ensureLabelLayouts((available - startPadding - endPadding).coerceAtLeast(1),
            (editorWidth - editor.paddingStart - editor.paddingEnd).coerceAtLeast(1))
        topReserve = if (fieldLabel.isEmpty()) 0 else ceil((floatingParagraph?.height ?: 0f) / 2f).toInt()
        applyEditorPadding()
        val minimumEditorHeight = max(dp(componentStyle.editorStyle.minHeightDp),
            logicalLineHeight(componentStyle.editorStyle.textStyle) + editor.paddingTop + editor.paddingBottom)
        if (editor.minimumHeight != minimumEditorHeight) editor.minimumHeight = minimumEditorHeight
        editor.measure(MeasureSpec.makeMeasureSpec(editorWidth, MeasureSpec.EXACTLY), unbounded)
        // The retained editor may refresh its base geometry during measure (for example after
        // textScaleX changes). Reassert the decoration's insets once after that refresh.
        val paddingChanged = applyEditorPadding()
        val minimumChanged = editor.minimumHeight != minimumEditorHeight
        if (minimumChanged) editor.minimumHeight = minimumEditorHeight
        if (paddingChanged || minimumChanged)
            editor.measure(MeasureSpec.makeMeasureSpec(editorWidth, MeasureSpec.EXACTLY), unbounded)
        val placeholderWidth = (editorWidth - editor.compoundPaddingLeft - editor.compoundPaddingRight).coerceAtLeast(0)
        if (placeholderView.maxLines != Int.MAX_VALUE) placeholderView.maxLines = Int.MAX_VALUE
        val placeholderLineHeight = logicalLineHeight(placeholderStyle())
        if (placeholderView.minimumHeight != placeholderLineHeight) placeholderView.minimumHeight = placeholderLineHeight
        placeholderView.measure(MeasureSpec.makeMeasureSpec(placeholderWidth, MeasureSpec.EXACTLY), unbounded)
        val placeholderLinesHeight = placeholderLineHeight * (placeholderView.layout?.lineCount ?: 1)
        if (placeholderView.measuredHeight < placeholderLinesHeight) placeholderView.measure(
            MeasureSpec.makeMeasureSpec(placeholderWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(placeholderLinesHeight, MeasureSpec.EXACTLY))
        val verticalPadding = editor.paddingTop + editor.paddingBottom
        val expandedHeight = if (fieldLabel.isEmpty()) 0 else ceil(expandedParagraph?.height ?: 0f).toInt() + verticalPadding
        val placeholderHeight = if (editor.text.isNullOrEmpty() && !placeholderText.isNullOrEmpty()) placeholderView.measuredHeight + verticalPadding else 0
        val slotHeight = if (slotsStacked) leadingHeight + dp(componentStyle.trailingRowGapDp) + trailingHeight
            else max(leadingHeight, trailingHeight)
        editorBandHeight = maxOf(editor.measuredHeight, if (trailingWraps) 0 else slotHeight, expandedHeight, placeholderHeight)
        fieldHeight = editorBandHeight + if (trailingWraps) dp(componentStyle.trailingRowGapDp) + slotHeight else 0
        surface.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(fieldHeight, MeasureSpec.EXACTLY))
        var supportHeight = 0
        if (supportingView.visibility != GONE) {
            val supportWidth = (available - 2 * dp(componentStyle.supportingHorizontalPaddingDp)).coerceAtLeast(0)
            val supportLineHeight = logicalLineHeight(componentStyle.supportingStyle)
            if (supportingView.minimumHeight != supportLineHeight) supportingView.minimumHeight = supportLineHeight
            supportingView.measure(MeasureSpec.makeMeasureSpec(supportWidth, MeasureSpec.EXACTLY), unbounded)
            val fullLineHeight = logicalLineHeight(componentStyle.supportingStyle) * (supportingView.layout?.lineCount ?: 1)
            if (supportingView.measuredHeight < fullLineHeight) supportingView.measure(
                MeasureSpec.makeMeasureSpec(supportWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(fullLineHeight, MeasureSpec.EXACTLY))
            supportHeight = dp(componentStyle.supportingTopPaddingDp) + supportingView.measuredHeight
        }
        val desiredHeight = paddingTop + topReserve + fieldHeight + supportHeight + paddingBottom
        enforceManagedTextPaint()
        var childState = editor.measuredState
        if (supportingView.visibility != GONE) childState = combineMeasuredStates(childState, supportingView.measuredState)
        if (leadingHost.visibility != GONE) childState = combineMeasuredStates(childState, leadingHost.measuredState)
        if (trailingHost.visibility != GONE) childState = combineMeasuredStates(childState, trailingHost.measuredState)
        setMeasuredDimension(measuredWidth or (childState and MEASURED_STATE_MASK),
            resolveSizeAndState(max(suggestedMinimumHeight, desiredHeight), heightMeasureSpec, childState shl MEASURED_HEIGHT_STATE_SHIFT))
    }

    private fun usesSingleLinePresentation(): Boolean = editor.singleLinePresentationMode()
        // Preserve previously admitted, unresolved API 28 editors without inventing a mode.
        ?: (editor.maxLines == 1)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val start = paddingLeft
        val topEdge = paddingTop + topReserve
        val end = width - paddingRight
        fieldBounds.set(start, topEdge, end, topEdge + fieldHeight)
        surface.layout(fieldBounds.left, fieldBounds.top, fieldBounds.right, fieldBounds.bottom)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val leadingWidth = if (leadingHost.visibility == GONE) 0 else leadingHost.measuredWidth
        val trailingWidth = if (trailingHost.visibility == GONE) 0 else trailingHost.measuredWidth
        val editorLeft = start + if (trailingWraps) 0 else if (rtl) trailingWidth else leadingWidth
        val editorTop = topEdge + if (usesSingleLinePresentation()) (editorBandHeight - editor.measuredHeight) / 2 else 0
        editor.layout(editorLeft, editorTop, editorLeft + editor.measuredWidth, editorTop + editor.measuredHeight)
        val slotTop = topEdge + editorBandHeight + dp(componentStyle.trailingRowGapDp)
        if (leadingHost.visibility != GONE) {
            val leadingLeft = if (rtl) end - leadingWidth else start
            val leadingTop = if (trailingWraps) slotTop else topEdge + (editorBandHeight - leadingHost.measuredHeight) / 2
            leadingHost.layout(leadingLeft, leadingTop, leadingLeft + leadingWidth, leadingTop + leadingHost.measuredHeight)
        }
        if (trailingHost.visibility != GONE) {
            val trailingLeft = if (rtl) start else end - trailingHost.measuredWidth
            val trailingTop = if (trailingWraps) slotTop + if (slotsStacked) leadingHost.measuredHeight + dp(componentStyle.trailingRowGapDp) else 0
                else topEdge + (editorBandHeight - trailingHost.measuredHeight) / 2
            trailingHost.layout(trailingLeft, trailingTop, trailingLeft + trailingHost.measuredWidth, trailingTop + trailingHost.measuredHeight)
        }
        val placeholderLeft = editor.left + editor.compoundPaddingLeft
        val placeholderTop = if ((placeholderView.layout?.lineCount ?: 1) == 1)
            (editor.top + editor.baseline - placeholderView.baseline).coerceIn(topEdge, max(topEdge, topEdge + editorBandHeight - placeholderView.measuredHeight))
        else if (usesSingleLinePresentation()) topEdge + (editorBandHeight - placeholderView.measuredHeight) / 2
        else topEdge + editor.paddingTop
        placeholderView.layout(placeholderLeft, placeholderTop, placeholderLeft + placeholderView.measuredWidth, placeholderTop + placeholderView.measuredHeight)
        if (supportingView.visibility != GONE) {
            val supportLeft = start + dp(componentStyle.supportingHorizontalPaddingDp)
            val supportTop = fieldBounds.bottom + dp(componentStyle.supportingTopPaddingDp)
            supportingView.layout(supportLeft, supportTop, supportLeft + supportingView.measuredWidth, supportTop + supportingView.measuredHeight)
        }
        border.bounds = fieldBounds
        touchDelegate = TouchDelegate(Rect(start, paddingTop, end, fieldBounds.bottom), editor)
    }

    override fun dispatchDraw(canvas: Canvas) {
        writeDecorationColors()
        super.dispatchDraw(canvas)
        if (fieldBounds.width() <= 0 || fieldBounds.height() <= 0) return
        val progress = labelMotion.value.coerceIn(0f, 1f)
        val paragraph = currentLabelParagraph(progress)
        val startPadding = baseStartPadding()
        val labelLeft = if (layoutDirection == LAYOUT_DIRECTION_RTL && paragraph != null)
            lerp((editor.right - editor.paddingStart).toFloat(), (fieldBounds.right - startPadding).toFloat(), progress) - paragraph.width
        else lerp((editor.left + editor.paddingStart).toFloat(), (fieldBounds.left + startPadding).toFloat(), progress)
        val labelHeight = paragraph?.let { max(it.height, lerp(dp(24f).toFloat(), dp(16f).toFloat(), progress)) } ?: 0f
        val labelTop = if (paragraph != null) {
            val expandedTop = fieldBounds.top + if (usesSingleLinePresentation()) (editorBandHeight - labelHeight) / 2f else editor.paddingTop.toFloat()
            lerp(expandedTop, fieldBounds.top - labelHeight / 2f, progress)
        } else 0f
        val checkpoint = canvas.save()
        try {
            if (paragraph != null && fieldLabel.isNotEmpty() && progress > 0f) {
                var lineLeft = paragraph.getLineLeft(0)
                var lineRight = paragraph.getLineRight(0)
                for (line in 1 until paragraph.lineCount) {
                    lineLeft = minOf(lineLeft, paragraph.getLineLeft(line))
                    lineRight = max(lineRight, paragraph.getLineRight(line))
                }
                val center = labelLeft + (lineLeft + lineRight) / 2f
                val halfWidth = (lineRight - lineLeft) * progress / 2f + dp(componentStyle.cutoutPaddingDp)
                val halfHeight = labelHeight * progress / 2f
                cutoutBounds.set(max(fieldBounds.left.toFloat(), center - halfWidth), fieldBounds.top - halfHeight,
                    minOf(fieldBounds.right.toFloat(), center + halfWidth), fieldBounds.top + halfHeight)
                canvas.clipOutRect(cutoutBounds)
            }
            border.draw(canvas)
        } finally { canvas.restoreToCount(checkpoint) }
        if (paragraph != null && fieldLabel.isNotEmpty()) {
            val saved = canvas.save()
            try {
                canvas.translate(labelLeft, labelTop + (labelHeight - paragraph.height) / 2f)
                paragraph.paint(canvas, TextColor(labelColor()))
            } finally { canvas.restoreToCount(saved) }
        }
    }

    private fun updateVisualState(animate: Boolean) {
        if (!ready || updatingState) return
        updatingState = true
        try {
            val focused = editor.hasFocus()
            val enabled = isEnabled && editor.isEnabled
            val empty = editor.text.isNullOrEmpty()
            val paintChanged = focused != lastFocused || enabled != lastEnabled
            lastFocused = focused
            lastEnabled = enabled
            val target = if (focused || !empty) 1f else 0f
            if (labelMotion.targetValue != target) {
                if (animate && componentStyle.animationsEnabled && isAttachedToWindow && isShown && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()) {
                    val driver = motionDriver ?: ViewSpringDriver(this, labelMotion, publish = { invalidate() }).also { motionDriver = it }
                    driver.retarget(target)
                } else { retireMotion(); labelMotion.snapTo(target); invalidate() }
            }
            val showPlaceholder = empty && !placeholderText.isNullOrEmpty() && (fieldLabel.isEmpty() || target == 1f)
            updatePlaceholder(showPlaceholder, animate && empty && fieldLabel.isNotEmpty())
            if (paintChanged) applyPaint()
            updateAccessibility()
        } finally { updatingState = false }
    }

    private fun updateAccessibility() {
        editor.bindFieldAccessibility(this, NativeFieldAccessibilityState(
            fieldLabel.takeIf { it.isNotEmpty() }, placeholderText, placeholderRequestedVisible, validationError))
    }

    private fun updateSupport() {
        val text = validationError ?: supportingText
        if (!TextUtils.equals(supportingView.text, text)) supportingView.text = text
        supportingView.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
        paintSupportingText()
        requestLayout()
    }

    private fun applyEditorStyle() {
        editor.applyStyle(componentStyle.editorStyle.copy(outlined = false))
        editor.setDefaultFocusHighlightEnabled(false)
        applyEditorPadding()
        editor.setHintTextColor(Color.TRANSPARENT)
    }

    private fun applyEditorPadding(): Boolean {
        val style = componentStyle.editorStyle
        val startPadding = baseStartPadding()
        val endPadding = baseEndPadding()
        val start = if (leadingContent != null && !trailingWraps) minOf(startPadding, dp(4f)) else startPadding
        val end = if (trailingContent != null && !trailingWraps) minOf(endPadding, dp(4f)) else endPadding
        val vertical = dp(style.verticalPaddingDp)
        val top = if (fieldLabel.isEmpty()) vertical else max(vertical, ceil((floatingParagraph?.height ?: 0f) / 2f).toInt())
        if (editor.paddingStart == start && editor.paddingEnd == end && editor.paddingTop == top && editor.paddingBottom == vertical) return false
        editor.setPaddingRelative(start, top, end, vertical)
        return true
    }

    private fun applyPaint() {
        val palette = componentPalette
        val enabled = isEnabled && editor.isEnabled
        editor.applyPalette(if (validationError != null && enabled) palette.copy(accent = componentStyle.errorColor) else palette)
        editor.setHintTextColor(Color.TRANSPARENT)
        placeholderView.applyPalette(palette)
        supportingView.applyPalette(palette)
        surface.applyStyle(surfaceStyle())
        updateDecorationColors()
    }

    private fun updateDecorationColors() {
        val palette=componentPalette
        val style=componentStyle
        val enabled=isEnabled&&editor.isEnabled
        val stroke=when {
            !enabled->withAlpha(palette.content,style.disabledOutlineAlpha)
            validationError!=null->style.errorColor
            editor.hasFocus()->style.focusedOutlineColor ?: palette.accent
            else->style.outlineColor ?: palette.outline
        }
        val support=when {
            !enabled->withAlpha(palette.content,style.disabledContentAlpha)
            validationError!=null->style.errorColor
            else->style.supportingColor ?: palette.muted
        }
        val text=if(enabled)palette.content else withAlpha(palette.content,style.disabledContentAlpha)
        val placeholder=if(enabled)palette.muted else text
        val targets=intArrayOf(stroke,targetLabelColor(),support,text,placeholder)
        val colors=decorationColors ?: smallthingz.ui.animation.view.ViewColorTransition(this,targets,{
            writeDecorationColors();invalidate()
        }).also {decorationColors=it}
        colors.retarget(*targets)
        borderCorners.fill(style.editorStyle.radiusDp * resources.displayMetrics.density)
        border.applyGeometry(colors.color(0),
            dp(if (enabled && editor.hasFocus()) style.focusedOutlineWidthDp else style.outlineWidthDp).toFloat(),
            borderCorners)
        writeDecorationColors()
    }

    private fun writeDecorationColors() {
        val colors=decorationColors ?: return
        border.setColor(colors.color(0))
        supportingView.setTextColor(colors.color(2))
        editor.setTextColor(colors.color(3))
        placeholderView.setTextColor(colors.color(4))
    }

    private fun paintSupportingText()=updateDecorationColors()
    private fun labelColor():Int=decorationColors?.color(1) ?: targetLabelColor()
    private fun targetLabelColor(): Int = when {
        !isEnabled || !editor.isEnabled -> withAlpha(componentPalette.content, componentStyle.disabledContentAlpha)
        validationError != null -> componentStyle.errorColor
        editor.hasFocus() -> componentStyle.focusedOutlineColor ?: componentPalette.accent
        else -> componentStyle.labelColor ?: componentPalette.muted
    }

    private fun surfaceStyle() = NativeSurfaceStyle(componentStyle.containerColor, componentStyle.editorStyle.radiusDp)

    private fun ensureLabelLayouts(available: Int, expandedAvailable: Int) {
        if (labelWidth == available && expandedLabelWidth == expandedAvailable && expandedParagraph != null && floatingParagraph != null) return
        labelWidth = available
        expandedLabelWidth = expandedAvailable
        expandedParagraph = labelParagraph(expandedLabelStyle(), expandedAvailable)
        floatingParagraph = labelParagraph(componentStyle.floatingLabelStyle)
        intermediateParagraph = null
        intermediateProgress = Float.NaN
        observeFonts()
    }

    private fun labelParagraph(style: NativeTextStyle, available: Int = labelWidth): Paragraph = Paragraph(
        text = fieldLabel,
        style = TextStyle(
            color = TextColor.Black, fontSize = style.sizeSp.sp, fontWeight = FontWeight(style.weight),
            fontFamily = labelFamilies.getOrPut(style.fontFamily) { FontFamily.system(style.fontFamily) },
            letterSpacing = style.letterSpacingSp?.sp ?: style.letterSpacing.em,
            lineHeight = style.lineHeightSp?.sp ?: TextUnit.Unspecified,
            platformStyle = PlatformTextStyle(includeFontPadding = style.includeFontPadding ?: false),
            lineHeightStyle = style.lineHeightStyle ?: LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            layoutDirection = if (layoutDirection == LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ),
        maxLines = componentStyle.labelMaxLines, overflow = TextOverflow.Ellipsis, density = density,
        fontFamilyResolver = fontResolver, constraints = Constraints(maxWidth = available.coerceAtLeast(1)),
    )

    private fun currentLabelParagraph(progress: Float): Paragraph? {
        if (fieldLabel.isEmpty() || labelWidth < 0) return null
        if (progress <= 0f) return expandedParagraph
        if (progress >= 1f) return floatingParagraph
        if (intermediateProgress != progress || intermediateParagraph == null) {
            val start = expandedLabelStyle()
            val end = componentStyle.floatingLabelStyle
            val size = lerp(start.sizeSp, end.sizeSp, progress)
            val tracking = lerp(trackingPx(start), trackingPx(end), progress)
            val fontPixels = with(density) { size.sp.toPx() }
            intermediateParagraph = labelParagraph((if (progress < .5f) start else end).copy(
                sizeSp = size,
                lineHeightSp = if (start.lineHeightSp != null && end.lineHeightSp != null) lerp(start.lineHeightSp, end.lineHeightSp, progress) else null,
                weight = lerp(start.weight.toFloat(), end.weight.toFloat(), progress).roundToInt().coerceIn(1, 1000),
                letterSpacing = if (fontPixels > 0f) tracking / fontPixels else 0f,
                letterSpacingSp = null,
            ), lerp(expandedLabelWidth.toFloat(), labelWidth.toFloat(), progress).roundToInt())
            intermediateProgress = progress
        }
        return intermediateParagraph
    }

    private fun clearLabelLayouts() {
        labelWidth = -1
        expandedLabelWidth = -1
        expandedParagraph = null
        floatingParagraph = null
        intermediateParagraph = null
        intermediateProgress = Float.NaN
        labelFamilies.clear()
        fontObservation?.close()
        fontObservation = null
    }

    private fun observeFonts() {
        if (!isAttachedToWindow || fontObservation != null) return
        fontObservation = expandedParagraph?.paragraphIntrinsics?.observeFontChanges {
            removeCallbacks(fontRefresh)
            post(fontRefresh)
        }
    }

    private fun retireMotion() {
        motionDriver?.close()
        motionDriver = null
        labelMotion.snapTo(if (editor.hasFocus() || !editor.text.isNullOrEmpty()) 1f else 0f)
        placeholderDriver?.close()
        placeholderDriver = null
        val show = editor.text.isNullOrEmpty() && !placeholderText.isNullOrEmpty() && (fieldLabel.isEmpty() || editor.hasFocus())
        placeholderRequestedVisible = show
        placeholderMotion.snapTo(if (show) 1f else 0f)
        placeholderView.alpha = placeholderMotion.value
        placeholderView.visibility = if (show) VISIBLE else GONE
        invalidate()
    }

    private fun updatePlaceholder(show: Boolean, animate: Boolean) {
        val target = if (show) 1f else 0f
        val changed = placeholderRequestedVisible != show
        placeholderRequestedVisible = show
        val canAnimate = animate && componentStyle.animationsEnabled && isAttachedToWindow &&
            isShown && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()
        if (!changed && placeholderMotion.targetValue == target && (!placeholderMotion.isRunning || canAnimate)) return
        val current = placeholderMotion.value
        placeholderDriver?.close()
        placeholderDriver = null
        if (!canAnimate || current == target) {
            placeholderMotion.snapTo(target)
            placeholderView.alpha = target
            placeholderView.visibility = if (show) VISIBLE else GONE
            return
        }
        // Material's placeholder enters with SlowEffects and leaves with FastEffects.
        // Retarget from the last painted opacity; never flash opaque over the expanded label.
        placeholderMotion = SpringValue.float(current, SpringSpec<Float>(dampingRatio = 1f,
            stiffness = if (show) 800f else 3800f, visibilityThreshold = .01f))
        placeholderView.visibility = VISIBLE
        placeholderDriver = ViewSpringDriver(placeholderView, placeholderMotion, publish = { value ->
            placeholderView.alpha = value.coerceIn(0f, 1f)
            if (!placeholderRequestedVisible && !placeholderMotion.isRunning) placeholderView.visibility = GONE
        }).also { it.retarget(target) }
    }

    private fun syncLeadingEnabled() = syncSlotEnabled(leadingContent, leadingRequestedEnabled, leadingEnabledBeforeDisable)

    private fun syncTrailingEnabled() = syncSlotEnabled(trailingContent, trailingRequestedEnabled, trailingEnabledBeforeDisable)

    private fun syncSlotEnabled(content: View?, requestedEnabled: Boolean, previousStates: IdentityHashMap<View, Boolean>) {
        if (content == null) return
        if (isEnabled && requestedEnabled) restoreSlotEnabled(previousStates)
        else {
            fun disable(view: View) {
                if (!previousStates.containsKey(view)) previousStates[view] = view.isEnabled
                view.isEnabled = false
                if (view is ViewGroup) for (index in 0 until view.childCount) disable(view.getChildAt(index))
            }
            disable(content)
        }
    }

    private fun restoreSlotEnabled(previousStates: IdentityHashMap<View, Boolean>) {
        previousStates.forEach { (view, enabled) -> view.isEnabled = enabled }
        previousStates.clear()
    }

    private fun placeholderStyle() = editor.currentPlaceholderTextStyle ?: componentStyle.editorStyle.textStyle

    private fun updatePlaceholderTypography() {
        placeholderView.applyStyle(placeholderStyle())
        requestLayout()
        invalidate()
    }

    private fun expandedLabelStyle() = componentStyle.expandedLabelStyle ?: componentStyle.editorStyle.textStyle

    private fun baseStartPadding() = dp(componentStyle.editorStyle.startPaddingDp ?: componentStyle.editorStyle.horizontalPaddingDp)
    private fun baseEndPadding() = dp(componentStyle.editorStyle.endPaddingDp ?: componentStyle.editorStyle.horizontalPaddingDp)

    private fun logicalLineHeight(style: NativeTextStyle): Int = with(density) { ceil((style.lineHeightSp ?: style.sizeSp).sp.toPx()).toInt() }
    private fun trackingPx(style: NativeTextStyle): Float = with(density) {
        style.letterSpacingSp?.sp?.toPx() ?: (style.letterSpacing * style.sizeSp.sp.toPx())
    }

    // Child configuration dispatch happens after the container's callback. Reassert only paint
    // that the decoration owns, without changing native hint data or requesting an input restart.
    private fun enforceManagedTextPaint() {
        if (editor.hintTextColors.defaultColor != Color.TRANSPARENT ||
            editor.hintTextColors.getColorForState(disabledState, Color.TRANSPARENT) != Color.TRANSPARENT)
            editor.setHintTextColor(Color.TRANSPARENT)
        writeDecorationColors()
    }
    private fun dp(value: Float) = context.componentDp(value)
    private fun withAlpha(color: Int, alpha: Float) = (color and 0x00ffffff) or ((Color.alpha(color) * alpha).roundToInt() shl 24)
    private fun lerp(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction
}
