package smallthingz.ui.reverb

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

data class ReverbBrowserChromeRow(val id: String?, val title: String, val detail: String? = null,
    val icon: ReverbBrowserChromeIcon? = null, val enabled: Boolean = true)
data class ReverbBrowserChromeSite(val origin: String, val connection: String?, val icon: ReverbBrowserChromeIcon)
enum class ReverbBrowserChromeMenuKind { NAVIGATION, HISTORY, SITE, EXTERNAL_LINK }
data class ReverbBrowserChromeMenuContent(val title: String,
    val heading: String? = null, val detail: String? = null,
    val navigation: List<ReverbBrowserChromeAction> = emptyList(),
    val site: ReverbBrowserChromeSite? = null, val rows: List<ReverbBrowserChromeRow> = emptyList())

internal class ReverbBrowserChromeMenuBody(context: Context, theme: ReverbTheme,
    content: ReverbBrowserChromeMenuContent, onAction: (String) -> Unit,
) : ReverbBrowserPanelLayout(context) {
    private val owned = mutableListOf<AutoCloseable>()
    init {
        fun paragraph(text: String, heading: Boolean) = theme.paragraph(context, text,
            if (heading) NativeTypographyRole.TITLE_LARGE else NativeTypographyRole.BODY_SMALL).apply {
            isAccessibilityHeading = heading
            fun dp(value: Int) = (value * theme.density.density).roundToInt()
            setPaddingRelative(dp(16), dp(if (heading) 14 else 4), dp(16), dp(if (heading) 4 else 10))
        }
        content.heading?.let { addView(paragraph(it, true), LayoutParams(-1, -2)) }
        content.detail?.let { addView(paragraph(it, false), LayoutParams(-1, -2)) }
        if (content.navigation.isNotEmpty()) addView(LinearLayout(context).apply {
            content.navigation.forEach { action ->
                val button = ReverbBrowserChromeActionView(context, theme, action, onAction)
                owned.add(button)
                addView(button, LayoutParams(0, -2, 1f))
            }
        }, LayoutParams(-1, -2))
        content.site?.let {
            val row = reverbBrowserPanelSiteIdentity(context, theme, it.origin, it.connection, it.icon.drawable(context, theme))
            owned.add(row); addView(row, LayoutParams(-1, -2))
        }
        val recipe = ReverbDesign.appearance.groupSurfaces.settingsGroup
        addView(recipe.create(context, ReverbTextSurface::invalidate).apply {
            recipe.bind(this, theme.palette)
            content.rows.forEachIndexed { index, item ->
                val row = browserChromeRow(context, theme, item, onAction)
                owned.add(row)
                addRow(row, NativeGroupLayout.LayoutParams(-1, -2).apply { dividerTop = index > 0 })
            }
        }, LayoutParams(-1, -2))
    }
    override fun close() {
        if (panelClosed) return
        super.close()
        owned.forEach { it.close() }; owned.clear()
    }
}

private fun browserChromeRow(context: Context, theme: ReverbTheme, item: ReverbBrowserChromeRow,
    onAction: (String) -> Unit) = ReverbBrowserPanelSettingRow(context, theme,
        ReverbBrowserPanelSetting(item.title, item.detail, icon = item.icon?.drawable(context, theme),
            actionId = item.id, enabled = item.enabled), onAction).apply {
        titleView.maxLines = 2; titleView.ellipsize = TextUtils.TruncateAt.END
        detailView.maxLines = 1; detailView.ellipsize = TextUtils.TruncateAt.END
    }

/** History content is a snapshot; admission and navigation remain with its current browser owner. */
class ReverbBrowserChromeStartPage(context: Context, theme: ReverbTheme, title: String,
    recentHeading: String?, rows: List<ReverbBrowserChromeRow>, inputAllowed: () -> Boolean, onAction: (String) -> Unit,
) : ReverbBrowserPanelLayout(context) {
    private var admission: (() -> Boolean)? = inputAllowed
    private var callback: ((String) -> Unit)? = onAction
    private val owned = mutableListOf<ReverbBrowserPanelSettingRow>()
    init {
        fun dp(value: Int) = (value * theme.density.density).roundToInt()
        background = ReverbDesign.appearance.backdrops.pageBackground(theme.palette)
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            setPaddingRelative(dp(16), dp(36), dp(16), dp(24))
            addView(theme.paragraph(context, title, NativeTypographyRole.DISPLAY_SMALL).apply {
                isAccessibilityHeading = true
                setPaddingRelative(dp(16), 0, dp(16), dp(8))
            })
            recentHeading?.let { addView(theme.paragraph(context, it, NativeTypographyRole.TITLE_LARGE).apply {
                isAccessibilityHeading = true
                setPaddingRelative(dp(16), dp(14), dp(16), dp(4))
            }, LayoutParams(-1, -2).apply { topMargin = dp(12) }) }
            val recipe = ReverbDesign.appearance.groupSurfaces.settingsGroup
            addView(recipe.create(context, ReverbTextSurface::invalidate).apply {
                recipe.bind(this, theme.palette)
                rows.forEachIndexed { index, item ->
                    val row = browserChromeRow(context, theme, item) { id ->
                        if (!panelClosed && admission?.invoke() == true && !panelClosed && browserPanelAdmitsInput())
                            callback?.invoke(id)
                    }
                    owned.add(row)
                    addRow(row, NativeGroupLayout.LayoutParams(-1, -2).apply { dividerTop = index > 0 })
                }
            }, LayoutParams(-1, -2).apply { if (recentHeading == null) topMargin = dp(12) })
        }
        addView(ScrollView(context).apply { addView(column, FrameLayout.LayoutParams(-1, -2)) }, LayoutParams(-1, -1))
    }
    override fun close() {
        if (panelClosed) return
        super.close(); admission = null; callback = null
        owned.forEach { it.close() }; owned.clear()
    }
}
