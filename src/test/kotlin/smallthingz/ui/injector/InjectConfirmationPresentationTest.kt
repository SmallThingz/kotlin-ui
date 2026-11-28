package smallthingz.ui.injector

import android.content.res.Configuration
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeDetailCardRow
import smallthingz.ui.animation.ColorTransition
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.controls.textSurfaceForeground
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import java.util.function.LongSupplier
import smallthingz.ui.icons.MaterialRoundedIcon
import smallthingz.ui.icons.MaterialRoundedIconDrawable

@RunWith(RobolectricTestRunner::class)
@Config(application = InjectCompositeHostApplication::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class InjectConfirmationPresentationTest {
    @Test fun detailRowFirstPalettePaintsImmediatelyBeforeOrAfterAttachment() {
        for (dark in listOf(false, true)) for (attached in listOf(false, true)) for (granted in listOf(false, true)) {
            withDetailPalette(dark, attached) { root, row, palette ->
                bindDetail(row, palette, granted = granted)
                assertLabelTarget(detailLabel(row, "title"), palette.ink)
                assertLabelTarget(detailLabel(row, "body"), palette.muted)
                if (!attached) root.addView(row)
                measure(row, 360)
                assertTrue(row.isAttachedToWindow)
                assertLabelPixels(detailLabel(row, "title"), palette.ink, palette.background)
                assertLabelPixels(detailLabel(row, "body"), palette.muted, palette.background)
            }
        }
    }

    @Test fun detailRowFirstPaletteRetainsPaintedSurfaceContrastOwnership() {
        for (dark in listOf(false, true)) withDetailPalette(dark) { _, row, palette ->
            val fill = if (dark) Color.BLACK else Color.WHITE
            val unreadable = palette.copy(onSurface = fill, onSurfaceVariant = fill)
            for (name in listOf("title", "body")) {
                detailLabel(row, name).bindPaintedTextSurface(LongSupplier { textSurfaceRange(fill) })
            }
            bindDetail(row, unreadable)
            measure(row, 360)
            val readable = textSurfaceForeground(textSurfaceRange(fill), fill)
            assertNotEquals(fill, readable)
            for (name in listOf("title", "body")) {
                val label = detailLabel(row, name)
                assertLabelTarget(label, fill)
                // A bare setTextColor first bind would lose this actual-paint qualification.
                assertLabelPixels(label, readable, fill)
            }
        }
    }

    @Test fun detailRowLaterPaletteStillInterpolatesBothLabelsAndKeepsOwners() =
        withDetailPalette { _, row, palette ->
            bindDetail(row, palette)
            val labels = listOf(detailLabel(row, "title"), detailLabel(row, "body"))
            val owners = labels.map(::labelMotion)
            val old = listOf(palette.ink, palette.muted)
            val next = palette.copy(onSurface = Color.RED, onSurfaceVariant = Color.BLUE)
            val targets = listOf(next.ink, next.muted)
            bindDetail(row, next)
            labels.forEachIndexed { index, label ->
                assertSame(owners[index], labelMotion(label))
                assertTrue(labelState(label).running)
                assertEquals(old[index], labelMotion(label).color(0))
                advanceLabel(label, ColorTransition.DURATION_NANOS / 2)
                val reference = ColorTransition(intArrayOf(old[index])).apply {
                    retarget(intArrayOf(targets[index]), 0L)
                    frame(ColorTransition.DURATION_NANOS / 2)
                }
                assertEquals(reference.color(0), labelMotion(label).color(0))
                assertTrue(labelMotion(label).isInterpolating)
                advanceLabel(label, ColorTransition.DURATION_NANOS)
                assertLabelTarget(label, targets[index])
            }
        }

    @Test fun detailRowHiddenAndDetachedPaletteBindsSnapAndReattachStillAnimates() =
        withDetailPalette { root, row, palette ->
            // Even an initially absent status receives its palette before later becoming visible.
            bindDetail(row, palette, body = null)
            val title = detailLabel(row, "title")
            val body = detailLabel(row, "body")
            assertEquals(View.GONE, body.visibility)
            assertLabelTarget(body, palette.muted)
            bindDetail(row, palette)
            assertEquals(View.VISIBLE, body.visibility)
            assertLabelTarget(body, palette.muted)

            row.visibility = View.GONE
            val hidden = palette.copy(onSurface = Color.RED, onSurfaceVariant = Color.BLUE)
            bindDetail(row, hidden)
            assertLabelTarget(title, hidden.ink)
            assertLabelTarget(body, hidden.muted)
            row.visibility = View.VISIBLE
            bindDetail(row, palette)
            assertTrue(labelState(title).running)
            assertTrue(labelState(body).running)
            root.removeView(row)
            assertLabelTarget(title, palette.ink)
            assertLabelTarget(body, palette.muted)
            bindDetail(row, hidden)
            assertLabelTarget(title, hidden.ink)
            assertLabelTarget(body, hidden.muted)
            root.addView(row)
            bindDetail(row, palette)
            assertTrue(labelState(title).running)
            assertTrue(labelState(body).running)
        }

    @Test fun detailRowDisabledAnimationAndGrantSemanticsRemainAuthoritative() =
        withDetailPalette { _, row, palette ->
            var calls = 0
            bindDetail(row, palette, action = { calls++ })
            assertTrue(row.performClick())
            setAnimatorScale(0f)
            val next = palette.copy(onSurface = Color.RED, onSurfaceVariant = Color.BLUE)
            bindDetail(row, next, body = "Allowed", granted = true, action = { calls++ })
            assertLabelTarget(detailLabel(row, "title"), next.ink)
            assertLabelTarget(detailLabel(row, "body"), next.muted)
            assertFalse(row.canRequestNow)
            assertFalse(row.performClick())
            val node = checkNotNull(row.createAccessibilityNodeInfo())
            try {
                assertEquals("Root", node.contentDescription.toString())
                assertEquals("Allowed", node.stateDescription.toString())
                assertFalse(node.isClickable)
            } finally { node.recycle() }
            var latestCalls = 0
            bindDetail(row, palette, action = { latestCalls++ })
            assertLabelTarget(detailLabel(row, "title"), palette.ink)
            assertLabelTarget(detailLabel(row, "body"), palette.muted)
            val actionable = checkNotNull(row.createAccessibilityNodeInfo())
            try {
                assertEquals("Root", actionable.contentDescription.toString())
                assertEquals("Status", actionable.stateDescription.toString())
                assertTrue(actionable.isClickable)
                assertEquals("Allow Root", actionable.actionList.single {
                    it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                }.label.toString())
            } finally { actionable.recycle() }
            assertTrue(row.performClick())
            assertEquals(1, calls)
            assertEquals(1, latestCalls)
        }

    @Test fun permissionStatusTextDefersIconsAndVisibleRebindUsesTheCurrentGrant() {
        val context = RuntimeEnvironment.getApplication()
        for (dark in listOf(false, true)) {
            val row = object : InjectPermissionRow(context, InjectDesign.theme(context, dark), android.R.drawable.sym_def_app_icon) {
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

    @Test fun cancelIsTextOnlyWhileConfirmKeepsItsRequestedGlyphAndSemanticColors() {
        val context = RuntimeEnvironment.getApplication()
        for (dark in listOf(false, true)) for (destructive in listOf(false, true)) {
            val theme = InjectDesign.theme(context, dark)
            val appearance = InjectAppearance(context, theme)
            val confirmIcon = if (destructive) MaterialRoundedIcon.Delete else MaterialRoundedIcon.Check
            var callbacks = 0
            val actions = appearance.confirmationActions("Cancel", if (destructive) "Delete" else "Save",
                { callbacks++ }, confirmIcon = confirmIcon, destructive = destructive,
                onConfirm = { callbacks++ })
            try {
                assertNull(actionGlyph(actions.cancelButton))
                assertEquals(confirmIcon, actionGlyph(actions.confirmButton)?.icon)
                assertEquals("Cancel", actions.cancelButton.label.getText())
                assertEquals(Color.TRANSPARENT, actions.cancelButton.sampledContainerColor)
                assertEquals(if (destructive) theme.palette.errorContainer else theme.palette.primary,
                    actions.confirmButton.sampledContainerColor)
                assertTrue(actions.isActionFooter)
                assertSame(actions.actionSurface.pointerContent, actions.cancelButton.parent)
                assertSame(actions.actionSurface.pointerContent, actions.confirmButton.parent)
                assertTrue(actions.cancelButton.isEnabled)
                assertTrue(actions.confirmButton.isEnabled)
            } finally {
                actions.confirmButton.close()
                actions.cancelButton.close()
            }
            assertEquals(0, callbacks)
        }
    }

    @Test fun equalShortLabelsKeepSharedWidthsAndNarrowStackingWithoutAnInvisibleIconSlot() {
        val application = RuntimeEnvironment.getApplication()
        for (fontScale in listOf(1f, 2f)) {
            val context = application.createConfigurationContext(
                Configuration(application.resources.configuration).apply { this.fontScale = fontScale })
            val theme = InjectDesign.theme(context, dark = false)
            val appearance = InjectAppearance(context, theme)
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                val actions = appearance.confirmationActions("OK", "OK", {}, onConfirm = {})
                val cancel = actions.cancelButton
                val confirm = actions.confirmButton
                try {
                    actions.layoutDirection = direction
                    measure(actions, 360)
                    assertNull(actionGlyph(cancel))
                    assertNull(actionGlyph(confirm))
                    assertEquals(cancel.actionMinimumWidthPx, confirm.actionMinimumWidthPx)
                    assertEquals(cancel.width, confirm.width)
                    assertEquals(cancel.top, confirm.top)
                    assertEquals(cancel.width + confirm.width, actions.actionSurface.width)

                    measure(actions, cancel.width + 8)
                    assertEquals(cancel.width, confirm.width)
                    assertEquals(cancel.left, confirm.left)
                    assertTrue(confirm.top >= cancel.bottom)

                    // The shared natural-width rule still follows text; no fixed equal-width override.
                    confirm.bind("Save a copy", true, theme.palette.buttonPalette)
                    measure(actions, 360)
                    assertTrue(confirm.width > cancel.width)
                    assertEquals(cancel.top, confirm.top)
                } finally {
                    confirm.close()
                    cancel.close()
                }
            }
        }
    }

    private fun withDetailPalette(dark: Boolean = false, attached: Boolean = true,
        block: (FrameLayout, InjectPermissionRow, NativeColorScheme) -> Unit) {
        val previousScale = ValueAnimator.getDurationScale()
        setAnimatorScale(1f)
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val root = FrameLayout(activity)
            activity.setContentView(root)
            controller.windowFocusChanged(true)
            val theme = InjectDesign.theme(activity, dark)
            val row = InjectPermissionRow(activity, theme, android.R.drawable.sym_def_app_icon,
                inputRoot = { root })
            if (attached) root.addView(row)
            block(root, row, theme.palette)
        } finally {
            controller.close()
            setAnimatorScale(previousScale)
        }
    }

    private fun bindDetail(row: InjectPermissionRow, palette: NativeColorScheme,
        body: String? = "Status", granted: Boolean = false, action: () -> Unit = {}) {
        row.bindPermission("Root", body, granted, body, palette,
            requestEnabled = true, requestLabel = "Allow Root", onRequest = action)
        measure(row, 360)
    }

    private fun setAnimatorScale(value: Float) = ValueAnimator::class.java
        .getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(null, value)

    private fun detailLabel(row: InjectPermissionRow, name: String) = NativeDetailCardRow::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(row) as ParagraphView

    @Suppress("UNCHECKED_CAST")
    private fun <T> detailField(owner: Any, name: String): T = owner.javaClass
        .getDeclaredField(name).apply { isAccessible = true }.get(owner) as T

    private fun labelMotion(label: ParagraphView): ViewColorTransition = detailField(label, "colorTransition")
    private fun labelState(label: ParagraphView): ColorTransition = detailField(labelMotion(label), "state")

    private fun assertLabelTarget(label: ParagraphView, color: Int) {
        assertEquals(color, labelMotion(label).color(0))
        assertFalse(labelState(label).running)
        assertFalse(detailField<Boolean>(labelMotion(label), "posted"))
    }

    private fun advanceLabel(label: ParagraphView, nanos: Long) {
        val motion = labelMotion(label)
        val last = detailField<Long>(labelState(label), "lastFrame")
        Choreographer.getInstance().removeFrameCallback(motion)
        motion.doFrame(last + nanos)
    }

    private fun assertLabelPixels(label: ParagraphView, expectedInk: Int, background: Int) {
        assertTrue(label.width > 0 && label.height > 0)
        val reference = ParagraphView(label.context, detailField<TextStyle>(label, "textStyle"),
            detailField<Density>(label, "density"), detailField<FontFamily.Resolver>(label, "fontResolver"),
            expectedInk).apply { setText(label.getText()) }
        reference.measure(View.MeasureSpec.makeMeasureSpec(label.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(label.height, View.MeasureSpec.EXACTLY))
        reference.layout(0, 0, label.width, label.height)
        fun pixels(view: View): IntArray {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            return try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(background)
                view.draw(canvas)
                IntArray(bitmap.width * bitmap.height).also {
                    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                }
            } finally { bitmap.recycle() }
        }
        val expected = pixels(reference)
        assertTrue("Reference must contain visible glyph pixels", expected.any { it != background })
        assertArrayEquals(expected, pixels(label))
    }

    // Same retained-glyph inspection used by NativeMergedActionLayoutIntegrationTest.
    private fun actionGlyph(button: NativeMaterialButtonView): MaterialRoundedIconDrawable? =
        NativeMaterialButtonView::class.java.getDeclaredField("icon").apply { isAccessible = true }
            .get(button) as MaterialRoundedIconDrawable?

    private fun measure(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
}
