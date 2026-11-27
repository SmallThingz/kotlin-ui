package smallthingz.ui.reverb

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.components.NativeColorScheme

/** Physical window insets already resolved by the host. */
data class ReverbScaffoldInsets(
    val statusTop: Int = 0,
    val contentLeft: Int = 0,
    val contentRight: Int = 0,
    val contentBottom: Int = 0,
)

/** Two direct content slots with Reverb's page backdrop, inset layout and traversal order. */
open class ReverbScaffold(
    context: Context,
    val toolbar: View,
    initialPalette: NativeColorScheme,
    initialInsets: ReverbScaffoldInsets = ReverbScaffoldInsets(),
) : ViewGroup(context) {
    constructor(
        context: Context,
        toolbar: View,
        theme: ReverbTheme,
        initialInsets: ReverbScaffoldInsets = ReverbScaffoldInsets(),
    ) : this(context, toolbar, theme.palette, initialInsets)

    private val backdrops = ReverbDesign.appearance.backdrops
    private val backdrop = backdrops.attachPage(this, initialPalette)
    private var contentInsets = initialInsets
    private var body: View? = null

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(toolbar)
    }

    fun setBody(value: View?) {
        if (body === value) return
        body?.let(::removeView)
        body = value
        if (value != null) addView(value)
    }

    fun setScaffoldInsets(value: ReverbScaffoldInsets) {
        if (contentInsets == value) return
        contentInsets = value
        requestLayout()
    }

    fun bindTheme(theme: ReverbTheme) = bindScaffoldPalette(theme.palette)

    fun bindScaffoldPalette(palette: NativeColorScheme) {
        backdrops.bindPage(backdrop, palette)
    }

    /** The body precedes its toolbar in one-dimensional focus searches. */
    fun scaffoldFocusChildAt(index: Int): View =
        getChildAt(if (body != null && index < 2) 1 - index else index)

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        setMeasuredDimension(width, height)
        toolbar.measure(exact(width), MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        val bodyWidth = (width - contentInsets.contentLeft - contentInsets.contentRight).coerceAtLeast(0)
        val bodyHeight = (height - toolbar.measuredHeight - contentInsets.contentBottom).coerceAtLeast(0)
        body?.measure(exact(bodyWidth), exact(bodyHeight))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val top = toolbar.measuredHeight
        toolbar.layout(0, 0, width, top)
        body?.let {
            val left = contentInsets.contentLeft
            it.layout(left, top, left + it.measuredWidth, top + it.measuredHeight)
        }
    }

    private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
}
