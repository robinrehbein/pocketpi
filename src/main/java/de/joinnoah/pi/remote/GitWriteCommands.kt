package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Values in these commands must originate from the relevant host read before use. */
internal sealed interface GitWriteCommand {
    val requestId: String
    val sessionId: String

    data class Branch(override val requestId: String, override val sessionId: String, val forPush: Boolean = false) : GitWriteCommand
    data class Commit(override val requestId: String, override val sessionId: String, val snapshotId: String, val message: String, val paths: List<String>? = null) : GitWriteCommand
    data class Push(override val requestId: String, override val sessionId: String, val writeId: String) : GitWriteCommand
    data class PushStatus(override val requestId: String, override val sessionId: String, val writeId: String) : GitWriteCommand
    data class PullRequestStatus(override val requestId: String, override val sessionId: String, val writeId: String) : GitWriteCommand
    data class PullRequest(override val requestId: String, override val sessionId: String, val writeId: String, val base: String, val title: String, val body: String, val draft: Boolean) : GitWriteCommand
}

// Match JavaScript TextEncoder, including replacement of a lone UTF-16 surrogate.
private fun byteCount(text: String): Long {
    var total = 0L
    var i = 0
    while (i < text.length) {
        val c = text[i++]
        total += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() && i < text.length && text[i].isLowSurrogate() -> { i++; 4 }
            else -> 3
        }
    }
    return total
}

private fun validText(text: String, limit: Int, empty: Boolean = false) {
    require((empty || text.isNotEmpty()) && byteCount(text) <= limit)
}

private fun wellFormed(text: String) {
    var i = 0
    while (i < text.length) {
        val c = text[i++]
        if (c.isHighSurrogate()) {
            require(i < text.length && text[i].isLowSurrogate())
            i++
        } else require(!c.isLowSurrogate())
    }
}

private fun validateGitWriteOpaqueId(value: String) {
    validText(value, 256)
    require(value.none { it.code <= 32 || it.code == 127 })
}

private fun token(value: String) {
    require(value.length == 22)
    Wire.decode(value, 16)
}

// ECMAScript \s, used by the host for the commit subject's blank check.
private fun subjectWhitespace(c: Char): Boolean = c.code in 9..13 || c == ' ' || c == '\u00a0' ||
    c == '\u1680' || c.code in 0x2000..0x200a || c == '\u2028' || c == '\u2029' ||
    c == '\u202f' || c == '\u205f' || c == '\u3000' || c == '\uFEFF'

private fun commitMessage(value: String) {
    validText(value, 16384)
    require('\u0000' !in value)
    wellFormed(value)
    val subject = value.takeWhile { it != '\r' && it != '\n' }
    require(byteCount(subject) <= 256 && subject.any { !subjectWhitespace(it) })
}

private fun pullRequestTitle(value: String) {
    validText(value, 256)
    require(value.none { it.code in 0..31 || it.code in 127..159 })
    wellFormed(value)
}

internal fun encodeGitWriteCommand(command: GitWriteCommand): JsonObject {
    validateGitWriteOpaqueId(command.requestId)
    validateGitWriteOpaqueId(command.sessionId)
    val common = mutableListOf<Pair<String, Any?>>("requestId" to command.requestId, "sessionId" to command.sessionId)
    when (command) {
        is GitWriteCommand.Branch -> { common.add("type" to "session.git.branch"); if (command.forPush) common.add("forPush" to true) }
        is GitWriteCommand.Commit -> {
            token(command.snapshotId)
            commitMessage(command.message)
            common.add("type" to "session.git.commit")
            common.add("snapshotId" to command.snapshotId)
            common.add("message" to command.message)
            command.paths?.let { paths ->
                require(paths.size in 1..1000 && paths.toSet().size == paths.size)
                paths.forEach { path -> validText(path, 4096); require('\u0000' !in path) }
                common.add("paths" to JsonArray(paths.map(::JsonPrimitive)))
            }
        }
        is GitWriteCommand.Push -> {
            token(command.writeId)
            common.add("type" to "session.git.push")
            common.add("writeId" to command.writeId)
        }
        is GitWriteCommand.PushStatus -> {
            token(command.writeId)
            common.add("type" to "session.git.push.status")
            common.add("writeId" to command.writeId)
        }
        is GitWriteCommand.PullRequestStatus -> {
            token(command.writeId)
            common.add("type" to "session.git.pull_request.status")
            common.add("writeId" to command.writeId)
        }
        is GitWriteCommand.PullRequest -> {
            token(command.writeId)
            validText(command.base, 256)
            require(Regex("[A-Za-z0-9._][A-Za-z0-9._/-]*").matches(command.base))
            pullRequestTitle(command.title)
            validText(command.body, 32768, empty = true)
            require('\u0000' !in command.body)
            wellFormed(command.body)
            common.add("type" to "session.git.pull_request")
            common.add("writeId" to command.writeId)
            common.add("base" to command.base)
            common.add("title" to command.title)
            common.add("body" to command.body)
            common.add("draft" to command.draft)
        }
    }
    return Wire.objectOf(*common.toTypedArray())
}
