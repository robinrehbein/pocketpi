package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

// Pure mapping between push payloads, pending questions and notification actions. Everything here
// runs on the JVM without Android so it stays unit-tested.

internal enum class PushEvent(val wire: String) {
    QUESTION("question"),
    COMPLETE("complete"),
    SUBAGENT_DONE("subagent.done"),
    SUBAGENT_STUCK("subagent.stuck"),
    JOB_DONE("job.done"),
    JOB_STUCK("job.stuck");

    /**
     * A background subagent or shell of a session, rather than the session itself. These say
     * nothing about the session's own state or its questions and get their own notification.
     */
    val attention: Boolean
        get() = this != QUESTION && this != COMPLETE
}

/** The data of one push message. It only ever carries opaque identifiers, never chat text. */
internal data class PushPayload(
    val routeId: String,
    val sessionId: String,
    val eventId: String,
    val event: PushEvent,
    /** Parent session of a subagent child or of a background job's session. */
    val parent: String? = null,
    /** The background job of `job.done` and `job.stuck`; absent for a bundle. */
    val jobId: String? = null,
    /** Completions bundled into this push, in 1..[MAX_PUSH_COUNT]. */
    val count: Int = 1,
)

private val opaqueId = Regex("[A-Za-z0-9_-]{1,256}")
private val pushCount = Regex("[1-9][0-9]{0,2}")

/** The relay's upper bound for a bundled completion count. */
internal const val MAX_PUSH_COUNT = 999

internal fun isOpaqueId(value: String?): Boolean = value != null && opaqueId.matches(value)

/** Returns null for a malformed payload or a kind this version does not know, which is ignored. */
internal fun parsePushPayload(data: Map<String, String>): PushPayload? {
    val route = data["routeId"]
    val target = data["target"]
    val event = data["eventId"]
    if (!isOpaqueId(route) || !isOpaqueId(target) || !isOpaqueId(event)) return null
    // Older relays send no kind at all; that stays a completion notice.
    val kind = data["event"]?.let { wire -> PushEvent.entries.find { it.wire == wire } ?: return null }
        ?: PushEvent.COMPLETE
    // Optional fields degrade instead of dropping the push: a bad id or count is left out. They
    // stay nullable here so the strict isOpaqueId(String?) applies, not the looser String one.
    val count =
        data["count"]?.takeIf { pushCount.matches(it) }?.toInt()?.takeIf { it <= MAX_PUSH_COUNT } ?: 1
    return PushPayload(
        route!!,
        target!!,
        event!!,
        kind,
        data["parent"].takeIf { kind.attention && isOpaqueId(it) },
        data["jobId"].takeIf {
            (kind == PushEvent.JOB_DONE || kind == PushEvent.JOB_STUCK) && isOpaqueId(it)
        },
        if (kind == PushEvent.SUBAGENT_DONE || kind == PushEvent.JOB_DONE) count else 1,
    )
}

/** Where a tapped notification leads: a session, optionally with its background jobs open. */
internal data class NotificationTarget(
    val routeId: String,
    val sessionId: String,
    /** Opens the session's background jobs list. */
    val jobs: Boolean = false,
    /** Opens this job's output on top of the list. */
    val jobId: String? = null,
)

/**
 * A subagent push opens its target, the child session, or the parent for a bundle. A job push
 * opens the jobs of the session that owns the job: that job alone, or the list for a bundle or a
 * push without a usable job id.
 */
internal fun notificationTarget(payload: PushPayload): NotificationTarget =
    when (payload.event) {
        PushEvent.JOB_DONE,
        PushEvent.JOB_STUCK ->
            NotificationTarget(
                payload.routeId,
                payload.sessionId,
                jobs = true,
                jobId = payload.jobId?.takeIf { payload.count == 1 },
            )
        else -> NotificationTarget(payload.routeId, payload.sessionId)
    }

/** The title of a subagent or job notification: a string, or a plural for a bundle. */
internal sealed interface AttentionTitle {
    data class Single(val id: Int) : AttentionTitle

    data class Bundle(val plural: Int, val count: Int) : AttentionTitle
}

