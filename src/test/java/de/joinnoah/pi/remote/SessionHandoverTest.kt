package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHandoverTest {
    private val fixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/session-handover-v1.json")!!.readText()).jsonObject
    }

    private fun entries(group: String) = fixture.getValue(group).jsonArray.map { it.jsonObject }

    private fun name(entry: JsonObject) = entry.getValue("name").jsonPrimitive.content

    private fun payload(entry: JsonObject) = entry.getValue("payload").jsonObject

    private fun valid(name: String) = payload(entries("wireValid").single { name(it) == name })

    private fun JsonObject.data() = getValue("data").jsonObject

    private fun at(event: JsonObject, revision: Int) = JsonObject(event + ("revision" to JsonPrimitive(revision)))

    @Test
    fun fixturePinsTheCapability() {
        assertEquals(SESSION_HANDOVER_CAPABILITY, fixture.getValue("capability").jsonPrimitive.content)
    }

    @Test
    fun everyValidEventParses() {
        val events = entries("wireValid").map(::payload).filter { it.text("type") == "event" }
        assertEquals(11, events.size)
        events.forEach { assertNotNull(it.toString(), sessionHandoverEvent(it)) }
        assertEquals(
            SessionHandover("handover:1", HandoverState.DONE, "new-session", partial = true, live = true),
            sessionHandoverEvent(valid("event-done-partial")),
        )
        assertEquals(HandoverError.TOO_LARGE, sessionHandoverEvent(valid("event-failed-too-large"))?.error)
        assertEquals(HandoverError.entries.toSet(), events.mapNotNull { sessionHandoverEvent(it)?.error }.toSet())
    }

    @Test
    fun everyValidSnapshotFieldParses() {
        assertEquals(
            SessionHandover("handover:1", HandoverState.SUMMARIZING),
            snapshotHandover(valid("snapshot-source-summarizing").data()["handover"]),
        )
        assertEquals(
            SessionHandover("handover:1", HandoverState.DONE, "new-session"),
            snapshotHandover(valid("snapshot-source-done").data()["handover"]),
        )
        for (name in listOf("snapshot-target", "snapshot-target-partial")) {
            val data = valid(name).data()
            assertEquals(HandedOverFrom("old-session", true), parseHandedOverFrom(data["handedOverFrom"]))
            val seed = data.getValue("messages").jsonArray.single().jsonObject
            assertEquals(MessageHandover("old-session", name.endsWith("partial")), messageHandover(seed))
        }
        assertEquals(HandedOverFrom("old-session", false), parseHandedOverFrom(valid("snapshot-target-unseeded").data()["handedOverFrom"]))
    }

    @Test
    fun everyInvalidShapeIsRefused() {
        val invalid = entries("wireInvalid")
        assertEquals(11, invalid.size)
        invalid.forEach { entry ->
            val value = payload(entry)
            val refused =
                when {
                    value.text("type") == "session.handover" -> value != JsonObject(valid("handover").toMap())
                    value.text("type") == "event" -> sessionHandoverEvent(value) == null
                    else -> {
                        val data = value.data()
                        val messages = data.getValue("messages").jsonArray.map { it.jsonObject }
                        parseHandedOverFrom(data["handedOverFrom"]) == null && messages.all { messageHandover(it) == null }
                    }
                }
            assertTrue(name(entry), refused)
        }
    }

    @Test
    fun theTimelineFollowsTheEventsAndIgnoresMalformedOnes() {
        val timeline = Timeline("old-session")
        val snapshot = valid("snapshot-source-summarizing").data()
        assertTrue(timeline.snapshot(JsonObject(snapshot - "handover" + ("revision" to JsonPrimitive(6)))))
        assertNull(timeline.handover)
        timeline.event(valid("event-summarizing"))
        assertEquals(HandoverState.SUMMARIZING, timeline.handover?.state)
        assertTrue(timeline.handover!!.live && timeline.handover!!.running)
        timeline.event(valid("event-switching"))
        assertEquals(HandoverState.SWITCHING, timeline.handover?.state)
        // A malformed event advances the revision only: no snapshot is owed, the state stays.
        val bad = payload(entries("wireInvalid").single { name(it) == "event-done-without-target" })
        assertFalse(timeline.event(at(bad, 9)))
        assertFalse(timeline.unknownKindSeen)
        assertEquals(HandoverState.SWITCHING, timeline.handover?.state)
        timeline.event(at(valid("event-failed-timeout"), 10))
        assertEquals(HandoverError.TIMEOUT, timeline.handover?.error)
        assertFalse(timeline.handover!!.running)
    }

    @Test
    fun aDoneEventNamesTheNewChat() {
        val timeline = Timeline("old-session")
        timeline.snapshot(JsonObject(valid("snapshot-source-summarizing").data() + ("revision" to JsonPrimitive(9))))
        // From the snapshot alone the handover was not seen running.
        assertFalse(timeline.handover!!.live)
        timeline.event(valid("event-done"))
        assertEquals(SessionHandover("handover:1", HandoverState.DONE, "new-session", live = true), timeline.handover)
    }

    @Test
    fun aSnapshotRecoversTheOutcomeAndKeepsWhetherItWasSeenRunning() {
        val reopened = Timeline("old-session")
        reopened.snapshot(valid("snapshot-source-done").data())
        assertEquals(SessionHandover("handover:1", HandoverState.DONE, "new-session"), reopened.handover)
        assertEquals("offline", reopened.status)

        val watching = Timeline("old-session")
        watching.snapshot(JsonObject(valid("snapshot-source-summarizing").data() - "handover" + ("revision" to JsonPrimitive(0))))
        watching.handoverAccepted("handover:1")
        assertEquals(SessionHandover("handover:1", HandoverState.SUMMARIZING, live = true), watching.handover)
        // The resync after a reconnect carries the end: this phone saw it run, so it still follows it.
        watching.snapshot(valid("snapshot-source-done").data())
        assertEquals(SessionHandover("handover:1", HandoverState.DONE, "new-session", live = true), watching.handover)
        // An accepted repeat of a request whose events already arrived changes nothing.
        watching.handoverAccepted("handover:1")
        assertEquals(HandoverState.DONE, watching.handover?.state)
    }

    @Test
    fun theNewChatKnowsItsSource() {
        val timeline = Timeline("new-session")
        timeline.snapshot(valid("snapshot-target").data())
        assertEquals(HandedOverFrom("old-session", true), timeline.handedOverFrom)
        assertNull(timeline.handover)
    }

    private fun worker(id: String = "old-session") =
        Wire.objectOf("id" to id, "origin" to "tui", HOST_WORKER_KEY to true, "status" to "idle")

    private fun idle(
        session: JsonObject = worker(),
        capabilities: Set<String> = setOf(SESSION_HANDOVER_CAPABILITY, HOST_WORKER_V2_CAPABILITY, COMPACT_CAPABILITY),
    ) = RemoteState(
        connected = true,
        status = "idle",
        selection = RemoteSelection("host", "project", session.text("id")),
        session = session,
        capabilities = capabilities,
        contextUsage = SessionContextUsage(session.text("id"), null, null, 160_000, 200_000, 80.0),
    )

    @Test
    fun onlyAHostWorkerOffersTheHandover() {
        assertTrue(handoverAvailable(idle()))
        // A terminal session and an RPC session keep only "Compact now".
        assertFalse(handoverAvailable(idle(Wire.objectOf("id" to "old-session", "origin" to "tui"))))
        assertFalse(handoverAvailable(idle(Wire.objectOf("id" to "old-session", "origin" to "rpc"))))
        // Without the capability, or once the host refused it for this session.
        assertFalse(handoverAvailable(idle(capabilities = setOf(HOST_WORKER_V2_CAPABILITY))))
        assertFalse(
            handoverAvailable(idle().copy(sessionControlUnsupported = mapOf("old-session" to setOf(ExtensionControl.HANDOVER))))
        )
        assertTrue(
            handoverAvailable(idle().copy(sessionControlUnsupported = mapOf("other" to setOf(ExtensionControl.HANDOVER))))
        )
    }

    @Test
    fun theOfferAddsTheHandoverForAWorker() {
        assertEquals(
            setOf(ContextOfferAction.COMPACT, ContextOfferAction.HANDOVER),
            contextOffer(idle(), null, 0L)?.actions,
        )
        assertEquals(
            setOf(ContextOfferAction.COMPACT),
            contextOffer(idle(Wire.objectOf("id" to "old-session", "origin" to "rpc")), null, 0L)?.actions,
        )
        // A worker whose host cannot compact still offers the handover alone.
        assertEquals(
            setOf(ContextOfferAction.HANDOVER),
            contextOffer(idle(capabilities = setOf(SESSION_HANDOVER_CAPABILITY, HOST_WORKER_V2_CAPABILITY)), null, 0L)?.actions,
        )
    }

    @Test
    fun aRunningHandoverBlocksInputAndTheOffer() {
        val running = idle().copy(draft = "Next", handover = SessionHandover("h", HandoverState.SWITCHING, live = true))
        assertTrue(canSendDraft(idle().copy(draft = "Next")))
        assertFalse(canSendDraft(running))
        assertFalse(canSendDraft(idle().copy(draft = "Next", handoverRequesting = true)))
        assertFalse(canCompact(running, 0L))
        assertFalse(canHandover(running, 0L))
        assertNull(contextOffer(running, null, 0L))
        // A failed one leaves the chat usable.
        val failed = running.copy(handover = SessionHandover("h", HandoverState.FAILED, error = HandoverError.CANCELLED))
        assertTrue(canSendDraft(failed))
        assertTrue(canHandover(failed, 0L))
    }

    @Test
    fun continueShowsWhileTheNewChatHoldsOnlyItsSummary() {
        val seed = valid("snapshot-target").data().getValue("messages").jsonArray.single().jsonObject
        val chat = idle(worker("new-session")).copy(messages = listOf(seed), handedOverFrom = HandedOverFrom("old-session", true))
        assertTrue(handoverContinueOffered(chat))
        assertFalse(handoverContinueOffered(chat.copy(status = "running")))
        assertFalse(handoverContinueOffered(chat.copy(sending = true)))
        assertFalse(handoverContinueOffered(chat.copy(handedOverFrom = HandedOverFrom("old-session", false))))
        assertFalse(handoverContinueOffered(chat.copy(messages = listOf(seed, Wire.objectOf("id" to "u", "role" to "user", "text" to "x")))))
        assertFalse(handoverContinueOffered(chat.copy(handedOverFrom = null)))
    }

    @Test
    fun theSummaryShowsAsACardNotABubble() {
        val seed = valid("snapshot-target").data().getValue("messages").jsonArray.single().jsonObject
        val item = conversationItems(listOf(seed)).single() as ConversationItem.Bubble
        assertEquals(MessageHandover("old-session", false), item.handover)
        assertTrue(item.text.startsWith("## Goal"))
        // The same mark on an assistant message is no summary.
        val assistant = payload(entries("wireInvalid").single { name(it) == "message-handover-on-assistant" })
            .data().getValue("messages").jsonArray.single().jsonObject
        assertTrue(conversationItems(listOf(assistant)).filterIsInstance<ConversationItem.Bubble>().all { it.handover == null })
    }

    @Test
    fun everyErrorHasItsOwnMessage() {
        assertEquals(HandoverError.entries.size, HandoverError.entries.map(::handoverErrorMessage).toSet().size)
    }
}
