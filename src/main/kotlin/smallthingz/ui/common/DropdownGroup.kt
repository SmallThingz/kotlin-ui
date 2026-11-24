package smallthingz.ui.common

import android.content.Context
import android.os.LocaleList
import android.view.View
import android.widget.FrameLayout
import smallthingz.ui.R
import smallthingz.ui.animation.view.transitionForegroundInk
import smallthingz.ui.components.NativeAnchoredDropdown
import smallthingz.ui.components.DropdownOptionHeights
import smallthingz.ui.components.NativeDropdown
import smallthingz.ui.components.NativeDropdownColors
import smallthingz.ui.components.createDropdownIcon
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.util.closePreservingPrimaryFailure

/** A window-scoped group of typed dropdown fields with one expanded field at a time. */
class DropdownGroup(
    initialTheme: ControlTheme,
    private val overlayHost: () -> FrameLayout?,
    private val inputAllowed: () -> Boolean,
    internal val selectionPolicy: DropdownSelectionPolicy,
) : AutoCloseable {
    constructor(initialTheme: ControlTheme, overlayHost: () -> FrameLayout?, inputAllowed: () -> Boolean) :
        this(initialTheme, overlayHost, inputAllowed, DropdownSelectionPolicy.PROGRESSIVE)

    private class Session(
        val field: DropdownField<*>,
        val dropdown: NativeAnchoredDropdown,
        val label: String,
        val options: List<*>,
        val optionLabels: List<String>,
        var selected: Any?,
    ) {
        val layoutDirection = field.view.layoutDirection
        var expectedSelection: Any? = selected
        var expanded = false
    }

    internal var theme = initialTheme
        private set
    internal var palette = initialTheme.dropdownColors()
        private set
    private var current: Session? = null
    private var closed = false

    /** True while this group owns a mounted or transitioning popup. */
    val isExpanded: Boolean get() = current != null

    fun <T> field(context: Context, content: DropdownContent<T>, inputRoot: (() -> View?)? = null,
        onSelected: (T) -> Unit): DropdownField<T> {
        check(!closed)
        return DropdownField(context, this, content, inputRoot, onSelected)
    }

    fun bindTheme(next: ControlTheme) {
        if (closed) return
        val previous = theme
        theme = next
        if (previous.colors != next.colors) palette = next.dropdownColors()
        if (previous.appearance !== next.appearance || previous.colors != next.colors ||
            previous.density !== next.density || previous.fontResolver !== next.fontResolver) dismiss()
    }

    internal fun <T> open(field: DropdownField<T>, content: DropdownContent<T>) {
        if (closed || !inputAllowed()) return
        val host = overlayHost() ?: return
        val anchor = field.view
        if (!host.isAttachedToWindow || host.windowToken != anchor.windowToken) return
        if (!content.canOpen(selectionPolicy)) return
        dismiss()
        if (closed || current != null || !inputAllowed()) return

        val options = content.options.toList()
        val labels = options.map(content.optionLabel)
        val config = theme
        val dropdown = NativeAnchoredDropdown(anchor.context, host, config.appearance, config.colors,
            config.density, config.fontResolver, palette = palette)
        val session = Session(field, dropdown, content.label, options, labels, content.selected)
        current = session
        try {
            val mounted = dropdown.open(anchor, content.copy(options = options,
                optionLabel = { labels[options.indexOf(it)] }), selectionPolicy, { terminal(session) }) select@{ value ->
                if (current !== session) return@select
                if (!inputAllowed() || !anchor.isEnabled || !anchor.isShown) {
                    dismiss()
                    return@select
                }
                session.expectedSelection = value
                field.select(value)
            }
            if (mounted && current === session) {
                session.expanded = true
                field.setExpanded(true)
            } else terminal(session)
        } catch (failure: Throwable) {
            if (current === session) closePreservingPrimaryFailure(failure, ::dismiss)
            throw failure
        }
    }

    internal fun <T> update(field: DropdownField<T>, content: DropdownContent<T>) {
        val session = current?.takeIf { it.field === field } ?: return
        if (!inputAllowed() || session.layoutDirection != field.view.layoutDirection ||
            session.label != content.label || session.options != content.options ||
            content.options.indices.any { session.optionLabels[it] != content.optionLabel(content.options[it]) } ||
            (session.selected != content.selected && session.expectedSelection != content.selected)) {
            dismiss()
            return
        }
        // Accept the owner's echo while the wheel still owns input for this field.
        session.selected = content.selected
    }

    internal fun retire(field: DropdownField<*>) {
        if (current?.field === field) dismiss()
    }

    fun dismiss() {
        val session = current ?: return
        var failure = closePreservingPrimaryFailure(null, session.dropdown::close)
        failure = closePreservingPrimaryFailure(failure) { terminal(session) }
        failure?.let { throw it }
    }

    private fun terminal(session: Session) {
        if (current !== session) return
        current = null
        if (session.expanded) session.field.setExpanded(false)
    }

    override fun close() {
        if (closed) return
        closed = true
        dismiss()
    }
}

