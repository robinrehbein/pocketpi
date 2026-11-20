package de.joinnoah.pi.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** `session.browser.history.state.v1`: parsing `browser.history.state` and keeping it per tab in [BrowserClient]. */
class BrowserHistoryStateTest {
    private val fixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/browser-history-state-v1.json")!!.readText()).jsonObject
    }
    private fun entries(group: String) = fixture.getValue(group).jsonArray.map { it.jsonObject.obj("payload") }

    @Test fun fixtureEventsParseAndInvalidOnesAreRefused() {
        assertEquals(SESSION_BROWSER_HISTORY_STATE_CAPABILITY, fixture.text("capability"))
        val valid = entries("valid")
        assertEquals(4, valid.map { it.flag("canGoBack") to it.flag("canGoForward") }.toSet().size)
        for (payload in valid) {
            val event = parseBrowserHistoryState(payload)
            assertEquals(BrowserHistoryStateEvent(payload.text("sessionId"), payload.text("browserId"), payload.text("incarnation"), payload.text("tabId"), payload.flag("canGoBack"), payload.flag("canGoForward")), event)
        }
        for (entry in fixture.getValue("invalid").jsonArray.map { it.jsonObject })
            assertThrows(entry.text("name"), Exception::class.java) { parseBrowserHistoryState(entry.obj("payload")) }
        // Released clients drop a document carrying the flags, which is why they are a separate event.
        val document = fixture.getValue("invalid").jsonArray.map { it.jsonObject }.single { it.text("name") == "flags on browser.document" }
        assertThrows(Exception::class.java) { parseBrowserDocument(document.obj("payload")) }
    }

    private data class Call(val type: String, val result: CompletableDeferred<JsonObject>)
    private fun tabs(vararg ids: String) = JsonArray(ids.map { Wire.objectOf("id" to it, "title" to it, "url" to "https://example.com/$it") })
    private fun opened(incarnation: String = "i", vararg tabIds: String = arrayOf("tab", "other")) =
        Wire.objectOf("kind" to "browser.opened", "browserId" to "b", "generation" to 1, "incarnation" to incarnation, "tabs" to tabs(*tabIds), "viewportRevision" to 1)
    private fun state(tab: String, back: Boolean, forward: Boolean, browser: String = "b", incarnation: String = "i", session: String = "session") =
        Wire.objectOf("type" to "host.event", "kind" to "browser.history.state", "sessionId" to session, "browserId" to browser, "incarnation" to incarnation, "tabId" to tab, "canGoBack" to back, "canGoForward" to forward)

    /** A client whose view is still opening: the `browser.open` request waits in [calls]. */
    private fun TestScope.opening(calls: MutableList<Call>, negotiated: Boolean = true): BrowserClient {
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ ->
            CompletableDeferred<JsonObject>().also { calls.add(Call(type, it)) }.await()
        }, {}, { "device" }, historyEnabled = { negotiated }, historyStateEnabled = { negotiated })
        assertTrue(client.open("session", 1)); runCurrent()
        return client
    }
    private fun TestScope.open(calls: MutableList<Call>, negotiated: Boolean = true): BrowserClient = opening(calls, negotiated).also {
        calls.removeAt(0).result.complete(opened()); runCurrent()
        calls.removeAll { c -> c.type == "browser.watch" }
    }

    @Test fun theLatestStateIsKeptPerTab() = runTest {
        val client = open(mutableListOf())
        val v = client.view!!
        assertTrue(v.historyState)
        // A tab without a state yet offers neither.
        assertFalse(v.canGoBack); assertFalse(v.canGoForward)
        client.historyState(state("tab", back = true, forward = false))
        client.historyState(state("other", back = false, forward = true))
        assertEquals(mapOf("tab" to BrowserTabHistory(true, false), "other" to BrowserTabHistory(false, true)), client.view!!.tabHistory)
        assertTrue(client.view!!.canGoBack); assertFalse(client.view!!.canGoForward)
        client.historyState(state("tab", back = true, forward = true))
        assertTrue(client.view!!.canGoForward)
        client.selectTab("other")
        assertFalse(client.view!!.canGoBack); assertTrue(client.view!!.canGoForward)
    }

    @Test fun aStateForAnUnknownTabIsKeptAndOneForAnotherBrowserOrIncarnationIsIgnored() = runTest {
        val client = open(mutableListOf())
        client.historyState(state("later", back = true, forward = false))
        assertEquals(BrowserTabHistory(true, false), client.view!!.tabHistory["later"])
        client.historyState(state("tab", back = true, forward = true, incarnation = "old"))
        client.historyState(state("tab", back = true, forward = true, browser = "elsewhere"))
        client.historyState(state("tab", back = true, forward = true, session = "another"))
        client.historyState(JsonObject(state("tab", back = true, forward = true) + ("url" to JsonPrimitive("https://example.com/"))))
        assertNull(client.view!!.tabHistory["tab"])
        assertNull(client.view!!.notice)
    }

    @Test fun aStateBeforeTheOpenedResultIsHandledCountsForThatBrowserOnly() = runTest {
        val calls = mutableListOf<Call>()
        val client = opening(calls)
        client.historyState(state("tab", back = true, forward = false))
        client.historyState(state("other", back = true, forward = true, incarnation = "previous"))
        calls.removeAt(0).result.complete(opened()); runCurrent()
        assertEquals(mapOf("tab" to BrowserTabHistory(true, false)), client.view!!.tabHistory)
    }

    @Test fun aClosedTabLosesItsState() = runTest {
        val calls = mutableListOf<Call>()
        val client = open(calls)
        client.historyState(state("tab", back = true, forward = false))
        client.historyState(state("other", back = true, forward = false))
        client.historyState(state("later", back = true, forward = false))
        assertTrue(client.refresh()); runCurrent()
        calls.single { it.type == "browser.open" }.result.complete(opened("i", "tab")); runCurrent()
        calls.single { it.type == "browser.participants" }.result.complete(Wire.objectOf("kind" to "browser.participants", "browserId" to "b", "generation" to 1, "participants" to JsonArray(emptyList()))); runCurrent()
        // "other" was listed and is gone; "later" was never listed and may still be new.
        assertEquals(setOf("tab", "later"), client.view!!.tabHistory.keys)
    }

    @Test fun closingDisconnectingOrAnotherIncarnationResetsTheStates() = runTest {
        val calls = mutableListOf<Call>()
        val client = open(calls)
        client.historyState(state("tab", back = true, forward = true))
        client.disconnected()
        assertTrue(client.view!!.tabHistory.isEmpty())
        client.historyState(state("tab", back = true, forward = true))
        client.close()
        assertNull(client.view)
        assertTrue(client.open("session", 1)); runCurrent()
        client.historyState(state("tab", back = true, forward = true))
        calls.removeAll { it.type != "browser.open" }
        calls.removeAt(0).result.complete(opened("new")); runCurrent()
        // The browser came back as another incarnation: the state buffered for "i" is stale.
        assertTrue(client.view!!.tabHistory.isEmpty())
        assertFalse(client.view!!.canGoBack)
    }

    @Test fun withoutTheCapabilityStatesAreIgnoredAndBothDirectionsStayOffered() = runTest {
        val client = open(mutableListOf(), negotiated = false)
        client.historyState(state("tab", back = false, forward = false))
        val v = client.view!!
        assertFalse(v.historyState)
        assertTrue(v.tabHistory.isEmpty())
        assertTrue(v.canGoBack); assertTrue(v.canGoForward)
    }
}
