package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The host lists and reads the session folder read-only: `session.files.list` and `.read`
 * (protocol README, "Project files"). Advertised only through the `projects.list` route.
 */
internal const val FILES_CAPABILITY = "session.files.v1"

private const val MAX_FILES_LIST_ENTRIES = 500
private const val MAX_FILES_CONTENT_JSON_BYTES = 192 * 1024
private const val MAX_FILES_PATH_BYTES = 4096
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

/** Quoted file text above this many UTF-8 bytes becomes a path-only reference. */
internal const val MAX_FILE_QUOTE_BYTES = 32 * 1024

enum class FileEntryType(val wire: String) {
    DIR("dir"),
    FILE("file"),
    SYMLINK("symlink"),
    SUBMODULE("submodule"),
}

/** Why the host lists nothing for the session folder. A result, not an error. */
enum class FilesUnavailable(val wire: String) {
    GIT_UNAVAILABLE("git_unavailable"),
    NOT_A_REPOSITORY("not_a_repository"),
}

/** One listed name; only files carry a [size] in bytes. */
data class FileEntry(val name: String, val type: FileEntryType, val size: Long? = null)

/** One folder: every page loaded so far, or why there is none. */
data class FileListing(
    val path: String,
    val entries: List<FileEntry> = emptyList(),
    val truncated: Boolean = false,
    /** Present while more entries follow; sent back as `after`. */
    val nextAfter: String? = null,
    val unavailable: FilesUnavailable? = null,
)

/** One `files.read` page. */
data class FileChunk(
    val path: String,
    val version: String,
    val size: Long,
    val offset: Long,
    val content: String,
    val binary: Boolean,
    val nextOffset: Long? = null,
    val tooLarge: Boolean = false,
)

/** Why the file browser shows no data. */
enum class FilesFailure {
    UNSUPPORTED,
    OFFLINE,
    NOT_FOUND,
    INVALID_PATH,
    FORBIDDEN,
    BUSY,
    FAILED,
}

/** Lines picked in the file view, 1-based: [anchor] alone until a second tap sets [end]. */
data class LineSelection(val anchor: Int, val end: Int? = null) {
    val first: Int get() = minOf(anchor, end ?: anchor)
    val last: Int get() = maxOf(anchor, end ?: anchor)
}

/** What a tap on a line number does. */
internal enum class LineTap { START, END, CLEAR }

/**
 * The first tap starts a range and the second ends it; a tap after that starts anew. Tapping the
 * start again before choosing an end clears the selection.
 */
internal fun LineSelection?.tapAction(line: Int): LineTap =
    when {
        this == null || end != null -> LineTap.START
        line == anchor -> LineTap.CLEAR
        else -> LineTap.END
    }

internal fun LineSelection?.tap(line: Int): LineSelection? =
    when (tapAction(line)) {
        LineTap.START -> LineSelection(line)
        LineTap.END -> checkNotNull(this).copy(end = line)
        LineTap.CLEAR -> null
    }

/** The open file: its pages joined, or why it shows no text. */
data class OpenFile(
    val path: String,
    val loading: Boolean = true,
    val failure: FilesFailure? = null,
    val version: String? = null,
    val size: Long = 0,
    val content: String = "",
    val binary: Boolean = false,
    val tooLarge: Boolean = false,
    val nextOffset: Long? = null,
    val moreLoading: Boolean = false,
    val moreFailure: FilesFailure? = null,
    /** The file changed while its next page loaded, so it was read again from the start. */
    val reopened: Boolean = false,
    val selection: LineSelection? = null,
)

/**
 * The file browser of the selected session. Like [ChangesState] it lives in [RemoteState], so
 * the phone overlay and the tablet inspector show the same state across a resize.
 */
data class FilesState(
    val sessionId: String,
    /** The folder shown, relative to the session folder; `""` is the folder itself. */
    val path: String = "",
    /** Listings of the folders above [path], outermost first, so Back needs no request. */
    val parents: List<FileListing> = emptyList(),
    val loading: Boolean = true,
    val listing: FileListing? = null,
    val failure: FilesFailure? = null,
    val moreLoading: Boolean = false,
    val moreFailure: FilesFailure? = null,
    val file: OpenFile? = null,
)

internal fun canBrowseFiles(state: RemoteState): Boolean =
    FILES_CAPABILITY in state.capabilities && FILES_CAPABILITY !in state.unavailableCapabilities &&
        state.selection.sessionId != null

/** The folder above [path]; `""` for a top-level entry. */
internal fun parentPath(path: String): String = path.substringBeforeLast('/', "")

