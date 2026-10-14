package de.joinnoah.pi.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

internal class NavigationFakeRepository : RemoteRepository {
    override val state = MutableStateFlow(RemoteState())
    val activations = mutableListOf<Pair<RemoteSelection, ActivationMode>>()
    var activation: suspend (RemoteSelection, ActivationMode) -> RemoteSelection = { selection, _ ->
        selection
    }
    var notification: RemoteSelection? = null
    val cachedChats = mutableMapOf<RemoteSelection, ChatPreview>()

    override fun peekCachedChat(selection: RemoteSelection): ChatPreview? = cachedChats[selection]
    var notificationLookup: suspend () -> RemoteSelection? = { notification }
    var answers = 0
    var prompts = 0
    val imports = mutableListOf<Triple<RemoteSelection, List<String>, Boolean>>()
    val quotes = mutableListOf<String>()
    val abortedSessions = mutableListOf<String>()
    var childStops = 0
    val childResumes = mutableListOf<String>()

    override fun stopChild() {
        childStops++
    }

    override fun resumeChild(message: String) {
        childResumes += message
    }
    val jobOpens = mutableListOf<String?>()
    var jobRefreshes = 0

    override fun refreshJobs() {
        jobRefreshes++
    }

    override fun openJobs() {
        jobOpens += null
    }

    override fun openJob(jobId: String) {
        jobOpens += jobId
    }

    override fun closeJobs() {
        state.value = state.value.copy(jobs = state.value.jobs?.copy(listOpen = false, view = null))
    }
    val toolOutputRequests = mutableListOf<String>()
    var sessionRefreshes = 0
    val browsed = mutableListOf<Pair<String, String>>()
    val providersBrowsed = mutableListOf<String>()

    override fun browseProviders(routeId: String) {
        providersBrowsed += routeId
    }
    val folderOpens = mutableListOf<Triple<String, String, Boolean>>()
    var folderOpen: suspend (String, String, Boolean) -> RemoteSelection? = { routeId, _, _ ->
        RemoteSelection(routeId, "opened", "fresh")
    }

    val forks = mutableListOf<Triple<RemoteSelection, String, ForkMode>>()
    var fork: suspend (RemoteSelection) -> RemoteSelection = { it.copy(sessionId = "fork") }

    override suspend fun forkSession(
        source: RemoteSelection,
        messageId: String,
        mode: ForkMode,
    ): RemoteSelection {
        forks += Triple(source, messageId, mode)
        return fork(source)
    }

    override fun browseFolder(routeId: String, path: String) {
        browsed += routeId to path
        state.value = state.value.copy(folders = state.value.folders.copy(routeId = routeId, path = path))
    }

    var folderPromptsCleared = 0

    override fun cancelFolderTrust() {
        folderPromptsCleared++
    }

    override fun dismissFolderNotice() {
        folderPromptsCleared++
    }

    val begun = mutableListOf<Triple<String, String, FolderTrustPrompt?>>()
    var beginResult = true

    override fun beginOpenFolder(routeId: String, path: String, confirmed: FolderTrustPrompt?): Long? {
        begun += Triple(routeId, path, confirmed)
        return if (beginResult) begun.size.toLong() else null
    }

    override suspend fun openFolder(routeId: String, path: String, openId: Long): RemoteSelection? {
        val confirmed = begun.getOrNull(openId.toInt() - 1)?.third
        folderOpens += Triple(routeId, path, confirmed != null)
        return folderOpen(routeId, path, confirmed != null)
    }

    var filesOpens = 0
    /** What [openFiles] shows; the session ID is filled in from the selection. */
    var filesOnOpen: (String) -> FilesState = { FilesState(it, loading = false, listing = FileListing("")) }

    // Like the real repository, files and changes close each other.
    override fun openFiles() {
        filesOpens++
        state.value = state.value.copy(changes = null, files = filesOnOpen(checkNotNull(state.value.selection.sessionId)))
    }

    /** The files [openChanges] lists. */
    var changedFiles: List<GitFile> = emptyList()

