package de.joinnoah.pi.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BrowserHistoryTest {
    private val fixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/browser-history-v1.json")!!.readText()).jsonObject
    }
    private fun entries(group: String) = fixture.getValue(group).jsonArray.map { it.jsonObject.obj("payload") }

    @Test fun fixtureCommandsRoundTripAndInvalidOnesAreRefused() {
        assertEquals(SESSION_BROWSER_HISTORY_CAPABILITY, fixture.text("capability"))
        val commands = entries("valid").filter { it.text("type") == "browser.history" }
        assertEquals(BROWSER_HISTORY_ACTIONS, commands.map { it.text("action") }.toSet())
        for (payload in commands) {
            val command = parseBrowserCommand(payload) as BrowserCommand.History
            assertEquals(payload, encodeBrowserCommand(command))
        }
        for (payload in entries("invalid")) assertThrows(Exception::class.java) { parseBrowserCommand(payload) }
        val tabs = entries("valid").single { it.text("type") == "result" && it.flag("ok") }
        assertTrue(parseBrowserResult(tabs.obj("data")) is BrowserResult.Tabs)
    }

    private data class Call(val type: String, val fields: Map<String, Any?>, val result: CompletableDeferred<JsonObject>)
    private val tabs = JsonArray(listOf(Wire.objectOf("id" to "tab", "title" to "T", "url" to "https://example.com")))
    private fun opened() = Wire.objectOf("kind" to "browser.opened", "browserId" to "b", "generation" to 1, "incarnation" to "i", "tabs" to tabs, "viewportRevision" to 1)
    private fun lease() = Wire.objectOf("kind" to "browser.lease", "browserId" to "b", "generation" to 1, "leaseId" to "l", "owner" to Wire.objectOf("kind" to "device", "id" to "device"), "viewportRevision" to 1)

    private fun kotlinx.coroutines.test.TestScope.controlled(history: Boolean, calls: MutableList<Call>): BrowserClient {
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, fields ->
            CompletableDeferred<JsonObject>().also { calls.add(Call(type, fields.toMap(), it)) }.await()
        }, {}, { "device" }, historyEnabled = { history })
        client.open("session", 1); runCurrent()
        calls.removeAt(0).result.complete(opened()); runCurrent()
        calls.removeAll { it.type == "browser.watch" }
        assertTrue(client.acquire()); runCurrent()
        calls.first { it.type == "browser.acquire" }.also { calls.remove(it); it.result.complete(lease()) }; runCurrent()
        calls.clear()
        return client
    }

    @Test fun historyIsSentOnlyWhenNegotiated() = runTest {
        val calls = mutableListOf<Call>()
        val client = controlled(history = false, calls)
        assertFalse(client.view!!.history)
        assertFalse(client.history("back"))
        assertTrue(calls.none { it.type == "browser.history" })
    }

    @Test fun backSendsTheSelectedTabAndRefreshes() = runTest {
        val calls = mutableListOf<Call>()
        val client = controlled(history = true, calls)
        assertTrue(client.history("back")); runCurrent()
        val sent = calls.single { it.type == "browser.history" }
        assertEquals("back", sent.fields["action"].toString().trim('"'))
        assertEquals("tab", sent.fields["tabId"].toString().trim('"'))
        assertEquals("l", sent.fields["leaseId"].toString().trim('"'))
        assertFalse(client.history("forward")) // One control request at a time.
        sent.result.complete(Wire.objectOf("kind" to "browser.tabs", "browserId" to "b", "generation" to 1, "tabs" to tabs)); runCurrent()
        assertTrue(calls.any { it.type == "browser.open" })
        assertNull(client.view!!.notice)
        assertTrue(client.view!!.ownsControl)
    }

    @Test fun noHistoryEntryIsBenign() = runTest {
        val calls = mutableListOf<Call>()
        val client = controlled(history = true, calls)
        assertTrue(client.history("forward")); runCurrent()
        calls.single { it.type == "browser.history" }.result.completeExceptionally(RemoteRequestException("not_found", hostMessage = "No browser history entry"))
        runCurrent()
        val view = client.view!!
        assertNull(view.notice)
        assertTrue(view.ownsControl)
        assertEquals("forward", view.historyMissing)
        assertEquals(1L, view.historyMissingCount)
        assertTrue(calls.none { it.type == "browser.detach" })
        // The next control request goes out.
        assertTrue(client.history("back"))
    }

    @Test fun anyOtherFailureIsUncertain() = runTest {
        val calls = mutableListOf<Call>()
        val client = controlled(history = true, calls)
        assertTrue(client.history("reload")); runCurrent()
        calls.single { it.type == "browser.history" }.result.completeExceptionally(RemoteRequestException("not_found"))
        runCurrent()
        // A reload has no "no entry" case: the outcome is in doubt.
        assertEquals(BrowserNotice.UNCERTAIN, client.view!!.notice)
        assertFalse(client.view!!.ownsControl)
    }
}
