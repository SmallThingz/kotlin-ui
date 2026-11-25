package smallthingz.ui.reverb

import org.junit.Assert.*
import org.junit.Test

class ReverbPermissionNoticeStateTest {
    private val kinds = ReverbPermissionOnboardingKind.entries
    private fun item(kind: ReverbPermissionOnboardingKind, granted: Boolean?, enabled: Boolean = true) =
        ReverbPermissionOnboardingState(kind, kind.name, "Detail", "Manage ${kind.name}", granted, "", enabled)

    @Test fun allPermissionCombinationsHideGrantedAndRetainUnknownWithoutAction() {
        for (notifications in listOf(false, true)) for (accessibility in listOf(false, true)) {
            for (battery in listOf(false, true, null)) {
                val values = listOf(item(kinds[0], notifications), item(kinds[1], accessibility), item(kinds[2], battery))
                val state = ReverbPermissionNoticeState()
                state.bind(values, true)
                assertEquals(values.filter { it.allowed != true }, state.items)
                for (value in values) assertEquals(value.allowed == false, state.admits(value.kind, state.revision))
            }
        }
    }

    @Test fun grantThenRevocationReappearsAndRejectsPriorAction() {
        val state = ReverbPermissionNoticeState()
        state.bind(listOf(item(kinds[0], false)), true)
        val old = state.revision
        state.bind(listOf(item(kinds[0], true)), true)
        assertTrue(state.items.isEmpty())
        assertFalse(state.admits(kinds[0], old))
        state.bind(listOf(item(kinds[0], false)), true)
        assertFalse(state.admits(kinds[0], old))
        assertTrue(state.admits(kinds[0], state.revision))
    }

    @Test fun hiddenScreenRevokesEveryActionAndRestoresOnlyMissing() {
        val state = ReverbPermissionNoticeState()
        val values = kinds.map { item(it, false) }
        state.bind(values, true)
        val old = state.revision
        state.bind(values, false)
        assertTrue(state.items.isEmpty())
        kinds.forEach { assertFalse(state.admits(it, old)) }
        state.bind(values, true)
        assertEquals(values, state.items)
        kinds.forEach { assertFalse(state.admits(it, old)) }
    }

    @Test fun disabledAdmissionCannotReplayAfterReenable() {
        val state = ReverbPermissionNoticeState()
        state.bind(listOf(item(kinds[0], false)), true)
        val old = state.revision
        state.bind(listOf(item(kinds[0], false, false)), true)
        assertFalse(state.admits(kinds[0], state.revision))
        state.bind(listOf(item(kinds[0], false)), true)
        assertFalse(state.admits(kinds[0], old))
        assertTrue(state.admits(kinds[0], state.revision))
    }

    @Test fun equalRefreshKeepsRevisionAndStableOrder() {
        val state = ReverbPermissionNoticeState()
        val values = kinds.map { item(it, false) }
        state.bind(values, true)
        val revision = state.revision
        assertFalse(state.bind(values.reversed(), true))
        assertEquals(revision, state.revision)
        assertEquals(values, state.items)
    }

    @Test fun callerMutationCannotChangeBoundFacts() {
        val state = ReverbPermissionNoticeState()
        val values = mutableListOf(item(kinds[0], false))
        state.bind(values, true)
        values.clear()
        assertTrue(state.admits(kinds[0], state.revision))
    }

    @Test fun allGrantedRemovesExpansionAndRevocationReturnsCollapsed() {
        val state = ReverbPermissionNoticeState()
        state.setExpanded(true)
        assertFalse(state.expanded)
        state.bind(kinds.map { item(it, false) }, true)
        state.setExpanded(true)
        assertTrue(state.expanded)
        state.bind(kinds.map { item(it, true) }, true)
        assertFalse(state.expanded)
        state.bind(kinds.map { item(it, false) }, true)
        assertFalse(state.expanded)
    }

    @Test(expected = IllegalArgumentException::class) fun duplicateIdentityRejected() {
        ReverbPermissionNoticeState().bind(listOf(item(kinds[0], false), item(kinds[0], true)), true)
    }
}
