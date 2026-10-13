package de.joinnoah.pi.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The `session.files.*` commands. Implementations throw [RemoteRequestException] on errors. */
internal interface FileSource {
    suspend fun filesList(sessionId: String, path: String, after: String?): FileListing

    suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?): FileChunk

    suspend fun filesWrite(sessionId: String, path: String, baseVersion: String, content: String): FileWritten =
        throw RemoteRequestException("unsupported")
}

/**
 * Loads the file browser into [RemoteState.files]. Folders and files load a page at a time; a
 * later file page that comes back `not_found` (the file changed, so its version no longer
 * matches) reads the file once more from the start. A response for a folder or file that is no
 * longer shown is dropped, and so is a save reply once its editor is gone.
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
        when ((e as? RemoteRequestException)?.code) {
            "not_found" -> FilesFailure.NOT_FOUND
            "invalid_path" -> FilesFailure.INVALID_PATH
            "forbidden" -> FilesFailure.FORBIDDEN
            "busy" -> FilesFailure.BUSY
            "conflict" -> FilesFailure.CONFLICT
            "unsupported" -> FilesFailure.UNSUPPORTED
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

    /** Opens the browser at the session folder. */
    fun open() {
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
        update { it.copy(files = FilesState(sessionId, loading = failure == null, failure = failure)) }
        if (failure == null) list(sessionId, "", after = null)
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
    fun openDir(path: String) {
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
        write(files.sessionId) {
            it.copy(
                path = path,
                parents = parents,
                loading = !offline,
                listing = null,
                failure = if (offline) FilesFailure.OFFLINE else null,
                moreLoading = false,
                moreFailure = null,
                file = null,
                previews = emptyMap(),
                peek = null,
                editor = null,
            )
        }
        if (!offline) list(files.sessionId, path, after = null)
    }

    /** Reloads what is shown: the open file from its start, or the shown folder's first page. */
    fun reload() {
        val files = files() ?: return
        stopPreviews()
        write(files.sessionId) { it.copy(previews = emptyMap(), peek = null) }
        val file = files.file
        if (file != null) openFile(file.path) else openDir(files.path)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
                            )
                        // The host decides per page: a later binary page makes the whole file binary.
                        chunk.binary ->
                            file.copy(binary = true, content = "", nextOffset = null, moreLoading = false, selection = null)
                        else ->
                            file.copy(content = file.content + chunk.content, nextOffset = chunk.nextOffset, moreLoading = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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

    fun selectLines(selection: LineSelection?) {
        val files = files() ?: return
        val file = files.file ?: return
        writeFile(files.sessionId, file.path) { it.copy(selection = selection) }
    }

    // ---- Editor ---------------------------------------------------------------------------

    private fun writeEditor(sessionId: String, path: String, block: (FileEditor) -> FileEditor) =
        write(sessionId) { files -> files.editor?.takeIf { it.path == path }?.let { files.copy(editor = block(it)) } ?: files }

    /** Opens the open file in the editor when [canEditFile] allows it; an open editor stays. */
    fun startEditing() {
        val files = files() ?: return
        if (files.editor != null || !canEditFile(current())) return
        val file = checkNotNull(files.file)
        stopEditor()
        write(files.sessionId) {
            it.copy(peek = null, editor = FileEditor(file.path, checkNotNull(file.version), file.content))
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
     * [FileEditor.conflict] is set. Text typed while the save runs stays as an unsaved change.
     */
    fun saveEditor() {
        val files = files() ?: return
        val editor = files.editor ?: return
        if (editor.saving) return
        val sessionId = files.sessionId
        val path = editor.path
        // Offline first: a lost connection also forgets the host's capabilities.
        if (!current().connected) {
            writeEditor(sessionId, path) { it.copy(saveFailure = FilesFailure.OFFLINE) }
            return
        }
        if (!canWriteFiles(current())) {
            writeEditor(sessionId, path) { it.copy(saveFailure = FilesFailure.UNSUPPORTED) }
            return
        }
        val content = editor.text
        val baseVersion = editor.baseVersion
        val version = editorVersion
        fun active() = version == editorVersion && files()?.let { it.sessionId == sessionId && it.editor?.path == path } == true
        writeEditor(sessionId, path) { it.copy(saving = true, saveFailure = null) }
        // Not cancelled by navigation: the host may already have the write, so only the reply is dropped.
        scope.launch {
            try {
                val written = source.filesWrite(sessionId, path, baseVersion, content)
                if (!active()) return@launch
                write(sessionId) { state ->
                    val current = state.editor ?: return@write state
                    state.copy(
                        editor = current.copy(
                            baseVersion = written.version,
                            original = content,
                            saving = false,
                            saveFailure = null,
                            conflict = false,
                            savedAt = current.savedAt + 1,
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!active()) return@launch
                val reason = failure(e)
                writeEditor(sessionId, path) {
                    it.copy(saving = false, saveFailure = reason, conflict = it.conflict || reason == FilesFailure.CONFLICT)
                }
            }
        }
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
     * The prompt that hands the editor's change to the agent ([editorPrompt]), or null when
     * nothing changed or no editor is open.
     */
    fun editorPrompt(intro: String? = null): EditorPrompt? {
        val editor = files()?.editor ?: return null
        return de.joinnoah.pi.remote.editorPrompt(editor.path, editor.original, editor.text, intro ?: editorPromptIntro(editor.path))
    }
}
