package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** A compaction the host reported as running for the selected session. */
data class SessionCompaction(val reason: String?, val startedAtMillis: Long)

private const val COMPACTION_VISIBLE_MILLIS = 10 * 60 * 1000L

/** Hides a compaction whose terminal event never arrived after ten minutes. */
fun compactionVisible(c: SessionCompaction?, nowMillis: Long): Boolean =
    c != null && nowMillis - c.startedAtMillis < COMPACTION_VISIBLE_MILLIS

private val compactionReasons = setOf("manual", "threshold", "overflow")

data class PendingRequest(
    val type: String,
    val sessionId: String? = null,
    val epoch: Long,
    val cursor: String? = null,
    val draft: String? = null,
    val questionId: String? = null,
)

class RequestLedger {
    private val values = mutableMapOf<String, PendingRequest>()

    fun add(id: String, request: PendingRequest) {
        require(values.size < 256)
        values[id] = request
    }

    fun take(id: String, epoch: Long): PendingRequest? =
        values.remove(id)?.takeIf { it.epoch == epoch }

    fun clear() {
        values.clear()
    }
}

class Timeline(val sessionId: String, private val now: () -> Long = System::currentTimeMillis) {
    var revision = -1L
        private set

    var messages = listOf<JsonObject>()
        private set

    var questions = listOf<JsonObject>()
        private set

    var nextCursor: String? = null
        private set

    var status = "idle"

    var compaction: SessionCompaction? = null
        private set
    private val pendingEvents = sortedMapOf<Long, JsonObject>()
    private var snapshotRevision = -1L
    val needsSnapshot: Boolean
        get() = pendingEvents.isNotEmpty()

    fun beginSnapshotEpoch(status: String) {
        revision = -1L
        snapshotRevision = -1L
        messages = emptyList()
        questions = emptyList()
        nextCursor = null
        pendingEvents.clear()
        compaction = null
        this.status = status
    }

    fun snapshot(data: JsonObject, older: Boolean = false) {
        if (data.text("sessionId") != sessionId) return
        val incomingRevision = data.long("revision")
        if (older) {
            if (incomingRevision != snapshotRevision || snapshotRevision < 0) return
            messages =
                (data.array("messages") + messages).associateBy { it.text("id") }.values.toList()
            nextCursor = data.optionalText("nextCursor")
            return
        }
        if (incomingRevision < revision) return
        val incomingStatus = data.text("status")
        require(incomingStatus in listOf("idle", "running", "waiting", "offline"))
        snapshotRevision = incomingRevision
        revision = incomingRevision
        status = incomingStatus
        messages = data.array("messages")
        questions = data.array("pendingQuestions")
        nextCursor = data.optionalText("nextCursor")
        compaction =
            if ((data["compacting"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true)
                compaction ?: SessionCompaction(null, now())
            else null
        val waiting = pendingEvents.values.toList()
        pendingEvents.clear()
        for (event in waiting) event(event)
    }

    fun event(event: JsonObject): Boolean {
        if (event.text("sessionId") != sessionId || event.long("revision") <= revision) return false
        if (event.long("revision") != revision + 1 || event.text("kind") == "snapshot.required") {
            pendingEvents[event.long("revision")] = event
            while (pendingEvents.size > 128) pendingEvents.remove(pendingEvents.firstKey())
            return true
        }
        revision = event.long("revision")
        when (event.text("kind")) {
            "message.upsert" -> {
                val message = event.obj("message")
                val index = messages.indexOfFirst { it.text("id") == message.text("id") }
                messages =
                    if (index < 0) messages + message
                    else messages.toMutableList().apply { set(index, message) }
            }
            "command.status" -> Unit
            "session.title" -> Unit
            "session.status" -> status = event.text("status")
            "question.open" -> {
                val question = event.obj("question")
                questions = questions.filterNot { it.text("id") == question.text("id") } + question
            }
            "question.closed" -> questions = questions.filterNot {
                    it.text("id") == event.text("questionId")
                }
            "session.compaction" -> compaction(event)
            else -> return true
        }
        return false
    }

    private fun compaction(event: JsonObject) {
        fun field(key: String): String? =
            (event[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        when (field("state")) {
            "running" -> {
                val reason = field("reason")?.takeIf { it in compactionReasons }
                compaction = compaction?.copy(reason = reason ?: compaction?.reason)
                    ?: SessionCompaction(reason, now())
            }
            "done", "failed", "aborted" -> compaction = null
            else -> Unit
        }
    }
}
