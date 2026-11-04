package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal const val SESSION_COMPACTION_LIST_CAPABILITY = "session.compaction.list.v1"

internal data class CompactionWatermark(val epoch: Long, val version: Long)

/** Host-wide activity, independent of the selected chat's contiguous timeline revisions. */
internal class SessionCompactions(private val now: () -> Long = System::currentTimeMillis) {
    private val revisions = mutableMapOf<String, Long>()
    private val versions = linkedMapOf<String, Long>()
    private var epoch = 0L
    private var version = 0L
    var values: Map<String, SessionCompaction> = emptyMap()
        private set

    fun clear() {
        epoch++
        version = 0L
        revisions.clear()
        versions.clear()
        values = emptyMap()
    }

    /** Capture before sending; retries of the same request must retain this watermark. */
    fun watermark(): CompactionWatermark = CompactionWatermark(epoch, ++version)

    /** Only opted-in complete list results have authoritative compaction metadata. */
    fun list(items: List<JsonObject>, watermark: CompactionWatermark) {
        if (watermark.epoch != epoch) return
        for (item in items) {
            val id = item.text("id")
            if ((versions[id] ?: -1L) > watermark.version) continue
            set(id, compacting(item), null, watermark.version)
        }
    }

    /** Journal changes start revisions over; the following event/page supplies current activity. */
    fun forgetRevision(id: String) {
        revisions.remove(id)
        versions[id] = ++version
        trim()
    }

    /** The selected Timeline already ordered a first page/replay and its buffered live events. */
    fun acceptedTimeline(id: String, revision: Long, compaction: SessionCompaction?) {
        // A snapshot can be accepted while a newer compaction event still waits beyond a gap.
        if (revision < 0 || revision < (revisions[id] ?: -1L)) return
        revisions[id] = revision
        versions[id] = ++version
        values = if (compaction == null) values - id else values + (id to compaction)
        trim()
    }

    fun event(event: JsonObject) {
        if (event.text("kind") != "session.compaction") return
        val running = when (event.optionalText("state")) {
            "running" -> true
            "done", "failed", "aborted" -> false
            else -> return
        }
        apply(event.text("sessionId"), event.long("revision"), running,
            event.optionalText("reason")?.takeIf { it in setOf("manual", "threshold", "overflow") })
    }

    fun status(event: JsonObject) {
        if (event.text("status") == "offline")
            apply(event.text("sessionId"), event.long("revision"), false, null)
    }

    private fun compacting(data: JsonObject): Boolean =
        (data["compacting"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

    private fun apply(id: String, revision: Long, running: Boolean, reason: String?) {
        if (revision < 0 || revision <= (revisions[id] ?: -1L)) return
        revisions[id] = revision
        set(id, running, reason)
    }

    private fun set(id: String, running: Boolean, reason: String?, listVersion: Long? = null) {
        versions[id] = listVersion ?: ++version
        values = if (running) {
            val previous = values[id]?.takeIf { compactionVisible(it, now()) }
            values + (id to (previous?.copy(reason = reason ?: previous.reason)
                ?: SessionCompaction(reason, now())))
        } else values - id
        trim()
    }

    private fun trim() {
        // Keep terminal revisions/watermarks too: delayed starts and list results must not
        // resurrect completed activity. Bound all stores together across project navigation.
        while (versions.size > 4096) {
            val oldest = versions.keys.first()
            versions.remove(oldest)
            revisions.remove(oldest)
            values = values - oldest
        }
    }
}
