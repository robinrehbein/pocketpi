package de.joinnoah.pi.remote

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The host lists and reads the session folder read-only: `session.files.list` and `.read`
 * (protocol README, "Project files"). Advertised only through the `projects.list` route.
 */
internal const val FILES_CAPABILITY = "session.files.v1"

/**
 * The host replaces an existing text file with `session.files.write`, checked against the version
 * the app last read or wrote. Advertised only through the `capabilities.v2` route, and only when
 * the host runs with `PI_REMOTE_FILE_WRITES=1`.
 */
internal const val FILES_WRITE_CAPABILITY = "session.files.write.v1"

private const val MAX_FILES_LIST_ENTRIES = 500
internal const val MAX_FILES_CONTENT_JSON_BYTES = 192 * 1024
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
    /** A save found the file changed since it was read (`conflict`). */
    CONFLICT,
    /** The edited text is over [MAX_FILES_CONTENT_JSON_BYTES], so a save would not fit one frame. */
    TOO_LARGE,
    FAILED,
}

/** One `files.written` result: the saved file's new [version] and [size] in bytes. */
data class FileWritten(val path: String, val version: String, val size: Long)

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
 * The open file while it is edited. [original] is the text of [baseVersion], the last version
 * read or saved; [text] is the editor's text as the UI last handed it over, and [latestText]
 * what the field shows right now.
 */
data class FileEditor(
    val path: String,
    val baseVersion: String,
    val original: String,
    val text: String = original,
    val saving: Boolean = false,
    /** Why the last save failed; the edit stays. */
    val saveFailure: FilesFailure? = null,
    /** The last save found a newer file on the host; a later save or other failure clears it. */
    val conflict: Boolean = false,
    /** Counts successful saves, so the UI can confirm each one. */
    val savedAt: Long = 0,
    /** Where the cursor starts: the start of the first line picked in the file view, else 0. */
    val cursor: Int = 0,
    /** The last save the host never answered; a retry of the same text resends its request ID. */
    val unconfirmed: PendingWrite? = null,
    /**
     * Earlier unanswered saves of other text, oldest first, that a newer save replaced as
     * [unconfirmed]. When the newer save meets a conflict and the host holds one of these, the
     * host's file is this editor's own write: it becomes the new base and the newer text is sent
     * again once.
     */
    val superseded: List<PendingWrite> = emptyList(),
    /** What waits for the user to discard the unsaved edit; the editor asks first. */
    val leaveRequest: EditorLeave? = null,
    val buffer: EditorBuffer = EditorBuffer(),
) {
    /** The field's text when the UI has one, else [text]. Not observable; read it on demand. */
    val latestText: String get() = buffer.text() ?: text

    val changed: Boolean get() = latestText != original
}

/**
 * A `session.files.write` whose reply was lost. The host answers a repeated [requestId] from its
 * ledger, so resending it cannot write twice or conflict with itself.
 */
data class PendingWrite(val requestId: String, val baseVersion: String, val content: String)

/** An action that would drop an unsaved edit and so waits for the discard prompt. */
enum class EditorLeave {
    /** Opening the changes pane, which takes the files pane's place. */
    CHANGES,
}

/** Whether [content] fits a `session.files.write` frame: at most [MAX_FILES_CONTENT_JSON_BYTES] as a JSON string. */
internal fun filesContentFits(content: String): Boolean =
    content.length <= MAX_FILES_CONTENT_JSON_BYTES &&
        Wire.json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(content)).encodeToByteArray().size <=
        MAX_FILES_CONTENT_JSON_BYTES

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
    val previews: Map<String, FileTilePreview> = emptyMap(),
    val peek: FilesPeek? = null,
    /** The open file in the editor; null while it is shown read-only. */
    val editor: FileEditor? = null,
)

internal fun canBrowseFiles(state: RemoteState): Boolean =
    FILES_CAPABILITY in state.capabilities && FILES_CAPABILITY !in state.unavailableCapabilities &&
        state.selection.sessionId != null

