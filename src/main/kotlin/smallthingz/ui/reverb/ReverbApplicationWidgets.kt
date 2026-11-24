package smallthingz.ui.reverb

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import smallthingz.ui.components.*
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.TextStyle
import kotlin.math.roundToInt

/**
 * Retained application widgets using Reverb's published recipes. Callers retain their native view
 * handles, content, editor configuration and callbacks; the library owns their appearance/layout.
 * The window-scoped instance must be replaced when its theme configuration changes.
 */
class ReverbApplicationWidgets(private val context: Context, theme: ReverbTheme) {
    private val appearance = theme.appearance
    private val colors = theme.palette
    private val componentPalette get() = colors.pageControls
    private val textDensity = theme.density
    private val fontResolver = theme.fontResolver
    private val reverbTheme = theme
    private fun dp(value: Int) = dp(value.toFloat())
    private fun dp(value: Float) = (value * textDensity.density).roundToInt()

    fun background(topBar: Boolean = false) = if (topBar)
        NoiseDrawable(appearance.toolbars.backgroundColor(colors), appearance.toolbars.grainSeed).apply {
            setNoiseIntensity(colors.surfacePreset?.backgroundGrain ?: 1f)
        }
    else appearance.backdrops.pageBackground(colors)

    fun informationalSheet(anchor: View, title: String, onDismiss: () -> Unit): NativeActionSheet =
        createReverbActionSheet(anchor, reverbTheme, title, onDismiss)

