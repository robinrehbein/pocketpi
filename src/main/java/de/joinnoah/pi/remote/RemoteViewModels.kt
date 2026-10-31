package de.joinnoah.pi.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
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
            is RemoteNavKey.Activity -> state.selection.routeId == key.routeId
            is RemoteNavKey.FolderBrowser ->
                state.selection.routeId == key.routeId && state.selection.projectId == null
            is RemoteNavKey.Providers ->
                state.selection.routeId == key.routeId && state.selection.projectId == null
            is RemoteNavKey.Tickets,
            is RemoteNavKey.Ticket ->
                state.selection.routeId == key.selection().routeId
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
                    questionBlocks = emptyMap(),
                    toolOutput = null,
                    changes = null,
                    compaction = null,
                    compactionRequesting = false,
                    notice = null,
                )
        }

    private val initialState =
        repository.state.value.let { current ->
            if (matches(current)) screenState(current)
            else
                (key as? RemoteNavKey.Chat)?.let { chat ->
                    val cached = repository.peekCachedChat(chat.selection())
                    val text = repository.peekDraftText(chat.selection())
                    RemoteState(
                        loading = true,
                        preview = cached != null,
                        previewDraftAvailable = text != null,
                        session = cached?.session,
                        messages = cached?.messages.orEmpty(),
                        draft = text.orEmpty(),
                    )
                } ?: RemoteState(loading = true)
        }

    val state =
        repository.state
            .scan(initialState) { previous, current ->
                if (matches(current)) screenState(current)
                else {
                    val text = (key as? RemoteNavKey.Chat)?.let { repository.peekDraftText(it.selection()) }
                    previous.copy(
                        loading = true, connected = false,
                        previewDraftAvailable = text != null,
                        // Offscreen settlement can change the persisted draft; ComposerDraft retains dirty local edits separately.
                        draft = text ?: previous.draft,
                    )
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                initialState,
            )

    protected fun active(action: () -> Unit) {
        if (matches(repository.state.value) && !repository.state.value.loading && !cleared) action()
    }

    /**
     * Like [active], but also while the destination loads (a reconnect), so the editor's text, a
     * save and its prompt are never dropped without a word; the repository answers a save it
     * cannot send with a retryable failure.
     */
    protected fun editing(action: () -> Unit) {
        if (matches(repository.state.value) && !cleared) action()
    }

    fun refresh() = active(repository::refresh)

    fun revalidateLists() = active(repository::revalidateLists)

    fun renameSession(sessionId: String, title: String) = active {
        repository.renameSession(sessionId, title)
    }

    fun remove(routeId: String) = repository.remove(routeId)

    fun older() = active(repository::older)

    // The draft and its quote are local to the chat, so they are taken while it loads too.
    fun draft(text: String) = editing { repository.draft(text) }

    /**
     * A composer's write for this destination's chat; see [RemoteRepository.draft]. Unlike [draft]
     * it is not held back while the chat loads or after the destination closed: a flush on the
     * way out (Back, another chat, a notification) runs when the selection already moved on, and
     * the repository files it under [selection]. A write naming another chat is dropped.
     */
    fun draft(selection: RemoteSelection, text: String) {
        if (key is RemoteNavKey.Chat && selection == key.selection()) repository.draft(selection, text)
    }

    fun quote(messageId: String?) = editing { repository.quote(messageId) }

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

    /**
     * A host action waits for the load, except a plain prompt typed while a reconnect loads: the
     * repository queues it (see [canQueuePrompt]).
     */
    fun prompt() = if (canQueuePrompt(repository.state.value)) editing(repository::prompt) else active(repository::prompt)

    fun cancelQueuedPrompt() = editing(repository::cancelQueuedPrompt)

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

    fun dismissNotice(id: Long) = repository.dismissNotice(id)

    override fun onCleared() {
        cleared = true
    }
}

internal class HostsViewModel(repository: RemoteRepository) :
    DestinationViewModel(repository, RemoteNavKey.Hosts) {
    /** Gives up the pairing waiting for the Mac's approval; see [RemoteRepository.cancelPairing]. */
    fun cancelPairing() = repository.cancelPairing()
}

internal class ActivityViewModel(repository: RemoteRepository, key: RemoteNavKey.Activity) :
    DestinationViewModel(repository, key)

