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

/** The session statuses this app knows; snapshots and `session.status` events carry one. */
internal val SESSION_STATUSES = setOf("idle", "running", "waiting", "offline")

/** Longest `session.notice` message, in UTF-8 bytes. */
internal const val MAX_SESSION_NOTICE_BYTES = 1000

/** An extension's `ctx.ui.notify` in a session (`session.notice`); [id] tells repeated texts apart. */
data class SessionNotice(val id: Long, val sessionId: String, val level: String, val message: String)

private val noticeLevels = setOf("info", "warning", "error")
private val noticeKeys = setOf("type", "sessionId", "revision", "kind", "level", "message")

/** The notice [event] carries, or null when a field breaks the protocol's rules. */
internal fun sessionNotice(event: JsonObject, id: Long): SessionNotice? {
    fun field(key: String): String? = (event[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (event.keys != noticeKeys) return null
    val sessionId = field("sessionId") ?: return null
    val level = field("level")?.takeIf { it in noticeLevels } ?: return null
    val message = field("message") ?: return null
    if (message.isBlank() || message.encodeToByteArray().size > MAX_SESSION_NOTICE_BYTES) return null
    return SessionNotice(id, sessionId, level, message)
}

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

    /**
     * Where each message ID sits in [messages], so a streamed upsert finds its message without a
     * scan. [showMessages] rebuilds it; an upsert keeps it current itself.
     */
    private var indexById = HashMap<String, Int>()

    var questions = listOf<JsonObject>()
        private set

    var nextCursor: String? = null
        private set

    var status = "idle"

    var compaction: SessionCompaction? = null
        private set

    /**
     * Whether a snapshot of this epoch has been applied. Until then [messages], [questions] and
     * [nextCursor] are empty placeholders, not the session's state, and the owner keeps showing
     * what it had.
     */
    var hasSnapshot = false
        private set

    /** Pages shown before this epoch began, kept as the prefix of a snapshot that overlaps them. */
    private var retained = listOf<JsonObject>()
    private var retainedCursor: String? = null
    private val pendingEvents = sortedMapOf<Long, JsonObject>()
    private var snapshotRevision = -1L
    val needsSnapshot: Boolean
        get() = pendingEvents.isNotEmpty()

    /**
     * An event of a kind this app does not know, or a malformed buffered event, advanced the
     * revision since the owner last cleared this. Its effect on the chat is unknown, so the owner
     * resyncs with a snapshot, rate-limited, and sets this back to false.
     */
    var unknownKindSeen = false

    fun beginSnapshotEpoch(status: String) {
        revision = -1L
        snapshotRevision = -1L
        hasSnapshot = false
        if (messages.isNotEmpty()) retain(messages, nextCursor)
        showMessages(emptyList())
        questions = emptyList()
        nextCursor = null
        pendingEvents.clear()
        compaction = null
        this.status = status
    }

    /**
     * Keeps [messages] (with the cursor before them) that an earlier Timeline of this session
     * showed, so the first snapshot keeps the older pages it overlaps.
     */
    fun retain(messages: List<JsonObject>, nextCursor: String?) {
        if (hasSnapshot) return
        retained = messages
        retainedCursor = nextCursor
    }

    fun snapshot(data: JsonObject, older: Boolean = false) {
        if (data.text("sessionId") != sessionId) return
        val incomingRevision = data.long("revision")
        if (older) {
            if (incomingRevision != snapshotRevision || snapshotRevision < 0) return
            showMessages((data.array("messages") + messages).associateBy { it.text("id") }.values.toList())
            nextCursor = data.optionalText("nextCursor")
            return
        }
        if (incomingRevision < revision) return
        val incomingStatus = data.text("status")
        require(incomingStatus in SESSION_STATUSES)
        snapshotRevision = incomingRevision
        revision = incomingRevision
        status = incomingStatus
        val page = data.array("messages")
        // A resync of the same session keeps the older pages loaded before it: they end where the
        // new page begins, so the prefix before its oldest message and the cursor before them stay.
        val shown = if (hasSnapshot) messages else retained
        val shownCursor = if (hasSnapshot) nextCursor else retainedCursor
        val oldest = page.firstOrNull()?.optionalText("id")
        val overlap = if (oldest == null) -1 else shown.indexOfFirst { it.optionalText("id") == oldest }
        if (overlap > 0) {
            showMessages((shown.subList(0, overlap) + page).associateBy { it.text("id") }.values.toList())
            nextCursor = shownCursor
        } else {
            showMessages(page)
            nextCursor = data.optionalText("nextCursor")
        }
        retained = emptyList()
        retainedCursor = null
        hasSnapshot = true
        questions = data.array("pendingQuestions")
        compaction =
            if ((data["compacting"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true)
                compaction ?: SessionCompaction(null, now())
            else null
        val waiting = pendingEvents.values.toList()
        pendingEvents.clear()
        for (event in waiting) {
            try {
                event(event)
            } catch (malformed: Exception) {
                // The snapshot itself is applied; a bad buffered event only asks for another one.
                unknownKindSeen = true
            }
        }
    }

    /** The index of the message with [id] in [messages], or -1. */
    internal fun messageIndex(id: String): Int = indexById[id] ?: -1

    private fun showMessages(value: List<JsonObject>) {
        messages = value
        indexById = HashMap<String, Int>(value.size * 2).apply {
            // The first message with an ID wins, as a scan from the start would find it.
            value.forEachIndexed { index, message ->
                (message["id"] as? JsonPrimitive)?.takeIf { it.isString }?.let { putIfAbsent(it.content, index) }
            }
        }
    }

    /**
     * Applies [event] and returns whether a snapshot is needed now: a revision gap or the host's
     * `snapshot.required`. An unknown kind only advances the revision and sets [unknownKindSeen]. A
     * malformed event of this session throws.
     */
    fun event(event: JsonObject): Boolean {
        if (event.optionalText("sessionId") != sessionId || event.long("revision") <= revision) return false
        if (event.long("revision") != revision + 1 || event.text("kind") == "snapshot.required") {
            pendingEvents[event.long("revision")] = event
            while (pendingEvents.size > 128) pendingEvents.remove(pendingEvents.firstKey())
            return true
        }
        revision = event.long("revision")
        when (event.text("kind")) {
            "message.upsert" -> {
                val message = event.obj("message")
                val id = message.text("id")
                val index = messageIndex(id)
                if (index < 0) {
                    // Appending moves no other message, so only the new ID is added.
                    messages = messages + message
                    indexById[id] = messages.lastIndex
                } else {
                    messages = messages.toMutableList().apply { set(index, message) }
                }
            }
            "command.status" -> Unit
            "session.title" -> Unit
            "session.status" -> {
                // A status this app does not know keeps the previous one and asks for a snapshot.
                val incomingStatus = event.text("status")
                require(incomingStatus in SESSION_STATUSES)
                status = incomingStatus
            }
            "question.open" -> {
                val question = event.obj("question")
                questions = questions.filterNot { it.text("id") == question.text("id") } + question
            }
            "question.closed" -> questions = questions.filterNot {
                    it.text("id") == event.text("questionId")
                }
            "session.compaction" -> compaction(event)
            "session.notice" -> Unit
            // Applied outside the timeline (the advisor chip); its revision needs no snapshot.
            "advisor.status" -> Unit
            else -> unknownKindSeen = true
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
