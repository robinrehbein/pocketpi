package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The host runs git for a session: `session.git.status`, `.diff` and `.log` (protocol README). */
internal const val GIT_CAPABILITY = "session.git.v1"

private const val MAX_GIT_FILES = 1000
private const val MAX_GIT_COMMITS = 50
private val commitId = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

/** What the working tree is compared with. */
enum class GitBase(val wire: String) {
    SESSION("session"),
    HEAD("head"),
    DEV("dev"),
}

enum class GitChange(val wire: String) {
    ADDED("added"),
    MODIFIED("modified"),
    DELETED("deleted"),
    TYPE_CHANGED("type_changed"),
}

/** Why the host has no status or log for a base. A result, not an error. */
enum class GitUnavailable(val wire: String) {
    GIT_UNAVAILABLE("git_unavailable"),
    NOT_A_REPOSITORY("not_a_repository"),
    BASE_UNAVAILABLE("base_unavailable"),
    SESSION_UNSUPPORTED("session_unsupported"),
}

/** Why a file has no counts and no diff. */
enum class GitOmitted(val wire: String) {
    /** Larger than the host reads. */
    TOO_LARGE("too_large"),

    /** Untracked at session start, and git has since garbage-collected that content. */
    BASE_UNAVAILABLE("base_unavailable"),
}

data class GitFile(
    val path: String,
    val change: GitChange,
    val additions: Int? = null,
    val deletions: Int? = null,
    val binary: Boolean = false,
    val omitted: GitOmitted? = null,
)

/** When the session snapshot was taken; [firstContact] means later than the session start. */
data class GitSince(val firstContact: Boolean, val at: Long)

data class GitStatus(
    val base: GitBase,
    val snapshotId: String?,
    val files: List<GitFile> = emptyList(),
    val truncated: Boolean = false,
    val since: GitSince? = null,
    val baseCommit: String? = null,
    val devRef: String? = null,
    val branch: String? = null,
    val unavailable: GitUnavailable? = null,
) {
    val available: Boolean get() = unavailable == null
}

data class GitDiff(
    val path: String,
    val snapshotId: String,
    val patch: String,
    val binary: Boolean,
    val truncated: Boolean,
    val omitted: GitOmitted? = null,
)

data class GitCommit(val sha: String, val subject: String, val author: String, val time: Long)

data class GitLog(
    val commits: List<GitCommit> = emptyList(),
    val truncated: Boolean = false,
    val unavailable: GitUnavailable? = null,
)

/**
 * A pending review comment on one diff line: [newLine] for added and context lines, else
 * [oldLine]. Line numbers only mean something against the [base] the diff was taken from.
 */
data class ReviewComment(
    val path: String,
    val oldLine: Int?,
    val newLine: Int?,
    val quoted: String,
    val text: String,
    val base: GitBase,
) {
    fun sameLine(other: ReviewComment): Boolean =
        path == other.path && oldLine == other.oldLine && newLine == other.newLine && base == other.base
}

internal const val MAX_REVIEW_COMMENTS = 100
internal const val MAX_REVIEW_COMMENT_BYTES = 4096
internal const val MAX_REVIEW_QUOTE_CHARS = 500

internal fun validReviewComment(comment: ReviewComment): Boolean =
    comment.path.isNotEmpty() && comment.path.encodeToByteArray().size <= 4096 &&
        (comment.newLine ?: comment.oldLine ?: 0) > 0 &&
        comment.text.isNotBlank() && comment.text.encodeToByteArray().size <= MAX_REVIEW_COMMENT_BYTES &&
        comment.quoted.length <= MAX_REVIEW_QUOTE_CHARS

internal fun ReviewComment.json(): JsonObject =
    Wire.objectOf(
        "path" to path,
        "oldLine" to oldLine,
        "newLine" to newLine,
        "quoted" to quoted,
        "text" to text,
        "base" to base.wire,
    )

