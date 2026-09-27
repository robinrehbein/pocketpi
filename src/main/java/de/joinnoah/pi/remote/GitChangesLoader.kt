package de.joinnoah.pi.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    private var statusJob: Job? = null
    private var diffJob: Job? = null
    private var statusVersion = 0L
    private var diffVersion = 0L

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
        if (failure == null) loadStatus(sessionId, GitBase.SESSION, fallback = true)
    }

    fun close() {
        stop()
        update { it.copy(changes = null) }
    }

    /** Shows [base]; an explicit choice of session start does not fall back to HEAD. */
    fun selectBase(base: GitBase) {
        val changes = changes() ?: return
        stop()
        val offline = !current().connected
        write(changes.sessionId) {
            it.copy(
                base = base,
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
        write(changes.sessionId) { it.copy(loading = true, failure = null) }
        loadStatus(
            changes.sessionId,
            base,
            fallback = changes.status == null && base == GitBase.SESSION,
            retry = retry,
        )
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
                        // The file open now, not at the request: a back press meanwhile stays.
                        val file = changes.file?.takeIf { path -> status.files.any { it.path == path } }
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
                            diff = null,
                            diffLoading = file != null,
                            diffFailure = null,
                            log = if (status.available && !shownLog) GitLog() else null,
                            logLoading = shownLog,
                            logFailure = null,
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
            changes()?.file?.let { path -> loadDiff(sessionId, snapshotId, path, retry = false) }
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

    /** Opens [path] from the shown status, or goes back to the file list for null. */
    fun openFile(path: String?) {
        val changes = changes() ?: return
        diffVersion++
        diffJob?.cancel()
        if (path == null) {
            write(changes.sessionId) {
                it.copy(file = null, diff = null, diffLoading = false, diffFailure = null)
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
                    diff = GitDiff(path, snapshotId, "", file.binary, false, file.omitted),
                    diffLoading = false,
                    diffFailure = null,
                )
            }
            return
        }
        if (!current().connected) {
            write(changes.sessionId) {
                it.copy(file = path, diff = null, diffLoading = false, diffFailure = ChangesFailure.OFFLINE)
            }
            return
        }
        write(changes.sessionId) {
            it.copy(file = path, diff = null, diffLoading = true, diffFailure = null)
        }
        loadDiff(changes.sessionId, snapshotId, path, retry = true)
    }

    private fun loadDiff(sessionId: String, snapshotId: String, path: String, retry: Boolean) {
        val version = ++diffVersion
        fun active() =
            version == diffVersion && changes()?.let { it.sessionId == sessionId && it.file == path } == true
        diffJob = scope.launch {
            try {
                val diff = source.gitDiff(sessionId, snapshotId, path)
                if (active()) write(sessionId) { it.copy(diff = diff, diffLoading = false, diffFailure = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!active()) return@launch
                val reason = failure(e)
                if (reason == ChangesFailure.NOT_FOUND && retry) {
                    // Snapshots expire after ten minutes: take a fresh one and reopen the file.
                    reload(retry = false)
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
