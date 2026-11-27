package smallthingz.ui.inject

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.widget.ScrollView
import android.widget.Space
import smallthingz.ui.components.NativeButtonTouchTarget
import smallthingz.ui.components.NativeButtonRecipe
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeSettingsRow
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.components.NativeTopBarAction
import smallthingz.ui.components.forDialogAction
import smallthingz.ui.components.NativeThemedToolbarAction
import smallthingz.ui.icons.MaterialRoundedIcon
import smallthingz.ui.icons.MaterialRoundedIconDrawable
import smallthingz.ui.reverb.ReverbLibraryLayout
import smallthingz.ui.reverb.ReverbTitledTopBarLayout
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.util.closePreservingPrimaryFailure

class InjectActionButton(val view: NativeButtonTouchTarget) : AutoCloseable {
    override fun close() = view.button.close()
}

/** Reusable Inject view recipes; the app resolves theme choice and owns operation state. */
open class InjectAppearance(private val context: Context, val theme: InjectTheme) {
    val defaults = InjectDesign.appearance
    val scheme = theme.palette
    val spacing = InjectDesign.spacing
    private val density = theme.density
    private val fonts = theme.fontResolver
    val controlTheme = theme.controlTheme

    fun bindScrollAppearance(view: ScrollView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.setEdgeEffectColor(ReverbLibraryLayout.overscrollGlowColor(scheme))
        }
    }

    fun icon(icon: MaterialRoundedIcon, tint: Int = scheme.muted) =
        MaterialRoundedIconDrawable(icon, density.density, tint)

    fun group(content: NativeGroupLayout.() -> Unit = {}): NativeGroupLayout =
        defaults.groupSurfaces.settingsGroup.create(context).apply {
            defaults.groupSurfaces.settingsGroup.bindImmediately(this, scheme)
            content()
        }

    fun settingsRow() = NativeSettingsRow(context, scheme.settingsControls)

    fun textButton(text: String, icon: MaterialRoundedIcon? = null, onClick: () -> Unit): InjectActionButton =
        button(text, defaults.buttons.text.forDialogAction(), icon, onClick)

    fun filledButton(text: String, icon: MaterialRoundedIcon? = null, onClick: () -> Unit): InjectActionButton =
        button(text, defaults.buttons.filled.forDialogAction(), icon, onClick)

    /** Paint is selected by semantic role; labels and accepted/cancelled actions belong to the host. */
    fun confirmationActions(cancelText: String, confirmText: String,
        onCancel: () -> Unit, confirmIcon: MaterialRoundedIcon? = null,
        destructive: Boolean = false, onConfirm: () -> Unit): InjectDialogActions {
        // Retain each completed constructor before icon/text binding can fail. Ownership moves
        // to the caller only when the joined layout has also finished construction and returns.
        val owned = arrayOfNulls<NativeMaterialButtonView>(2)
        fun action(index: Int, text: String, recipe: NativeButtonRecipe,
            glyph: MaterialRoundedIcon?, run: () -> Unit) =
            NativeMaterialButtonView(context, recipe, density, fonts, onClick = run).also {
                owned[index] = it
            }.apply {
                setLeadingIcon(glyph?.let { icon(it) })
                bind(text, enabled = true, palette = scheme.buttonPalette)
            }
        try {
            val back = action(0, cancelText, defaults.buttons.text, null, onCancel)
            val recipe = if (destructive) defaults.buttons.destructive else defaults.buttons.filled
            val accept = action(1, confirmText, recipe, confirmIcon, onConfirm)
            return InjectDialogActions(back, accept, scheme)
        } catch (failure: Throwable) {
            for (index in owned.lastIndex downTo 0) {
                closePreservingPrimaryFailure(failure) { owned[index]?.close() }
            }
            throw failure
        }
    }

    fun footerButton(text: String, icon: MaterialRoundedIcon? = null, onClick: () -> Unit): InjectActionButton =
        button(text, defaults.buttons.footerContinue, icon, onClick)

    fun addButton(text: String, onClick: () -> Unit): InjectActionButton {
        val recipe = defaults.buttons.filled
        val button = NativeMaterialButtonView(context, recipe, density, fonts, onClick = onClick).apply {
            setLeadingIcon(icon(MaterialRoundedIcon.Add))
            bind(text, enabled = true, palette = scheme.buttonPalette)
        }
        return InjectActionButton(NativeButtonTouchTarget(button))
    }

    private fun button(text: String, recipe: NativeButtonRecipe, icon: MaterialRoundedIcon?, onClick: () -> Unit): InjectActionButton {
        val button = NativeMaterialButtonView(context, recipe, density, fonts, onClick = onClick)
        button.setLeadingIcon(icon?.let { this.icon(it) })
        button.bind(text, enabled = true, palette = scheme.buttonPalette)
        return InjectActionButton(NativeButtonTouchTarget(button))
    }

    fun paragraph(text: String, role: NativeTypographyRole, color: Int = scheme.ink): ParagraphView =
        paragraph(text, defaults.typography[role], color)

    fun sectionTitle(text: String): ParagraphView =
        paragraph(text, defaults.settingsSelectors.sectionTitle, scheme.ink).apply { isAccessibilityHeading = true }

    private fun toolbarTitle(text: String): ParagraphView =
        paragraph(text, defaults.toolbars.centeredTitleRole, defaults.toolbars.contentColor(scheme)).apply {
            isAccessibilityHeading = true
        }

    private fun paragraph(text: String, style: TextStyle, color: Int) =
        ParagraphView(context, style, density, fonts, color).apply { setText(text) }

    fun homeToolbar(inputRoot: View? = null): InjectProductTopBar =
        InjectProductTopBar(context, theme, inputRoot)

    fun semanticIconAction(description: String, icon: MaterialRoundedIcon, onClick: () -> Unit): NativeTopBarAction =
        toolbarIcon({ null }, description, selectionWrapper = true, onClick = onClick) { tint -> this.icon(icon, tint) }

    fun semanticIconAction(inputRoot: () -> View?, description: String, icon: MaterialRoundedIcon,
        onClick: () -> Unit): NativeTopBarAction =
        toolbarIcon(inputRoot, description, selectionWrapper = false, onClick = onClick) { tint -> this.icon(icon, tint) }

    fun centeredToolbar(navigation: View, title: String): ReverbTitledTopBarLayout =
        ReverbTitledTopBarLayout(context, density.density, scheme, navigation, toolbarTitle(title),
            Space(context), defaults.toolbars)

    fun iconAction(
        inputRoot: () -> View?,
        description: String,
        drawableRes: Int,
        onClick: () -> Unit,
    ): NativeTopBarAction = toolbarIcon(inputRoot, description, selectionWrapper = true, onClick = onClick) { tint ->
        requireNotNull(context.getDrawable(drawableRes)).mutate().apply { setTint(tint) }
    }

    private fun toolbarIcon(inputRoot: () -> View?, description: String, selectionWrapper: Boolean,
        onClick: () -> Unit, glyph: (Int) -> Drawable): NativeTopBarAction {
        val toolbar = defaults.toolbars
        val tone = NativeToolbarActionTone.MUTED
        val tint = toolbar.actionTint(scheme, tone)
        return NativeThemedToolbarAction(context, density.density, scheme, inputRoot, description,
            selectionWrapper = selectionWrapper, rolePresent = true, style = toolbar).apply {
            setGlyph(glyph(tint), tint)
            bindToolbarAction(density.density, scheme, true, tone, onClick)
        }
    }
}
