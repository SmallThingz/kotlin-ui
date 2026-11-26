package app.smallthingz.dropdownviewportqa

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import smallthingz.ui.components.*
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import kotlin.math.roundToInt

/** Isolated interactive fixture: every control and dropdown is the real shared native UI. */
class MainActivity : Activity() {
    private var dark = false
    private var fullscreen = false
    private var rtl = false
    private var largeText = false
    private var longList = true
    private var selectedStart = 1
    private var anchorPosition = 1
    private var preview = false
    private var selection = 20
    private var dropdown: NativeAnchoredDropdown? = null
    private val owned = ArrayList<AutoCloseable>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        state?.let {
            dark = it.getBoolean("dark"); fullscreen = it.getBoolean("fullscreen")
            rtl = it.getBoolean("rtl"); largeText = it.getBoolean("largeText")
            longList = it.getBoolean("longList", true)
            selectedStart = it.getInt("selectedStart", 1); anchorPosition = it.getInt("anchorPosition", 1)
            preview = it.getBoolean("preview"); selection = it.getInt("selection", 20)
        }
        window.setDecorFitsSystemWindows(false)
        render()
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("dark", dark); state.putBoolean("fullscreen", fullscreen)
        state.putBoolean("rtl", rtl); state.putBoolean("largeText", largeText)
        state.putBoolean("longList", longList); state.putBoolean("preview", preview)
        state.putInt("selectedStart", selectedStart); state.putInt("anchorPosition", anchorPosition)
        state.putInt("selection", selection)
        super.onSaveInstanceState(state)
    }

    private fun release() {
        dropdown?.close(); dropdown = null
        owned.forEach { it.close() }; owned.clear()
    }
    override fun onStop() { dropdown?.close(); super.onStop() }
    override fun onDestroy() { release(); super.onDestroy() }
    @Deprecated("Activity back supports this API 30+ test fixture")
    override fun onBackPressed() {
        if (preview) { dropdown?.close(); preview = false; render() } else super.onBackPressed()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun render() {
        release()
        val appearance = NativeUiAppearance.Default
        val palette = appearance.resolveColorScheme(this, dark)
        val density = Density(resources.displayMetrics.density, resources.configuration.fontScale * if (largeText) 1.5f else 1f)
        val resolver = createFontFamilyResolver(this)
        val root = FrameLayout(this).apply {
            background = appearance.backdrops.pageBackground(palette)
            layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        // Keep the overlay host full size: shared dropdown code must respect visible system bars itself.
        val content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val edges = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            content.setPadding(edges.left, edges.top, edges.right, edges.bottom)
            insets
        }
        fun paragraph(text: String, title: Boolean = false) = ParagraphView(this,
            appearance.typography[if (title) NativeTypographyRole.TITLE_MEDIUM else NativeTypographyRole.BODY_MEDIUM],
            density, resolver, if (title) palette.ink else palette.muted).apply { setText(text) }
        fun button(text: String, action: () -> Unit) = NativeMaterialButtonView(this,
            appearance.buttons.text, density, resolver, onClick = action).apply {
            bind(text, true, palette.buttonPalette); owned += this
        }
        fun column() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        fun LinearLayout.action(text: String, action: () -> Unit) {
            addView(button(text, action), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        val positions = listOf("Top", "Middle", "Bottom")
        val starts = listOf("First", "Middle", "Last")
        val count = if (longList) 40 else 3
        if (!preview) {
            val page = column()
            page.addView(paragraph("Dropdown playground", title = true))
            page.addView(paragraph("Choose a scenario, then open the list. Look for a small arrow only where more choices are hidden."))
            page.action("List: ${if (longList) "40 choices" else "3 choices"}") { longList = !longList; render() }
            page.action("Start at: ${starts[selectedStart]}") { selectedStart = (selectedStart + 1) % 3; render() }
            page.action("Field position: ${positions[anchorPosition]}") { anchorPosition = (anchorPosition + 1) % 3; render() }
            page.action("Screen: ${if (fullscreen) "Fullscreen" else "Normal"}") { fullscreen = !fullscreen; render() }
            page.action("Theme: ${if (dark) "Dark" else "Light"}") { dark = !dark; render() }
            page.action("Direction: ${if (rtl) "Right to left" else "Left to right"}") { rtl = !rtl; render() }
            page.action("Text: ${if (largeText) "Large (150%)" else "System size"}") { largeText = !largeText; render() }
            page.action("Try this dropdown") {
                selection = when (selectedStart) { 0 -> 0; 2 -> count - 1; else -> count / 2 }
                preview = true; render()
            }
            val scroll = ScrollView(this).apply { isFillViewport = true; addView(page) }
            content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        } else {
            val popup = NativeAnchoredDropdown(this, root, smallthingz.ui.common.ControlTheme.resolve(this, dark, appearance)
                .withColorScheme(palette).withTypography(density, resolver))
            dropdown = popup
            val options = (0 until count).toList()
            fun optionLabel(value: Int) = "Choice ${value + 1} of $count"
            var value = NativeDropdownContent("Choose an option", selection.coerceIn(0, count - 1), options, ::optionLabel)
            val status = paragraph("Selected: ${optionLabel(value.selected)}")
            lateinit var field: NativeDropdown<Int>
            field = popup.createField(value) { selected ->
                selection = selected
                value = value.copy(selected = selected)
                field.bind(value, false, NativeDropdownColors(palette.field, palette.field, palette.muted, palette.ink, palette.border))
                status.setText("Selected: ${optionLabel(selected)}")
            }
            owned += field
            val explanation = column().apply {
                addView(paragraph("Try the list", title = true))
                addView(paragraph("$count choices · ${positions[anchorPosition].lowercase()} field · ${if (fullscreen) "fullscreen" else "normal screen"}"))
                addView(paragraph("Tap the field. Drag through choices; tap to close. Fades and arrows mark hidden choices. Android Back closes the list."))
                addView(status)
                action("Change scenario") { preview = false; render() }
            }
            content.addView(ScenarioLayout(this, field, ScrollView(this).apply { addView(explanation) },
                anchorPosition, dp(20), dp(16)), FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
        val controller = window.insetsController
        controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.setSystemBarsAppearance(
            if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        if (fullscreen) controller?.hide(WindowInsets.Type.systemBars()) else controller?.show(WindowInsets.Type.systemBars())
        root.requestApplyInsets()
    }
}

/** Instructions scroll only in the space outside the measured field, even in landscape/large text. */
private class ScenarioLayout(
    context: Context,
    private val field: View,
    private val instructions: View,
    private val position: Int,
    private val horizontalMargin: Int,
    private val gap: Int,
) : ViewGroup(context) {
    private var fieldTop = 0
    private var instructionTop = 0
    init { addView(field); addView(instructions) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        field.measure(MeasureSpec.makeMeasureSpec((width - 2 * horizontalMargin).coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        fieldTop = when (position) { 0 -> 0; 2 -> height - field.measuredHeight; else -> (height - field.measuredHeight) / 2 }
        instructionTop = if (position == 0) (field.measuredHeight + gap).coerceAtMost(height) else 0
        val instructionHeight = if (position == 0) height - instructionTop else (fieldTop - gap).coerceAtLeast(0)
        instructions.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(instructionHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        field.layout(horizontalMargin, fieldTop, horizontalMargin + field.measuredWidth, fieldTop + field.measuredHeight)
        instructions.layout(0, instructionTop, width, instructionTop + instructions.measuredHeight)
    }
}
