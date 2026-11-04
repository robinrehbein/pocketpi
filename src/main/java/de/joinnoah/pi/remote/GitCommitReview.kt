package de.joinnoah.pi.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

data class GitCommitConfirmation(val branch: String, val paths: List<String>, val message: String, val requestId: String, val snapshotId: String)
data class GitCommitDraft(val message: String = "", val pending: GitCommitConfirmation? = null)

internal fun GitCommitDraft.json(): JsonObject = Wire.objectOf("message" to message, "pending" to pending?.let {
    Wire.objectOf("branch" to it.branch, "paths" to JsonArray(it.paths.map(::JsonPrimitive)), "message" to it.message,
        "requestId" to it.requestId, "snapshotId" to it.snapshotId)
})
internal fun parseGitCommitDraft(value: JsonObject): GitCommitDraft {
    val message = value.text("message")
    require(message.toByteArray(Charsets.UTF_8).size <= 16384)
    val pending = (value["pending"] as? JsonObject)?.let { item ->
        val result = GitCommitConfirmation(item.text("branch"), item.getValue("paths").jsonArray.map { it.jsonPrimitive.content },
            item.text("message"), item.text("requestId"), item.text("snapshotId"))
        require(result.branch.isNotBlank() && result.paths.isNotEmpty())
        encodeGitWriteCommand(GitWriteCommand.Commit(result.requestId, "stored", result.snapshotId, result.message, result.paths))
        result
    }
    return GitCommitDraft(message, pending)
}

internal enum class GitCommitReviewError { UNAVAILABLE, INVALID_MESSAGE, STORAGE, REQUEST }
internal data class GitCommitReviewState(
    val open: Boolean = false, val loading: Boolean = false, val snapshot: GitStatus? = null,
    val selected: Set<String> = emptySet(), val message: String = "",
    val confirmation: GitCommitConfirmation? = null, val sending: Boolean = false,
    val result: GitWriteResult.Commit? = null, val error: GitCommitReviewError? = null,
    val invalidated: Boolean = false,
)

