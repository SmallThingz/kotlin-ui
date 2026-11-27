package smallthingz.ui.reverb

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeInputGate
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import smallthingz.ui.components.NativePermissionRow
import smallthingz.ui.components.NativeSettingsRow
import smallthingz.ui.components.NativeToggleRow
import smallthingz.ui.text.view.ParagraphView

/** Requires an owned foreground test window. These tests never launch a destination or request a grant. */
@RunWith(AndroidJUnit4::class)
class ReverbInformationIntegrationTest {
    @Test fun linksRejectClosedAncestorAndRetiredBindings() = fixture { activity, gate ->
        val opened = mutableListOf<String>()
        lateinit var content: ReverbInformationContent<String>
        lateinit var old: NativeMaterialButtonView
        main {
            content = ReverbInformationContent(activity, ReverbDesign.theme(activity, false), "Unavailable") {
                opened += it
                true
            }
            content.bind(listOf(ReverbInformationStep("Instructions", ReverbInformationLink("first", "Open"))))
            gate.addView(content.view)
        }
        idle()
        try {
            main {
                old = descendants(content.view).filterIsInstance<NativeMaterialButtonView>().single()
                old.performClick()
                assertEquals(listOf("first"), opened)
                gate.admitted = false
                old.performClick()
                assertEquals(listOf("first"), opened)
                gate.admitted = true
                content.bind(listOf(ReverbInformationStep("Replacement", ReverbInformationLink("second", "Open"))))
                old.performClick()
                assertEquals(listOf("first"), opened)
            }
            idle()
            main {
                val next = descendants(content.view).filterIsInstance<NativeMaterialButtonView>().single()
                next.performClick()
                assertEquals(listOf("first", "second"), opened)
                content.close()
                next.performClick()
                assertEquals(listOf("first", "second"), opened)
            }
        } finally { main { content.close() } }
    }

    @Test fun providerBindsRetainRowsWithoutEchoingChanges() = fixture { activity, gate ->
        val changes = mutableListOf<Pair<String, Boolean>>()
        lateinit var list: ReverbInformationToggleList<String>
        val state = ReverbInformationToggle("provider", "Provider", "example.package", false, true)
        main {
            list = ReverbInformationToggleList(activity, ReverbDesign.theme(activity, false)) { id, enabled -> changes += id to enabled }
            list.bind(listOf(state), null)
            gate.addView(list.view)
        }
        idle()
        try {
            main {
                val original = descendants(list.view).filterIsInstance<NativeToggleRow>().single()
                list.bind(listOf(state.copy(checked = true, stateDescription = "Pending")), null)
                assertSame(original, descendants(list.view).filterIsInstance<NativeToggleRow>().single())
                assertEquals(emptyList<Pair<String, Boolean>>(), changes)
                gate.admitted = false
                original.performClick()
                assertEquals(emptyList<Pair<String, Boolean>>(), changes)
                gate.admitted = true
                list.close()
                original.performClick()
                assertEquals(emptyList<Pair<String, Boolean>>(), changes)
            }
        } finally { main { list.close() } }
    }

    @Test fun permissionFactsDoNotRequestGrantedOrUnavailableAccess() = fixture { activity, gate ->
        val requested = mutableListOf<ReverbPermissionOnboardingKind>()
        lateinit var rows: ReverbPermissionOnboardingRows
        val granted = ReverbPermissionOnboardingState(ReverbPermissionOnboardingKind.BATTERY,
            "Battery", "Background access", "Manage", true, "Allowed", true)
        main {
            rows = ReverbPermissionOnboardingRows(activity, ReverbDesign.theme(activity, false), onRequest = requested::add)
            rows.bind(listOf(granted))
            gate.addView(rows.view)
        }
        idle()
        try {
            main {
                val row = descendants(rows.view).filterIsInstance<NativePermissionRow>().single()
                assertFalse(row.performClick())
                rows.bind(listOf(granted.copy(allowed = null, detail = "Unavailable", status = "", canRequest = false)))
                assertFalse(row.performClick())
                assertEquals(emptyList<ReverbPermissionOnboardingKind>(), requested)
                rows.bind(listOf(granted.copy(allowed = false, status = "Required")))
                row.performClick()
                assertEquals(listOf(ReverbPermissionOnboardingKind.BATTERY), requested)
                gate.admitted = false
                row.performClick()
                assertEquals(listOf(ReverbPermissionOnboardingKind.BATTERY), requested)
            }
        } finally { main { rows.close() } }
    }

