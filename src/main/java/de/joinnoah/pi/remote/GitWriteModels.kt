package de.joinnoah.pi.remote

import kotlinx.serialization.json.*

enum class GitBranchReason { GIT_UNAVAILABLE, NOT_A_REPOSITORY, SESSION_UNSUPPORTED, DETACHED_HEAD, UNBORN_BRANCH, OPERATION_IN_PROGRESS, PROTECTED_BRANCH, NO_ORIGIN, UNSUPPORTED_REMOTE, LFS_UNSUPPORTED, LFS_SCAN_LIMIT }
enum class GitCommitReason { GIT_UNAVAILABLE, NOT_A_REPOSITORY, SESSION_UNSUPPORTED, DETACHED_HEAD, UNBORN_BRANCH, OPERATION_IN_PROGRESS, PROTECTED_BRANCH, LFS_UNSUPPORTED, NOTHING_TO_COMMIT, IDENTITY_MISSING, SIGNING_FAILED, LOCAL_TRANSPORT_CONFIG }
enum class GitPushReason { GIT_UNAVAILABLE, NOT_A_REPOSITORY, SESSION_UNSUPPORTED, DETACHED_HEAD, UNBORN_BRANCH, OPERATION_IN_PROGRESS, PROTECTED_BRANCH, NO_ORIGIN, UNSUPPORTED_REMOTE, LFS_UNSUPPORTED, LFS_SCAN_LIMIT, LOCAL_TRANSPORT_CONFIG, UP_TO_DATE, AUTH_FAILED, HOST_KEY, NETWORK, REJECTED_NON_FAST_FORWARD, REJECTED_REMOTE }
enum class GitPullRequestReason { GIT_UNAVAILABLE, NOT_A_REPOSITORY, SESSION_UNSUPPORTED, DETACHED_HEAD, UNBORN_BRANCH, OPERATION_IN_PROGRESS, PROTECTED_BRANCH, NO_ORIGIN, UNSUPPORTED_REMOTE, NOT_PUSHED, GH_UNAVAILABLE, GH_AUTH, NOT_GITHUB, REJECTED }
enum class GitWriteHook { PRE_COMMIT, COMMIT_MSG, PRE_PUSH }
enum class GitRemoteTip { ABSENT, EQUAL, AHEAD, BEHIND, DIVERGED }
enum class GitPushStatus { MATCHES, ABSENT, DIFFERENT, UNAVAILABLE }
const val SESSION_GIT_PULL_REQUEST_CAPABILITY = "session.git.pull_request.v1"
const val SESSION_GIT_PULL_REQUEST_STATUS_CAPABILITY = "session.git.pull_request.status.v1"
enum class GitPullRequestStatus { FOUND, ABSENT, DIFFERENT, UNAVAILABLE }
data class GitPullRequestIdentity(val number: Long, val url: String, val base: String)
enum class GitSkippedReason { OMITTED, DENIED, SUBMODULE }
data class GitWriteUpstream(val remote: String, val branch: String)
data class GitWriteTarget(val remote: String, val branch: String, val host: String, val repo: String)
data class GitSkippedPath(val path: String, val reason: GitSkippedReason)

sealed interface GitWriteResult {
    sealed interface Branch : GitWriteResult {
        data class Available(val writeId: String, val branch: String, val upstream: GitWriteUpstream?, val remoteTip: GitRemoteTip, val ahead: Long, val pushTarget: GitWriteTarget, val pullRequestBases: List<String>, val suggestedTitle: String?, val hooksSkipped: List<GitWriteHook>, val head: String? = null) : Branch
        data class Unavailable(val reason: GitBranchReason) : Branch
    }
    sealed interface Commit : GitWriteResult {
        /** Dispatch may have reached the host; inspect state before trying again. */
        data object Unknown : Commit
        data class Committed(val branch: String, val commit: String, val files: Int, val skipped: List<GitSkippedPath>, val indexUpdated: Boolean) : Commit
        data class Refused(val reason: GitCommitReason) : Commit
    }
    sealed interface Push : GitWriteResult {
        data object Unknown : Push
        data class Pushed(val branch: String, val remoteBranch: String, val commit: String, val created: Boolean, val upstreamSet: Boolean) : Push
        data class Refused(val reason: GitPushReason) : Push
    }
    /** Current observed remote state; never proves which attempt produced it. */
    data class PushStatus(val state: GitPushStatus) : GitWriteResult
    data class PullRequestStatus(val state: GitPullRequestStatus, val pullRequest: GitPullRequestIdentity?) : GitWriteResult
    sealed interface PullRequest : GitWriteResult {
        data object Unknown : PullRequest
        data class Available(val created: Boolean, val number: Long, val url: String, val base: String) : PullRequest
        data class Unavailable(val reason: GitPullRequestReason) : PullRequest
    }
}

