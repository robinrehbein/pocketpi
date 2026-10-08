package de.joinnoah.pi.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

internal open class DestinationViewModel(
    protected val repository: RemoteRepository,
    val key: RemoteNavKey,
) : ViewModel() {
    internal var cleared = false
        private set

    protected fun matches(state: RemoteState): Boolean =
        when (key) {
            RemoteNavKey.Hosts,
            RemoteNavKey.Settings -> true
            is RemoteNavKey.Projects -> state.selection.routeId == key.routeId
            is RemoteNavKey.FolderBrowser ->
                state.selection.routeId == key.routeId && state.selection.projectId == null
            is RemoteNavKey.Providers ->
                state.selection.routeId == key.routeId && state.selection.projectId == null
            is RemoteNavKey.Sessions ->
                state.selection.routeId == key.routeId && state.selection.projectId == key.projectId
            is RemoteNavKey.Chat -> state.selection == key.selection()
        }

    private fun screenState(state: RemoteState): RemoteState =
        when (key) {
            is RemoteNavKey.Chat -> state
            else ->
                state.copy(
                    session = null,
                    messages = emptyList(),
                    questions = emptyList(),
                    draft = "",
                    quote = null,
                    attachments = emptyList(),
                    sending = false,
                    uncertain = false,
                    followUps = emptyList(),
                    answering = emptySet(),
                    toolOutput = null,
                    changes = null,
                    compaction = null,
                    compactionRequesting = false,
                )
        }

    private val initialState =
        repository.state.value.let { current ->
            if (matches(current)) screenState(current) else RemoteState(loading = true)
        }

    val state =
        repository.state
            .scan(initialState) { previous, current ->
                if (matches(current)) screenState(current)
                else previous.copy(loading = true, connected = false)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                initialState,
            )

    protected fun active(action: () -> Unit) {
        if (matches(repository.state.value) && !repository.state.value.loading && !cleared) action()
    }

    fun refresh() = active(repository::refresh)

    fun renameSession(sessionId: String, title: String) = active {
        repository.renameSession(sessionId, title)
    }

    fun remove(routeId: String) = repository.remove(routeId)

    fun older() = active(repository::older)

    fun draft(text: String) = active { repository.draft(text) }

    fun quote(messageId: String?) = active { repository.quote(messageId) }

    fun refreshConfiguration() = active(repository::refreshConfiguration)

    fun refreshContextUsage() = active(repository::refreshContextUsage)

    fun compactContext() = active(repository::compactContext)

    fun refreshAdvisor() = active(repository::refreshAdvisor)

    fun setAdvisor(provider: String?, id: String?, level: String?) =
        active { repository.setAdvisor(provider, id, level) }


    fun setModel(provider: String, id: String) = active { repository.setModel(provider, id) }

    fun setThinkingLevel(level: String) = active { repository.setThinkingLevel(level) }

    fun changeSettings(autoCompaction: Boolean?, steeringMode: String?, followUpMode: String?) =
        active { repository.changeSettings(autoCompaction, steeringMode, followUpMode) }

    fun refreshCommands() = active(repository::refreshCommands)

    fun selectCommand(command: RemoteCommand) = active { repository.selectCommand(command) }

    fun importAttachments(selection: RemoteSelection, uris: List<String>, photo: Boolean) = active {
        repository.importAttachments(selection, uris, photo)
    }

    fun removeAttachment(id: String) = active { repository.removeAttachment(id) }

    fun cancelAttachmentWork() = active(repository::cancelAttachmentWork)

    fun prompt() = active(repository::prompt)

    fun followUp() = active(repository::followUp)

    fun steer() = active(repository::steer)

    fun stopChild() = active(repository::stopChild)

    fun resumeChild() = active { repository.resumeChild(repository.state.value.draft) }

    fun dismissFollowUp(requestId: String) = active { repository.dismissFollowUp(requestId) }

    fun abort() = active(repository::abort)

    fun answer(questionId: String, answer: JsonObject) = active {
        repository.answer(questionId, answer)
    }

    fun dismissError() = repository.dismissError()

    override fun onCleared() {
        cleared = true
    }
}

internal class HostsViewModel(repository: RemoteRepository) :
    DestinationViewModel(repository, RemoteNavKey.Hosts)

internal class ProjectsViewModel(repository: RemoteRepository, key: RemoteNavKey.Projects) :
    DestinationViewModel(repository, key) {
    fun unshare(projectId: String) = active { repository.unshareProject(projectId) }
}

