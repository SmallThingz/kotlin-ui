package smallthingz.ui.reverb

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Space
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import smallthingz.ui.components.NativeForeground
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.util.closePreservingPrimaryFailure
import kotlin.math.roundToInt

/** Content only. Preview bitmap ownership and tab identity remain with the browser. */
data class ReverbBrowserPanelTab<Key : Any>(
    val key: Key,
    val title: String,
    val origin: String?,
    val preview: Bitmap?,
    val fallbackIcon: Drawable?,
    val current: Boolean,
    val currentLabel: String,
    val closeLabel: String,
    val enabled: Boolean = true,
)

enum class ReverbBrowserPanelTabAction { BACK, NEW, SELECT, CLOSE }

/** Candidate tab overview contract, built from Reverb toolbar, group and action recipes. */
open class ReverbBrowserPanelTabs<Key : Any>(context: Context, private val theme: ReverbTheme,
    title: String, backLabel: String, newLabel: String,
) : ReverbBrowserPanelLayout(context) {
    var onTabAction: ((Key?, ReverbBrowserPanelTabAction) -> Unit)? = null
    private val environment = ReverbEnvironment(theme)
    private val back = browserPanelToolbarAction(context, theme, backLabel, ReverbIcon.Back) {
        if (!panelClosed) onTabAction?.invoke(null, ReverbBrowserPanelTabAction.BACK)
    }
    private val heading = theme.paragraph(context, title, theme.appearance.toolbars.centeredTitleRole)
    private val newTab = NativeMaterialButtonView(context, ReverbBottomActionDock.buttonStyle(theme),
        theme.density, theme.fontResolver, onClick = ::newTabRequested).apply {
        bind(newLabel, true, theme.palette.buttonPalette, onClick = ::newTabRequested)
        setLeadingIcon(ReverbIconDrawable(ReverbIcon.Add, theme.density.density, theme.palette.ink))
        filterTouchesWhenObscured = true
    }
    private val cards = linkedMapOf<Key, Card>()
    private val spacing = dp(8)
    private var columns = 1
    private var cardWidth = 0
    private var cardViewportHeight: Int? = null
    private val grid = GridLayout(context).apply { setPadding(spacing, spacing, spacing, spacing) }
    private val scroll = object : ScrollView(context) {
        override fun onMeasure(w: Int, h: Int) {
            // LinearLayout has already allocated the top bar and inset-owning footer. A grid
            // row may use this remaining viewport; further rows still scroll normally.
            val available = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) null else
                (MeasureSpec.getSize(h) - paddingTop - paddingBottom - grid.paddingTop -
                    grid.paddingBottom - 2 * (spacing / 2)).coerceAtLeast(0)
            if (cardViewportHeight != available) {
                cardViewportHeight = available
                cards.values.forEach { it.body.requestLayout() }
            }
            super.onMeasure(w, h)
        }
    }.apply { addView(grid, FrameLayout.LayoutParams(-1, -2)) }
    private val footer = ReverbBottomActionDock(context, theme, newTab)
    init {
        background = theme.appearance.backdrops.pageBackground(theme.palette)
        ViewCompat.setAccessibilityPaneTitle(this, title)
        ViewCompat.setAccessibilityHeading(heading, true)
        addView(ReverbTitledTopBarLayout(context, theme.density.density, theme.palette, back, heading, Space(context),
            style = theme.appearance.toolbars),
            LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, 0, 1f))
        addView(footer, LayoutParams(-1, -2))
    }
    private fun newTabRequested() {
        if (!panelClosed && newTab.browserPanelAdmitsInput() && !panelClosed)
            onTabAction?.invoke(null, ReverbBrowserPanelTabAction.NEW)
    }
    fun focusBackAction() { if (!back.accessibilityAction.requestFocus()) requestFocus() }
    val scrollPosition: Int get() = scroll.scrollY
    val footerHeight: Int get() = footer.height
    /** Remaining safe insets only: the footer includes the full bottom inset in footerHeight. */
    fun applySafeInsets(insets: Insets) { if (!panelClosed) footer.applySafeInsets(insets) }
    /** Current preview bounds in this panel, for anchored presentation and geometry consumers. */
    fun tabPreviewBounds(key: Key): Rect? = cards[key]?.preview?.let { preview ->
        Rect(0, 0, preview.width, preview.height).also { offsetDescendantRectToMyCoords(preview, it) }
    }
    fun restoreScrollPosition(position: Int) {
        if (position != 0) scroll.doOnLayout { if (!panelClosed) scroll.scrollTo(0, position) }
    }
    fun bindTabs(title: String, entries: List<ReverbBrowserPanelTab<Key>>) {
        if (panelClosed) return
        val keys = entries.map { it.key }.toSet()
        require(keys.size == entries.size) { "Tab keys must be unique" }
        var removedFocusIndex: Int? = null
        val iterator = cards.values.iterator()
        while (iterator.hasNext()) {
            val card = iterator.next()
            if (card.content.key !in keys) {
                if (card.gesture.hasFocus()) removedFocusIndex = grid.indexOfChild(card.gesture)
                card.close()
                grid.removeView(card.gesture)
                iterator.remove()
            }
        }
        heading.setText(title)
        entries.forEachIndexed { index, entry ->
            val card = cards.getOrPut(entry.key) { Card(entry) }
            card.bind(entry)
            if (grid.indexOfChild(card.gesture) != index) {
                val focus = card.gesture.findFocus()
                grid.removeView(card.gesture)
                grid.addView(card.gesture, index, cardParams(index))
                focus?.requestFocus()
            } else card.gesture.layoutParams = cardParams(index)
        }
        removedFocusIndex?.let { index ->
            val next = entries.getOrNull(index.coerceAtMost(entries.lastIndex))
            if (next == null || cards[next.key]?.row?.requestFocus() != true) focusBackAction()
        }
    }
    fun updateTab(entry: ReverbBrowserPanelTab<Key>) { if (!panelClosed) cards[entry.key]?.bind(entry) }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val count = columnCount(width / resources.displayMetrics.density)
        val nextWidth = ((width - 2 * spacing) / count - 2 * (spacing / 2)).coerceAtLeast(0)
        if (columns != count || cardWidth != nextWidth) {
            columns = count; cardWidth = nextWidth
            for (index in 0 until grid.childCount) grid.getChildAt(index).layoutParams = cardParams(index)
        }
        super.onMeasure(w, h)
    }
    private fun cardParams(index: Int) = GridLayout.LayoutParams(
        GridLayout.spec(index / columns), GridLayout.spec(index % columns)).apply {
        width = cardWidth; height = LayoutParams.WRAP_CONTENT
        val margin = spacing / 2
        setMargins(margin, margin, margin, margin)
    }
    private inner class Card(initial: ReverbBrowserPanelTab<Key>) : AutoCloseable {
        private var snapshot: ReverbBrowserPanelTab<Key>? = null
        val content get() = checkNotNull(snapshot)
        private var closed = false
        private val recipe = theme.appearance.groupSurfaces.settingsChoices
        private val surface = recipe.create(context, ReverbTextSurface::invalidate).also { recipe.bind(it, theme.palette) }
        val gesture = ReverbBrowserPanelSwipe(context, { it * .35f },
            canSwipe = { !closed && content.enabled && surface.browserPanelAdmitsInput() },
            onDrag = { surface.translationX = it }, onSwipe = { dispatch(ReverbBrowserPanelTabAction.CLOSE) })
        val row = ReverbBrowserPanelSettingRow(context, theme, ReverbBrowserPanelSetting(initial.title, actionId = "select")) {
            dispatch(ReverbBrowserPanelTabAction.SELECT)
        }.apply {
            titleView.setLines(1)
            titleView.ellipsize = TextUtils.TruncateAt.END
            // A tab title needs less inset than a settings row. The adjacent close slot
            // already supplies its own visual inset inside a full 48dp touch target.
            setPaddingRelative(dp(8), dp(4), 0, dp(4))
            minimumHeight = dp(48)
        }
        private val close = ReverbStandardIconButtonView(context, environment, { this@ReverbBrowserPanelTabs })
        private val header = FrameLayout(context).apply {
            // Keep SELECT and CLOSE separate. Reserve the close target before measuring the
            // single-line title, rather than overlaying it or stacking it below long text.
            addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply {
                marginEnd = dp(48)
            })
            addView(close, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.END))
        }
        private val image = object : ImageView(context) {
            private val topFit = Matrix()
            fun alignPreview() {
                if (scaleType != ScaleType.MATRIX) return
                val art = drawable ?: return
                if (width <= 0 || height <= 0 || art.intrinsicWidth <= 0 || art.intrinsicHeight <= 0) return
                val scale = maxOf(width.toFloat() / art.intrinsicWidth, height.toFloat() / art.intrinsicHeight)
                topFit.setScale(scale, scale)
                topFit.postTranslate((width - art.intrinsicWidth * scale) / 2f, 0f)
                imageMatrix = topFit
            }
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                alignPreview()
            }
        }
        val preview = FrameLayout(context).apply {
            setOnClickListener { dispatch(ReverbBrowserPanelTabAction.SELECT, this) }
            filterTouchesWhenObscured = true
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            image.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(image, FrameLayout.LayoutParams(-1, -1))
        }
        val body = object : ViewGroup(context) {
            init { addView(header); addView(preview) }
            override fun onMeasure(w: Int, h: Int) {
                val width = MeasureSpec.getSize(w)
                val exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
                header.measure(exactWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                val previewHeight = browserTabPreviewHeight(width, dp(200), cardViewportHeight, header.measuredHeight)
                preview.measure(exactWidth, MeasureSpec.makeMeasureSpec(previewHeight, MeasureSpec.EXACTLY))
                // Never shrink text or the close target if the header alone exceeds a short
                // viewport. The full header remains scrollable, with a zero-height preview.
                setMeasuredDimension(resolveSize(width, w), header.measuredHeight + preview.measuredHeight)
            }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                header.layout(0, 0, width, header.measuredHeight)
                preview.layout(0, header.bottom, width, header.bottom + preview.measuredHeight)
            }
        }
        init {
            close.visual.mergedRowAccessibility.enableStandaloneAccessibility({ !closed }) { snapshot?.closeLabel }
            close.visual.glyph.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            close.visual.filterTouchesWhenObscured = true
            close.visual.background = NativeForeground.foregroundBackground(theme.palette.pageControls)
            surface.addRow(body, NativeGroupLayout.LayoutParams(-1, -2))
            gesture.addView(surface, FrameLayout.LayoutParams(-1, -2))
            bind(initial)
        }
        private fun dispatch(action: ReverbBrowserPanelTabAction, source: View = surface) {
            val before = snapshot ?: return
            if (!closed && !panelClosed && before.enabled && cards[before.key] === this &&
                source.browserPanelAdmitsInput() && !closed && !panelClosed && snapshot === before && cards[before.key] === this)
                onTabAction?.invoke(before.key, action)
        }
        fun bind(entry: ReverbBrowserPanelTab<Key>) {
            if (closed) return
            val previous = snapshot
            snapshot = entry
            if (!entry.enabled) gesture.cancelBrowserPanelInput()
            if (previous?.title != entry.title || previous.enabled != entry.enabled) {
                row.bindContent(ReverbBrowserPanelSetting(entry.title, actionId = "select", enabled = entry.enabled))
            }
            if (previous?.title != entry.title || previous.origin != entry.origin) {
                row.contentDescription = listOfNotNull(entry.title, entry.origin).joinToString(", ")
            }
            if (previous?.current != entry.current || previous.currentLabel != entry.currentLabel) {
                ViewCompat.setStateDescription(row, entry.currentLabel.takeIf { entry.current })
                surface.setSurfacePreset(theme.palette.surfacePreset)
                surface.setPaletteImmediately(recipe.fillColor(theme.palette),
                    if (entry.current) theme.palette.primary else recipe.borderColor(theme.palette))
            }
            if (previous?.closeLabel != entry.closeLabel || previous.enabled != entry.enabled) {
                close.bind(environment, ReverbIcon.Close, entry.closeLabel, entry.enabled, theme.palette.muted, theme.palette.ink) {
                    dispatch(ReverbBrowserPanelTabAction.CLOSE, close.visual)
                }
            }
            if (previous == null || previous.preview !== entry.preview ||
                (entry.preview == null && previous.fallbackIcon !== entry.fallbackIcon)) {
                if (entry.preview != null) {
                    image.layoutParams = FrameLayout.LayoutParams(-1, -1)
                    image.imageTintList = null
                    image.scaleType = ImageView.ScaleType.MATRIX
                    image.setImageBitmap(entry.preview)
                    image.alignPreview()
                } else {
                    val size = dp(theme.appearance.buttons.raisedIcon.surface.iconSizeDp)
                    image.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                    image.imageTintList = ColorStateList.valueOf(theme.palette.muted)
                    image.scaleType = ImageView.ScaleType.FIT_CENTER
                    image.setImageDrawable(entry.fallbackIcon)
                }
            }
        }
        override fun close() {
            if (closed) return
            closed = true
            // Preview ownership stays with the host; release references even if input retirement throws.
            preview.setOnClickListener(null)
            var failure = closePreservingPrimaryFailure(null) { gesture.cancelBrowserPanelInput() }
            failure = closePreservingPrimaryFailure(failure, row::close)
            failure = closePreservingPrimaryFailure(failure) {
                close.bind(environment, ReverbIcon.Close, "", false, theme.palette.muted, theme.palette.ink) {}
            }
            failure = closePreservingPrimaryFailure(failure) { image.setImageDrawable(null) }
            snapshot = null
            failure?.let { throw it }
        }
    }
    override fun close() {
        if (panelClosed) return
        onTabAction = null
        var failure = closePreservingPrimaryFailure(null) { super.close() }
        cards.values.forEach { failure = closePreservingPrimaryFailure(failure, it::close) }
        cards.clear()
        failure = closePreservingPrimaryFailure(failure) { grid.removeAllViews() }
        failure = closePreservingPrimaryFailure(failure, newTab::close)
        failure?.let { throw it }
    }
    private fun dp(value: Int) = (value * theme.density.density).roundToInt()
    private fun dp(value: Float) = (value * theme.density.density).roundToInt()
    companion object {
        fun columnCount(widthDp: Float): Int = when { widthDp >= 720f -> 3; widthDp >= 296f -> 2; else -> 1 }
    }
}