private inline fun <reified T : Enum<T>> wireEnum(value: String): T =
    requireNotNull(enumValues<T>().firstOrNull { it.name.lowercase() == value })
// TextEncoder replaces an unpaired surrogate with U+FFFD (three UTF-8 bytes).
private fun utf8Size(value: String): Long {
    var bytes = 0L
    var index = 0
    while (index < value.length) {
        val c = value[index++]
        bytes += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() && index < value.length && value[index].isLowSurrogate() -> { index++; 4 }
            else -> 3
        }
    }
    return bytes
}
private fun string(value: String, max: Int): String = value.also { require(it.isNotEmpty() && utf8Size(it) <= max) }
private fun name(o: JsonObject, key: String) = string(o.text(key), 256)
private fun title(value: String): String = string(value, 256).also {
    require(it.none { c -> c.code in 0..31 || c.code in 127..159 })
    var i = 0
    while (i < it.length) { val c = it[i++]; if (c.isHighSurrogate()) { require(i < it.length && it[i].isLowSurrogate()); i++ } else require(!c.isLowSurrogate()) }
}
private fun branch(value: String) = string(value, 256).also { require(Regex("[A-Za-z0-9._][A-Za-z0-9._/-]*").matches(it)) }
private fun sha(o: JsonObject) = o.text("commit").also { require(Regex("[0-9a-f]{40}|[0-9a-f]{64}").matches(it)) }
private fun count(o: JsonObject, key: String, min: Long = 0, max: Long = 9007199254740991L): Long {
    val p = o.getValue(key).jsonPrimitive
    require(!p.isString && p !is JsonNull)
    val number = p.content.toDoubleOrNull() ?: error("Invalid count")
    require(number.isFinite() && number % 1.0 == 0.0 && number >= min.toDouble() && number <= max.toDouble())
    return number.toLong()
}
private fun strings(o: JsonObject, key: String, max: Int): List<String> {
    val values = o.getValue(key).jsonArray.map { it.jsonPrimitive.let { p -> require(p.isString); p.content } }
    require(values.size <= max && values.toSet().size == values.size)
    return values
}
private fun target(o: JsonObject): GitWriteTarget {
    Wire.keys(o, setOf("remote", "branch", "host", "repo"))
    require(o.text("remote") == "origin")
    val host = string(o.text("host"), 253)
    require(Regex("(?:[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?|\\[(?=[0-9A-Fa-f.]*:)[0-9A-Fa-f:.]+\\])").matches(host))
    val repo = string(o.text("repo"), 512)
    val segments = repo.split('/')
    require(segments.all { Regex("[A-Za-z0-9._~+-]+").matches(it) && it != "." && it != ".." } && !segments.last().endsWith(".git"))
    return GitWriteTarget("origin", name(o, "branch"), host, repo)
}

