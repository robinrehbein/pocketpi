package de.joinnoah.pi.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
)

data class FileMutationsState(
    val routeId: String,
    val sessionId: String,
    val dialog: FileMutationDialog? = null,
    val outcome: FileMutationOutcome? = null,
    val recovery: List<FileMutationRecoveryItem> = emptyList(),
    /** The local operation journal could not be read or written; nothing is sent. */
    val storageFailed: Boolean = false,
)

/** Whether [kind] can be offered: its capability, recovery, a connected session and this client's journal. */
internal fun canMutateFiles(state: RemoteState, kind: FileMutationKind): Boolean =
    canBrowseFiles(state) && state.connected && fileMutationsOf(state) != null &&
        kind.capability in state.capabilities && kind.capability !in state.unavailableCapabilities &&
        FILES_MUTATION_RECOVERY_CAPABILITY in state.capabilities &&
        FILES_MUTATION_RECOVERY_CAPABILITY !in state.unavailableCapabilities

internal fun canRecoverFileMutations(state: RemoteState): Boolean =
    canBrowseFiles(state) && fileMutationsOf(state) != null &&
        FILES_MUTATION_RECOVERY_CAPABILITY in state.capabilities &&
        FILES_MUTATION_RECOVERY_CAPABILITY !in state.unavailableCapabilities

/** The mutation state of the selected session, or null. */
internal fun fileMutationsOf(state: RemoteState): FileMutationsState? =
    state.fileMutations?.takeIf {
        it.routeId == state.selection.routeId && it.sessionId == state.selection.sessionId
    }

/** The host side of file mutations. Implementations throw [RemoteRequestException] on errors. */
internal interface FileMutationSource {
    suspend fun mutationList(sessionId: String, after: String?): FileMutationList

    /** Sends [request]; [onSent] runs right before the frame may leave, so its absence proves it did not. */
    suspend fun mutate(request: FileMutationRequest, onSent: () -> Unit): FileMutationRecord

    suspend fun mutationStatus(request: FileMutationRequest): FileMutationRecord

