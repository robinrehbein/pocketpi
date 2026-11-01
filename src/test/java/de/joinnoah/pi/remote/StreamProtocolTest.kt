package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * The shared `stream-v1.json` and `resume-v1.json` fixtures: what the app builds equals the valid
 * requests, what it parses accepts the valid results and events, and every invalid one is refused.
 */
class StreamProtocolTest {
    private fun load(name: String) =
        Wire.json.parseToJsonElement(javaClass.getResource("/$name")!!.readText()).jsonObject

    private val stream = load("stream-v1.json")
    private val resume = load("resume-v1.json")

    private fun entries(fixture: JsonObject, list: String) = fixture.array(list).map { it.text("name") to it.obj("payload") }

    private fun valid(fixture: JsonObject, name: String) = entries(fixture, "wireValid").single { it.first == name }.second

    private fun data(payload: JsonObject) = payload.obj("data")

    private fun isResult(payload: JsonObject, kind: String) =
        payload.text("type") == "result" && payload.obj("data").text("kind") == kind

    private fun isEvent(payload: JsonObject) = payload.text("type") == "event"

    private fun ids(value: JsonObject) = (value.getValue("sessionIds") as JsonArray).map { (it as JsonPrimitive).content }

    @Test
    fun theFixturesNameTheConstantsTheAppUses() {
        assertEquals(1L, stream.long("version"))
        assertEquals(STREAM_CAPABILITY, stream.text("capability"))
        assertEquals(MAX_FOCUS_SESSIONS.toLong(), stream.long("maxFocusSessions"))
        assertEquals(RESUME_REQUEST_PREFIX, resume.text("prefix"))
        assertEquals(MAX_SESSION_EVENTS.toLong(), resume.long("maxEvents"))
    }

    @Test
    fun theValidCommandsAreExactlyWhatTheAppSends() {
        for (name in listOf("focus-none", "focus-one", "focus-four")) {
            val request = valid(stream, name)
            assertEquals(name, request, sessionFocusPayload(request.text("requestId"), ids(request)))
        }
        val ping = valid(stream, "ping")
        assertEquals(ping, remotePingPayload(ping.text("requestId")))
        for (name in listOf("ack", "ack-zero", "ack-max-sequence")) {
            val ack = valid(stream, name)
            assertEquals(name, ack, remoteAckPayload(ack.long("seq")))
        }
        // Every valid command in the fixture is one of those.
        val commands = entries(stream, "wireValid").filter { it.second.text("type") in setOf("session.focus", "remote.ping", "remote.ack") }
        assertEquals(7, commands.size)
    }

    @Test
    fun theAppNeverBuildsAnInvalidCommand() {
        fun refused(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java) { block() }
        // The other invalid commands carry keys, types or fractions the builders cannot produce.
        val focus =
            entries(stream, "wireInvalid").filter {
                it.second.text("type") == "session.focus" && it.second.keys == setOf("type", "requestId", "sessionIds") &&
                    it.second.getValue("sessionIds") is JsonArray
            }
        assertEquals(setOf("focus-five-ids", "focus-duplicate-ids", "focus-id-with-whitespace", "focus-empty-id"), focus.map { it.first }.toSet())
        for ((name, payload) in focus) assertTrue(name, runCatching { sessionFocusPayload(payload.text("requestId"), ids(payload)) }.isFailure)
        refused { sessionFocusPayload("focus", listOf("a", "b", "c", "d", "e")) }
        refused { sessionFocusPayload("focus", listOf("a", "a")) }
        refused { sessionFocusPayload("focus", listOf("a b")) }
        refused { sessionFocusPayload("focus", listOf("")) }
        refused { remoteAckPayload(-1) }
        refused { remoteAckPayload(MAX_ACK_SEQUENCE + 1) }
        refused { remotePingPayload("") }
    }

    @Test
    fun theFocusAndPongResultsPassAndTheInvalidOnesAreRefused() {
        for ((name, payload) in entries(stream, "wireValid").filter { isResult(it.second, "session.focus") })
            assertEquals(name, ids(payload.obj("data")), sessionFocusResult(data(payload)))
        assertEquals(emptyList<String>(), sessionFocusResult(data(valid(stream, "focus-result-none"))))
        assertEquals(4, sessionFocusResult(data(valid(stream, "focus-result-four"))).size)
        assertNull(pongResult(data(valid(stream, "pong"))))
        assertEquals(42L, pongResult(data(valid(stream, "pong-with-relay-rtt"))))
        assertEquals(0L, pongResult(data(valid(stream, "pong-relay-rtt-zero"))))
        assertEquals(MAX_RELAY_RTT_MS, pongResult(data(valid(stream, "pong-relay-rtt-max"))))

        val invalid = entries(stream, "wireInvalid")
        val focus = invalid.filter { isResult(it.second, "session.focus") }
        val pong = invalid.filter { isResult(it.second, "pong") }
        assertEquals(3, focus.size)
        assertEquals(4, pong.size)
        for ((name, payload) in focus) assertTrue(name, runCatching { sessionFocusResult(data(payload)) }.isFailure)
        for ((name, payload) in pong) assertTrue(name, runCatching { pongResult(data(payload)) }.isFailure)
    }

    @Test
    fun theEventsWithAfterPassAndTheMalformedOnesAreRefused() {
        val events = entries(stream, "wireValid").filter { isEvent(it.second) }
        assertEquals(17, events.size)
        for ((name, event) in events) {
            val expected = if ("after" in event) event.long("after") else null
            assertEquals(name, expected, eventAfter(event))
        }
        val invalid = entries(stream, "wireInvalid").filter { isEvent(it.second) }
        assertEquals(7, invalid.size)
        for ((name, event) in invalid) assertTrue(name, runCatching { eventAfter(event) }.isFailure)
    }