internal fun childPath(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder/$name"

/** The lines of [content]; a final line end does not start another line, and `\r\n` counts as one. */
internal fun fileLines(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    val lines = content.split('\n').map { it.removeSuffix("\r") }
    return if (content.endsWith('\n')) lines.dropLast(1) else lines
}

// ---- Wire ---------------------------------------------------------------------------------

private fun JsonObject.onlyKeys(required: Set<String>, optional: Set<String> = emptySet()) =
    require(keys.containsAll(required) && (keys - required - optional).isEmpty())

private fun JsonObject.count(key: String, max: Long = MAX_SAFE_INTEGER): Long =
    long(key).also { require(it in 0..max) }

private fun validPath(value: String, empty: Boolean = false): Boolean =
    (empty || value.isNotEmpty()) && '\u0000' !in value && value.encodeToByteArray().size <= MAX_FILES_PATH_BYTES

private fun validName(value: String): Boolean =
    validPath(value) && '/' !in value && value != "." && value != ".."

private fun fileEntry(value: JsonObject): FileEntry {
    value.onlyKeys(setOf("name", "type"), setOf("size"))
    val name = value.text("name").also { require(validName(it)) }
    val type = requireNotNull(FileEntryType.entries.firstOrNull { it.wire == value.text("type") })
    val size = if (value.containsKey("size")) value.count("size").also { require(type == FileEntryType.FILE) } else null
    return FileEntry(name, type, size)
}

/** Validates one `files.list` page against the request that produced it. */
internal fun parseFilesList(data: JsonObject, sessionId: String, path: String): FileListing {
    require(data.text("kind") == "files.list")
    require(data.text("sessionId") == sessionId && data.text("path") == path)
    if (!data.flag("available")) {
        data.onlyKeys(setOf("kind", "sessionId", "path", "available", "reason"))
        val reason = requireNotNull(FilesUnavailable.entries.firstOrNull { it.wire == data.text("reason") })
        return FileListing(path, unavailable = reason)
    }
    data.onlyKeys(setOf("kind", "sessionId", "path", "available", "entries", "truncated"), setOf("nextAfter"))
    val entries = data.array("entries").map(::fileEntry)
    require(entries.size <= MAX_FILES_LIST_ENTRIES)
    val nextAfter = if (data.containsKey("nextAfter")) data.text("nextAfter").also { require(validName(it)) } else null
    return FileListing(path, entries, data.flag("truncated"), nextAfter)
}

/**
 * Validates one `files.read` page against the request that produced it: the same path and
 * offset, and on a later page the version of the first.
 */
internal fun parseFilesRead(data: JsonObject, sessionId: String, path: String, offset: Long, version: String?): FileChunk {
    require(data.text("kind") == "files.read")
    data.onlyKeys(
        setOf("kind", "sessionId", "path", "version", "size", "offset", "content", "binary"),
        setOf("nextOffset", "omitted"),
    )
    require(data.text("sessionId") == sessionId && data.text("path") == path)
    val chunkVersion = data.text("version")
    require(chunkVersion.length <= 22 && runCatching { Wire.decode(chunkVersion, 16) }.isSuccess)
    require(version == null || chunkVersion == version)
    val size = data.count("size")
    require(data.count("offset", size) == offset)
    val content = data.text("content")
    require(Wire.json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(content)).encodeToByteArray().size <=
        MAX_FILES_CONTENT_JSON_BYTES)
    val binary = data.flag("binary")
    val nextOffset = if (data.containsKey("nextOffset")) data.count("nextOffset", size).also { require(it > offset) } else null
    val tooLarge =
        if (data.containsKey("omitted")) {
            require(data.text("omitted") == "too_large")
            require(content.isEmpty() && !binary && nextOffset == null)
            true
        } else false
    require(!binary || (content.isEmpty() && nextOffset == null))
    return FileChunk(path, chunkVersion, size, offset, content, binary, nextOffset, tooLarge)
}

// ---- Quote ---------------------------------------------------------------------------------

/** A Markdown fence longer than any backtick run in [text], and at least three long. */
internal fun codeFence(text: String): String {
    var longest = 0
    var run = 0
    for (char in text) {
        run = if (char == '`') run + 1 else 0
        if (run > longest) longest = run
    }
    return "`".repeat(maxOf(3, longest + 1))
}

/**
 * The composer text that shows pi file content: [intro] (naming the path and lines), then [text]
 * in a fenced block tagged with [language]. Above [MAX_FILE_QUOTE_BYTES] it is [reference] alone,
 * which names the path and lines without the content.
 */
internal fun fileQuotePrompt(intro: String, reference: String, text: String, language: SyntaxLanguage?): String {
    if (text.encodeToByteArray().size > MAX_FILE_QUOTE_BYTES) return reference
    val fence = codeFence(text)
    return "$intro\n\n$fence${language?.tag.orEmpty()}\n$text\n$fence"
}