internal class FolderBrowserViewModel(
    repository: RemoteRepository,
    key: RemoteNavKey.FolderBrowser,
) : DestinationViewModel(repository, key) {
    private val routeId = key.routeId

    /** Reloads the shown folder, for retry and after the connection comes back. */
    fun reload() = active {
        val folders = repository.state.value.folders
        repository.browseFolder(routeId, folders.path.takeIf { folders.routeId == routeId }.orEmpty())
    }

    fun enter(name: String) = active {
        repository.browseFolder(routeId, joinFolderPath(repository.state.value.folders.path, name))
    }

    fun createFolder(name: String) = active { repository.createFolder(name) }

    fun cloneRepository(url: String, name: String?) = active { repository.cloneRepository(url, name) }

    fun dismissClone() = repository.dismissClone()

    fun refreshClone() = repository.refreshClone()

    fun dismissNotice() = repository.dismissFolderNotice()

    fun cancelTrust() = repository.cancelFolderTrust()
}

internal class ProvidersViewModel(
    repository: RemoteRepository,
    key: RemoteNavKey.Providers,
) : DestinationViewModel(repository, key) {
    private val routeId = key.routeId

    /** Loads the list again, for retry, after a reconnect and when the app comes back. */
    fun reload() = active {
        if (repository.state.value.providerAuth.routeId == routeId) repository.refreshProviders()
        else repository.browseProviders(routeId)
    }

    fun startLogin(providerId: String, method: ProviderAuthMethod, replace: Boolean) =
        active { repository.startLogin(providerId, method, replace) }

    fun answerLogin(promptId: String, value: String): Boolean {
        var sent = false
        active { sent = repository.answerLogin(promptId, value) }
        return sent
    }

    fun cancelLogin() = active(repository::cancelLogin)

    fun logout(providerId: String) = active { repository.logoutProvider(providerId) }

    fun dismissLogin() = repository.dismissLogin()

    fun dismissNotice() = repository.dismissProviderNotice()
}

internal class SessionsViewModel(
    repository: RemoteRepository,
    key: RemoteNavKey.Sessions,
    private val settings: SettingsRepository,
) : DestinationViewModel(repository, key) {
    val preferences = settings.state

    fun setHideOfflineSessions(hide: Boolean) = settings.setHideOfflineSessions(hide)

    fun close(sessionId: String) = repository.closeSession(sessionId)
}

