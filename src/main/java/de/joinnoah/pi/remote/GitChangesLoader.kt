package de.joinnoah.pi.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The three `session.git.*` reads. Implementations throw [RemoteRequestException] on errors. */
internal interface GitSource {
    suspend fun gitStatus(sessionId: String, base: GitBase): GitStatus

    suspend fun gitDiff(sessionId: String, snapshotId: String, path: String): GitDiff

    suspend fun gitLog(sessionId: String, snapshotId: String): GitLog
}

/** How long [GitChangesLoader.reloadSoon] waits for the agent's next change before it reads. */
internal const val CHANGES_LIVE_RELOAD_MILLIS = 2_000L

/**
 * Whether [message] is a finished tool call that may have changed the session's files: an edit
 * or a write that succeeded, or any finished bash command.
 */
internal fun mayChangeFiles(message: JsonObject): Boolean {
    // A malformed message is the timeline's to report; here it changes nothing.
    fun text(key: String) = (message[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (text("role") != "tool") return false
    val state = text("state")
    return when (text("toolName")) {
        "edit", "write" -> state == "complete"
        "bash" -> state == "complete" || state == "error"
        else -> false
    }
}

/** How many statuses [GitChangesLoader] keeps for an instant reopen. */
private const val MAX_REMEMBERED_STATUSES = 8

/**
 * Loads the changes view into [RemoteState.changes]. Session start is the default base and falls
 * back to HEAD when the host has none; a `not_found` (the snapshot expired after ten minutes)
 * requests a fresh status once and repeats the read. The last status of each session and base
 * stays in memory, so the view opens on it at once and reads again in place.
 */
internal class GitChangesLoader(
    private val scope: CoroutineScope,
    private val source: GitSource,
    private val current: () -> RemoteState,
    private val now: () -> Long = System::currentTimeMillis,
    private val update: ((RemoteState) -> RemoteState) -> Unit,
) {
    /** What a read of one session's status at one base showed, with its log. */
    private data class Remembered(
        val status: GitStatus,
        val sessionUnavailable: GitUnavailable?,
        val log: GitLog?,
        val devBase: GitDevBase?,
        val fetchedAt: Long,
    )

    /** By Mac, session and the base the user asked for ([ChangesState.base]). */
    private val remembered = NavigationMemoryCache<Triple<String?, String, GitBase>, Remembered>(MAX_REMEMBERED_STATUSES)
    private var liveJob: Job? = null
    private val previewSlots = Semaphore(2)
    private val previewJobs = mutableMapOf<String, Job>()
    private var previewVersion = 0L
    private var statusJob: Job? = null
    private var diffJob: Job? = null
    private var statusVersion = 0L
    private var diffVersion = 0L
    private var subagentJob: Job? = null
    private var subagentVersion = 0L
    /** Subagents to ask once the session's own status and log are in, with a file to reopen. */
    private var pendingSubagents: Pair<String, Pair<String, String>?>? = null

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
        if (e.isUnsupported) ChangesFailure.UNSUPPORTED
        else when (e.hostCode) {
            "not_found" -> ChangesFailure.NOT_FOUND
            "forbidden" -> ChangesFailure.FORBIDDEN
            "busy" -> ChangesFailure.BUSY
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

    /**
     * Opens the view for the selected session with [comments] from its draft, at [base]. [file]
     * opens once the status lists it: the view as a return to the session left it.
     */
    fun open(comments: List<ReviewComment>, base: GitBase = GitBase.SESSION, file: String? = null) {
        stop()
        val state = current()
        val sessionId = state.selection.sessionId ?: return
        val failure =
            when {
                !canViewChanges(state) -> ChangesFailure.UNSUPPORTED
                !state.connected -> ChangesFailure.OFFLINE
                else -> null
            }
        val offline = failure == ChangesFailure.OFFLINE
        // The last status read shows at once, offline too, and a connected view reads it again in place.
        val known = remembered[key(state, sessionId, base)].takeIf { failure == null || offline }
        val reopened = file?.takeIf { failure == null || (known != null && known.status.lists(it)) }
        update {
            it.copy(
                changes = ChangesState(
                    sessionId,
                    base = base,
                    loading = failure == null && known == null,
                    failure = failure.takeIf { known == null },
                    file = reopened,
                    diffLoading = reopened != null && !offline,
                    diffFailure = ChangesFailure.OFFLINE.takeIf { reopened != null && offline },
                    comments = comments,
                ).shown(known)
            )
        }
        stopSubagents()
        if (failure == null) {
            pendingSubagents = sessionId to null
            // Only session start falls back to HEAD; another base was the user's own choice.
            loadStatus(sessionId, base, fallback = base == GitBase.SESSION, silent = known != null)
        }
    }

    private fun key(state: RemoteState, sessionId: String, base: GitBase) =
        Triple(state.selection.routeId, sessionId, base)

    private fun GitStatus.lists(path: String) = files.any { it.path == path }

    /** This view showing what [known] showed, when it is known. */
    private fun ChangesState.shown(known: Remembered?): ChangesState =
        if (known == null) this
        else copy(
            status = known.status,
            sessionUnavailable = known.sessionUnavailable,
            log = known.log,
            devBase = known.devBase ?: devBase,
            fetchedAt = known.fetchedAt,
        )

    /** Keeps what [sessionId]'s view shows now for its next opening. */
    private fun remember(sessionId: String) {
        val changes = changes()?.takeIf { it.sessionId == sessionId } ?: return
        val status = changes.status ?: return
        if (changes.failure != null || changes.loading) return
        remembered[key(current(), sessionId, changes.base)] =
            Remembered(
                status,
                changes.sessionUnavailable,
                changes.log?.takeIf { !changes.logLoading && changes.logFailure == null },
                changes.devBase,
                changes.fetchedAt ?: now(),
            )
    }

    /**
     * The agent may have changed the session's files (a tool finished, a run ended): reads the
     * shown status again in place [CHANGES_LIVE_RELOAD_MILLIS] after the last such hint. A read
     * that started after the hint already sees the change; one still running from before it is
     * waited for, so there is only ever one read. A view that shows a failure or nothing waits
     * for Reload, and an offline one for the reconnect.
     */
    fun reloadSoon() {
        val sessionId = changes()?.sessionId ?: return
        val hinted = statusVersion
        liveJob?.cancel()
        liveJob = scope.launch {
            delay(CHANGES_LIVE_RELOAD_MILLIS)
            if (statusVersion == hinted) statusJob?.takeIf { it.isActive }?.join()
            if (statusVersion != hinted) return@launch
            val changes = changes()?.takeIf { it.sessionId == sessionId } ?: return@launch
            if (!current().connected || changes.status == null || changes.failure != null) return@launch
            reload(retry = true, silent = true)
        }
    }

    /** Drops every remembered status: the Mac is gone, or its navigation starts over. */
    fun forget() = remembered.clear()

    fun close() {
        stop()
        stopSubagents()
        liveJob?.cancel()
        liveJob = null
        update { it.copy(changes = null) }
    }

    private fun stopSubagents() {
        pendingSubagents = null
        subagentVersion++
        subagentJob?.cancel()
        subagentJob = null
    }

    /** Shows [base]; an explicit choice of session start does not fall back to HEAD. */
    fun selectBase(base: GitBase) {
        val changes = changes() ?: return
        stop()
        val offline = !current().connected
        val known = remembered[key(current(), changes.sessionId, base)]
        write(changes.sessionId) {
            it.copy(
                base = base,
                previews = emptyMap(),
                loading = !offline && known == null,
                status = null,
                sessionUnavailable = null,
                failure = if (offline && known == null) ChangesFailure.OFFLINE else null,
                file = null,
                fileSession = null,
                diff = null,
                diffLoading = false,
                diffFailure = null,
                log = null,
                logLoading = false,
                logFailure = null,
                fetchedAt = null,
                stale = false,
            ).shown(known)
        }
        if (!offline) loadStatus(changes.sessionId, base, fallback = false, silent = known != null)
    }

    /**
     * Reloads the status of the shown base, keeping the open file when it is still listed and the
     * previews of files whose change the new status still lists the same.
     */
    fun reload() = reload(retry = true)

    /**
     * Reads the shown base again in place, as after a reconnect: the status, diff and log on
     * screen stay until fresher ones replace them, and a failure leaves them, marked
     * [ChangesState.stale]. A view with nothing shown yet reloads.
     */
    fun refresh() {
        val changes = changes() ?: return
        reload(retry = true, silent = changes.status != null && changes.failure == null)
    }

    private fun reload(retry: Boolean, silent: Boolean = false) {
        val changes = changes() ?: return
        if (!current().connected) {
            write(changes.sessionId) { it.copy(failure = ChangesFailure.OFFLINE) }
            return
        }
        stop()
        val base = changes.status?.base ?: changes.base
        // A preview still loading lost its request with stop(); a loaded one stays.
        write(changes.sessionId) {
            val previews = it.previews.filterValues { preview -> !preview.loading && preview.failure == null }
            if (silent) it.copy(previews = previews)
            else it.copy(loading = true, failure = null, stale = false, previews = previews)
        }
        // A subagent file shown now reopens from that subagent's fresh snapshot.
        val subagentFile = changes.file?.let { path -> changes.fileSession?.let { it to path } }
        stopSubagents()
        pendingSubagents = changes.sessionId to subagentFile
        loadStatus(
            changes.sessionId,
            base,
            fallback = changes.status == null && base == GitBase.SESSION,
            retry = retry,
            silent = silent,
        )
    }

    private fun loadStatus(
        sessionId: String,
        base: GitBase,
        fallback: Boolean,
        retry: Boolean = true,
        silent: Boolean = false,
    ) {
        val version = ++statusVersion
        fun active() = version == statusVersion && changes()?.sessionId == sessionId
        statusJob = scope.launch {
            try {
                loadStatusAndLog(sessionId, base, fallback, retry, silent, ::active)
            } finally {
                // The subagents wait for the session's own status and log, so their requests do not
                // compete with those for the host's per-device limit, and are not asked at all when
                // the session's own status failed.
                val loaded = changes()?.let { it.status != null && it.failure == null } == true
                if (version == statusVersion && loaded) startPendingSubagents()
            }
        }
    }

    private fun startPendingSubagents() {
        val (sessionId, reopen) = pendingSubagents ?: return
        pendingSubagents = null
        loadSubagents(sessionId, reopen)
    }

    private suspend fun loadStatusAndLog(
        sessionId: String,
        base: GitBase,
        fallback: Boolean,
        retry: Boolean,
        silent: Boolean,
        active: () -> Boolean,
    ) {
        run {
            val status =
                try {
                    var status = source.gitStatus(sessionId, base)
                    var sessionUnavailable: GitUnavailable? = null
                    if (fallback && base == GitBase.SESSION && status.unavailable in fallbackReasons) {
                        sessionUnavailable = status.unavailable
                        status = source.gitStatus(sessionId, GitBase.HEAD)
                    }
                    if (!active()) return
                    val shownLog = status.available && status.base != GitBase.HEAD
                    write(sessionId) { changes ->
                        // A subagent's file reads that subagent's snapshot; this status leaves it alone.
                        val own = changes.fileSession == null
                        // The file open now, not at the request: a back press meanwhile stays.
                        val file =
                            if (!own) changes.file
                            else changes.file?.takeIf { path -> status.files.any { it.path == path } }
                        // A silent read keeps the open diff and the log until fresher ones arrive.
                        val keptDiff = changes.diff?.takeIf { silent && own && file != null && it.path == file }
                        val keptLog = changes.log?.takeIf { silent && shownLog }
                        // A preview stays while its file's change is listed the same.
                        val before = changes.status?.files.orEmpty().associateBy { it.path }
                        val listed = status.files.associateBy { it.path }
                        changes.copy(
                            loading = false,
                            status = status,
                            previews = changes.previews.filterKeys { path ->
                                listed[path] != null && listed[path] == before[path]
                            },
                            // A reload of the HEAD fallback still explains why it shows HEAD.
                            sessionUnavailable = sessionUnavailable
                                ?: changes.sessionUnavailable.takeIf {
                                    changes.base == GitBase.SESSION && status.base == GitBase.HEAD
                                },
                            failure = null,
                            file = file,
                            diff = if (own) keptDiff else changes.diff,
                            diffLoading = if (own) file != null && keptDiff == null else changes.diffLoading,
                            diffFailure = if (own) null else changes.diffFailure,
                            log = if (status.available && !shownLog) GitLog() else keptLog,
                            logLoading = shownLog && keptLog == null,
                            logFailure = null,
                            // Base dev gone since the last status: the repository has no such branch any more.
                            devBase = status.devBase
                                ?: GitDevBase(null).takeIf {
                                    status.base == GitBase.DEV && status.unavailable == GitUnavailable.BASE_UNAVAILABLE
                                }
                                ?: changes.devBase,
                            fetchedAt = now(),
                            stale = false,
                        )
                    }
                    remember(sessionId)
                    status
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Diagnostics.caught("git.loadStatusAndLog", e)
                    // A silent read that failed leaves what is shown, marked as not current, and Reload
                    // tries again. A file reopened with the remembered status waited for this read.
                    if (active())
                        write(sessionId) {
                            if (!silent) it.copy(loading = false, failure = failure(e))
                            else {
                                val waiting = it.fileSession == null && it.file != null && it.diff == null && it.diffLoading
                                it.copy(
                                    loading = false,
                                    stale = it.status != null && it.failure == null,
                                    diffLoading = it.diffLoading && !waiting,
                                    diffFailure = if (waiting) failure(e) else it.diffFailure,
                                )
                            }
                        }
                    return
                }
            val snapshotId = status.snapshotId ?: return
            changes()?.takeIf { it.fileSession == null }?.file?.let { path ->
                loadDiff(sessionId, snapshotId, path, retry = false, silent = silent)
            }
            if (!status.available || status.base == GitBase.HEAD) return
            try {
                val log = source.gitLog(sessionId, snapshotId)
                if (active()) {
                    write(sessionId) { it.copy(log = log, logLoading = false) }
                    remember(sessionId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.caught("git.loadStatusAndLog.2", e)
                if (!active()) return
                if (failure(e) == ChangesFailure.NOT_FOUND && retry) {
                    // The snapshot expired between status and log: one fresh status repeats both.
                    reload(retry = false, silent = silent)
                    return
                }
                // A silent read that failed leaves the log shown.
                write(sessionId) {
                    it.copy(logLoading = false, logFailure = if (silent && it.log != null) null else failure(e))
                }
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
                Diagnostics.caught("git.requestPreview", e)
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

    /**
     * Opens [path] from the changes of the subagent [subagentId]; its diff reads that subagent's
     * snapshot. [keep] leaves that file's diff on screen until the new one replaces it.
     */
    fun openSubagentFile(subagentId: String, path: String, retry: Boolean = true, keep: Boolean = false) {
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
        val kept =
            changes.diff?.takeIf { keep && changes.file == path && changes.fileSession == subagentId && it.path == path }
        write(changes.sessionId) {
            it.copy(
                file = path,
                fileSession = subagentId,
                diff = kept,
                diffLoading = !offline && kept == null,
                diffFailure = if (offline && kept == null) ChangesFailure.OFFLINE else null,
            )
        }
        if (!offline) loadDiff(changes.sessionId, snapshotId, path, retry, reader = subagentId, silent = kept != null)
    }

    /**
     * Asks the session's subagents (running first, then the newest, at most [MAX_SUBAGENT_GROUPS])
     * for their changes against HEAD, one after another, after the session's own status and log.
     * A subagent the host has no changes for, or that fails, gets no group. [reopen] opens that
     * subagent file again once its fresh snapshot is in.
     */
    private fun loadSubagents(sessionId: String, reopen: Pair<String, String>? = null) {
        val version = ++subagentVersion
        subagentJob?.cancel()
        val now = current()
        val children =
            sessionListItems(now.sessions, now.connected, now.loading)
                .filter { it.parentSessionId == sessionId && it.id != sessionId }
                .sortedWith(compareByDescending<SessionListItem> { it.live() }.thenByDescending { it.updatedAt ?: 0L })
                .take(MAX_SUBAGENT_GROUPS)
        if (children.isEmpty()) {
            write(sessionId) { it.copy(subagents = emptyList()) }
            finishReopen(sessionId, emptyList(), reopen)
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
                        Diagnostics.caught("git.loadSubagents", e)
                        null
                    }
                if (version != subagentVersion) return@launch
                if (status != null && status.available && status.files.isNotEmpty())
                    found += SubagentChanges(child.id, child.title, child.live(), status)
            }
            write(sessionId) { it.copy(subagents = found) }
            finishReopen(sessionId, found, reopen)
        }
    }

    /**
     * Reopens a subagent file that is still shown from the subagent's fresh snapshot, or ends its
     * loading with `not_found` when that subagent no longer lists it (taken over, gone, failed).
     */
    private fun finishReopen(sessionId: String, found: List<SubagentChanges>, reopen: Pair<String, String>?) {
        val (subagentId, path) = reopen ?: return
        if (changes()?.let { it.file == path && it.fileSession == subagentId } != true) return
        val listed = found.firstOrNull { it.sessionId == subagentId }?.status?.files.orEmpty().any { it.path == path }
        if (listed) openSubagentFile(subagentId, path, retry = false, keep = true)
        else write(sessionId) { it.copy(diff = null, diffLoading = false, diffFailure = ChangesFailure.NOT_FOUND) }
    }

    /** [reader] is the session whose snapshot [snapshotId] is: the shown one, or one of its subagents. */
    private fun loadDiff(
        sessionId: String,
        snapshotId: String,
        path: String,
        retry: Boolean,
        reader: String = sessionId,
        silent: Boolean = false,
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
                Diagnostics.caught("git.loadDiff", e)
                if (!active()) return@launch
                val reason = failure(e)
                if (reason == ChangesFailure.NOT_FOUND && retry) {
                    // Snapshots expire after ten minutes: take a fresh one and reopen the file.
                    when {
                        subagent == null -> reload(retry = false)
                        // A reload in flight asks the subagents next; it takes the reopen along.
                        pendingSubagents?.first == sessionId -> pendingSubagents = sessionId to (subagent to path)
                        else -> loadSubagents(sessionId, reopen = subagent to path)
                    }
                    return@launch
                }
                // A silent read that failed leaves the diff shown.
                write(sessionId) {
                    it.copy(diffLoading = false, diffFailure = if (silent && it.diff?.path == path) null else reason)
                }
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
