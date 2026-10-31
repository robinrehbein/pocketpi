package de.joinnoah.pi.remote

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Keeps a summary of [Diagnostics] in [file] (`diagnostics-v1.json` in the no-backup directory), so
 * the Diagnostics page can show what the run before a restart saw: the last
 * [Diagnostics.CONNECT_HISTORY] connections, the last [Diagnostics.STORED_ENTRIES] failures,
 * recoveries and offline intervals, and the counters and round-trip summary, each with a
 * wall-clock time.
 *
 * [start] loads the file into [Diagnostics.previousRun], then saves every [intervalMillis] while
 * something changed; [flush] saves at once, for when the app goes to the background. The file
 * never exceeds [maxBytes], and everything read back passes [Diagnostics.safeToken] again, so it
 * holds nothing [Diagnostics] would not hold.
 */
internal class DiagnosticsStore(
    private val file: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val intervalMillis: Long = SAVE_INTERVAL_MILLIS,
    private val maxBytes: Int = MAX_BYTES,
) {
    private val lock = Any()
    private var loaded = false
    /** A fresh process starts at revision 0; a run that recorded nothing must not replace the previous file. */
    private var savedRevision = 0L

    /** The class of the failure last recorded, so a fault that persists is recorded once, not per tick. */
    private var recordedFailure: String? = null

    /** Loads the previous run, then saves periodically. Call once. */
    fun start() {
        scope.launch(io) {
            Diagnostics.previousRun = load()
            synchronized(lock) { loaded = true }
            // The new run's first entry already differs from the file; save it at the next tick.
            while (true) {
                delay(intervalMillis)
                saveIfChanged()
            }
        }
    }

    /** Saves now if something changed since the last save; returns at once. */
    fun flush() {
        scope.launch(io) { saveIfChanged() }
    }

    /** Writes the file if [Diagnostics] changed since the last write. */
    fun saveIfChanged() {
        synchronized(lock) {
            if (!loaded) return
            val revision = Diagnostics.revision
            if (revision == savedRevision) return
            try {
                val text = encode(Diagnostics.persistable(wallClock()))
                val temporary = File(file.parentFile, file.name + ".tmp")
                temporary.writeText(text)
                if (!temporary.renameTo(file)) {
                    file.delete()
                    check(temporary.renameTo(file))
                }
                savedRevision = revision
                recordedFailure = null
            } catch (swallowed: Exception) {
                // Diagnostics must never cost the app anything; the next tick tries again, quietly:
                // a failure that repeats must not fill the ring with copies of itself.
                val cause = swallowed.javaClass.name
                if (cause != recordedFailure) {
                    recordedFailure = cause
                    Diagnostics.caught("diagnostics.save", swallowed)
                }
            }
        }
    }

    /** The saved run, or null when there is none or the file is unreadable, too large or malformed. */
    fun load(): Diagnostics.PreviousRun? =
        try {
            if (!file.isFile || file.length() > maxBytes) null else decode(file.readText())
        } catch (swallowed: Exception) {
            null
        }

    /** The JSON of [run], with the oldest connections and entries dropped until it fits [maxBytes]. */
    internal fun encode(run: Diagnostics.PreviousRun): String {
        var current = run
        while (true) {
            val text = Json.encodeToString(JsonObject.serializer(), toJson(current))
            if (text.toByteArray().size <= maxBytes) return text
            current =
                when {
                    current.entries.size >= current.connects.size && current.entries.isNotEmpty() ->
                        current.copy(entries = current.entries.dropLast(1))
                    current.connects.isNotEmpty() -> current.copy(connects = current.connects.dropLast(1))
                    // Counters alone are a few hundred bytes; a smaller cap than that holds nothing.
                    else -> return text
                }
        }
    }

    private fun toJson(run: Diagnostics.PreviousRun) =
        buildJsonObject {
            put("version", 1)
            put("savedAt", run.savedAt)
            put(
                "connects",
                buildJsonArray {
                    run.connects.forEach { connect ->
                        add(
                            buildJsonObject {
                                put("at", connect.at)
                                put("label", connect.label)
                                put(
                                    "phases",
                                    buildJsonArray {
                                        connect.phases.forEach { (phase, ms) ->
                                            add(buildJsonArray { add(JsonPrimitive(phase.name)); add(JsonPrimitive(ms)) })
                                        }
                                    },
                                )
                                connect.durationMs?.let { put("durationMs", it) }
                                connect.outcome?.let { put("outcome", it) }
                                put("extensions", buildJsonArray { connect.extensions.forEach { add(JsonPrimitive(it)) } })
                            }
                        )
                    }
                },
            )
            put(
                "entries",
                buildJsonArray {
                    run.entries.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("at", entry.at)
                                put("kind", entry.kind.name)
                                put("label", entry.label)
                                if (entry.durationMs >= 0) put("durationMs", entry.durationMs)
                                if (entry.bytes >= 0) put("bytes", entry.bytes)
                                if (entry.outcome.isNotEmpty()) put("outcome", entry.outcome)
                            }
                        )
                    }
                },
            )
            put(
                "counters",
                buildJsonObject { run.link.counters.forEach { (counter, value) -> put(counter.key, value) } },
            )
            put("closeCodes", buildJsonObject { run.link.closeCodes.forEach { (code, value) -> put(code.toString(), value) } })
            run.link.rtt?.let { rtt ->
                put(
                    "rtt",
                    buildJsonObject {
                        put("count", rtt.count)
                        put("p50", rtt.p50)
                        put("p95", rtt.p95)
                        put("last", rtt.last)
                        rtt.relayMs?.let { put("relayMs", it) }
                    },
                )
            }
        }

    /** Parses [text]; null when it is not a version 1 file. Values are clamped and re-redacted. */
    internal fun decode(text: String): Diagnostics.PreviousRun? {
        val root = Json.parseToJsonElement(text).jsonObject
        if (root.long("version") != 1L) return null
        val savedAt = root.long("savedAt")?.takeIf { it > 0 } ?: return null
        val connects =
            root.array("connects").mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val phases =
                    item.array("phases").mapNotNull { pair ->
                        val parts = pair as? JsonArray ?: return@mapNotNull null
                        val phase = HandshakePhase.entries.firstOrNull { it.name == parts.getOrNull(0).string() }
                        val ms = parts.getOrNull(1).long()
                        if (phase == null || ms == null) null else phase to ms.coerceAtLeast(0)
                    }.take(HandshakePhase.entries.size)
                Diagnostics.Connect(
                    item.long("at") ?: return@mapNotNull null,
                    Diagnostics.safeToken(item.string("label") ?: return@mapNotNull null),
                    phases,
                    item.long("durationMs")?.coerceAtLeast(0),
                    item.string("outcome")?.let(Diagnostics::safeToken),
                    item.array("extensions").mapNotNull { it.string() }
                        .filter { it.length <= 32 && EXTENSION_NAME.matches(it) }
                        .take(4),
                )
            }.take(Diagnostics.CONNECT_HISTORY)
        val entries =
            root.array("entries").mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val kind = Diagnostics.Kind.entries.firstOrNull { it.name == item.string("kind") }
                val at = item.long("at")
                val label = item.string("label")
                if (kind == null || at == null || label == null) return@mapNotNull null
                Diagnostics.Entry(
                    at,
                    kind,
                    Diagnostics.safeToken(label),
                    item.long("durationMs")?.coerceAtLeast(0) ?: -1,
                    item.long("bytes")?.coerceAtLeast(0) ?: -1,
                    item.string("outcome")?.let(Diagnostics::safeToken).orEmpty(),
                )
            }.take(Diagnostics.STORED_ENTRIES)
        val counters =
            (root["counters"] as? JsonObject).orEmpty().entries.mapNotNull { (key, value) ->
                val counter = Diagnostics.Counter.entries.firstOrNull { it.key == key }
                val count = value.long()
                if (counter == null || count == null) null else counter to count.coerceAtLeast(0)
            }.toMap()
        val closeCodes =
            (root["closeCodes"] as? JsonObject).orEmpty().entries.mapNotNull { (key, value) ->
                val code = key.toIntOrNull()
                val count = value.long()
                if (code == null || code !in 0..4999 || count == null) null else code to count.coerceAtLeast(0)
            }.take(Diagnostics.CLOSE_CODES).toMap()
        val rtt =
            (root["rtt"] as? JsonObject)?.let { item ->
                Diagnostics.Rtt(
                    (item.long("count") ?: return@let null).coerceIn(1, Diagnostics.RTT_SAMPLES.toLong()).toInt(),
                    item.long("p50") ?: return@let null,
                    item.long("p95") ?: return@let null,
                    item.long("last") ?: return@let null,
                    item.long("relayMs"),
                )
            }
        return Diagnostics.PreviousRun(savedAt, connects, entries, Diagnostics.Link(rtt, counters, closeCodes))
    }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString && this !is JsonNull }?.longOrNull

    private fun JsonObject.string(key: String) = this[key].string()

    private fun JsonObject.long(key: String) = this[key].long()

    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    internal companion object {
        const val MAX_BYTES = 64 * 1024
        const val SAVE_INTERVAL_MILLIS = 30_000L

        /** A WebSocket extension name such as `permessage-deflate`. */
        private val EXTENSION_NAME = Regex("[a-z0-9]+(?:[-_][a-z0-9]+)*")
    }
}
