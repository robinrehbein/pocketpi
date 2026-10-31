package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.IdentityHashMap

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
    if (!hasEventShape(event, noticeKeys)) return null
    val sessionId = field("sessionId") ?: return null
    val level = field("level")?.takeIf { it in noticeLevels } ?: return null
    val message = field("message") ?: return null
    if (message.isBlank() || message.encodeToByteArray().size > MAX_SESSION_NOTICE_BYTES) return null
    return SessionNotice(id, sessionId, level, message)
}

/** Longest prompt suggestion in `session.suggestion` events and snapshots, in UTF-8 bytes. */
internal const val MAX_SESSION_SUGGESTION_BYTES = 1024

/** A `session.suggestion` event: the host's suggested next prompt, or null when it was cleared. */
internal data class SessionSuggestion(val text: String?)

private val suggestionKeys = setOf("type", "sessionId", "revision", "kind", "suggestion")

/** [value] as a prompt suggestion: a string of 1 to [MAX_SESSION_SUGGESTION_BYTES] UTF-8 bytes, else null. */
internal fun suggestionText(value: JsonElement?): String? =
    (value as? JsonPrimitive)
        ?.takeIf { it !is JsonNull && it.isString }
        ?.content
        ?.takeIf { it.isNotEmpty() && it.encodeToByteArray().size <= MAX_SESSION_SUGGESTION_BYTES }

