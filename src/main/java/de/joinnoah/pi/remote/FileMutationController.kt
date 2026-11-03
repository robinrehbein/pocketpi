package de.joinnoah.pi.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the user is asked before a create, rename or delete is sent. */
data class FileMutationDialog(
    val kind: FileMutationKind,
    /** The folder a file is created in, or the file renamed or deleted. */
    val path: String,
    /** The fresh read of the file before rename or delete is still running. */
    val checking: Boolean = false,
    /** The version that fresh read found; rename and delete are sent against it. */
    val version: String? = null,
    /** Why the operation cannot be confirmed now. */
    val failure: FilesFailure? = null,
    /** An unsaved or unconfirmed edit, or an unresolved operation, holds this file. */
    val blocked: Boolean = false,
    /** The file changed since the dialog read it; confirming again uses the new version. */
    val changed: Boolean = false,
    /** A rename name, or a create name, the host already lists in the folder. */
    val nameTaken: Boolean = false,
    val sending: Boolean = false,
) {
    /** Whether the confirm action may run: rename and delete only after a successful fresh read. */
    val confirmable: Boolean
        get() = !checking && !sending && !blocked && failure == null &&
            (kind == FileMutationKind.CREATE || version != null)
}

enum class FileMutationStatus {
    /** Sent; the host has not answered with an outcome yet. */
    PENDING,
    APPLIED,
    /** The host refused before any effect, with [FileMutationOutcome.reason]. */
    REFUSED,
    /** Not run: the app or host could not take the request ([FileMutationOutcome.failure]). */
    FAILED,
    /** May or may not have happened; see the recovery list. */
    UNKNOWN,
}

/** The outcome of the last operation of this session, until dismissed. */
data class FileMutationOutcome(
    val kind: FileMutationKind,
    val path: String,
    val destinationPath: String? = null,
    val status: FileMutationStatus,
    val reason: FileMutationRefusal? = null,
    val failure: FilesFailure? = null,
    val operationId: String? = null,
)

/** An operation whose outcome is unknown; only an explicit acknowledgement removes it. */
data class FileMutationRecoveryItem(
    val operationId: String,
    val kind: FileMutationKind,
    val path: String,
    val destinationPath: String? = null,
    /** The host's journal epoch changed since: its journal was lost, so it can never tell. */
    val lost: Boolean = false,
    /** The epoch the acknowledgement is sent with. */
    val epoch: String,
    val acknowledging: Boolean = false,
    val failure: FilesFailure? = null,
    /** The host has not finished it yet: only its status can be asked for again, never acknowledged. */
    val pending: Boolean = false,
    /** Its status is being asked for. */
    val checking: Boolean = false,
    /** This device's journal holds it; an operation only the host lists is false. */
    val local: Boolean = true,
    /** The host's current journal never held it although its epoch is current: it never arrived, nothing ran. */
    val neverReceived: Boolean = false,
)

data class FileMutationsState(
    val routeId: String,
    val sessionId: String,
    val dialog: FileMutationDialog? = null,
    val outcome: FileMutationOutcome? = null,
    val recovery: List<FileMutationRecoveryItem> = emptyList(),
    /** The local operation journal could not be written; nothing is sent. */
    val storageFailed: Boolean = false,
    /**
     * The local journal cannot be read: this phone lost its record of open operations. Nothing is
     * sent until the user confirms a reset ([resetConfirming]); the host's list still shows its own.
     */
    val journalUnreadable: Boolean = false,
    val resetConfirming: Boolean = false,
    val resetting: Boolean = false,
    /** The journal holds its maximum of entries; [journalEntries] of [journalCapacity] are in use. */
    val journalFull: Boolean = false,
    val journalEntries: Int = 0,
    val journalCapacity: Int = 0,
)

/** Whether [kind] can be offered: its capability, recovery, a connected session and this client's journal. */
internal fun canMutateFiles(state: RemoteState, kind: FileMutationKind): Boolean =
    canBrowseFiles(state) && state.connected &&
        fileMutationsOf(state)?.let { !it.journalUnreadable && !it.storageFailed } == true &&
        kind.capability in state.capabilities && kind.capability !in state.unavailableCapabilities &&
        FILES_MUTATION_RECOVERY_CAPABILITY in state.capabilities &&
        FILES_MUTATION_RECOVERY_CAPABILITY !in state.unavailableCapabilities

internal fun canRecoverFileMutations(state: RemoteState): Boolean =
    canBrowseFiles(state) && fileMutationsOf(state) != null &&
        FILES_MUTATION_RECOVERY_CAPABILITY in state.capabilities &&
        FILES_MUTATION_RECOVERY_CAPABILITY !in state.unavailableCapabilities

