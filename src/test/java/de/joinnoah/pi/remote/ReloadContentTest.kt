package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Runs the shared `session-reload-v1.json` wire entries and the failure mapping. */
class ReloadContentTest {
    private val fixture =
        Wire.json.parseToJsonElement(javaClass.getResource("/session-reload-v1.json")!!.readText()).jsonObject

    private fun payloads(list: String) = fixture.array(list).map { it.text("name") to it.obj("payload") }

    @Test
    fun theValidRequestIsExactlyWhatTheAppSends() {
        val request = payloads("wireValid").map { it.second }.single { it.text("type") == "session.reload" }
        val built =
            Wire.objectOf(
                "type" to "session.reload",
                "requestId" to request.text("requestId"),
                "sessionId" to request.text("sessionId"),
            )
        assertEquals(request, built)
    }

    @Test
    fun theValidResultPassesAndEveryInvalidOneIsRefused() {
        val sessionId = "live-session"
        val valid = payloads("wireValid").map { it.second }.single { it.text("type") == "result" }
        validatedReloaded(valid.obj("data"), sessionId)
        val results = payloads("wireInvalid").filter { it.second.text("type") == "result" }
        assertTrue(results.isNotEmpty())
        for ((name, payload) in results)
            assertTrue(name, runCatching { validatedReloaded(payload.obj("data"), sessionId) }.isFailure)
        // A result for another session is no answer for this one.
        assertTrue(runCatching { validatedReloaded(valid.obj("data"), "other") }.isFailure)
    }

    @Test
    fun errorsMapToTheirFailures() {
        fun failure(code: String, message: String? = null) =
            reloadFailure(RemoteRequestException(code, hostMessage = message))
        assertEquals(ReloadFailure.Busy, failure("busy"))
        assertEquals(ReloadFailure.Unsupported, failure("unsupported"))
        assertEquals(ReloadFailure.ConnectionFailure, failure("offline"))
        assertEquals(ReloadFailure.Unknown, failure("timeout"))
        assertEquals(ReloadFailure.Unknown, failure("internal", "Reload result unknown; refresh"))
        assertEquals(ReloadFailure.Failed("boom"), failure("internal", "boom"))
        assertEquals(ReloadFailure.Failed(null), failure("internal", " "))
        assertEquals(ReloadFailure.Failed(null), failure("forbidden"))
        assertEquals(ReloadFailure.Unknown, reloadFailure(IllegalStateException("Request timed out")))
        assertEquals(ReloadFailure.ConnectionFailure, reloadFailure(IllegalStateException("Connection lost")))
        assertTrue(reloadResultUnknown(ReloadFailure.Unknown))
        assertFalse(reloadResultUnknown(ReloadFailure.Busy))
    }
}
