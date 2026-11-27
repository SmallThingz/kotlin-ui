package smallthingz.ui.reverb

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.text.Spanned
import android.view.ContextThemeWrapper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
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
import smallthingz.ui.controls.textSurfaceForeground
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.reverb.onboarding.OnboardingFooterPresentation
import smallthingz.ui.reverb.onboarding.OnboardingFooterView
import smallthingz.ui.reverb.onboarding.ReverbOnboardingScreenHost
import smallthingz.ui.reverb.settings.ReverbRetentionMode
import smallthingz.ui.reverb.settings.ReverbRetentionSection
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

    @Test fun reopeningSameOptionalPageRetiresHeldContinuePress() = withFixture { f ->
        main {
            var continued = 0
            val presentation = optionalFooterPresentation()
            f.frame.configureOnboardingFooter(f.environment.theme, {}, { continued++ })
            f.frame.bindOnboardingFooter(presentation)
            f.relayout()
            val button = continueButton(f)
            val bounds = Rect(0, 0, button.width, button.height)
            f.frame.offsetDescendantRectToMyCoords(button, bounds)

            val held = SystemClock.uptimeMillis()
            assertTrue(dispatchFooterTouch(f.frame, bounds, held, MotionEvent.ACTION_DOWN))
            f.frame.bindOnboardingFooter(null)
            assertFalse(button.isShown)
            f.frame.bindOnboardingFooter(presentation)
            f.relayout()
            assertSame(button, continueButton(f))
            assertTrue(button.isShown)
            dispatchFooterTouch(f.frame, bounds, held, MotionEvent.ACTION_UP)
            assertEquals("An earlier visit's press cannot continue this visit", 0, continued)

            val fresh = SystemClock.uptimeMillis()
            assertTrue(dispatchFooterTouch(f.frame, bounds, fresh, MotionEvent.ACTION_DOWN))
            dispatchFooterTouch(f.frame, bounds, fresh, MotionEvent.ACTION_UP)
            assertEquals(1, continued)

            val enabled = checkNotNull(button.createAccessibilityNodeInfo())
            assertEquals("android.widget.Button", enabled.className.toString())
            assertEquals(presentation.continueLabel, enabled.contentDescription.toString())
            assertTrue(enabled.isVisibleToUser && enabled.isEnabled)
            assertTrue(enabled.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })

            f.frame.bindOnboardingFooter(presentation.copy(continueEnabled = false))
            val disabled = checkNotNull(button.createAccessibilityNodeInfo())
            assertFalse(disabled.isEnabled)
            assertFalse(disabled.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            assertFalse(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(1, continued)
        }
    }

    @Test fun optionalFooterReconfigurationRetainsFocusedPageAndUpdatesActionOwner() = withFixture { f ->
        main {
            var original = 0
            var replacement = 0
            var afterThemeChange = 0
            f.frame.configureOnboardingFooter(f.environment.theme, {}, { original++ })
            f.frame.bindOnboardingFooter(optionalFooterPresentation())
            val competitor = EditText(f.context).apply {
                showSoftInputOnFocus = false
                setText("Another focus target")
            }
            f.frame.controlsContent.addView(competitor)
            f.relayout()

            val host = f.frame.content.descendants.filterIsInstance<ReverbOnboardingScreenHost>().single()
            val page = host.pageArea
            var detaches = 0
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) { detaches++ }
            }
            page.addOnAttachStateChangeListener(listener)
            try {
                val editor = (f.first.view as ViewGroup).descendants.filterIsInstance<NativeLabeledTextField>().single().editor
                val editable = checkNotNull(editor.text)
                assertTrue(competitor.isAttachedToWindow && competitor.isFocusable)
                assertTrue(editor.requestFocus())
                editor.setSelection(2, 5)
                val button = continueButton(f)

                fun assertRetainedEditor() {
                    assertEquals(0, detaches)
                    assertSame(host, page.parent)
                    assertTrue(page.isAttachedToWindow)
                    assertSame(editor, f.frame.findFocus())
                    assertSame(editable, editor.text)
                    assertEquals(2, editor.selectionStart)
                    assertEquals(5, editor.selectionEnd)
                }

                f.frame.configureOnboardingFooter(f.environment.theme, {}, { replacement++ })
                f.relayout()
                assertRetainedEditor()
                assertSame(button, continueButton(f))
                assertTrue(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(0, original)
                assertEquals(1, replacement)

                f.frame.configureOnboardingFooter(ReverbEnvironment(f.context, true).theme, {}, { afterThemeChange++ })
                f.relayout()
                assertRetainedEditor()
                val themedButton = continueButton(f)
                assertNotSame(button, themedButton)
                assertFalse(button.isAttachedToWindow)
                assertFalse(button.performClick())
                assertTrue(themedButton.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(0, original)
                assertEquals(1, replacement)
                assertEquals(1, afterThemeChange)
            } finally {
                page.removeOnAttachStateChangeListener(listener)
            }
        }
    }

    private fun optionalFooterPresentation() = OnboardingFooterPresentation(
        page = 0, backLabel = "Back", continueLabel = "Continue setup",
        backEnabled = false, continueEnabled = true,
    )

    private fun continueButton(f: Fixture): NativeMaterialButtonView =
        f.frame.content.descendants.filterIsInstance<OnboardingFooterView>().single()
            .descendants.filterIsInstance<NativeMaterialButtonView>().single { it.label.getText().toString() == "Continue setup" }

    private fun dispatchFooterTouch(frame: View, bounds: Rect, downTime: Long, action: Int): Boolean {
        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = bounds.exactCenterX()
            y = bounds.exactCenterY()
            pressure = 1f
            size = 1f
        }
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
            arrayOf(pointer), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        return try { frame.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun frameExposesNoPersistentStatusSlotOrCompatibilityApi() {
        // Removing the slot is an API contract: a hidden view or no-op setter is not a replacement.
        val type = ReverbControlScreenFrame::class.java
        val retiredMethods = setOf("getStatus", "getStatusTextView", "showStatus")
        assertFalse("Persistent status APIs must stay removed",
            type.methods.any { it.name in retiredMethods })
        assertFalse("A hidden persistent status view must not survive",
            type.declaredFields.any { it.name == "status" || it.name == "statusTextView" })
    }

    @Test fun rtlLargeTextContentUsesTheSafeViewportWithoutAStatusOrHiddenActionGap() = withFixture(fontScale = 2f) { f ->
        main {
            f.frame.layoutDirection = View.LAYOUT_DIRECTION_RTL
            f.frame.applySafeInsets(Insets.of(11, 19, 23, 31))
            fun contentBounds() = Rect(0, 0, f.frame.content.width, f.frame.content.height).also {
                f.frame.offsetDescendantRectToMyCoords(f.frame.content, it)
            }
            for ((width, height) in listOf(280 to 500, 320 to 700)) {
                f.frame.showPrimaryAction(false)
                f.frame.bindTheme(f.environment.theme)
                f.measure(widthDp = width, heightDp = height)
                val withoutAction = contentBounds()
                assertTrue(withoutAction.height() > 0)
                assertEquals(11, withoutAction.left)
                assertEquals(f.frame.width - 23, withoutAction.right)
                assertEquals("No status strip may consume the remaining page", f.frame.height - 31, withoutAction.bottom)
                assertEquals(View.GONE, f.primary.visibility)
                assertEquals(View.GONE, f.frame.primaryActionDock.visibility)

                f.frame.showPrimaryAction(true)
                f.measure(widthDp = width, heightDp = height)
                val dock = f.frame.primaryActionDock
                val withAction = contentBounds()
                assertTrue(dock.height >= f.dp(48))
                assertEquals(31, dock.safeBottomInset)
                assertEquals(0, f.frame.paddingBottom)
                assertEquals("Content must adjoin its action dock", dock.top, withAction.bottom)
                assertEquals(f.frame.height, dock.bottom)
                assertEquals(withoutAction.top, withAction.top)
                assertTrue(withAction.height() < withoutAction.height())

                f.frame.showPrimaryAction(false)
                f.measure(widthDp = width, heightDp = height)
                assertEquals("Hiding the action restores all page space", withoutAction, contentBounds())

                f.frame.browserHost.visibility = View.VISIBLE
                f.frame.showPrimaryAction(false)
                f.measure(widthDp = width, heightDp = height)
                assertEquals("Browser owns its bottom inset without a frame strip", f.frame.height, contentBounds().bottom)
                assertEquals(Insets.of(0, 0, 0, 31), f.frame.browserSafeInsets)
                f.frame.browserHost.visibility = View.GONE
                f.frame.showPrimaryAction(false)
                f.measure(widthDp = width, heightDp = height)
                assertEquals(withoutAction, contentBounds())
            }
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

    @Test fun retentionCallerInkTracksPagePaintAboveAnOpaqueGroup() = withFixture { f ->
        main {
            val page = f.frame.background as NoiseDrawable
            page.setBackgroundColor(Color.BLACK)
            // This public content slot has a caller-owned dependency, not nearest-group ink.
            val caller = object : TextView(f.context), ReverbTextSurfaceTarget {
                var refreshes = 0
                var sampledPage = page.paintedTextSurface
                override fun invalidateTextSurfaceInk() {
                    refreshes++
                    sampledPage = page.paintedTextSurface
                    setTextColor(textSurfaceForeground(sampledPage, Color.BLACK))
                }
            }.apply {
                text = "Page-dependent retention value"
                invalidateTextSurfaceInk()
            }
            val section = ReverbRetentionSection(f.context, ReverbRetentionMode.TIME, f.environment,
                { "Retention" }, { true }, { error("Paint changes must not request a mode") },
                caller, TextView(f.context).apply { text = "Looping value" })
            try {
                f.frame.controlsContent.addView(section)
                f.measure(widthDp = 320, heightDp = 700)
                assertTrue(caller.isAttachedToWindow && caller.isShown)
                assertTrue(caller.width > 0 && caller.height > 0)
                val surface = generateSequence(caller.parent as? View) { it.parent as? View }
                    .filterIsInstance<NativeGroupLayout>().first()
                val groupPaint = surface.paintedTextSurface
                assertEquals(255L, groupPaint ushr 56)
                assertEquals(255L, (groupPaint ushr 24) and 255L)
                assertEquals(1f, surface.alpha, 0f)

                for (color in listOf(Color.WHITE, Color.BLACK)) {
                    val previousPage = caller.sampledPage
                    val previousInk = caller.currentTextColor
                    val previousRefreshes = caller.refreshes
                    page.setBackgroundColor(color)
                    assertNotEquals(previousPage, page.paintedTextSurface)
                    ReverbTextSurface.invalidate(f.frame)
                    assertEquals("Page paint must reach the caller through the opaque group",
                        previousRefreshes + 1, caller.refreshes)
                    assertEquals(page.paintedTextSurface, caller.sampledPage)
                    assertNotEquals(previousInk, caller.currentTextColor)
                    assertEquals("Only page paint changed", groupPaint, surface.paintedTextSurface)
                }

                // Conservative traversal must also retain the surface's own paint callback.
                val previousRefreshes = caller.refreshes
                val nextFill = if (surface.paintedFillColor == Color.BLACK) Color.WHITE else Color.BLACK
                surface.setPaletteImmediately(nextFill, Color.TRANSPARENT)
                assertEquals(previousRefreshes + 1, caller.refreshes)
                assertEquals(page.paintedTextSurface, caller.sampledPage)
            } finally {
                f.frame.controlsContent.removeView(section)
                section.close()
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
        fun relayout() {
            frame.measure(View.MeasureSpec.makeMeasureSpec(frame.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(frame.height, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
        }
        fun close() {
            frame.closeOnboardingFooter()
            first.close()
            second.close()
            primary.close()
        }
    }

    private fun withFixture(fontScale: Float = 1f, dark: Boolean = false, block: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var fixture: Fixture? = null
        AutoCloseable {
            AutoCloseable { main { activity.finish() } }.use { main { fixture?.close() } }
        }.use {
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
        }
    }

    private fun main(block: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }
}
