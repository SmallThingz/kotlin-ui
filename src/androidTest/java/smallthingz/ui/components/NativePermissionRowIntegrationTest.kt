package smallthingz.ui.components

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.unit.Density
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class NativePermissionRowIntegrationTest {
    @Test fun grantedRowAnnouncesStatusAndRetainsIndependentAccessory() = withActivity { activity ->
        var requests = 0
        var details = 0
        lateinit var fixture: Fixture
        main {
            val accessory = Button(activity).apply {
                contentDescription = "Details zum Zugriff"
                isFocusableInTouchMode = true
                setOnClickListener { details++ }
            }
            fixture = fixture(activity, accessory, independent = true)
            fixture.row.bindPermission("Mikrofon", "Fuer Tonaufnahmen", true, "Erlaubt", palette,
                requestLabel = "Erlauben", onRequest = { requests++ })
        }
        waitForLayout()
        main {
            val row = fixture.row
            val accessory = fixture.trailing
            val node = checkNotNull(row.createAccessibilityNodeInfo())
            assertEquals("Mikrofon, Fuer Tonaufnahmen", node.contentDescription.toString())
            assertEquals("Erlaubt", AccessibilityNodeInfoCompat.wrap(node).stateDescription.toString())
            assertTrue(node.isScreenReaderFocusable)
            assertFalse(node.isClickable)
            assertFalse(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            assertFalse(row.isClickable)
            assertFalse(row.performClick())
            assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))

            val semanticChildren = arrayListOf<View>()
            row.addChildrenForAccessibility(semanticChildren)
            assertEquals(listOf(accessory), semanticChildren)
            assertTrue(accessory.createAccessibilityNodeInfo().actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            val focusables = arrayListOf<View>()
            row.addFocusables(focusables, View.FOCUS_FORWARD)
            assertTrue(accessory in focusables)
            assertTrue(accessory.requestFocus())
            row.bindPermission("Mikrofon", "Fuer Tonaufnahmen", false, "Nicht erlaubt", palette)
            assertSame(accessory, fixture.trailing)
            assertTrue(accessory.isFocused)
            assertTrue(accessory.isEnabled)
            val group = fixture.root.getChildAt(0) as NativeGroupLayout
            val inserted = View(activity)
            group.addRow(0, inserted, NativeGroupLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            assertTrue(row.isAttachedToWindow && accessory.isFocused)
            group.removeRow(inserted)
            assertTrue(row.isAttachedToWindow && accessory.isFocused)
            assertTrue(accessory.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
            assertTrue(accessory.dispatchKeyEvent(key(KeyEvent.ACTION_UP)))
            assertTrue(accessory.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(2, details)
            assertEquals(0, requests)
        }
    }

    @Test fun deniedRowInvokesCurrentRequestAndGrantRevokesQueuedKey() = withActivity { activity ->
        var first = 0
        var second = 0
        lateinit var row: NativePermissionRow
        main {
            row = fixture(activity, View(activity)).row
            row.isFocusableInTouchMode = true
            row.bindPermission("Microphone", null, false, "Denied", palette,
                requestLabel = "Request microphone access", onRequest = { first++ })
        }
        waitForLayout()
        main {
            val node = checkNotNull(row.createAccessibilityNodeInfo())
            val click = node.actionList.single { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            assertEquals("Request microphone access", click.label.toString())
            assertEquals("Denied", AccessibilityNodeInfoCompat.wrap(node).stateDescription.toString())
            assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(1, first)

            row.bindPermission("Microphone", null, false, "Denied", palette, onRequest = { second++ })
            assertTrue(row.requestFocus())
            assertTrue(row.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
            assertTrue(row.dispatchKeyEvent(key(KeyEvent.ACTION_UP)))
            assertEquals(1, second)
            assertEquals(1, first)

            assertTrue(row.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
            row.bindPermission("Microphone", null, true, "Granted", palette, onRequest = { second++ })
            row.dispatchKeyEvent(key(KeyEvent.ACTION_UP))
            assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertFalse(row.canRequestNow)
            assertEquals(1, second)
        }
    }

    @Test fun defaultCardGeometryAndSourceMergedBoundsRemainStable() = withActivity { activity ->
        lateinit var fixture: Fixture
        main {
            val glyph = View(activity).apply { contentDescription = "Allow" }
            fixture = fixture(activity, glyph)
            fixture.row.bindPermission("Mic", "Audio", false, null, palette, onRequest = {})
        }
        waitForLayout()
        main {
            val row = fixture.row
            val density = activity.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).roundToInt()
            fun measure(width: Int) {
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, width, row.measuredHeight)
            }
            val originalWidth = row.width
            // Wide default geometry is measured independently of the device's current window width.
            measure(dp(360))
            assertEquals(dp(44), fixture.marker.view.width)
            assertEquals(dp(16), fixture.marker.view.left)
            assertEquals(dp(24), fixture.trailing.width)
            assertEquals(row.width - dp(16) - dp(24), fixture.trailing.left)
            val title = row.getChildAt(1)
            val body = row.getChildAt(2)
            assertEquals(dp(16) + dp(44) + dp(14), title.left)
            assertEquals(title.bottom + dp(2), body.top)
            assertTrue(row.height >= dp(44) + dp(16) * 2)

            measure(dp(124))
            assertTrue(title.top >= fixture.marker.view.bottom)
            row.layoutDirection = View.LAYOUT_DIRECTION_RTL
            measure(dp(360))
            assertEquals(row.width - dp(16) - dp(44), fixture.marker.view.left)
            assertEquals(dp(16), fixture.trailing.left)
            row.layoutDirection = View.LAYOUT_DIRECTION_LTR
            measure(originalWidth)

            val geometry = NativePlainTextViewGeometry(fixture.root,
                NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })
            row.mergedRowAccessibility.bindGeometry(geometry) { true }.use {
                val markerLabel = (fixture.marker.view.pointerContent.getChildAt(0) as ViewGroup).getChildAt(0)
                assertEquals(listOf(markerLabel, title, body, fixture.trailing), row.mergedRowAccessibility.sourceOrderedChildren())
                val location = IntArray(2).also(row::getLocationOnScreen)
                val expected = Rect(location[0], location[1], location[0] + row.width, location[1] + row.height)
                val node = checkNotNull(row.createAccessibilityNodeInfo())
                val bounds = Rect().also(node::getBoundsInScreen)
                assertEquals(expected, bounds)
                val child = checkNotNull(row.mergedRowAccessibility.roleView.createAccessibilityNodeInfo())
                assertEquals(expected, Rect().also(child::getBoundsInScreen))
                row.bindPermission("Mic", "Audio", true, "Granted", palette)
                assertEquals(listOf(markerLabel, title, body), row.mergedRowAccessibility.sourceOrderedChildren())
                val granted = checkNotNull(row.createAccessibilityNodeInfo())
                assertTrue(granted.isScreenReaderFocusable)
                assertFalse(granted.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            }
        }
    }

    private data class Fixture(val row: NativePermissionRow, val root: FrameLayout,
                               val marker: NativeDetailCardMarker, val trailing: View)

    private fun fixture(activity: Activity, trailing: View, independent: Boolean = false): Fixture {
        val style = NativeDetailCardStyle()
        val typography = NativeTypography.Default
        val density = Density(activity)
        val resolver = createFontFamilyResolver(activity)
        val title = typography[NativeTypographyRole.TITLE_MEDIUM]
        val body = typography[NativeTypographyRole.BODY_MEDIUM]
        val marker = NativeDetailCardMarker(activity, title, density, resolver, style, markerText = "M")
        val row = NativePermissionRow(activity, title, body, density, resolver, marker, trailing,
            independentAccessory = independent, style = style)
        val root = FrameLayout(activity)
        val group = style.createGroup(activity)
        group.addRow(row, NativeGroupLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        group.setPalette(palette.field, palette.border)
        root.addView(group, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(root)
        return Fixture(row, root, marker, trailing)
    }

    private fun withActivity(action: (Activity) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { action(activity) } finally { main { activity.finish() } }
    }

    private fun key(action: Int) = KeyEvent(action, KeyEvent.KEYCODE_DPAD_CENTER)
    private fun waitForLayout() = instrumentation.waitForIdleSync()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val palette = NativeColorScheme.fallback(false)
}
