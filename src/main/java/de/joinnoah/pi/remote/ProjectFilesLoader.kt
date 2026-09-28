package de.joinnoah.pi.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The two `session.files.*` reads. Implementations throw [RemoteRequestException] on errors. */
internal interface FileSource {
    suspend fun filesList(sessionId: String, path: String, after: String?): FileListing

    suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?): FileChunk
}

/**
 * Loads the file browser into [RemoteState.files]. Folders and files load a page at a time; a
 * later file page that comes back `not_found` (the file changed, so its version no longer
 * matches) reads the file once more from the start. A response for a folder or file that is no
 * longer shown is dropped.
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

    /** Opens the browser at the session folder. */
    fun open() {
        stopList()
        stopFile()
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
            )
        }
        if (!offline) list(files.sessionId, path, after = null)
    }

    /** Reloads what is shown: the open file from its start, or the shown folder's first page. */
    fun reload() {
        val files = files() ?: return
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
        stopFile()
        if (path == null) {
            write(files.sessionId) { it.copy(file = null) }
            return
        }
        if (!current().connected) {
            write(files.sessionId) { it.copy(file = OpenFile(path, loading = false, failure = FilesFailure.OFFLINE)) }
            return
        }
        write(files.sessionId) { it.copy(file = OpenFile(path)) }
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
}
