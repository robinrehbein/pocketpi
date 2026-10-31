package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject

data class GitPullRequestIntent(
    val branch: String, val head: String, val writeId: String, val requestId: String,
    val target: GitWriteTarget, val base: String, val title: String, val body: String, val draft: Boolean,
)

internal fun GitPullRequestIntent.json(): JsonObject = Wire.objectOf(
    "branch" to branch, "head" to head, "writeId" to writeId, "requestId" to requestId,
    "target" to Wire.objectOf("remote" to target.remote, "branch" to target.branch, "host" to target.host, "repo" to target.repo),
    "base" to base, "title" to title, "body" to body, "draft" to draft,
)
internal fun parseGitPullRequestIntent(value: JsonObject): GitPullRequestIntent {
    Wire.keys(value, setOf("branch", "head", "writeId", "requestId", "target", "base", "title", "body", "draft"))
    val branch = value.text("branch"); val head = value.text("head"); val writeId = value.text("writeId"); val requestId = value.text("requestId")
    val base = value.text("base"); val title = value.text("title"); val body = value.text("body"); val draft = value.flag("draft")
    encodeGitWriteCommand(GitWriteCommand.PullRequest(requestId, "stored", writeId, base, title, body, draft))
    val push = parseGitPushIntent(Wire.objectOf("branch" to branch, "head" to head, "writeId" to writeId, "requestId" to requestId, "target" to value.obj("target")))
    return GitPullRequestIntent(branch, head, writeId, requestId, push.target, base, title, body, draft)
}
