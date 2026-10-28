package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The host lists Plane tickets of shared projects and acts on them (`tickets.*`, protocol README).
 * Advertised only on the `capabilities.v2:` route of `projects.list`.
 */
internal const val TICKETS_CAPABILITY = "tickets.v1"

// Limits of the host's `protocol/tickets.ts`; the host rejects anything beyond them.
internal const val MAX_TICKET_COMMENT_CHARS = 8000
internal const val MAX_TICKET_MOVE_COMMENT_CHARS = 2000
internal const val MAX_TICKET_NOTE_CHARS = 2000
internal const val MAX_TICKET_DESCRIPTION_BYTES = 32 * 1024
internal const val MAX_TICKET_COMMENTS = 30
internal const val MAX_TICKET_COMMENT_TEXT_BYTES = 6 * 1024
internal const val MAX_TICKETS_PAGE = 100
internal const val MAX_TICKET_PROJECTS = 64
private const val MAX_TICKET_TITLE_BYTES = 4096
private const val MAX_TICKET_URL_BYTES = 2048
private const val MAX_TICKET_CURSOR_BYTES = 1024
private const val MAX_TICKET_SESSIONS = 256
private const val MAX_SAFE_INTEGER = 9007199254740991L

/** Only the Plane projects DEV and TOOLING. */
private val ticketRef = Regex("(DEV|TOOLING)-[1-9][0-9]{0,8}")

/** States a ticket may be moved to, as the host spells them. */
internal val TICKET_MOVE_STATES = setOf("Done", "Cancelled", "Todo", "In Progress", "Backlog")

/** Moving to one of these needs a comment. */
internal val TICKET_MOVE_COMMENT_STATES = setOf("Done", "Cancelled")

enum class TicketSection(val wire: String) {
    ACCEPTANCE("acceptance"),
    WORKING("working"),
    TODO("todo"),
}

enum class TicketAction(val wire: String) {
    ACCEPT("accept"),
    START("start"),
    COMMENT("comment"),
    MOVE("move"),
}

/** Why Plane has no tickets to show. A result, not an error. */
enum class TicketReason(val wire: String) {
    NOT_CONFIGURED("not_configured"),
    MISSING_BOT_KEY("missing_bot_key"),
    PLANE_UNREACHABLE("plane_unreachable"),
    PLANE_REJECTED("plane_rejected"),
}

