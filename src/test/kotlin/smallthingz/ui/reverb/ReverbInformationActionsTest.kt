package smallthingz.ui.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverbInformationActionsTest {
    @Test fun rebindRetiresOldActionsEvenWhenTheIdIsReused() {
        val actions = ReverbInformationActions<String>()
        val old = actions.bind(listOf("help"))
        val replacement = actions.bind(listOf("help"))
        assertFalse(actions.admits(old, "help"))
        assertTrue(actions.admits(replacement, "help"))
        assertFalse(actions.admits(replacement, "other"))
    }

    @Test fun disablingEveryActionRevokesThePreviousBinding() {
        val actions = ReverbInformationActions<String>()
        val enabled = actions.bind(listOf("import", "export"))
        val disabled = actions.bind(emptyList())
        assertFalse(actions.admits(enabled, "import"))
        assertFalse(actions.admits(disabled, "export"))
    }

    @Test fun ambiguousIdsFailWithoutReplacingTheCurrentBinding() {
        val actions = ReverbInformationActions<String>()
        val current = actions.bind(listOf("help"))
        assertThrows(IllegalArgumentException::class.java) { actions.bind(listOf("help", "help")) }
        assertTrue(actions.admits(current, "help"))
    }

    @Test fun closeRevokesCallbacksAndCannotBeUndoneByRebinding() {
        val actions = ReverbInformationActions<String>()
        val current = actions.bind(listOf("help"))
        actions.close()
        actions.close()
        assertFalse(actions.admits(current, "help"))
        assertThrows(IllegalStateException::class.java) { actions.bind(listOf("help")) }
    }
}
