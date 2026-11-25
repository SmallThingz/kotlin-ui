package smallthingz.ui.alldoc

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeButtonRecipes
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeUiAppearance
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class AllDocHomeActionsIntegrationTest {
    @Test fun windowRecipesReachActionsAndTopBarWhileLegacyConstructorsKeepDefaults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
            val palette = NativeColorScheme.fallback(false).pageControls
            val default = NativeUiAppearance.Default
            val padding = default.buttons.neutralText.surface.horizontalPaddingDp
            val appearance = default.copy(buttons = NativeButtonRecipes(
                neutralText = default.buttons.neutralText.copy(
                    surface = default.buttons.neutralText.surface.copy(horizontalPaddingDp = padding + 17)),
                raisedIcon = default.buttons.raisedIcon.copy(
                    surface = default.buttons.raisedIcon.surface.copy(iconSizeDp = 12)),
            ))
            val owned = mutableListOf<AutoCloseable>()
            try {
                val retries = listOf(
                    AllDocHomeActions(context),
                    AllDocHomeActions(context, default),
                    AllDocHomeActions(context, appearance),
                ).map { actions ->
                    actions.retry("Retry loading this document", palette) {}.also { owned.add(it) }
                }
                for (retry in retries) {
                    retry.measure(View.MeasureSpec.makeMeasureSpec(dp(2000), View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    retry.layout(0, 0, retry.measuredWidth, retry.measuredHeight)
                }
                assertEquals(retries[0].width, retries[1].width)
                assertEquals(retries[0].height, retries[1].height)
                assertEquals(retries[0].label.width, retries[2].label.width)
                assertEquals(2 * (dp(padding + 17) - dp(padding)), retries[2].width - retries[0].width)

                val icons = List(3) { ColorDrawable(Color.WHITE) }
                // Keep positional calls and trailing callbacks compiling through both signatures.
                val legacy = AllDocHomeTopBar(context, palette,
                    "Licenses", "About", "Open", "Settings", null, null, icons[0], null,
                    {}, {}, {}) {}.also { owned.add(it) }
                val explicitDefault = AllDocHomeTopBar(context, palette, default,
                    "Licenses", "About", "Open", "Settings", null, null, icons[1], null,
                    {}, {}, {}) {}.also { owned.add(it) }
                val custom = AllDocHomeTopBar(context, palette, appearance,
                    "Licenses", "About", "Open", "Settings", null, null, icons[2], null,
                    {}, {}, {}) {}.also { owned.add(it) }
                for (bar in listOf(legacy, explicitDefault, custom)) {
                    bar.layoutDirection = View.LAYOUT_DIRECTION_LTR
                    bar.bindLayout(compact = false, stacked = false)
                    bar.measure(View.MeasureSpec.makeMeasureSpec(dp(320), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dp(64), View.MeasureSpec.EXACTLY))
                    bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)
                    assertEquals(dp(48), bar.open.width)
                    assertEquals(dp(48), bar.open.height)
                    assertEquals(bar.open.right, bar.settings.left)
                }
                assertEquals(dp(default.buttons.raisedIcon.surface.iconSizeDp), icons[0].bounds.width())
                assertEquals(icons[0].bounds, icons[1].bounds)
                assertEquals(dp(12), icons[2].bounds.width())
                assertEquals(dp(12), icons[2].bounds.height())
            } finally {
                owned.asReversed().forEach { it.close() }
            }
        }
    }
}
