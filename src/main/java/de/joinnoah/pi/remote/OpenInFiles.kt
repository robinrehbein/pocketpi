package de.joinnoah.pi.remote

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A file to show in Files: [path] relative to the session folder, at [lines] when known. */
internal data class FileTarget(val path: String, val lines: LineSelection? = null)

/** What a file named in the chat offers: opening it in Files, or only copying a path outside the project. */
internal sealed interface FileOpenAction {
    data class Open(val target: FileTarget) : FileOpenAction

    data class CopyPath(val path: String) : FileOpenAction
}

/**
 * What [projectRelativePath] knows about the session folder. The host sends no folder path
 * (`projects.list` has none), so [roots] are only those the host may send as `cwd`, plus the
 * folders an absolute and a relative tool path of the same file give away. [projectName] is the
 * project's name, usually its folder's, and [toolPaths] are the relative paths the chat's tools
 * used, which are relative to the session folder.
 */
internal data class SessionFolderHints(
    val roots: List<String> = emptyList(),
    val projectName: String? = null,
    val toolPaths: Set<String> = emptySet(),
)

/** Whether [path] names a file or folder below the session folder the way `session.files.*` takes it. */
internal fun isSessionPath(path: String): Boolean =
    path.isNotEmpty() && '\u0000' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

/** The segments of [path] with `.` and empty ones dropped; null when `..` climbs out of it. */
private fun normalSegments(path: String): List<String>? {
    val segments = ArrayList<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
    }
    return segments
}

private fun absolutePath(path: String) = path.startsWith("/") || path.startsWith("~")

/**
 * [path] relative to the session folder, or null when it lies outside or cannot be placed. A
 * relative path is one already, unless it climbs out with `..`. An absolute path loses the first
 * of [SessionFolderHints.roots] it lies in; failing that it is the longest relative tool path it
 * ends with, or what follows the first segment named like the project.
 */
internal fun projectRelativePath(path: String, hints: SessionFolderHints = SessionFolderHints()): String? {
    val trimmed = path.trim()
    if (trimmed.isEmpty() || '\u0000' in trimmed) return null
    if (!absolutePath(trimmed)) {
        val segments = normalSegments(trimmed) ?: return null
        return segments.joinToString("/").takeIf(String::isNotEmpty)
    }
    val segments = normalSegments(trimmed.removePrefix("~")) ?: return null
    if (segments.isEmpty()) return null
    val home = trimmed.startsWith("~")
    for (root in hints.roots) {
        if (root.startsWith("~") != home) continue
        val rootSegments = normalSegments(root.removePrefix("~")) ?: continue
        if (rootSegments.isNotEmpty() && segments.size > rootSegments.size && segments.subList(0, rootSegments.size) == rootSegments)
            return segments.drop(rootSegments.size).joinToString("/")
    }
    val joined = segments.joinToString("/")
    hints.toolPaths
        .mapNotNull { projectRelativePath(it) }
        .filter { joined.endsWith("/$it") }
        .maxByOrNull { it.length }
        ?.let { return it }
    val name = hints.projectName?.takeIf { it.isNotBlank() && '/' !in it }
    if (name != null) {
        val at = segments.indexOf(name)
        if (at >= 0 && at < segments.lastIndex) return segments.drop(at + 1).joinToString("/")
    }
    return null
}

/**
 * The hints for [projectRelativePath] from the chat: a `cwd` on the session or project, should the
 * host send one, the project's name, and the paths [toolPaths] its tools used. An absolute tool
 * path that ends with a relative one names the folder above it.
 */
