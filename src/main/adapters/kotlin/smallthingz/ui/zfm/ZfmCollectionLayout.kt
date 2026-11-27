package smallthingz.ui.zfm

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import smallthingz.ui.reverb.ReverbScaffold
import smallthingz.ui.reverb.ReverbTitledTopBarLayout

/** Retained common chrome; the owner retains its list, selection and Search body. */
class ZfmCollectionLayout(
    context: Context,
    theme: ZfmTheme,
    title: View,
    back: View,
    primaryActions: View,
    val header: ReverbTitledTopBarLayout = ReverbTitledTopBarLayout(
        context, theme.density.density, theme.colors, back, title, primaryActions),
) : ReverbScaffold(context, header, theme.colors) {
    val optionsHost = FrameLayout(context).apply { visibility = View.GONE }
    private val contentHost = FrameLayout(context)
    init {
        header.contentClipView.measureCompactActionsNaturally = true
        setBody(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(optionsHost, LinearLayout.LayoutParams(-1, -2))
            addView(contentHost, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }
    fun bindTheme(theme: ZfmTheme) {
        bindScaffoldPalette(theme.colors)
        header.bindTitledToolbar(theme.density.density, theme.colors)
    }
    fun setOptionsContent(view: View) = replace(optionsHost, view, -2)
    fun setCollectionBody(view: View) = replace(contentHost, view, -1)
    private fun replace(host: FrameLayout, view: View, height: Int) {
        if (host.childCount == 1 && host.getChildAt(0) === view) return
        host.removeAllViews()
        host.addView(view, FrameLayout.LayoutParams(-1, height))
    }
}
