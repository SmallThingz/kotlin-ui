package smallthingz.ui.reverb

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.text.Spanned
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.descendants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.common.CredentialActionState
import smallthingz.ui.common.CredentialActionTarget
import smallthingz.ui.common.CredentialFieldAction
import smallthingz.ui.common.CredentialFieldGroup
import smallthingz.ui.common.CredentialFieldSpec
import smallthingz.ui.common.CredentialFieldState
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeLabeledTextField
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.roundToInt

/** Attached composition tests; no global input or live IME/TalkBack claims. Values are synthetic. */
@RunWith(AndroidJUnit4::class)
class ReverbControlScreenIntegrationTest {
    @Test fun retainedProviderEditorSurvivesInsetsResizeAndHiddenProviderDiscovery() = withFixture { f ->
        main {
            val editor = (f.first.view as ViewGroup).descendants.filterIsInstance<NativeLabeledTextField>().single().editor
            val editable = checkNotNull(editor.text)
            val composition = Any()
            editable.setSpan(composition, 1, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING)
            editor.setSelection(2, 5)
            assertTrue(editor.requestFocus())
            val parent = editor.parent

            f.frame.applySafeInsets(Insets.of(9, 13, 17, 80))
            f.second.view.visibility = View.VISIBLE
            f.frame.layoutDirection = View.LAYOUT_DIRECTION_RTL
            f.frame.bindTheme(f.environment.theme)
            f.measure(widthDp = 280, heightDp = 500)

            assertSame(parent, editor.parent)
            assertSame(editable, editor.text)
            assertTrue(editor.hasFocus())
            assertEquals(2, editor.selectionStart)
            assertEquals(5, editor.selectionEnd)
            assertEquals(1, editable.getSpanStart(composition))
            assertEquals(6, editable.getSpanEnd(composition))
            assertEquals("sample-draft", editable.toString())
            assertEquals(0, f.edits)
            assertTrue(f.first.view.isShown)
            assertTrue(f.second.view.isShown)
            assertSame(f.body, f.first.view.parent)
            assertSame(f.body, f.second.view.parent)
            assertEquals(9, f.frame.paddingLeft)
            assertEquals(17, f.frame.paddingRight)
            assertEquals(80, f.frame.paddingBottom)
        }
    }

