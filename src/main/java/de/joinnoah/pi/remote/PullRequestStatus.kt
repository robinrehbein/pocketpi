package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Longest pull request URL in `session.pull_request` events and snapshots, in UTF-8 bytes. */
internal const val MAX_PULL_REQUEST_URL_BYTES = 2048

/** Most failing check names a pull request value carries. */
internal const val MAX_FAILING_CHECKS = 5

/** Longest failing check name, in UTF-8 bytes. */
internal const val MAX_CHECK_NAME_BYTES = 200

/** Largest failed check count on the wire. */
internal const val MAX_CHECK_COUNT = 10000

/** The review decision of a pull request; [NONE] when the host sent `null`. */
enum class PullRequestReview { APPROVED, CHANGES_REQUESTED, REVIEW_REQUIRED, NONE }

/** The coarse CI state: no checks, at least one pending, or checks exist and none are pending. */
enum class PullRequestChecksState { NONE, RUNNING, DONE }

/**
 * The open pull request of a session's branch, as the host reports it (`session.pull_request`
 * events and the snapshot's `pullRequest` key). [url] and the check names are untrusted plain
 * text; [failingChecks] lists at most [MAX_FAILING_CHECKS] of the [failedChecks] failing checks.
 */
data class PullRequestStatus(
    val number: Long,
    val url: String,
    val draft: Boolean,
    val review: PullRequestReview,
    val checks: PullRequestChecksState,
    val failedChecks: Int,
    val failingChecks: List<String>,
)

/** A `session.pull_request` event: the pull request, or null when it was cleared. */
internal data class SessionPullRequest(val pullRequest: PullRequestStatus?)

private val pullRequestEventKeys = setOf("type", "sessionId", "revision", "kind", "pullRequest")
private val pullRequestKeys = setOf("number", "url", "draft", "review", "checks")
private val pullRequestKeysWithNames = pullRequestKeys + "failingChecks"
private val checksKeys = setOf("state", "failed")

private const val MAX_SAFE_INTEGER = 9007199254740991L

/** [value] as a JSON number holding an exact integer within +-(2^53 - 1), else null. */
internal fun wireInteger(value: JsonElement?): Long? {
    val primitive = (value as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString } ?: return null
    if (primitive.booleanOrNull != null) return null
    primitive.content.toLongOrNull()?.let { return it.takeIf { n -> n in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER } }
    // Forms such as 1.0 or 1e2 are integers in JSON too, as long as they are exact.
    val double = primitive.content.toDoubleOrNull() ?: return null
    if (!double.isFinite() || double != Math.floor(double) || Math.abs(double) > MAX_SAFE_INTEGER) return null
    return double.toLong()
}

private fun pullRequestUrl(value: JsonElement?): String? {
    val url = (value as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content ?: return null
    if (!url.startsWith("https://") || url.length == "https://".length) return null
    if (url.encodeToByteArray().size > MAX_PULL_REQUEST_URL_BYTES) return null
    return url.takeUnless { it.any(::isUnsafeUrlChar) }
}

/** The complement of the host's `[^\s\u0000-\u001f\u007f-\u009f]`: JS whitespace, including U+FEFF, or a control. */
private fun isUnsafeUrlChar(c: Char): Boolean =
    c.code <= 0x1f || c.code in 0x7f..0x9f || c.isWhitespace() || Character.isSpaceChar(c) ||
        c == '\uFEFF' || c == '\u2028' || c == '\u2029'

private fun checkName(value: JsonElement?): String? =
    (value as? JsonPrimitive)
        ?.takeIf { it !is JsonNull && it.isString }
        ?.content
        ?.takeIf { it.isNotEmpty() && it.encodeToByteArray().size <= MAX_CHECK_NAME_BYTES }

private fun pullRequestReview(value: JsonElement?): PullRequestReview? {
    if (value is JsonNull) return PullRequestReview.NONE
    return when ((value as? JsonPrimitive)?.takeIf { it.isString }?.content) {
        "approved" -> PullRequestReview.APPROVED
        "changes_requested" -> PullRequestReview.CHANGES_REQUESTED
        "review_required" -> PullRequestReview.REVIEW_REQUIRED
        else -> null
    }
}

/**
 * [value] as a pull request status under the protocol's rules, else null: exact keys, an https
 * URL of at most [MAX_PULL_REQUEST_URL_BYTES] UTF-8 bytes without whitespace or control
 * characters, a known review and checks state, and 1 to [MAX_FAILING_CHECKS] failing check names
 * of at most [MAX_CHECK_NAME_BYTES] bytes that do not outnumber the failed checks. A JSON null is
 * not a status; [sessionPullRequest] reads it in an event as a clear.
 */
internal fun pullRequestStatus(value: JsonElement?): PullRequestStatus? {
    val obj = value as? JsonObject ?: return null
    if (obj.keys != pullRequestKeys && obj.keys != pullRequestKeysWithNames) return null
    val number = wireInteger(obj["number"])?.takeIf { it >= 1 } ?: return null
    val url = pullRequestUrl(obj["url"]) ?: return null
    val draft = (obj["draft"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
    val review = pullRequestReview(obj["review"]) ?: return null
    val checksObject = obj["checks"] as? JsonObject ?: return null
    if (checksObject.keys != checksKeys) return null
    val state = when ((checksObject["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
        "none" -> PullRequestChecksState.NONE
        "running" -> PullRequestChecksState.RUNNING
        "done" -> PullRequestChecksState.DONE
        else -> return null
    }
    val failed = wireInteger(checksObject["failed"])?.takeIf { it in 0..MAX_CHECK_COUNT }?.toInt() ?: return null
    if (state == PullRequestChecksState.NONE && failed != 0) return null
    val names = obj["failingChecks"]?.let { raw ->
        val list = raw as? JsonArray ?: return null
        if (list.size !in 1..MAX_FAILING_CHECKS || list.size > failed) return null
        list.map { checkName(it) ?: return null }
    } ?: emptyList()
    return PullRequestStatus(number, url, draft, review, state, failed, names)
}

/** The pull request [event] sets or clears, or null when a field breaks the protocol's rules. */
internal fun sessionPullRequest(event: JsonObject): SessionPullRequest? {
    if (!hasEventShape(event, pullRequestEventKeys)) return null
    val value = event["pullRequest"]
    if (value is JsonNull) return SessionPullRequest(null)
    return pullRequestStatus(value)?.let(::SessionPullRequest)
}
