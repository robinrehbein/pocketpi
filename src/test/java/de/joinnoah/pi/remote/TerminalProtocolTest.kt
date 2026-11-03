package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TerminalProtocolTest {
    @Test fun structuredKeyMatchesSharedFixtureAndNeverSendsFalseModifiers() {
        val fixture = Wire.json.parseToJsonElement(javaClass.getResource("/terminal-keys-v1.json")!!.readText()).jsonObject
        val valid = fixture.getValue("valid").jsonArray.map { it.jsonObject.obj("payload").obj("key") }
        assertEquals(valid[0], terminalInputField(TerminalInput.Chord("c", ctrl = true))!!.second)
        assertEquals(valid[1], terminalInputField(TerminalInput.Chord("enter", alt = true))!!.second)
        assertEquals(valid[2], terminalInputField(TerminalInput.Chord("2", alt = true, shift = true, shifted = "\""))!!.second)
        val invalid = fixture.getValue("invalid").jsonArray.map { it.jsonObject.obj("payload").obj("key") }
        assertNull(terminalInputField(TerminalInput.Chord(invalid[0].text("name"))))
        assertNotEquals(invalid[1], terminalInputField(TerminalInput.Chord("c"))!!.second)
        assertNotEquals(invalid[2], terminalInputField(TerminalInput.Chord("2"))!!.second)
        assertNull(terminalInputField(TerminalInput.Chord("shift_tab", ctrl = true)))
    }

    @Test fun hostFixturesValidateResultsAndEvents() {
        val fixture = Wire.json.parseToJsonElement(javaClass.getResourceAsStream("/terminal-v1.json")!!.bufferedReader().readText()).jsonObject
        for (key in listOf("wireValid", "wireInvalid")) {
            for (item in fixture.getValue(key).jsonArray.map { it.jsonObject }) {
                val payload = item.obj("payload")
                val applicable = payload.text("type") == "host.event" || (payload["data"] as? JsonObject)?.optionalText("kind") in setOf("terminal.attached", "terminal.held")
                if (!applicable) continue
                val parse = {
                    if (payload.text("type") == "host.event") parseTerminalEvent(payload)
                    else parseTerminalResult(payload.obj("data"), "worker-session")
                }
                if (key == "wireValid") parse() else assertThrows(item.text("name"), Exception::class.java) { parse() }
            }
        }
    }

    @Test fun attachmentRejectsStaleConnectionsAndGapsAndNeverReplaysInput() {
        val state = TerminalAttachment("connection-1", "worker-session", 3)
        assertEquals(0L, state.nextInputSequence("connection-1"))
        assertEquals(1L, state.nextInputSequence("connection-1"))
        assertFalse(state.receive("old", TerminalEvent.Data("worker-session", 3, 0, "old")))
        assertFalse(state.receive("connection-1", TerminalEvent.Data("worker-session", 2, 0, "old")))
        assertTrue(state.receive("connection-1", TerminalEvent.Data("worker-session", 3, 0, "hello")))
        assertFalse(state.receive("connection-1", TerminalEvent.Data("worker-session", 3, 2, "gap")))
        assertFalse(state.active)
        assertNull(state.nextInputSequence("connection-1"))
        assertFalse(state.receive("connection-1", TerminalEvent.Data("worker-session", 3, 1, "late")))
    }

    @Test fun detachAndDisconnectEndAttachment() {
        val state = TerminalAttachment("c", "s", 4)
        assertTrue(state.receive("c", TerminalEvent.Detached("s", 4, "exit", 0)))
        assertFalse(state.active)
        assertNull(state.nextInputSequence("c"))
        val other = TerminalAttachment("c", "s", 5)
        other.disconnect("old")
        assertTrue(other.active)
        other.disconnect("c")
        assertFalse(other.active)
    }
}