    @Test fun connectionAncestorRetainsLiveInputAdmissionForAccessibilityActions() = withFixture { f ->
        main {
            val reveal = (f.first.view as ViewGroup).descendants.filterIsInstance<NativeMaterialButtonView>().single()
            f.allowed = false
            reveal.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
            assertEquals(0, f.actions)
            f.allowed = true
            assertTrue(reveal.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(1, f.actions)
            f.first.view.visibility = View.GONE
            assertFalse(reveal.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(1, f.actions)
            assertEquals(0, f.edits)
        }
    }

    @Test fun rtlLargeTextWarningIsCompleteLiveAndDoesNotLeaveAnInvisibleActionSlot() = withFixture(fontScale = 2f) { f ->
        main {
            val warning = "هناك تغييرات غير محفوظة. اضغط رجوع مرة أخرى لتجاهل التغييرات والخروج."
            f.frame.layoutDirection = View.LAYOUT_DIRECTION_RTL
            f.frame.status.text = warning
            f.frame.showStatus(show = true, actionRequired = true)
            f.frame.showPrimaryAction(false)
            f.frame.applySafeInsets(Insets.of(11, 19, 23, 31))
            f.measure(widthDp = 320, heightDp = 700)
            assertTrue(f.frame.status.lineCount > 2)
            assertNull(f.frame.status.ellipsize)
            assertTrue(f.frame.content.height > 0)
            assertTrue(f.frame.status.bottom <= (f.frame.status.parent as View).height)
            val node = f.frame.status.createAccessibilityNodeInfo()
            try {
                assertEquals(warning, node.text.toString())
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, node.liveRegion)
            } finally { node.recycle() }
            val textLayout = checkNotNull(f.frame.status.layout)
            for (line in 0 until textLayout.lineCount) assertEquals(0, textLayout.getEllipsisCount(line))
            assertEquals(View.GONE, f.primary.visibility)
            assertEquals(View.GONE, (f.primary.parent as View).visibility)

            val withoutAction = f.frame.content.height
            f.frame.showPrimaryAction(true)
            f.measure(widthDp = 320, heightDp = 700)
            val target = f.primary.parent as View
            assertTrue(target.height >= f.dp(48))
            assertTrue(f.frame.content.height < withoutAction)
            f.frame.showPrimaryAction(false)
            f.measure(widthDp = 320, heightDp = 700)
            assertEquals(withoutAction, f.frame.content.height)
        }
    }

    @Test fun sectionHeadingsAndDisclosureSampleTheirActualPaintedAncestors() {
        for (dark in listOf(false, true)) withFixture(dark = dark) { f ->
            main {
                val access = f.sections.access(TextView(f.context), "Access", TextView(f.context))
                f.frame.controlsContent.addView(access)
                val title = (access as ViewGroup).descendants.filterIsInstance<ParagraphView>().single()
                assertTrue(title.isAccessibilityHeading)
                assertEquals((f.frame.background as NoiseDrawable).paintedTextSurface, ReverbTextSurface.sample(title))
                assertNotEquals(0L, ReverbTextSurface.sample(title))

                val warning = f.sections.debuggingDisclosure("Debugging grants access", TextView(f.context))
                f.frame.controlsContent.addView(warning)
                val message = (warning as ViewGroup).descendants.filterIsInstance<ParagraphView>().single()
                assertNotNull(message.paintedTextSurface)
                assertEquals((warning as NativeGroupLayout).paintedTextSurface, ReverbTextSurface.sample(message))
                assertNotEquals(0L, ReverbTextSurface.sample(message))
            }
        }
    }

    private enum class Field { SECRET }
    private class Fixture(val context: Context, dark: Boolean) {
        var allowed = true
        var actions = 0
        var edits = 0
        val environment = ReverbEnvironment(context, dark).forSettings()
        val sections = ReverbControlScreenSections(context, environment)
        private val reveal = CredentialActionTarget(Field.SECRET, CredentialFieldAction.REVEAL)
        private fun fields() = CredentialFieldGroup(context, environment.theme.controls,
            listOf(CredentialFieldSpec(Field.SECRET, "Provider credential")), actions = setOf(reveal),
            onAction = { actions++ }, onEdit = { _, _ -> edits++ }).apply {
            bind(listOf(CredentialFieldState(Field.SECRET, "sample-draft")),
                listOf(CredentialActionState(reveal, "Show provider credential")))
            (view as ViewGroup).descendants.filterIsInstance<NativeLabeledTextField>().forEach { it.editor.showSoftInputOnFocus = false }
        }
        val first = fields()
        val second = fields().apply { view.visibility = View.GONE }
        val body = sections.connectionBody(first.view, second.view) { allowed }
        val primary = sections.action("Start", primary = true) {}
        val frame = ReverbControlScreenFrame(context, environment.theme,
            TextView(context).apply { visibility = View.GONE }, TextView(context).apply { text = "Settings" },
            ScrollView(context), View(context).apply { visibility = View.GONE },
            View(context).apply { visibility = View.GONE }, primary).apply {
            browserHost.visibility = View.GONE
            controlsContent.addView(sections.connection(TextView(context).apply { text = "Provider" }, body, null))
            showPrimaryAction(false)
        }
        fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
        fun measure(widthDp: Int, heightDp: Int) {
            frame.measure(View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(heightDp), View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
        }
        fun close() { first.close(); second.close(); primary.close() }
    }

    private fun withFixture(fontScale: Float = 1f, dark: Boolean = false, block: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var fixture: Fixture? = null
        try {
            main {
                val configured = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                    this.fontScale = fontScale
                })
                val context = ContextThemeWrapper(configured, android.R.style.Theme_Material_Light_NoActionBar)
                fixture = Fixture(context, dark).also { activity.setContentView(it.frame) }
            }
            val current = checkNotNull(fixture)
            val deadline = SystemClock.uptimeMillis() + 5_000
            var ready = false
            do {
                instrumentation.waitForIdleSync()
                main { ready = current.frame.isLaidOut && current.frame.hasWindowFocus() }
                if (!ready) Thread.sleep(16)
            } while (!ready && SystemClock.uptimeMillis() < deadline)
            assertTrue("Control frame did not acquire its attached window", ready)
            block(current)
        } finally {
            main { fixture?.close(); activity.finish() }
        }
    }

    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