    override fun openChanges() {
        val sessionId = checkNotNull(state.value.selection.sessionId)
        state.value =
            state.value.copy(
                files = null,
                changes =
                    ChangesState(
                        sessionId,
                        loading = false,
                        status = GitStatus(GitBase.SESSION, "Q2hhbmdlc1NuYXBzaG90MQ", changedFiles),
                        log = GitLog(),
                    ),
            )
    }

    val openedChangesFiles = mutableListOf<String?>()

    override fun openChangesFile(path: String?) {
        openedChangesFiles += path
        val changes = state.value.changes ?: return
        state.value = state.value.copy(changes = changes.copy(file = path))
    }

    // Beside the chat both stay open, each in its own inspector tab.
    override fun openFiles(exclusive: Boolean) {
        if (exclusive) return openFiles()
        filesOpens++
        state.value = state.value.copy(files = filesOnOpen(checkNotNull(state.value.selection.sessionId)))
    }

    var sharedChangesOpens = 0

    override fun openChanges(exclusive: Boolean) {
        if (exclusive) return openChanges()
        sharedChangesOpens++
        val files = state.value.files
        openChanges()
        state.value = state.value.copy(files = files)
    }

    override fun closeChanges() {
        state.value = state.value.copy(changes = null)
    }

    override fun closeFiles() {
        state.value = state.value.copy(files = null)
    }

    override fun openFilesFile(path: String?) {
        val files = state.value.files ?: return
        state.value = state.value.copy(files = files.copy(file = path?.let { OpenFile(it, loading = false) }))
    }

    override fun selectFileLines(selection: LineSelection?) {
        val files = state.value.files ?: return
        val file = files.file ?: return
        state.value = state.value.copy(files = files.copy(file = file.copy(selection = selection)))
    }

    var editorSaves = 0

    override fun startFileEditing() {
        val files = state.value.files ?: return
        val file = files.file ?: return
        if (files.editor != null) return
        state.value = state.value.copy(files = files.copy(editor = FileEditor(file.path, file.version ?: "", file.content)))
    }

    override fun updateFileEditorText(text: String) {
        val files = state.value.files ?: return
        val editor = files.editor ?: return
        state.value = state.value.copy(files = files.copy(editor = editor.copy(text = text)))
    }

    override fun saveFileEditor() {
        editorSaves++
    }

    override fun discardFileEditor() {
        val files = state.value.files ?: return
        state.value = state.value.copy(files = files.copy(editor = null))
    }

    /** Imports mark themselves running like the real repository's; a test ends them. */
    var importsRun = false

    override fun importAttachments(selection: RemoteSelection, uris: List<String>, photo: Boolean) {
        imports += Triple(selection, uris, photo)
        if (importsRun) state.value = state.value.copy(importingAttachments = true)
    }

    /** With this on, a batch import is only recorded; the test calls its `onDone` itself. */
    var holdBatches = false
    val batches = mutableListOf<Triple<RemoteSelection, List<Picked>, () -> Unit>>()

    override fun importAttachments(selection: RemoteSelection, batch: List<Picked>, onDone: () -> Unit) {
        if (holdBatches) batches += Triple(selection, batch, onDone)
        else super.importAttachments(selection, batch, onDone)
    }

    override suspend fun activate(
        selection: RemoteSelection,
        mode: ActivationMode,
    ): RemoteSelection {
        activations += selection to mode
        val result = activation(selection, mode)
        // The real repository's every activate() branch calls select(...), which sets
        // state.value.selection to the canonical result before returning it.
        state.value = state.value.copy(selection = result)
        return result
    }

    /** Answers [readProjectArtifact]; `fresh` is a reload from the viewer. */
    var artifact: suspend (sessionId: String, path: String, fresh: Boolean) -> ProjectArtifactResult = { _, _, _ ->
        ProjectArtifactResult.Unavailable
    }
    val artifactReads = mutableListOf<Pair<String, Boolean>>()

    override suspend fun readProjectArtifact(sessionId: String, path: String, fresh: Boolean): ProjectArtifactResult {
        artifactReads += path to fresh
        return artifact(sessionId, path, fresh)
    }