internal fun attentionTitle(payload: PushPayload): AttentionTitle =
    when (payload.event) {
        PushEvent.SUBAGENT_DONE ->
            if (payload.count > 1)
                AttentionTitle.Bundle(R.plurals.remote_notification_subagents_done, payload.count)
            else AttentionTitle.Single(R.string.remote_notification_subagent_done)
        PushEvent.SUBAGENT_STUCK -> AttentionTitle.Single(R.string.remote_notification_subagent_stuck)
        PushEvent.JOB_DONE ->
            if (payload.count > 1)
                AttentionTitle.Bundle(R.plurals.remote_notification_jobs_done, payload.count)
            else AttentionTitle.Single(R.string.remote_notification_job_done)
        PushEvent.JOB_STUCK -> AttentionTitle.Single(R.string.remote_notification_job_stuck)
        PushEvent.QUESTION -> AttentionTitle.Single(R.string.remote_notification_question)
        PushEvent.COMPLETE -> AttentionTitle.Single(R.string.remote_notification_complete)
    }

internal sealed interface ActionLabel {
    data class Resource(val id: Int) : ActionLabel

    data class Text(val text: String) : ActionLabel
}

internal sealed interface NotificationAction {
    /** Sends [answer] from the background without opening the app. */
    data class Answer(val label: ActionLabel, val answer: JsonObject) : NotificationAction

    /** Inline text reply that becomes an `input` answer. */
    data object Reply : NotificationAction

    /** Opens the session in the app. */
    data object Open : NotificationAction

    /** Opens the session to answer there, for questions too big for a notification. */
    data object AnswerInApp : NotificationAction
}

internal data class QuestionNotification(
    val questionId: String,
    val kind: String,
    /** Question title from the host; null means the generic question title. */
    val title: String?,
    val body: String?,
    val actions: List<NotificationAction>,
)

internal const val MAX_OPTION_ACTIONS = 3
internal const val MAX_ACTION_LABEL = 40
private const val MAX_NOTIFICATION_TEXT = 400

private fun bounded(text: String?, limit: Int): String? =
    text?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (it.length <= limit) it else it.take(limit - 1).trimEnd() + "…"
    }

private fun cut(text: String?, limit: Int): Boolean = (text?.trim()?.length ?: 0) > limit

/** Answering needs the whole question in view; otherwise the notification only opens the app. */
private fun answerInAppOnly(content: QuestionNotification): QuestionNotification =
    if (content.actions.none { it is NotificationAction.Answer || it == NotificationAction.Reply })
        content
    else content.copy(actions = listOf(NotificationAction.AnswerInApp))

/** The question a notification is about: the oldest one still pending. */
internal fun notifiedQuestion(pending: List<JsonObject>): JsonObject? = pending.firstOrNull()

/**
 * Maps a pending question to notification content and actions, or null if it is malformed.
 *
 * [backgroundAnswers] is false below Android 12 (API 31): there an action cannot require unlocking,
 * so answering from a locked screen would be possible and only in-app answers are offered. Content
 * the notification had to shorten is also answered in the app, never blind.
 */
internal fun questionNotification(
    question: JsonObject,
    backgroundAnswers: Boolean,
): QuestionNotification? =
    try {
        mapQuestion(question)?.let { content ->
            val shortened =
                when (content.kind) {
                    "confirm" -> cut(question.optionalText("title"), 120) ||
                        cut(question.optionalText("message"), MAX_NOTIFICATION_TEXT)
                    "plan" -> cut(question.optionalText("title"), 120) ||
                        cut(question.optionalText("plan"), MAX_NOTIFICATION_TEXT)
                    "select", "input" -> cut(question.optionalText("title"), 120)
                    else -> false
                }
            if (!backgroundAnswers || shortened) answerInAppOnly(content) else content
        }
    } catch (_: Exception) {
        null
    }