    @Test fun transferPresentationNeverDispatchesDisabledOrRetiredActions() = fixture { activity, gate ->
        val selected = mutableListOf<String>()
        val action = ReverbTransferAction("import", "Import", "Choose a backup", ReverbTransferDirection.IMPORT, true)
        lateinit var transfers: ReverbTransferSettings<String>
        main {
            val theme = ReverbDesign.theme(activity, false)
            val group = ReverbDesign.appearance.groupSurfaces.settingsGroup.create(activity)
            transfers = ReverbTransferSettings(group, theme, listOf(action), selected::add)
            gate.addView(group)
        }
        idle()
        try {
            main {
                val row = transfers.anchor("import")
                row.performClick()
                assertEquals(listOf("import"), selected)
                gate.admitted = false
                row.performClick()
                assertEquals(listOf("import"), selected)
                gate.admitted = true
                transfers.bind(listOf(action.copy(enabled = false)), ReverbTransferProgress("import", "Saving"))
                row.performClick()
                transfers.close()
                row.performClick()
                assertEquals(listOf("import"), selected)
            }
        } finally { main { transfers.close() } }
    }

    @Test fun transferProgressBelongsToItsRowAndDoesNotReserveATerminalFooter() = fixture { activity, gate ->
        val actions = listOf(
            ReverbTransferAction("import", "Import", "Choose a backup", ReverbTransferDirection.IMPORT, false),
            ReverbTransferAction("export", "Export", "Save app data", ReverbTransferDirection.EXPORT, false),
        )
        main {
            val group = ReverbDesign.appearance.groupSurfaces.settingsGroup.create(activity)
            val transfers = ReverbTransferSettings(group, ReverbDesign.theme(activity, false), actions) {}
            gate.addView(group)
            try {
                assertEquals(2, group.pointerContent.childCount)
                val importing = transfers.anchor("import") as NativeSettingsRow
                val exporting = transfers.anchor("export") as NativeSettingsRow
                transfers.bind(actions, ReverbTransferProgress("export", "Saving export…"))
                assertEquals("Choose a backup", importing.detailView.text.toString())
                assertEquals("Saving export…", exporting.detailView.text.toString())
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, exporting.detailView.accessibilityLiveRegion)
                assertEquals(2, group.pointerContent.childCount)
                transfers.bind(actions, ReverbTransferProgress("import", "Restart to finish import"))
                assertEquals("Restart to finish import", importing.detailView.text.toString())
                assertEquals("Save app data", exporting.detailView.text.toString())
                transfers.bind(actions)
                assertEquals("Choose a backup", importing.detailView.text.toString())
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, importing.detailView.accessibilityLiveRegion)
                assertEquals(2, group.pointerContent.childCount)
            } finally { transfers.close() }
        }
    }

    @Test fun legacyTransferMessagesRetainASeparatePoliteFooterAndNormalDetails() = fixture { activity, gate ->
        val actions = listOf(
            ReverbTransferAction("import", "Import", "Choose a backup", ReverbTransferDirection.IMPORT, true),
            ReverbTransferAction("export", "Export", "Save app data", ReverbTransferDirection.EXPORT, true),
        )
        main {
            val group = ReverbDesign.appearance.groupSurfaces.settingsGroup.create(activity)
            val selected = mutableListOf<String>()
            val transfers = ReverbTransferSettings(group, ReverbDesign.theme(activity, false), actions, selected::add)
            val laterRow = View(activity)
            group.addRow(laterRow)
            gate.addView(group)
            try {
                val importing = transfers.anchor("import") as NativeSettingsRow
                val exporting = transfers.anchor("export") as NativeSettingsRow
                val rows = group.pointerContent

                transfers.bind(actions, message = "")
                val footer = rows.getChildAt(2) as FrameLayout
                val status = footer.getChildAt(0) as ParagraphView
                assertEquals(View.GONE, footer.visibility)
                assertSame(laterRow, rows.getChildAt(3))

                transfers.bind(actions, message = "Ready")
                assertSame(footer, rows.getChildAt(2))
                assertEquals(View.VISIBLE, footer.visibility)
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, status.accessibilityLiveRegion)
                assertEquals("Ready", status.getText())
                assertEquals("Choose a backup", importing.detailView.text.toString())
                assertEquals("Save app data", exporting.detailView.text.toString())

                group.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                group.layout(0, 0, group.measuredWidth, group.measuredHeight)
                assertFalse(importing.isLayoutRequested)
                transfers.bind(actions.toList(), message = "Ready")
                assertFalse(importing.isLayoutRequested)
                transfers.bind(actions, message = "Complete")
                assertEquals("Complete", status.getText())
                transfers.bind(actions, message = "")
                assertEquals(View.GONE, footer.visibility)
                assertEquals("", status.getText())

                transfers.bind(actions, ReverbTransferProgress("export", "Saving export…"))
                assertNull(footer.parent)
                assertSame(laterRow, rows.getChildAt(2))
                assertEquals("Saving export…", exporting.detailView.text.toString())
                transfers.bind(actions, null)
                assertEquals("Save app data", exporting.detailView.text.toString())
                transfers.bind(actions, message = "Complete")
                val finalFooter = rows.getChildAt(2)
                transfers.close()
                assertNull(finalFooter.parent)
                transfers.bind(actions, "Stale")
                assertEquals(1, rows.childCount)
                assertSame(laterRow, rows.getChildAt(0))
                assertEquals(emptyList<String>(), selected)
            } finally { transfers.close() }
        }
    }

    @Test fun emptyLegacyTransferFooterUsesItsConstructionPosition() = fixture { activity, gate ->
        main {
            val group = ReverbDesign.appearance.groupSurfaces.settingsGroup.create(activity)
            val earlierRow = View(activity)
            val laterRow = View(activity)
            group.addRow(earlierRow)
            val transfers = ReverbTransferSettings<String>(group, ReverbDesign.theme(activity, false), emptyList()) {}
            group.addRow(laterRow)
            gate.addView(group)
            val rows = group.pointerContent
            try {
                transfers.bind(emptyList(), message = "Complete")
                val footer = rows.getChildAt(1) as FrameLayout
                assertEquals("Complete", (footer.getChildAt(0) as ParagraphView).getText())
                assertSame(earlierRow, rows.getChildAt(0))
                assertSame(laterRow, rows.getChildAt(2))

                transfers.bind(emptyList())
                group.removeRow(earlierRow)
                transfers.bind(emptyList(), message = "")
                assertEquals(View.GONE, rows.getChildAt(0).visibility)
                assertSame(laterRow, rows.getChildAt(1))
            } finally { transfers.close() }
            assertEquals(1, rows.childCount)
            assertSame(laterRow, rows.getChildAt(0))
        }
    }

    private class Gate(activity: Activity) : FrameLayout(activity), NativeInputGate {
        var admitted = true
        override val acceptsNativeInput get() = admitted
    }
    private fun fixture(test: (Activity, Gate) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var gate: Gate
        try {
            main { gate = Gate(activity); activity.setContentView(gate) }
            idle()
            test(activity, gate)
        } finally { main { activity.finish() } }
    }
    private fun main(action: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
