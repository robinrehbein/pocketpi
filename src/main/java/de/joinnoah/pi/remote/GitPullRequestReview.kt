package de.joinnoah.pi.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull

data class GitPullRequestDraft(val base: String? = null, val title: String = "", val body: String = "", val draft: Boolean = true)
internal fun GitPullRequestDraft.json(): JsonObject = Wire.objectOf("base" to base, "title" to title, "body" to body, "draft" to draft)
internal fun parseGitPullRequestDraft(value: JsonObject): GitPullRequestDraft {
    Wire.keys(value, setOf("title", "body", "draft"), setOf("base"))
    val base = if (value["base"] == null || value["base"] is JsonNull) null else value.text("base")
    val title = value.text("title"); val body = value.text("body")
    require((base == null || base.toByteArray(Charsets.UTF_8).size <= 256) && title.toByteArray(Charsets.UTF_8).size <= 256 && body.toByteArray(Charsets.UTF_8).size <= 32768)
    return GitPullRequestDraft(base, title, body, value.flag("draft"))
}

internal enum class GitPullRequestReviewError { UNAVAILABLE, STORAGE, REQUEST, INVALID_DRAFT }
internal data class GitPullRequestReviewState(
    val open: Boolean = false, val loading: Boolean = false,
    val draft: GitPullRequestDraft = GitPullRequestDraft(),
    val preparation: GitWriteResult.Branch.Available? = null,
    val confirmation: GitPullRequestIntent? = null, val sending: Boolean = false,
    val result: GitWriteResult.PullRequest? = null, val pending: GitPullRequestIntent? = null,
    val recovery: GitWriteResult.PullRequestStatus? = null, val checking: Boolean = false,
    val canCheckRemote: Boolean = false, val error: GitPullRequestReviewError? = null,
    val invalidated: Boolean = false,
)