internal class GitCommitReviewController(
    private val repository: RemoteRepository,
    private val scope: CoroutineScope,
    private val targetSelection: RemoteSelection,
) {
    private val mutable = MutableStateFlow(GitCommitReviewState())
    val state: StateFlow<GitCommitReviewState> = mutable
    private val storage = Mutex()
    private var generation = 0L
    private var authorityGeneration: Long? = null
    private var draft = GitCommitDraft()
    private fun eligible(): Boolean = repository.state.value.let {
        it.connected && it.selection == targetSelection && targetSelection.sessionId != null &&
            GIT_CAPABILITY in it.capabilities && GIT_CAPABILITY !in it.unavailableCapabilities &&
            GIT_WRITE_CAPABILITY in it.capabilities && GIT_WRITE_CAPABILITY !in it.unavailableCapabilities &&
            (authorityGeneration == null || it.gitReviewGeneration == authorityGeneration)
    }
    init {
        scope.launch {
            repository.state.collect {
                if (!eligible() && mutable.value.open) {
                    generation++
                    mutable.value = mutable.value.copy(loading = false, snapshot = null, selected = emptySet(),
                        confirmation = null, invalidated = true)
                }
            }
        }
    }
    fun prepare() {
        if (mutable.value.loading || mutable.value.sending) return
        val serial = ++generation
        authorityGeneration = repository.state.value.gitReviewGeneration
        mutable.value = mutable.value.copy(open = true, loading = true, snapshot = null, selected = emptySet(), confirmation = null, error = null, invalidated = false)
        scope.launch {
            try {
                val saved = storage.withLock { repository.gitCommitDraft(targetSelection).also { draft = it } }
                if (serial != generation) return@launch
                mutable.value = mutable.value.copy(message = saved.message, result = if (saved.pending != null) GitWriteResult.Commit.Unknown else null)
                if (!eligible()) { mutable.value = mutable.value.copy(loading = false, invalidated = true); return@launch }
                val snapshot = repository.gitCommitStatus(checkNotNull(targetSelection.sessionId))
                if (serial != generation || !eligible()) return@launch
                mutable.value = mutable.value.copy(loading = false, snapshot = snapshot, error = if (!snapshot.available || snapshot.snapshotId == null || snapshot.branch == null) GitCommitReviewError.UNAVAILABLE else null)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (serial == generation) mutable.value = mutable.value.copy(loading = false, error = GitCommitReviewError.REQUEST) }
        }
    }
    fun close() { generation++; mutable.value = mutable.value.copy(open = false, loading = false, confirmation = null) }
    fun toggle(path: String) {
        val current = mutable.value
        if (!eligible() || current.loading || current.sending || current.confirmation != null || current.result == GitWriteResult.Commit.Unknown) return
        if (current.snapshot?.files?.none { it.path == path && it.omitted == null && !it.submodule } != false) return
        mutable.value = current.copy(selected = if (path in current.selected) current.selected - path else current.selected + path)
    }
    fun setMessage(text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > 16384 || !mutable.value.open || mutable.value.loading || mutable.value.sending || draft.pending != null) return
        mutable.value = mutable.value.copy(message = text, error = null)
        scope.launch {
            try { storage.withLock { draft = draft.copy(message = text); repository.saveGitCommitDraft(targetSelection, draft) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(error = GitCommitReviewError.STORAGE) }
        }
    }
    fun review() {
        val current = mutable.value
        val snapshot = current.snapshot ?: return
        if (!eligible() || current.loading || current.sending || current.invalidated || draft.pending != null || current.selected.isEmpty()) return
        val branch = snapshot.branch ?: return
        val id = snapshot.snapshotId ?: return
        val paths = snapshot.files.map { it.path }.filter { it in current.selected }
        val confirmation = GitCommitConfirmation(branch, paths, current.message, Wire.random(), id)
        try { encodeGitWriteCommand(GitWriteCommand.Commit(confirmation.requestId, checkNotNull(targetSelection.sessionId), id, confirmation.message, paths)) }
        catch (_: Exception) { mutable.value = current.copy(error = GitCommitReviewError.INVALID_MESSAGE); return }
        mutable.value = current.copy(confirmation = confirmation, error = null)
    }
    fun backToEdit() { if (!mutable.value.sending) mutable.value = mutable.value.copy(confirmation = null) }
    /** Called only after the user has checked the branch/history and explicitly acknowledged uncertainty. */
    fun acknowledgeUnknown() {
        if (mutable.value.sending || mutable.value.result != GitWriteResult.Commit.Unknown || draft.pending == null) return
        mutable.value = mutable.value.copy(sending = true, error = null)
        scope.launch {
            try {
                storage.withLock {
                    val cleared = draft.copy(pending = null)
                    repository.saveGitCommitDraft(targetSelection, cleared)
                    draft = cleared
                }
                generation++
                mutable.value = mutable.value.copy(sending = false, result = null, snapshot = null,
                    selected = emptySet(), confirmation = null, invalidated = true)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(sending = false, error = GitCommitReviewError.STORAGE) }
        }
    }
    fun confirm() {
        val current = mutable.value
        val confirmed = current.confirmation ?: return
        if (!eligible() || current.sending || current.invalidated || draft.pending != null) return
        mutable.value = current.copy(sending = true, error = null)
        scope.launch {
            var dispatched = false
            try {
                storage.withLock {
                    val pending = GitCommitDraft(confirmed.message, confirmed)
                    repository.saveGitCommitDraft(targetSelection, pending)
                    draft = pending
                }
                if (!eligible() || mutable.value.invalidated) {
                    storage.withLock { draft = draft.copy(pending = null); repository.saveGitCommitDraft(targetSelection, draft) }
                    mutable.value = mutable.value.copy(sending = false, invalidated = true, confirmation = null)
                    return@launch
                }
                val result = repository.gitCommit(checkNotNull(targetSelection.sessionId), confirmed.snapshotId, confirmed.message, confirmed.paths, confirmed.requestId) { dispatched = true }
                mutable.value = mutable.value.copy(sending = false, result = result, confirmation = null, snapshot = null, selected = emptySet())
                if (result != GitWriteResult.Commit.Unknown) try {
                    storage.withLock {
                        val cleared = draft.copy(pending = null)
                        repository.saveGitCommitDraft(targetSelection, cleared)
                        draft = cleared
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    mutable.value = mutable.value.copy(error = GitCommitReviewError.STORAGE)
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    if (!dispatched) try {
                        storage.withLock {
                            val cleared = draft.copy(pending = null)
                            repository.saveGitCommitDraft(targetSelection, cleared)
                            draft = cleared
                        }
                    } catch (_: Exception) { /* Keep an unresolved intent on storage failure. */ }
                    mutable.value = mutable.value.copy(sending = false,
                        result = mutable.value.result ?: if (dispatched) GitWriteResult.Commit.Unknown else null,
                        confirmation = null)
                }
                throw e
            } catch (e: RemoteRequestException) {
                val uncertain = dispatched && (!e.answered || e.code == "outcome_unknown" || e.reason == "outcome_unknown")
                if (!uncertain) try {
                    storage.withLock {
                        val cleared = draft.copy(pending = null)
                        repository.saveGitCommitDraft(targetSelection, cleared)
                        draft = cleared
                    }
                } catch (_: Exception) { /* The stored intent remains conservatively unresolved. */ }
                mutable.value = mutable.value.copy(sending = false, result = if (uncertain) GitWriteResult.Commit.Unknown else null,
                    confirmation = null, error = if (uncertain) null else GitCommitReviewError.REQUEST)
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(sending = false, result = if (dispatched) GitWriteResult.Commit.Unknown else null,
                    confirmation = null, error = if (dispatched) null else GitCommitReviewError.STORAGE)
            }
        }
    }
}