    fun floatingPanel(body: View): NativeFloatingPanelSurface {
        val panel = appearance.modals.floating
        return object : NativeFloatingPanelSurface(context, textDensity.density, panel, colors) {
            init { setPanelViewport(ScrollView(context).apply { addView(body) }) }
        }.apply {
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                val edges = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                setContentInsets(edges.left, edges.top, edges.right, edges.bottom)
                insets
            }
        }
    }

    fun sectionLabel(label: CharSequence, topInset: Boolean = true) = NativeText(context, componentPalette,
        nativeTextStyle(appearance.settingsSelectors.sectionTitle, NativeTypographyRole.TITLE_MEDIUM)).apply {
        text = label
        isAccessibilityHeading = true
        val layout = appearance.settingsSelectors.recordingSection
        setPaddingRelative(dp(layout.sideInsetDp), if (topInset) dp(layout.titleTopInsetDp) else 0, dp(layout.sideInsetDp),
            dp(appearance.settingsSelectors.retentionSection.stackedGapDp))
    }

    fun settingsGroup(role: ReverbApplicationGroup = ReverbApplicationGroup.SETTINGS): NativeGroupLayout {
        val recipe = role.recipe(appearance.groupSurfaces)
        return recipe.create(context).also { recipe.bind(it, colors) }
    }

    fun text(
        value: String = "",
        secondary: Boolean = false,
        heading: Boolean = false,
        role: NativeTypographyRole = when {
            heading -> NativeTypographyRole.TITLE_LARGE
            secondary -> NativeTypographyRole.BODY_SMALL
            else -> NativeTypographyRole.BODY_MEDIUM
        },
    ) = NativeText(context, componentPalette, appearance.typography.nativeTextStyle(role),
        if (secondary) NativeTextRole.MUTED else NativeTextRole.CONTENT).apply {
        text = value
        isAccessibilityHeading = heading
    }

    fun nativeTextStyle(role: NativeTypographyRole): NativeTextStyle = appearance.typography.nativeTextStyle(role)

    private fun nativeTextStyle(style: TextStyle, role: NativeTypographyRole): NativeTextStyle = NativeTextStyle(
        sizeSp = if (style.fontSize.isSp) style.fontSize.value else role.style.fontSizeSp,
        lineHeightSp = if (style.lineHeight.isSp) style.lineHeight.value else null,
        weight = style.fontWeight?.weight ?: role.style.fontWeight,
        letterSpacingSp = if (style.letterSpacing.isSp) style.letterSpacing.value else null,
        includeFontPadding = style.platformStyle?.includeFontPadding,
        lineHeightStyle = style.lineHeightStyle,
    )

    fun button(label: String, primary: Boolean = false, action: () -> Unit) = button(
        label, if (primary) ReverbApplicationAction.PRIMARY else ReverbApplicationAction.SECONDARY, action,
    )

    fun button(label: String, role: ReverbApplicationAction, action: () -> Unit) = NativeMaterialButtonView(
        context, role.recipe(appearance.buttons), textDensity, fontResolver, onClick = action,
    ).apply { bindButton(this, true, primary = true, label = label) }

    fun bindButton(button: NativeMaterialButtonView, enabled: Boolean, primary: Boolean = false,
        label: String = button.label.getText()) {
        button.bind(label, enabled, colors.buttonPalette, emphasized = primary)
    }

    fun actionPair(first: NativeMaterialButtonView, second: NativeMaterialButtonView): View {
        val entries = listOf(first, second)
        val group = settingsGroup(ReverbApplicationGroup.CHOICES).apply {
            entries.forEach { addRow(it, NativeGroupLayout.LayoutParams(-1, -2)) }
        }
        return object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val both = first.visibility == View.VISIBLE && second.visibility == View.VISIBLE
                val width = MeasureSpec.getSize(widthMeasureSpec)
                val horizontal = both && (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ||
                    width >= 2 * maxOf(first.preferredWidth(false), second.preferredWidth(false)))
                group.setRowOrientation(if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL)
                for (entry in entries) {
                    val row = entry.layoutParams as NativeGroupLayout.LayoutParams
                    row.width = if (horizontal) 0 else ViewGroup.LayoutParams.MATCH_PARENT
                    row.weight = if (horizontal) 1f else 0f
                    row.dividerStart = entry === second && both && horizontal
                    row.dividerTop = entry === second && both && !horizontal
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply { addView(group, FrameLayout.LayoutParams(-1, -2)) }
    }

    fun icon(glyph: Drawable?, label: String, action: () -> Unit) =
        button("", ReverbApplicationAction.ICON, action).apply {
            setLeadingIcon(glyph?.mutate())
            contentDescription = label
            tooltipText = label
            filterTouchesWhenObscured = true
        }

    fun toolbarIcon(glyph: Drawable, label: String, action: () -> Unit) = NativeTopBarAction(
        context, textDensity.density, inputRoot = { null }, description = label, selectionWrapper = true,
        rolePresent = true, initialTint = colors.muted, actionStyle = appearance.toolbars.action,
    ).apply {
        val toolbar = appearance.toolbars
        setSurfacePaletteImmediately(toolbar.actionFill(colors), toolbar.actionOutline(colors, NativeToolbarActionTone.NEUTRAL))
        setGlyph(glyph.mutate().apply { setTint(colors.muted) }, colors.muted)
        accessibilityAction.apply {
            tooltipText = label
            filterTouchesWhenObscured = true
        }
        bindToolbarIcon(this, enabled = true, action = action)
    }

    fun bindToolbarIcon(button: NativeTopBarAction, enabled: Boolean, action: () -> Unit) {
        button.bindSurfacePreset(colors.surfacePreset)
        button.bindAction(textDensity.density, enabled, colors.muted, appearance.toolbars.contentColor(colors), action)
    }

    fun toggle(label: String, changed: (Boolean) -> Unit) = toggle(text(label), changed)

    fun toggle(label: TextView, changed: (Boolean) -> Unit) = toggle(label, null, changed)

    fun toggle(label: TextView, detail: TextView?, changed: (Boolean) -> Unit) = NativeToggleRow(context,
        label, detail, NativeMaterialSwitchView(context, colors.switchPalette).apply {
            bindSurfacePreset(colors.surfacePreset)
        }, onChanged = changed).apply {
        filterTouchesWhenObscured = true
    }
    fun bindToggle(row: NativeToggleRow, checked: Boolean, enabled: Boolean = true) {
        row.bindSurfacePreset(colors.surfacePreset)
        row.bindToggle(checked, enabled, textDensity.density, colors.ink, colors.switchPalette)
    }

    fun settingsSegments(inputAllowed: () -> Boolean): NativeSegmentedControl =
        appearance.settingsSelectors.createSegmentedControl(
            context, colors.settingsControls, textDensity.density, inputAllowed,
        )

    fun selectorRow(label: String, selector: NativeSegmentedControl, titleAction: View? = null): View {
        val recipe = appearance.settingsSelectors
        val title = NativeText(context, componentPalette,
            nativeTextStyle(recipe.sectionTitle, NativeTypographyRole.TITLE_MEDIUM)).apply {
            text = label
            isAccessibilityHeading = true
        }
        val heading = if (titleAction == null) title else LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(titleAction, LinearLayout.LayoutParams(-2, -2))
        }
        val row = NativeLabeledSegmentedLayout(heading, selector)
        val layout = recipe.retentionSection
        return object : ViewGroup(context) {
            init {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(heading)
                addView(selector)
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec)
                val inset = dp(layout.sideInsetDp)
                row.measure((width - 2 * inset).coerceAtLeast(0), dp(layout.inlineGapDp),
                    dp(layout.stackedGapDp), preferredInlineWidth = 0)
                setMeasuredDimension(width, resolveSize(row.measuredHeight, heightMeasureSpec))
            }

            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                row.layout(width, dp(layout.sideInsetDp), 0, layoutDirection == View.LAYOUT_DIRECTION_RTL)
            }
        }
    }

    fun input() = NativeTextField(context, componentPalette)

    fun labeledInput(label: String, editor: NativeTextField) = NativeLabeledTextField(
        context, componentPalette, label, NativeLabeledTextFieldStyle(errorColor = colors.error), editor,
    )

    fun joinedInputs(vararg inputs: Pair<String, NativeTextField>): View {
        val recipe = appearance.joinedFields
        val fields = inputs.map { (label, editor) -> recipe.createField(context, colors, label, editor) }.toTypedArray()
        return recipe.createGroup(context, colors, *fields).view
    }
}
