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

    override fun openJobs() {
        jobOpens += null
    }

    override fun openJob(jobId: String) {
        jobOpens += jobId
    }
    val toolOutputRequests = mutableListOf<String>()
    var sessionRefreshes = 0
    val browsed = mutableListOf<Pair<String, String>>()
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

    override fun importAttachments(selection: RemoteSelection, uris: List<String>, photo: Boolean) {
        imports += Triple(selection, uris, photo)
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

    override suspend fun createSession(routeId: String, projectId: String) =
        RemoteSelection(routeId, projectId, "created")

    override suspend fun openNotification(routeId: String, sessionId: String) = notificationLookup()

    override suspend fun pair(text: String): RemoteSelection? = null

    override fun cancelSelection() {}

    override fun remove(routeId: String) {}

    override fun disconnect() {}

    override fun refresh() {}

    override fun older() {}

    override fun draft(text: String) {
        state.value = state.value.copy(draft = text)
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

    override fun abort() {}

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
        state.value = state.value.copy(pushEnabled = enabled)
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
}
