package smallthingz.ui.components

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.common.DropdownContent
import smallthingz.ui.common.DropdownGroup
import smallthingz.ui.common.DropdownSelectionPolicy
import smallthingz.ui.common.dropdownColors
import smallthingz.ui.injector.InjectResponsiveChoiceGroup
import smallthingz.ui.text.NativeTypographyRole.BODY_LARGE
import smallthingz.ui.text.NativeTypographyRole.BODY_SMALL
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.roundToInt

/** Source fixture only until explicitly executed; no rendered or device qualification implied. */
@RunWith(AndroidJUnit4::class)
class NativeResponsiveChoiceGroupIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun columnBudgetFitsRealDropdownTextAtDifferentFontScales() = main {
        for (fontScale in listOf(1f, 2f)) for (dark in listOf(false, true)) {
            val base = instrumentation.targetContext
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                densityDpi = 160
                this.fontScale = fontScale
            })
            val theme = ControlTheme.resolve(context, dark)
            val caption = "Choice caption"
            val options = listOf("Short", "A deliberately longer choice")
            val width = dropdownColumnWidth(context, theme, caption, options, 0f)
            val title = ParagraphView(context, theme.appearance.typography[BODY_SMALL],
                theme.density, theme.fontResolver, theme.colors.muted).apply { setText(caption) }
            val value = ParagraphView(context, theme.appearance.typography[BODY_LARGE],
                theme.density, theme.fontResolver, theme.colors.ink).apply { setText(options.last()) }
            val dropdown = NativeDropdown<String>(context, title, value,
                createDropdownIcon(context, theme.colors.muted), theme.dropdownColors(),
                inputRoot = { null }, onBinding = { _, _ -> }, open = { _, _ -> })
            try {
                dropdown.measure(exact(width), unspecified())
                assertEquals(title.maxIntrinsicTextWidth(), title.measuredWidth)
                assertEquals(value.maxIntrinsicTextWidth(), value.measuredWidth)
                dropdown.measure(exact(width - 1), unspecified())
                assertTrue(title.measuredWidth < title.maxIntrinsicTextWidth() ||
                    value.measuredWidth < value.maxIntrinsicTextWidth())
                assertEquals(192, dropdownColumnWidth(context, theme, "", emptyList(), 192f))
            } finally {
                dropdown.close()
            }
        }
    }

    private class Choices(context: Context, theme: ControlTheme) : InjectResponsiveChoiceGroup(
        context, theme, "Choices", "Expand choices", "Collapse choices", minimumColumnWidthDp = 0f) {
        public override var choicesExpanded = false
        override val summaryEnabled = true
        override fun selectionSummary() = "First · A\nSecond · B"
    }

    @Test fun resizingRetainsAnchorsAndFocusAndKeepsPopupGeometryRetirement() {
        for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
                NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var cleanup: () -> Unit = {}
            try {
                lateinit var verify: () -> Unit
                main {
                    val appearance = NativeUiAppearance.Default.copy(anchoredDropdown =
                        NativeUiAppearance.Default.anchoredDropdown.copy(expandMillis = 0,
                            settleMillis = 0, collapseMillis = 0))
                    val theme = ControlTheme.resolve(activity, false, appearance)
                    val host = FrameLayout(activity).apply { isFocusableInTouchMode = true }
                    val group = DropdownGroup(theme, { host }, { true }, DropdownSelectionPolicy.EXPLICIT)
                    val deliveries = mutableListOf<String>()
                    val content = DropdownContent("Choice", "A", listOf("A", "B")) { it }
                    val first = group.field(activity, content) { deliveries += it }
                    val second = group.field(activity, content) { deliveries += it }
                    val controls = Choices(activity, theme).apply { layoutDirection = direction }
                    fun wrap(view: View) = FrameLayout(activity).apply {
                        addView(view, FrameLayout.LayoutParams(-1, -2))
                    }
                    val firstField = wrap(first.view)
                    val secondField = wrap(second.view)
                    for ((field, focus) in listOf(firstField to first.view, secondField to second.view)) {
                        controls.addChoiceView(field, focus, content.label, content.options,
                            popupOpen = { group.isExpanded }, bindPresentationEnabled = { enabled ->
                                focus.isEnabled = enabled
                                focus.isFocusable = enabled
                            })
                    }
                    val gap = (NativeScreenSpacing.CONTROL_GAP_DP * activity.resources.displayMetrics.density).roundToInt()
                    val column = dropdownColumnWidth(activity, theme, content.label, content.options, 0f)
                    val wide = column * 2 + gap
                    host.addView(controls, FrameLayout.LayoutParams(wide, -2))
                    activity.setContentView(host)
                    host.requestFocus()
                    cleanup = { controls.close(); first.close(); second.close(); group.close() }
                    verify = {
                        fun resize(width: Int) {
                            controls.layoutParams = (controls.layoutParams as FrameLayout.LayoutParams).apply {
                                this.width = width
                            }
                            controls.measure(exact(width), unspecified())
                            controls.layout(0, 0, width, controls.measuredHeight)
                        }
                        resize(wide)
                        val anchor = first.view
                        val parent = anchor.parent
                        val token = anchor.windowToken
                        var detaches = 0
                        anchor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(view: View) = Unit
                            override fun onViewDetachedFromWindow(view: View) { detaches++ }
                        })
                        assertTrue(anchor.requestFocusFromTouch())
                        resize(wide - 1)
                        assertTrue(controls.choicesExpanded)
                        assertTrue(anchor.hasFocus() && anchor.isShown && anchor.isEnabled)
                        assertSame(parent, anchor.parent)
                        assertSame(token, anchor.windowToken)
                        resize(wide)
                        assertTrue(anchor.hasFocus())
                        assertEquals(direction == View.LAYOUT_DIRECTION_RTL, firstField.left > secondField.left)

                        host.requestFocus()
                        controls.choicesExpanded = false
                        resize(wide - 1)
                        assertFalse(anchor.isShown)
                        val summary = dropdownIn(controls.getChildAt(0))!!
                        assertTrue(summary.requestFocusFromTouch())
                        resize(wide)
                        assertTrue(anchor.hasFocus() && anchor.isEnabled)

                        // An open popup also forces expanded narrow presentation. The native popup
                        // intentionally retires on changed anchor geometry at pre-draw; do not bypass
                        // that safety contract or recreate/reopen it during responsive layout.
                        controls.choicesExpanded = false
                        group.open(first, content)
                        assertTrue(group.isExpanded)
                        resize(wide - 1)
                        assertTrue(controls.choicesExpanded)
                        assertSame(parent, anchor.parent)
                        assertSame(anchor, first.view)
                        assertTrue(anchor.isShown && anchor.isEnabled)
                        anchor.viewTreeObserver.dispatchOnPreDraw()
                        assertFalse(group.isExpanded)
                        assertEquals(0, detaches)
                        assertTrue(deliveries.isEmpty())

                        firstField.visibility = View.GONE
                        controls.refreshPresentation()
                        assertTrue(second.view.isShown && second.view.isEnabled)
                        secondField.visibility = View.GONE
                        controls.refreshPresentation()
                        assertEquals(View.GONE, controls.visibility)
                    }
                }
                instrumentation.waitForIdleSync()
                main(verify)
            } finally {
                main { try { cleanup() } finally { activity.finish() } }
            }
        }
    }

    private fun dropdownIn(view: View): NativeDropdown<*>? {
        if (view is NativeDropdown<*>) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            dropdownIn(view.getChildAt(index))?.let { return it }
        }
        return null
    }
    private fun main(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
    private fun unspecified() = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
}