enum class TicketStateGroup(val wire: String) {
    BACKLOG("backlog"),
    UNSTARTED("unstarted"),
    STARTED("started"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    TRIAGE("triage"),
}

enum class TicketPriority(val wire: String) {
    URGENT("urgent"),
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low"),
    NONE("none"),
}

/** Why the host does not let this device write to a project's tickets. */
enum class TicketWriteBlock(val wire: String) {
    WRITES_OFF("writes_off"),
    MISSING_USER_KEY("missing_user_key"),
}

enum class TicketStartState(val wire: String) {
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
}

enum class TicketStartStep(val wire: String) {
    WORKTREE("worktree"),
    MOVE("move"),
    SESSION("session"),
    PROMPT("prompt"),
    DONE("done"),
}

enum class TicketStartError(val wire: String) {
    WORKTREE_FAILED("worktree_failed"),
    SESSION_FAILED("session_failed"),
    PROMPT_FAILED("prompt_failed"),
    TIMEOUT("timeout"),
    NOT_SUPPORTED("not_supported"),
}

/** The status of a chat linked to a ticket. */
enum class SessionLinkStatus(val wire: String) {
    IDLE("idle"),
    RUNNING("running"),
    WAITING("waiting"),
    OFFLINE("offline"),
}

data class TicketProject(
    val projectId: String,
    val name: String,
    val planeProjects: List<String>,
    val writable: Boolean,
    val writeBlock: TicketWriteBlock?,
    val available: Boolean,
    val reason: TicketReason?,
)

data class TicketSession(val id: String, val title: String, val status: SessionLinkStatus)

data class TicketPr(val number: Long, val url: String)

data class Ticket(
    val ref: String,
    val title: String,
    val url: String,
    val state: String,
    val stateGroup: TicketStateGroup,
    val type: String?,
    val priority: TicketPriority,
    val assignedToMe: Boolean,
    val section: TicketSection,
    /** The host's `caption: "pr_open"`. */
    val prOpen: Boolean,
    val actions: Set<TicketAction>,
    val moveTargets: List<String>,
    val updatedAt: Long,
    val sessions: List<TicketSession>,
    /** Reserved by the host. */
    val pr: TicketPr? = null,
    /** Reserved by the host: `pass`, `fail` or `unknown`. */
    val checks: String? = null,
)

data class TicketComment(val id: String, val author: String, val createdAt: Long, val text: String)

/** [ticket] is null when Plane could not be asked; [reason] says why. */
data class TicketDetail(
    val ticket: Ticket?,
    val description: String = "",
    val descriptionTruncated: Boolean = false,
    val comments: List<TicketComment> = emptyList(),
    val commentsTruncated: Boolean = false,
    val reason: TicketReason? = null,
) {
    val available: Boolean get() = ticket != null
}

/** One page of a project's tickets; [available] false carries only [reason]. */
data class TicketsPage(
    val projectId: String,
    val available: Boolean,
    val reason: TicketReason?,
    val syncedAt: Long?,
    val items: List<Ticket>,
    val nextCursor: String?,
)

data class TicketStart(
    val startId: String,
    val state: TicketStartState,
    val step: TicketStartStep,
    val worktreeReused: Boolean,
    /** Whether the Plane move happened; null until known. */
    val moved: Boolean?,
    val sessionId: String?,
    val error: TicketStartError?,
)

/** Tickets show with the capability and while the host has not reported it unavailable. */
internal fun canUseTickets(state: RemoteState): Boolean =
    state.connected && TICKETS_CAPABILITY in state.capabilities && TICKETS_CAPABILITY !in state.unavailableCapabilities

// ---- Requests -----------------------------------------------------------------------------

internal fun validTicketRef(ref: String): Boolean = ticketRef.matches(ref)

internal fun requireTicketRef(ref: String) = require(validTicketRef(ref))

internal fun requireTicketProjectId(projectId: String) = require(validOpaqueId(projectId))

/** The checks the host makes on `tickets.comment`, so a bad one is never sent. */
internal fun requireTicketComment(text: String) = require(text.isNotEmpty() && text.length <= MAX_TICKET_COMMENT_CHARS)

internal fun requireTicketMove(state: String, comment: String?) {
    require(state in TICKET_MOVE_STATES)
    require(comment != null || state !in TICKET_MOVE_COMMENT_STATES)
    require(comment == null || (comment.isNotEmpty() && comment.length <= MAX_TICKET_MOVE_COMMENT_CHARS))
}

internal fun requireTicketNote(note: String?) =
    require(note == null || (note.isNotEmpty() && note.length <= MAX_TICKET_NOTE_CHARS))

internal fun requireTicketCursor(cursor: String?) =
    require(cursor == null || (cursor.isNotEmpty() && cursor.encodeToByteArray().size <= MAX_TICKET_CURSOR_BYTES))

internal fun requireStartId(startId: String) {
    require(startId.length == 22)
    Wire.decode(startId, 16)
}

// ---- Wire ---------------------------------------------------------------------------------

/** 1..256 bytes without control characters, spaces or DEL, like the host's `opaqueId`. */
private fun validOpaqueId(value: String): Boolean =
    value.isNotEmpty() && value.encodeToByteArray().size <= 256 && value.none { it <= ' ' || it == '\u007f' }

private fun JsonObject.opaque(key: String): String = text(key).also { require(validOpaqueId(it)) }

private fun JsonObject.bytes(key: String, max: Int, empty: Boolean = false): String =
    text(key).also { require((empty || it.isNotEmpty()) && it.encodeToByteArray().size <= max) }

private fun JsonObject.count(key: String): Long =
    long(key).also { require(it in 0..MAX_SAFE_INTEGER) }

private fun JsonObject.ref(key: String): String = text(key).also { requireTicketRef(it) }

private inline fun <reified E : Enum<E>> enumOf(entries: List<E>, wire: (E) -> String, value: String): E =
    requireNotNull(entries.firstOrNull { wire(it) == value })

private fun JsonObject.reason(key: String = "reason"): TicketReason =
    enumOf(TicketReason.entries, { it.wire }, text(key))

private fun JsonArray.objects(max: Int): List<JsonObject> {
    require(size <= max)
    return map { it as? JsonObject ?: throw IllegalArgumentException() }
}

private fun JsonObject.list(key: String, max: Int): JsonArray =
    (getValue(key) as? JsonArray ?: throw IllegalArgumentException()).also { require(it.size <= max) }

private fun JsonObject.strings(key: String, max: Int): List<String> =
    list(key, max).map {
        require(it is JsonPrimitive && it.isString)
        it.content
    }

private val ticketRequired =
    setOf(
        "ref", "title", "url", "state", "stateGroup", "type", "priority", "assignedToMe", "section",
        "actions", "moveTargets", "updatedAt", "sessions",
    )
private val ticketOptional = setOf("caption", "pr", "checks")
private val detailRequired = setOf("description", "descriptionTruncated", "comments", "commentsTruncated")
private val ticketChecks = setOf("pass", "fail", "unknown")

private fun ticket(value: JsonObject, extra: Set<String> = emptySet()): Ticket {
    Wire.keys(value, ticketRequired + extra, ticketOptional)
    val type =
        if (value["type"] is JsonNull) null else value.bytes("type", 256)
    val actions =
        value.list("actions", TicketAction.entries.size).map {
            require(it is JsonPrimitive && it.isString)
            enumOf(TicketAction.entries, { a -> a.wire }, it.content)
        }
    val moveTargets = value.strings("moveTargets", TICKET_MOVE_STATES.size).onEach { require(it in TICKET_MOVE_STATES) }
    val sessions =
        value.list("sessions", MAX_TICKET_SESSIONS).objects(MAX_TICKET_SESSIONS).map { session ->
            Wire.keys(session, setOf("id", "title", "status"))
            TicketSession(
                session.opaque("id"),
                session.bytes("title", MAX_TICKET_TITLE_BYTES, empty = true),
                enumOf(SessionLinkStatus.entries, { it.wire }, session.text("status")),
            )
        }
    val caption = if (value.containsKey("caption")) value.text("caption").also { require(it == "pr_open") } else null
    val pr =
        value["pr"]?.let { element ->
            val pr = element as? JsonObject ?: throw IllegalArgumentException()
            Wire.keys(pr, setOf("number", "url"))
            TicketPr(pr.count("number"), pr.bytes("url", MAX_TICKET_URL_BYTES))
        }
    val checks = if (value.containsKey("checks")) value.text("checks").also { require(it in ticketChecks) } else null
    return Ticket(
        ref = value.ref("ref"),
        title = value.bytes("title", MAX_TICKET_TITLE_BYTES),
        url = value.bytes("url", MAX_TICKET_URL_BYTES),
        state = value.bytes("state", 256),
        stateGroup = enumOf(TicketStateGroup.entries, { it.wire }, value.text("stateGroup")),
        type = type,
        priority = enumOf(TicketPriority.entries, { it.wire }, value.text("priority")),
        assignedToMe = value.flag("assignedToMe"),
        section = enumOf(TicketSection.entries, { it.wire }, value.text("section")),
        prOpen = caption != null,
        actions = actions.toSet(),
        moveTargets = moveTargets,
        updatedAt = value.count("updatedAt"),
        sessions = sessions,
        pr = pr,
        checks = checks,
    )
}

private fun JsonObject.projectIs(projectId: String) {
    require(opaque("projectId") == projectId)
}

/** Validates a `tickets.projects` result. */
internal fun parseTicketProjects(data: JsonObject): List<TicketProject> {
    require(data.text("kind") == "tickets.projects")
    Wire.keys(data, setOf("kind", "items"))
    return data.list("items", MAX_TICKET_PROJECTS).objects(MAX_TICKET_PROJECTS).map { item ->
        Wire.keys(
            item,
            setOf("projectId", "name", "planeProjects", "writable", "available"),
            setOf("writeBlock", "reason"),
        )
        val writable = item.flag("writable")
        val available = item.flag("available")
        val writeBlock =
            if (item.containsKey("writeBlock")) {
                enumOf(TicketWriteBlock.entries, { it.wire }, item.text("writeBlock"))
            } else {
                null
            }
        val reason = if (item.containsKey("reason")) item.reason() else null
        // The host sends a block only for a project it will not write to, a reason only for an unavailable one.
        require(!(writable && writeBlock != null))
        require(!(available && reason != null))
        TicketProject(
            item.opaque("projectId"),
            item.bytes("name", MAX_TICKET_TITLE_BYTES, empty = true),
            item.strings("planeProjects", 16).onEach { require(it.isNotEmpty() && it.encodeToByteArray().size <= 64) },
            writable,
            writeBlock,
            available,
            reason,
        )
    }
}

/** Validates a `tickets.list` result against the project asked for. */
internal fun parseTicketsPage(data: JsonObject, projectId: String): TicketsPage {
    require(data.text("kind") == "tickets.list")
    val available = data.flag("available")
    if (!available) {
        Wire.keys(data, setOf("kind", "projectId", "available", "reason"))
        data.projectIs(projectId)
        return TicketsPage(projectId, false, data.reason(), null, emptyList(), null)
    }
    Wire.keys(data, setOf("kind", "projectId", "available", "syncedAt", "items"), setOf("nextCursor"))
    data.projectIs(projectId)
    val items = data.list("items", MAX_TICKETS_PAGE).objects(MAX_TICKETS_PAGE).map { ticket(it) }
    val cursor = if (data.containsKey("nextCursor")) data.bytes("nextCursor", MAX_TICKET_CURSOR_BYTES) else null
    return TicketsPage(projectId, true, null, data.count("syncedAt"), items, cursor)
}

/** Validates a `tickets.ticket` result against the project asked for. */
internal fun parseTicketDetail(data: JsonObject, projectId: String): TicketDetail {
    require(data.text("kind") == "tickets.ticket")
    if (!data.flag("available")) {
        Wire.keys(data, setOf("kind", "projectId", "available", "reason"))
        data.projectIs(projectId)
        return TicketDetail(null, reason = data.reason())
    }
    Wire.keys(data, setOf("kind", "projectId", "available", "ticket"))
    data.projectIs(projectId)
    val value = data.obj("ticket")
    val ticket = ticket(value, detailRequired)
    val comments =
        value.list("comments", MAX_TICKET_COMMENTS).objects(MAX_TICKET_COMMENTS).map { comment ->
            Wire.keys(comment, setOf("id", "author", "createdAt", "text"))
            TicketComment(
                comment.opaque("id"),
                comment.bytes("author", 1024, empty = true),
                comment.count("createdAt"),
                comment.bytes("text", MAX_TICKET_COMMENT_TEXT_BYTES, empty = true),
            )
        }
    return TicketDetail(
        ticket,
        value.bytes("description", MAX_TICKET_DESCRIPTION_BYTES, empty = true),
        value.flag("descriptionTruncated"),
        comments,
        value.flag("commentsTruncated"),
    )
}

/** Validates a `tickets.reloaded` result and returns when the snapshot was taken. */
internal fun parseTicketReloaded(data: JsonObject, projectId: String): Long {
    require(data.text("kind") == "tickets.reloaded")
    Wire.keys(data, setOf("kind", "projectId", "syncedAt"))
    data.projectIs(projectId)
    return data.count("syncedAt")
}

/** Validates a `tickets.commented` result. */
internal fun parseTicketCommented(data: JsonObject, projectId: String, ref: String) {
    require(data.text("kind") == "tickets.commented")
    Wire.keys(data, setOf("kind", "projectId", "ref"))
    data.projectIs(projectId)
    require(data.ref("ref") == ref)
}

/** Validates a `tickets.moved` result; true when the ticket was already there and nothing was written. */
internal fun parseTicketMoved(data: JsonObject, projectId: String, ref: String, state: String? = null): Boolean {
    require(data.text("kind") == "tickets.moved")
    Wire.keys(data, setOf("kind", "projectId", "ref", "state", "skipped"))
    data.projectIs(projectId)
    require(data.ref("ref") == ref)
    require(data.text("state") in TICKET_MOVE_STATES && (state == null || data.text("state") == state))
    return data.flag("skipped")
}

/** Validates a `tickets.start.started` result and returns its start ID. */
internal fun parseTicketStartStarted(data: JsonObject): String {
    require(data.text("kind") == "tickets.start.started")
    Wire.keys(data, setOf("kind", "startId"))
    return data.text("startId").also(::requireStartId)
}

/** Validates a `tickets.start` result against the start asked about. */
internal fun parseTicketStart(data: JsonObject, startId: String): TicketStart {
    require(data.text("kind") == "tickets.start")
    Wire.keys(data, setOf("kind", "startId", "state", "step", "worktreeReused", "moved"), setOf("sessionId", "error"))
    require(data.text("startId") == startId)
    requireStartId(startId)
    val state = enumOf(TicketStartState.entries, { it.wire }, data.text("state"))
    val moved = if (data["moved"] is JsonNull) null else data.flag("moved")
    val error =
        if (data.containsKey("error")) enumOf(TicketStartError.entries, { it.wire }, data.text("error")) else null
    // The host reports an error only for a start that failed.
    require(error == null || state == TicketStartState.FAILED)
    return TicketStart(
        startId,
        state,
        enumOf(TicketStartStep.entries, { it.wire }, data.text("step")),
        data.flag("worktreeReused"),
        moved,
        if (data.containsKey("sessionId")) data.opaque("sessionId") else null,
        error,
    )
}
