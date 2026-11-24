package smallthingz.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.common.SegmentOption
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbHeaderAction
import smallthingz.ui.reverb.ReverbSettingsTopBar
import smallthingz.ui.reverb.ReverbStandardIconButtonView
import smallthingz.ui.reverb.ReverbTextSurface
import smallthingz.ui.reverb.ReverbTextSurfaceScope
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.reverb.createReverbSupportingParagraph
import smallthingz.ui.reverb.settings.ReverbLabeledSegmentedSelector
import smallthingz.ui.reverb.settings.ReverbSelectorHelp
import smallthingz.ui.reverb.settings.ReverbSettingsScreenLayout
import smallthingz.ui.reverb.settings.ReverbSettingsSection
import smallthingz.ui.reverb.settings.ReverbSettingsSectionFactory
import smallthingz.ui.reverb.settings.ReverbSettingsSectionKey
import smallthingz.ui.reverb.settings.ReverbSettingsSectionListView
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ReverbLabeledSegmentedSelectorIntegrationTest {
    private enum class Mode { TIME, SIZE, AUTOMATIC }
    private val options = listOf(
        SegmentOption(Mode.TIME, "Time"),
        SegmentOption(Mode.SIZE, "Size"),
        SegmentOption(Mode.AUTOMATIC, "Automatic", enabled = false, disabledReason = "Requires setup"),
    )
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun settingsPaletteDarkeningPreservesOpaqueAlphaAndExistingRgb() = main {
        val context = instrumentation.targetContext
        for (dark in listOf(false, true)) {
            val base = NativeColorScheme.resolve(dark, Build.VERSION.SDK_INT) { id ->
                context.resources.getColor(id, context.theme)
            }
            val palette = ReverbDesign.appearance.resolveColorScheme(context, dark)
            for ((role, original, actual) in listOf(
                Triple("background", base.background, palette.background),
                Triple("surface", base.surface, palette.surface),
            )) {
                assertEquals("$role input is opaque, dark=$dark", 255, original ushr 24)
                // blendARGB truncates 255*(1-.04f)+255*.04f from 254.99998 to 254. The
                // selector's painted-surface sampler correctly rejects that translucent fill.
                assertEquals("$role darkening must preserve opacity, dark=$dark", 255, actual ushr 24)
                assertEquals("$role darkening must preserve the published RGB recipe, dark=$dark",
                    ColorUtils.blendARGB(original, Color.BLACK, .04f) and 0x00ffffff, actual and 0x00ffffff)
            }
        }
    }

    @Test fun retainedChoicesBindSilentlyAndReportOnlyCurrentDisabledReasons() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val requests = mutableListOf<Mode>()
        val options = listOf(
            SegmentOption(Mode.TIME, "Time"),
            SegmentOption(Mode.SIZE, "Size"),
            SegmentOption(Mode.AUTOMATIC, "Automatic", enabled = false, disabledReason = "Requires setup"),
        )
        lateinit var environment: ReverbEnvironment
        var installed: ReverbLabeledSegmentedSelector<Mode>? = null
        try {
            instrumentation.runOnMainSync {
                environment = ReverbEnvironment(activity, false).forSettings()
                val selector = ReverbLabeledSegmentedSelector(activity, environment, "Retention", options, Mode.TIME,
                    enabled = false, inputAllowed = { true }) { requests.add(it) }
                installed = selector
                activity.setContentView(selector, ViewGroup.LayoutParams(-1, -2))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val selector = requireNotNull(installed)
                assertTrue(selector.hasWindowFocus())
                val time = selector.chip("Time")
                val size = selector.chip("Size")
                val automatic = selector.chip("Automatic")
                assertTrue(time.isChecked)
                assertFalse(time.isEnabled)
                assertFalse(size.isEnabled)
                assertFalse(size.performClick())
                assertEquals(View.GONE, selector.helpButton().visibility)
                assertEquals(View.GONE, selector.reason().visibility)

                selector.bind("Retention", options, Mode.SIZE, true, environment)
                assertSame(size, selector.chip("Size"))
                assertSame(automatic, selector.chip("Automatic"))
                assertFalse(time.isChecked)
                assertTrue(size.isChecked)
                assertTrue(time.isEnabled)
                assertTrue(size.isEnabled)
                assertFalse(automatic.isEnabled)
                assertFalse(automatic.performClick())
                assertEquals("Automatic. Requires setup",
                    automatic.createAccessibilityNodeInfo().contentDescription?.toString())
                assertNull(time.createAccessibilityNodeInfo().contentDescription)
                assertTrue(requests.isEmpty())

                val available = options.map { if (it.value == Mode.AUTOMATIC) it.copy(enabled = true) else it }
                selector.bind("Retention", available, Mode.SIZE, true, environment)
                assertTrue(automatic.isEnabled)
                assertEquals("Automatic", automatic.createAccessibilityNodeInfo().text?.toString())
                assertNull(automatic.createAccessibilityNodeInfo().contentDescription)
                assertTrue(requests.isEmpty())

                assertTrue(automatic.performClick())
                assertEquals(listOf(Mode.AUTOMATIC), requests)
                assertTrue(size.isChecked)
                selector.close()
                assertFalse(automatic.performClick())
                assertEquals(listOf(Mode.AUTOMATIC), requests)
            }
        } finally {
            instrumentation.runOnMainSync {
                installed?.close()
                activity.finish()
            }
        }
    }

    @Test fun legacyPositionalConstructorAndBindRemainSilent() = withSelector { fixture ->
        main {
            val legacyCalls = listOf(
                ReverbLabeledSegmentedSelector(fixture.activity, fixture.environment,
                    "Modes", options, Mode.TIME, true, { true }, { error("Binding must be silent") }),
                ReverbLabeledSegmentedSelector(fixture.activity, fixture.environment,
                    "Modes", options, Mode.TIME, inputAllowed = { true }) { error("Binding must be silent") },
            )
            for (positional in legacyCalls) {
                fixture.root.addView(positional)
                try {
                    assertTrue(positional.chip("Size").isEnabled)
                    positional.bind("Modes", options, Mode.SIZE, false, fixture.environment)
                    assertTrue(positional.isAttachedToWindow)
                    assertTrue(positional.chip("Size").isChecked)
                    assertFalse(positional.chip("Size").isEnabled)
                    assertEquals(View.GONE, positional.helpButton().visibility)
                    assertEquals(View.GONE, positional.reason().visibility)
                } finally {
                    positional.close()
                    fixture.root.removeView(positional)
                }
            }
        }
    }

    @Test fun disabledReasonHasOneSpokenOwnerAndTracksEnabledEmptyAndUpdatedState() =
        withSelector(enabled = false, disabledReason = "Requires setup") { fixture ->
            main {
                val selector = fixture.selector
                val reason = selector.reason()
                assertTrue(selector.isAttachedToWindow && selector.hasWindowFocus())
                assertEquals(View.VISIBLE, reason.visibility)
                assertEquals("Requires setup", reason.createAccessibilityNodeInfo()?.text?.toString())
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, reason.importantForAccessibility)
                assertNull(selector.contentDescription)
                assertTrue(selector.descendants().filterIsInstance<NativeChip>().all {
                    it.createAccessibilityNodeInfo().contentDescription == null
                })
                assertTrue(reason in selector.accessibilityChildren())
                val visibleHeight = selector.height

                selector.bind("Retention", options, Mode.TIME, false, fixture.environment,
                    disabledReason = "Paused by owner")
                assertSame(reason, selector.reason())
                assertEquals("Paused by owner", reason.getText())
                assertEquals("Automatic. Requires setup",
                    selector.chip("Automatic").createAccessibilityNodeInfo().contentDescription?.toString())

                for (empty in listOf(null, "", "  \n ")) {
                    selector.bind("Retention", options, Mode.TIME, false, fixture.environment,
                        disabledReason = empty)
                    selector.layoutAtWidth(selector.width)
                    assertEquals(View.GONE, reason.visibility)
                    assertEquals("", reason.getText())
                    assertFalse(reason in selector.accessibilityChildren())
                    assertTrue(selector.height < visibleHeight)
                }

                selector.bind("Retention", options, Mode.TIME, true, fixture.environment,
                    disabledReason = "Paused by owner")
                assertEquals(View.GONE, reason.visibility)
                assertTrue(selector.chip("Time").isEnabled)
                selector.bind("Retention", options, Mode.SIZE, false, fixture.environment)
                assertEquals(View.VISIBLE, reason.visibility)
                assertEquals("Paused by owner", reason.getText())
                assertTrue(fixture.requests.isEmpty())
            }
        }

    @Test fun helpUsesCurrentLocalizedActionAndRetiresHiddenDisabledAndClosedInput() {
        var oldCalls = 0
        var currentCalls = 0
        var admitted = true
        val original = ReverbSelectorHelp("Hilfe") { oldCalls++ }
        val current = ReverbSelectorHelp("Aide") { currentCalls++ }
        withSelector(enabled = false, help = original, inputAllowed = { admitted }) { fixture ->
            main {
                val selector = fixture.selector
                val help = selector.helpButton()
                val action = help.visual
                val node = requireNotNull(action.createAccessibilityNodeInfo())
                assertEquals("Hilfe", node.contentDescription?.toString())
                assertEquals("android.widget.Button", node.className?.toString())
                assertTrue(node.isEnabled)
                assertEquals(0, node.childCount)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, action.glyph.importantForAccessibility)
                assertNull(help.background)
                assertNull(action.background)
                assertFalse(selector.chip("Time").isEnabled)
                assertTrue(action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(1, oldCalls)

                action.isFocusableInTouchMode = true
                assertTrue(action.requestFocus())
                assertTrue(action.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
                selector.bind("Retention", options, Mode.SIZE, false, fixture.environment, help = current)
                assertSame(help, selector.helpButton())
                assertTrue(action.isFocused)
                assertEquals("Aide", action.createAccessibilityNodeInfo()?.contentDescription?.toString())
                assertTrue(action.dispatchKeyEvent(key(KeyEvent.ACTION_UP)))
                assertEquals(1, oldCalls)
                assertEquals(1, currentCalls)

                assertTrue(action.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
                selector.bind("Retention", options, Mode.SIZE, false, fixture.environment,
                    help = current.copy(enabled = false))
                action.dispatchKeyEvent(key(KeyEvent.ACTION_UP))
                assertEquals(View.VISIBLE, help.visibility)
                assertFalse(action.isFocused)
                assertFalse(action.isFocusable)
                assertFalse(requireNotNull(action.createAccessibilityNodeInfo()).isEnabled)
                assertFalse(action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                action.performClick()
                assertEquals(1, currentCalls)

                selector.bind("Retention", options, Mode.SIZE, false, fixture.environment, help = current)
                assertTrue(action.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
                admitted = false
                action.dispatchKeyEvent(key(KeyEvent.ACTION_UP))
                action.performClick()
                action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                assertEquals(1, currentCalls)
                admitted = true

                assertTrue(action.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
                selector.bind("Retention", options, Mode.SIZE, false, fixture.environment, help = null)
                assertEquals(View.GONE, help.visibility)
                assertNull(action.createAccessibilityNodeInfo())
                action.performClick()
                selector.bind("Retention", options, Mode.SIZE, false, fixture.environment, help = current)
                action.dispatchKeyEvent(key(KeyEvent.ACTION_UP))
                assertEquals(1, currentCalls)

                assertTrue(action.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
                selector.visibility = View.GONE
                action.performClick()
                selector.visibility = View.VISIBLE
                action.dispatchKeyEvent(key(KeyEvent.ACTION_UP))
                assertEquals(1, currentCalls)
                assertTrue(action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(2, currentCalls)

                action.isFocusableInTouchMode = true
                assertTrue(action.requestFocus())
                assertTrue(action.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
                selector.close()
                selector.close()
                action.dispatchKeyEvent(key(KeyEvent.ACTION_UP))
                action.performClick()
                selector.bind("Should not bind", options, Mode.TIME, true, fixture.environment, help = original)
                assertFalse(action.isFocused)
                assertFalse(action.isFocusable)
                assertNull(action.createAccessibilityNodeInfo())
                assertEquals(View.GONE, help.visibility)
                assertFalse(selector.chip("Time").performClick())
                assertEquals(1, oldCalls)
                assertEquals(2, currentCalls)
                assertTrue(fixture.requests.isEmpty())
            }
        }
    }

    @Test fun helpChecksCurrentAncestorAdmissionForClickAccessibilityAndKeyboard() {
        var admitted = false
        var calls = 0
        withSelector(enabled = false, help = ReverbSelectorHelp("Help") { calls++ },
            ancestorInputAllowed = { admitted }) { fixture ->
            main {
                val selector = fixture.selector
                val action = selector.helpButton().visual
                assertTrue(selector.isAttachedToWindow && selector.hasWindowFocus())
                assertFalse(selector.chip("Time").isEnabled)
                assertTrue(action.isEnabled)
                assertTrue(requireNotNull(action.createAccessibilityNodeInfo()).isEnabled)
                action.isFocusableInTouchMode = true
                assertTrue(action.requestFocus())

                action.performClick()
                assertEquals(0, calls)
                action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                assertEquals(0, calls)
                assertTrue(action.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
                assertTrue(action.dispatchKeyEvent(key(KeyEvent.ACTION_UP)))
                assertEquals(0, calls)

                // Gate changes take effect without rebinding or disabling the independent Help action.
                admitted = true
                assertTrue(action.performClick())
                assertEquals(1, calls)
                assertTrue(action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(2, calls)
                assertTrue(action.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
                admitted = false
                assertTrue(action.dispatchKeyEvent(key(KeyEvent.ACTION_UP)))
                assertEquals(2, calls)

                admitted = true
                assertTrue(action.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
                assertTrue(action.dispatchKeyEvent(key(KeyEvent.ACTION_UP)))
                assertEquals(3, calls)
                assertTrue(action.isEnabled)
                assertFalse(selector.chip("Time").isEnabled)
                assertTrue(fixture.requests.isEmpty())
            }
        }
    }

    @Test fun helpRechecksVisibilityWhenAdmissionRetiresItsOwner() {
        for (retireInAncestor in listOf(false, true)) {
            var calls = 0
            var retire: (() -> Unit)? = null
            withSelector(help = ReverbSelectorHelp("Help") { calls++ },
                inputAllowed = { if (!retireInAncestor) retire?.invoke(); true },
                ancestorInputAllowed = { if (retireInAncestor) retire?.invoke(); true },
            ) { fixture ->
                main {
                    val selector = fixture.selector
                    val action = selector.helpButton().visual
                    retire = { selector.visibility = View.GONE }
                    action.performClick()
                    assertEquals(View.GONE, selector.visibility)
                    assertEquals(0, calls)
                    selector.visibility = View.VISIBLE
                    action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                    assertEquals(View.GONE, selector.visibility)
                    assertEquals(0, calls)
                    selector.visibility = View.VISIBLE
                    action.isFocusableInTouchMode = true
                    assertTrue(action.requestFocus())
                    assertTrue(action.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
                    action.dispatchKeyEvent(key(KeyEvent.ACTION_UP))
                    assertEquals(View.GONE, selector.visibility)
                    assertEquals(0, calls)
                    retire = null
                    selector.visibility = View.VISIBLE
                    action.performClick()
                    assertEquals(1, calls)
                }
            }
        }
    }

    @Test fun detachingHelpRetiresPendingKeyBeforeReattachment() {
        var calls = 0
        withSelector(help = ReverbSelectorHelp("Help") { calls++ }) { fixture ->
            main {
                val selector = fixture.selector
                val action = selector.helpButton().visual
                assertTrue(action.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
                fixture.root.removeView(selector)
                assertFalse(selector.isAttachedToWindow)
                action.performClick()
                fixture.root.addView(selector)
            }
            instrumentation.waitForIdleSync()
            main {
                val action = fixture.selector.helpButton().visual
                action.dispatchKeyEvent(key(KeyEvent.ACTION_UP))
                assertEquals(0, calls)
                assertTrue(action.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
                assertTrue(action.dispatchKeyEvent(key(KeyEvent.ACTION_UP)))
                assertEquals(1, calls)
            }
        }
    }

    @Test fun helpAndReasonReflowAcrossNarrowRtlAndThemeChanges() = withSelector(
        enabled = false, help = ReverbSelectorHelp("مساعدة") {},
        disabledReason = "This setting is unavailable until setup has completed.",
    ) { fixture ->
        main {
            val selector = fixture.selector
            val capsule = selector.descendants().filterIsInstance<NativeSegmentedControl>().single()
            val help = selector.helpButton()
            val titleRow = help.parent as ViewGroup
            val reason = selector.reason()
            val title = titleRow.descendants().filterIsInstance<ParagraphView>().single()
            val originalDensity = fixture.environment.textDensity.density
            selector.layoutAtWidth((1000 * originalDensity).roundToInt())
            assertTrue(titleRow.right <= capsule.left)
            assertEquals((48 * originalDensity).roundToInt(), help.width)
            assertSupportingBelow(selector, titleRow, capsule, reason, fixture.environment)

            val narrowWidth = (220 * originalDensity).roundToInt()
            selector.layoutAtWidth(narrowWidth)
            assertTrue(capsule.top >= titleRow.bottom)
            assertTrue(help.left >= title.right)
            assertSupportingBelow(selector, titleRow, capsule, reason, fixture.environment)
            val ltrTitle = selector.boundsOf(title)
            val ltrHelp = selector.boundsOf(help)
            selector.layoutDirection = View.LAYOUT_DIRECTION_RTL
            selector.layoutAtWidth(narrowWidth)
            assertEquals(narrowWidth - ltrTitle.right, selector.boundsOf(title).left)
            assertEquals(narrowWidth - ltrHelp.right, selector.boundsOf(help).left)
            assertTrue(help.right <= title.left)
            assertSupportingBelow(selector, titleRow, capsule, reason, fixture.environment)

            val dark = ReverbEnvironment(fixture.activity, true)
            val rebound = ReverbEnvironment(Density(originalDensity * 1.25f, fontScale = 1.6f),
                dark.sharedFontResolver, dark.palette).forSettings()
            selector.bind("الاحتفاظ", options, Mode.SIZE, false, rebound)
            selector.layoutAtWidth(narrowWidth)
            assertSame(help, selector.helpButton())
            assertSame(reason, selector.reason())
            assertEquals("الاحتفاظ", title.getText())
            assertEquals((48 * rebound.textDensity.density).roundToInt(), help.width)
            assertTrue(capsule.top >= titleRow.bottom)
            assertSupportingBelow(selector, titleRow, capsule, reason, rebound)
            val reference = createReverbSupportingParagraph(fixture.activity, reason.getText(),
                NativeTypographyRole.BODY_SMALL, rebound.theme)
            reference.layoutDirection = View.LAYOUT_DIRECTION_RTL
            reference.measure(View.MeasureSpec.makeMeasureSpec(reason.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertEquals(reference.measuredHeight, reason.height)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun unchangedAndSelectionOnlyBindsAvoidTraversalInProductionSettingsHierarchy() {
        var oldCalls = 0
        var currentCalls = 0
        withTraversalFixture(ReverbSelectorHelp("Help") { oldCalls++ }) { fixture ->
            lateinit var time: NativeChip
            lateinit var size: NativeChip
            lateinit var geometry: List<Rect>
            main {
                time = fixture.selector.chip("Time")
                size = fixture.selector.chip("Size")
                geometry = fixture.selector.selectorGeometry()
                assertTrue(surfaceDiagnostic(fixture), ReverbTextSurface.sample(fixture.selector.title()) != 0L)
            }
            // Positive control: the attached section must observe a real ViewRoot traversal.
            measureBinding(fixture, "explicit layout request", expectLayout = true) {
                fixture.selector.requestLayout()
            }
            // These are separate rendered updates, not many binds coalesced into one traversal.
            // At 5f26bbc each fails: both bindHelp -> bindAction and bind() request layout.
            repeat(3) { measureBinding(fixture, "identical bind $it", expectLayout = false) {} }
            measureBinding(fixture, "selection only", expectLayout = false) {
                fixture.state = fixture.state.copy(selected = Mode.SIZE)
            }
            main {
                assertSame(time, fixture.selector.chip("Time"))
                assertSame(size, fixture.selector.chip("Size"))
                assertFalse(time.isChecked)
                assertTrue(size.isChecked)
                assertTrue(requireNotNull(size.createAccessibilityNodeInfo()).isChecked)
                assertEquals(geometry, fixture.selector.selectorGeometry())
            }
            measureBinding(fixture, "same Help presentation, new callback", expectLayout = false) {
                fixture.state = fixture.state.copy(help = ReverbSelectorHelp("Help") { currentCalls++ })
            }
            main {
                assertTrue(fixture.selector.helpButton().visual.performAccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(0, oldCalls)
                assertEquals(1, currentCalls)
            }
            measureBinding(fixture, "remove Help", expectLayout = true) {
                fixture.state = fixture.state.copy(help = null)
            }
            measureBinding(fixture, "identical bind without Help", expectLayout = false) {}
            measureBinding(fixture, "selection without Help", expectLayout = false) {
                fixture.state = fixture.state.copy(selected = Mode.TIME)
            }
        }
    }

    @Test fun geometryAndHelpStateChangesTraverseAndMatchFreshProductionLayout() =
        withTraversalFixture { fixture ->
            fun change(name: String, update: () -> Unit) {
                measureBinding(fixture, name, expectLayout = true, update = update)
                main { assertMatchesFreshSelector(fixture) }
                measureBinding(fixture, "$name retained bind", expectLayout = false) {}
            }
            change("show Help") { fixture.state = fixture.state.copy(help = ReverbSelectorHelp("Help") {}) }
            main {
                assertEquals((48 * fixture.state.environment.textDensity.density).roundToInt(),
                    fixture.selector.helpButton().width)
            }
            change("localize Help") { fixture.state = fixture.state.copy(help = ReverbSelectorHelp("Aide") {}) }
            main {
                assertEquals("Aide", fixture.selector.helpButton().visual.createAccessibilityNodeInfo()
                    ?.contentDescription?.toString())
            }
            change("disable Help") {
                fixture.state = fixture.state.copy(help = requireNotNull(fixture.state.help).copy(enabled = false))
            }
            main {
                val action = fixture.selector.helpButton().visual
                assertFalse(requireNotNull(action.createAccessibilityNodeInfo()).isEnabled)
                assertFalse(action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            }
            change("show supporting reason") {
                fixture.state = fixture.state.copy(enabled = false, reason = "Requires setup")
            }
            main {
                assertTrue(fixture.selector.reason() in fixture.selector.accessibilityChildren())
                assertNull(fixture.selector.chip("Automatic").createAccessibilityNodeInfo().contentDescription)
            }
            change("wrap supporting reason") {
                fixture.state = fixture.state.copy(reason = "Paused by the owner until setup has completed. ".repeat(4))
            }
            change("hide supporting reason") { fixture.state = fixture.state.copy(reason = null) }
            main { assertFalse(fixture.selector.reason() in fixture.selector.accessibilityChildren()) }
            change("title and option labels") {
                fixture.state = fixture.state.copy(label = "Recording retention options",
                    options = fixture.state.options.map { it.copy(label = "${it.label} mode") })
            }
            change("palette with retained density and resolver") {
                val old = fixture.state.environment
                fixture.state = fixture.state.copy(environment = old.withPalette(
                    ReverbDesign.appearance.resolveColorScheme(fixture.activity, true)))
                assertSame(old.textDensity, fixture.state.environment.textDensity)
                assertSame(old.sharedFontResolver, fixture.state.environment.sharedFontResolver)
                fixture.screen.bindEnvironment(fixture.state.environment)
            }
            // Palette binding still traverses the shared icon child's bindAction; this test does
            // not suppress that work or mistake it for an identical retained-theme bind.
            change("density and font scale") {
                val old = fixture.state.environment
                fixture.state = fixture.state.copy(environment = ReverbEnvironment(
                    Density(old.textDensity.density * 1.25f, old.textDensity.fontScale * 1.3f),
                    old.sharedFontResolver, old.palette).forSettings(), reason = "Paused by owner")
                fixture.screen.bindEnvironment(fixture.state.environment)
            }
            main {
                assertEquals((48 * fixture.state.environment.textDensity.density).roundToInt(),
                    fixture.selector.helpButton().width)
            }
        }

    private data class SelectorState(val environment: ReverbEnvironment, val options: List<SegmentOption<Mode>>,
        val label: String = "Retention", val selected: Mode = Mode.TIME, val enabled: Boolean = true,
        val help: ReverbSelectorHelp? = null, val reason: String? = null) {
        fun bind(selector: ReverbLabeledSegmentedSelector<Mode>) = selector.bind(
            label, options, selected, enabled, environment, help, reason)
    }

    /** Count real child requests and traversals without overriding or manually measuring the selector. */
    private class MeasuredSection(context: Context, selector: View) : FrameLayout(context) {
        var requests = 0
        var measures = 0
        var layouts = 0
        init { addView(selector, LayoutParams(-1, -2)) }
        fun reset() { requests = 0; measures = 0; layouts = 0 }
        override fun requestLayout() { requests++; super.requestLayout() }
        override fun onMeasure(w: Int, h: Int) { measures++; super.onMeasure(w, h) }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            layouts++; super.onLayout(changed, l, t, r, b)
        }
    }

    private class TraversalScreen(context: Context, environment: ReverbEnvironment,
        runtime: NativeModalWindowRuntime, sections: ReverbSettingsSectionListView) :
        ReverbSettingsScreenLayout(context, environment, runtime.frames, runtime.scope), NativeInputGate {
        override val acceptsNativeInput get() = isAttachedToWindow && isShown && hasWindowFocus()
        private val header = ReverbSettingsTopBar(context, environment.theme, this)
        private val backdrop = ReverbDesign.appearance.backdrops.attachPage(this, environment.palette) {
            ReverbTextSurface.invalidate(this)
        }
        init { installTopBar(header); setSettingsList(sections); bindEnvironment(environment) }
        fun bindEnvironment(environment: ReverbEnvironment) {
            ReverbDesign.appearance.backdrops.bindPage(backdrop, environment.palette)
            header.bind(environment.theme, "Settings", ReverbHeaderAction("Back", {}), null,
                ReverbHeaderAction("Apply", {}))
            bindSettingsSurface(environment, NativeTopBarInsets(0, 0, 0), 0, inputAllowed = true, pending = false)
        }
        override fun onSettingsLayoutChanged() = Unit
        fun close() = releaseSettingsSurface()
    }

    private class TraversalFixture(val activity: Activity, var state: SelectorState) {
        val requests = mutableListOf<Mode>()
        val runtime = NativeModalWindowRuntime(activity)
        val selector = ReverbLabeledSegmentedSelector(activity, state.environment, state.label, state.options,
            state.selected, state.enabled, state.help, state.reason, { true }) { requests.add(it) }
        val section = MeasuredSection(activity, selector)
        val sections = ReverbSettingsSectionListView(activity, ReverbSettingsSectionFactory { key ->
            object : ReverbSettingsSection {
                // Other domain sections are empty; the selector uses the real retained Settings
                // list, screen, painted backdrop, toolbar and input gate without test appearances.
                override val view: View = if (key == ReverbSettingsSectionKey.RETENTION) section else View(activity)
                override fun bind() { if (key == ReverbSettingsSectionKey.RETENTION) state.bind(selector) }
                override fun close() { if (key == ReverbSettingsSectionKey.RETENTION) selector.close() }
            }
        }, state.environment, restricted = false)
        val screen = TraversalScreen(activity, state.environment, runtime, sections)
        fun bind() = sections.bindSection(ReverbSettingsSectionKey.RETENTION)
        fun close() {
            screen.close()
            sections.close()
            selector.close()
            runtime.close()
        }
    }

    private fun withTraversalFixture(help: ReverbSelectorHelp? = null, block: (TraversalFixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var fixture: TraversalFixture? = null
        try {
            main {
                fixture = TraversalFixture(activity, SelectorState(
                    ReverbEnvironment(activity, false).forSettings(), options, help = help)).also {
                    activity.setContentView(it.screen)
                    it.runtime.setStarted(true)
                }
            }
            val mounted = requireNotNull(fixture)
            repeat(2) { awaitDraw(mounted.screen) }
            main {
                assertTrue(mounted.selector.isShown && mounted.selector.hasWindowFocus())
                assertTrue(mounted.selector.width > 0 && mounted.selector.height > 0)
            }
            block(mounted)
        } finally {
            main { fixture?.close(); activity.finish() }
        }
    }

    private fun measureBinding(fixture: TraversalFixture, name: String, expectLayout: Boolean, update: () -> Unit) {
        main {
            assertFalse("$name starts with settled section", fixture.section.isLayoutRequested)
            assertFalse("$name starts with settled selector", fixture.selector.isLayoutRequested)
            fixture.section.reset()
            update()
            fixture.bind()
        }
        // Invalidate drawing only. No measure/layout/forceLayout calls may repair missing requests.
        repeat(2) { awaitDraw(fixture.screen) }
        main {
            if (expectLayout) {
                assertTrue("$name requests section layout", fixture.section.requests > 0)
                assertTrue("$name actually measures section", fixture.section.measures > 0)
                assertTrue("$name actually lays out section", fixture.section.layouts > 0)
            } else {
                assertEquals("$name layout requests", 0, fixture.section.requests)
                assertEquals("$name measures", 0, fixture.section.measures)
                assertEquals("$name layouts", 0, fixture.section.layouts)
            }
            assertFalse("$name finishes layout", fixture.selector.isLayoutRequested)
            assertTrue("$name must bind silently", fixture.requests.isEmpty())
        }
    }

    /** Only this synthetic fixture's paint metadata; never text, identifiers or external windows. */
    private fun surfaceDiagnostic(fixture: TraversalFixture): String {
        val title = fixture.selector.title()
        val ancestry = ArrayList<String>()
        val owned = fixture.screen.descendants().toSet() + fixture.selector.descendants().toSet()
        var current: View? = title
        repeat(12) {
            val node = current ?: return@repeat
            if (node !in owned) {
                ancestry += "outsideFixture"
                current = null
                return@repeat
            }
            val background = node.background
            ancestry += "${node.javaClass.simpleName}[scope=${node is ReverbTextSurfaceScope}," +
                "attached=${node.isAttachedToWindow},shown=${node.isShown},layout=${node.isLayoutRequested}," +
                "size=${node.width}x${node.height},alpha=${node.alpha}," +
                "background=${background?.javaClass?.simpleName},backgroundAlpha=${background?.alpha}," +
                "surface=${(background as? NoiseDrawable)?.paintedTextSurface}]"
            // Do not inspect the Activity decor or any platform ancestor outside the owned screen.
            current = if (node === fixture.screen) null else node.parent as? View
        }
        if (current != null) ancestry += "ancestryTruncated"
        return "Settings title needs a sampled painted surface: ${ancestry.joinToString(" -> ")};" +
            " sample=${ReverbTextSurface.sample(title)}," +
            "screenSurface=${(fixture.screen.background as? NoiseDrawable)?.paintedTextSurface}," +
            "paletteSurface=${fixture.state.environment.palette.surface}"
    }

    private fun awaitDraw(view: View) {
        val drawn = CountDownLatch(1)
        var posted = false
        val listener = ViewTreeObserver.OnDrawListener {
            if (!posted) { posted = true; view.post { drawn.countDown() } }
        }
        main { view.viewTreeObserver.addOnDrawListener(listener); view.postInvalidateOnAnimation() }
        try {
            assertTrue("The attached production hierarchy must draw", drawn.await(5, TimeUnit.SECONDS))
        } finally {
            main { view.viewTreeObserver.removeOnDrawListener(listener) }
        }
    }

    private fun assertMatchesFreshSelector(fixture: TraversalFixture) {
        val state = fixture.state
        val reference = ReverbLabeledSegmentedSelector(fixture.activity, state.environment, state.label,
            state.options, state.selected, state.enabled, state.help, state.reason, { true }) { false }
        try {
            // Only the fresh reference is manually measured; the retained subject had to traverse.
            reference.layoutDirection = fixture.selector.layoutDirection
            reference.layoutAtWidth(fixture.selector.width)
            assertEquals(reference.height, fixture.selector.height)
            assertEquals(reference.selectorGeometry(), fixture.selector.selectorGeometry())
            assertEquals(reference.reason().getText(), fixture.selector.reason().getText())
            assertEquals(reference.reason().visibility, fixture.selector.reason().visibility)
            for (option in state.options) {
                assertEquals(reference.chip(option.label).isChecked, fixture.selector.chip(option.label).isChecked)
                assertEquals(reference.chip(option.label).isEnabled, fixture.selector.chip(option.label).isEnabled)
            }
        } finally { reference.close() }
    }

    private fun ViewGroup.title() = (helpButton().parent as ViewGroup).descendants().filterIsInstance<ParagraphView>().single()
    private fun ViewGroup.selectorGeometry(): List<Rect> = listOf(
        title(), helpButton(), reason(), descendants().filterIsInstance<NativeSegmentedControl>().single(),
    ).map { if (it.visibility == View.GONE) Rect() else boundsOf(it) }

    private data class Fixture(val activity: Activity, val root: FrameLayout,
        val selector: ReverbLabeledSegmentedSelector<Mode>, val environment: ReverbEnvironment,
        val requests: MutableList<Mode>)

    private fun withSelector(enabled: Boolean = true, help: ReverbSelectorHelp? = null,
        disabledReason: String? = null, inputAllowed: () -> Boolean = { true },
        ancestorInputAllowed: () -> Boolean = { true }, block: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var fixture: Fixture? = null
        try {
            main {
                val environment = ReverbEnvironment(activity, false).forSettings()
                val requests = mutableListOf<Mode>()
                val selector = ReverbLabeledSegmentedSelector(activity, environment, "Retention", options,
                    Mode.TIME, enabled, help, disabledReason, inputAllowed) { requests.add(it) }
                val root = object : FrameLayout(activity), NativeInputGate {
                    override val acceptsNativeInput: Boolean get() = ancestorInputAllowed()
                }
                root.addView(selector, FrameLayout.LayoutParams(-1, -2))
                fixture = Fixture(activity, root, selector, environment, requests)
                activity.setContentView(root)
            }
            instrumentation.waitForIdleSync()
            block(requireNotNull(fixture))
        } finally {
            main {
                fixture?.selector?.close()
                activity.finish()
            }
        }
    }

    private fun assertSupportingBelow(selector: ViewGroup, title: View, capsule: View, reason: View,
        environment: ReverbEnvironment) {
        val gap = (environment.appearance.outlinedField.supportingPaddingDp * environment.textDensity.density).roundToInt()
        assertEquals(maxOf(title.bottom, capsule.bottom) + gap, reason.top)
        assertEquals(reason.bottom, selector.height)
        for (child in listOf(title, capsule, reason)) {
            assertTrue(child.left >= 0 && child.right <= selector.width)
            assertTrue(child.width > 0 && child.height > 0)
        }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun key(action: Int) = KeyEvent(action, KeyEvent.KEYCODE_ENTER)
    private fun ViewGroup.helpButton() = descendants().filterIsInstance<ReverbStandardIconButtonView>().single()
    private fun ViewGroup.reason() = (0 until childCount).map { getChildAt(it) }.filterIsInstance<ParagraphView>().single()
    private fun ViewGroup.accessibilityChildren() = arrayListOf<View>().also { addChildrenForAccessibility(it) }
    private fun ViewGroup.boundsOf(child: View) = Rect(0, 0, child.width, child.height).also {
        offsetDescendantRectToMyCoords(child, it)
    }
    private fun View.layoutAtWidth(width: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        val group = this@descendants as? ViewGroup
        if (group != null) {
            for (index in 0 until group.childCount) yieldAll(group.getChildAt(index).descendants())
        }
    }

    private fun ViewGroup.chip(label: String): NativeChip =
        descendants().filterIsInstance<NativeChip>().single { it.text.toString() == label }
}
