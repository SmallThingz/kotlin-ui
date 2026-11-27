package smallthingz.ui.zfm

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import androidx.core.view.OneShotPreDrawListener
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import smallthingz.ui.components.NativeToolbarContentAction
import smallthingz.ui.reverb.ReverbTitledTopBarLayout
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
    private val settingsHost: FrameLayout = FrameLayout(context),
    val header: ReverbTitledTopBarLayout = ReverbTitledTopBarLayout(context,
        theme.density.density, theme.colors, Space(context), identity, settingsHost).apply {
        contentClipView.measureCompactActionsNaturally = true
    },
) : ReverbScaffold(context, header, theme.colors) {
    private val density = theme.density.density
    private fun dp(value: Int) = (value * density).roundToInt()
    val contextualActions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(16), dp(12), dp(16), dp(16))
    }
    private var bottomFirst: Boolean? = null
    val scroll = object : ScrollView(context) {
        private var laidOutRange = 0
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            // Our frame already has its new size here; use the last complete
            // layout's range rather than mixing old child and new viewport sizes.
            val wasAtBottom = bottomFirst == true && scrollY >= laidOutRange
            super.onLayout(changed, l, t, r, b)
            laidOutRange = bottomScrollY(this)
            // Storage metadata and warnings arrive after the first layout. Keep
            // the user's chosen end anchored; never pull a scrolled-up user back.
            if (wasAtBottom) scrollTo(scrollX, laidOutRange)
        }
    }.apply {
        isFillViewport = true
        clipToPadding = false
        addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
    }

    private fun bottomScrollY(viewport: ScrollView): Int {
        val viewportHeight = viewport.height - viewport.paddingTop - viewport.paddingBottom
        return (content.height - viewportHeight).coerceAtLeast(0)
    }

    init {
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
        settingsHost.addView(settings, FrameLayout.LayoutParams(dp(48), dp(48)))
        setBody(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(contextualActions, LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }

    /** Direction changes choose an edge; subsequent layouts preserve the user's end/offset. */
    fun bindDirection(bottomUp: Boolean) {
        if (bottomFirst == bottomUp) return
        bottomFirst = bottomUp
        content.gravity = if (bottomUp) Gravity.BOTTOM else Gravity.TOP
        OneShotPreDrawListener.add(scroll) {
            if (bottomFirst == bottomUp) scroll.scrollTo(0, if (bottomUp) bottomScrollY(scroll) else 0)
        }
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
