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
}
