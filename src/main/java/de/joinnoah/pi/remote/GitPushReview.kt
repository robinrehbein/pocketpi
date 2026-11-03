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

data class GitPushIntent(val branch: String, val head: String, val writeId: String, val requestId: String, val target: GitWriteTarget)

internal fun GitPushIntent.json(): JsonObject = Wire.objectOf(
    "branch" to branch, "head" to head, "writeId" to writeId, "requestId" to requestId,
    "target" to Wire.objectOf("remote" to target.remote, "branch" to target.branch, "host" to target.host, "repo" to target.repo),
)
internal fun parseGitPushIntent(value: JsonObject): GitPushIntent {
    Wire.keys(value, setOf("branch", "head", "writeId", "requestId", "target"))
    val branch = value.text("branch"); val head = value.text("head"); val writeId = value.text("writeId"); val requestId = value.text("requestId")
    encodeGitWriteCommand(GitWriteCommand.Push(requestId, "stored", writeId))
    val candidate = parseGitWriteResult(Wire.objectOf("kind" to "git.branch", "sessionId" to "stored", "available" to true,
        "writeId" to writeId, "branch" to branch, "head" to head, "remoteTip" to "absent", "ahead" to 0,
        "pushTarget" to value.obj("target"), "pullRequestBases" to JsonArray(emptyList()), "hooksSkipped" to JsonArray(emptyList())), "stored") as GitWriteResult.Branch.Available
    require(candidate.pushTarget.branch == branch && candidate.head == head)
    return GitPushIntent(branch, head, writeId, requestId, candidate.pushTarget)
}

internal enum class GitPushReviewError { UNAVAILABLE, STORAGE, REQUEST }
internal data class GitPushReviewState(
    val open: Boolean = false, val loading: Boolean = false,
    val preparation: GitWriteResult.Branch.Available? = null,
    val confirmation: GitPushIntent? = null, val sending: Boolean = false,
    val result: GitWriteResult.Push? = null, val pending: GitPushIntent? = null,
    val recovery: GitWriteResult.PushStatus? = null, val checking: Boolean = false,
    val canCheckRemote: Boolean = false, val error: GitPushReviewError? = null,
    val invalidated: Boolean = false, val canResend: Boolean = false,
    // A resend's outcome that leaves the push unknown: still no answer, a refusal that cannot speak
    // for the first send, or a window that has closed.
    val resendNotice: GitPushResendNotice? = null, val resendRefusal: GitPushReason? = null,
)
internal enum class GitPushResendNotice { UNANSWERED, REFUSED, EXPIRED }
/**
 * How long after the first dispatch a resend under the same request ID still reaches the Host's
 * ledger record of it. The Host keeps a record while the push runs and five minutes after it ends,
 * so four minutes from dispatch is always inside it.
 */
internal const val GIT_PUSH_RESEND_WINDOW_MILLIS = 4 * 60_000L