    /** Answers [listSessionArtifacts] and [openSessionArtifact]. */
    var sessionArtifacts: List<SessionArtifact> = emptyList()
    var openArtifact: suspend (artifactId: String, version: Int?) -> SessionArtifactOpenResult = { _, _ ->
        SessionArtifactOpenResult.Unavailable
    }
    var artifactLists = 0

    override suspend fun listSessionArtifacts(sessionId: String): SessionArtifactListResult {
        artifactLists++
        return SessionArtifactListResult.Loaded(SessionArtifactList(sessionArtifacts, false))
    }

    override suspend fun openSessionArtifact(
        sessionId: String,
        artifactId: String,
        version: Int?,
        type: ArtifactType?,
        sha256: String?,
    ): SessionArtifactOpenResult = openArtifact(artifactId, version)

    /** Answers [readProjectImage]. */
    var image: suspend (path: String) -> ProjectImageResult = { ProjectImageResult.Unavailable }

    override suspend fun readProjectImage(sessionId: String, path: String, fresh: Boolean): ProjectImageResult = image(path)

    var createdSessions = 0
    var create: suspend (String, String) -> RemoteSelection = { routeId, projectId ->
        RemoteSelection(routeId, projectId, "created")
    }

    override suspend fun createSession(routeId: String, projectId: String): RemoteSelection {
        createdSessions++
        return create(routeId, projectId)
    }

    val notificationRequests = mutableListOf<Pair<String, String>>()

    override suspend fun openNotification(routeId: String, sessionId: String): RemoteSelection? {
        notificationRequests += routeId to sessionId
        return notificationLookup()
    }

    var pairResult: RemoteSelection? = null

    override suspend fun pair(text: String): RemoteSelection? = pairResult

    override fun cancelSelection() {}

    override fun remove(routeId: String) {}

    override fun disconnect() {}

    var refreshes = 0

    override fun refresh() {
        refreshes++
    }

    override fun older() {}

    override fun draft(text: String) {
        state.value = state.value.copy(draft = text)
    }

    /** Drafts written for a chat that was no longer selected, filed per chat like the real repository. */
    val filedDrafts = mutableMapOf<RemoteSelection, String>()

    override fun draft(selection: RemoteSelection, text: String) {
        if (selection == state.value.selection) draft(text) else filedDrafts[selection] = text
    }

    var compactions = 0

    override fun compactContext() {
        compactions++
    }

    override fun quote(messageId: String?) {
        if (messageId != null) quotes += messageId
    }

    override fun abortSession(sessionId: String) {
        abortedSessions += sessionId
    }

    override fun refreshSessions() {
        sessionRefreshes++
    }

    override fun loadToolOutput(toolCallId: String) {
        toolOutputRequests += toolCallId
    }

    override fun prompt() {
        prompts++
    }

    var aborts = 0

    override fun abort() {
        aborts++
    }

    override fun answer(questionId: String, answer: JsonObject) {
        answers++
    }

    override fun dismissError() {}

    val errors = mutableListOf<Int>()

    override fun reportError(resource: Int) {
        errors += resource
    }

    override fun setPushToken(token: String?) {}
}

internal class FakeSettingsRepository : SettingsRepository {
    override val state = kotlinx.coroutines.flow.MutableStateFlow(RemoteSettings())

    override fun setTheme(theme: String) {
        state.value = state.value.copy(theme = theme)
    }

    override fun setPushEnabled(enabled: Boolean) {
        state.value = state.value.copy(pushEnabled = enabled, pushOptedOut = !enabled)
    }

    override fun markNotificationPromptShown() {
        state.value = state.value.copy(notificationPromptShown = true)
    }

    override fun setThinkingDisplay(display: String) {
        state.value = state.value.copy(thinkingDisplay = display)
    }

    override fun setHideOfflineSessions(hide: Boolean) {
        state.value = state.value.copy(hideOfflineSessions = hide)
    }

    override fun setSwipeEndToStart(action: SwipeAction) {
        state.value = state.value.copy(swipeEndToStart = action)
    }

    override fun setSwipeStartToEnd(action: SwipeAction) {
        state.value = state.value.copy(swipeStartToEnd = action)
    }

    override fun setEnterSends(enabled: Boolean) {
        state.value = state.value.copy(enterSends = enabled)
    }
}