private fun mapQuestion(question: JsonObject): QuestionNotification? =
    try {
        val id = question.text("id")
        require(id.isNotBlank() && id.length <= 256)
        when (val kind = question.text("kind")) {
            "confirm" ->
                QuestionNotification(
                    id,
                    kind,
                    bounded(question.text("title"), 120),
                    bounded(question.optionalText("message"), MAX_NOTIFICATION_TEXT),
                    listOf(
                        NotificationAction.Answer(
                            ActionLabel.Resource(R.string.remote_yes),
                            Wire.objectOf("kind" to "confirm", "value" to true),
                        ),
                        NotificationAction.Answer(
                            ActionLabel.Resource(R.string.remote_no),
                            Wire.objectOf("kind" to "confirm", "value" to false),
                        ),
                    ),
                )
            "select" -> {
                val options = question.getValue("options").jsonArray.map {
                    val value = it.jsonPrimitive
                    require(value.isString)
                    value.content
                }
                // Shortened labels must still tell the options apart.
                val answerable =
                    options.size in 1..MAX_OPTION_ACTIONS &&
                        options.all { it.isNotBlank() && it.trim().length <= MAX_ACTION_LABEL } &&
                        options.distinct().size == options.size &&
                        options.map { it.trim() }.distinct().size == options.size
                QuestionNotification(
                    id,
                    kind,
                    bounded(question.text("title"), 120),
                    if (answerable) null
                    else bounded(options.take(8).joinToString(" · "), MAX_NOTIFICATION_TEXT),
                    if (answerable)
                        options.map { option ->
                            NotificationAction.Answer(
                                ActionLabel.Text(bounded(option, MAX_ACTION_LABEL)!!),
                                Wire.objectOf("kind" to "select", "value" to option),
                            )
                        }
                    else listOf(NotificationAction.AnswerInApp),
                )
            }
            "input" ->
                QuestionNotification(
                    id,
                    kind,
                    bounded(question.text("title"), 120),
                    bounded(question.optionalText("placeholder"), 120),
                    listOf(NotificationAction.Reply, NotificationAction.Open),
                )
            "plan" ->
                QuestionNotification(
                    id,
                    kind,
                    bounded(question.optionalText("title"), 120),
                    bounded(question.optionalText("plan"), MAX_NOTIFICATION_TEXT),
                    listOf(
                        NotificationAction.Answer(
                            ActionLabel.Resource(R.string.remote_notification_approve),
                            Wire.objectOf("kind" to "plan", "action" to "approve"),
                        ),
                        NotificationAction.Open,
                    ),
                )
            "questionnaire" ->
                QuestionNotification(id, kind, null, null, emptyList())
            "local_required" ->
                QuestionNotification(
                    id,
                    kind,
                    bounded(question.optionalText("title"), 120),
                    null,
                    emptyList(),
                )
            else -> null
        }
    } catch (_: Exception) {
        null
    }

/** Builds the `question.answer` value for an inline reply, or null when it is blank or too long. */
internal fun inputAnswer(text: CharSequence?): JsonObject? {
    val value = text?.toString()?.trim().orEmpty()
    if (value.isEmpty() || value.toByteArray().size > 32 * 1024) return null
    return Wire.objectOf("kind" to "input", "value" to value)
}

internal const val FETCH_TIMEOUT_MILLIS = 6_000L
internal const val UNREACHABLE_FETCH_TIMEOUT_MILLIS = 2_000L
internal const val UNREACHABLE_WINDOW_MILLIS = 60_000L

/** How long to try reading a question: shorter while the host was recently unreachable. */
internal fun fetchTimeoutMillis(lastUnreachableAt: Long?, now: Long): Long =
    if (lastUnreachableAt != null && now - lastUnreachableAt in 0 until UNREACHABLE_WINDOW_MILLIS)
        UNREACHABLE_FETCH_TIMEOUT_MILLIS
    else FETCH_TIMEOUT_MILLIS

internal enum class AnswerOutcome {
    SENT,
    ALREADY_RESOLVED,
    FAILED,
}

/** Maps a failed `question.answer` to what the notification says next. */
internal fun answerOutcome(error: Throwable?): AnswerOutcome =
    when {
        error == null -> AnswerOutcome.SENT
        error is RemoteCommandException &&
            error.code in setOf("already_resolved", "not_found") -> AnswerOutcome.ALREADY_RESOLVED
        else -> AnswerOutcome.FAILED
    }

internal fun isPlanApproval(answer: JsonObject): Boolean =
    answer.optionalText("kind") == "plan" &&
        (answer["action"] as? JsonPrimitive)?.content == "approve"