internal class ChatViewModel(
    repository: RemoteRepository,
    key: RemoteNavKey.Chat,
    private val settings: SettingsRepository,
) : DestinationViewModel(repository, key) {
    val preferences = settings.state

    /** Header actions by use, ranked once per opened chat so the pill does not shift while shown. */
    val chatActions: List<ChatAction> = settings.rankedChatActions()

    fun recordChatAction(action: ChatAction) = settings.recordChatAction(action)

    private data class PickerResult(
        val selection: RemoteSelection,
        val uris: List<String>,
        val photo: Boolean,
    )

    private val pendingPicker = MutableStateFlow<PickerResult?>(null)

    init {
        viewModelScope.launch {
            combine(repository.state, pendingPicker) { state, result -> state to result }
                .collect { (state, result) ->
                    if (result != null && !state.loading && state.selection == result.selection) {
                        pendingPicker.value = null
                        repository.importAttachments(result.selection, result.uris, result.photo)
                    }
                }
        }
    }

    fun abortSession(sessionId: String) = active { repository.abortSession(sessionId) }

    fun refreshSessions() = active(repository::refreshSessions)

    fun loadToolOutput(toolCallId: String) = active { repository.loadToolOutput(toolCallId) }

    fun cancelToolOutput() = active(repository::cancelToolOutput)

    /** Reads an image this chat sent; a destination that is no longer the shown chat gets nothing. */
    suspend fun readAttachment(sessionId: String, attachment: RemoteAttachment): AttachmentReadResult =
        if (cleared || !matches(repository.state.value)) AttachmentReadResult.Unavailable
        else repository.readAttachment(sessionId, attachment)

    /** Reads an image the agent mentioned; a destination that is no longer the shown chat gets nothing. */
    suspend fun readProjectImage(sessionId: String, path: String, fresh: Boolean = false): ProjectImageResult =
        if (cleared || !matches(repository.state.value)) ProjectImageResult.Unavailable
        else repository.readProjectImage(sessionId, path, fresh)

    /** Reads an HTML file the agent mentioned; a destination that is no longer the shown chat gets nothing. */
    suspend fun readProjectArtifact(sessionId: String, path: String, fresh: Boolean = false): ProjectArtifactResult =
        if (cleared || !matches(repository.state.value)) ProjectArtifactResult.Unavailable
        else repository.readProjectArtifact(sessionId, path, fresh)

    /** Lists this chat's artifacts; a destination that is no longer the shown chat gets a failure. */
    suspend fun listSessionArtifacts(sessionId: String): SessionArtifactListResult =
        if (cleared || !matches(repository.state.value)) SessionArtifactListResult.Failed
        else repository.listSessionArtifacts(sessionId)

    /** Opens one of this chat's artifacts; a destination that is no longer the shown chat gets nothing. */
    suspend fun openSessionArtifact(
        sessionId: String,
        artifactId: String,
        version: Int? = null,
        type: ArtifactType? = null,
        sha256: String? = null,
    ): SessionArtifactOpenResult =
        if (cleared || !matches(repository.state.value)) SessionArtifactOpenResult.Unavailable
        else repository.openSessionArtifact(sessionId, artifactId, version, type, sha256)

    /** Exports this chat's session; a destination that is no longer the shown chat gets a failure. */
    suspend fun exportSession(sessionId: String): ExportResult =
        if (cleared || !matches(repository.state.value)) ExportResult.Failed(ExportFailure.FAILED)
        else repository.exportSession(sessionId)

    /** Reads the tree of this chat's session; a destination that is no longer the shown chat gets a failure. */
    suspend fun loadSessionTree(sessionId: String): TreeLoadResult =
        if (cleared || !matches(repository.state.value)) TreeLoadResult.Failed(TreeFailure.FAILED)
        else repository.loadSessionTree(sessionId)

    /** Moves this chat's session to a tree node; see [RemoteRepository.navigateSessionTree]. */
    suspend fun navigateSessionTree(sessionId: String, nodeId: String, summarize: Boolean): TreeNavigateResult =
        if (cleared || !matches(repository.state.value)) TreeNavigateResult.Failed(TreeFailure.FAILED)
        else repository.navigateSessionTree(sessionId, nodeId, summarize)

    /** Quotes the message and offers [prompt] as the draft unless the user already typed one. */
    fun askToFix(messageId: String, prompt: String) = active {
        repository.quote(messageId)
        if (repository.state.value.draft.isBlank()) repository.draft(prompt)
    }

    fun openChanges() = active(repository::openChanges)

    fun refreshJobs() = active(repository::refreshJobs)

    fun openJobs() = active(repository::openJobs)

    fun closeJobs() = repository.closeJobs()

    fun openJob(jobId: String) = active { repository.openJob(jobId) }

    fun closeJob() = repository.closeJob()

    fun stopJob(jobId: String) = active { repository.stopJob(jobId) }

    fun closeChanges() = repository.closeChanges()

    fun selectChangesBase(base: GitBase) = active { repository.selectChangesBase(base) }

    fun reloadChanges() = active(repository::reloadChanges)

    fun openChangesFile(path: String?) = active { repository.openChangesFile(path) }

    fun openFiles() = active(repository::openFiles)

    fun closeFiles() = repository.closeFiles()

    fun openFilesDir(path: String) = active { repository.openFilesDir(path) }

    fun openFilesFile(path: String?) = active { repository.openFilesFile(path) }

    fun loadMoreFiles() = active(repository::loadMoreFiles)

    fun reloadFiles() = active(repository::reloadFiles)

    fun requestFilesPreview(path: String) = active { repository.requestFilesPreview(path) }

    fun showFilesPeek(path: String, type: FileEntryType) = active { repository.showFilesPeek(path, type) }

    fun dismissFilesPeek() = repository.dismissFilesPeek()

    fun selectFileLines(selection: LineSelection?) = active { repository.selectFileLines(selection) }

    /** False when the comment was not saved; the caller keeps the user's text on screen. */
    fun setReviewComment(comment: ReviewComment): Boolean {
        var saved = false
        active { saved = repository.setReviewComment(comment) }
        return saved
    }

    fun removeReviewComment(comment: ReviewComment) = active { repository.removeReviewComment(comment) }

    /** Puts [prompt] into the composer after anything already typed. It is never sent from here. */
    fun prefillPrompt(prompt: String): Boolean {
        var done = false
        active {
            val text = prefilledDraft(repository.state.value.draft, prompt)
            if (text.toByteArray().size <= 128 * 1024) {
                repository.draft(text)
                done = repository.state.value.draft == text
            }
        }
        return done
    }

    /** Moves the review into the composer; the comments are cleared only once the draft holds it. */
    fun useReview(prompt: String): Boolean {
        if (!prefillPrompt(prompt)) return false
        repository.clearReviewComments()
        repository.closeChanges()
        return true
    }

    fun acceptAttachments(selection: RemoteSelection, uris: List<String>, photo: Boolean) {
        if (selection != key.selection() || uris.isEmpty() || cleared) return
        pendingPicker.value = PickerResult(selection, uris, photo)
    }
}

internal class SettingsViewModel(
    repository: RemoteRepository,
    private val settings: SettingsRepository,
) : DestinationViewModel(repository, RemoteNavKey.Settings) {
    val preferences = settings.state

    fun setTheme(theme: String) = settings.setTheme(theme)

    fun setThinkingDisplay(display: String) = settings.setThinkingDisplay(display)

    fun setSwipeEndToStart(action: SwipeAction) = settings.setSwipeEndToStart(action)

    fun setSwipeStartToEnd(action: SwipeAction) = settings.setSwipeStartToEnd(action)

    fun setEnterSends(enabled: Boolean) = settings.setEnterSends(enabled)
}