internal class GitPullRequestReviewController(
    private val repository: RemoteRepository,
    private val scope: CoroutineScope,
    private val targetSelection: RemoteSelection,
) {
    private val mutable = MutableStateFlow(GitPullRequestReviewState())
    val state: StateFlow<GitPullRequestReviewState> = mutable
    private val storage = Mutex()
    private var generation = 0L
    private var authorityGeneration: Long? = null
    private var pending: GitPullRequestIntent? = null
    private var hydrated = false
    private var hydrating = false
    private var editDraft = GitPullRequestDraft()
    private var savedDraft = false
    private fun has(capability: String): Boolean = repository.state.value.let {
        it.connected && it.capabilitiesKnown && it.selection == targetSelection && targetSelection.sessionId != null &&
            capability in it.capabilities && capability !in it.unavailableCapabilities
    }
    private fun current(serial: Long, capability: String): Boolean = serial == generation && has(capability) &&
        repository.state.value.gitReviewGeneration == authorityGeneration
    private fun refreshCheck() {
        mutable.value = mutable.value.copy(canCheckRemote = mutable.value.open && pending != null &&
            !mutable.value.checking && !mutable.value.sending && has(SESSION_GIT_PULL_REQUEST_STATUS_CAPABILITY))
    }
    private fun hydrate() {
        if (hydrated || hydrating || mutable.value.loading || mutable.value.sending ||
            repository.state.value.selection != targetSelection || targetSelection.routeId == null || targetSelection.sessionId == null) return
        hydrating = true
        val serial = generation
        val authority = repository.state.value.gitReviewGeneration
        scope.launch {
            try {
                val (saved, draft) = storage.withLock { repository.gitPullRequestIntent(targetSelection) to repository.gitPullRequestDraft(targetSelection) }
                if (serial == generation && repository.state.value.selection == targetSelection &&
                    repository.state.value.gitReviewGeneration == authority && !mutable.value.loading && !mutable.value.sending) {
                    pending = saved; hydrated = true; savedDraft = draft != null; editDraft = draft ?: GitPullRequestDraft()
                    mutable.value = mutable.value.copy(draft = editDraft, pending = saved, result = if (saved != null) GitWriteResult.PullRequest.Unknown else null)
                    refreshCheck()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (serial == generation) { hydrated = true; mutable.value = mutable.value.copy(error = GitPullRequestReviewError.STORAGE) } }
            finally { hydrating = false; if (!hydrated && !mutable.value.loading && !mutable.value.sending) hydrate() }
        }
    }
    init {
        scope.launch {
            repository.state.collect {
                if (mutable.value.open && authorityGeneration != null &&
                    (it.selection != targetSelection || it.gitReviewGeneration != authorityGeneration || !it.connected)) {
                    generation++
                    mutable.value = mutable.value.copy(loading = false, preparation = null, confirmation = null, checking = false, invalidated = true)
                }
                refreshCheck()
                hydrate()
            }
        }
    }
    fun prepare() {
        if (mutable.value.loading || mutable.value.sending || mutable.value.checking) return
        val serial = ++generation
        authorityGeneration = repository.state.value.gitReviewGeneration
        mutable.value = mutable.value.copy(open = true, loading = true, preparation = null, confirmation = null, recovery = null, error = null, invalidated = false)
        scope.launch {
            try {
                val (saved, draft) = storage.withLock { repository.gitPullRequestIntent(targetSelection) to repository.gitPullRequestDraft(targetSelection) }
                if (serial != generation || repository.state.value.selection != targetSelection || repository.state.value.gitReviewGeneration != authorityGeneration) return@launch
                pending = saved; hydrated = true; savedDraft = draft != null; editDraft = draft ?: GitPullRequestDraft()
                mutable.value = mutable.value.copy(draft = editDraft, pending = saved, result = if (saved != null) GitWriteResult.PullRequest.Unknown else null)
                refreshCheck()
                if (saved != null) { mutable.value = mutable.value.copy(loading = false); return@launch }
                if (!current(serial, SESSION_GIT_PULL_REQUEST_CAPABILITY)) { mutable.value = mutable.value.copy(loading = false, invalidated = true); return@launch }
                val result = repository.gitPullRequestPrepare(checkNotNull(targetSelection.sessionId))
                if (!current(serial, SESSION_GIT_PULL_REQUEST_CAPABILITY)) return@launch
                val available = result as? GitWriteResult.Branch.Available
                if (available?.head != null && !savedDraft) {
                    val suggested = editDraft.copy(base = available.pullRequestBases.firstOrNull(), title = available.suggestedTitle ?: "")
                    storage.withLock { repository.saveGitPullRequestDraft(targetSelection, suggested); editDraft = suggested; savedDraft = true }
                    if (!current(serial, SESSION_GIT_PULL_REQUEST_CAPABILITY)) return@launch
                }
                mutable.value = mutable.value.copy(draft = editDraft, loading = false, preparation = available?.takeIf { it.head != null }, error = if (available?.head == null) GitPullRequestReviewError.UNAVAILABLE else null)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (serial == generation) mutable.value = mutable.value.copy(loading = false, error = GitPullRequestReviewError.REQUEST) }
        }
    }
    fun close() { generation++; mutable.value = mutable.value.copy(open = false, loading = false, confirmation = null, checking = false, canCheckRemote = false) }
    private fun edit(transform: (GitPullRequestDraft) -> GitPullRequestDraft) {
        val value = mutable.value
        if (!value.open || value.loading || value.sending || value.confirmation != null || pending != null) return
        val updated = transform(value.draft)
        try { parseGitPullRequestDraft(updated.json()) } catch (_: Exception) { mutable.value = value.copy(error = GitPullRequestReviewError.INVALID_DRAFT); return }
        editDraft = updated; savedDraft = true
        mutable.value = value.copy(draft = updated, error = null)
        // Enter the non-cancellable section before returning to the caller: cancellation of the
        // screen scope must not discard an edit already shown to the user, even while queued.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try { storage.withLock { repository.saveGitPullRequestDraft(targetSelection, updated) } }
                catch (_: Exception) { mutable.value = mutable.value.copy(error = GitPullRequestReviewError.STORAGE) }
            }
        }
    }
    fun setBase(value: String) = edit { it.copy(base = value) }
    fun setTitle(value: String) = edit { it.copy(title = value) }
    fun setBody(value: String) = edit { it.copy(body = value) }
    fun setDraft(value: Boolean) = edit { it.copy(draft = value) }
    fun review() {
        val value = mutable.value; val prepared = value.preparation ?: return
        if (!current(generation, SESSION_GIT_PULL_REQUEST_CAPABILITY) || value.loading || value.sending || value.invalidated || pending != null) return
        val head = prepared.head ?: return
        val draft = value.draft
        val base = draft.base
        if (base == null || base !in prepared.pullRequestBases) { mutable.value = value.copy(error = GitPullRequestReviewError.INVALID_DRAFT); return }
        val intent = GitPullRequestIntent(prepared.branch, head, prepared.writeId, Wire.random(), prepared.pushTarget, base, draft.title, draft.body, draft.draft)
        try { parseGitPullRequestIntent(intent.json()) } catch (_: Exception) { mutable.value = value.copy(error = GitPullRequestReviewError.INVALID_DRAFT); return }
        mutable.value = value.copy(confirmation = intent, error = null)
    }
    fun backToEdit() { if (!mutable.value.sending) mutable.value = mutable.value.copy(confirmation = null) }
    fun confirm() {
        val value = mutable.value; val intent = value.confirmation ?: return
        if (!current(generation, SESSION_GIT_PULL_REQUEST_CAPABILITY) || value.sending || value.invalidated || pending != null) return
        val serial = generation
        mutable.value = value.copy(sending = true, error = null, canCheckRemote = false)
        scope.launch {
            var dispatched = false
            var known: GitWriteResult.PullRequest? = null
            try {
                storage.withLock { repository.saveGitPullRequestIntent(targetSelection, intent); pending = intent }
                if (!current(serial, SESSION_GIT_PULL_REQUEST_CAPABILITY)) {
                    storage.withLock { repository.saveGitPullRequestIntent(targetSelection, null); pending = null }
                    mutable.value = mutable.value.copy(sending = false, confirmation = null, invalidated = true); refreshCheck(); return@launch
                }
                val result = repository.gitPullRequest(checkNotNull(targetSelection.sessionId), intent.writeId, intent.base, intent.title, intent.body, intent.draft, intent.requestId) { dispatched = true }
                if (result != GitWriteResult.PullRequest.Unknown) known = result
                if (known != null) {
                    withContext(NonCancellable) {
                        var storageError = false
                        try { storage.withLock { repository.saveGitPullRequestIntent(targetSelection, null); pending = null } }
                        catch (_: Exception) { storageError = true }
                        mutable.value = mutable.value.copy(sending = false, pending = pending, result = known,
                            confirmation = null, preparation = null, invalidated = mutable.value.invalidated || !current(serial, SESSION_GIT_PULL_REQUEST_CAPABILITY),
                            error = if (storageError) GitPullRequestReviewError.STORAGE else null)
                        refreshCheck()
                    }
                    return@launch
                }
                mutable.value = mutable.value.copy(sending = false, pending = pending, result = GitWriteResult.PullRequest.Unknown,
                    confirmation = null, preparation = null, invalidated = mutable.value.invalidated || !current(serial, SESSION_GIT_PULL_REQUEST_CAPABILITY))
                refreshCheck()
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    var storageError = false
                    if (known != null) {
                        try { storage.withLock { repository.saveGitPullRequestIntent(targetSelection, null); pending = null } }
                        catch (_: Exception) { storageError = true }
                    } else if (dispatched) {
                        try { storage.withLock { repository.saveGitPullRequestIntent(targetSelection, intent); pending = intent } }
                        catch (_: Exception) { storageError = true }
                    } else try { storage.withLock { repository.saveGitPullRequestIntent(targetSelection, null); pending = null } }
                    catch (_: Exception) { storageError = true }
                    mutable.value = mutable.value.copy(sending = false, pending = pending,
                        result = known ?: if (dispatched || pending != null) GitWriteResult.PullRequest.Unknown else null,
                        confirmation = null, preparation = null, invalidated = mutable.value.invalidated || !current(serial, SESSION_GIT_PULL_REQUEST_CAPABILITY),
                        error = if (storageError) GitPullRequestReviewError.STORAGE else null)
                    refreshCheck()
                }
                throw e
            } catch (e: RemoteRequestException) {
                val uncertain = dispatched && (!e.answered || e.code == "outcome_unknown" || e.reason == "outcome_unknown")
                if (!uncertain) try { storage.withLock { repository.saveGitPullRequestIntent(targetSelection, null); pending = null } } catch (_: Exception) {}
                mutable.value = mutable.value.copy(sending = false, pending = pending, result = if (uncertain || pending != null) GitWriteResult.PullRequest.Unknown else null, confirmation = null, error = if (uncertain) null else GitPullRequestReviewError.REQUEST)
                refreshCheck()
            } catch (_: Exception) {
                if (!dispatched && pending != null) try { storage.withLock { repository.saveGitPullRequestIntent(targetSelection, null); pending = null } } catch (_: Exception) {}
                mutable.value = mutable.value.copy(sending = false, pending = pending, result = if (dispatched || pending != null) GitWriteResult.PullRequest.Unknown else null, confirmation = null, error = if (dispatched) null else GitPullRequestReviewError.STORAGE)
                refreshCheck()
            }
        }
    }
    fun checkRemote() {
        val intent = pending ?: return
        if (!mutable.value.open || mutable.value.checking || mutable.value.sending || !has(SESSION_GIT_PULL_REQUEST_STATUS_CAPABILITY)) return
        val serial = ++generation; authorityGeneration = repository.state.value.gitReviewGeneration
        mutable.value = mutable.value.copy(checking = true, canCheckRemote = false, recovery = null, error = null)
        scope.launch {
            try {
                val result = repository.gitPullRequestStatus(checkNotNull(targetSelection.sessionId), intent.writeId)
                if (!current(serial, SESSION_GIT_PULL_REQUEST_STATUS_CAPABILITY) || pending != intent) return@launch
                mutable.value = mutable.value.copy(checking = false, recovery = result)
                refreshCheck()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (serial == generation) { mutable.value = mutable.value.copy(checking = false, error = GitPullRequestReviewError.REQUEST); refreshCheck() } }
        }
    }
    /** User explicitly acknowledges after inspecting the remote or checking it manually. */
    fun acknowledgeUnknown() {
        if (pending == null || mutable.value.sending || mutable.value.checking || repository.state.value.selection != targetSelection) return
        mutable.value = mutable.value.copy(sending = true, error = null, canCheckRemote = false)
        scope.launch {
            try {
                storage.withLock { repository.saveGitPullRequestIntent(targetSelection, null); pending = null }
                generation++
                mutable.value = mutable.value.copy(sending = false, pending = null, result = null, recovery = null, preparation = null, confirmation = null, invalidated = true)
                refreshCheck()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(sending = false, error = GitPullRequestReviewError.STORAGE); refreshCheck() }
        }
    }
}
