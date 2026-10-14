package de.joinnoah.pi.remote

import kotlin.random.Random
import kotlinx.serialization.json.*

internal const val CONNECTED_FLOW_FIXTURE = "connected-flow.json"

/** Network timing of one fake relay profile; every reply waits one sampled round trip. */
internal data class LatencyProfile(
    val roundTripMs: Long,
    val jitterMs: Long,
    /** Share of replies that are held back for [stallMs] on top of the round trip. */
    val stallRate: Double,
    val stallMs: Long,
) {
    fun sample(random: Random): Long {
        val jitter = if (jitterMs > 0) random.nextLong(-jitterMs, jitterMs + 1) else 0L
        val stall = if (stallRate > 0 && random.nextDouble() < stallRate) stallMs else 0L
        return (roundTripMs + jitter).coerceAtLeast(0) + stall
    }
}

/** One fixture session: its wire object plus how much history the fake host generates for it. */
internal class FixtureSession(
    val session: JsonObject,
    val revision: Long,
    val turns: Int,
    /** Text of the last message, so the benchmarks can wait for the tail of this chat. */
    val tail: String,
) {
    val id: String get() = session.text("id")
    val projectId: String get() = session.text("projectId")
}

/**
 * The scripted answer [FakeRelayTransport.stream] sends: [chunks] upserts [intervalMs] apart, each
 * appending the next of [parts] (cycled, `{chunk}` replaced by its index). After [toolAt] chunks
 * the first answer completes and an edit tool call and its result follow, as when the agent stops
 * to edit a file; the remaining chunks stream into a second answer.
 */
internal class StreamScript(
    val intervalMs: Long,
    val chunks: Int,
    private val parts: List<String>,
    val toolAt: Int,
    /** Text of a separate message published after the stream, for benchmarks to wait for. */
    val complete: String,
) {
    init {
        require(intervalMs > 0 && chunks > 0 && parts.isNotEmpty() && toolAt in 0..chunks)
    }

    fun chunk(index: Int) = parts[index % parts.size].replace("{chunk}", index.toString())

    /** The text of the answer streamed over chunks [range]. */
    fun text(range: IntRange) = range.joinToString("") { chunk(it) }
}

/**
 * The data the benchmark-only [FakeRelayTransport] answers with, read from
 * `src/benchmark/assets/connected-flow.json`. Transcripts are generated from the `turn` template.
 */
internal class RelayFixture(
    val seed: Int,
    val relay: String,
    val routeId: String,
    private val pairId: String,
    private val pairSecret: String,
    val deviceId: String,
    val deviceSecret: String,
    val hostName: String,
    val capabilities: Set<String>,
    val routeCapabilities: List<String>,
    val handshakeRoundTrips: Int,
    val snapshotPageSize: Int,
    val defaultProfile: String,
    val profiles: Map<String, LatencyProfile>,
    /** The chat the prepare pass opens, and the draft it leaves there. */
    val prepare: RemoteSelection,
    val prepareDraft: String,
    val projects: List<JsonObject>,
    val sessions: List<FixtureSession>,
    private val turn: List<JsonObject>,
    val stream: StreamScript,
) {
    fun profile(name: String?): LatencyProfile =
        profiles[name] ?: profiles.getValue(defaultProfile)

    fun session(id: String): FixtureSession? = sessions.firstOrNull { it.id == id }

    /** The QR text a Mac would show for this fixture host. */
    fun pairingCode(expiresAt: Long): String {
        val value = Wire.objectOf(
            "v" to 1, "relay" to relay, "routeId" to routeId, "pairId" to pairId,
            "secret" to pairSecret, "expiresAt" to expiresAt,
        )
        return "pi-remote://pair#" + Wire.encode(value.toString().toByteArray())
    }

    /** The full history of [session], oldest first, ending with its tail marker. */
    fun transcript(session: FixtureSession): List<JsonObject> = buildList {
        repeat(session.turns) { index -> addAll(turn(session.id, index.toString())) }
        add(Wire.objectOf("id" to "${session.id}-tail", "role" to "user", "state" to "complete", "text" to session.tail))
    }

    /**
     * One turn from the template: user prompt, edit tool call, tool result and answer. Message IDs
     * start with [prefix]; [turn] names the turn in IDs, paths and texts.
     */
    fun turn(prefix: String, turn: String): List<JsonObject> {
        val expanded = JsonArray(this.turn).toString().replace("{session}", prefix).replace("{turn}", turn)
        return Wire.json.parseToJsonElement(expanded).jsonArray.map { it.jsonObject }
    }

    companion object {
        fun parse(text: String): RelayFixture {
            val root = Wire.json.parseToJsonElement(text).jsonObject
            require(root.long("version") == 1L)
            val host = root.obj("host")
            val profiles = root.obj("profiles").mapValues { (_, value) ->
                val profile = value.jsonObject
                LatencyProfile(
                    roundTripMs = profile.long("roundTripMs"),
                    jitterMs = profile.long("jitterMs"),
                    stallRate = profile.getValue("stallRate").jsonPrimitive.double,
                    stallMs = profile.long("stallMs"),
                )
            }
            val prepare = root.obj("prepare")
            val fixture = RelayFixture(
                seed = root.long("seed").toInt(),
                relay = host.text("relay"),
                routeId = host.text("routeId"),
                pairId = host.text("pairId"),
                pairSecret = host.text("pairSecret"),
                deviceId = host.text("deviceId"),
                deviceSecret = host.text("deviceSecret"),
                hostName = host.text("hostName"),
                capabilities = root.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                routeCapabilities = root.getValue("routeCapabilities").jsonArray.map { it.jsonPrimitive.content },
                handshakeRoundTrips = root.long("handshakeRoundTrips").toInt(),
                snapshotPageSize = root.long("snapshotPageSize").toInt(),
                defaultProfile = root.text("defaultProfile"),
                profiles = profiles,
                prepare = RemoteSelection(host.text("routeId"), prepare.text("projectId"), prepare.text("sessionId")),
                prepareDraft = prepare.text("draft"),
                projects = root.array("projects"),
                sessions = root.array("sessions").map {
                    FixtureSession(it.obj("session"), it.long("revision"), it.long("turns").toInt(), it.text("tail"))
                },
                turn = root.array("turn"),
                stream = root.obj("stream").let { stream ->
                    StreamScript(
                        intervalMs = stream.long("intervalMs"),
                        chunks = stream.long("chunks").toInt(),
                        parts = stream.getValue("parts").jsonArray.map { it.jsonPrimitive.content },
                        toolAt = stream.long("toolAt").toInt(),
                        complete = stream.text("complete"),
                    )
                },
            )
            require(fixture.defaultProfile in profiles && fixture.snapshotPageSize > 0)
            require(fixture.sessions.all { session -> fixture.projects.any { it.text("id") == session.projectId } })
            require(fixture.session(fixture.prepare.sessionId!!)?.projectId == fixture.prepare.projectId)
            return fixture
        }
    }
}