internal class ProjectsViewModel(repository: RemoteRepository, key: RemoteNavKey.Projects) :
    DestinationViewModel(repository, key) {
    fun unshare(projectId: String) = active { repository.unshareProject(projectId) }

    fun queueAccept(projectId: String, ref: String, comment: String) =
        active { repository.queueAccept(projectId, ref, comment) }

    fun undoAccept() = repository.undoAccept()

    fun dismissTicketNotice() = repository.dismissTicketNotice()
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

/**
 * Backs both ticket pages. The detail page must read its state with [ticketDetailView] by ref
 * (loading, ready, unavailable or failed). [matches] ignores the project so the pages
 * stay valid over a chat.
 */
internal class TicketsViewModel(
    repository: RemoteRepository,
    key: RemoteNavKey,
) : DestinationViewModel(repository, key) {
    /** Reads the tickets again; [force] has the host read Plane first. */
    fun reload(force: Boolean) = active { repository.refreshTickets(force) }

    fun open(projectId: String, ref: String) = active { repository.openTicket(projectId, ref) }

    fun comment(projectId: String, ref: String, text: String) =
        active { repository.commentOnTicket(projectId, ref, text) }

    fun queueAccept(projectId: String, ref: String, comment: String) =
        active { repository.queueAccept(projectId, ref, comment) }

    /** Not gated: taking an accept back must work whatever the page is doing. */
    fun undoAccept() = repository.undoAccept()

    fun dismissNotice() = repository.dismissTicketNotice()

    fun move(projectId: String, ref: String, state: String, comment: String?) =
        active { repository.moveTicketTo(projectId, ref, state, comment) }

    fun start(projectId: String, ref: String, note: String?) =
        active { repository.startTicketJob(projectId, ref, note) }
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

    fun recordChatAction(action: ChatAction) = settings.recordChatAction(action)

    /** A picked, dropped or pasted batch waiting for its chat; identity matters, so not a data class. */
    private class PickerResult(
        val selection: RemoteSelection,
        val items: List<Picked>,
        val onDone: () -> Unit,
    )

    private val pendingPicker = MutableStateFlow<PickerResult?>(null)

    init {
        viewModelScope.launch {
            combine(repository.state, pendingPicker) { state, result -> state to result }
                .collect { (state, result) ->
                    if (
                        result != null && !state.loading && state.selection == result.selection &&
                            pendingPicker.compareAndSet(result, null)
                    )
                        repository.importAttachments(result.selection, result.items, result.onDone)
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

    /** Reloads this chat's session; a destination that is no longer the shown chat gets a failure. */
    suspend fun reloadSession(sessionId: String): ReloadResult =
        if (cleared || !matches(repository.state.value)) ReloadResult.Failed(ReloadFailure.Failed(null))
        else repository.reloadSession(sessionId)

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

    /** Closes this chat's session on the Mac, as a session row's Close does. */
    fun closeSession(sessionId: String) = active { repository.closeSession(sessionId) }

    /** [exclusive] false keeps the files open beside the changes ([RemoteRepository.openChanges]). */
    fun openChanges(exclusive: Boolean = true) = active { repository.openChanges(exclusive) }

    fun refreshJobs() = active(repository::refreshJobs)

    fun openJobs() = active(repository::openJobs)

    fun closeJobs() = repository.closeJobs()

    fun openJob(jobId: String) = active { repository.openJob(jobId) }

    fun closeJob() = repository.closeJob()

    fun stopJob(jobId: String) = active { repository.stopJob(jobId) }

    /** [RemoteRepository.runBash]; false when it did not start, also for a chat no longer shown. */
    fun runBash(command: String, excludeFromContext: Boolean): Boolean {
        var started = false
        active { started = repository.runBash(command, excludeFromContext) }
        return started
    }

    fun abortBash() = active(repository::abortBash)

    fun closeBash() = repository.closeBash()

    fun closeChanges() = repository.closeChanges()

    fun selectChangesBase(base: GitBase) = active { repository.selectChangesBase(base) }

    fun reloadChanges() = active(repository::reloadChanges)

    fun openChangesFile(path: String?) = active { repository.openChangesFile(path) }

    fun openChangesSubagentFile(sessionId: String, path: String) =
        active { repository.openChangesSubagentFile(sessionId, path) }

    fun requestChangesPreview(path: String) = active { repository.requestChangesPreview(path) }

    /** [exclusive] false keeps the changes open beside the files ([RemoteRepository.openFiles]). */
    fun openFiles(exclusive: Boolean = true) = active { repository.openFiles(exclusive) }

    fun closeFiles() = repository.closeFiles()

    fun openFilesDir(path: String) = active { repository.openFilesDir(path) }

    fun openFilesFile(path: String?) = active { repository.openFilesFile(path) }

    /** [RemoteRepository.openFileAt]; false when nothing opened (a path outside the session folder, or an editor open). */
    fun openFileAt(path: String, lines: LineSelection?, exclusive: Boolean = true): Boolean {
        var opened = false
        active { opened = repository.openFileAt(path, lines, exclusive) }
        return opened
    }

    fun loadMoreFiles() = active(repository::loadMoreFiles)

    fun reloadFiles() = active(repository::reloadFiles)

    fun requestFilesPreview(path: String) = active { repository.requestFilesPreview(path) }

    fun showFilesPeek(path: String, type: FileEntryType) = active { repository.showFilesPeek(path, type) }

    fun dismissFilesPeek() = repository.dismissFilesPeek()

    fun selectFileLines(selection: LineSelection?) = active { repository.selectFileLines(selection) }

    fun startFileEditing() = active(repository::startFileEditing)

    fun updateFileEditorText(text: String) = editing { repository.updateFileEditorText(text) }

    fun saveFileEditor() = editing(repository::saveFileEditor)

    fun discardFileEditor() = repository.discardFileEditor()

    fun reloadFileAfterConflict() = editing(repository::reloadFileAfterConflict)

    fun confirmFileEditorLeave() = editing(repository::confirmFileEditorLeave)

    fun cancelFileEditorLeave() = repository.cancelFileEditorLeave()

    /** The edit as a diff prompt for the composer; null when unchanged. Nothing is sent. */
    fun fileEditorPrompt(intro: String? = null): EditorPrompt? {
        var prompt: EditorPrompt? = null
        editing { prompt = repository.fileEditorPrompt(intro) }
        return prompt
    }

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

    /**
     * Sends [prompt] as it is when the composer is empty and the chat takes a prompt now; else it
     * goes into the composer after what is typed there, as [prefillPrompt] does, so nothing the
     * user typed, quoted or attached is sent along unseen.
     */
    fun askNow(prompt: String): PromptHandoff {
        var handoff: PromptHandoff? = null
        active {
            val current = repository.state.value
            if (
                current.draft.isNotBlank() || current.quote != null || current.attachments.isNotEmpty() ||
                    !current.connected || current.sending || current.questions.isNotEmpty() ||
                    current.status != "idle" || prompt.toByteArray().size > 128 * 1024
            )
                return@active
            repository.draft(prompt)
            if (repository.state.value.draft != prompt) return@active
            repository.prompt()
            // A prompt the repository would not send stays in the composer.
            handoff = if (repository.state.value.sending) PromptHandoff.SENT else PromptHandoff.PREFILLED
        }
        handoff?.let { return it }
        return if (prefillPrompt(prompt)) PromptHandoff.PREFILLED else PromptHandoff.FAILED
    }

    /** Moves the review into the composer; the comments are cleared only once the draft holds it. */
    fun useReview(prompt: String): Boolean {
        if (!prefillPrompt(prompt)) return false
        repository.clearReviewComments()
        repository.closeChanges()
        return true
    }

    fun acceptAttachments(selection: RemoteSelection, uris: List<String>, photo: Boolean) =
        acceptAttachments(selection, uris.map { Picked(it, photo) }) {}

    /**
     * Imports [uris] into this chat's draft once it can take them, and reports whether it took
     * them. [done] runs once the import no longer reads them: it ended, was refused, the result
     * was replaced by a newer one before it started, or this chat closed first.
     */
    fun acceptAttachments(selection: RemoteSelection, uris: List<String>, photo: Boolean, done: () -> Unit): Boolean {
        if (selection != key.selection() || uris.isEmpty() || cleared) return false
        acceptAttachments(selection, uris.map { Picked(it, photo) }, done)
        return true
    }

    /**
     * Imports a mixed batch of images and files into [selection]'s draft once that chat is shown
     * and loaded, as one run (see [RemoteRepository.importAttachments]). [onDone] runs exactly
     * once: after the import read every URI or refused the batch, when a later batch replaces this
     * one before it started, or when this chat closes first. A drop releases its grants there.
     */
    fun acceptAttachments(selection: RemoteSelection, items: List<Picked>, onDone: () -> Unit) {
        if (selection != key.selection() || items.isEmpty() || cleared) {
            onDone()
            return
        }
        pendingPicker.getAndUpdate { PickerResult(selection, items, onDone) }?.onDone?.invoke()
    }

    override fun onCleared() {
        super.onCleared()
        pendingPicker.getAndUpdate { null }?.onDone?.invoke()
    }
}

internal class SettingsViewModel(
    repository: RemoteRepository,
    private val settings: SettingsRepository,
) : DestinationViewModel(repository, RemoteNavKey.Settings) {
    val preferences = settings.state

    fun setTheme(theme: ThemePalette) = settings.setTheme(theme)

    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)

    fun setThinkingDisplay(display: ThinkingDisplay) = settings.setThinkingDisplay(display)

    fun setSwipeEndToStart(action: SwipeAction) = settings.setSwipeEndToStart(action)

    fun setSwipeStartToEnd(action: SwipeAction) = settings.setSwipeStartToEnd(action)

    fun setEnterSends(enabled: Boolean) = settings.setEnterSends(enabled)

    /** The same setting as the session filter's switch ([SessionsViewModel.setHideOfflineSessions]). */
    fun setHideOfflineSessions(hide: Boolean) = settings.setHideOfflineSessions(hide)
}

/** Where [ChatViewModel.askNow] put a prompt. */
internal enum class PromptHandoff {
    SENT,
    /** In the composer, for the user to send. */
    PREFILLED,
    FAILED,
}