/**
 * Whether an operation that is unresolved, or being sent, holds [path]: editing it then would
 * leave the editor on a file that may move or vanish.
 */
internal fun fileMutationHolds(state: RemoteState, path: String): Boolean {
    val mutations = fileMutationsOf(state) ?: return false
    val dialog = mutations.dialog?.takeIf { it.sending }
    val outcome = mutations.outcome?.takeIf { it.status == FileMutationStatus.PENDING }
    return mutations.recovery.any { path == it.path || path == it.destinationPath } ||
        (dialog != null && dialog.path == path) ||
        (outcome != null && (outcome.path == path || outcome.destinationPath == path))
}

/** The mutation state of the selected session, or null. */
internal fun fileMutationsOf(state: RemoteState): FileMutationsState? =
    state.fileMutations?.takeIf {
        it.routeId == state.selection.routeId && it.sessionId == state.selection.sessionId
    }

/** The host side of file mutations. Implementations throw [RemoteRequestException] on errors. */
internal interface FileMutationSource {
    suspend fun mutationList(sessionId: String, after: String?): FileMutationList

    /** Sends [request]; [onSent] runs right before the frame may leave, so its absence proves it did not. */
    suspend fun mutate(request: FileMutationRequest, connection: Long, onSent: () -> Unit): FileMutationRecord

    suspend fun mutationStatus(request: FileMutationRequest): FileMutationRecord

    suspend fun acknowledge(sessionId: String, item: FileMutationRecoveryItem): FileMutationRecord

    /** `session.files.mutation.status` of an operation only known from the recovery list. */
    suspend fun itemStatus(sessionId: String, item: FileMutationRecoveryItem): FileMutationRecord

    suspend fun read(sessionId: String, path: String): FileChunk
}

/**
 * Creates, renames and deletes files of the shown session. Every request is journaled before it
 * is sent, with the host's journal epoch fetched just before; an operation without an answer is
 * only ever asked about (`session.files.mutation.status`), never sent again. A pending operation
 * whose epoch the host no longer has is lost: its outcome is unknown. Unknown operations stay in
 * [FileMutationsState.recovery] until the user acknowledges them. Rows change only after the
 * host confirmed the operation: [applied] refreshes the browser then.
 */
