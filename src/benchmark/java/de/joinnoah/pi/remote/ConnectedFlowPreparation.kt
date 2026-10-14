package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject

/**
 * The connected-flow benchmarks' prepare pass and the checks around it, shared by
 * [ConnectedFlowActivity] and FakeRelayTransportTest.
 */
internal object ConnectedFlowPreparation {
    /**
     * Pairs with the fixture Mac unless it is already paired, opens [RelayFixture.prepare], leaves
     * the fixture draft there and returns to the projects, all through the production repository.
     * A repeated pass only reconnects, so it resets the stored navigation snapshot to the prepare
     * chat after a benchmark opened another one.
     */
    suspend fun visit(repository: RemoteRepository, fixture: RelayFixture, now: Long): Boolean {
        val projects = RemoteSelection(fixture.routeId)
        if (
            repository.activate(projects, ActivationMode.USER_OPEN) != projects &&
                repository.pair(fixture.pairingCode(now + 120_000)) != projects
        ) return false
        if (repository.activate(fixture.prepare, ActivationMode.USER_OPEN) != fixture.prepare) return false
        repository.draft(fixture.prepareDraft)
        if (repository.activate(projects, ActivationMode.USER_OPEN) != projects) return false
        repository.flush()
        return true
    }

    /**
     * Whether the repository settled on the fixture Mac's projects after [visit] and [stored], the
     * navigation snapshot on disk, is the one every benchmark starts from: the prepare chat as the
     * last chat, and the refreshed quick chats [state] shows. Nothing the visit started then
     * changes the snapshot again, so the process can be killed.
     */
    fun settled(fixture: RelayFixture, state: RemoteState, stored: NavigationSnapshot?): Boolean =
        unsettled(fixture, state, stored) == null

    /**
     * Null when [settled]; otherwise the first condition that is not met yet, so a prepare pass
     * that gives up can say what it was waiting for.
     */
    fun unsettled(fixture: RelayFixture, state: RemoteState, stored: NavigationSnapshot?): String? {
        if (!state.connected) return "not connected"
        if (state.loading) return "loading"
        if (state.selection != RemoteSelection(fixture.routeId)) return "selection ${state.selection}"
        val chats = state.projectChats
        val projectIds = fixture.sessions.mapTo(mutableSetOf()) { it.projectId }
        if (chats.isEmpty()) return "no quick chats"
        val unverified = chats.filterNot { it.verified }.map { it.projectId }.distinct()
        if (unverified.isNotEmpty()) return "quick chats of $unverified unverified"
        val chatProjects = chats.mapTo(mutableSetOf()) { it.projectId }
        if (chatProjects != projectIds) return "quick chats from $chatProjects, expected $projectIds"
        if (stored == null) return "no stored snapshot"
        if (stored.projectId != fixture.prepare.projectId || stored.sessionId != fixture.prepare.sessionId)
            return "stored snapshot on ${stored.projectId}/${stored.sessionId}"
        val storedChats = stored.projectChats.map { (it["session"] as? JsonObject)?.optionalText("id") }
        if (storedChats != chats.map { it.session.optionalText("id") }) return "stored quick chats differ"
        return null
    }

    /**
     * `hit` or `miss`: whether the newest activation that opened a chat found it in the
     * repository's chat cache (memory, or the navigation snapshot restored from disk). Null before
     * any chat opened in this process.
     */
    fun lastChatCache(): String? =
        Diagnostics.report().activations
            .firstOrNull { it.outcome.startsWith("ok:") && ":chat." in it.outcome }
            // Later tags such as `:snapshot.held` follow the chat's.
            ?.outcome?.substringAfterLast(":chat.")?.substringBefore(':')
}
