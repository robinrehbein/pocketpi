package de.joinnoah.pi.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

internal class WatchFakeRepository : RemoteRepository {
    val host = PairedHost("r", "wss://example.invalid", "device", "not-a-real-key", "Mac")
    val project = Wire.objectOf("id" to "p", "name" to "Project", "path" to "/project")
    val session = Wire.objectOf("id" to "s", "projectId" to "p", "title" to "Session", "status" to "idle", "origin" to "rpc")
    override val state = MutableStateFlow(RemoteState(hosts = listOf(host)))
    val activationModes = mutableListOf<ActivationMode>()
    var activationDelay = 0L
    var canonical: RemoteSelection? = null
    var prompts = 0
    var aborts = 0
    var forks = 0
    var closedChanges = 0
    var unshared: String? = null
    var closeRequests = 0
    var answer: JsonObject? = null
    var error: Int? = null
    override suspend fun activate(selection: RemoteSelection, mode: ActivationMode): RemoteSelection {
        activationModes += mode
        if (activationDelay > 0) delay(activationDelay)
        val chosen = canonical ?: selection
        state.value = state.value.copy(
            selection = chosen, host = host.takeIf { chosen.routeId != null }, connected = chosen.routeId != null,
            connection = if (chosen.routeId != null) R.string.wear_idle else R.string.remote_offline,
            projects = if (chosen.routeId != null) listOf(project) else emptyList(),
            project = project.takeIf { chosen.projectId != null }, sessions = if (chosen.projectId != null) listOf(session) else emptyList(),
            session = session.takeIf { chosen.sessionId != null }, status = "idle", capabilities = setOf(CONFIGURATION_CAPABILITY, SESSION_FORK_CAPABILITY),
        )
        return chosen
    }
    override suspend fun createSession(routeId: String, projectId: String) = activate(RemoteSelection(routeId, projectId, "s"), ActivationMode.USER_OPEN)
    override suspend fun openNotification(routeId: String, sessionId: String) = activate(RemoteSelection(routeId, "p", sessionId), ActivationMode.USER_OPEN)
    override suspend fun pair(text: String) = activate(RemoteSelection("r"), ActivationMode.USER_OPEN)
    override suspend fun forkSession(source: RemoteSelection, messageId: String, mode: ForkMode): RemoteSelection {
        forks++
        return activate(source.copy(sessionId = "fork"), ActivationMode.USER_OPEN)
    }
    override fun cancelSelection() {}
    override fun remove(routeId: String) { state.value = RemoteState() }
    override fun disconnect() {}
    override fun refresh() {}
    override fun older() {}
    override fun draft(text: String) { state.value = state.value.copy(draft = text) }
    override fun quote(messageId: String?) {}
    override fun prompt() { prompts++ }
    override fun abort() { aborts++ }
    override fun answer(questionId: String, answer: JsonObject) { this.answer = answer }
    override fun dismissError() {}
    override fun reportError(resource: Int) { error = resource }
    override fun setPushToken(token: String?) {}
    override fun closeChanges() { closedChanges++ }
    override fun closeSession(sessionId: String) { closeRequests++ }
    override fun unshareProject(projectId: String) {
        if (state.value.selection.projectId == null) unshared = projectId
    }
}
