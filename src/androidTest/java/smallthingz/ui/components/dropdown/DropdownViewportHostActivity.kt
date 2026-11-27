package smallthingz.ui.components.dropdown

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.components.NativeAnchoredDropdown
import smallthingz.ui.components.NativeDropdown
import smallthingz.ui.components.NativeDropdownColors
import smallthingz.ui.components.NativeDropdownContent
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import kotlin.math.roundToInt

/** Real inset and lifecycle ownership for the window, viewport, and fling regressions. */
@RequiresApi(Build.VERSION_CODES.R)
class DropdownViewportHostActivity : Activity() {
    private var dark = false
    private var selection = 0
    private var dropdown: NativeAnchoredDropdown? = null
    private var anchor: NativeDropdown<Int>? = null
    private val optionCount get() = if (intent.getBooleanExtra(SHORT_LIST, false)) 3 else 40

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        dark = state?.getBoolean("dark") ?: false
        selection = state?.getInt("selection") ?: optionCount / 2
        window.setDecorFitsSystemWindows(false)
        render()
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("dark", dark)
        state.putInt("selection", selection)
        super.onSaveInstanceState(state)
    }

    override fun onStop() {
        closeDropdown()
        super.onStop()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    internal fun closeDropdown() {
        dropdown?.close()
    }

    internal fun showDarkTheme() {
        dark = true
        render()
    }

    private fun release() {
        closeDropdown()
        anchor?.close()
        dropdown = null
        anchor = null
    }

    private fun render() {
        release()
        val appearance = NativeUiAppearance.Default
        val palette = appearance.resolveColorScheme(this, dark)
        val density = Density(this)
        val resolver = createFontFamilyResolver(this)
        val root = FrameLayout(this).apply {
            background = appearance.backdrops.pageBackground(palette)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        // Keep the overlay full size so that the library accounts for system bars itself.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val edges = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            content.setPadding(edges.left, edges.top, edges.right, edges.bottom)
            insets
        }

        val theme = ControlTheme.resolve(this, dark, appearance).withColorScheme(palette).withTypography(density, resolver)
        val popup = NativeAnchoredDropdown(this, root, theme)
        dropdown = popup
        val count = optionCount
        fun optionLabel(value: Int) = "Choice ${value + 1} of $count"
        var choice = NativeDropdownContent("Choose an option", selection, (0 until count).toList(), ::optionLabel)
        val status = ParagraphView(this, appearance.typography[NativeTypographyRole.BODY_MEDIUM],
            density, resolver, palette.muted).apply { setText("Selected: ${optionLabel(selection)}") }
        val horizontalMargin = (20 * resources.displayMetrics.density).roundToInt()
        content.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            setMargins(horizontalMargin, horizontalMargin, horizontalMargin, 0)
        })
        lateinit var field: NativeDropdown<Int>
        field = popup.createField(choice) { selected ->
            selection = selected
            choice = choice.copy(selected = selected)
            field.bind(choice, false, NativeDropdownColors(palette.field, palette.field, palette.muted, palette.ink, palette.border))
            status.setText("Selected: ${optionLabel(selected)}")
        }
        anchor = field
        val gravity = if (intent.getBooleanExtra(BOTTOM_FIELD, false)) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        content.addView(field, FrameLayout.LayoutParams(-1, -2, gravity).apply {
            setMargins(horizontalMargin, 0, horizontalMargin, 0)
        })
        setContentView(root)
        window.insetsController?.let { controller ->
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            controller.setSystemBarsAppearance(if (dark) 0 else lightBars, lightBars)
            if (intent.getBooleanExtra(FULLSCREEN, false)) controller.hide(WindowInsets.Type.systemBars())
            else controller.show(WindowInsets.Type.systemBars())
        }
        root.requestApplyInsets()
    }

    companion object {
        const val FULLSCREEN = "fullscreen"
        const val SHORT_LIST = "short_list"
        const val BOTTOM_FIELD = "bottom_field"
    }
}