internal class GitPushReviewController(
    private val repository: RemoteRepository,
    private val scope: CoroutineScope,
    private val targetSelection: RemoteSelection,
    // Milliseconds that keep counting through deep sleep, like the Host's wall clock its ledger
    // window runs on: SystemClock.elapsedRealtime in the app.
    private val now: () -> Long,
) {
    // The intent this process dispatched, and when. An intent restored from storage has no known
    // dispatch time, so it is never resent.
    private var dispatchedAt: Pair<GitPushIntent, Long>? = null
    private fun resendable(intent: GitPushIntent?): Boolean =
        intent != null && dispatchedAt?.let { (sent, at) -> sent == intent && now() - at < GIT_PUSH_RESEND_WINDOW_MILLIS } == true
    private val mutable = MutableStateFlow(GitPushReviewState())
    val state: StateFlow<GitPushReviewState> = mutable
    private val storage = Mutex()
    private var generation = 0L
    private var authorityGeneration: Long? = null
    private var pending: GitPushIntent? = null
    private var hydrated = false
    private var hydrating = false
    private fun has(capability: String): Boolean = repository.state.value.let {
        it.connected && it.capabilitiesKnown && it.selection == targetSelection && targetSelection.sessionId != null &&
            capability in it.capabilities && capability !in it.unavailableCapabilities
    }
    private fun current(serial: Long, capability: String): Boolean = serial == generation && has(capability) &&
        repository.state.value.gitReviewGeneration == authorityGeneration
    private fun refreshCheck() {
        val idle = mutable.value.open && pending != null && !mutable.value.checking && !mutable.value.sending
        mutable.value = mutable.value.copy(canCheckRemote = idle && has(GIT_PUSH_STATUS_CAPABILITY),
            canResend = idle && mutable.value.result == GitWriteResult.Push.Unknown && resendable(pending) && has(GIT_PUSH_CAPABILITY))
    }
    private fun hydrate() {
        if (hydrated || hydrating || mutable.value.loading || mutable.value.sending ||
            repository.state.value.selection != targetSelection || targetSelection.routeId == null || targetSelection.sessionId == null) return
        hydrating = true
        val serial = generation
        val authority = repository.state.value.gitReviewGeneration
        scope.launch {
            try {
                val saved = storage.withLock { repository.gitPushIntent(targetSelection) }
                if (serial == generation && repository.state.value.selection == targetSelection &&
                    repository.state.value.gitReviewGeneration == authority && !mutable.value.loading && !mutable.value.sending) {
                    pending = saved; hydrated = true
                    mutable.value = mutable.value.copy(pending = saved, result = if (saved != null) GitWriteResult.Push.Unknown else null)
                    refreshCheck()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (serial == generation) { hydrated = true; mutable.value = mutable.value.copy(error = GitPushReviewError.STORAGE) } }
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
                val saved = storage.withLock { repository.gitPushIntent(targetSelection) }
                if (serial != generation || repository.state.value.selection != targetSelection || repository.state.value.gitReviewGeneration != authorityGeneration) return@launch
                pending = saved; hydrated = true
                mutable.value = mutable.value.copy(pending = saved, result = if (saved != null) GitWriteResult.Push.Unknown else null)
                refreshCheck()
                if (saved != null) { mutable.value = mutable.value.copy(loading = false); return@launch }
                if (!current(serial, GIT_PUSH_CAPABILITY)) { mutable.value = mutable.value.copy(loading = false, invalidated = true); return@launch }
                val result = repository.gitPushPrepare(checkNotNull(targetSelection.sessionId))
                if (!current(serial, GIT_PUSH_CAPABILITY)) return@launch
                val available = result as? GitWriteResult.Branch.Available
                mutable.value = mutable.value.copy(loading = false, preparation = available?.takeIf { it.head != null }, error = if (available?.head == null) GitPushReviewError.UNAVAILABLE else null)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (serial == generation) mutable.value = mutable.value.copy(loading = false, error = GitPushReviewError.REQUEST) }
        }
    }
    fun close() { generation++; mutable.value = mutable.value.copy(open = false, loading = false, confirmation = null, checking = false, canCheckRemote = false) }
    fun review() {
        val value = mutable.value; val prepared = value.preparation ?: return
        if (!current(generation, GIT_PUSH_CAPABILITY) || value.loading || value.sending || value.invalidated || pending != null) return
        val head = prepared.head ?: return
        val intent = GitPushIntent(prepared.branch, head, prepared.writeId, Wire.random(), prepared.pushTarget)
        try { parseGitPushIntent(intent.json()) } catch (_: Exception) { mutable.value = value.copy(error = GitPushReviewError.UNAVAILABLE); return }
        mutable.value = value.copy(confirmation = intent, error = null)
    }
    fun backToEdit() { if (!mutable.value.sending) mutable.value = mutable.value.copy(confirmation = null) }
    fun confirm() {
        val value = mutable.value; val intent = value.confirmation ?: return
        if (!current(generation, GIT_PUSH_CAPABILITY) || value.sending || value.invalidated || pending != null) return
        val serial = generation
        mutable.value = value.copy(sending = true, error = null, canCheckRemote = false)
        scope.launch {
            var dispatched = false
            var known: GitWriteResult.Push? = null
            try {
                storage.withLock { repository.saveGitPushIntent(targetSelection, intent); pending = intent }
                if (!current(serial, GIT_PUSH_CAPABILITY)) {
                    storage.withLock { repository.saveGitPushIntent(targetSelection, null); pending = null }
                    mutable.value = mutable.value.copy(sending = false, confirmation = null, invalidated = true); refreshCheck(); return@launch
                }
                val result = repository.gitPush(checkNotNull(targetSelection.sessionId), intent.writeId, intent.requestId) {
                    dispatched = true; dispatchedAt = intent to now()
                }
                if (result != GitWriteResult.Push.Unknown) known = result
                if (known != null) {
                    withContext(NonCancellable) {
                        var storageError = false
                        try { storage.withLock { repository.saveGitPushIntent(targetSelection, null); pending = null } }
                        catch (_: Exception) { storageError = true }
                        mutable.value = mutable.value.copy(sending = false, pending = pending, result = known,
                            confirmation = null, preparation = null, invalidated = mutable.value.invalidated || !current(serial, GIT_PUSH_CAPABILITY),
                            error = if (storageError) GitPushReviewError.STORAGE else null)
                        refreshCheck()
                    }
                    return@launch
                }
                mutable.value = mutable.value.copy(sending = false, pending = pending, result = GitWriteResult.Push.Unknown,
                    confirmation = null, preparation = null, invalidated = mutable.value.invalidated || !current(serial, GIT_PUSH_CAPABILITY))
                refreshCheck()
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    var storageError = false
                    if (known != null) {
                        try { storage.withLock { repository.saveGitPushIntent(targetSelection, null); pending = null } }
                        catch (_: Exception) { storageError = true }
                    } else if (dispatched) {
                        try { storage.withLock { repository.saveGitPushIntent(targetSelection, intent); pending = intent } }
                        catch (_: Exception) { storageError = true }
                    } else try { storage.withLock { repository.saveGitPushIntent(targetSelection, null); pending = null } }
                    catch (_: Exception) { storageError = true }
                    mutable.value = mutable.value.copy(sending = false, pending = pending,
                        result = known ?: if (dispatched || pending != null) GitWriteResult.Push.Unknown else null,
                        confirmation = null, preparation = null, invalidated = mutable.value.invalidated || !current(serial, GIT_PUSH_CAPABILITY),
                        error = if (storageError) GitPushReviewError.STORAGE else null)
                    refreshCheck()
                }
                throw e
            } catch (e: RemoteRequestException) {
                val uncertain = dispatched && (!e.answered || e.code == "outcome_unknown" || e.reason == "outcome_unknown")
                if (!uncertain) try { storage.withLock { repository.saveGitPushIntent(targetSelection, null); pending = null } } catch (_: Exception) {}
                mutable.value = mutable.value.copy(sending = false, pending = pending, result = if (uncertain || pending != null) GitWriteResult.Push.Unknown else null, confirmation = null, error = if (uncertain) null else GitPushReviewError.REQUEST)
                refreshCheck()
            } catch (_: Exception) {
                if (!dispatched && pending != null) try { storage.withLock { repository.saveGitPushIntent(targetSelection, null); pending = null } } catch (_: Exception) {}
                mutable.value = mutable.value.copy(sending = false, pending = pending, result = if (dispatched || pending != null) GitWriteResult.Push.Unknown else null, confirmation = null, error = if (dispatched) null else GitPushReviewError.STORAGE)
                refreshCheck()
            }
        }
    }
    fun checkRemote() {
        val intent = pending ?: return
        if (!mutable.value.open || mutable.value.checking || mutable.value.sending || !has(GIT_PUSH_STATUS_CAPABILITY)) return
        val serial = ++generation; authorityGeneration = repository.state.value.gitReviewGeneration
        mutable.value = mutable.value.copy(checking = true, canCheckRemote = false, recovery = null, error = null)
        scope.launch {
            try {
                val result = repository.gitPushStatus(checkNotNull(targetSelection.sessionId), intent.writeId)
                if (!current(serial, GIT_PUSH_STATUS_CAPABILITY) || pending != intent) return@launch
                mutable.value = mutable.value.copy(checking = false, recovery = result)
                refreshCheck()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (serial == generation) { mutable.value = mutable.value.copy(checking = false, error = GitPushReviewError.REQUEST); refreshCheck() } }
        }
    }
    /**
     * Sends the pending push again with its original request ID, only when the user asks, and only
     * within [GIT_PUSH_RESEND_WINDOW_MILLIS] of the first dispatch from this process. Inside that
     * window the Host's command ledger answers a request ID it holds with the first answer, waiting
     * for it if the push still runs, so a push that reached the Mac is not run twice, and one that
     * never arrived runs now. Only a push or `up_to_date` settles the intent: any other answer may
     * come from a fresh run (a Host restart forgets the ledger) and says nothing about the first.
     * Such a fresh run cannot push twice: the Host answers `up_to_date` when the remote branch
     * already points at the reviewed commit, and otherwise pushes it without force.
     */
    fun resend() {
        val intent = pending ?: return
        val value = mutable.value
        if (!value.open || value.sending || value.checking || value.result != GitWriteResult.Push.Unknown || !has(GIT_PUSH_CAPABILITY)) return
        if (!resendable(intent)) { mutable.value = value.copy(resendNotice = GitPushResendNotice.EXPIRED, resendRefusal = null); refreshCheck(); return }
        val serial = ++generation; authorityGeneration = repository.state.value.gitReviewGeneration
        mutable.value = value.copy(sending = true, error = null, recovery = null, canCheckRemote = false, canResend = false,
            preparation = null, confirmation = null, resendNotice = null, resendRefusal = null)
        scope.launch {
            var settled = false
            try {
                val result = repository.gitPush(checkNotNull(targetSelection.sessionId), intent.writeId, intent.requestId) {}
                val definite = result is GitWriteResult.Push.Pushed || result == GitWriteResult.Push.Refused(GitPushReason.UP_TO_DATE)
                if (!definite) {
                    mutable.value = mutable.value.copy(sending = false, result = GitWriteResult.Push.Unknown,
                        resendNotice = if (result is GitWriteResult.Push.Refused) GitPushResendNotice.REFUSED else GitPushResendNotice.UNANSWERED,
                        resendRefusal = (result as? GitWriteResult.Push.Refused)?.reason,
                        invalidated = mutable.value.invalidated || !current(serial, GIT_PUSH_CAPABILITY))
                    refreshCheck(); return@launch
                }
                settled = true
                withContext(NonCancellable) {
                    var storageError = false
                    try { storage.withLock { if (pending == intent) { repository.saveGitPushIntent(targetSelection, null); pending = null } } }
                    catch (_: Exception) { storageError = true }
                    mutable.value = mutable.value.copy(sending = false, pending = pending, result = if (storageError) GitWriteResult.Push.Unknown else result,
                        invalidated = mutable.value.invalidated || !current(serial, GIT_PUSH_CAPABILITY),
                        error = if (storageError) GitPushReviewError.STORAGE else null)
                    refreshCheck()
                }
            } catch (e: CancellationException) {
                if (!settled) withContext(NonCancellable) { mutable.value = mutable.value.copy(sending = false, result = GitWriteResult.Push.Unknown); refreshCheck() }
                throw e
            } catch (_: Exception) {
                // A failure of the resend says nothing about the first send.
                mutable.value = mutable.value.copy(sending = false, result = GitWriteResult.Push.Unknown, error = GitPushReviewError.REQUEST)
                refreshCheck()
            }
        }
    }
    /** User explicitly acknowledges after inspecting the remote or checking it manually. */
    fun acknowledgeUnknown() {
        if (pending == null || mutable.value.sending || mutable.value.checking || repository.state.value.selection != targetSelection) return
        mutable.value = mutable.value.copy(sending = true, error = null, canCheckRemote = false)
        scope.launch {
            try {
                storage.withLock { repository.saveGitPushIntent(targetSelection, null); pending = null }
                generation++
                mutable.value = mutable.value.copy(sending = false, pending = null, result = null, recovery = null, preparation = null, confirmation = null, invalidated = true)
                refreshCheck()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(sending = false, error = GitPushReviewError.STORAGE); refreshCheck() }
        }
    }
}
