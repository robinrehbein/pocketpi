package de.joinnoah.pi.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The three `session.git.*` reads. Implementations throw [RemoteRequestException] on errors. */
internal interface GitSource {
    suspend fun gitStatus(sessionId: String, base: GitBase): GitStatus

    suspend fun gitDiff(sessionId: String, snapshotId: String, path: String): GitDiff

    suspend fun gitLog(sessionId: String, snapshotId: String): GitLog
}

/**
 * Loads the changes view into [RemoteState.changes]. Session start is the default base and falls
 * back to HEAD when the host has none; a `not_found` (the snapshot expired after ten minutes)
 * requests a fresh status once and repeats the read.
 */
internal class GitChangesLoader(
    private val scope: CoroutineScope,
    private val source: GitSource,
    private val current: () -> RemoteState,
    private val update: ((RemoteState) -> RemoteState) -> Unit,
) {
    private val previewSlots = Semaphore(2)
    private val previewJobs = mutableMapOf<String, Job>()
    private var previewVersion = 0L
    private var statusJob: Job? = null
    private var diffJob: Job? = null
    private var statusVersion = 0L
    private var diffVersion = 0L
    private var subagentJob: Job? = null
    private var subagentVersion = 0L

    private fun changes(): ChangesState? =
        current().changes?.takeIf { it.sessionId == current().selection.sessionId }

    private fun write(sessionId: String, block: (ChangesState) -> ChangesState) =
        update { state ->
            val changes = state.changes
            if (changes == null || changes.sessionId != sessionId ||
                state.selection.sessionId != sessionId
            ) state
            else state.copy(changes = block(changes))
        }

    private fun failure(e: Exception): ChangesFailure =
        when ((e as? RemoteRequestException)?.code) {
            "not_found" -> ChangesFailure.NOT_FOUND
            "forbidden" -> ChangesFailure.FORBIDDEN
            "busy" -> ChangesFailure.BUSY
            "unsupported" -> ChangesFailure.UNSUPPORTED
            "offline" -> ChangesFailure.OFFLINE
            else -> if (!current().connected) ChangesFailure.OFFLINE else ChangesFailure.FAILED
        }

    private fun stop() {
        previewVersion++
        previewJobs.values.forEach { it.cancel() }
        previewJobs.clear()
        statusVersion++
        diffVersion++
        statusJob?.cancel()
        diffJob?.cancel()
        statusJob = null
        diffJob = null
    }

    /** Opens the view for the selected session with [comments] from its draft. */
    fun open(comments: List<ReviewComment>) {
        stop()
        val state = current()
        val sessionId = state.selection.sessionId ?: return
        val failure =
            when {
                !canViewChanges(state) -> ChangesFailure.UNSUPPORTED
                !state.connected -> ChangesFailure.OFFLINE
                else -> null
            }
        update {
            it.copy(
                changes = ChangesState(
                    sessionId,
                    loading = failure == null,
                    failure = failure,
                    comments = comments,
                )
            )
        }
        if (failure == null) {
            loadStatus(sessionId, GitBase.SESSION, fallback = true)
            loadSubagents(sessionId)
        } else stopSubagents()
    }

    fun close() {
        stop()
        stopSubagents()
        update { it.copy(changes = null) }
    }

    private fun stopSubagents() {
        subagentVersion++
        subagentJob?.cancel()
        subagentJob = null
    }

    /** Shows [base]; an explicit choice of session start does not fall back to HEAD. */
    fun selectBase(base: GitBase) {
        val changes = changes() ?: return
        stop()
        val offline = !current().connected
        write(changes.sessionId) {
            it.copy(
                base = base,
                previews = emptyMap(),
                loading = !offline,
                status = null,
                sessionUnavailable = null,
                failure = if (offline) ChangesFailure.OFFLINE else null,
                file = null,
                diff = null,
                diffLoading = false,
                diffFailure = null,
                log = null,
                logLoading = false,
                logFailure = null,
            )
        }
        if (!offline) loadStatus(changes.sessionId, base, fallback = false)
    }

    /** Reloads the status of the shown base, keeping the open file when it is still listed. */
    fun reload() = reload(retry = true)

    private fun reload(retry: Boolean) {
        val changes = changes() ?: return
        if (!current().connected) {
            write(changes.sessionId) { it.copy(failure = ChangesFailure.OFFLINE) }
            return
        }
        stop()
        val base = changes.status?.base ?: changes.base
        write(changes.sessionId) { it.copy(loading = true, failure = null, previews = emptyMap()) }
        loadStatus(
            changes.sessionId,
            base,
            fallback = changes.status == null && base == GitBase.SESSION,
            retry = retry,
        )
        // A subagent file shown now reopens from that subagent's fresh snapshot.
        val subagentFile = changes.file?.let { path -> changes.fileSession?.let { it to path } }
        loadSubagents(changes.sessionId, reopen = subagentFile)
    }

    private fun loadStatus(
        sessionId: String,
        base: GitBase,
        fallback: Boolean,
        retry: Boolean = true,
    ) {
        val version = ++statusVersion
        fun active() = version == statusVersion && changes()?.sessionId == sessionId
        statusJob = scope.launch {
            val status =
                try {
                    var status = source.gitStatus(sessionId, base)
                    var sessionUnavailable: GitUnavailable? = null
                    if (fallback && base == GitBase.SESSION && status.unavailable in fallbackReasons) {
                        sessionUnavailable = status.unavailable
                        status = source.gitStatus(sessionId, GitBase.HEAD)
                    }
                    if (!active()) return@launch
                    val shownLog = status.available && status.base != GitBase.HEAD
                    write(sessionId) { changes ->
                        // A subagent's file reads that subagent's snapshot; this status leaves it alone.
                        val own = changes.fileSession == null
                        // The file open now, not at the request: a back press meanwhile stays.
                        val file =
                            if (!own) changes.file
                            else changes.file?.takeIf { path -> status.files.any { it.path == path } }
                        changes.copy(
                            loading = false,
                            status = status,
                            // A reload of the HEAD fallback still explains why it shows HEAD.
                            sessionUnavailable = sessionUnavailable
                                ?: changes.sessionUnavailable.takeIf {
                                    changes.base == GitBase.SESSION && status.base == GitBase.HEAD
                                },
                            failure = null,
                            file = file,
                            diff = if (own) null else changes.diff,
                            diffLoading = if (own) file != null else changes.diffLoading,
                            diffFailure = if (own) null else changes.diffFailure,
                            log = if (status.available && !shownLog) GitLog() else null,
                            logLoading = shownLog,
                            logFailure = null,
                            // Base dev gone since the last status: the repository has no such branch any more.
                            devBase = status.devBase
                                ?: GitDevBase(null).takeIf {
                                    status.base == GitBase.DEV && status.unavailable == GitUnavailable.BASE_UNAVAILABLE
                                }
                                ?: changes.devBase,
                        )
                    }
                    status
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (active()) write(sessionId) { it.copy(loading = false, failure = failure(e)) }
                    return@launch
                }
            val snapshotId = status.snapshotId ?: return@launch
            changes()?.takeIf { it.fileSession == null }?.file?.let { path ->
                loadDiff(sessionId, snapshotId, path, retry = false)
            }
            if (!status.available || status.base == GitBase.HEAD) return@launch
            try {
                val log = source.gitLog(sessionId, snapshotId)
                if (active()) write(sessionId) { it.copy(log = log, logLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!active()) return@launch
                if (failure(e) == ChangesFailure.NOT_FOUND && retry) {
                    // The snapshot expired between status and log: one fresh status repeats both.
                    reload(retry = false)
                    return@launch
                }
                write(sessionId) { it.copy(logLoading = false, logFailure = failure(e)) }
            }
        }
    }

    /** Read the same snapshot as the full diff; deleted files can still be previewed. */
    fun requestPreview(path: String) {
        val changes = changes() ?: return
        val status = changes.status?.takeIf { !changes.loading && changes.failure == null } ?: return
        val snapshot = status.snapshotId ?: return
        val file = status.files.firstOrNull { it.path == path } ?: return
        if (path in changes.previews || path in previewJobs) return
        if (file.binary || file.omitted != null) {
            write(changes.sessionId) {
                it.copy(previews = it.previews + (path to FileTilePreview(
                    loading = false, binary = file.binary,
                    tooLarge = file.omitted == GitOmitted.TOO_LARGE,
                    failure = if (file.omitted == GitOmitted.BASE_UNAVAILABLE) FilesFailure.NOT_FOUND else null,
                )))
            }
            return
        }
        write(changes.sessionId) { it.copy(previews = it.previews + (path to FileTilePreview())) }
        val version = previewVersion
        fun active() = version == previewVersion && changes()?.let {
            it.sessionId == changes.sessionId && it.status?.snapshotId == snapshot
        } == true
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                previewSlots.withPermit {
                    if (!active()) return@withPermit
                    val diff = source.gitDiff(changes.sessionId, snapshot, path)
                    // Skip diff metadata, retain the +/- prefixes and hunk headers in Peek.
                    val excerpt = diff.patch.lineSequence().dropWhile { !it.startsWith("@@") }
                        .take(40).joinToString("\n").take(FILE_TILE_PREVIEW_CHARS)
                    if (active()) write(changes.sessionId) {
                        it.copy(previews = it.previews + (path to FileTilePreview(
                            loading = false, content = excerpt, binary = diff.binary,
                            tooLarge = diff.omitted == GitOmitted.TOO_LARGE,
                            failure = if (diff.omitted == GitOmitted.BASE_UNAVAILABLE) FilesFailure.NOT_FOUND else null,
                        )))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (active()) write(changes.sessionId) {
                    it.copy(previews = it.previews + (path to FileTilePreview(
                        loading = false,
                        failure = when (failure(e)) {
                            ChangesFailure.OFFLINE -> FilesFailure.OFFLINE
                            ChangesFailure.UNSUPPORTED -> FilesFailure.UNSUPPORTED
                            ChangesFailure.NOT_FOUND -> FilesFailure.NOT_FOUND
                            ChangesFailure.FORBIDDEN -> FilesFailure.FORBIDDEN
                            ChangesFailure.BUSY -> FilesFailure.BUSY
                            ChangesFailure.FAILED -> FilesFailure.FAILED
                        },
                    )))
                }
            } finally {
                if (version == previewVersion) previewJobs.remove(path)
            }
        }
        previewJobs[path] = job
        job.start()
    }

    /** Opens [path] from the shown status, or goes back to the file list for null. */
    fun openFile(path: String?) {
        val changes = changes() ?: return
        diffVersion++
        diffJob?.cancel()
        if (path == null) {
            write(changes.sessionId) {
                it.copy(file = null, fileSession = null, diff = null, diffLoading = false, diffFailure = null)
            }
            return
        }
        val status = changes.status ?: return
        val snapshotId = status.snapshotId ?: return
        val file = status.files.firstOrNull { it.path == path } ?: return
        if (file.binary || file.omitted != null) {
            // The status already says there is no text to show; skip the request.
            write(changes.sessionId) {
                it.copy(
                    file = path,
                    fileSession = null,
                    diff = GitDiff(path, snapshotId, "", file.binary, false, file.omitted),
                    diffLoading = false,
                    diffFailure = null,
                )
            }
            return
        }
        if (!current().connected) {
            write(changes.sessionId) {
                it.copy(file = path, fileSession = null, diff = null, diffLoading = false, diffFailure = ChangesFailure.OFFLINE)
            }
            return
        }
        write(changes.sessionId) {
            it.copy(file = path, fileSession = null, diff = null, diffLoading = true, diffFailure = null)
        }
        loadDiff(changes.sessionId, snapshotId, path, retry = true)
    }

    /** Opens [path] from the changes of the subagent [subagentId]; its diff reads that subagent's snapshot. */
    fun openSubagentFile(subagentId: String, path: String, retry: Boolean = true) {
        val changes = changes() ?: return
        val status = changes.subagents.firstOrNull { it.sessionId == subagentId }?.status ?: return
        val snapshotId = status.snapshotId ?: return
        val file = status.files.firstOrNull { it.path == path } ?: return
        diffVersion++
        diffJob?.cancel()
        if (file.binary || file.omitted != null) {
            write(changes.sessionId) {
                it.copy(
                    file = path,
                    fileSession = subagentId,
                    diff = GitDiff(path, snapshotId, "", file.binary, false, file.omitted),
                    diffLoading = false,
                    diffFailure = null,
                )
            }
            return
        }
        val offline = !current().connected
        write(changes.sessionId) {
            it.copy(
                file = path,
                fileSession = subagentId,
                diff = null,
                diffLoading = !offline,
                diffFailure = if (offline) ChangesFailure.OFFLINE else null,
            )
        }
        if (!offline) loadDiff(changes.sessionId, snapshotId, path, retry, reader = subagentId)
    }

    /**
     * Asks the session's subagents (running first, then the newest, at most [MAX_SUBAGENT_GROUPS])
     * for their changes against HEAD, one after another so the host's status limit is not hit.
     * A subagent the host has no changes for, or that fails, gets no group. [reopen] opens that
     * subagent file again once its fresh snapshot is in.
     */
    private fun loadSubagents(sessionId: String, reopen: Pair<String, String>? = null) {
        val version = ++subagentVersion
        subagentJob?.cancel()
        val now = current()
        val children =
            now.sessions.mapNotNull { runCatching { sessionListItem(it, now.connected, now.loading) }.getOrNull() }
                .filter { it.parentSessionId == sessionId && it.id != sessionId }
                .sortedWith(compareByDescending<SessionListItem> { it.running() }.thenByDescending { it.updatedAt ?: 0L })
                .take(MAX_SUBAGENT_GROUPS)
        if (children.isEmpty()) {
            write(sessionId) { it.copy(subagents = emptyList()) }
            return
        }
        subagentJob = scope.launch {
            val found = mutableListOf<SubagentChanges>()
            for (child in children) {
                val status =
                    try {
                        source.gitStatus(child.id, GitBase.HEAD)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                if (version != subagentVersion) return@launch
                if (status != null && status.available && status.files.isNotEmpty())
                    found += SubagentChanges(child.id, child.title, child.running(), status)
            }
            write(sessionId) { it.copy(subagents = found) }
            val (subagentId, path) = reopen ?: return@launch
            if (changes()?.let { it.file == path && it.fileSession == subagentId } == true)
                openSubagentFile(subagentId, path, retry = false)
        }
    }

    private fun SessionListItem.running(): Boolean =
        availability == SessionAvailability.RUNNING || availability == SessionAvailability.WAITING

    /** [reader] is the session whose snapshot [snapshotId] is: the shown one, or one of its subagents. */
    private fun loadDiff(
        sessionId: String,
        snapshotId: String,
        path: String,
        retry: Boolean,
        reader: String = sessionId,
    ) {
        val subagent = reader.takeIf { it != sessionId }
        val version = ++diffVersion
        fun active() =
            version == diffVersion && changes()?.let {
                it.sessionId == sessionId && it.file == path && it.fileSession == subagent
            } == true
        diffJob = scope.launch {
            try {
                val diff = source.gitDiff(reader, snapshotId, path)
                if (active()) write(sessionId) { it.copy(diff = diff, diffLoading = false, diffFailure = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!active()) return@launch
                val reason = failure(e)
                if (reason == ChangesFailure.NOT_FOUND && retry) {
                    // Snapshots expire after ten minutes: take a fresh one and reopen the file.
                    if (subagent == null) reload(retry = false)
                    else loadSubagents(sessionId, reopen = subagent to path)
                    return@launch
                }
                write(sessionId) { it.copy(diffLoading = false, diffFailure = reason) }
            }
        }
    }

    fun setComments(comments: List<ReviewComment>) {
        val changes = changes() ?: return
        write(changes.sessionId) { it.copy(comments = comments) }
    }

    private companion object {
        val fallbackReasons = setOf(GitUnavailable.BASE_UNAVAILABLE, GitUnavailable.SESSION_UNSUPPORTED)
    }
}
