package smallthingz.ui.reverb

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeGroupSurfaceRecipe
import smallthingz.ui.components.NativeInputGate
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeText
import smallthingz.ui.components.NativeTextRole
import smallthingz.ui.components.nativeTextStyle
import smallthingz.ui.reverb.settings.settingsSectionTitle
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/**
 * Window-scoped Control settings composition, using Reverb's settings recipes. Named content
 * slots retain their original views and callbacks; the caller owns values, validity, visibility,
 * input admission and draft lifetime. No app-supplied dimensions, colors or recipes are accepted.
 */
class ReverbControlScreenSections(private val context: Context, environment: ReverbEnvironment) {
    private val environment = environment.forSettings()
    private val theme = this.environment.theme
    private val appearance = theme.appearance
    private val selector = appearance.settingsSelectors
    private val spacing = selector.retentionSection
    private val heading = selector.recordingSection
    private fun dp(value: Int) = (value * theme.density.density).roundToInt()
    private fun row() = LinearLayout.LayoutParams(-1, -2)
    private fun column() = object : LinearLayout(context), ReverbTextSurfaceScope {
        init { orientation = VERTICAL }
    }
    private fun group(recipe: NativeGroupSurfaceRecipe = appearance.groupSurfaces.settingsGroup) =
        recipe.create(context, ReverbTextSurface::invalidate).also { recipe.bind(it, theme.palette) }
    private fun NativeGroupLayout.row(view: View, divided: Boolean = false) {
        addRow(view, NativeGroupLayout.LayoutParams(-1, -2).apply { dividerTop = divided })
    }
    private fun title(label: String, followsTheme: Boolean = false) =
        settingsSectionTitle(context, label, this.environment).apply {
            isAccessibilityHeading = true
            setPaddingRelative(dp(heading.sideInsetDp), if (followsTheme) 0 else dp(heading.titleTopInsetDp),
                dp(heading.sideInsetDp), dp(spacing.stackedGapDp))
        }

    fun access(themeSelector: View, accessLabel: String, permissions: View): View = column().apply {
        addView(themeSelector, row())
        addView(title(accessLabel, followsTheme = true), row())
        addView(permissions, row())
    }

    fun connection(selector: View, body: View, guide: View?): View = column().apply {
        layoutParams = row().apply { topMargin = dp(heading.titleTopInsetDp + spacing.trailingInsetDp) }
        addView(selector, row())
        guide?.let { addView(it, row().apply {
            marginStart = dp(spacing.sideInsetDp)
            marginEnd = dp(spacing.sideInsetDp)
        }) }
        addView(body, row().apply { topMargin = dp(spacing.bodyGapDp) })
    }

    fun connectionBody(firstProvider: View, secondProvider: View, inputAllowed: () -> Boolean) =
        ReverbControlScreenConnectionBody(context, theme, firstProvider, secondProvider, inputAllowed)

    fun targets(choices: List<View>): View = group().apply {
        layoutParams = row().apply { topMargin = dp(heading.titleTopInsetDp) }
        choices.forEachIndexed { index, choice -> row(choice, divided = index > 0) }
    }

    fun integrations(label: String, providers: View): View = column().apply {
        addView(title(label), row())
        addView(providers, row())
    }

    fun controller(): ReverbControlScreenController {
        val body = column().apply {
            setPaddingRelative(dp(spacing.sideInsetDp), 0, dp(spacing.sideInsetDp), dp(spacing.stackedGapDp))
        }
        return ReverbControlScreenController(column().apply { addView(group().apply { row(body) }, row()) }, body)
    }

    fun exports(label: String): ReverbControlScreenExports {
        val rows = group()
        return ReverbControlScreenExports(column().apply {
            addView(title(label), row())
            addView(rows, row())
        }, rows)
    }

    fun browser(selection: View, installedChoice: View, authorization: View, disableBuiltIn: View): ReverbControlScreenBrowser {
        val installed = group().apply {
            row(installedChoice)
            row(authorization)
            row(disableBuiltIn, divided = true)
        }
        return ReverbControlScreenBrowser(column().apply {
            layoutParams = row().apply { topMargin = dp(heading.titleTopInsetDp + spacing.trailingInsetDp) }
            addView(selection, row())
            addView(installed, row().apply { topMargin = dp(spacing.bodyGapDp) })
        }, installed)
    }

    fun requests(label: String, enabled: View, notifications: View, overlay: View, overlayPermission: View): View = column().apply {
        layoutParams = row().apply { topMargin = dp(spacing.trailingInsetDp) }
        addView(title(label), row())
        addView(group().apply {
            row(enabled)
            row(notifications)
            row(overlay, divided = true)
            row(overlayPermission)
        }, row())
    }

    fun debuggingDisclosure(message: String, actions: View): View = group(appearance.groupSurfaces.settingsWarning).apply {
        layoutParams = row().apply { bottomMargin = dp(spacing.stackedGapDp) }
        row(column().apply {
            setPaddingRelative(dp(heading.warningHorizontalInsetDp), dp(heading.warningVerticalInsetDp),
                dp(heading.warningHorizontalInsetDp), dp(heading.warningVerticalInsetDp))
            addView(environment.paragraph(context, message, ReverbTextRole.BODY_MEDIUM, theme.palette.onSurface), row())
            addView(actions, row())
        })
    }

    /** A standalone Control action; group and frame slots may replace its standalone spacing. */
    fun action(label: String, primary: Boolean = false, onClick: () -> Unit) = NativeMaterialButtonView(
        context, if (primary) appearance.buttons.filled else appearance.buttons.neutralText,
        theme.density, theme.fontResolver, onClick = onClick,
    ).apply {
        bind(label, true, theme.palette.buttonPalette, emphasized = primary)
        layoutParams = row().apply { topMargin = dp(spacing.stackedGapDp) }
    }
}

class ReverbControlScreenConnectionBody internal constructor(
    context: Context,
    theme: ReverbTheme,
    firstProvider: View,
    secondProvider: View,
    private val inputAllowed: () -> Boolean,
) : LinearLayout(context), NativeInputGate, ReverbTextSurfaceScope {
    override val acceptsNativeInput get() = inputAllowed()
    val note = NativeText(context, theme.palette.pageControls,
        theme.appearance.typography.nativeTextStyle(NativeTypographyRole.BODY_SMALL), NativeTextRole.MUTED).apply {
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        val spacing = theme.appearance.settingsSelectors.retentionSection
        fun dp(value: Int) = (value * theme.density.density).roundToInt()
        setPaddingRelative(dp(spacing.sideInsetDp), dp(spacing.stackedGapDp),
            dp(spacing.sideInsetDp), dp(spacing.trailingInsetDp))
    }
    init {
        orientation = VERTICAL
        addView(firstProvider, LayoutParams(-1, -2))
        addView(secondProvider, LayoutParams(-1, -2))
        addView(note, LayoutParams(-1, -2))
    }
}

class ReverbControlScreenController internal constructor(val view: View, val content: LinearLayout)
class ReverbControlScreenExports internal constructor(val view: View, val rows: NativeGroupLayout)
class ReverbControlScreenBrowser internal constructor(val view: View, val installedSettings: View)
