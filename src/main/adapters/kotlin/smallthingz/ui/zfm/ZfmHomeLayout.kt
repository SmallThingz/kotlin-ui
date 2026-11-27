package smallthingz.ui.zfm

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import smallthingz.ui.components.NativeTopBarLayout
import smallthingz.ui.components.NativeToolbarContentAction
import smallthingz.ui.reverb.ReverbTopBarLayout
import smallthingz.ui.reverb.ReverbScaffold
import kotlin.math.roundToInt

/** Retained launcher: shared product chrome, one scrolling body, no navigation dock. */
class ZfmHomeLayout(
    context: Context,
    theme: ZfmTheme,
    identity: View,
    settingsIcon: Drawable?,
    settingsLabel: String,
    onSettings: () -> Unit,
    val header: ReverbTopBarLayout = ReverbTopBarLayout(context, theme.density.density, theme.colors),
) : ReverbScaffold(context, header, theme.colors) {
    private val density = theme.density.density
    private fun dp(value: Int) = (value * density).roundToInt()
    val contextualActions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(16), dp(12), dp(16), dp(16))
    }
    val scroll = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
        addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
    }
    private var bottomFirst: Boolean? = null

    init {
        header.addToolbarSlot(identity, NativeTopBarLayout.Slot.CENTER)
        val glyph = ImageView(context).apply {
            setImageDrawable(settingsIcon)
            setColorFilter(theme.ink)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            minimumWidth = dp(24)
            minimumHeight = dp(24)
        }
        val settings = NativeToolbarContentAction(context, glyph, settingsLabel, this,
            density, 48f, theme.ink, standaloneAccessibility = true).apply {
            bindAction(density, 48f, theme.ink, onSettings)
        }
        header.addToolbarSlot(settings, NativeTopBarLayout.Slot.END)
        setBody(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(contextualActions, LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }

    /** Only direction changes choose an edge; ordinary publications retain the user's scroll. */
    fun bindDirection(bottomUp: Boolean) {
        if (bottomFirst == bottomUp) return
        bottomFirst = bottomUp
        content.gravity = if (bottomUp) Gravity.BOTTOM else Gravity.TOP
        val observer = scroll.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                if (bottomFirst == bottomUp) scroll.scrollTo(0,
                    if (bottomUp) (content.height - scroll.height).coerceAtLeast(0) else 0)
                return true
            }
        })
    }
}

/** Two comfortable category columns collapse to a readable list with larger type. */
class ZfmHomeCategoryGrid(context: Context) : GridLayout(context) {
    private var columns = 0
    private var children = -1
    private var bottomFirst = true
    fun bindDirection(bottomUp: Boolean) {
        if (bottomFirst == bottomUp) return
        bottomFirst = bottomUp
        columns = 0
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val minimum = (168f * resources.displayMetrics.density *
            resources.configuration.fontScale.coerceAtLeast(1f)).roundToInt()
        val next = if (available >= minimum * 2) 2 else 1
        if (columns != next || children != childCount) {
            columnCount = UNDEFINED
            val rows = (childCount + next - 1) / next
            for (i in 0 until childCount) getChildAt(i).layoutParams = LayoutParams(
                spec(if (bottomFirst) rows - 1 - i / next else i / next),
                spec(i % next, 1f)).apply { width = 0; height = -2 }
            columnCount = next
            columns = next
            children = childCount
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
