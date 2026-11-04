package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCloseTest {
    private val fixture =
        Wire.parse(
            checkNotNull(javaClass.getResourceAsStream("/session-close-terminal-v1.json")).readBytes().decodeToString(),
        )

    private fun payload(name: String): JsonObject =
        fixture.array("wireValid").map { it as JsonObject }.first { it.text("name") == name }.obj("payload")

    private fun refusal(name: String): RemoteRequestException {
        val error = payload(name).obj("error")
        return RemoteRequestException(error.text("code"), hostMessage = error.text("message"))
    }

    @Test
    fun theConstantsAreTheFixtureStrings() {
        assertEquals(fixture.text("capability"), SESSION_CLOSE_TERMINAL_CAPABILITY)
        assertEquals(payload("close-draft").obj("error").text("message"), CLOSE_DRAFT_REFUSAL)
        assertEquals(payload("close-result-unknown").obj("error").text("message"), CLOSE_RESULT_UNKNOWN)
        assertEquals(payload("close-unsupported").obj("error").text("message"), EXTENSION_CONTROL_REFUSAL_MESSAGE)
    }

    @Test
    fun everyFixtureRefusalHasItsOwnExplanation() {
        assertEquals(R.string.remote_close_busy, closeRefusal(refusal("close-busy")))
        assertEquals(R.string.remote_close_draft, closeRefusal(refusal("close-draft")))
        assertEquals(R.string.remote_close_unsupported, closeRefusal(refusal("close-unsupported")))
        assertEquals(R.string.remote_close_offline, closeRefusal(refusal("close-offline")))
        assertEquals(R.string.remote_close_timeout, closeRefusal(refusal("close-result-unknown")))
        assertEquals(R.string.remote_request_error, closeRefusal(RemoteRequestException("forbidden", hostMessage = "x")))
        assertEquals(R.string.remote_request_error, closeRefusal(IllegalStateException()))
    }

    @Test
    fun onlyAnUnreachableOrUnconfirmedCloseReadsTheListAgain() {
        assertTrue(closeNeedsRefresh(refusal("close-offline")))
        assertTrue(closeNeedsRefresh(refusal("close-result-unknown")))
        assertFalse(closeNeedsRefresh(refusal("close-busy")))
        assertFalse(closeNeedsRefresh(refusal("close-draft")))
    }

    @Test
    fun aTerminalRowClosesOnlyForAnUnmarkedTopLevelTuiSessionWithTheCapability() {
        val caps = setOf(SESSION_CLOSE_TERMINAL_CAPABILITY)
        fun row(vararg extra: Pair<String, Any>) =
            Wire.objectOf("id" to "t", "title" to "T", "origin" to "tui", "status" to "idle", *extra)
        val state = RemoteState(capabilities = caps)
        assertTrue(terminalClosable(state, row()))
        assertFalse(terminalClosable(state, row("parentSessionId" to "p")))
        assertFalse(terminalClosable(state, JsonObject(row() + (HOST_WORKER_KEY to JsonPrimitive(true)))))
        assertFalse(terminalClosable(state, Wire.objectOf("id" to "r", "origin" to "rpc", "status" to "idle")))
        assertFalse(terminalClosable(RemoteState(), row()))
        assertFalse(terminalClosable(state.copy(unavailableCapabilities = caps), row()))
        assertFalse(terminalClosable(state.copy(sessionControlUnsupported = mapOf("t" to setOf(ExtensionControl.CLOSE))), row()))
    }
}
