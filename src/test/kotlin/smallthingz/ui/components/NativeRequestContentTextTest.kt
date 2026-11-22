package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeRequestContentTextTest {
    @Test fun snapshotsLiteralTextAndCountsSupplementaryCharactersOnce() {
        val text = StringBuilder("<b>😀</b>")
        val snapshot = boundedRequestText(text, 8)
        text.clear()
        assertEquals("<b>😀</b>", snapshot)
        assertEquals("😀".repeat(120), boundedRequestText("😀".repeat(120), 120))
        assertThrows(IllegalArgumentException::class.java) { boundedRequestText("😀".repeat(121), 120) }
    }

    @Test fun rejectsOversizedBodyAndUnpairedSurrogates() {
        assertThrows(IllegalArgumentException::class.java) { boundedRequestText("a".repeat(4001), 4000) }
        assertThrows(IllegalArgumentException::class.java) { boundedRequestText("\uD800", 1) }
    }
}
