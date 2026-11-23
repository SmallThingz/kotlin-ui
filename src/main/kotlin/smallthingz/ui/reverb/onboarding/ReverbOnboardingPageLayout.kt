package smallthingz.ui.reverb.onboarding

import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbGlyphView
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbPalette
import smallthingz.ui.reverb.ReverbTextSurface
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.scroll.NativeVerticalScrollState
import smallthingz.ui.scroll.NativeVerticalViewport
import smallthingz.ui.text.view.ParagraphView
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import smallthingz.ui.text.TextStyle
import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.unit.Density
import androidx.lifecycle.LifecycleOwner
import smallthingz.ui.R
import kotlin.math.roundToInt

/** The Reverb page hierarchy owns the visible rows, grouping and source scroll viewport. */
open class ReverbOnboardingPageLayout(
    context: Context,
    val page: Int,
    typography: OnboardingPageTypography,
    density: Density,
    resolver: FontFamily.Resolver,
    initialPalette: ReverbPalette,
    scrollState: NativeVerticalScrollState,
    scrollFrames: MonotonicFrameClock,
    scrollScope: CoroutineScope,
    scrollNested: NativeVerticalNestedHost,
    inputRoot: () -> View?,
) : FrameLayout(context) {
    protected var onToggleOneShot: () -> Unit = {}
    protected var onToggleLooping: () -> Unit = {}
    protected val viewport = NativeVerticalViewport(context, scrollState, density, scrollFrames, scrollScope, scrollNested)
    val sourceVerticalViewport: NativeVerticalViewport get() = viewport
    private val column = OnboardingPageColumn(context)
    private val headerIcon = ReverbGlyphView(context, when (page) {
        0 -> ReverbIcon.BackgroundWarning
        1 -> ReverbIcon.OneShot
        else -> ReverbIcon.Capture
    }, initialPalette.primary)
    private val header = ParagraphView(context, typography.headlineMedium, density, resolver, initialPalette.onSurface)
    private val permissionBody = if (page == 0) label(context, typography.bodyMedium, density, resolver,
        R.string.onboarding_permissions_body, initialPalette.onSurfaceVariant) else null
    private val detailCards = ReverbDesign.appearance.detailCards
    private val permissions = if (page == 0)
        OnboardingPermissionGroup(context, typography, density, resolver, inputRoot, detailCards) else null
    private val bufferGroup = if (page == 1) detailCards.createGroup(context, ReverbTextSurface::invalidate) else null
    private val oneShot = if (page == 1)
        OnboardingBufferRow(context, typography, density, resolver, ReverbIcon.OneShot, initialPalette, inputRoot, detailCards) else null
    private val looping = if (page == 1)
        OnboardingBufferRow(context, typography, density, resolver, ReverbIcon.Looping, initialPalette, inputRoot, detailCards) else null
    private val bufferOrder = if (page == 1) label(context, typography.bodyMedium, density, resolver,
        R.string.onboarding_order, initialPalette.onSurfaceVariant) else null
    private val groupRecipe = ReverbDesign.appearance.groupSurfaces.settingsGroup
    private val audioGroup = if (page == 2) groupRecipe.create(context, ReverbTextSurface::invalidate) else null
    private val compressionWarning = if (page == 2) OnboardingPaddedLabel(context,
        label(context, typography.bodySmall, density, resolver,
            R.string.flac_pcm_required, initialPalette.onSurfaceVariant), density) else null
    private val silenceWarning = if (page == 2) OnboardingPaddedLabel(context,
        label(context, typography.bodySmall, density, resolver,
            R.string.silence_invalid_settings, initialPalette.error), density) else null

    init {
        require(page in 0..2)
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        header.setText(context.getString(when (page) {
            0 -> R.string.onboarding_permissions_title
            1 -> R.string.onboarding_title
            else -> R.string.recording_settings_title
        }))
        column.add(headerIcon, widthDp = 40, heightDp = 40)
        column.add(header, beforeDp = 16)
        when (page) {
            0 -> {
                column.add(checkNotNull(permissionBody), beforeDp = 24)
                column.add(checkNotNull(permissions).view, beforeDp = 16, fillWidth = true)
            }
            1 -> {
                val group = checkNotNull(bufferGroup)
                group.addRow(checkNotNull(oneShot), NativeGroupLayout.LayoutParams(-1, -2))
                group.addRow(checkNotNull(looping), NativeGroupLayout.LayoutParams(-1, -2).apply { dividerTop = true })
                column.add(group, beforeDp = 24, fillWidth = true)
                column.add(checkNotNull(bufferOrder), beforeDp = 18)
                oneShot.onActivate = { onToggleOneShot() }
                looping.onActivate = { onToggleLooping() }
            }
            2 -> column.add(checkNotNull(audioGroup), beforeDp = 24, fillWidth = true)
        }
        viewport.setContent(column)
        addView(viewport, LayoutParams(-1, -1))
    }

    protected fun installAudioRows(compression: View, silence: View) {
        val group = checkNotNull(audioGroup)
        group.addRow(compression, NativeGroupLayout.LayoutParams(-1, -2))
        group.addRow(checkNotNull(compressionWarning), NativeGroupLayout.LayoutParams(-2, -2))
        group.addRow(silence, NativeGroupLayout.LayoutParams(-1, -2).apply { dividerTop = true })
        group.addRow(checkNotNull(silenceWarning), NativeGroupLayout.LayoutParams(-2, -2))
    }

    protected fun connectPermission(lifecycleOwner: LifecycleOwner, request: (OnboardingPermission) -> Unit) =
        permissions?.connect(lifecycleOwner, request)

    protected fun bindPageAppearance(
        palette: ReverbPalette,
        permissionStatus: OnboardingPermissionStatus,
        oneShotEnabled: Boolean,
        loopingEnabled: Boolean,
        controlsEnabled: Boolean,
        interactionEnabled: Boolean,
        compressionAvailable: Boolean,
        silenceValid: Boolean,
    ): Boolean {
        headerIcon.bind(when (page) {
            0 -> ReverbIcon.BackgroundWarning
            1 -> ReverbIcon.OneShot
            else -> ReverbIcon.Capture
        }, palette.primary)
        header.animateTextColor(palette.onSurface)
        permissionBody?.animateTextColor(palette.onSurfaceVariant)
        val permissionMembershipChanged = permissions?.bind(
            permissionStatus.copy(interactionEnabled = interactionEnabled), palette) == true
        bufferGroup?.let { groupRecipe.bind(it, palette) }
        oneShot?.bind(context.getString(R.string.onboarding_one_shot_title),
            context.getString(R.string.onboarding_one_shot_body), oneShotEnabled,
            controlsEnabled && (!oneShotEnabled || loopingEnabled), interactionEnabled, palette)
        looping?.bind(context.getString(R.string.onboarding_looping_title),
            context.getString(R.string.onboarding_looping_body), loopingEnabled,
            controlsEnabled && (!loopingEnabled || oneShotEnabled), interactionEnabled, palette)
        bufferOrder?.animateTextColor(palette.onSurfaceVariant)
        audioGroup?.let { groupRecipe.bind(it, palette) }
        compressionWarning?.apply {
            visibility = if (compressionAvailable) GONE else VISIBLE
            animateTextColor(palette.onSurfaceVariant)
        }
        silenceWarning?.apply {
            visibility = if (silenceValid) GONE else VISIBLE
            animateTextColor(palette.error)
        }
        return permissionMembershipChanged
    }

    protected fun setAudioSectionExposed(exposed: Boolean) {
        audioGroup?.importantForAccessibility = if (exposed) IMPORTANT_FOR_ACCESSIBILITY_NO
            else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    protected fun cancelPageInput() {
        permissions?.cancelPendingRowInput()
        oneShot?.cancelPendingInputEvents()
        looping?.cancelPendingInputEvents()
    }
}

/** Source Column geometry: each Spacer rounds separately, children align to logical Start. */
private class OnboardingPageColumn(context: Context) : ViewGroup(context) {
    private class Item(val beforeDp: Int, val widthDp: Int?, val heightDp: Int?, val fillWidth: Boolean) :
        LayoutParams(-2, -2)
    init { clipChildren = false; clipToPadding = false }
    fun add(view: View, beforeDp: Int = 0, widthDp: Int? = null, heightDp: Int? = null, fillWidth: Boolean = false) {
        addView(view, Item(beforeDp, widthDp, heightDp, fillWidth))
    }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        var height = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val item = child.layoutParams as Item
            height += dp(item.beforeDp)
            val childWidth = item.widthDp?.let { exact(dp(it).coerceAtMost(width)) }
                ?: if (item.fillWidth) exact(width) else atMost(width)
            child.measure(childWidth, item.heightDp?.let { exact(dp(it)) } ?: unspecified())
            height += child.measuredHeight
        }
        setMeasuredDimension(resolveSize(width, w), resolveSize(height, h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            y += dp((child.layoutParams as Item).beforeDp)
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - child.measuredWidth else 0
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            y += child.measuredHeight
        }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

private fun label(context: Context, style: TextStyle, density: Density, resolver: FontFamily.Resolver,
                  text: Int, color: Int): ParagraphView =
    ParagraphView(context, style, density, resolver, color).apply { setText(context.getString(text)) }

/**
 * Source Modifier.padding sits outside Text semantics. Keeping that inset on the paragraph itself
 * draws correctly but incorrectly enlarges the exposed Text bounds. This wrapper owns only the
 * original14/8dp layout inset; the real original label/cursor remains the child node.
 */
private class OnboardingPaddedLabel(
    context: Context,
    private val label: ParagraphView,
    density: Density,
) : ViewGroup(context) {
    private val horizontalInset = (14f * density.density).roundToInt()
    private val verticalInset = (8f * density.density).roundToInt()
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        addView(label)
    }
    fun animateTextColor(color: Int) = label.animateTextColor(color)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun inset(spec: Int, amount: Int): Int =
            if (MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED) unspecified()
            else MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(spec) - amount).coerceAtLeast(0),
                MeasureSpec.getMode(spec))
        label.measure(inset(widthMeasureSpec, horizontalInset * 2), inset(heightMeasureSpec, verticalInset * 2))
        setMeasuredDimension(resolveSize(label.measuredWidth + horizontalInset * 2, widthMeasureSpec),
            resolveSize(label.measuredHeight + verticalInset * 2, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        label.layout(horizontalInset, verticalInset,
            horizontalInset + label.measuredWidth, verticalInset + label.measuredHeight)
    }
}
