package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

// Pure mapping between push payloads, pending questions and notification actions. Everything here
// runs on the JVM without Android so it stays unit-tested.

internal enum class PushEvent {
    QUESTION,
    COMPLETE,
}

/** The data of one push message. It only ever carries opaque identifiers, never chat text. */
internal data class PushPayload(
    val routeId: String,
    val sessionId: String,
    val eventId: String,
    val event: PushEvent,
)

private val opaqueId = Regex("[A-Za-z0-9_-]{1,256}")

internal fun isOpaqueId(value: String?): Boolean = value != null && opaqueId.matches(value)

internal fun parsePushPayload(data: Map<String, String>): PushPayload? {
    val route = data["routeId"]
    val target = data["target"]
    val event = data["eventId"]
    if (!isOpaqueId(route) || !isOpaqueId(target) || !isOpaqueId(event)) return null
    return PushPayload(
        route!!,
        target!!,
        event!!,
        // Older relays and unknown kinds keep the previous behavior: a completion notice.
        if (data["event"] == "question") PushEvent.QUESTION else PushEvent.COMPLETE,
    )
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