internal fun canWriteFiles(state: RemoteState): Boolean =
    canBrowseFiles(state) && FILES_WRITE_CAPABILITY in state.capabilities &&
        FILES_WRITE_CAPABILITY !in state.unavailableCapabilities

/**
 * Whether the open file can be edited: the host saves files, and the file is text read whole in
 * one page.
 */
internal fun canEditFile(state: RemoteState): Boolean {
    val file = state.files?.file ?: return false
    return canWriteFiles(state) && !file.loading && file.failure == null && file.version != null &&
        !file.binary && !file.tooLarge && file.nextOffset == null && !writeDeniedPath(file.path)
}

/** Folders whose files a tool runs on its own; the host refuses to write anywhere below them. */
private val WRITE_DENIED_SEGMENTS = setOf(".pi", ".claude", ".cursor", ".husky", ".githooks", ".lefthook", ".vscode", ".idea")

/** Files a tool runs or loads on its own, wherever they are. */
private val WRITE_DENIED_NAMES =
    setOf(".envrc", ".mcp.json", "lefthook.yml", "lefthook.yaml", ".lefthook.yml", ".lefthook.yaml", ".pre-commit-config.yaml")

/**
 * Whether the host refuses to save [path] as auto-executed configuration: any segment is one of
 * [WRITE_DENIED_SEGMENTS], or the file name is one of [WRITE_DENIED_NAMES]. Segments compare
 * NFC-normalised and lower-cased, as the host does. The host also refuses paths the app cannot
 * judge (such as git's `core.hooksPath`); this list only hides Edit.
 */
internal fun writeDeniedPath(path: String): Boolean {
    val segments = path.split('/').map { Normalizer.normalize(it, Normalizer.Form.NFC).lowercase(Locale.ROOT) }
    return segments.any { it in WRITE_DENIED_SEGMENTS } || segments.lastOrNull() in WRITE_DENIED_NAMES
}

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

/** Validates a `files.written` result against the write that produced it. */
internal fun parseFilesWritten(data: JsonObject, sessionId: String, path: String): FileWritten {
    require(data.text("kind") == "files.written")
    data.onlyKeys(setOf("kind", "sessionId", "path", "version", "size"))
    require(data.text("sessionId") == sessionId && data.text("path") == path)
    val version = data.text("version")
    require(version.length <= 22 && runCatching { Wire.decode(version, 16) }.isSuccess)
    return FileWritten(path, version, data.count("size"))
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

/** What "Send to agent" puts into the composer for an edit. */
sealed interface EditorPrompt {
    data class Ready(val text: String) : EditorPrompt

    /** The diff is over [MAX_FILE_QUOTE_BYTES] or too long to compute; nothing goes to the composer. */
    data object TooLarge : EditorPrompt
}

/** The English intro of [editorPrompt], from the spec; the UI may pass a translated one. */
internal fun editorPromptIntro(path: String): String =
    "Here is a suggested change to `$path`. Apply it, adapting it if needed, and say what you changed."

/**
 * [intro] and the diff of [original] to [edited] in a `diff` fence, or null when nothing changed.
 * A diff above [MAX_FILE_QUOTE_BYTES], or one too long to compute ([MAX_DIFF_EDITS]), is
 * [EditorPrompt.TooLarge].
 */
internal fun editorPrompt(path: String, original: String, edited: String, intro: String = editorPromptIntro(path)): EditorPrompt? {
    val diff =
        when (val result = boundedUnifiedDiff(path, original, edited)) {
            LineDiff.Same -> return null
            LineDiff.TooLarge -> return EditorPrompt.TooLarge
            is LineDiff.Text -> result.text
        }
    if (diff.encodeToByteArray().size > MAX_FILE_QUOTE_BYTES) return EditorPrompt.TooLarge
    val body = diff.removeSuffix("\n")
    val fence = codeFence(body)
    return EditorPrompt.Ready("$intro\n\n${fence}diff\n$body\n$fence")
}