/** Parse only the four result payloads and bind them to the requested session. */
internal fun parseGitWriteResult(o: JsonObject, sessionId: String): GitWriteResult {
    val received = string(o.text("sessionId"), 256)
    require(received.none { it.code <= 32 || it.code == 127 } && received == sessionId)
    val base = setOf("kind", "sessionId")
    return when (o.text("kind")) {
        "git.branch" -> if (!o.flag("available")) {
            Wire.keys(o, base + setOf("available", "reason")); GitWriteResult.Branch.Unavailable(wireEnum(o.text("reason")))
        } else {
            Wire.keys(o, base + setOf("available", "writeId", "branch", "remoteTip", "ahead", "pushTarget", "pullRequestBases", "hooksSkipped"), setOf("upstream", "suggestedTitle", "head"))
            val id = o.text("writeId"); Wire.decode(id, 16)
            val upstream = o["upstream"]?.let { value -> val u = value.jsonObject; Wire.keys(u, setOf("remote", "branch")); GitWriteUpstream(name(u, "remote"), name(u, "branch")) }
            val bases = strings(o, "pullRequestBases", 16).map(::branch)
            val hooks = strings(o, "hooksSkipped", 3).map {
                require(it in setOf("pre-commit", "commit-msg", "pre-push"))
                wireEnum<GitWriteHook>(it.replace('-', '_'))
            }
            val head = o["head"]?.let { o.text("head").also { value -> require(Regex("[0-9a-f]{40}|[0-9a-f]{64}").matches(value)) } }
            GitWriteResult.Branch.Available(id, name(o, "branch"), upstream, wireEnum(o.text("remoteTip")), count(o, "ahead"), target(o.obj("pushTarget")), bases, o["suggestedTitle"]?.let { title(o.text("suggestedTitle")) }, hooks, head)
        }
        "git.commit" -> if (!o.flag("committed")) {
            Wire.keys(o, base + setOf("committed", "reason")); GitWriteResult.Commit.Refused(wireEnum(o.text("reason")))
        } else {
            Wire.keys(o, base + setOf("committed", "branch", "commit", "files", "skipped", "indexUpdated"))
            val skipped = o.array("skipped"); require(skipped.size <= 1000)
            GitWriteResult.Commit.Committed(name(o, "branch"), sha(o), count(o, "files", 1, 1000).toInt(), skipped.map { s -> Wire.keys(s, setOf("path", "reason")); val path = string(s.text("path"), 4096); require('\u0000' !in path); GitSkippedPath(path, wireEnum(s.text("reason"))) }, o.flag("indexUpdated"))
        }
        "git.push" -> if (!o.flag("pushed")) {
            Wire.keys(o, base + setOf("pushed", "reason")); GitWriteResult.Push.Refused(wireEnum(o.text("reason")))
        } else {
            Wire.keys(o, base + setOf("pushed", "branch", "remoteBranch", "commit", "created", "upstreamSet")); GitWriteResult.Push.Pushed(name(o, "branch"), name(o, "remoteBranch"), sha(o), o.flag("created"), o.flag("upstreamSet"))
        }
        "git.push.status" -> { Wire.keys(o, base + setOf("state")); GitWriteResult.PushStatus(wireEnum(o.text("state"))) }
        "git.pull_request.status" -> {
            val state = wireEnum<GitPullRequestStatus>(o.text("state"))
            val hasIdentity = state == GitPullRequestStatus.FOUND || state == GitPullRequestStatus.DIFFERENT
            Wire.keys(o, base + if (hasIdentity) setOf("state", "pullRequest") else setOf("state"))
            val identity = if (hasIdentity) {
                val pr = o.obj("pullRequest")
                Wire.keys(pr, setOf("number", "url", "base"))
                val url = string(pr.text("url"), 2048); require(url.startsWith("https://") && url.length > 8 && url.none { it.isWhitespace() || Character.isSpaceChar(it) || it.code in 0..31 || it.code in 127..159 || it == '\uFEFF' })
                GitPullRequestIdentity(count(pr, "number", 1), url, branch(pr.text("base")))
            } else null
            GitWriteResult.PullRequestStatus(state, identity)
        }
        "git.pull_request" -> if (!o.flag("available")) {
            Wire.keys(o, base + setOf("available", "reason")); GitWriteResult.PullRequest.Unavailable(wireEnum(o.text("reason")))
        } else {
            Wire.keys(o, base + setOf("available", "created", "number", "url", "base"))
            val url = string(o.text("url"), 2048); require(url.startsWith("https://") && url.length > 8 && url.none { it.isWhitespace() || Character.isSpaceChar(it) || it.code in 0..31 || it.code in 127..159 || it == '\uFEFF' })
            GitWriteResult.PullRequest.Available(o.flag("created"), count(o, "number", 1), url, branch(o.text("base")))
        }
        else -> error("Unknown git write result")
    }
}
