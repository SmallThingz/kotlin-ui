package smallthingz.ui.inject

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.NativeSurface
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.components.withRetiringTestActivity
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class InjectPluginVisualsIntegrationTest {
    @Test fun permissionVariantKeepsStatusIndependentFromRequestAdmission() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (dark in listOf(false, true)) {
            val theme = InjectDesign.theme(context, dark)
            val row = object : smallthingz.ui.injector.InjectPermissionRow(context, theme, ICON) {
                val statusIcon get() = trailing as ImageView
            }
            var calls = 0
            row.bindStatus("Root", "Not allowed", false, false, false, false, "Allow", { calls++ })
            assertFalse(row.canRequestNow)
            assertEquals(.45f, row.statusIcon.alpha, 0f)
            row.bindStatus("Root", "Waiting…", true, false, true, false, "Allow", { calls++ })
            assertFalse(row.canRequestNow)
            assertEquals(View.GONE, row.statusIcon.visibility)
            row.bindStatus("Root", "Not allowed", false, false, true, true, "Allow", { calls++ })
            assertTrue(row.canRequestNow)
            assertFalse("Detached row cannot request permission", row.performClick())
            row.bindStatus("Root", "Allowed", false, true, false, false, "Allow", { calls++ })
            assertFalse(row.canRequestNow)
            assertEquals(1f, row.statusIcon.alpha, 0f)
            assertEquals(0, calls)
            assertEquals(theme.palette.copy(primaryContainer = Color.TRANSPARENT),
                InjectPluginVisuals.permissionPalette(theme.palette))
        }
    }

    @Test fun shownPermissionStatusIsExposedOnlyAsTheStandaloneState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var installed: InjectPermissionRow? = null
        withRetiringTestActivity(activity, root = { installed }) {
            main {
                val row = InjectPermissionRow(activity, InjectDesign.theme(activity, false), ICON)
                installed = row
                row.bindStatus("Root", "Waiting…", showStatus = true, granted = false,
                    enabled = true, requestEnabled = false, requestLabel = "Allow") {}
                activity.setContentView(row, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            instrumentation.waitForIdleSync()
            main {
                val row = checkNotNull(installed)
                val visibleStatus = descendants(row).filterIsInstance<ParagraphView>().single { it.getText() == "Waiting…" }
                assertEquals(View.VISIBLE, visibleStatus.visibility)
                val node = checkNotNull(row.createAccessibilityNodeInfo())
                assertTrue(node.isVisibleToUser)
                assertEquals("Root", node.contentDescription.toString())
                assertEquals("Waiting…", AccessibilityNodeInfoCompat.wrap(node).stateDescription.toString())
                assertFalse(node.isClickable)
            }
        }
    }

    @Test fun heroRetainsAccentRolesAndExactlyOneReadoutOrDescription() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (dark in listOf(false, true)) for (kind in listOf(null, InjectHeadlineKind.VALUE, InjectHeadlineKind.SUMMARY)) {
            val theme = InjectDesign.theme(context, dark)
            val hero = smallthingz.ui.injector.InjectPresentationHero(context, theme, ICON, kind, "Description")
            val text = descendants(hero.view).filterIsInstance<ParagraphView>().single()
            assertEquals(theme.palette.primaryContainer, hero.view.paintedFillColor)
            assertEquals(InjectPluginVisuals.heroFill(theme.palette), InjectPluginVisuals.heroBorder(theme.palette))
            val image = descendants(hero.view).filterIsInstance<ImageView>().single()
            assertEquals(theme.palette.onPrimary, image.imageTintList!!.defaultColor)
            assertEquals(theme.palette.primary, (image.background as GradientDrawable).color!!.defaultColor)
            assertEquals((InjectPluginVisuals.heroIconSizeDp * theme.density.density).roundToInt(), image.layoutParams.width)
            hero.bindHeadline("42", "Accessible value")
            assertEquals(if (kind == null) "Description" else "42", text.getText())
            if (kind != null) {
                assertEquals("Accessible value", text.contentDescription)
                hero.bindHeadline("43")
                assertNull(text.contentDescription)
            }
        }
    }

    @Test fun identityVariantsUseSharedToolbarRolesAndRetainHeadingAndIconGeometry() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (dark in listOf(false, true)) for (button in listOf(false, true)) for (showTitle in listOf(false, true)) {
            val theme = InjectDesign.theme(context, dark)
            val identity = smallthingz.ui.injector.InjectPluginIdentityView(context, theme, ICON,
                "Plugin".takeIf { showTitle }, button)
            val image = descendants(identity).filterIsInstance<ImageView>().single()
            val headings = descendants(identity).filterIsInstance<ParagraphView>()
            assertEquals(if (showTitle) 1 else 0, headings.size)
            headings.forEach { assertTrue(it.isAccessibilityHeading); assertEquals("Plugin", it.getText()) }
            val size = (InjectPluginVisuals.identitySizeDp * theme.density.density).roundToInt()
            assertEquals(size, identity.getChildAt(0).layoutParams.width)
            assertEquals((InjectPluginVisuals.identitySizeDp * theme.density.density / 4).roundToInt(), image.paddingLeft)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, image.importantForAccessibility)
            if (button) {
                val surface = identity.getChildAt(0) as NativeSurface
                assertEquals(InjectDesign.appearance.toolbars.actionFill(theme.palette), surface.paintedFillColor)
                assertEquals(InjectDesign.appearance.toolbars.actionOutline(theme.palette, NativeToolbarActionTone.MUTED),
                    InjectPluginVisuals.identityOutline(theme.palette))
            } else assertEquals(theme.palette.primaryContainer, (image.background as GradientDrawable).color!!.defaultColor)
        }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
    }
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private companion object { const val ICON = android.R.drawable.sym_def_app_icon }
}
