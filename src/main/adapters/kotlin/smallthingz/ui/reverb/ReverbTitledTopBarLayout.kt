package smallthingz.ui.reverb

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.components.NativeCenteredTopBarLayout
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeToolbarBackground
import smallthingz.ui.components.NativeToolbarStyle
import smallthingz.ui.components.NativeTopBarInsets
import smallthingz.ui.text.view.ParagraphView

/** A centered title between the same compact action slots used by the brand toolbar. */
open class ReverbTitledTopBarLayout(
    context: Context,
    density: Float,
    initialPalette: NativeColorScheme,
    navigation: View,
    title: View,
    action: View,
    private val style: NativeToolbarStyle,
    onPaintChanged: (View) -> Unit = ReverbTextSurface::invalidate,
) : ViewGroup(context) {
    /** Preserve the original constructor and its shared default recipe. */
    constructor(
        context: Context,
        density: Float,
        initialPalette: NativeColorScheme,
        navigation: View,
        title: View,
        action: View,
        onPaintChanged: (View) -> Unit = ReverbTextSurface::invalidate,
    ) : this(context, density, initialPalette, navigation, title, action,
        ReverbDesign.appearance.toolbars, onPaintChanged)

    private val chrome = NativeToolbarBackground(this, style, initialPalette) {
        onPaintChanged(this)
    }
    private var insets = NativeTopBarInsets(0, 0, 0)
    val contentClipView = NativeCenteredTopBarLayout(context, density, navigation, title, action, style,
        useCompactActions = true)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        (title as? ParagraphView)?.useSingleLineEllipsisForDefaultLayout()
        addView(contentClipView)
    }

    fun bindTitledToolbar(density: Float, palette: NativeColorScheme) {
        chrome.bind(style, palette)
        contentClipView.bindStyle(density, style)
    }

    fun setRemainingWindowInsets(value: NativeTopBarInsets) {
        require(value.left >= 0 && value.top >= 0 && value.right >= 0)
        if (value == insets) return
        insets = value
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        require(MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) { "Reverb toolbar requires bounded width" }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val innerWidth = (width - insets.left - insets.right).coerceAtLeast(0)
        val maxHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) heightMeasureSpec
        else MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(heightMeasureSpec) - insets.top).coerceAtLeast(0),
            MeasureSpec.AT_MOST)
        contentClipView.measure(MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY), maxHeight)
        setMeasuredDimension(width, resolveSize(insets.top + contentClipView.measuredHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        contentClipView.layout(insets.left, insets.top, insets.left + contentClipView.measuredWidth,
            insets.top + contentClipView.measuredHeight)
    }

    override fun onTouchEvent(event: MotionEvent) = true
}
