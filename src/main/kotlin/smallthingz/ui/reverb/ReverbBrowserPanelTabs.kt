package smallthingz.ui.reverb

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Space
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import smallthingz.ui.components.NativeButtonTouchTarget
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeToolbarBackground
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
    private val environment = ReverbEnvironment(theme.density, theme.fontResolver, theme.palette)
    private val back = browserPanelToolbarAction(context, theme, backLabel, ReverbIcon.Back) {
        if (!panelClosed) onTabAction?.invoke(null, ReverbBrowserPanelTabAction.BACK)
    }
    private val heading = theme.paragraph(context, title, ReverbDesign.appearance.toolbars.centeredTitleRole)
    private val newTab = NativeMaterialButtonView(context, ReverbDesign.appearance.buttons.filled,
        theme.density, theme.fontResolver, onClick = ::newTabRequested).apply {
        bind(newLabel, true, theme.palette.buttonPalette, onClick = ::newTabRequested)
        setLeadingIcon(ReverbIconDrawable(ReverbIcon.Add, theme.density.density, theme.palette.ink))
        filterTouchesWhenObscured = true
    }
    private val cards = linkedMapOf<Key, Card>()
    private val spacing = dp(8)
    private var columns = 1
    private var cardWidth = 0
    private val grid = GridLayout(context).apply { setPadding(spacing, spacing, spacing, spacing) }
    private val scroll = ScrollView(context).apply { addView(grid, FrameLayout.LayoutParams(-1, -2)) }
    private val footer = FrameLayout(context).apply {
        val recipe = ReverbDesign.appearance.toolbars
        NativeToolbarBackground(this, recipe, recipe.backgroundColor(theme.palette)) { ReverbTextSurface.invalidate(this) }
        setPadding(spacing, spacing / 2, spacing, spacing / 2)
        addView(NativeButtonTouchTarget(newTab), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }
    init {
        background = ReverbDesign.appearance.backdrops.pageBackground(theme.palette)
        ViewCompat.setAccessibilityPaneTitle(this, title)
        ViewCompat.setAccessibilityHeading(heading, true)
        addView(ReverbTitledTopBarLayout(context, theme.density.density, theme.palette, back, heading, Space(context)),
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
        private val recipe = ReverbDesign.appearance.groupSurfaces.settingsChoices
        private val surface = recipe.create(context, ReverbTextSurface::invalidate).also { recipe.bind(it, theme.palette) }
        val gesture = ReverbBrowserPanelSwipe(context, { it * .35f },
            canSwipe = { !closed && content.enabled && surface.browserPanelAdmitsInput() },
            onDrag = { surface.translationX = it }, onSwipe = { dispatch(ReverbBrowserPanelTabAction.CLOSE) })
        val row = ReverbBrowserPanelSettingRow(context, theme, ReverbBrowserPanelSetting(initial.title, actionId = "select")) {
            dispatch(ReverbBrowserPanelTabAction.SELECT)
        }.apply {
            titleView.setLines(1)
            titleView.ellipsize = TextUtils.TruncateAt.END
        }
        private val close = ReverbStandardIconButtonView(context, environment, { this@ReverbBrowserPanelTabs })
        private val image = ImageView(context)
        private val preview = object : FrameLayout(context) {
            override fun onMeasure(w: Int, h: Int) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(w), dp(200)), MeasureSpec.EXACTLY))
            }
        }.apply {
            setOnClickListener { dispatch(ReverbBrowserPanelTabAction.SELECT, this) }
            filterTouchesWhenObscured = true
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(image, FrameLayout.LayoutParams(-1, -1))
        }
        init {
            close.visual.mergedRowAccessibility.enableStandaloneAccessibility({ !closed }) { snapshot?.closeLabel }
            close.visual.glyph.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            close.visual.filterTouchesWhenObscured = true
            row.setAccessory(close)
            surface.addRow(row, NativeGroupLayout.LayoutParams(-1, -2))
            surface.addRow(preview, NativeGroupLayout.LayoutParams(-1, -2))
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
                    image.scaleType = ImageView.ScaleType.CENTER_CROP
                    image.setImageBitmap(entry.preview)
                } else {
                    val size = dp(ReverbDesign.appearance.buttons.raisedIcon.surface.iconSizeDp)
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
