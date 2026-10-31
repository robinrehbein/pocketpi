package de.joinnoah.pi.remote

import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrowserClientTest {
    private fun opened() = Wire.objectOf("kind" to "browser.opened", "browserId" to "b", "generation" to 1, "incarnation" to "i", "tabs" to JsonArray(listOf(Wire.objectOf("id" to "tab", "title" to "T", "url" to "https://example.com"))), "viewportRevision" to 1)
    private fun lease(owner: String = "device") = Wire.objectOf("kind" to "browser.lease", "browserId" to "b", "generation" to 1, "leaseId" to "private", "owner" to Wire.objectOf("kind" to "device", "id" to owner), "viewportRevision" to 1)
    private fun watching() = Wire.objectOf("kind" to "browser.watching", "browserId" to "b", "streamId" to "stream", "generation" to 1, "viewportRevision" to 1)
    private fun document(leaseId: String = "private") = Wire.objectOf(
        "type" to "host.event", "kind" to "browser.document", "sessionId" to "session", "browserId" to "b", "incarnation" to "i", "tabId" to "tab", "generation" to 1,
        "fromViewportRevision" to 1, "viewportRevision" to 2, "tabs" to opened().getValue("tabs"),
        "owner" to Wire.objectOf("kind" to "device", "id" to "device"), "control" to Wire.objectOf("leaseId" to leaseId, "nextSequence" to 1),
    )
    private fun waiting(streamId: String = "stream") = Wire.objectOf("type" to "host.event", "kind" to "browser.frame.waiting", "browserId" to "b", "tabId" to "tab", "streamId" to streamId, "generation" to 1, "viewportRevision" to 1, "reason" to "moving")
    private fun frame() = buildJsonObject {
        put("type", "host.event"); put("kind", "browser.frame"); put("browserId", "b"); put("tabId", "tab"); put("streamId", "stream")
        put("generation", 1); put("viewportRevision", 1); put("frameId", 1); put("chunkIndex", 0); put("chunkCount", 1); put("totalBytes", 3)
        put("width", 100); put("height", 100); put("cssWidth", 100); put("cssHeight", 100); put("deviceScale", 1); put("scrollX", 0); put("scrollY", 0)
        put("data", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
    }
    @Test fun openingUnconfiguredTabDoesNotWatchOrDetachAndAcquireWaitsForViewport() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" })
        assertTrue(client.open("session", 1)); runCurrent()
        calls.removeAt(0).second.complete(JsonObject(opened() + ("viewportRevision" to JsonPrimitive(0)))); runCurrent()
        assertTrue(calls.none { it.first == "browser.watch" || it.first == "browser.detach" })
        assertTrue(client.acquire()); runCurrent()
        calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(JsonObject(lease() + ("viewportRevision" to JsonPrimitive(0)))) }; runCurrent()
        assertTrue(client.view!!.ownsControl)
        assertTrue(calls.none { it.first == "browser.watch" || it.first == "browser.detach" })
        assertTrue(client.resize(100, 100, 1.0, "portrait")); runCurrent()
        calls.first { it.first == "browser.viewport" }.also { calls.remove(it); it.second.complete(Wire.objectOf("kind" to "browser.viewport", "browserId" to "b", "generation" to 1, "viewportRevision" to 1)) }; runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        client.frame(frame()); runCurrent()
        assertNotNull(client.view!!.image)
        assertTrue(client.view!!.ownsControl)
        assertTrue(calls.none { it.first == "browser.detach" })
    }
    @Test fun readonlyRevisionZeroRefreshAdoptsHostViewportAndStartsWatch() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" })
        client.open("session", 1); runCurrent()
        calls.removeAt(0).second.complete(JsonObject(opened() + ("viewportRevision" to JsonPrimitive(0)))); runCurrent()
        assertTrue(calls.none { it.first == "browser.watch" })
        assertTrue(client.refresh()); runCurrent()
        assertEquals("browser.open", calls.first().first)
        calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.first { it.first == "browser.participants" }.also { calls.remove(it); it.second.complete(Wire.objectOf("kind" to "browser.participants", "browserId" to "b", "generation" to 1, "participants" to JsonArray(emptyList()))) }; runCurrent()
        assertEquals(1L, client.view!!.viewportRevision)
        assertFalse(client.view!!.ownsControl)
        assertTrue(calls.any { it.first == "browser.watch" })
        assertTrue(calls.none { it.first == "browser.acquire" || it.first == "browser.detach" })
    }
    @Test fun refreshRejectsOlderViewportRevision() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {})
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        client.refresh(); runCurrent()
        calls.first { it.first == "browser.open" }.also { calls.remove(it); it.second.complete(JsonObject(opened() + ("viewportRevision" to JsonPrimitive(0)))) }; runCurrent()
        calls.first { it.first == "browser.participants" }.also { calls.remove(it); it.second.complete(Wire.objectOf("kind" to "browser.participants", "browserId" to "b", "generation" to 1, "participants" to JsonArray(emptyList()))) }; runCurrent()
        assertEquals(1L, client.view!!.viewportRevision)
        assertTrue(calls.none { it.first == "browser.unwatch" })
    }
    @Test fun refreshWithDifferentIncarnationFailsClosed() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        client.acquire(); runCurrent(); calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(lease()) }; runCurrent()
        client.refresh(); runCurrent()
        calls.first { it.first == "browser.open" }.also { calls.remove(it); it.second.complete(JsonObject(opened() + ("incarnation" to JsonPrimitive("replacement")))) }; runCurrent()
        calls.first { it.first == "browser.participants" }.also { calls.remove(it); it.second.complete(Wire.objectOf("kind" to "browser.participants", "browserId" to "b", "generation" to 1, "participants" to JsonArray(emptyList()))) }; runCurrent()
        assertFalse(client.view!!.ownsControl)
        assertEquals(BrowserNotice.FRAME_FAILED, client.view!!.notice)
        assertTrue(calls.any { it.first == "browser.detach" })
    }
    @Test fun refreshMustNotPreemptOwnerDocumentSequence() = runTest {
        data class Call(val type: String, val fields: Array<out Pair<String, Any?>>, val result: CompletableDeferred<JsonObject>)
        val calls = mutableListOf<Call>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, fields -> CompletableDeferred<JsonObject>().also { calls.add(Call(type, fields, it)) }.await() }, {}, { "device" }, recoveryEnabled = { true })
        client.open("session", 1); runCurrent(); calls.removeAt(0).result.complete(opened()); runCurrent()
        calls.first { it.type == "browser.watch" }.also { calls.remove(it); it.result.complete(watching()) }; runCurrent()
        client.acquire(); runCurrent(); calls.first { it.type == "browser.acquire" }.also { calls.remove(it); it.result.complete(lease()) }; runCurrent()
        client.refresh(); runCurrent()
        calls.first { it.type == "browser.open" }.also { calls.remove(it); it.result.complete(JsonObject(opened() + ("viewportRevision" to JsonPrimitive(2)))) }; runCurrent()
        calls.first { it.type == "browser.participants" }.also { calls.remove(it); it.result.complete(Wire.objectOf("kind" to "browser.participants", "browserId" to "b", "generation" to 1, "participants" to JsonArray(emptyList()))) }; runCurrent()
        assertEquals(1L, client.view!!.viewportRevision)
        client.frame(JsonObject(document() + ("control" to Wire.objectOf("leaseId" to "private", "nextSequence" to 2)))); runCurrent()
        assertEquals(2L, client.view!!.viewportRevision)
        assertTrue(client.view!!.ownsControl)
        assertTrue(client.input(BrowserInput.Text("after document"))); runCurrent()
        val input = calls.first { it.type == "browser.input" }
        assertEquals("2", input.fields.first { it.first == "sequence" }.second.toString())
    }
    @Test fun documentDuringRefreshDiscardsOlderMetadata() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" }, recoveryEnabled = { true })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        client.acquire(); runCurrent(); calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(lease()) }; runCurrent()
        client.refresh(); runCurrent()
        val pending = calls.first { it.first == "browser.open" }; calls.remove(pending)
        client.frame(document()); runCurrent()
        pending.second.complete(opened()); runCurrent()
        calls.first { it.first == "browser.participants" }.also { calls.remove(it); it.second.complete(Wire.objectOf("kind" to "browser.participants", "browserId" to "b", "generation" to 1, "participants" to JsonArray(emptyList()))) }; runCurrent()
        assertEquals(2L, client.view!!.viewportRevision)
        assertTrue(client.view!!.ownsControl)
        assertEquals(1L, client.view!!.documentEpoch)
    }
    @Test fun earlyFrameIsAssembledAfterWatchAndAcknowledged() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" })
        assertTrue(client.open("session", 1)); runCurrent()
        assertEquals("browser.open", calls[0].first); calls.removeAt(0).second.complete(opened()); runCurrent()
        assertEquals("browser.watch", calls[0].first)
        client.frame(frame()); assertNull(client.view!!.image)
        calls.removeAt(0).second.complete(watching()); runCurrent()
        assertArrayEquals(byteArrayOf(1,2,3), client.view!!.image!!.jpeg)
        assertEquals("browser.frame.ack", calls[0].first)
    }
    @Test fun acquisitionRequiresSelfAndInputIsBoundedAndUncertain() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.removeAt(0).second.complete(watching()); runCurrent()
        assertTrue(client.acquire()); runCurrent(); calls.removeAt(0).second.complete(lease()); runCurrent()
        // Acquisition switches streams before accepting input.
        assertTrue(client.view!!.ownsControl)
        assertTrue(client.input(BrowserInput.Text("hello")))
        assertFalse(client.input(BrowserInput.Text("again")))
        runCurrent()
        val input = calls.first { it.first == "browser.input" }; calls.remove(input)
        input.second.complete(Wire.objectOf("kind" to "browser.input.ack", "browserId" to "b", "generation" to 1, "sequence" to 0, "status" to "uncertain")); runCurrent()
        assertFalse(client.view!!.ownsControl); assertFalse(client.view!!.inputPending)
        assertEquals(BrowserNotice.UNCERTAIN, client.view!!.notice)
        assertFalse(client.input(BrowserInput.Text("replay")))
    }
    @Test fun hiddenPaneDropsLateAcquireAndDetachesCapturedLease() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.removeAt(0).second.complete(watching()); runCurrent()
        client.acquire(); runCurrent(); val acquire = calls.removeAt(0)
        client.setVisible(false); runCurrent(); acquire.second.complete(lease()); runCurrent()
        assertFalse(client.view!!.ownsControl)
        assertTrue(calls.any { it.first == "browser.detach" })
    }
    @Test fun lateWatchAfterSelectionChangeIsUnwatchedOnOriginalConnection() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        var selected = "session"
        val client = BrowserClient(backgroundScope, { session, _ -> session == selected }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" }, { true })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        val watch = calls.removeAt(0)
        selected = "other"; client.close(); watch.second.complete(watching()); runCurrent()
        assertNull(client.view)
        assertTrue(calls.any { it.first == "browser.unwatch" })
        client.frame(frame()); assertNull(client.view)
    }
    @Test fun disconnectClearsFrameAndLateInputCannotRestoreControl() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        var connected = true
        val client = BrowserClient(backgroundScope, { _, _ -> connected }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" }, { connected })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.removeAt(0).second.complete(watching()); runCurrent(); client.frame(frame()); runCurrent()
        client.acquire(); runCurrent(); calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(lease()) }; runCurrent()
        assertTrue(client.input(BrowserInput.Text("hello"))); runCurrent()
        val pending = calls.first { it.first == "browser.input" }; calls.remove(pending)
        connected = false; client.disconnected()
        pending.second.complete(Wire.objectOf("kind" to "browser.input.ack", "browserId" to "b", "generation" to 1, "sequence" to 0, "status" to "accepted")); runCurrent()
        assertNull(client.view!!.image); assertFalse(client.view!!.ownsControl); assertFalse(client.view!!.inputPending)
        assertEquals(BrowserNotice.DISCONNECTED, client.view!!.notice)
    }

    @Test fun interruptedInitialOpenResumesWithoutLosingPaneOrAcceptingOldReply() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val views = mutableListOf<BrowserView?>()
        var connection = 1L
        val client = BrowserClient(backgroundScope, { _, c -> c == connection }, { _, type, _ ->
            CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await()
        }, { views.add(it) })
        assertTrue(client.open("session", 1)); runCurrent()
        val oldOpen = calls.removeAt(0)
        connection = 2; client.disconnected()
        val afterDisconnect = views.size
        assertTrue(client.open("session", 2)); runCurrent()
        val newOpen = calls.first { it.first == "browser.open" }; calls.remove(newOpen)
        newOpen.second.complete(opened()); runCurrent()
        oldOpen.second.complete(JsonObject(opened() + ("browserId" to JsonPrimitive("old")))); runCurrent()
        assertTrue(views.drop(afterDisconnect).none { it == null || it.sessionId != "session" })
        assertEquals("b", client.view?.browserId)
        assertFalse(client.view!!.ownsControl)
        assertTrue(calls.none { it.first == "browser.acquire" || it.first == "browser.input" })
    }

    @Test fun resumeDoesNotReopenAHealthyOrClosedPane() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ ->
            CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await()
        }, {}, { "device" })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        assertFalse(client.resumeIfDisconnected("session", 1))
        assertEquals("b", client.view?.browserId)
        assertTrue(calls.none { it.first == "browser.open" })
        client.close(); runCurrent()
        assertFalse(client.resumeIfDisconnected("session", 2))
        assertNull(client.view)
        assertTrue(calls.none { it.first == "browser.open" })
    }

    @Test fun reconnectPreservesSelectedTabAndUncertainInputWithoutReplaying() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val views = mutableListOf<BrowserView?>()
        var connection = 1L
        val client = BrowserClient(backgroundScope, { _, c -> c == connection }, { _, type, _ ->
            CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await()
        }, { views.add(it) }, { "device" }, { it == connection })
        val tabs = JsonArray(listOf(
            Wire.objectOf("id" to "tab", "title" to "First", "url" to "https://example.com"),
            Wire.objectOf("id" to "second", "title" to "Second", "url" to "https://example.com/two"),
        ))
        val openedTwo = JsonObject(opened() + ("tabs" to tabs))
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(openedTwo); runCurrent()
        client.selectTab("second"); runCurrent()
        client.acquire(); runCurrent()
        calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(lease()) }; runCurrent()
        assertTrue(client.input(BrowserInput.Text("possibly applied"))); runCurrent()
        val oldInput = calls.first { it.first == "browser.input" }; calls.remove(oldInput)
        connection = 2; client.disconnected()
        val afterDisconnect = views.size
        assertTrue(client.open("session", 2)); runCurrent()
        calls.last { it.first == "browser.open" }.also { calls.remove(it); it.second.complete(openedTwo) }; runCurrent()
        calls.firstOrNull { it.first == "browser.participants" }?.also { calls.remove(it); it.second.complete(Wire.objectOf("kind" to "browser.participants", "browserId" to "b", "generation" to 1, "participants" to JsonArray(emptyList()))) }; runCurrent()
        oldInput.second.complete(Wire.objectOf("kind" to "browser.input.ack", "browserId" to "b", "generation" to 1, "sequence" to 0, "status" to "accepted")); runCurrent()
        assertTrue(views.drop(afterDisconnect).none { it == null })
        assertEquals("second", client.view!!.selectedTabId)
        assertFalse(client.view!!.ownsControl)
        assertEquals(BrowserNotice.UNCERTAIN, client.view!!.notice)
        assertFalse(client.input(BrowserInput.Text("replay")))
        assertTrue(calls.none { it.first == "browser.acquire" || it.first == "browser.input" })
    }

    @Test fun duplicateAcquireIsNotQueuedWhileFirstReplyIsPending() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        assertTrue(client.acquire())
        assertFalse(client.acquire())
        runCurrent()
        assertEquals(1, calls.count { it.first == "browser.acquire" })
    }
    @Test fun inputAckWithDifferentGenerationInvalidatesLease() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        client.acquire(); runCurrent(); calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(lease()) }; runCurrent()
        assertTrue(client.input(BrowserInput.Text("hello"))); runCurrent()
        calls.first { it.first == "browser.input" }.also { calls.remove(it); it.second.complete(Wire.objectOf("kind" to "browser.input.ack", "browserId" to "b", "generation" to 2, "sequence" to 0, "status" to "accepted")) }; runCurrent()
        assertFalse(client.view!!.ownsControl)
        assertEquals(BrowserNotice.UNCERTAIN, client.view!!.notice)
    }

    @Test fun lateAckFailureCannotCloseReplacementStreamWithSameId() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.removeAt(0).second.complete(watching()); runCurrent()
        client.frame(frame()); runCurrent()
        val oldAck = calls.first { it.first == "browser.frame.ack" }; calls.remove(oldAck)
        client.setVisible(false); client.setVisible(true); runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        oldAck.second.completeExceptionally(IllegalStateException()); runCurrent()
        client.frame(frame()); runCurrent()
        assertNotNull(client.view!!.image)
        assertTrue(calls.any { it.first == "browser.frame.ack" })
    }

    @Test fun hideDuringOpenStillReceivesMetadataAndCanWatchWhenShown() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {})
        client.open("session", 1); runCurrent(); val pending = calls.removeAt(0)
        client.setVisible(false); pending.second.complete(opened()); runCurrent()
        assertEquals("b", client.view!!.browserId); assertFalse(client.view!!.opening)
        assertTrue(calls.none { it.first == "browser.watch" })
        client.setVisible(true); runCurrent(); assertTrue(calls.any { it.first == "browser.watch" })
    }
    @Test fun earlyOldStreamDoesNotDisplaceNewStreamsFirstChunk() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {})
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.removeAt(0).second.complete(watching()); runCurrent()
        client.setVisible(false); client.setVisible(true); runCurrent()
        val nextWatch = calls.first { it.first == "browser.watch" }; calls.remove(nextWatch)
        client.frame(frame())
        val newFrame = JsonObject(frame() + ("streamId" to JsonPrimitive("newstream")))
        client.frame(newFrame)
        nextWatch.second.complete(JsonObject(watching() + ("streamId" to JsonPrimitive("newstream")))); runCurrent()
        assertNotNull(client.view!!.image)
        assertTrue(calls.any { it.first == "browser.frame.ack" })
    }
    @Test fun refreshRequestsAreCoalescedAndCannotRollback() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {})
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        repeat(50) { client.refresh() }; runCurrent()
        assertEquals(1, calls.count { it.first == "browser.open" })
    }
    @Test fun mutationsAreBoundedAndResizeDoesNotSendDuplicateInFlight() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        client.acquire(); runCurrent(); calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(lease()) }; runCurrent()
        assertTrue(client.resize(100,100,1.0,"portrait"))
        repeat(50) { client.resize(100,100,1.0,"portrait") }
        assertFalse(client.navigate("https://example.com"))
        runCurrent(); assertEquals(1, calls.count { it.first == "browser.viewport" })
    }

    @Test fun inputSequenceStartsAtZeroForEachNewLease() = runTest {
        data class Call(val type: String, val fields: Array<out Pair<String, Any?>>, val result: CompletableDeferred<JsonObject>)
        val calls = mutableListOf<Call>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, fields -> CompletableDeferred<JsonObject>().also { calls.add(Call(type, fields, it)) }.await() }, {}, { "device" })
        client.open("session", 1); runCurrent(); calls.removeAt(0).result.complete(opened()); runCurrent()
        client.acquire(); runCurrent(); calls.first { it.type == "browser.acquire" }.also { calls.remove(it); it.result.complete(lease()) }; runCurrent()
        assertTrue(client.input(BrowserInput.Text("one"))); runCurrent()
        val first = calls.first { it.type == "browser.input" }; calls.remove(first)
        assertEquals("0", first.fields.first { it.first == "sequence" }.second.toString())
        first.result.complete(Wire.objectOf("kind" to "browser.input.ack", "browserId" to "b", "generation" to 1, "sequence" to 0, "status" to "accepted")); runCurrent()
        client.setVisible(false); client.setVisible(true); runCurrent()
        calls.first { it.type == "browser.detach" }.also { calls.remove(it); it.result.complete(Wire.objectOf("kind" to "browser.released", "browserId" to "b", "generation" to 2)) }; runCurrent()
        assertTrue(client.acquire()); runCurrent(); calls.first { it.type == "browser.acquire" }.also { calls.remove(it); it.result.complete(JsonObject(lease() + ("generation" to JsonPrimitive(2)))) }; runCurrent()
        assertTrue(client.input(BrowserInput.Text("two"))); runCurrent()
        assertEquals("0", calls.first { it.type == "browser.input" }.fields.first { it.first == "sequence" }.second.toString())
    }
    @Test fun silentWatchExpiresAndClearsOldImage() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {})
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.removeAt(0).second.complete(watching()); runCurrent()
        client.frame(frame()); runCurrent(); assertNotNull(client.view!!.image)
        advanceTimeBy(6000); runCurrent()
        assertNull(client.view!!.image); assertEquals(BrowserNotice.FRAME_FAILED, client.view!!.notice)
    }

    @Test fun acquireAcceptsCorrelatedDeviceOwnerWithOpaquePeerId() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "persistent-device-id" })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        client.acquire(); runCurrent(); calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(lease("opaque-peer-id")) }; runCurrent()
        assertTrue(client.view!!.ownsControl)
    }
    @Test fun uncertainInputReleasesOldLeaseBeforeAnotherAcquire() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        client.acquire(); runCurrent(); calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(lease()) }; runCurrent()
        assertTrue(client.input(BrowserInput.Text("possibly delivered"))); runCurrent()
        calls.first { it.first == "browser.input" }.also { calls.remove(it); it.second.completeExceptionally(IllegalStateException("timeout")) }; runCurrent()
        assertFalse(client.acquire())
        val detach = calls.first { it.first == "browser.detach" }; calls.remove(detach)
        detach.second.complete(Wire.objectOf("kind" to "browser.released", "browserId" to "b", "generation" to 2)); runCurrent()
        assertEquals(2L, client.view!!.generation)
        assertTrue(client.acquire()); runCurrent()
        assertEquals(1, calls.count { it.first == "browser.acquire" })
    }
    @Test fun delayedResizeForOldTabDoesNotSuppressSameSizeForNewTab() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" })
        client.open("session", 1); runCurrent()
        val twoTabs = JsonArray(listOf(Wire.objectOf("id" to "tab", "title" to "T", "url" to "https://example.com"), Wire.objectOf("id" to "other", "title" to "O", "url" to "https://example.com")))
        calls.removeAt(0).second.complete(JsonObject(opened() + ("tabs" to twoTabs))); runCurrent()
        client.acquire(); runCurrent(); calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(lease()) }; runCurrent()
        assertTrue(client.resize(100,100,1.0,"portrait")); runCurrent()
        val first = calls.first { it.first == "browser.viewport" }; calls.remove(first)
        client.selectTab("other"); assertTrue(client.resize(100,100,1.0,"portrait"))
        first.second.complete(Wire.objectOf("kind" to "browser.viewport", "browserId" to "b", "generation" to 1, "viewportRevision" to 2)); runCurrent()
        assertEquals(1, calls.count { it.first == "browser.viewport" })
    }

    @Test fun navigateUnwatchesBeforeCommandAndWatchesSameTabAgain() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        client.acquire(); runCurrent(); calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(lease()) }; runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        calls.clear()
        assertTrue(client.navigate("https://example.com/new")); runCurrent()
        assertEquals(listOf("browser.unwatch", "browser.navigate"), calls.map { it.first })
        calls.first { it.first == "browser.navigate" }.also { calls.remove(it); it.second.complete(Wire.objectOf("kind" to "browser.tabs", "browserId" to "b", "generation" to 1, "tabs" to opened().getValue("tabs"))) }; runCurrent()
        calls.first { it.first == "browser.open" }.also { calls.remove(it); it.second.complete(opened()) }; runCurrent()
        calls.first { it.first == "browser.participants" }.also { calls.remove(it); it.second.complete(Wire.objectOf("kind" to "browser.participants", "browserId" to "b", "generation" to 1, "participants" to JsonArray(emptyList()))) }; runCurrent()
        assertTrue(calls.any { it.first == "browser.watch" })
    }

    @Test fun frameTimeoutAfterAcceptedDownReleasesLeaseBeforeMoreInput() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        client.acquire(); runCurrent(); calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(lease()) }; runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        assertTrue(client.input(BrowserInput.Pointer(10.0, 10.0, "down", "left"))); runCurrent()
        calls.first { it.first == "browser.input" }.also { calls.remove(it); it.second.complete(Wire.objectOf("kind" to "browser.input.ack", "browserId" to "b", "generation" to 1, "sequence" to 0, "status" to "accepted")) }; runCurrent()
        advanceTimeBy(6000); runCurrent()
        assertFalse(client.view!!.ownsControl)
        assertEquals(BrowserNotice.FRAME_FAILED, client.view!!.notice)
        assertFalse(client.input(BrowserInput.Pointer(10.0, 10.0, "down", "left")))
        assertTrue(calls.any { it.first == "browser.detach" })
    }

    @Test fun frameFailureDetachFailureClosesOnlyCapturedConnection() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val closed = mutableListOf<Long>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" }, { it == 9L }, { closed.add(it) })
        client.open("session", 9); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        client.acquire(); runCurrent(); calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(lease()) }; runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        advanceTimeBy(6000); runCurrent()
        assertFalse(client.view!!.ownsControl); assertFalse(client.acquire())
        calls.first { it.first == "browser.detach" }.also { calls.remove(it); it.second.completeExceptionally(IllegalStateException("lost")) }; runCurrent()
        assertEquals(listOf(9L), closed)
    }

    @Test fun failedWatchKeepsPendingAcquireReservedUntilLateGrantIsReleased() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        val watch = calls.first { it.first == "browser.watch" }; calls.remove(watch)
        assertTrue(client.acquire()); runCurrent()
        val acquire = calls.first { it.first == "browser.acquire" }; calls.remove(acquire)
        watch.second.completeExceptionally(IllegalStateException("watch lost")); runCurrent()
        assertFalse(client.acquire())
        acquire.second.complete(lease()); runCurrent()
        assertFalse(client.view!!.ownsControl)
        assertTrue(calls.any { it.first == "browser.detach" })
        assertFalse(client.acquire())
    }

    @Test fun pendingAcquireRemainsReservedAcrossFrameFailureAndHideShow() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        val watch = calls.first { it.first == "browser.watch" }; calls.remove(watch)
        assertTrue(client.acquire()); runCurrent()
        watch.second.completeExceptionally(IllegalStateException("lost")); runCurrent()
        client.setVisible(false); client.setVisible(true); runCurrent()
        assertFalse(client.acquire())
        assertEquals(1, calls.count { it.first == "browser.acquire" })
    }
    @Test fun pendingAcquireRemainsReservedAcrossCloseAndReopenOnSameConnection() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, { "device" })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        assertTrue(client.acquire()); runCurrent()
        client.close(); assertTrue(client.open("session", 1)); runCurrent()
        calls.last { it.first == "browser.open" }.also { calls.remove(it); it.second.complete(opened()) }; runCurrent()
        assertFalse(client.acquire())
        assertEquals(1, calls.count { it.first == "browser.acquire" })
    }

    @Test fun documentTransitionPreservesConfirmedLeaseAndUsesHostSequenceWithoutReplay() = runTest {
        data class Call(val type: String, val fields: Array<out Pair<String, Any?>>, val result: CompletableDeferred<JsonObject>)
        val calls = mutableListOf<Call>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, fields -> CompletableDeferred<JsonObject>().also { calls.add(Call(type, fields, it)) }.await() }, {}, recoveryEnabled = { true })
        client.open("session", 1); runCurrent(); calls.removeAt(0).result.complete(opened()); runCurrent()
        calls.first { it.type == "browser.watch" }.also { calls.remove(it); it.result.complete(watching()) }; runCurrent()
        client.acquire(); runCurrent(); calls.first { it.type == "browser.acquire" }.also { calls.remove(it); it.result.complete(lease()) }; runCurrent()
        calls.first { it.type == "browser.watch" }.also { calls.remove(it); it.result.complete(watching()) }; runCurrent()
        assertTrue(client.input(BrowserInput.Text("uncertain"))); runCurrent()
        val pending = calls.first { it.type == "browser.input" }; calls.remove(pending)
        client.frame(document()); runCurrent()
        assertTrue(client.view!!.ownsControl); assertEquals(2L, client.view!!.viewportRevision)
        assertFalse(client.input(BrowserInput.Text("not while previous command is unresolved")))
        pending.result.complete(Wire.objectOf("kind" to "browser.input.ack", "browserId" to "b", "generation" to 1, "sequence" to 0, "status" to "accepted")); runCurrent()
        assertNull(client.view!!.receipt)
        assertTrue(client.input(BrowserInput.Text("new"))); runCurrent()
        val next = calls.first { it.type == "browser.input" }
        assertEquals("1", next.fields.first { it.first == "sequence" }.second.toString())
        assertEquals(1, calls.count { it.type == "browser.input" }); assertTrue(calls.none { it.type == "browser.acquire" })
    }

    @Test fun documentWithDifferentLeaseFailsClosed() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, recoveryEnabled = { true })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        client.acquire(); runCurrent(); calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(lease()) }; runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        client.frame(document("other")); runCurrent()
        assertFalse(client.view!!.ownsControl); assertEquals(2L, client.view!!.viewportRevision); assertFalse(client.input(BrowserInput.Text("must not send")))
        assertTrue(calls.none { it.first == "browser.acquire" })
    }

    @Test fun waitingHeartbeatExtendsOnlyMatchingStreamDeadline() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, recoveryEnabled = { true })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        advanceTimeBy(5000); client.frame(waiting()); runCurrent()
        advanceTimeBy(1000); runCurrent(); assertNull(client.view!!.notice)
        client.frame(waiting("old")); runCurrent()
        advanceTimeBy(5000); runCurrent(); assertEquals(BrowserNotice.FRAME_FAILED, client.view!!.notice)
    }

    @Test fun documentDuringAcquireKeepsReservationAndReleasesLateGrant() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, recoveryEnabled = { true })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        assertTrue(client.acquire()); runCurrent(); val acquire = calls.first { it.first == "browser.acquire" }; calls.remove(acquire)
        client.frame(JsonObject(document().filterKeys { it != "control" })); runCurrent()
        assertFalse(client.view!!.ownsControl); assertFalse(client.acquire())
        acquire.second.complete(lease()); runCurrent()
        assertFalse(client.view!!.ownsControl); assertTrue(calls.any { it.first == "browser.detach" }); assertFalse(client.acquire())
    }

    @Test fun documentRequiresCurrentScopeAndAcceptsCoveredRevisionRange() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, recoveryEnabled = { true })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        client.frame(JsonObject(document() + ("incarnation" to JsonPrimitive("old")))); runCurrent()
        assertEquals(1L, client.view!!.viewportRevision)
        client.frame(JsonObject(document() + mapOf("fromViewportRevision" to JsonPrimitive(0), "viewportRevision" to JsonPrimitive(3)))); runCurrent()
        assertEquals(3L, client.view!!.viewportRevision); assertFalse(client.view!!.ownsControl)
        client.frame(document()); runCurrent(); assertEquals(3L, client.view!!.viewportRevision)
    }

    @Test fun recoveryCapabilityIsOptInForOpenAndWatch() = runTest {
        data class Call(val type: String, val fields: Array<out Pair<String, Any?>>, val result: CompletableDeferred<JsonObject>)
        suspend fun check(recovery: Boolean) {
            val calls = mutableListOf<Call>()
            val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, fields -> CompletableDeferred<JsonObject>().also { calls.add(Call(type, fields, it)) }.await() }, {}, recoveryEnabled = { recovery })
            client.open("session", 1); runCurrent()
            val open = calls.removeAt(0); assertEquals(recovery, open.fields.any { it.first == "recovery" && it.second == JsonPrimitive(true) })
            open.result.complete(opened()); runCurrent()
            val watch = calls.first { it.type == "browser.watch" }; assertEquals(recovery, watch.fields.any { it.first == "recovery" && it.second == true })
            client.close()
        }
        check(false); check(true)
    }

    @Test fun hiddenDocumentUpdatesReadOnlyRevisionWithoutStartingWatch() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, recoveryEnabled = { true })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        client.setVisible(false); runCurrent(); calls.clear()
        client.frame(JsonObject(document().filterKeys { it != "control" })); runCurrent()
        assertEquals(2L, client.view!!.viewportRevision); assertFalse(client.view!!.ownsControl); assertTrue(calls.none { it.first == "browser.watch" })
    }

    @Test fun uncoveredDocumentRevisionFailsClosed() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, recoveryEnabled = { true })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        client.acquire(); runCurrent(); calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(lease()) }; runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        client.frame(JsonObject(document() + mapOf("fromViewportRevision" to JsonPrimitive(2), "viewportRevision" to JsonPrimitive(3)))); runCurrent()
        assertFalse(client.view!!.ownsControl); assertTrue(calls.any { it.first == "browser.detach" })
    }

    @Test fun failedReplacementWatchKeepsPreviousInputReservedUntilItSettles() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, recoveryEnabled = { true })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        client.acquire(); runCurrent(); calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(lease()) }; runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        assertTrue(client.input(BrowserInput.Text("pending"))); runCurrent()
        val input = calls.first { it.first == "browser.input" }; calls.remove(input)
        client.frame(document()); runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.completeExceptionally(IllegalStateException("watch failed")) }; runCurrent()
        calls.first { it.first == "browser.detach" }.also { calls.remove(it); it.second.complete(Wire.objectOf("kind" to "browser.released", "browserId" to "b", "generation" to 2)) }; runCurrent()
        assertFalse(client.acquire())
        input.second.complete(Wire.objectOf("kind" to "browser.input.ack", "browserId" to "b", "generation" to 1, "sequence" to 0, "status" to "accepted")); runCurrent()
        assertTrue(client.acquire())
    }

    @Test fun documentCanReuseSequenceNotYetAdmittedByHostWithoutReplayingInput() = runTest {
        data class Call(val type: String, val fields: Array<out Pair<String, Any?>>, val result: CompletableDeferred<JsonObject>)
        val calls = mutableListOf<Call>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, fields -> CompletableDeferred<JsonObject>().also { calls.add(Call(type, fields, it)) }.await() }, {}, recoveryEnabled = { true })
        client.open("session", 1); runCurrent(); calls.removeAt(0).result.complete(opened()); runCurrent()
        calls.first { it.type == "browser.watch" }.also { calls.remove(it); it.result.complete(watching()) }; runCurrent()
        client.acquire(); runCurrent(); calls.first { it.type == "browser.acquire" }.also { calls.remove(it); it.result.complete(lease()) }; runCurrent()
        calls.first { it.type == "browser.watch" }.also { calls.remove(it); it.result.complete(watching()) }; runCurrent()
        assertTrue(client.input(BrowserInput.Text("not admitted"))); runCurrent()
        val old = calls.first { it.type == "browser.input" }; calls.remove(old)
        val control = Wire.objectOf("leaseId" to "private", "nextSequence" to 0)
        client.frame(JsonObject(document() + ("control" to control))); runCurrent()
        assertTrue(client.view!!.ownsControl)
        old.result.completeExceptionally(IllegalStateException("old viewport rejected")); runCurrent()
        assertTrue(client.input(BrowserInput.Text("new explicit input"))); runCurrent()
        val next = calls.first { it.type == "browser.input" }
        assertEquals("0", next.fields.first { it.first == "sequence" }.second.toString())
        assertEquals(1, calls.count { it.type == "browser.input" }); assertTrue(calls.none { it.type == "browser.acquire" })
    }

    @Test fun documentCannotRollBackConfirmedHostSequence() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, recoveryEnabled = { true })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        client.acquire(); runCurrent(); calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(lease()) }; runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        assertTrue(client.input(BrowserInput.Text("accepted"))); runCurrent()
        calls.first { it.first == "browser.input" }.also { calls.remove(it); it.second.complete(Wire.objectOf("kind" to "browser.input.ack", "browserId" to "b", "generation" to 1, "sequence" to 0, "status" to "accepted")) }; runCurrent()
        client.frame(JsonObject(document() + ("control" to Wire.objectOf("leaseId" to "private", "nextSequence" to 0)))); runCurrent()
        assertFalse(client.view!!.ownsControl); assertTrue(calls.any { it.first == "browser.detach" })
    }

    @Test fun documentCannotRollBackPreviousAuthoritativeNextSequence() = runTest {
        val calls = mutableListOf<Pair<String, CompletableDeferred<JsonObject>>>()
        val client = BrowserClient(backgroundScope, { _, _ -> true }, { _, type, _ -> CompletableDeferred<JsonObject>().also { calls.add(type to it) }.await() }, {}, recoveryEnabled = { true })
        client.open("session", 1); runCurrent(); calls.removeAt(0).second.complete(opened()); runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        client.acquire(); runCurrent(); calls.first { it.first == "browser.acquire" }.also { calls.remove(it); it.second.complete(lease()) }; runCurrent()
        calls.first { it.first == "browser.watch" }.also { calls.remove(it); it.second.complete(watching()) }; runCurrent()
        client.frame(document()); runCurrent(); assertTrue(client.view!!.ownsControl)
        val olderSequence = JsonObject(document() + mapOf("fromViewportRevision" to JsonPrimitive(2), "viewportRevision" to JsonPrimitive(3), "control" to Wire.objectOf("leaseId" to "private", "nextSequence" to 0)))
        client.frame(olderSequence); runCurrent()
        assertFalse(client.view!!.ownsControl); assertTrue(calls.any { it.first == "browser.detach" })
    }

}
