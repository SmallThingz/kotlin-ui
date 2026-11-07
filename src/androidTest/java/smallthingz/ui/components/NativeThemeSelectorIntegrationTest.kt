package smallthingz.ui.components

import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.R
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.input.NativeAndroidInteropHost
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
            val selector = NativeThemeSelector(context, palette, NativeThemeMode.SYSTEM) { error("No input expected") }
            try {
                assertEquals(expected.first(), (selector.getChildAt(0) as TextView).text.toString())
                assertEquals(expected, listOf(context.getString(R.string.native_theme_title)) +
                    NativeThemeMode.entries.map { context.getString(it.labelRes) })
                val row = requireNotNull(choices(selector))
                assertEquals(expected.drop(1), NativeThemeMode.entries.map { row.choiceView(it.name)!!.text.toString() })
            } finally { selector.close() }
        }
    }

    @Test fun ownerBindingRetainsChoicesAndInteropRequests() = main {
        val context = instrumentation.targetContext
        val selector = NativeThemeSelector(context, palette, NativeThemeMode.SYSTEM) { error("Binding must be silent") }
        assertTrue(selector.getChildAt(1) is NativeSegmentedControl)
        val row = requireNotNull(choices(selector))
        val children = NativeThemeMode.entries.map { row.choiceView(it.name)!! }
        val parent = row.parent as NativeAndroidInteropHost
        var requested: Boolean? = null
        val old = parent.nativeInteropRequests.bind { error("Replaced request owner called") }
        val current = parent.nativeInteropRequests.bind { requested = it }
        old.close()
        row.requestDisallowInterceptTouchEvent(true)
        assertEquals(true, requested)
        NativeThemeMode.entries.forEach { selected ->
            selector.bind(selected, palette)
            NativeThemeMode.entries.forEachIndexed { index, mode ->
                assertSame(children[index], row.choiceView(mode.name))
                assertEquals(mode == selected, children[index].isChecked)
            }
        }
        val width = (360 * context.resources.displayMetrics.density).roundToInt()
        selector.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        selector.layout(0, 0, selector.measuredWidth, selector.measuredHeight)
        assertEquals((34 * context.resources.displayMetrics.density).roundToInt(), row.height)
        assertEquals((42 * context.resources.displayMetrics.density).roundToInt(), selector.getChildAt(1).height)
        assertFalse(row.activateOnFocusNavigation)
        current.close()
        selector.close()
    }

    @Test fun unattachedDisabledAndClosedOwnersRejectSelection() = main {
        val selector = NativeThemeSelector(instrumentation.targetContext, palette, NativeThemeMode.SYSTEM,
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
}
