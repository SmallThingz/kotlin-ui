package smallthingz.ui.components

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.common.SegmentOption
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbStandardIconButtonView
import smallthingz.ui.reverb.createReverbSupportingParagraph
import smallthingz.ui.reverb.settings.ReverbLabeledSegmentedSelector
import smallthingz.ui.reverb.settings.ReverbSelectorHelp
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
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