internal class FileMutationController(
    private val scope: CoroutineScope,
    private val source: FileMutationSource,
    private val journal: FileMutationJournal,
    private val current: () -> RemoteState,
    /** True when an edit, a pending save or a kept draft holds one of the paths. */
    private val draftsHold: suspend (routeId: String, sessionId: String, paths: List<String>) -> Boolean,
    private val applied: (FileMutationRequest) -> Unit,
    private val update: ((RemoteState) -> RemoteState) -> Unit,
    private val pollDelayMillis: Long = 1_000,
    /** The connection a request goes out on; one confirmed on another is not sent. */
    private val connection: () -> Long = { 0L },
) {
    private val work = Mutex()
    private var dialogVersion = 0L
    /** At most one status poll per operation ID. */
    private val polls = mutableMapOf<String, Job>()
    /** The host's journal epoch as last listed, by route and session. */
    private val knownEpochs = mutableMapOf<Pair<String, String>, String>()

    private data class Scope(val routeId: String, val deviceId: String, val sessionId: String)

    private fun scopeOf(state: RemoteState = current()): Scope? {
        val routeId = state.selection.routeId ?: return null
        val sessionId = state.selection.sessionId ?: return null
        val deviceId = state.host?.takeIf { it.routeId == routeId }?.deviceId ?: return null
        return Scope(routeId, deviceId, sessionId)
    }

    private fun write(target: Scope, block: (FileMutationsState) -> FileMutationsState) = update { state ->
        val mutations = state.fileMutations
        if (state.selection.routeId != target.routeId || state.selection.sessionId != target.sessionId) state
        else state.copy(
            fileMutations = block(
                mutations?.takeIf { it.routeId == target.routeId && it.sessionId == target.sessionId }
                    ?: FileMutationsState(target.routeId, target.sessionId)
            )
        )
    }

    private fun failure(e: Throwable): FilesFailure =
        when {
            e.isUnsupported -> FilesFailure.UNSUPPORTED
            e.hostCode == "forbidden" -> FilesFailure.FORBIDDEN
            e.hostCode == "busy" -> FilesFailure.BUSY
            e.hostCode == "not_found" -> FilesFailure.NOT_FOUND
            e.hostCode == "invalid_path" -> FilesFailure.INVALID_PATH
            e.hostCode == "offline" || !current().connected -> FilesFailure.OFFLINE
            else -> FilesFailure.FAILED
        }

    /** Shows the mutation state of the selected session and asks the host about open operations. */
    fun open() {
        val target = scopeOf() ?: return
        write(target) { it }
        scope.launch {
            // Read the journal at once, also offline: an unreadable one shows its recovery notice.
            val readable = work.withLock { readable(target) }
            if (readable) write(target) { it.copy(journalUnreadable = false, storageFailed = false) }
            reconcile()
        }
    }

    private suspend fun readable(target: Scope): Boolean =
        try {
            journal.pending(target.routeId, target.deviceId, target.sessionId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.caught("files.mutation.journal", e)
            write(target) { it.copy(journalUnreadable = true) }
            false
        }

    // ---- Dialogs -----------------------------------------------------------------------------

    fun prepareCreate(folder: String) {
        val state = current()
        val target = scopeOf(state) ?: return
        if (!canMutateFiles(state, FileMutationKind.CREATE) || fileMutationsOf(state)?.dialog?.sending == true) return
        dialogVersion++
        write(target) { it.copy(dialog = FileMutationDialog(FileMutationKind.CREATE, folder)) }
    }

    /** Opens the rename or delete prompt for [path] and reads the file afresh for its version. */
    fun prepare(kind: FileMutationKind, path: String) {
        require(kind != FileMutationKind.CREATE)
        val state = current()
        val target = scopeOf(state) ?: return
        if (!canMutateFiles(state, kind) || !validMutationPath(path) || fileMutationsOf(state)?.dialog?.sending == true) return
        val version = ++dialogVersion
        write(target) { it.copy(dialog = FileMutationDialog(kind, path, checking = true)) }
        scope.launch { preflight(target, version, kind, path) }
    }

    private fun dialogActive(target: Scope, version: Long, kind: FileMutationKind, path: String): Boolean =
        version == dialogVersion && scopeOf() == target &&
            fileMutationsOf(current())?.dialog?.let { it.kind == kind && it.path == path } == true

    private suspend fun preflight(target: Scope, version: Long, kind: FileMutationKind, path: String) {
        val blocked = holds(target, listOf(path))
        try {
            val chunk = source.read(target.sessionId, path)
            if (!dialogActive(target, version, kind, path)) return
            write(target) {
                it.copy(dialog = it.dialog?.copy(checking = false, version = chunk.version, blocked = blocked, failure = null))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.caught("files.mutation.preflight", e)
            if (!dialogActive(target, version, kind, path)) return
            write(target) { it.copy(dialog = it.dialog?.copy(checking = false, version = null, blocked = blocked, failure = failure(e))) }
        }
    }

    /** Whether a draft or an unresolved operation holds any of [paths]; a failed check holds them. */
    private suspend fun holds(target: Scope, paths: List<String>): Boolean {
        val drafts = try {
            draftsHold(target.routeId, target.sessionId, paths)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.caught("files.mutation.drafts", e)
            true
        }
        if (drafts) return true
        val unresolved = try {
            journal.pending(target.routeId, target.deviceId, target.sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.caught("files.mutation.journal", e)
            write(target) { it.copy(journalUnreadable = true) }
            return true
        }
        if (unresolved.any { entry -> listOfNotNull(entry.request.path, entry.request.destinationPath).any { it in paths } }) return true
        // Also what only the host lists (after a reset, say): every unresolved operation shown.
        val shown = current().fileMutations?.takeIf { it.routeId == target.routeId && it.sessionId == target.sessionId }?.recovery.orEmpty()
        return shown.any { item -> listOfNotNull(item.path, item.destinationPath).any { it in paths } }
    }

    /** An edit was saved or discarded: a prompt it blocked checks again, rename and delete with a fresh read. */
    fun draftsChanged() {
        val state = current()
        val target = scopeOf(state) ?: return
        val dialog = fileMutationsOf(state)?.dialog ?: return
        if (!dialog.blocked || dialog.checking || dialog.sending) return
        if (dialog.kind != FileMutationKind.CREATE) prepare(dialog.kind, dialog.path)
        else {
            val version = dialogVersion
            scope.launch {
                val blocked = holds(target, listOf(dialog.path))
                if (version == dialogVersion) write(target) { it.copy(dialog = it.dialog?.copy(blocked = blocked)) }
            }
        }
    }

    fun cancel() {
        val target = scopeOf() ?: return
        if (fileMutationsOf(current())?.dialog?.sending == true) return
        dialogVersion++
        write(target) { it.copy(dialog = null) }
    }

    fun dismissOutcome() {
        val target = scopeOf() ?: return
        write(target) { it.copy(outcome = it.outcome?.takeIf { outcome -> outcome.status == FileMutationStatus.PENDING }) }
    }

    /**
     * Confirms the open dialog: [name] is the new file's name for create and the new name for
     * rename; delete takes none. Rename and delete read the file again first: a different version
     * than the dialog showed is not sent, the dialog asks again instead.
     */
    fun confirm(name: String? = null) {
        val state = current()
        val target = scopeOf(state) ?: return
        val dialog = fileMutationsOf(state)?.dialog ?: return
        if (!dialog.confirmable || !canMutateFiles(state, dialog.kind)) return
        val (path, destination) =
            when (dialog.kind) {
                FileMutationKind.CREATE -> {
                    if (name == null || !validMutationName(name)) return
                    childPath(dialog.path, name) to null
                }
                FileMutationKind.RENAME -> {
                    if (name == null || !validMutationName(name)) return
                    val destination = childPath(parentPath(dialog.path), name)
                    if (destination == dialog.path) return
                    dialog.path to destination
                }
                FileMutationKind.DELETE -> dialog.path to null
            }
        val listed = state.files?.takeIf { it.sessionId == target.sessionId && it.path == parentPath(destination ?: path) }
            ?.listing?.entries?.map { it.name }.orEmpty()
        val newPath = if (dialog.kind == FileMutationKind.DELETE) null else destination ?: path
        if (newPath != null && newPath.substringAfterLast('/') in listed) {
            write(target) { it.copy(dialog = it.dialog?.copy(nameTaken = true)) }
            return
        }
        val version = ++dialogVersion
        val link = connection()
        write(target) { it.copy(dialog = dialog.copy(sending = true, nameTaken = false, changed = false)) }
        scope.launch {
            work.withLock { submit(target, link, version, dialog, path, destination) }
        }
    }

    private fun reopen(target: Scope, version: Long, block: (FileMutationDialog) -> FileMutationDialog) {
        if (version != dialogVersion) return
        write(target) { it.copy(dialog = it.dialog?.let(block)?.copy(sending = false)) }
    }

    /** Whether [target] is still shown on connection [link]: else nothing is reserved or sent. */
    private fun still(target: Scope, link: Long) = scopeOf() == target && connection() == link && current().connected

    private suspend fun submit(target: Scope, link: Long, version: Long, dialog: FileMutationDialog, path: String, destination: String?) {
        if (holds(target, listOfNotNull(path, destination))) {
            reopen(target, version) { it.copy(blocked = true) }
            return
        }
        // A fresh read right before sending: the confirmed version must still be the file's.
        var baseVersion: String? = null
        if (dialog.kind != FileMutationKind.CREATE) {
            val chunk = try {
                source.read(target.sessionId, path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.caught("files.mutation.recheck", e)
                reopen(target, version) { it.copy(failure = failure(e), version = null) }
                return
            }
            if (chunk.version != dialog.version) {
                reopen(target, version) { it.copy(version = chunk.version, changed = true) }
                return
            }
            baseVersion = chunk.version
        }
        val epoch = try {
            reconcileLocked(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.caught("files.mutation.epoch", e)
            reopen(target, version) { it.copy(failure = if (e is StorageFailure) FilesFailure.STORAGE else failure(e)) }
            return
        }
        // Reconciling may have found an unresolved operation on one of these paths.
        if (holds(target, listOfNotNull(path, destination))) {
            reopen(target, version) { it.copy(blocked = true) }
            return
        }
        val request = FileMutationRequest(
            target.routeId, target.deviceId, target.sessionId, Wire.random(), epoch, dialog.kind, path, destination, baseVersion,
        )
        if (!validMutationRequest(request)) {
            reopen(target, version) { it.copy(failure = FilesFailure.INVALID_PATH) }
            return
        }
        if (!still(target, link)) {
            reopen(target, version) { it.copy(failure = FilesFailure.OFFLINE) }
            return
        }
        try {
            journal.reserve(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileMutationJournalFull) {
            Diagnostics.caught("files.mutation.full", e)
            refreshCapacity(target)
            reopen(target, version) { it.copy(failure = FilesFailure.STORAGE) }
            return
        } catch (e: Exception) {
            // Never send what is not journaled.
            Diagnostics.caught("files.mutation.reserve", e)
            write(target) { it.copy(storageFailed = true) }
            reopen(target, version) { it.copy(failure = FilesFailure.STORAGE) }
            return
        }
        if (version == dialogVersion) write(target) { it.copy(dialog = null) }
        write(target) {
            it.copy(outcome = FileMutationOutcome(request.kind, path, destination, FileMutationStatus.PENDING, operationId = request.operationId))
        }
        refreshCapacity(target)
        send(target, request, link)
    }

    /** Shows how full the journal is; a full one offers its way out. */
    private suspend fun refreshCapacity(target: Scope) {
        val size = try { journal.size() } catch (e: CancellationException) { throw e } catch (e: Exception) { return }
        write(target) { it.copy(journalFull = size >= journal.capacity, journalEntries = size, journalCapacity = journal.capacity) }
    }

    private suspend fun send(target: Scope, request: FileMutationRequest, link: Long) {
        var sent = false
        try {
            val record = source.mutate(request, link) { sent = true }
            withContext(NonCancellable) { settle(target, request, record, poll = true) }
        } catch (e: Throwable) {
            val code = (e as? RemoteRequestException)?.takeIf { it.answered }?.code
            withContext(NonCancellable) {
                when {
                    // The frame never left, or the host refused it before reserving anything (a
                    // reused ID or its store full): nothing ran. Any other error after sending, such
                    // as authority lost after the host's reservation, may hide an effect.
                    !sent || code in SAFE_ERRORS -> {
                        Diagnostics.caught("files.mutation.send", e)
                        val code = (e as? RemoteRequestException)?.code ?: "not_sent"
                        val receipt = runCatching {
                            journal.complete(request, FileMutationResult.Refused(code))
                            journal.forget(FileMutationKey(request.deviceId, request.operationId))
                        }
                        if (receipt.isFailure) write(target) { it.copy(storageFailed = true) }
                        showOutcome(target, request, FileMutationStatus.FAILED, failure = failure(e))
                    }
                    // No usable answer: the host may have it. Only its status is asked for, later.
                    else -> {
                        Diagnostics.caught("files.mutation.unanswered", e)
                        addRecovery(target, item(request).copy(pending = true))
                    }
                }
            }
            if (e is CancellationException) throw e
        }
    }

    private fun showOutcome(
        target: Scope,
        request: FileMutationRequest,
        status: FileMutationStatus,
        reason: FileMutationRefusal? = null,
        failure: FilesFailure? = null,
    ) = write(target) {
        // Only the operation the outcome shows, or one learned on recovery while none is shown.
        when (it.outcome?.operationId) {
            request.operationId -> it.copy(outcome = it.outcome.copy(status = status, reason = reason, failure = failure))
            null -> it.copy(outcome = FileMutationOutcome(request.kind, request.path, request.destinationPath, status, reason, failure, request.operationId))
            else -> it
        }
    }

    /** Records what the host answered for [request]; a pending one is asked about again shortly. */
    private suspend fun settle(target: Scope, request: FileMutationRequest, record: FileMutationRecord, poll: Boolean) {
        val key = FileMutationKey(request.deviceId, request.operationId)
        if (record.state != FileMutationHostState.PENDING) stopPoll(request.operationId)
        // Settled meanwhile by another answer: nothing is recorded or shown twice.
        val entry = try {
            journal.get(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.caught("files.mutation.settle", e)
            write(target) { it.copy(journalUnreadable = true) }
            return
        }
        if (entry == null || entry.state !in FileMutationJournal.UNRESOLVED) return
        try {
            when (record.state) {
                FileMutationHostState.APPLIED -> {
                    journal.complete(request, FileMutationResult.Applied)
                    journal.forget(key)
                }
                FileMutationHostState.REFUSED -> {
                    journal.complete(request, FileMutationResult.Refused(checkNotNull(record.reason).wire))
                    journal.forget(key)
                }
                FileMutationHostState.UNKNOWN -> {
                    journal.markUnknown(key)
                    // The host keeps the acknowledged tombstone; this device needs no record of it.
                    if (record.acknowledged) {
                        journal.acknowledgeUnknown(key)
                        journal.forget(key)
                    }
                }
                FileMutationHostState.PENDING -> Unit
            }
        } catch (e: Exception) {
            Diagnostics.caught("files.mutation.settle", e)
            write(target) { it.copy(storageFailed = true) }
        }
        when (record.state) {
            FileMutationHostState.APPLIED -> {
                removeRecovery(target, request.operationId)
                showOutcome(target, request, FileMutationStatus.APPLIED)
                if (scopeOf() == target) applied(request)
            }
            FileMutationHostState.REFUSED -> {
                removeRecovery(target, request.operationId)
                showOutcome(target, request, FileMutationStatus.REFUSED, record.reason)
            }
            FileMutationHostState.UNKNOWN -> {
                showOutcome(target, request, FileMutationStatus.UNKNOWN)
                if (record.acknowledged) removeRecovery(target, request.operationId) else addRecovery(target, item(request))
            }
            FileMutationHostState.PENDING -> {
                addRecovery(target, item(request).copy(pending = true))
                if (poll) poll(target, request)
            }
        }
    }

    private fun item(request: FileMutationRequest, lost: Boolean = false) =
        FileMutationRecoveryItem(request.operationId, request.kind, request.path, request.destinationPath, lost, request.epoch)

    private fun addRecovery(target: Scope, item: FileMutationRecoveryItem) = write(target) { state ->
        val index = state.recovery.indexOfFirst { it.operationId == item.operationId }
        state.copy(recovery = if (index < 0) state.recovery + item else state.recovery.toMutableList().also { it[index] = item })
    }

    private fun removeRecovery(target: Scope, operationId: String) = write(target) { state ->
        state.copy(recovery = state.recovery.filter { it.operationId != operationId })
    }

    /**
     * The host's record of [request], or null when its current journal holds no such operation
     * (`not_found`: another epoch, or never reserved). Then the outcome is lost; nothing is resent.
     */
    private suspend fun statusOf(target: Scope, request: FileMutationRequest): FileMutationRecord? =
        try {
            source.mutationStatus(request)
        } catch (e: RemoteRequestException) {
            if (e.hostCode != "not_found") throw e
            try {
                journal.markLost(FileMutationKey(request.deviceId, request.operationId))
            } catch (storage: Exception) {
                Diagnostics.caught("files.mutation.lost", storage)
                write(target) { it.copy(storageFailed = true) }
            }
            stopPoll(request.operationId)
            val never = knownEpochs[target.routeId to target.sessionId] == request.epoch
            addRecovery(target, item(request, lost = true).copy(neverReceived = never))
            showOutcome(target, request, FileMutationStatus.UNKNOWN)
            null
        }

    private suspend fun stopPoll(operationId: String) {
        val self = currentCoroutineContext()[Job]
        polls.remove(operationId)?.let { if (it !== self) it.cancel() }
    }

    /** Asks for [request]'s status a few times while it stays reserved; one poll per operation. */
    private fun poll(target: Scope, request: FileMutationRequest) {
        if (polls[request.operationId]?.isActive == true) return
        val key = FileMutationKey(request.deviceId, request.operationId)
        lateinit var job: Job
        job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                repeat(MAX_POLLS) {
                    delay(pollDelayMillis)
                    if (scopeOf() != target || !current().connected) return@launch
                    val done = try {
                        work.withLock {
                            // Settled elsewhere: nothing left to ask about.
                            if (journal.get(key)?.state != FileMutationState.RESERVED) true
                            else {
                                val record = statusOf(target, request)
                                if (record == null) true
                                else {
                                    settle(target, request, record, poll = false)
                                    record.state != FileMutationHostState.PENDING
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Diagnostics.caught("files.mutation.poll", e)
                        true
                    }
                    if (done) return@launch
                }
            } finally {
                if (polls[request.operationId] === job) polls.remove(request.operationId)
            }
        }
        polls[request.operationId] = job
        job.start()
    }

    // ---- Recovery ----------------------------------------------------------------------------

    private class StorageFailure(cause: Exception) : Exception(cause)

    /** Asks the host about every operation of this session it has not settled; sends no mutation. */
    fun reconcile() {
        val state = current()
        val target = scopeOf(state) ?: return
        if (!state.connected || !canRecoverFileMutations(state)) return
        scope.launch {
            try {
                work.withLock { reconcileLocked(target) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.caught("files.mutation.reconcile", e)
            }
        }
    }

    /**
     * Lists the host's unresolved operations and its current epoch, then settles this device's
     * journal against them: an operation from another epoch is lost, one still reserved is asked
     * about. Returns the epoch for a new operation.
     */
    private suspend fun reconcileLocked(target: Scope): String {
        val pages = mutableListOf<FileMutationList>()
        var after: String? = null
        do {
            val page = source.mutationList(target.sessionId, after)
            // Pages of one listing share one epoch; a change midway means the host started over.
            require(pages.isEmpty() || pages.first().epoch == page.epoch) { "Epoch changed while listing" }
            pages += page
            after = page.nextAfter
        } while (after != null && pages.size < MAX_LIST_PAGES)
        val epoch = pages.first().epoch
        knownEpochs[target.routeId to target.sessionId] = epoch
        val host = pages.flatMap { it.operations }.associateBy { it.operationId }
        // An unreadable journal still lets the host's own unresolved operations show.
        var unreadable: Exception? = null
        val local = try {
            journal.pending(target.routeId, target.deviceId, target.sessionId)
        } catch (e: Exception) {
            Diagnostics.caught("files.mutation.journal", e)
            write(target) { it.copy(journalUnreadable = true) }
            unreadable = e
            emptyList()
        }
        // Acknowledged entries left by an earlier run that stopped before dropping them.
        if (unreadable == null) try {
            journal.forgetSettled(target.routeId, target.deviceId, target.sessionId)
        } catch (e: Exception) {
            Diagnostics.caught("files.mutation.release", e)
            write(target) { it.copy(storageFailed = true) }
        }
        val recovery = mutableListOf<FileMutationRecoveryItem>()
        for (entry in local) {
            val request = entry.request
            val key = FileMutationKey(request.deviceId, request.operationId)
            if (request.epoch != epoch) {
                // The host lost the journal this operation was recorded in: never resend it.
                try { journal.markLost(key) } catch (e: Exception) { write(target) { it.copy(storageFailed = true) }; throw StorageFailure(e) }
                recovery += FileMutationRecoveryItem(request.operationId, request.kind, request.path, request.destinationPath, lost = true, epoch = request.epoch)
                continue
            }
            when (entry.state) {
                FileMutationState.RESERVED -> {
                    val record = statusOf(target, request)
                    if (record == null) {
                        recovery += item(request, lost = true).copy(neverReceived = true)
                        continue
                    }
                    // A pending one is asked about again shortly, and stays listed until then.
                    settle(target, request, record, poll = true)
                    when {
                        record.state == FileMutationHostState.UNKNOWN && !record.acknowledged -> recovery += item(request)
                        record.state == FileMutationHostState.PENDING -> recovery += item(request).copy(pending = true)
                    }
                }
                FileMutationState.UNKNOWN ->
                    if (host[request.operationId]?.acknowledged == true) {
                        try {
                            journal.acknowledgeUnknown(key)
                            journal.forget(key)
                        } catch (e: Exception) { write(target) { it.copy(storageFailed = true) } }
                    } else recovery += FileMutationRecoveryItem(request.operationId, request.kind, request.path, request.destinationPath, epoch = epoch)
                FileMutationState.LOST ->
                    recovery += FileMutationRecoveryItem(request.operationId, request.kind, request.path, request.destinationPath, lost = true, epoch = request.epoch,
                        neverReceived = request.epoch == epoch)
                else -> Unit
            }
        }
        // Unknown operations the host holds for this device that this journal no longer has.
        val known = local.map { it.request.operationId }.toSet()
        host.values.filter { !it.acknowledged && it.operationId !in known }.forEach {
            recovery += FileMutationRecoveryItem(it.operationId, it.kind, it.path, it.destinationPath, epoch = epoch,
                pending = it.state == FileMutationHostState.PENDING, local = false)
        }
        write(target) { state ->
            val acknowledging = state.recovery.filter { it.acknowledging }.map { it.operationId }.toSet()
            state.copy(recovery = recovery.map { it.copy(acknowledging = it.operationId in acknowledging) })
        }
        if (unreadable != null) throw StorageFailure(unreadable)
        refreshCapacity(target)
        return epoch
    }

    /**
     * Acknowledges that the user saw [operationId]'s unknown outcome. Sends
     * `session.files.mutation.acknowledge`, never the operation again. A lost operation's host
     * journal is gone, so it is acknowledged on this device alone.
     */
    fun acknowledge(operationId: String) {
        val state = current()
        val target = scopeOf(state) ?: return
        val item = fileMutationsOf(state)?.recovery?.firstOrNull { it.operationId == operationId } ?: return
        if (item.acknowledging || item.pending) return
        if (!item.lost && (!state.connected || !canRecoverFileMutations(state))) return
        write(target) { it.copy(recovery = it.recovery.map { entry -> if (entry.operationId == operationId) entry.copy(acknowledging = true, failure = null) else entry }) }
        scope.launch {
            work.withLock {
                val key = FileMutationKey(target.deviceId, operationId)
                try {
                    if (!item.lost) {
                        // not_found: the host's current journal does not hold it, so there is nothing to acknowledge there.
                        val record = try {
                            source.acknowledge(target.sessionId, item)
                        } catch (e: RemoteRequestException) {
                            if (e.hostCode != "not_found") throw e
                            null
                        }
                        if (record != null) check(record.state == FileMutationHostState.UNKNOWN && record.acknowledged) { "Not acknowledged" }
                    }
                    // An operation only the host lists has nothing to change here. Once acknowledged, the
                    // host keeps the tombstone (or a lost epoch can never run again): drop it here.
                    if (item.local) {
                        journal.acknowledgeUnknown(key)
                        journal.forget(key)
                    }
                    write(target) { it.copy(recovery = it.recovery.filter { entry -> entry.operationId != operationId }) }
                    refreshCapacity(target)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Diagnostics.caught("files.mutation.acknowledge", e)
                    val reason = if (e is RemoteRequestException) failure(e) else FilesFailure.FAILED
                    write(target) {
                        it.copy(recovery = it.recovery.map { entry ->
                            if (entry.operationId == operationId) entry.copy(acknowledging = false, failure = reason) else entry
                        })
                    }
                }
            }
        }
    }

    /**
     * Asks the host again about a pending operation of the recovery list
     * (`session.files.mutation.status`); never sends the operation itself.
     */
    fun checkAgain(operationId: String) {
        val state = current()
        val target = scopeOf(state) ?: return
        val item = fileMutationsOf(state)?.recovery?.firstOrNull { it.operationId == operationId } ?: return
        if (!item.pending || item.checking || !state.connected || !canRecoverFileMutations(state)) return
        fun mark(checking: Boolean, failure: FilesFailure?) = write(target) {
            it.copy(recovery = it.recovery.map { entry -> if (entry.operationId == operationId) entry.copy(checking = checking, failure = failure) else entry })
        }
        mark(true, null)
        scope.launch {
            work.withLock {
                try {
                    val request = if (item.local) journal.pending(target.routeId, target.deviceId, target.sessionId)
                        .firstOrNull { it.request.operationId == operationId }?.request else null
                    if (request != null) {
                        statusOf(target, request)?.let { settle(target, request, it, poll = false) }
                    } else {
                        val record = try {
                            source.itemStatus(target.sessionId, item)
                        } catch (e: RemoteRequestException) {
                            if (e.hostCode != "not_found") throw e
                            addRecovery(target, item.copy(pending = false, checking = false, lost = true, failure = null))
                            return@withLock
                        }
                        when (record.state) {
                            FileMutationHostState.PENDING -> mark(false, null)
                            FileMutationHostState.UNKNOWN ->
                                if (record.acknowledged) removeRecovery(target, operationId)
                                else addRecovery(target, item.copy(pending = false, checking = false, failure = null))
                            else -> removeRecovery(target, operationId)
                        }
                        return@withLock
                    }
                    write(target) { it.copy(recovery = it.recovery.map { entry -> if (entry.operationId == operationId) entry.copy(checking = false) else entry }) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Diagnostics.caught("files.mutation.check", e)
                    mark(false, if (e is RemoteRequestException) failure(e) else FilesFailure.FAILED)
                }
            }
        }
    }

    /** Asks to confirm discarding an unreadable journal. */
    fun requestReset() {
        val target = scopeOf() ?: return
        val mutations = fileMutationsOf(current()) ?: return
        if (!mutations.journalUnreadable && !mutations.journalFull) return
        write(target) { it.copy(resetConfirming = true) }
    }

    fun cancelReset() {
        val target = scopeOf() ?: return
        write(target) { it.copy(resetConfirming = false) }
    }

    /**
     * Discards the unreadable journal and starts an empty one, after the user confirmed that this
     * phone lost its record of open operations. Nothing is sent for them; the host's own
     * unresolved operations are listed again for acknowledgement.
     */
    fun confirmReset() {
        val target = scopeOf() ?: return
        val mutations = fileMutationsOf(current()) ?: return
        if (!mutations.resetConfirming || !(mutations.journalUnreadable || mutations.journalFull) || mutations.resetting) return
        write(target) { it.copy(resetting = true) }
        scope.launch {
            work.withLock {
                try {
                    journal.reset()
                    journal.pending(target.routeId, target.deviceId, target.sessionId)
                    write(target) { it.copy(journalUnreadable = false, storageFailed = false, resetConfirming = false, resetting = false, recovery = emptyList(), journalFull = false) }
                    refreshCapacity(target)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Diagnostics.caught("files.mutation.reset", e)
                    write(target) { it.copy(resetting = false, resetConfirming = false) }
                    return@withLock
                }
            }
            reconcile()
        }
    }

    private companion object {
        /** Errors the host answers before it reserves anything, so the operation never ran. */
        val SAFE_ERRORS = setOf("invalid_request", "busy")
        const val MAX_POLLS = 5
        const val MAX_LIST_PAGES = 20
    }
}