/** The suggestion [event] sets or clears, or null when a field breaks the protocol's rules. */
internal fun sessionSuggestion(event: JsonObject): SessionSuggestion? {
    if (!hasEventShape(event, suggestionKeys)) return null
    val value = event["suggestion"]
    if (value is JsonNull) return SessionSuggestion(null)
    return suggestionText(value)?.let(::SessionSuggestion)
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

/** Why [Timeline.eventNeed] asks for a snapshot, so the owner picks the request form. */
enum class SnapshotNeed {
    NONE,

    /** A revision gap that the event's `after` does not cover: a resume from the journal can fill it. */
    GAP,

    /** The host's `snapshot.required`: only a fresh snapshot carries what changed. */
    REQUIRED,
}

/** The event as a [Timeline] applies it: follow-up and steer receipts count as command receipts. */
internal fun timelineForm(event: JsonObject): JsonObject =
    if ((event["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content in setOf("follow_up.status", "steer.status"))
        JsonObject(event + ("kind" to JsonPrimitive("command.status")))
    else event

/**
 * What [Timeline.replay] did with a `session.events` reply: the reply's events it applied, in
 * order and in their wire form, for the owner's side effects; and whether the reply [fits], that
 * is, the Timeline ended at or past the reply's revision with nothing left waiting. A reply that
 * does not fit needs one fresh snapshot.
 */
class Replay(val applied: List<JsonObject>, val fits: Boolean)

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
     * The host's suggested next prompt (`session.suggestion` or the snapshot's `suggestion` key),
     * untrusted plain text; null when there is none or the session went offline.
     */
    var suggestion: String? = null
        private set

    /**
     * The open pull request of the session's branch (`session.pull_request` or the snapshot's
     * `pullRequest` key); null when there is none or the session went offline.
     */
    var pullRequest: PullRequestStatus? = null
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
    /** Buffered events wait for a snapshot; not while a resume reply is awaited, which they wait for. */
    val needsSnapshot: Boolean
        get() = pendingEvents.isNotEmpty() && !awaitingReply

    /**
     * An event of a kind this app does not know, or a malformed buffered event, advanced the
     * revision since the owner last cleared this. Its effect on the chat is unknown, so the owner
     * resyncs with a snapshot, rate-limited, and sets this back to false.
     */
    var unknownKindSeen = false

    /**
     * The host's journal epoch for this session, from the last first-page snapshot (`epoch`);
     * null when the host sent none (an older host, a session without a live process). A resume
     * names it, and a first page with another epoch starts the Timeline over.
     */
    var journalEpoch: String? = null
        private set

    /**
     * The chat left the screen: the owner keeps this Timeline to resume from later, and it
     * applies nothing meanwhile, so the events it misses never ask for a snapshot.
     */
    var detached = false

    /**
     * A resume request for this Timeline (or the first page of a `stale_cursor` recovery) is in
     * flight: events of its session wait in the buffer until the reply is applied, or the request
     * failed, and then apply each exactly once.
     */
    var awaitingReply = false
        private set

    /**
     * The highest revision of an event of this session that arrived while [awaitingReply] since
     * the buffer last drained, or -1; also of one at or below [revision], which is not buffered.
     */
    private var heldThrough = -1L

    /** Whether an event of this session arrived during the current wait for a reply. */
    val heldArrivals: Boolean
        get() = awaitingReply && heldThrough >= 0

    /** See [takeOwedResume]. */
    private var resumeOwed = false

    /**
     * The owner's tasks for this session are current as of [revision]: the last accepted first page
     * carried them, and nothing since asked for a snapshot. Task changes emit `snapshot.required`,
     * which no replay spans, so a replay keeps such tasks.
     */
    var tasksKnown = false

    /** The events [replay] applies while it runs, in order; null otherwise. */
    private var applying: MutableList<JsonObject>? = null

    /**
     * Where a resume continues: the journal epoch and the last applied revision, or null when this
     * Timeline has no snapshot or no epoch to name (the request then takes the fresh form).
     */
    internal fun resumeBase(): ResumeBase? {
        val epoch = journalEpoch ?: return null
        return if (hasSnapshot && revision >= 0) ResumeBase(epoch, revision) else null
    }

    /**
     * Takes over the state of [previous], an earlier Timeline of this session, as a resume starts
     * from it: the chat, its pages, revisions, epoch and task knowledge. Events it still buffered
     * are dropped; the reply covers them.
     */
    fun continueFrom(previous: Timeline) {
        require(previous.sessionId == sessionId)
        revision = previous.revision
        snapshotRevision = previous.snapshotRevision
        hasSnapshot = previous.hasSnapshot
        showMessages(previous.messages)
        questions = previous.questions
        nextCursor = previous.nextCursor
        status = previous.status
        compaction = previous.compaction
        suggestion = previous.suggestion
        pullRequest = previous.pullRequest
        journalEpoch = previous.journalEpoch
        tasksKnown = previous.tasksKnown
        // A resync the previous Timeline still owed is owed by this one.
        unknownKindSeen = previous.unknownKindSeen
        retained = emptyList()
        retainedCursor = null
        pendingEvents.clear()
        heldThrough = -1L
        resumeOwed = false
        awaitingReply = false
        detached = false
    }

    /** A request this Timeline's events wait for goes out now; see [awaitingReply]. */
    fun awaitReply() {
        awaitingReply = true
    }

    /**
     * Ends a wait whose reply will not come (the request failed or was dropped): the events held
     * meanwhile apply as they would have, and may leave a gap. Returns whether it was waiting.
     */
    fun endAwaiting(): Boolean {
        if (!awaitingReply) return false
        awaitingReply = false
        drainPending()
        return true
    }

    /**
     * Whether a first page of another journal epoch came while events that arrived for the resume
     * lie past its revision, clearing the answer. Those events were dropped (on arrival when at
     * or below the old revision, with the buffer otherwise). They arrived after the request went
     * out, so they most likely count in the new epoch, but one may still be the old epoch's:
     * instead of applying them, the owner fetches what follows the snapshot with one resume.
     */
    fun takeOwedResume(): Boolean = resumeOwed.also { resumeOwed = false }

    fun beginSnapshotEpoch(status: String) {
        revision = -1L
        snapshotRevision = -1L
        hasSnapshot = false
        journalEpoch = null
        tasksKnown = false
        awaitingReply = false
        detached = false
        if (messages.isNotEmpty()) retain(messages, nextCursor)
        showMessages(emptyList())
        questions = emptyList()
        nextCursor = null
        pendingEvents.clear()
        heldThrough = -1L
        resumeOwed = false
        compaction = null
        suggestion = null
        pullRequest = null
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

    /**
     * Applies a snapshot page and returns whether it did. A first page from another journal epoch
     * than [journalEpoch] (also none while one is held: a host downgrade) starts the Timeline
     * over first, so a restarted host or a session reopened in place at a lower revision is not
     * mistaken for an outdated reply. With [keepOlder] false, older pages on screen are dropped
     * and the page's own cursor is taken, as after a `stale_cursor`.
     */
    fun snapshot(data: JsonObject, older: Boolean = false, keepOlder: Boolean = true): Boolean {
        if (data.text("sessionId") != sessionId) return false
        val incomingRevision = data.long("revision")
        if (older) {
            if (incomingRevision != snapshotRevision || snapshotRevision < 0) return false
            showMessages((data.array("messages") + messages).associateBy { it.text("id") }.values.toList())
            nextCursor = data.optionalText("nextCursor")
            return true
        }
        // A malformed epoch or status breaks the snapshot like any other malformed field, before
        // anything changes.
        val epoch = snapshotEpoch(data)
        val restart = hasSnapshot && epoch != journalEpoch
        if (restart) require(data.text("status") in SESSION_STATUSES)
        val held = awaitingReply
        awaitingReply = false
        if (restart) {
            // Events that arrived for the resume past the new page are fetched with a resume; see
            // [takeOwedResume].
            resumeOwed = heldThrough > incomingRevision
            restartEpoch()
        }
        if (incomingRevision < revision) {
            // An outdated reply to a resume: the events held for it apply on what is already here.
            if (held) drainPending()
            return false
        }
        val incomingStatus = data.text("status")
        require(incomingStatus in SESSION_STATUSES)
        snapshotRevision = incomingRevision
        revision = incomingRevision
        status = incomingStatus
        journalEpoch = epoch
        tasksKnown = false
        val page = data.array("messages")
        // A resync of the same session keeps the older pages loaded before it: they end where the
        // new page begins, so the prefix before its oldest message and the cursor before them stay.
        val shown = if (!keepOlder) emptyList() else if (hasSnapshot) messages else retained
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
        // Absent means none; a malformed value is dropped rather than failing the snapshot.
        suggestion = suggestionText(data["suggestion"])
        // Same for the pull request: absent means none, a malformed value is dropped.
        pullRequest = pullRequestStatus(data["pullRequest"])
        compaction =
            if ((data["compacting"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true)
                compaction ?: SessionCompaction(null, now())
            else null
        drainPending()
        return true
    }

    /**
     * The first page of another journal epoch arrived: what this Timeline holds counts in another
     * incarnation's revisions. It starts over as [beginSnapshotEpoch] does, keeping the shown
     * messages as the prefix the new page may overlap. Buffered events may count in the old
     * epoch's numbering and go.
     */
    private fun restartEpoch() {
        if (messages.isNotEmpty()) {
            retained = messages
            retainedCursor = nextCursor
        }
        revision = -1L
        snapshotRevision = -1L
        hasSnapshot = false
        tasksKnown = false
        pendingEvents.clear()
        heldThrough = -1L
    }

    /** Applies the buffered events in revision order; those after a gap wait again. */
    private fun drainPending() {
        val waiting = pendingEvents.values.toList()
        pendingEvents.clear()
        heldThrough = -1L
        for (event in waiting) {
            try {
                eventNeed(event)
            } catch (malformed: Exception) {
                // What came before is applied; a bad buffered event only asks for a snapshot.
                unknownKindSeen = true
            }
        }
    }

    /**
     * Applies a `session.events` reply to the resume this Timeline asked for: each event goes
     * through [eventNeed] (revisions already here are ignored, `after` decides, gaps wait), then
     * the events held while the reply was awaited. Receipts of follow-ups and steers count as
     * command receipts here, as live. A reply for another session or epoch, or from a revision
     * past this Timeline's, applies nothing and does not fit.
     */
    internal fun replay(reply: SessionEventsReply): Replay {
        val usable =
            reply.sessionId == sessionId && hasSnapshot && journalEpoch != null && reply.epoch == journalEpoch &&
                reply.from <= revision
        awaitingReply = false
        val applied = mutableListOf<JsonObject>()
        val wire = IdentityHashMap<JsonObject, JsonObject>()
        applying = applied
        try {
            if (usable)
                for (event in reply.events) {
                    val forTimeline = timelineForm(event)
                    wire[forTimeline] = event
                    try {
                        eventNeed(forTimeline)
                    } catch (malformed: Exception) {
                        unknownKindSeen = true
                    }
                }
            drainPending()
        } finally {
            applying = null
        }
        // Held live events also apply above; their side effects ran when they arrived.
        return Replay(applied.mapNotNull { wire[it] }, usable && revision >= reply.revision && pendingEvents.isEmpty())
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
     * Applies [event] and returns whether a snapshot is needed now: a revision gap (one that the
     * event's `after` does not cover) or the host's `snapshot.required`. An unknown kind only advances the revision and sets [unknownKindSeen]. A
     * malformed event of this session throws.
     */
    fun event(event: JsonObject): Boolean = eventNeed(event) != SnapshotNeed.NONE

    /**
     * [event], telling why a snapshot is needed: [SnapshotNeed.GAP] or [SnapshotNeed.REQUIRED].
     * While [awaitingReply], every event of the session waits for the reply and nothing is needed;
     * a [detached] Timeline applies nothing.
     */
    fun eventNeed(event: JsonObject): SnapshotNeed {
        if (detached || event.optionalText("sessionId") != sessionId) return SnapshotNeed.NONE
        // Counted before the revision check: a reply of another epoch may restart the numbering
        // below an event that is already behind this Timeline.
        if (awaitingReply) heldThrough = maxOf(heldThrough, event.long("revision"))
        if (event.long("revision") <= revision) return SnapshotNeed.NONE
        if (awaitingReply) {
            buffer(event)
            return SnapshotNeed.NONE
        }
        // The host leaves events out on purpose for a focused phone or a coalesced stream and says
        // so with `after`: the event applies once everything up to there is in, a gap otherwise.
        val applicableFrom = eventAfter(event) ?: (event.long("revision") - 1)
        val required = event.text("kind") == "snapshot.required"
        if (revision < applicableFrom || required) {
            // What changed is unknown until the snapshot, tasks included.
            if (required) tasksKnown = false
            buffer(event)
            return if (required) SnapshotNeed.REQUIRED else SnapshotNeed.GAP
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
                // The host forgets a suggestion and the pull request when the session goes offline.
                if (incomingStatus == "offline") {
                    suggestion = null
                    pullRequest = null
                }
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
            // A malformed suggestion is ignored; the next event or snapshot carries the value.
            "session.suggestion" -> sessionSuggestion(event)?.let { suggestion = it.text }
            // Likewise a malformed pull request keeps the previous one; a null clears it.
            "session.pull_request" -> sessionPullRequest(event)?.let { pullRequest = it.pullRequest }
            // Applied outside the timeline (the advisor chip); its revision needs no snapshot.
            "advisor.status" -> Unit
            else -> unknownKindSeen = true
        }
        applying?.add(event)
        return SnapshotNeed.NONE
    }

    private fun buffer(event: JsonObject) {
        pendingEvents[event.long("revision")] = event
        while (pendingEvents.size > 128) pendingEvents.remove(pendingEvents.firstKey())
    }

    /** Forgets a question the host reported as answered or closed elsewhere. */
    fun dropQuestion(questionId: String) {
        questions = questions.filterNot { it.text("id") == questionId }
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
