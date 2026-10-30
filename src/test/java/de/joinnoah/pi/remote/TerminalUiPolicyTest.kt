package de.joinnoah.pi.remote

import org.junit.Assert.*
import org.junit.Test

class TerminalUiPolicyTest {
    @Test fun normalizesPasteAndRejectsUnsafeControls() {
        assertEquals("one\ntwo\nthree    four", normalizeTerminalPaste("one\r\ntwo\rthree\tfour"))
        assertNull(normalizeTerminalPaste("bad\u001b[200~paste"))
    }

    @Test fun queueRejectsOverflowAndInvalidInput() {
        assertFalse(terminalQueueCanAccept(List(64) { TerminalInput.Key("up") }, TerminalInput.Text("x")))
        assertFalse(terminalQueueCanAccept(listOf(TerminalInput.Paste("a".repeat(16384))), TerminalInput.Text("x")))
        assertFalse(terminalQueueCanAccept(emptyList(), TerminalInput.Text("\u001b")))
        assertTrue(terminalQueueCanAccept(emptyList(), TerminalInput.Text("hello")))
    }

    @Test fun draftAndEnterAreQueuedInOrderOrRejectedTogether() {
        val batch = terminalInputForKey("ls", "enter")!!
        assertEquals(listOf(TerminalInput.Text("ls"), TerminalInput.Key("enter")), batch)
        assertTrue(terminalQueueCanAcceptAll(emptyList(), batch))
        assertFalse(terminalQueueCanAcceptAll(List(63) { TerminalInput.Key("up") }, batch))
        assertNull(terminalInputForKey("bad\u001b", "enter"))
    }

    @Test fun dimensionsAreClampedToProtocolLimits() {
        assertEquals(20 to 300, terminalDimensions(1, 999))
        assertEquals(500 to 5, terminalDimensions(999, 1))
    }
}
