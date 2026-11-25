package smallthingz.ui.components

import android.content.res.Configuration
import android.graphics.Rect
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.R
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import java.util.Locale
import kotlin.math.roundToInt

/** Unattached real Views; local resources only, with no Activity or global configuration writes. */
@RunWith(AndroidJUnit4::class)
class NativeThemeSelectorInsetIntegrationTest {
    @Test fun sectionHostOwnsExactlyOneGutterWithoutChangingChoiceContent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (fontScale in listOf(1f, 2f)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    densityDpi = 421
                    this.fontScale = fontScale
                    setLocales(LocaleList(Locale.ENGLISH))
                }
                val context = instrumentation.targetContext.createConfigurationContext(config)
                val density = Density(context)
                val resolver = createFontFamilyResolver(context)
                val palette = ControlPalette(-1, -1, 0xff202020.toInt(), 0xff606060.toInt(),
                    0xff3458a0.toInt(), -1, 0xff3458a0.toInt(), 0xffaaaaaa.toInt())
                val style = NativeUiAppearance.Default.settingsSelectors
                val gutter = (16 * density.density).roundToInt()
                var inputAllowed = true
                var requests = 0
                val full = style.createThemeSelector(context, palette, NativeThemeMode.DARK, density, resolver,
                    inputAllowed = { inputAllowed }, onSelectionRequest = { requests++; false })
                val inset = style.createThemeSelectorInInsetSection(context, palette, NativeThemeMode.DARK,
                    density, resolver, inputAllowed = { inputAllowed },
                    onSelectionRequest = { requests++; false })
                val host = FrameLayout(context).apply {
                    setPaddingRelative(gutter, 0, gutter, 0)
                    addView(inset, FrameLayout.LayoutParams(-1, -2))
                }
                try {
                    val fullTitle = full.getChildAt(0) as ParagraphView
                    val insetTitle = inset.getChildAt(0) as ParagraphView
                    val fullCapsule = full.getChildAt(1) as NativeSegmentedControl
                    val insetCapsule = inset.getChildAt(1) as NativeSegmentedControl
                    val fullChoices = requireNotNull(choices(full))
                    val insetChoices = requireNotNull(choices(inset))
                    val retained = NativeThemeMode.entries.map { insetChoices.choiceView(it.name)!! }
                    for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                        full.layoutDirection = direction
                        host.layoutDirection = direction
                        inset.layoutDirection = direction
                        // Equal usable widths: full-width factory owns16; inset host owns16, row owns0.
                        for (widthDp in listOf(168, 600, 168)) {
                            val width = (widthDp * density.density).roundToInt()
                            measureAndLayout(full, width)
                            measureAndLayout(host, width)
                            assertEquals(full.height, host.height)
                            assertEquals(gutter, logicalStart(full, fullTitle, direction))
                            assertEquals(0, logicalStart(inset, insetTitle, direction))
                            assertEquals(gutter, if (direction == View.LAYOUT_DIRECTION_RTL)
                                fullCapsule.left else full.width - fullCapsule.right)
                            assertEquals(0, if (direction == View.LAYOUT_DIRECTION_RTL)
                                insetCapsule.left else inset.width - insetCapsule.right)
                            assertEquals(bounds(full, fullTitle), bounds(host, insetTitle))
                            assertEquals(bounds(full, fullCapsule), bounds(host, insetCapsule))
                            assertFalse(Rect.intersects(bounds(full, fullTitle), bounds(full, fullCapsule)))
                            assertTrue(fullCapsule.width > 0 && fullCapsule.height > 0)
                            assertEquals(context.getString(R.string.native_theme_title), insetTitle.getText())
                            assertEquals(fullTitle.getText(), insetTitle.getText())
                            NativeThemeMode.entries.forEachIndexed { index, mode ->
                                val original = fullChoices.choiceView(mode.name)!!
                                val embedded = insetChoices.choiceView(mode.name)!!
                                assertSame(retained[index], embedded)
                                assertEquals(bounds(full, original), bounds(host, embedded))
                                assertEquals(context.getString(mode.labelRes), embedded.text.toString())
                                assertEquals(original.paddingStart, embedded.paddingStart)
                                assertEquals(original.paddingEnd, embedded.paddingEnd)
                                assertEquals(original.compoundDrawablePadding, embedded.compoundDrawablePadding)
                                assertTrue(embedded.paddingStart > 0 && embedded.paddingEnd > 0)
                                assertTrue(embedded.compoundDrawablesRelative.any { it is NativeThemeIconDrawable })
                                assertEquals(mode == NativeThemeMode.DARK, embedded.isChecked)
                            }
                        }
                    }
                    inputAllowed = false
                    assertFalse(full.acceptsNativeInput)
                    assertFalse(inset.acceptsNativeInput)
                    inputAllowed = true
                    // Admission still requires an attached, visible, focused window.
                    assertFalse(full.acceptsNativeInput)
                    assertFalse(inset.acceptsNativeInput)
                    inset.bind(NativeThemeMode.SYSTEM, palette, density, resolver)
                    assertTrue(insetChoices.choiceView(NativeThemeMode.SYSTEM.name)!!.isChecked)
                    assertEquals(0, requests)
                } finally {
                    full.close()
                    inset.close()
                    host.removeAllViews()
                }
            }
        }
    }

    private fun choices(view: View): NativeSegmentedChoice? = if (view is NativeSegmentedChoice) view else
        (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).firstNotNullOfOrNull { choices(group.getChildAt(it)) }
        }

    private fun measureAndLayout(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun logicalStart(parent: View, child: View, direction: Int): Int =
        if (direction == View.LAYOUT_DIRECTION_RTL) parent.width - child.right else child.left

    private fun bounds(root: ViewGroup, child: View): Rect = Rect(0, 0, child.width, child.height).also {
        root.offsetDescendantRectToMyCoords(child, it)
    }
}
