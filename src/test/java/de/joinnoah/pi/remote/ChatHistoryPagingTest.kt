package de.joinnoah.pi.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryPagingTest {
    @Test fun attemptsEachCursorOnlyOnce() {
        val gate = ChatHistoryPageGate()
        assertTrue(gate.request("page-1", true))
        assertFalse(gate.request("page-1", true))
        assertFalse(gate.request("page-1", false))
        assertTrue(gate.request("page-2", true))
    }

    @Test fun ineligibleScrollDoesNotConsumeCursor() {
        val gate = ChatHistoryPageGate()
        assertFalse(gate.request("page", false))
        assertFalse(gate.request(null, true))
        assertTrue(gate.request("page", true))
    }

    @Test fun newChatCanLoadSameCursor() {
        assertTrue(ChatHistoryPageGate().request("page", true))
        assertTrue(ChatHistoryPageGate().request("page", true))
    }
}
