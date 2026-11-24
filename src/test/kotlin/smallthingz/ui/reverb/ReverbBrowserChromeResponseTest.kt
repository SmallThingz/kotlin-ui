package smallthingz.ui.reverb

import org.junit.Assert.*
import org.junit.Test

class ReverbBrowserChromeResponseTest {
    @Test fun chosenActionCannotEscapeBeforeItsDismissal() {
        val response = ReverbBrowserChromeResponse()
        assertTrue(response.choose("open"))
        assertNull(response.take(true))
        assertTrue(response.dismiss(true))
        assertEquals("open", response.take(true))
    }
    @Test fun repeatedClicksAndDismissalsDeliverAtMostOneIdentity() {
        val response = ReverbBrowserChromeResponse()
        assertTrue(response.choose("first"))
        assertFalse(response.choose("second"))
        assertTrue(response.dismiss(true))
        assertEquals("first", response.take(true))
        assertNull(response.take(true))
        assertFalse(response.dismiss(true))
        assertFalse(response.choose("third"))
    }
    @Test fun outsideDismissalAndRetirementCancelPendingActions() {
        for (outside in listOf(true, false)) {
            val response = ReverbBrowserChromeResponse()
            response.choose("clear")
            if (outside) assertFalse(response.dismiss(false)) else response.cancel()
            assertNull(response.take(true))
            assertFalse(response.choose("clear"))
        }
    }
    @Test fun ownerRejectionAtDeliveryCannotBeReadmittedLater() {
        val response = ReverbBrowserChromeResponse()
        response.choose("navigate")
        response.dismiss(true)
        assertNull(response.take(false))
        assertNull(response.take(true))
        assertFalse(response.choose("navigate"))
    }
    @Test fun completionRetiresBeforeActionAndNotifiesLast() {
        val order = mutableListOf<String>()
        completeReverbBrowserChromeAction({ order += "retire" }, { order += "action" }, { order += "notify" })
        assertEquals(listOf("retire", "action", "notify"), order)
    }
    @Test fun completionPreservesActionFailureAndSuppressesRetirementCallbackFailure() {
        val actionFailure = IllegalStateException("action")
        val cleanupFailure = IllegalArgumentException("retirement callback")
        var notifications = 0
        val caught = runCatching {
            completeReverbBrowserChromeAction({}, { throw actionFailure }, { notifications++; throw cleanupFailure })
        }.exceptionOrNull()
        assertSame(actionFailure, caught)
        assertEquals(listOf(cleanupFailure), actionFailure.suppressed.toList())
        assertEquals(1, notifications)
    }
    @Test fun failedInputRetirementSkipsActionButStillNotifiesAndPreservesItsFailure() {
        val retirementFailure = IllegalStateException("input retirement")
        val callbackFailure = IllegalArgumentException("retirement callback")
        var deliveries = 0
        var notifications = 0
        val caught = runCatching {
            completeReverbBrowserChromeAction({ throw retirementFailure }, { deliveries++ }, { notifications++; throw callbackFailure })
        }.exceptionOrNull()
        assertSame(retirementFailure, caught)
        assertEquals(listOf(callbackFailure), retirementFailure.suppressed.toList())
        assertEquals(0, deliveries)
        assertEquals(1, notifications)
    }
    @Test fun identicalCallbackFailureCannotReplaceItselfWithSelfSuppressionError() {
        val failure = IllegalStateException("same failure")
        val caught = runCatching { completeReverbBrowserChromeAction({}, { throw failure }, { throw failure }) }.exceptionOrNull()
        assertSame(failure, caught)
        assertTrue(failure.suppressed.isEmpty())
    }
}
