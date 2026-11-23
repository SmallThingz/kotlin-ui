package smallthingz.ui.components

import android.content.Context
import android.graphics.Rect
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.R
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.input.NativeAndroidInteropHost
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import java.util.Locale
import kotlin.math.roundToInt

/** Real View/resource checks; this unattached fixture makes no window-input or pixel-parity claim. */
@RunWith(AndroidJUnit4::class)
class NativeThemeSelectorIntegrationTest {
    private val palette = ControlPalette(-1, -1, 0xff202020.toInt(), 0xff606060.toInt(),
        0xff3458a0.toInt(), -1, 0xff3458a0.toInt(), 0xffaaaaaa.toInt())
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun choices(view: View): NativeSegmentedChoice? = if (view is NativeSegmentedChoice) view else
        (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { choices(group.getChildAt(it)) } }

    @Test fun localizedIntrinsicCopyRetainsAllTransferredValues() = main {
        val labels = mapOf(
            "en" to listOf("Theme", "Auto", "Light", "Dark"),
            "ar" to listOf("السمة", "النظام", "فاتح", "داكن"),
            "de" to listOf("Design", "System", "Hell", "Dunkel"),
            "es" to listOf("Tema", "Sistema", "Claro", "Oscuro"),
            "fa" to listOf("پوسته", "سیستم", "روشن", "تیره"),
            "fr" to listOf("Thème", "Système", "Clair", "Sombre"),
            "hi" to listOf("थीम", "सिस्टम", "हल्की", "गहरी"),
            "id" to listOf("Tema", "Sistem", "Terang", "Gelap"),
            "it" to listOf("Tema", "Sistema", "Chiaro", "Scuro"),
            "ja" to listOf("テーマ", "システム", "ライト", "ダーク"),
            "ko" to listOf("테마", "시스템", "라이트", "다크"),
            "pt" to listOf("Tema", "Sistema", "Claro", "Escuro"),
            "ru" to listOf("Тема", "Системная", "Светлая", "Тёмная"),
            "tr" to listOf("Tema", "Sistem", "Açık", "Koyu"),
            "zh" to listOf("主题", "跟随系统", "浅色", "深色"),
        )
        labels.forEach { (language, expected) ->
            val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language)))
            }
            val context = instrumentation.targetContext.createConfigurationContext(config)
            val selector = createSelector(context) { error("No input expected") }
            try {
                assertEquals(expected.first(), (selector.getChildAt(0) as ParagraphView).getText())
                assertEquals(expected, listOf(context.getString(R.string.native_theme_title)) +
                    NativeThemeMode.entries.map { context.getString(it.labelRes) })
                val row = requireNotNull(choices(selector))
                assertEquals(expected.drop(1), NativeThemeMode.entries.map { row.choiceView(it.name)!!.text.toString() })
            } finally { selector.close() }
        }
    }

    @Test fun ownerBindingRetainsChoicesAndInteropRequests() = main {
        val context = instrumentation.targetContext
        val density = Density(context)
        val resolver = createFontFamilyResolver(context)
        val selector = NativeUiAppearance.Default.settingsSelectors.createThemeSelector(
            context, palette, NativeThemeMode.SYSTEM, density, resolver,
        ) { error("Binding must be silent") }
        val title = selector.getChildAt(0) as ParagraphView
        val capsule = selector.getChildAt(1) as NativeSegmentedControl
        val surface = capsule.getChildAt(0) as NativeGroupLayout
        val row = requireNotNull(choices(selector))
        val children = NativeThemeMode.entries.map { row.choiceView(it.name)!! }
        val parent = row.parent as NativeAndroidInteropHost
        var requested: Boolean? = null
        val old = parent.nativeInteropRequests.bind { error("Replaced request owner called") }
        val current = parent.nativeInteropRequests.bind { requested = it }
        old.close()
        row.requestDisallowInterceptTouchEvent(true)
        assertEquals(true, requested)
        val reboundPalette = palette.copy(surface = 0xffeee4da.toInt())
        NativeThemeMode.entries.forEach { selected ->
            selector.bind(selected, reboundPalette, density, resolver)
            assertSame(title, selector.getChildAt(0))
            assertSame(surface, capsule.getChildAt(0))
            NativeThemeMode.entries.forEachIndexed { index, mode ->
                assertSame(children[index], row.choiceView(mode.name))
                assertEquals(mode == selected, children[index].isChecked)
            }
        }
        assertEquals(reboundPalette.surface, surface.paintedFillColor)
        val width = (360 * context.resources.displayMetrics.density).roundToInt()
        selector.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        selector.layout(0, 0, selector.measuredWidth, selector.measuredHeight)
        assertEquals((34 * context.resources.displayMetrics.density).roundToInt(), row.height)
        assertEquals((42 * context.resources.displayMetrics.density).roundToInt(), selector.getChildAt(1).height)
        assertFalse(row.activateOnFocusNavigation)
        val settledCells = children.map { Rect(it.left, it.top, it.right, it.bottom) }
        selector.bind(NativeThemeMode.SYSTEM, reboundPalette, density, resolver)
        assertTrue(children.first().isChecked)
        assertFalse(children.last().isChecked)
        assertFalse("Only selection changed, so cell measurement remains valid", selector.isLayoutRequested)
        assertEquals(settledCells, children.map { Rect(it.left, it.top, it.right, it.bottom) })
        current.close()
        selector.close()
    }

    @Test fun unattachedDisabledAndClosedOwnersRejectSelection() = main {
        val selector = createSelector(instrumentation.targetContext,
            inputAllowed = { false }) { error("Inactive owner callback executed") }
        val row = requireNotNull(choices(selector))
        assertFalse(selector.acceptsNativeInput)
        assertFalse(row.onSelectionRequest!!.invoke(NativeThemeMode.DARK.name))
        selector.isEnabled = false
        assertFalse(row.choiceView(NativeThemeMode.LIGHT.name)!!.performClick())
        selector.close()
        selector.close()
        assertFalse(selector.acceptsNativeInput)
        assertEquals(NativeThemeMode.SYSTEM.name, row.selectedId)
    }

    @Test fun largeGermanLabelsStackAndKeepTheSameOptions() = main {
        val host = instrumentation.targetContext
        val context = host.createConfigurationContext(Configuration(host.resources.configuration).apply {
            setLocales(LocaleList(Locale.GERMAN))
            fontScale = 2f
        })
        val density = Density(context)
        val resolver = createFontFamilyResolver(context)
        val selector = NativeUiAppearance.Default.settingsSelectors.createThemeSelector(
            context, palette, NativeThemeMode.DARK, density, resolver,
        ) { error("Reflow must not request a selection") }
        try {
            selector.layoutDirection = View.LAYOUT_DIRECTION_RTL
            val title = selector.getChildAt(0)
            val capsule = selector.getChildAt(1)
            val row = requireNotNull(choices(selector))
            val options = NativeThemeMode.entries.map { requireNotNull(row.choiceView(it.name)) }
            val unconstrained = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            selector.measure(unconstrained, unconstrained)
            assertTrue(selector.measuredWidth > 0)
            fun layout(widthDp: Int) {
                val width = (widthDp * density.density).roundToInt()
                selector.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                selector.layout(0, 0, selector.measuredWidth, selector.measuredHeight)
            }
            layout(168)
            assertTrue(capsule.top >= title.bottom)
            assertTrue(options.map { it.top }.distinct().size > 1)
            for (option in options) {
                val textLayout = requireNotNull(option.layout)
                assertTrue(option.width > 0 && option.height > 0)
                assertEquals(option.text.length, textLayout.getLineEnd(textLayout.lineCount - 1))
                assertEquals(0, (0 until textLayout.lineCount).sumOf(textLayout::getEllipsisCount))
                assertTrue(textLayout.height <= option.height - option.compoundPaddingTop - option.compoundPaddingBottom)
            }
            layout(600)
            assertTrue(capsule.top < title.bottom)
            NativeThemeMode.entries.forEachIndexed { index, mode -> assertSame(options[index], row.choiceView(mode.name)) }
            assertEquals(NativeThemeMode.DARK.name, row.selectedId)
            assertTrue(options.last().isChecked)
        } finally { selector.close() }
    }

    private fun createSelector(
        context: Context,
        inputAllowed: () -> Boolean = { true },
        onSelectionRequest: (NativeThemeMode) -> Boolean,
    ): NativeThemeSelector = NativeUiAppearance.Default.settingsSelectors.createThemeSelector(
        context, palette, NativeThemeMode.SYSTEM, Density(context), createFontFamilyResolver(context),
        inputAllowed = inputAllowed, onSelectionRequest = onSelectionRequest,
    )
}