internal fun reviewComment(value: JsonObject): ReviewComment {
    fun line(key: String): Int? =
        (value[key] as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            ?.let { value.long(key).toInt() }
    return ReviewComment(
        value.text("path"),
        line("oldLine"),
        line("newLine"),
        value.text("quoted"),
        value.text("text"),
        // Comments stored before the base was recorded were written against session start.
        value.optionalText("base")?.let { wire -> requireNotNull(GitBase.entries.firstOrNull { it.wire == wire }) }
            ?: GitBase.SESSION,
    ).also { require(validReviewComment(it)) }
}

/**
 * The review comments of one stored draft. An entry that does not parse is dropped, so a bad
 * comment can never make the draft file, and with it every draft, unreadable.
 */
internal fun storedReviewComments(value: JsonElement?): List<ReviewComment> =
    (value as? JsonArray)
        ?.mapNotNull { element -> runCatching { reviewComment(element.jsonObject) }.getOrNull() }
        ?.distinctBy { Triple(it.path, it.oldLine to it.newLine, it.base) }
        ?.take(MAX_REVIEW_COMMENTS)
        .orEmpty()

/** Why the changes view shows no data. */
enum class ChangesFailure {
    UNSUPPORTED,
    OFFLINE,
    NOT_FOUND,
    FORBIDDEN,
    BUSY,
    FAILED,
}

/**
 * The changes view of the selected session. It lives in [RemoteState], like the tool output
 * download, so any container (overlay today, a tablet inspector later) can show it.
 */
data class ChangesState(
    val sessionId: String,
    /** The base the user asked for; [status] may show HEAD after a session-start fallback. */
    val base: GitBase = GitBase.SESSION,
    val loading: Boolean = true,
    val status: GitStatus? = null,
    /** Set when session start was not available and the view fell back to HEAD. */
    val sessionUnavailable: GitUnavailable? = null,
    val failure: ChangesFailure? = null,
    val file: String? = null,
    val diff: GitDiff? = null,
    val diffLoading: Boolean = false,
    val diffFailure: ChangesFailure? = null,
    val log: GitLog? = null,
    val logLoading: Boolean = false,
    val logFailure: ChangesFailure? = null,
    val comments: List<ReviewComment> = emptyList(),
)

internal fun canViewChanges(state: RemoteState): Boolean =
    GIT_CAPABILITY in state.capabilities && GIT_CAPABILITY !in state.unavailableCapabilities &&
        state.selection.sessionId != null

// ---- Wire ---------------------------------------------------------------------------------

private fun JsonObject.count(key: String): Int? =
    if (containsKey(key)) long(key).also { require(it in 0..Int.MAX_VALUE) }.toInt() else null

private fun unavailable(value: String): GitUnavailable =
    requireNotNull(GitUnavailable.entries.firstOrNull { it.wire == value })

private fun omitted(value: String?): GitOmitted? =
    value?.let { wire -> requireNotNull(GitOmitted.entries.firstOrNull { it.wire == wire }) }

private fun validSnapshotId(value: String): Boolean =
    runCatching { Wire.decode(value, 16) }.isSuccess

private fun gitFile(value: JsonObject): GitFile {
    val path = value.text("path")
    require(path.isNotEmpty() && '\u0000' !in path)
    val change = requireNotNull(GitChange.entries.firstOrNull { it.wire == value.text("change") })
    val additions = value.count("additions")
    val deletions = value.count("deletions")
    // The host sends binary only as true; false or anything else is not a valid entry.
    val binary = if (value.containsKey("binary")) value.flag("binary").also { require(it) } else false
    val omitted = omitted(value.optionalText("omitted"))
    val counted = additions != null && deletions != null
    require(counted == (additions != null || deletions != null))
    require(counted != (binary || omitted != null))
    require(!(binary && omitted != null))
    return GitFile(path, change, additions, deletions, binary, omitted)
}

/** Validates a `git.status` result against the request that produced it. */
internal fun parseGitStatus(data: JsonObject, sessionId: String, base: GitBase): GitStatus {
    require(data.text("kind") == "git.status")
    require(data.text("sessionId") == sessionId && data.text("base") == base.wire)
    if (!data.flag("available")) {
        require(data.keys == setOf("kind", "sessionId", "base", "available", "reason"))
        return GitStatus(base, null, unavailable = unavailable(data.text("reason")))
    }
    require("reason" !in data)
    val snapshotId = data.text("snapshotId")
    require(validSnapshotId(snapshotId))
    val files = data.array("files").map(::gitFile)
    require(files.size <= MAX_GIT_FILES)
    val since =
        (data["since"] as? JsonObject)?.let {
            require(base == GitBase.SESSION)
            val kind = it.text("kind")
            require(kind in setOf("session_start", "first_contact"))
            GitSince(kind == "first_contact", it.long("at").also { at -> require(at >= 0) })
        }
    val baseCommit = data.optionalText("baseCommit")?.also { require(commitId.matches(it)) }
    val devRef =
        data.optionalText("devRef")?.also {
            require(base == GitBase.DEV && it in setOf("origin/dev", "dev"))
        }
    val branch = data.optionalText("branch")?.also {
        require(it.isNotEmpty() && it.encodeToByteArray().size <= 256)
    }
    return GitStatus(base, snapshotId, files, data.flag("truncated"), since, baseCommit, devRef, branch)
}

/** Validates a `git.diff` result against the request that produced it. */
internal fun parseGitDiff(data: JsonObject, sessionId: String, snapshotId: String, path: String): GitDiff {
    require(data.text("kind") == "git.diff")
    require(data.text("sessionId") == sessionId && data.text("snapshotId") == snapshotId)
    require(data.text("path") == path)
    val patch = data.text("patch")
    val binary = data.flag("binary")
    val omitted = omitted(data.optionalText("omitted"))
    require(patch.isEmpty() || (!binary && omitted == null))
    return GitDiff(path, snapshotId, patch, binary, data.flag("truncated"), omitted)
}

/** Validates a `git.log` result against the request that produced it. */
internal fun parseGitLog(data: JsonObject, sessionId: String, snapshotId: String): GitLog {
    require(data.text("kind") == "git.log")
    require(data.text("sessionId") == sessionId && data.text("snapshotId") == snapshotId)
    if (!data.flag("available")) {
        require(data.keys == setOf("kind", "sessionId", "snapshotId", "available", "reason"))
        val reason = unavailable(data.text("reason"))
        require(reason == GitUnavailable.BASE_UNAVAILABLE)
        return GitLog(unavailable = reason)
    }
    require(data.keys == setOf("kind", "sessionId", "snapshotId", "available", "commits", "truncated"))
    val commits =
        data.array("commits").map { value ->
            require(value.keys == setOf("sha", "subject", "author", "time"))
            GitCommit(
                value.text("sha").also { require(commitId.matches(it)) },
                value.text("subject").also { require(it.encodeToByteArray().size <= 1024) },
                value.text("author").also { require(it.encodeToByteArray().size <= 256) },
                value.long("time").also { require(it >= 0) },
            )
        }
    require(commits.size <= MAX_GIT_COMMITS)
    return GitLog(commits, data.flag("truncated"))
}

// ---- Review prompt ------------------------------------------------------------------------

/**
 * One prompt for all pending comments: `path:line`, the quoted line and the comment, in file, line
 * and base order. [location] formats a line reference, naming the base the line number belongs
 * to; removed lines refer to the old file.
 */
internal fun reviewPrompt(
    intro: String,
    comments: List<ReviewComment>,
    location: (path: String, line: Int, removed: Boolean, base: GitBase) -> String,
): String =
    buildString {
        append(intro)
        comments
            .sortedWith(compareBy({ it.path }, { it.newLine ?: it.oldLine ?: 0 }, { it.base.ordinal }))
            .forEach { comment ->
                val removed = comment.newLine == null
                val line = comment.newLine ?: comment.oldLine ?: 0
                append("\n\n")
                append(location(comment.path, line, removed, comment.base))
                append("\n> ")
                append(comment.quoted.take(MAX_REVIEW_QUOTE_CHARS).trimEnd())
                append('\n')
                append(comment.text.trim())
            }
    }

/** [prompt] after what the user already typed, so prefilling never discards a draft. */
internal fun prefilledDraft(draft: String, prompt: String): String =
    if (draft.isBlank()) prompt else draft.trimEnd() + "\n\n" + prompt