internal fun sessionFolderHints(session: JsonObject?, project: JsonObject?, toolPaths: Collection<String>): SessionFolderHints {
    fun JsonObject.cwd() = (get("cwd") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(::absolutePath)
    val relative = toolPaths.filterNot(::absolutePath).mapNotNull { projectRelativePath(it) }.toSet()
    val derived =
        toolPaths.filter(::absolutePath).mapNotNull { absolute ->
            val joined = absolute.trimEnd('/')
            relative.filter { joined.endsWith("/$it") }.maxByOrNull { it.length }?.let { joined.removeSuffix("/$it") }
        }
    return SessionFolderHints(
        roots = (listOfNotNull(session?.cwd(), project?.cwd()) + derived).distinct(),
        projectName = project?.optionalText("name"),
        toolPaths = relative,
    )
}

/**
 * The file a read, edit or write call worked on, as the tool named it, with the lines it touched:
 * a read's `offset` and `limit`, an edit's changed lines when the host sent its patch (else the
 * first line it says changed). A write replaced the whole file, so it opens at the top.
 */
internal fun toolFileTarget(item: ConversationItem.Activity): FileTarget? {
    if (item.name != "read" && item.name != "edit" && item.name != "write") return null
    val path = toolPath(item) ?: return null
    val lines =
        when (item.name) {
            "read" -> {
                val args = item.arguments?.let(::parsedToolArguments)
                val offset = (args?.get("offset") as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
                val limit = (args?.get("limit") as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
                offset?.takeIf { it >= 1 }?.let { first ->
                    if (limit != null && limit > 1) LineSelection(first, first + limit - 1) else LineSelection(first)
                }
            }
            "edit" ->
                toolDiff(item)?.takeIf { it.fromDetails }?.let { editedLines(it.lines) }
                    ?: item.details?.firstChangedLine?.takeIf { it >= 1 }?.let { LineSelection(it) }
            else -> null
        }
    return FileTarget(path, lines)
}

/** The lines of the new file an edit's patch changed, from the first to the last; null for none. */
internal fun editedLines(lines: List<DiffLine>): LineSelection? {
    var first: Int? = null
    var last: Int? = null
    for ((index, line) in lines.withIndex()) {
        if (line.kind != DiffKind.ADDED && line.kind != DiffKind.REMOVED) continue
        val at = line.newLine ?: newLineNear(lines, index) ?: continue
        if (first == null) first = at
        last = at
    }
    return first?.let { LineSelection(it, last.takeIf { end -> end != it }) }
}

/**
 * The line of the new file nearest to [index] of a diff: [index]'s own, else the next one in its
 * hunk, else the one before it. A removed line has none of its own.
 */
internal fun newLineNear(lines: List<DiffLine>, index: Int): Int? {
    lines[index].newLine?.let { return it }
    for (next in index + 1 until lines.size) {
        if (lines[next].kind == DiffKind.HUNK) break
        lines[next].newLine?.let { return it }
    }
    for (previous in index - 1 downTo 0) {
        if (lines[previous].kind == DiffKind.HUNK) break
        lines[previous].newLine?.let { return it.coerceAtLeast(1) }
    }
    return null
}

// ---- path:line in text ------------------------------------------------------------------------

/** A `path:line`, `path:line-line` or `path:line:column` reference in the agent's text. */
internal data class FileReference(val path: String, val lines: LineSelection)

/** Extensions of files the agent names in prose, so `foo.kt:12` links and `example.com:443` does not. */
private val SOURCE_EXTENSIONS = setOf(
    "kt", "kts", "java", "gradle", "ts", "tsx", "mts", "cts", "js", "jsx", "mjs", "cjs", "json", "jsonc", "json5",
    "md", "markdown", "mdx", "py", "pyi", "rb", "go", "rs", "swift", "c", "h", "cc", "cpp", "cxx", "hh", "hpp", "m", "mm",
    "cs", "fs", "php", "sh", "bash", "zsh", "fish", "yml", "yaml", "toml", "xml", "html", "htm", "css", "scss", "sass",
    "less", "sql", "properties", "txt", "vue", "svelte", "astro", "lua", "dart", "ex", "exs", "erl", "scala", "clj",
    "hs", "ml", "r", "pl", "proto", "graphql", "gql", "ini", "cfg", "conf", "csv", "tf", "nix", "zig", "elm", "pro",
)

/** A path (no spaces, no scheme) and `:line`, optionally `-line` or `:column`, nothing after it. */
private val FILE_REFERENCE = Regex("""^(~?/?(?:[\w.@+\-]+/)*[\w.@+\-]*[\w@+\-])(?::(\d{1,7})(?:[-–](\d{1,7})|:\d{1,5})?)$""")

/**
 * [text] as a file reference: a path with a line, whose extension is a known source one or which
 * [known] (a path the chat's tools used) accepts. Anything with a scheme (`https://`) never is.
 */
internal fun fileReference(text: String, known: (String) -> Boolean = { false }): FileReference? {
    if (text.length > 1024 || "://" in text) return null
    val match = FILE_REFERENCE.matchEntire(text) ?: return null
    val path = match.groupValues[1]
    val name = path.substringAfterLast('/')
    if (name.isEmpty() || name.all { it == '.' }) return null
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension !in SOURCE_EXTENSIONS && !known(path)) return null
    val first = match.groupValues[2].toIntOrNull()?.takeIf { it >= 1 } ?: return null
    val last = match.groupValues[3].toIntOrNull()?.takeIf { it > first }
    return FileReference(path, LineSelection(first, last))
}

// ---- The chat's opener ------------------------------------------------------------------------

/**
 * Opens a file the chat names in Files. [canOpen] is false while Files cannot browse the session;
 * then, and for a path outside the project, the chat offers to copy the path instead. One opener
 * serves a chat; [hints] and [canOpen] follow it, so a new tool call recomposes only the icons
 * that read them, never every message of the chat.
 */
@Stable
internal class FileOpener(
    hints: SessionFolderHints,
    canOpen: Boolean,
    /** Shows [FileTarget] in Files: the pane, at its folder, with the lines picked and in view. */
    private val open: (FileTarget) -> Unit,
    private val copy: (String) -> Unit,
) {
    var hints by mutableStateOf(hints)
    var canOpen by mutableStateOf(canOpen)

    /** What [path] (as the chat wrote it) offers, at [lines]. */
    fun action(path: String, lines: LineSelection? = null): FileOpenAction {
        val relative = projectRelativePath(path, hints)?.takeIf { canOpen }
        return if (relative != null) FileOpenAction.Open(FileTarget(relative, lines)) else FileOpenAction.CopyPath(path)
    }

    fun perform(action: FileOpenAction) =
        when (action) {
            is FileOpenAction.Open -> open(action.target)
            is FileOpenAction.CopyPath -> copy(action.path)
        }

    /**
     * A path the chat's tools used, so `Makefile:12` links even without a known extension. Text
     * that links once stays linked: Markdown asks while it parses and keeps what it parsed.
     */
    fun knows(path: String): Boolean =
        Snapshot.withoutReadObservation {
            val hints = hints
            projectRelativePath(path, hints)?.let { it in hints.toolPaths } == true
        }
}

/** The chat's [FileOpener]; null outside a chat, where files named in text stay plain. */
internal val LocalFileOpener = staticCompositionLocalOf<FileOpener?> { null }

/**
 * A trailing icon that opens [path] in Files at [lines], then runs [onDone], or copies a path
 * outside the project. Nothing outside a chat ([LocalFileOpener]).
 */
@Composable
internal fun OpenInFilesButton(path: String, lines: LineSelection?, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    val opener = LocalFileOpener.current ?: return
    val action = opener.action(path, lines)
    val open = action is FileOpenAction.Open
    IconButton(
        onClick = {
            opener.perform(action)
            if (open) onDone()
        },
        modifier = modifier.size(40.dp).testTag(if (open) "openInFiles" else "copyOutsidePath"),
    ) {
        Icon(
            if (open) Icons.Default.FolderOpen else Icons.Default.ContentCopy,
            stringResource(if (open) R.string.remote_open_files_open else R.string.remote_open_files_copy_outside),
            Modifier.size(20.dp),
        )
    }
}