    @Test
    fun theRequestIdsFollowTheGrammar() {
        val ids = resume.obj("requestIds")
        for (entry in ids.array("valid")) {
            val parsed = parseResumeRequest(entry.text("requestId"))
            val expected = entry.obj("value")
            assertEquals(entry.text("name"), ResumeBase(expected.optionalText("epoch"), expected.long("revision")), parsed)
        }
        for (entry in ids.array("invalid")) assertNull(entry.text("name"), parseResumeRequest(entry.text("requestId")))
    }

    @Test
    fun everyRequestIdTheAppGeneratesIsValidAndNeverOneOfTheInvalidShapes() {
        val epochs = listOf("Q2xhdWRlUmVzdW1lRXBvY2", "zX9_-aB3cD4eF5gH6iJ7kL")
        repeat(200) {
            val fresh = freshRequestId()
            assertEquals(ResumeBase(null, 0), parseResumeRequest(fresh))
            assertTrue(fresh.startsWith("resume.v1:-:0:"))
            for (epoch in epochs) {
                val revision = (it * 7919L) % 100000
                val id = resumeRequestId(epoch, revision)
                assertEquals(ResumeBase(epoch, revision), parseResumeRequest(id))
                assertTrue(id.encodeToByteArray().size <= 256)
            }
        }
        assertEquals(ResumeBase("Q2xhdWRlUmVzdW1lRXBvY2", 9007199254740991L), parseResumeRequest(resumeRequestId("Q2xhdWRlUmVzdW1lRXBvY2", 9007199254740991L)))
        assertNotEquals(freshRequestId(), freshRequestId())
        // The shapes the host refuses are refused before they could be sent.
        fun refused(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java) { block() }
        refused { resumeRequestId("Q2xhdWRlUmVzdW1lRXBvY", 4) }
        refused { resumeRequestId("Q2xhdWRlUmVzdW1lRXBvY2A", 4) }
        refused { resumeRequestId("Q2xhdWRlUmVzdW1lRXBvY+", 4) }
        refused { resumeRequestId("Q2xhdWRlUmVzdW1lRXBvY2", -1) }
        refused { resumeRequestId("Q2xhdWRlUmVzdW1lRXBvY2", 9007199254740992L) }
        refused { resumeRequestId(null, 4) }
        refused { resumeRequestId(null, 0, "a:b") }
        refused { resumeRequestId(null, 0, "a b") }
        refused { resumeRequestId(null, 0, "") }
        refused { resumeRequestId(null, 0, "x".repeat(300)) }
    }

    @Test
    fun theSnapshotsWithAnEpochPassAndAMalformedEpochIsRefused() {
        val epoch = "Q2xhdWRlUmVzdW1lRXBvY2"
        assertEquals(epoch, snapshotEpoch(data(valid(resume, "snapshot-with-epoch"))))
        assertEquals("zX9_-aB3cD4eF5gH6iJ7kL", snapshotEpoch(data(valid(resume, "snapshot-with-epoch-and-cursor"))))
        assertNull(snapshotEpoch(data(valid(resume, "snapshot-without-epoch"))))
        val invalid = entries(resume, "wireInvalid").filter { isResult(it.second, "snapshot") }
        assertEquals(3, invalid.size)
        for ((name, payload) in invalid) assertTrue(name, runCatching { snapshotEpoch(data(payload)) }.isFailure)
        // The Timeline refuses such a snapshot as it does any other malformed field.
        for ((name, payload) in invalid) {
            val timeline = Timeline(payload.obj("data").text("sessionId"))
            assertTrue(name, runCatching { timeline.snapshot(data(payload)) }.isFailure)
            assertFalse(name, timeline.hasSnapshot)
        }
    }

    @Test
    fun theSessionEventsResultsPassAndEveryInvalidOneIsRefused() {
        val results = entries(resume, "wireValid").filter { isResult(it.second, "session.events") }
        assertEquals(setOf("events-empty", "events-compacted-and-merged", "events-first-after-from", "events-from-zero", "events-512", "events-status-with-epoch"), results.map { it.first }.toSet())
        for ((name, payload) in results) {
            val reply = sessionEventsReply(data(payload))
            assertEquals(name, data(payload).long("from"), reply.from)
            assertEquals(name, data(payload).long("revision"), reply.revision)
            assertEquals(name, data(payload).array("events"), reply.events)
        }
        assertEquals(512, sessionEventsReply(data(valid(resume, "events-512"))).events.size)
        assertEquals(emptyList<JsonObject>(), sessionEventsReply(data(valid(resume, "events-empty"))).events)
        val compacted = sessionEventsReply(data(valid(resume, "events-compacted-and-merged")))
        assertEquals(listOf(41L, 42L, 43L), compacted.events.take(3).map { it.long("revision") })
        assertEquals(40L, sessionEventsReply(data(valid(resume, "events-first-after-from"))).events.single().long("after"))
        // A status replayed by a newer host carries its journal epoch; the reply keeps it for the list.
        val withEpoch = sessionEventsReply(data(valid(resume, "events-status-with-epoch")))
        assertTrue(withEpoch.events.any { it.text("kind") == "session.status" && it.text("epoch") == withEpoch.epoch })

        val invalid = entries(resume, "wireInvalid").filter { isResult(it.second, "session.events") }
        assertEquals(18, invalid.size)
        for ((name, payload) in invalid) assertTrue(name, runCatching { sessionEventsReply(data(payload)) }.isFailure)
    }
}