/** Content and values retain their Kotlin type through selection, regardless of identical labels. */
class DropdownField<T> internal constructor(
    context: Context,
    private val group: DropdownGroup,
    initial: DropdownContent<T>,
    inputRoot: (() -> View?)?,
    onSelected: (T) -> Unit,
) : AutoCloseable {
    private var content = initial
    private var onSelected: ((T) -> Unit)? = onSelected
    private var expanded = false
    private var closed = false
    private var descriptionLocales: LocaleList? = null
    private val arrow = createDropdownIcon(context, group.palette.muted)
    private val titleRole = NativeTypographyRole.BODY_SMALL
    private val valueRole = NativeTypographyRole.BODY_LARGE
    internal val native = NativeDropdown(context,
        ParagraphView(context, group.theme.appearance.typography[titleRole], group.theme.density,
            group.theme.fontResolver, group.theme.colors.muted),
        ParagraphView(context, group.theme.appearance.typography[valueRole], group.theme.density,
            group.theme.fontResolver, group.theme.colors.ink),
        arrow, group.palette, inputRoot ?: { null },
        onBinding = { _, _ -> }, open = { _, next -> group.open(this, next) },
        foregroundColor = ::transitionForegroundInk,
    )
    val view: View get() = native

    init {
        if (group.selectionPolicy == DropdownSelectionPolicy.EXPLICIT) {
            val theme = group.theme
            native.wrapValues(DropdownOptionHeights(context, theme.appearance.typography[valueRole], theme.density, theme.fontResolver))
        }
        bind(initial)
    }

    fun bind(next: DropdownContent<T>, onSelected: ((T) -> Unit)? = null) {
        if (closed) return
        content = next
        if (onSelected != null) this.onSelected = onSelected
        val config = group.theme
        native.bindTypography(config.appearance.typography[titleRole], config.appearance.typography[valueRole],
            config.density, config.fontResolver)
        val locales = view.resources.configuration.locales
        if (descriptionLocales != locales) {
            native.bindDescription(view.context.getString(R.string.common_open_options))
            descriptionLocales = locales
        }
        render()
        group.update(this, next)
    }

    internal fun select(value: T) = onSelected?.invoke(value)

    internal fun setExpanded(value: Boolean) {
        expanded = value
        render()
    }

    private fun render() {
        if (closed) return
        if (group.selectionPolicy == DropdownSelectionPolicy.EXPLICIT) {
            native.bind(content, expanded, group.palette, present = content.canOpen(group.selectionPolicy))
        } else native.bind(content, expanded, group.palette)
    }

    override fun close() {
        if (closed) return
        closed = true
        onSelected = null
        var failure = closePreservingPrimaryFailure(null) { group.retire(this) }
        failure = closePreservingPrimaryFailure(failure, native::close)
        failure?.let { throw it }
    }
}

internal fun ControlTheme.dropdownColors() = NativeDropdownColors(colors.field, colors.raised, colors.muted, colors.ink, colors.border)