    suspend fun acknowledge(sessionId: String, item: FileMutationRecoveryItem): FileMutationRecord

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
    private val draftsHold: suspend (sessionId: String, paths: List<String>) -> Boolean,
    private val applied: (FileMutationRequest) -> Unit,
    private val update: ((RemoteState) -> RemoteState) -> Unit,
    private val pollDelayMillis: Long = 1_000,
) {
    private val work = Mutex()
    private var dialogVersion = 0L

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
        reconcile()
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
            draftsHold(target.sessionId, paths)
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
            write(target) { it.copy(storageFailed = true) }
            return true
        }
        return unresolved.any { entry -> listOfNotNull(entry.request.path, entry.request.destinationPath).any { it in paths } }
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
        write(target) { it.copy(dialog = dialog.copy(sending = true, nameTaken = false, changed = false)) }
        scope.launch {
            work.withLock { submit(target, version, dialog, path, destination) }
        }
    }

    private fun reopen(target: Scope, version: Long, block: (FileMutationDialog) -> FileMutationDialog) {
        if (version != dialogVersion) return
        write(target) { it.copy(dialog = it.dialog?.let(block)?.copy(sending = false)) }
    }

    private suspend fun submit(target: Scope, version: Long, dialog: FileMutationDialog, path: String, destination: String?) {
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
        try {
            journal.reserve(request)
        } catch (e: CancellationException) {
            throw e
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
        send(target, request)
    }

    private suspend fun send(target: Scope, request: FileMutationRequest) {
        var sent = false
        try {
            val record = source.mutate(request) { sent = true }
            withContext(NonCancellable) { settle(target, request, record, shown = true) }
        } catch (e: Throwable) {
            val answered = (e as? RemoteRequestException)?.answered == true
            withContext(NonCancellable) {
                when {
                    // The host answered with an error before recording anything, or the frame never
                    // left: nothing ran.
                    answered || !sent -> {
                        Diagnostics.caught("files.mutation.send", e)
                        val code = (e as? RemoteRequestException)?.code ?: "not_sent"
                        val receipt = runCatching {
                            journal.complete(request, FileMutationResult.Refused(code))
                            journal.forget(FileMutationKey(request.deviceId, request.operationId))
                        }
                        if (receipt.isFailure) write(target) { it.copy(storageFailed = true) }
                        showOutcome(target, request, FileMutationStatus.FAILED, failure = failure(e))
                    }
                    // No answer: the host may have it. Only its status is asked for later.
                    else -> Diagnostics.caught("files.mutation.unanswered", e)
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
        // Only the operation the outcome shows; a later one replaced it.
        if (it.outcome?.operationId != request.operationId) it
        else it.copy(outcome = it.outcome.copy(status = status, reason = reason, failure = failure))
    }

    /** Records what the host answered for [request]; a pending one is asked about again shortly. */
    private suspend fun settle(target: Scope, request: FileMutationRequest, record: FileMutationRecord, shown: Boolean) {
        val key = FileMutationKey(request.deviceId, request.operationId)
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
                    if (record.acknowledged) journal.acknowledgeUnknown(key)
                }
                FileMutationHostState.PENDING -> Unit
            }
        } catch (e: Exception) {
            Diagnostics.caught("files.mutation.settle", e)
            write(target) { it.copy(storageFailed = true) }
        }
        when (record.state) {
            FileMutationHostState.APPLIED -> {
                showOutcome(target, request, FileMutationStatus.APPLIED)
                if (scopeOf() == target) applied(request)
            }
            FileMutationHostState.REFUSED -> showOutcome(target, request, FileMutationStatus.REFUSED, record.reason)
            FileMutationHostState.UNKNOWN -> {
                showOutcome(target, request, FileMutationStatus.UNKNOWN)
                if (!record.acknowledged) addRecovery(target, request, lost = false)
            }
            FileMutationHostState.PENDING -> if (shown) poll(target, request)
        }
    }

    private fun addRecovery(target: Scope, request: FileMutationRequest, lost: Boolean) = write(target) { state ->
        val item = FileMutationRecoveryItem(request.operationId, request.kind, request.path, request.destinationPath, lost, request.epoch)
        state.copy(recovery = state.recovery.filter { it.operationId != request.operationId } + item)
    }

    private fun poll(target: Scope, request: FileMutationRequest) {
        scope.launch {
            repeat(MAX_POLLS) {
                delay(pollDelayMillis)
                if (scopeOf() != target || !current().connected) return@launch
                val record = try {
                    work.withLock { source.mutationStatus(request) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Diagnostics.caught("files.mutation.poll", e)
                    return@launch
                }
                settle(target, request, record, shown = false)
                if (record.state != FileMutationHostState.PENDING) return@launch
            }
        }
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
        val host = pages.flatMap { it.operations }.associateBy { it.operationId }
        val local = try {
            journal.pending(target.routeId, target.deviceId, target.sessionId)
        } catch (e: Exception) {
            write(target) { it.copy(storageFailed = true) }
            throw StorageFailure(e)
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
                    val record = source.mutationStatus(request)
                    settle(target, request, record, shown = false)
                    if (record.state == FileMutationHostState.UNKNOWN && !record.acknowledged)
                        recovery += FileMutationRecoveryItem(request.operationId, request.kind, request.path, request.destinationPath, epoch = epoch)
                }
                FileMutationState.UNKNOWN ->
                    if (host[request.operationId]?.acknowledged == true) {
                        try { journal.acknowledgeUnknown(key) } catch (e: Exception) { write(target) { it.copy(storageFailed = true) } }
                    } else recovery += FileMutationRecoveryItem(request.operationId, request.kind, request.path, request.destinationPath, epoch = epoch)
                FileMutationState.LOST ->
                    recovery += FileMutationRecoveryItem(request.operationId, request.kind, request.path, request.destinationPath, lost = true, epoch = request.epoch)
                else -> Unit
            }
        }
        // Unknown operations the host holds for this device that this journal no longer has.
        val known = local.map { it.request.operationId }.toSet()
        host.values.filter { it.state == FileMutationHostState.UNKNOWN && !it.acknowledged && it.operationId !in known }
            .forEach { recovery += FileMutationRecoveryItem(it.operationId, it.kind, it.path, it.destinationPath, epoch = epoch) }
        write(target) { state ->
            val acknowledging = state.recovery.filter { it.acknowledging }.map { it.operationId }.toSet()
            state.copy(recovery = recovery.map { it.copy(acknowledging = it.operationId in acknowledging) })
        }
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
        if (item.acknowledging) return
        if (!item.lost && (!state.connected || !canRecoverFileMutations(state))) return
        write(target) { it.copy(recovery = it.recovery.map { entry -> if (entry.operationId == operationId) entry.copy(acknowledging = true, failure = null) else entry }) }
        scope.launch {
            work.withLock {
                val key = FileMutationKey(target.deviceId, operationId)
                try {
                    if (!item.lost) {
                        val record = source.acknowledge(target.sessionId, item)
                        check(record.state == FileMutationHostState.UNKNOWN && record.acknowledged) { "Not acknowledged" }
                    }
                    try {
                        journal.acknowledgeUnknown(key)
                    } catch (e: IllegalStateException) {
                        // Only the host had it: nothing to change here.
                        if (!e.message.orEmpty().contains("not reserved")) throw e
                    }
                    write(target) { it.copy(recovery = it.recovery.filter { entry -> entry.operationId != operationId }) }
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

    private companion object {
        const val MAX_POLLS = 5
        const val MAX_LIST_PAGES = 20
    }
}
