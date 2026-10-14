package de.joinnoah.pi.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How many earlier unanswered saves an editor remembers ([FileEditor.superseded]). */
private const val MAX_SUPERSEDED_WRITES = 4

/** How many folder listings [ProjectFilesLoader] keeps for an instant reopen. */
private const val MAX_REMEMBERED_LISTINGS = 16

/** The `session.files.*` commands. Implementations throw [RemoteRequestException] on errors. */
internal interface FileSource {
    suspend fun filesList(sessionId: String, path: String, after: String?): FileListing

    suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?): FileChunk

    /** Sends [content] under [requestId]; a resent ID gets the host's first answer again. */
    suspend fun filesWrite(sessionId: String, path: String, baseVersion: String, content: String, requestId: String): FileWritten =
        throw RemoteRequestException.unsupported()
}

/**
 * Loads the file browser into [RemoteState.files]. Folders and files load a page at a time; a
 * later file page that comes back `not_found` (the file changed, so its version no longer
 * matches) reads the file once more from the start. A response for a folder or file that is no
 * longer shown is dropped, and so is a save reply once its editor is gone. The last listing of
 * each folder stays in memory, so a connected browser opens a folder on it at once and lists it
 * again in place.
 */
internal class ProjectFilesLoader(
    private val scope: CoroutineScope,
    private val source: FileSource,
    private val current: () -> RemoteState,
    private val update: ((RemoteState) -> RemoteState) -> Unit,
) {
    private var listJob: Job? = null
    private var fileJob: Job? = null
    private var listVersion = 0L
    private var fileVersion = 0L
    private var previewVersion = 0L
    private var peekVersion = 0L
    private val previewQueue = ArrayDeque<String>()
    private val previewJobs = mutableMapOf<String, Job>()
    private var drainingPreviews = false
    private var peekJob: Job? = null
    private var editorVersion = 0L
    /** By Mac, session and folder. */
    private val remembered = NavigationMemoryCache<Triple<String?, String, String>, FileListing>(MAX_REMEMBERED_LISTINGS)

    /** Drops every remembered listing: the Mac is gone, or its navigation starts over. */
    fun forget() = remembered.clear()

    private fun key(sessionId: String, path: String) = Triple(current().selection.routeId, sessionId, path)

    /** Keeps the listing [sessionId]'s browser shows now for the folder's next opening. */
    private fun remember(sessionId: String) {
        val files = files()?.takeIf { it.sessionId == sessionId } ?: return
        val listing = files.listing?.takeIf { it.unavailable == null && files.failure == null } ?: return
        remembered[key(sessionId, files.path)] = listing
    }

    private fun files(): FilesState? =
        current().files?.takeIf { it.sessionId == current().selection.sessionId }

    private fun write(sessionId: String, block: (FilesState) -> FilesState) =
        update { state ->
            val files = state.files
            if (files == null || files.sessionId != sessionId || state.selection.sessionId != sessionId) state
            else state.copy(files = block(files))
        }

    private fun writeFile(sessionId: String, path: String, block: (OpenFile) -> OpenFile) =
        write(sessionId) { files -> files.file?.takeIf { it.path == path }?.let { files.copy(file = block(it)) } ?: files }

    private fun failure(e: Exception): FilesFailure =
        if (e.isUnsupported) FilesFailure.UNSUPPORTED
        else when (e.hostCode) {
            "not_found" -> FilesFailure.NOT_FOUND
            "invalid_path" -> FilesFailure.INVALID_PATH
            "forbidden" -> FilesFailure.FORBIDDEN
            "busy" -> FilesFailure.BUSY
            "conflict" -> FilesFailure.CONFLICT
            "offline" -> FilesFailure.OFFLINE
            else -> if (!current().connected) FilesFailure.OFFLINE else FilesFailure.FAILED
        }

    private fun stopList() {
        listVersion++
        listJob?.cancel()
        listJob = null
    }

    private fun stopFile() {
        fileVersion++
        fileJob?.cancel()
        fileJob = null
    }

    private fun stopPreviews() {
        previewVersion++
        previewJobs.values.forEach { it.cancel() }
        previewJobs.clear()
        previewQueue.clear()
        stopPeek()
    }

    /** Drops the editor's identity, so a save still on its way changes nothing. */
    private fun stopEditor() {
        editorVersion++
    }

    private fun stopPeek() {
        peekVersion++
        peekJob?.cancel()
        peekJob = null
    }

    private fun cache(files: FilesState, path: String, preview: FileTilePreview): FilesState {
        val kept = LinkedHashMap(files.previews)
        kept.remove(path)
        kept[path] = preview
        while (kept.size > MAX_CACHED_FILE_PREVIEWS) {
            val pinned = files.peek?.takeIf { it.type == FileEntryType.FILE }?.path
            val oldest = kept.keys.firstOrNull { it != pinned } ?: break
            kept.remove(oldest)
        }
        return files.copy(previews = kept)
    }

    /** Fetches only the first read page for a tile in the current folder. */
    fun requestPreview(path: String) {
        val files = files() ?: return
        if (parentPath(path) != files.path || path in files.previews) return
        if (path in previewQueue || path in previewJobs) {
            // The cache may have evicted a still-pending tile. Restore its entry so the
            // existing request can publish its result, and an open Peek can pin it.
            write(files.sessionId) { cache(it, path, FileTilePreview()) }
            return
        }
        if (!current().connected) {
            write(files.sessionId) { cache(it, path, FileTilePreview(loading = false, failure = FilesFailure.OFFLINE)) }
            return
        }
        write(files.sessionId) { cache(it, path, FileTilePreview()) }
        previewQueue.addLast(path)
        startPreviews(files.sessionId, files.path)
    }

    private fun startPreviews(sessionId: String, folder: String) {
        if (drainingPreviews) return
        drainingPreviews = true
        try {
            while (previewJobs.size < 2 && previewQueue.isNotEmpty() &&
                files()?.let { it.sessionId == sessionId && it.path == folder } == true
            ) {
                val path = previewQueue.removeFirst()
                val version = previewVersion
                fun active() = version == previewVersion &&
                    files()?.let { it.sessionId == sessionId && it.path == folder && path in it.previews } == true
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val chunk = source.filesRead(sessionId, path, 0, null)
                        if (active()) {
                            val preview = FileTilePreview(
                                loading = false,
                                content = if (chunk.binary || chunk.tooLarge) null else chunk.content.take(FILE_TILE_PREVIEW_CHARS),
                                binary = chunk.binary,
                                tooLarge = chunk.tooLarge,
                                complete = !chunk.binary && !chunk.tooLarge && chunk.nextOffset == null &&
                                    chunk.content.length <= FILE_TILE_PREVIEW_CHARS,
                            )
                            write(sessionId) { state ->
                                if (state.path != folder) state
                                else {
                                    val updated = cache(state, path, preview)
                                    if (updated.peek?.path == path && updated.peek.type == FileEntryType.FILE)
                                        updated.copy(peek = updated.peek.copy(loading = false))
                                    else updated
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Diagnostics.caught("files.startPreviews", e)
                        if (active()) {
                            write(sessionId) { state ->
                                if (state.path != folder) state
                                else {
                                    val updated = cache(state, path, FileTilePreview(loading = false, failure = failure(e)))
                                    if (updated.peek?.path == path && updated.peek.type == FileEntryType.FILE)
                                        updated.copy(peek = updated.peek.copy(loading = false))
                                    else updated
                                }
                            }
                        }
                    } finally {
                        if (version == previewVersion) {
                            previewJobs.remove(path)
                            startPreviews(sessionId, folder)
                        }
                    }
                }
                previewJobs[path] = job
                job.start()
            }
        } finally {
            drainingPreviews = false
        }
    }

    /** Shows a read-only first-page peek without changing browser navigation. */
    fun showPeek(path: String, type: FileEntryType) {
        val files = files() ?: return
        if (parentPath(path) != files.path || type !in setOf(FileEntryType.DIR, FileEntryType.FILE)) return
        stopPeek()
        val preview = files.previews[path]
        write(files.sessionId) {
            it.copy(peek = FilesPeek(path, type, loading = type == FileEntryType.DIR || preview?.loading != false))
        }
        if (type == FileEntryType.FILE) {
            requestPreview(path)
            return
        }
        if (!current().connected) {
            write(files.sessionId) { it.copy(peek = it.peek?.copy(loading = false, failure = FilesFailure.OFFLINE)) }
            return
        }
        val version = peekVersion
        fun active() = version == peekVersion &&
            files()?.let { it.sessionId == files.sessionId && it.path == files.path && it.peek?.path == path && it.peek.type == type } == true
        peekJob = scope.launch {
            try {
                val page = source.filesList(files.sessionId, path, null)
                if (active()) write(files.sessionId) { state ->
                    val clipped = page.entries.size > MAX_FILE_PEEK_NAMES
                    val listing = page.copy(
                        entries = page.entries.take(MAX_FILE_PEEK_NAMES),
                        truncated = page.truncated || clipped,
                        nextAfter = page.nextAfter ?: if (clipped) page.entries[MAX_FILE_PEEK_NAMES - 1].name else null,
                    )
                    state.copy(peek = state.peek?.copy(loading = false, listing = listing))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.caught("files.showPeek", e)
                if (active()) write(files.sessionId) { state ->
                    state.copy(peek = state.peek?.copy(loading = false, failure = failure(e)))
                }
            }
        }
    }

    fun dismissPeek() {
        stopPeek()
        files()?.let { files -> write(files.sessionId) { it.copy(peek = null) } }
    }

    /**
     * Opens the browser at the session folder, or at its folder [path] with [file] open: the
     * browser as a return to the session left it. [lines] are picked in [file] and scrolled to.
     */
    fun open(path: String = "", file: String? = null, lines: LineSelection? = null) {
        stopList()
        stopFile()
        stopPreviews()
        stopEditor()
        val state = current()
        val sessionId = state.selection.sessionId ?: return
        val failure =
            when {
                !canBrowseFiles(state) -> FilesFailure.UNSUPPORTED
                !state.connected -> FilesFailure.OFFLINE
                else -> null
            }
        val opened = file?.takeIf { failure == null }
        val known = remembered[key(sessionId, path)].takeIf { failure == null }
        update {
            it.copy(
                files = FilesState(
                    sessionId,
                    path = path,
                    loading = failure == null && known == null,
                    listing = known,
                    failure = failure,
                    file = opened?.let { OpenFile(it, selection = lines, revealLine = lines?.first) },
                )
            )
        }
        if (failure != null) return
        if (known != null) relist(sessionId, path, revalidate = true) else list(sessionId, path, after = null)
        if (opened != null) read(sessionId, opened, 0, null, reopen = false)
    }

    /**
     * Opens the browser at the folder of [path] with the file open, [lines] picked and in view:
     * a file the chat named. False, with nothing opened, when [path] is not relative to the session
     * folder; `session.files.*` reads nothing outside it.
     */
    fun openAt(path: String, lines: LineSelection?): Boolean {
        if (!isSessionPath(path)) return false
        open(parentPath(path), path, lines)
        return true
    }

    fun close() {
        stopList()
        stopFile()
        stopPreviews()
        stopEditor()
        update { it.copy(files = null) }
    }

    /**
     * Shows the folder [path]: a child of the shown folder keeps the shown listing for Back, and
     * an ancestor comes back from those kept listings without a request.
     */
    fun openDir(path: String) = openDir(path, cached = true)

    /** [cached] shows the folder's last listing while it is listed again, if there is one. */
    private fun openDir(path: String, cached: Boolean) {
        val files = files() ?: return
        stopList()
        stopFile()
        stopPreviews()
        stopEditor()
        val kept = files.parents.indexOfFirst { it.path == path }
        if (kept >= 0) {
            write(files.sessionId) {
                it.copy(
                    path = path,
                    parents = it.parents.take(kept),
                    loading = false,
                    listing = it.parents[kept],
                    failure = null,
                    moreLoading = false,
                    moreFailure = null,
                    file = null,
                    previews = emptyMap(),
                    peek = null,
                    editor = null,
                )
            }
            return
        }
        val parents =
            if (path.isNotEmpty() && parentPath(path) == files.path)
                files.parents + listOfNotNull(files.listing?.takeIf { it.unavailable == null })
            else files.parents.filter { path.startsWith(if (it.path.isEmpty()) "" else it.path + "/") && it.path != path }
        val offline = !current().connected
        val known = remembered[key(files.sessionId, path)].takeIf { cached && !offline }
        write(files.sessionId) {
            it.copy(
                path = path,
                parents = parents,
                loading = !offline && known == null,
                listing = known,
                failure = if (offline) FilesFailure.OFFLINE else null,
                moreLoading = false,
                moreFailure = null,
                file = null,
                previews = emptyMap(),
                peek = null,
                editor = null,
            )
        }
        when {
            offline -> Unit
            known != null -> relist(files.sessionId, path, revalidate = true)
            else -> list(files.sessionId, path, after = null)
        }
    }

    /** Reloads what is shown: the open file from its start, or the shown folder's first page. */
    fun reload() {
        val files = files() ?: return
        stopPreviews()
        write(files.sessionId) { it.copy(previews = emptyMap(), peek = null) }
        val file = files.file
        // Reload by hand shows the read and its failure; it does not stand behind a remembered listing.
        if (file != null) openFile(file.path) else openDir(files.path, cached = false)
    }

    /**
     * Reads the shown file or folder again in place, as after a reconnect: what is shown stays
     * until the reply replaces it, and a failure leaves it. An open editor keeps its own text and
     * version; a file or folder with nothing shown yet loads again.
     */
    fun refresh() {
        val files = files() ?: return
        if (files.editor != null || !current().connected) return
        stopPreviews()
        // A preview or peek still loading lost its request; loaded ones stay.
        write(files.sessionId) {
            it.copy(
                previews = it.previews.filterValues { preview -> !preview.loading && preview.failure == null },
                peek = it.peek?.takeIf { peek -> !peek.loading && peek.failure == null },
            )
        }
        val file = files.file
        when {
            file != null && file.version != null && !file.loading && file.failure == null ->
                reread(files.sessionId, file)
            file != null -> openFile(file.path)
            files.listing != null && !files.loading && files.failure == null -> relist(files.sessionId, files.path)
            else -> openDir(files.path)
        }
        // The shown file's folder is listed again too, for Back.
        if (file != null && files.listing != null && files.failure == null) relist(files.sessionId, files.path)
    }

    /** The open file's first page again: the same version keeps every page shown. */
    private fun reread(sessionId: String, shown: OpenFile) {
        stopFile()
        val requestVersion = ++fileVersion
        fun active() =
            requestVersion == fileVersion &&
                files()?.let { it.sessionId == sessionId && it.file?.path == shown.path && it.editor == null } == true
        writeFile(sessionId, shown.path) { it.copy(moreLoading = false) }
        fileJob = scope.launch {
            try {
                val chunk = source.filesRead(sessionId, shown.path, 0, null)
                if (!active() || chunk.version == shown.version) return@launch
                writeFile(sessionId, shown.path) {
                    OpenFile(
                        shown.path,
                        loading = false,
                        version = chunk.version,
                        size = chunk.size,
                        content = chunk.content,
                        binary = chunk.binary,
                        tooLarge = chunk.tooLarge,
                        nextOffset = chunk.nextOffset,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.caught("files.reread", e)
            }
        }
    }

    /**
     * The shown folder's first page again; previews of entries listed the same stay. A failure
     * leaves the listing shown, except when [revalidate] checks a remembered listing just opened:
     * then the folder may be gone or unreadable since, and the failure replaces it.
     */
    private fun relist(sessionId: String, path: String, revalidate: Boolean = false) {
        stopList()
        val version = ++listVersion
        fun active() = version == listVersion && files()?.let { it.sessionId == sessionId && it.path == path } == true
        write(sessionId) { it.copy(moreLoading = false, moreFailure = null) }
        listJob = scope.launch {
            try {
                val page = source.filesList(sessionId, path, null)
                if (!active()) return@launch
                write(sessionId) { files ->
                    val before = files.listing?.entries.orEmpty().associateBy { it.name }
                    val listed = page.entries.associateBy { it.name }
                    files.copy(
                        listing = page,
                        previews = files.previews.filterKeys { preview ->
                            val name = preview.substringAfterLast('/')
                            listed[name] != null && listed[name] == before[name]
                        },
                    )
                }
                remember(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.caught("files.relist", e)
                if (revalidate && active()) {
                    remembered.remove(key(sessionId, path))
                    write(sessionId) { it.copy(loading = false, failure = failure(e)) }
                }
            }
        }
    }

    /** The next page of the open file, or of the shown folder. */
    fun loadMore() {
        val files = files() ?: return
        val file = files.file
        if (file != null) {
            val next = file.nextOffset ?: return
            val version = file.version ?: return
            if (file.moreLoading || file.binary) return
            if (!current().connected) {
                writeFile(files.sessionId, file.path) { it.copy(moreFailure = FilesFailure.OFFLINE) }
                return
            }
            stopFile()
            writeFile(files.sessionId, file.path) { it.copy(moreLoading = true, moreFailure = null) }
            read(files.sessionId, file.path, next, version, reopen = true)
            return
        }
        val listing = files.listing ?: return
        val after = listing.nextAfter ?: return
        if (files.moreLoading) return
        if (!current().connected) {
            write(files.sessionId) { it.copy(moreFailure = FilesFailure.OFFLINE) }
            return
        }
        stopList()
        write(files.sessionId) { it.copy(moreLoading = true, moreFailure = null) }
        list(files.sessionId, files.path, after)
    }

    private fun list(sessionId: String, path: String, after: String?) {
        val version = ++listVersion
        fun active() = version == listVersion && files()?.let { it.sessionId == sessionId && it.path == path } == true
        listJob = scope.launch {
            try {
                val page = source.filesList(sessionId, path, after)
                if (!active()) return@launch
                write(sessionId) { files ->
                    val previous = files.listing
                    val listing =
                        if (after == null || previous == null || page.unavailable != null) page
                        else {
                            // A vanished `after` entry makes the host repeat names; keep the first.
                            val names = previous.entries.mapTo(HashSet()) { it.name }
                            previous.copy(
                                entries = previous.entries + page.entries.filter { names.add(it.name) },
                                truncated = previous.truncated || page.truncated,
                                nextAfter = page.nextAfter,
                            )
                        }
                    files.copy(loading = false, listing = listing, failure = null, moreLoading = false, moreFailure = null)
                }
                remember(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.caught("files.list", e)
                if (!active()) return@launch
                val reason = failure(e)
                write(sessionId) {
                    if (after == null) it.copy(loading = false, failure = reason)
                    else it.copy(moreLoading = false, moreFailure = reason)
                }
            }
        }
    }

    /** Opens [path] of the shown folder from its first page, or returns to the folder for null. */
    fun openFile(path: String?) {
        val files = files() ?: return
        dismissPeek()
        stopFile()
        stopEditor()
        if (path == null) {
            write(files.sessionId) { it.copy(file = null, editor = null) }
            return
        }
        if (!current().connected) {
            write(files.sessionId) {
                it.copy(file = OpenFile(path, loading = false, failure = FilesFailure.OFFLINE), editor = null)
            }
            return
        }
        write(files.sessionId) { it.copy(file = OpenFile(path), editor = null) }
        read(files.sessionId, path, 0, null, reopen = false)
    }

    private fun read(sessionId: String, path: String, offset: Long, version: String?, reopen: Boolean) {
        val requestVersion = ++fileVersion
        fun active() =
            requestVersion == fileVersion &&
                files()?.let { it.sessionId == sessionId && it.file?.path == path } == true
        fileJob = scope.launch {
            try {
                val chunk = source.filesRead(sessionId, path, offset, version)
                if (!active()) return@launch
                writeFile(sessionId, path) { file ->
                    val read =
                        when {
                            offset == 0L ->
                                OpenFile(
                                    path,
                                    loading = false,
                                    version = chunk.version,
                                    size = chunk.size,
                                    content = chunk.content,
                                    binary = chunk.binary,
                                    tooLarge = chunk.tooLarge,
                                    nextOffset = chunk.nextOffset,
                                    reopened = file.reopened,
                                    // Lines picked before the text came are those the chat opened it at.
                                    selection = file.selection,
                                    revealLine = file.revealLine,
                                )
                            // The host decides per page: a later binary page makes the whole file binary.
                            chunk.binary ->
                                file.copy(binary = true, content = "", nextOffset = null, moreLoading = false, selection = null)
                            else ->
                                file.copy(content = file.content + chunk.content, nextOffset = chunk.nextOffset, moreLoading = false)
                        }
                    revealed(read)
                }
                // The lines the chat opened the file at lie further on: read on to them.
                val file = files()?.file?.takeIf { it.path == path }
                val wanted = file?.revealLine?.let { file.selection?.last ?: it }
                val next = file?.nextOffset
                if (file != null && wanted != null && next != null && !file.binary && lineCount(file.content) < wanted) {
                    writeFile(sessionId, path) { it.copy(moreLoading = true, moreFailure = null) }
                    read(sessionId, path, next, chunk.version, reopen = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.caught("files.read", e)
                if (!active()) return@launch
                val reason = failure(e)
                if (offset > 0 && reason == FilesFailure.NOT_FOUND && reopen) {
                    // "File changed; reopen": the pages so far belong to the old version.
                    writeFile(sessionId, path) { OpenFile(path, reopened = true) }
                    read(sessionId, path, 0, null, reopen = false)
                    return@launch
                }
                writeFile(sessionId, path) {
                    if (offset == 0L) it.copy(loading = false, failure = reason)
                    else it.copy(moreLoading = false, moreFailure = reason)
                }
            }
        }
    }

    /** Lines a final line end does not start; see [fileLines]. */
    private fun lineCount(content: String): Int =
        if (content.isEmpty()) 0 else content.count { it == '\n' } + if (content.endsWith('\n')) 0 else 1

    /**
     * [file] once it is read whole: the lines it was opened at are cut to the lines it has, and
     * dropped when it has none of them.
     */
    private fun revealed(file: OpenFile): OpenFile {
        val selection = file.selection
        if (file.revealLine == null || selection == null || file.loading || file.nextOffset != null) return file
        val count = lineCount(file.content)
        return when {
            file.binary || file.tooLarge || selection.first > count -> file.copy(selection = null, revealLine = null)
            selection.last > count -> file.copy(selection = LineSelection(selection.first, count.takeIf { it > selection.first }))
            else -> file
        }
    }

    fun selectLines(selection: LineSelection?) {
        val files = files() ?: return
        val file = files.file ?: return
        writeFile(files.sessionId, file.path) { it.copy(selection = selection) }
    }

    // ---- Editor ---------------------------------------------------------------------------

    private fun writeEditor(sessionId: String, path: String, block: (FileEditor) -> FileEditor) =
        write(sessionId) { files -> files.editor?.takeIf { it.path == path }?.let { files.copy(editor = block(it)) } ?: files }

    /**
     * Opens the open file in the editor when [canEditFile] allows it; an open editor stays. The
     * cursor starts at the first line picked in the file view.
     */
    fun startEditing() {
        val files = files() ?: return
        if (files.editor != null || !canEditFile(current())) return
        val file = checkNotNull(files.file)
        stopEditor()
        val cursor = file.selection?.let { lineStartOffset(file.content, it.first) } ?: 0
        write(files.sessionId) {
            it.copy(peek = null, editor = FileEditor(file.path, checkNotNull(file.version), file.content, cursor = cursor))
        }
    }

    fun updateEditorText(text: String) {
        val files = files() ?: return
        val editor = files.editor ?: return
        if (editor.text != text) writeEditor(files.sessionId, editor.path) { it.copy(text = text) }
    }

    /**
     * Sends the editor's text with the version it was read or last saved as. On success that text
     * becomes the new original and the open file shows it; on `conflict` the edit stays and
     * [FileEditor.conflict] is set, unless the host already holds exactly this text (a save whose
     * reply was lost), which counts as saved. When the host holds the text of an earlier save of
     * this editor that was never answered ([FileEditor.superseded]), that write becomes the new base
     * and this text is sent once more against it. Text typed while the save runs stays as an
     * unsaved change. A save the host never answered keeps its request ID for a retry of the same
     * text. Unchanged text sends nothing and only clears an old failure.
     */
    fun saveEditor() {
        val files = files() ?: return
        val editor = files.editor ?: return
        if (editor.saving) return
        val sessionId = files.sessionId
        val path = editor.path
        val content = editor.latestText
        if (content == editor.original) {
            writeEditor(sessionId, path) { it.copy(text = content, saveFailure = null, conflict = false) }
            return
        }
        val state = current()
        val refused =
            when {
                // A lost connection forgets the host's capabilities, and a reconnect learns them
                // again only with its first projects.list: both are "offline" for a save.
                !state.connected || !state.capabilitiesKnown -> FilesFailure.OFFLINE
                !canWriteFiles(state) -> FilesFailure.UNSUPPORTED
                !filesContentFits(content) -> FilesFailure.TOO_LARGE
                else -> null
            }
        if (refused != null) {
            writeEditor(sessionId, path) { it.copy(text = content, saveFailure = refused, conflict = false) }
            return
        }
        val firstBase = editor.baseVersion
        val previous = editor.unconfirmed
        val resent = previous?.takeIf { it.baseVersion == firstBase && it.content == content }
        // An unanswered save of other text may still have reached the host: remember it.
        val superseded =
            if (previous == null || resent != null) editor.superseded
            else (editor.superseded.filter { it.content != previous.content } + previous).takeLast(MAX_SUPERSEDED_WRITES)
        var requestId = resent?.requestId ?: Wire.random()
        val version = editorVersion
        fun active() = version == editorVersion && files()?.let { it.sessionId == sessionId && it.editor?.path == path } == true
        writeEditor(sessionId, path) {
            it.copy(
                text = content,
                saving = true,
                saveFailure = null,
                unconfirmed = PendingWrite(requestId, firstBase, content),
                superseded = superseded,
            )
        }
        // Not cancelled by navigation: the host may already have the write, so only the reply is dropped.
        scope.launch {
            var baseVersion = firstBase
            var rebased = false
            try {
                while (true) {
                    try {
                        val written = source.filesWrite(sessionId, path, baseVersion, content, requestId)
                        if (active()) saved(sessionId, path, content, written)
                        return@launch
                    } catch (e: CancellationException) {
                        // The repository also throws this when the selection changes under a live
                        // request (a reconnect): the write may or may not have happened.
                        if (!isActive) throw e
                        if (active()) saveFailed(sessionId, path, requestId, FilesFailure.OFFLINE, answered = false)
                        return@launch
                    } catch (e: Exception) {
                        Diagnostics.caught("files.write", e)
                        if (!active()) return@launch
                        val reason = failure(e)
                        val host = if (reason == FilesFailure.CONFLICT) wholeFile(sessionId, path) else null
                        if (!active()) return@launch
                        if (host != null && host.content == content) {
                            saved(sessionId, path, content, FileWritten(path, host.version, host.size))
                            return@launch
                        }
                        val ours =
                            host?.takeIf { !rebased }?.let { chunk ->
                                files()?.editor?.superseded?.any { it.content == chunk.content }
                            } == true
                        if (host == null || !ours) {
                            saveFailed(sessionId, path, requestId, reason, answered = e.hostCode.let { it != null && it != "offline" })
                            return@launch
                        }
                        // The host holds an earlier save of ours: send this text again on top of it, once.
                        rebased = true
                        baseVersion = host.version
                        requestId = Wire.random()
                        rebase(sessionId, path, host, PendingWrite(requestId, baseVersion, content))
                    }
                }
            } finally {
                // Whatever ended the save, the editor never stays "saving".
                if (active()) writeEditor(sessionId, path) {
                    if (it.saving && it.unconfirmed?.requestId == requestId) it.copy(saving = false, saveFailure = FilesFailure.OFFLINE)
                    else it
                }
            }
        }
    }

    /** The host's file read whole in one page as text, or null. */
    private suspend fun wholeFile(sessionId: String, path: String): FileChunk? =
        try {
            source.filesRead(sessionId, path, 0, null).takeIf { !it.binary && !it.tooLarge && it.nextOffset == null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.caught("files.wholeFile", e)
            null
        }

    /**
     * Makes [host], an earlier write of this editor, its new original and base while [pending]
     * sends the newer text on top of it; the open file shows what the host holds.
     */
    private fun rebase(sessionId: String, path: String, host: FileChunk, pending: PendingWrite) =
        write(sessionId) { state ->
            val current = state.editor?.takeIf { it.path == path } ?: return@write state
            state.copy(
                editor = current.copy(
                    baseVersion = host.version,
                    original = host.content,
                    conflict = false,
                    unconfirmed = pending,
                    superseded = emptyList(),
                ),
                file = state.file?.takeIf { it.path == path }?.copy(
                    version = host.version,
                    size = host.size,
                    content = host.content,
                    selection = null,
                    reopened = false,
                ) ?: state.file,
            )
        }

    private fun saved(sessionId: String, path: String, content: String, written: FileWritten) =
        write(sessionId) { state ->
            val current = state.editor?.takeIf { it.path == path } ?: return@write state
            state.copy(
                editor = current.copy(
                    baseVersion = written.version,
                    original = content,
                    saving = false,
                    saveFailure = null,
                    conflict = false,
                    savedAt = current.savedAt + 1,
                    unconfirmed = null,
                    superseded = emptyList(),
                ),
                file = state.file?.takeIf { it.path == path }?.copy(
                    version = written.version,
                    size = written.size,
                    content = content,
                    selection = null,
                    reopened = false,
                ) ?: state.file,
            )
        }

    /**
     * Shows [reason]. A failure the host [answered] forgets the request ID, so a retry is a new
     * save; any other keeps it, since the host may have the write.
     */
    private fun saveFailed(sessionId: String, path: String, requestId: String, reason: FilesFailure, answered: Boolean) =
        writeEditor(sessionId, path) {
            it.copy(
                saving = false,
                saveFailure = reason,
                conflict = reason == FilesFailure.CONFLICT,
                unconfirmed = it.unconfirmed?.takeUnless { pending -> answered && pending.requestId == requestId },
            )
        }

    /** Leaves the editor for the read-only file; its text is dropped. */
    fun discardEditor() {
        val files = files() ?: return
        if (files.editor == null) return
        stopEditor()
        write(files.sessionId) { it.copy(editor = null) }
    }

    /** Drops the edit and reads the open file again, after a save found it changed. */
    fun reloadAfterConflict() {
        val files = files() ?: return
        val path = files.editor?.path ?: return
        stopEditor()
        openFile(path)
    }

    /**
     * True when [leave] has to wait because the editor holds unsaved changes; the editor then
     * asks, and [confirmLeave] or [cancelLeave] answers. False when nothing is in the way.
     */
    fun requestLeave(leave: EditorLeave): Boolean {
        val files = files() ?: return false
        val editor = files.editor ?: return false
        if (!editor.changed) return false
        writeEditor(files.sessionId, editor.path) { it.copy(leaveRequest = leave) }
        return true
    }

    /** Discards the edit and returns the action that waited for it, if any. */
    fun confirmLeave(): EditorLeave? {
        val leave = files()?.editor?.leaveRequest
        discardEditor()
        return leave
    }

    fun cancelLeave() {
        val files = files() ?: return
        val editor = files.editor ?: return
        writeEditor(files.sessionId, editor.path) { it.copy(leaveRequest = null) }
    }

    /**
     * The prompt that hands the editor's change to the agent ([editorPrompt]), or null when
     * nothing changed or no editor is open.
     */
    fun editorPrompt(intro: String? = null): EditorPrompt? {
        val editor = files()?.editor ?: return null
        return de.joinnoah.pi.remote.editorPrompt(editor.path, editor.original, editor.latestText, intro ?: editorPromptIntro(editor.path))
    }
}
