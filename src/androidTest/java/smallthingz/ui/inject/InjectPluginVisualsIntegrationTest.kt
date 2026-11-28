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
import smallthingz.ui.icons.MaterialRoundedIcon
import smallthingz.ui.icons.MaterialRoundedIconDrawable
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class InjectPluginVisualsIntegrationTest {
    @Test fun permissionStatusTextDefersIconsAndVisibleRebindUsesTheCurrentGrant() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (dark in listOf(false, true)) {
            val row = object : InjectPermissionRow(context, InjectDesign.theme(context, dark), ICON) {
                val statusIcon get() = trailing as ImageView
            }
            fun iconInitialized(name: String) = (InjectPermissionRow::class.java
                .getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(row) as Lazy<*>).isInitialized()
            var calls = 0
            fun bind(showStatus: Boolean, granted: Boolean) {
                row.bindStatus("Root", if (showStatus) "Waiting…" else if (granted) "Allowed" else "Not allowed",
                    showStatus, granted, enabled = true, requestEnabled = !showStatus,
                    requestLabel = "Allow", onRequest = { calls++ })
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, row.statusIcon.importantForAccessibility)
                assertEquals(!showStatus && !granted, row.canRequestNow)
            }

            bind(showStatus = true, granted = false)
            assertEquals(View.GONE, row.statusIcon.visibility)
            assertNull("Initial textual status must not construct the hidden icon", row.statusIcon.drawable)
            bind(showStatus = true, granted = true)
            assertNull("A hidden grant change must not construct the other icon either", row.statusIcon.drawable)
            assertFalse(iconInitialized("ungrantedIcon"))
            assertFalse(iconInitialized("grantedIcon"))

            bind(showStatus = false, granted = false)
            val ungranted = row.statusIcon.drawable as MaterialRoundedIconDrawable
            assertEquals(View.VISIBLE, row.statusIcon.visibility)
            assertEquals(MaterialRoundedIcon.RadioButtonUnchecked, ungranted.icon)
            assertTrue(iconInitialized("ungrantedIcon"))
            assertFalse(iconInitialized("grantedIcon"))

            bind(showStatus = true, granted = true)
            assertEquals(View.GONE, row.statusIcon.visibility)
            assertSame("Hidden state does not replace the retained drawable", ungranted, row.statusIcon.drawable)
            assertFalse(iconInitialized("grantedIcon"))
            bind(showStatus = false, granted = true)
            val granted = row.statusIcon.drawable as MaterialRoundedIconDrawable
            assertEquals(View.VISIBLE, row.statusIcon.visibility)
            assertEquals(MaterialRoundedIcon.CheckCircle, granted.icon)
            assertTrue(iconInitialized("grantedIcon"))

            bind(showStatus = true, granted = false)
            assertSame(granted, row.statusIcon.drawable)
            bind(showStatus = false, granted = false)
            assertEquals(View.VISIBLE, row.statusIcon.visibility)
            assertSame("Returning to an ungranted icon reuses its original drawable", ungranted, row.statusIcon.drawable)
            bind(showStatus = false, granted = true)
            assertSame("Returning to a granted icon reuses its original drawable", granted, row.statusIcon.drawable)
            assertEquals(0, calls)
        }
    }

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
