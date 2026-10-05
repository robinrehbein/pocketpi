package de.joinnoah.pi.remote

import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal fun canAbortRemoteRun(state: RemoteState, activeRouteId: String?): Boolean =
    state.connected &&
        !state.loading &&
        state.answering.isEmpty() &&
        activeRouteId == state.selection.routeId &&
        state.selection.sessionId != null

/**
 * A subagent child may be aborted from its parent chat: it must be a running or waiting session
 * whose parent is the selected session on the active route.
 */
internal fun canAbortSubagent(
    state: RemoteState,
    sessionId: String,
    activeRouteId: String? = state.selection.routeId,
): Boolean {
    val parentId = state.selection.sessionId ?: return false
    if (!state.connected || state.loading || state.selection.routeId == null ||
        activeRouteId != state.selection.routeId || sessionId == parentId
    ) return false
    val target = state.sessions.find { (it["id"] as? JsonPrimitive)?.contentOrNull == sessionId }
        ?: return false
    fun field(key: String): String? =
        (target[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return field("parentSessionId") == parentId && field("status") in setOf("running", "waiting")
}

private const val MAX_TOOL_OUTPUT_BYTES = 1_048_576L
private const val TOOL_OUTPUT_CHUNK_BYTES = 49_152
private const val MAX_TOOL_OUTPUT_DATA_CHARS = 65_536
private const val SESSION_REFRESH_INTERVAL_MILLIS = 5_000L

private fun opaqueId(value: String): Boolean =
    value.isNotEmpty() &&
        value.encodeToByteArray().size <= 256 &&
        value.none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }

internal class ValidatedToolOutputChunk(
    val totalBytes: Long,
    val bytes: ByteArray,
    val truncated: Boolean,
    val isError: Boolean,
)

/** Validates one `tool_output` result against the request that produced it. */
internal fun validatedToolOutputChunk(
    data: JsonObject,
    sessionId: String,
    toolCallId: String,
    offset: Long,
    expectedTotal: Long?,
): ValidatedToolOutputChunk {
    require(data.text("kind") == "tool_output")
    require(data.text("sessionId") == sessionId && data.text("toolCallId") == toolCallId)
    require(data.long("offset") == offset)
    val total = data.long("totalBytes")
    require(total in 0..MAX_TOOL_OUTPUT_BYTES && (expectedTotal == null || total == expectedTotal))
    require(offset <= total)
    val encoded = data.text("data")
    require(encoded.length <= MAX_TOOL_OUTPUT_DATA_CHARS)
    val bytes = Wire.decode(encoded)
    require(bytes.size <= TOOL_OUTPUT_CHUNK_BYTES)
    require(bytes.isNotEmpty() || offset == total)
    require(offset + bytes.size <= total)
    return ValidatedToolOutputChunk(total, bytes, data.flag("truncated"), data.flag("isError"))
}

class DefaultRemoteRepository(
    private val pairings: PairingStorage,
    private val draftStorage: DraftStorage,
    private val transport: RemoteTransport,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val attachmentStorage: AttachmentStorage? = null,
    private val attachmentImporter: (suspend (String, Boolean) -> LocalAttachment)? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val onRecoverySignal: (RemoteRecovery.Signal) -> Unit = {},
    private val navigationStorage: NavigationSnapshotStorage? = null,
    /** Called once a pairing is removed, so stores outside the repository can forget the host. */
    private val onUnpaired: (String) -> Unit = {},
) : RemoteRepository {
    private val routeCapabilities =
        setOf(
            STEER_CAPABILITY,
            FOLLOW_UP_CAPABILITY,
            TOOL_OUTPUT_CAPABILITY,
            BACKGROUND_JOBS_CAPABILITY,
            BACKGROUND_JOBS_LIST_LEASE_CAPABILITY,
            // The host advertises subagent control only on this route, never at authentication.
            SUBAGENT_CONTROL_CAPABILITY,
            PROJECT_OPEN_CAPABILITY,
            SESSION_FORK_CAPABILITY,
            GIT_CAPABILITY,
            FILES_CAPABILITY,
            PROVIDER_AUTH_CAPABILITY,
        )
    private val mutable = MutableStateFlow(RemoteState())
    override val state = mutable.asStateFlow()
    private val attachmentMutex = Mutex()
    private var importJob: Job? = null
    private var sendJob: Job? = null
    private val attachmentInFlight = mutableSetOf<String>()
    private var persistedAttachmentIds = emptySet<String>()

    private fun journalAttachmentIds(values: Map<DraftKey, StoredDraft>): Set<String> =
        values.values.flatMap { it.attachments + it.submittedAttachments }.map { it.id }.toSet()

    private var configurationVersion = 0L
    private var contextVersion = 0L
    private var commandsVersion = 0L
    private var advisorVersion = 0L
    private var configurationNeedsRefresh = false
    private var toolOutputJob: Job? = null
    private var toolOutputVersion = 0L
    private var sessionRefreshJob: Job? = null
    private var lastSessionRefreshAt: Long? = null
    private var folderVersion = 0L
    private var cloneStatusJob: Job? = null
    /** Entries seen in listings of the current folder host, by path, to know a folder's trust. */
    private val folderEntries = LinkedHashMap<String, FolderEntry>()
    /** host.event payloads that arrived before their clone.started result, by clone ID. */
    private val earlyCloneEvents = linkedMapOf<String, JsonObject>()
    /** Folder and clone requests; a selection change does not cancel them, a lost connection does. */
    private val folderCompletions = mutableMapOf<String, CompletableDeferred<JsonObject>>()
    private var openCounter = 0L
    private var providerVersion = 0L
    private var providerStatusJob: Job? = null
    /** Login events that arrived before the start result created the flow they belong to. */
    private val earlyProviderUpdates = mutableListOf<ProviderAuthUpdate>()

    /** An open begun by beginOpenFolder, with the flags of the prompt the user confirmed. */
    private data class PendingOpen(
        val id: Long,
        val routeId: String,
        val path: String,
        val trust: Boolean,
        val session: Long,
    )

    private var activeOpen: PendingOpen? = null
    /** Bumped when the connection is lost or replaced; older folder replies write nothing. */
    private var folderSession = 0L

    private data class CommandDispatch(val key: DraftKey, val epoch: Long, var revision: Long = -1)

    private val commandDispatches = linkedMapOf<String, CommandDispatch>()
    private val earlyFollowUpReceipts = linkedMapOf<Pair<String, String>, String>()
    private var selectionEpoch = 0L
    private var generation = 0L
    private val requests = RequestLedger()
    private val completions = mutableMapOf<String, CompletableDeferred<JsonObject>>()
    private var activeHost: PairedHost? = null
    private var ready: CompletableDeferred<Unit>? = null
    private var pairing: CompletableDeferred<PairedHost>? = null
    private var retry: Job? = null
    private var recoveryJob: Job? = null
    private var deferredRecovery: RemoteRecovery.Signal? = null
    private var reconnectAttempts = 0
    private var reconnectEnabled = true
    private val recovery = RemoteRecovery()
    private var timeline: Timeline? = null
    private data class CachedSessions(
        val routeId: String,
        val projectId: String,
        val project: JsonObject,
        val sessions: List<JsonObject>,
    )

    private data class CachedChat(
        val selection: RemoteSelection,
        val session: JsonObject,
        val messages: List<JsonObject>,
        val status: String,
        val nextCursor: String?,
    )

    private var cachedProjects: Pair<String, List<JsonObject>>? = null
    private var cachedProjectChats: Pair<String, List<ProjectChatSummary>>? = null
    private val projectChatCandidates = linkedMapOf<String, List<ProjectChatSummary>>()
    private var projectChatsJob: Job? = null
    private var attentionRefreshJob: Job? = null
    private var lastProjectChatsRefreshAt: Long? = null
    private var cachedSessions: CachedSessions? = null
    private var cachedChat: CachedChat? = null
    private val savedNavigation = linkedMapOf<String, NavigationSnapshot>()
    private data class NavigationWrite(
        val version: Long,
    )

    private val navigationWrites = Channel<NavigationWrite>(Channel.CONFLATED)
    private val navigationWriteMutex = Mutex()
    private var navigationVersion = 0L
    private var navigationWriteJob: Job? = null
    private var navigationLoaded = navigationStorage == null
    private var pairingsLoaded = false
    private val removedRoutes = mutableSetOf<String>()
    private val removalJobs = mutableMapOf<String, Job>()
    private var resynchronizing = false
    private var pushToken: String? = null
    private var pushTokenKnown = false
    private val drafts = mutableMapOf<DraftKey, StoredDraft>()
    private val closingSessions = mutableSetOf<String>()
    private val unsharingProjects = mutableSetOf<String>()
    private val sessionStatusRevisions = linkedMapOf<String, Long>()
    private val sessionStatusOverrides = mutableMapOf<String, String>()
    private val sessionTitleRevisions = linkedMapOf<String, Long>()
    private val sessionTitleOverrides = mutableMapOf<String, String>()
    private val renamingIds = mutableSetOf<String>()

    private data class Write(
        val values: Map<DraftKey, StoredDraft>?,
        val done: CompletableDeferred<Unit>,
    )

    private val writes = Channel<Write>(Channel.UNLIMITED)
    private var storageFailure: Exception? = null
    private var draftsAvailable = false
    private val initialized = CompletableDeferred<Unit>()
    private var pairingEpoch: Long? = null
    private val pairingWrites = Mutex()

    init {
        scope.launch {
            val hosts =
                try {
                    withContext(io) { pairings.load() }.also { pairingsLoaded = true }
                } catch (_: Exception) {
                    reportError(R.string.remote_storage_error)
                    null
                }
            if (hosts != null) {
                update { it.copy(hosts = hosts.filterNot { host -> host.routeId in removedRoutes }) }
                navigationStorage?.let { storage ->
                    try {
                        val allowed = hosts.mapTo(mutableSetOf()) { it.routeId }
                        savedNavigation.putAll(
                            withContext(io) { storage.load() }
                                .filterKeys { it in allowed && it !in removedRoutes }
                        )
                        navigationLoaded = true
                    } catch (_: Exception) {
                        reportError(R.string.remote_storage_error)
                    }
                }
            }
            try {
                drafts.putAll(withContext(io) { draftStorage.load() }.mapValues { (_, value) ->
                    value.copy(followUps = value.followUps.map { entry ->
                        if (entry.status in setOf("pending", "accepted")) entry.copy(status = "uncertain") else entry
                    })
                })
                persistedAttachmentIds = journalAttachmentIds(drafts)
                draftsAvailable = true
                cleanupAttachments()
            } catch (e: Exception) {
                storageFailure = e
                reportError(R.string.remote_storage_error)
            }
            initialized.complete(Unit)
        }
        scope.launch {
            for (write in writes) {
                try {
                    if (write.values != null) {
                        withContext(io) { draftStorage.save(write.values) }
                        persistedAttachmentIds = journalAttachmentIds(write.values)
                        storageFailure = null
                    } else storageFailure?.let { throw it }
                    write.done.complete(Unit)
                } catch (e: Exception) {
                    storageFailure = e
                    reportError(R.string.remote_storage_error)
                    write.done.completeExceptionally(e)
                }
            }
        }
        scope.launch {
            for (write in navigationWrites) {
                try {
                    initialized.await()
                    navigationWriteMutex.withLock {
                        if (
                            write.version == navigationVersion &&
                                ensureNavigationLoaded() &&
                                write.version == navigationVersion
                        ) {
                            val snapshots = savedNavigation.toMap()
                            withContext(io) { navigationStorage?.save(snapshots) }
                        }
                    }
                } catch (_: Exception) {
                    reportError(R.string.remote_storage_error)
                }
            }
        }
        transport.listener =
            object : RemoteTransport.Listener {
                override fun ready(capabilities: Set<String>) {
                    reconnectAttempts = 0
                    update {
                        it.copy(
                            connected = true,
                            capabilities = capabilities,
                            connection = R.string.remote_connected,
                            error = null,
                        )
                    }
                    ready?.complete(Unit)
                    registerPush()
                }

                override fun approval() {
                    update { it.copy(connection = R.string.remote_approval) }
                }

                override suspend fun persistPairing(host: PairedHost) = pairingWrites.withLock {
                    val epoch = checkNotNull(pairingEpoch)
                    checkEpoch(epoch)
                    check(pairing?.isActive == true)
                    check(pairingsLoaded)
                    check(host.routeId !in removedRoutes)
                    val hosts = state.value.hosts.filterNot { it.routeId == host.routeId } + host
                    try {
                        withContext(io) { pairings.save(hosts) }
                    } catch (e: Exception) {
                        reportError(R.string.remote_storage_error)
                        throw e
                    }
                    checkEpoch(epoch)
                    update { it.copy(hosts = hosts) }
                }

                override fun paired(host: PairedHost) {
                    pairing?.complete(host)
                }

                override fun message(payload: JsonObject) {
                    receive(payload)
                }

                override fun failed(reconnect: Boolean, error: Int) {
                    this@DefaultRemoteRepository.failed(reconnect, error)
                }
            }
    }

    private fun update(block: (RemoteState) -> RemoteState) {
        mutable.value = block(mutable.value)
    }

    private fun clearNavigationCache() {
        cachedProjects = null
        cachedProjectChats = null
        projectChatCandidates.clear()
        projectChatsJob?.cancel()
        attentionRefreshJob?.cancel()
        cachedSessions = null
        cachedChat = null
    }

    private suspend fun ensureNavigationLoaded(): Boolean {
        if (navigationLoaded) return true
        if (!pairingsLoaded) return false
        val storage = navigationStorage ?: return false
        return try {
            val stored = withContext(io) { storage.load() }
            val allowed = state.value.hosts.mapTo(mutableSetOf()) { it.routeId }
            val fresh = savedNavigation.toMap()
            savedNavigation.clear()
            savedNavigation.putAll(
                stored.filterKeys { it in allowed && it !in removedRoutes }
            )
            savedNavigation.putAll(
                fresh.filterKeys { it in allowed && it !in removedRoutes }
            )
            while (savedNavigation.size > 8) savedNavigation.remove(savedNavigation.keys.first())
            navigationLoaded = true
            true
        } catch (_: Exception) {
            reportError(R.string.remote_storage_error)
            false
        }
    }

    private fun queueNavigationWrite(immediate: Boolean = false) {
        if (navigationStorage == null) return
        navigationVersion++
        if (immediate) {
            navigationWriteJob?.cancel()
            navigationWriteJob = null
            navigationWrites.trySend(NavigationWrite(navigationVersion))
        } else if (navigationWriteJob?.isActive != true) {
            navigationWriteJob =
                scope.launch {
                    delay(1000)
                    navigationWrites.send(NavigationWrite(navigationVersion))
                }
        }
    }

    private fun persistNavigation(immediate: Boolean = false) {
        if (navigationStorage == null) return
        val routeId = activeHost?.routeId ?: return
        if (routeId in removedRoutes) return
        val projects = cachedProjects?.takeIf { it.first == routeId }?.second ?: return
        val projectChats = cachedProjectChats?.takeIf { it.first == routeId }?.second.orEmpty()
        val sessions = cachedSessions?.takeIf { it.routeId == routeId }
        val chat = cachedChat?.takeIf { it.selection.routeId == routeId }
        val snapshot =
            NavigationSnapshot(
                routeId = routeId,
                projects = projects.take(100),
                projectId = sessions?.projectId,
                sessions = sessions?.sessions.orEmpty().take(300),
                sessionId = chat?.selection?.sessionId,
                messages = chat?.messages.orEmpty().takeLast(80),
                projectChats = projectChats.take(12).map { item ->
                    Wire.objectOf(
                        "projectId" to item.projectId,
                        "projectName" to item.projectName,
                        "session" to item.session,
                    )
                },
            )
        savedNavigation.remove(routeId)
        savedNavigation[routeId] = snapshot
        while (savedNavigation.size > 8) savedNavigation.remove(savedNavigation.keys.first())
        queueNavigationWrite(immediate)
    }

    private fun restoreNavigation(routeId: String) {
        val saved = savedNavigation[routeId] ?: return
        if (cachedProjects?.first != routeId) cachedProjects = routeId to saved.projects
        if (cachedProjectChats?.first != routeId) {
            val projectIds = saved.projects.mapTo(mutableSetOf()) { it.text("id") }
            val chats = saved.projectChats.mapNotNull { item ->
                val projectId = item.optionalText("projectId") ?: return@mapNotNull null
                val session = item["session"] as? JsonObject ?: return@mapNotNull null
                val sessionId = session.optionalText("id") ?: return@mapNotNull null
                if (projectId !in projectIds || sessionId.isBlank()) return@mapNotNull null
                ProjectChatSummary(
                    projectId,
                    item.optionalText("projectName").orEmpty(),
                    session,
                )
            }
            cachedProjectChats = routeId to sortedProjectChats(chats)
            projectChatCandidates.clear()
            cachedProjectChats?.second.orEmpty().groupBy { it.projectId }
                .forEach { (id, rows) -> projectChatCandidates[id] = rows }
        }
        val project = saved.projects.find { it.text("id") == saved.projectId }
        if (project != null && cachedSessions?.routeId != routeId) {
            cachedSessions = CachedSessions(routeId, project.text("id"), project, saved.sessions)
        }
        val session = saved.sessions.find { it.text("id") == saved.sessionId }
        if (project != null && session != null && cachedChat?.selection?.routeId != routeId) {
            cachedChat =
                CachedChat(
                    RemoteSelection(routeId, project.text("id"), session.text("id")),
                    session,
                    saved.messages,
                    "offline",
                    null,
                )
        }
    }

    private fun publishProjectChats(routeId: String) {
        if (activeHost?.routeId != routeId || state.value.selection.routeId != routeId) return
        val chats = sortedProjectChats(projectChatCandidates.values.flatten())
        cachedProjectChats = routeId to chats
        update { it.copy(projectChats = chats) }
        persistNavigation()
    }

    private fun refreshProjectChats(routeId: String, projects: List<JsonObject>, epoch: Long) {
        projectChatsJob?.cancel()
        lastProjectChatsRefreshAt = now()
        projectChatCandidates.clear()
        val known = cachedProjectChats?.takeIf { it.first == routeId }?.second.orEmpty()
        val projectsById = projects.associateBy { it.text("id") }
        known.filter { it.projectId in projectsById }.groupBy { it.projectId }.forEach { (id, chats) ->
            val name = projectsById.getValue(id).text("name")
            projectChatCandidates[id] = chats.map { it.copy(projectName = name, verified = false) }
        }
        publishProjectChats(routeId)
        projectChatsJob = scope.launch {
            val permits = Semaphore(4)
            projects.forEach { project ->
                launch {
                    val projectId = project.text("id")
                    try {
                        permits.withPermit {
                            val sessions = request("sessions.list", epoch, "projectId" to projectId)
                                .array("items")
                            checkEpoch(epoch)
                            if (activeHost?.routeId != routeId) return@withPermit
                            projectChatCandidates[projectId] =
                                sortedProjectChats(sessions.map { session ->
                                    ProjectChatSummary(
                                        projectId,
                                        project.text("name"),
                                        withSessionTitle(withSessionStatus(session)),
                                        verified = true,
                                    )
                                })
                            publishProjectChats(routeId)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A failed project request leaves its cached rows offline.
                    }
                }
            }
        }
    }

    private fun refreshMissingAttention(sessionId: String, status: String) {
        val current = state.value
        val routeId = current.selection.routeId ?: return
        if (status !in setOf("waiting", "running") ||
            !current.connected || current.selection.projectId != null ||
            activeHost?.routeId != routeId || current.projects.isEmpty() ||
            projectChatCandidates.values.any { chats ->
                chats.any { it.session.text("id") == sessionId }
            } || attentionRefreshJob?.isActive == true
        ) return
        val epoch = selectionEpoch
        attentionRefreshJob = scope.launch {
            delay(300)
            withTimeoutOrNull(1200) { projectChatsJob?.join() }
            val elapsed = lastProjectChatsRefreshAt?.let { (now() - it).coerceAtLeast(0) }
            if (elapsed != null && elapsed < 1500) delay(1500 - elapsed)
            if (epoch != selectionEpoch || activeHost?.routeId != routeId ||
                !state.value.connected || state.value.selection != RemoteSelection(routeId)
            ) return@launch
            if (projectChatCandidates.values.any { chats ->
                    chats.any { it.session.text("id") == sessionId }
                }) return@launch
            refreshProjectChats(routeId, state.value.projects, epoch)
        }
    }

    private fun cacheCurrentScreen(immediate: Boolean = false) {
        val current = state.value
        val routeId = current.selection.routeId ?: return
        if (!current.connected || current.loading || activeHost?.routeId != routeId) return
        cachedProjects = routeId to current.projects
        cachedProjectChats = routeId to current.projectChats
        val projectId = current.selection.projectId
        val project = current.project
        if (projectId != null && project != null) {
            val session = current.session
            val sessions =
                if (session != null && current.sessions.none { it.text("id") == session.text("id") })
                    listOf(session) + current.sessions
                else current.sessions
            cachedSessions = CachedSessions(routeId, projectId, project, sessions)
            if (session != null && current.selection.sessionId == session.text("id")) {
                cachedChat =
                    CachedChat(
                        current.selection,
                        session,
                        current.messages,
                        current.status,
                        current.nextCursor,
                    )
            }
        }
        persistNavigation(immediate)
    }

    private fun showCachedSelection(selection: RemoteSelection, host: PairedHost) {
        restoreNavigation(host.routeId)
        val projects = cachedProjects?.takeIf { it.first == host.routeId }?.second ?: return
        val projectId = selection.projectId
        if (projectId == null) {
            select(RemoteSelection(host.routeId), host, projects)
            return
        }
        val sessions =
            cachedSessions?.takeIf { it.routeId == host.routeId && it.projectId == projectId }
                ?: run {
                    val project = projects.find { it.text("id") == projectId } ?: return
                    val summaries = cachedProjectChats?.takeIf { it.first == host.routeId }
                        ?.second.orEmpty().filter { it.projectId == projectId }
                    if (summaries.isEmpty()) return
                    CachedSessions(host.routeId, projectId, project, summaries.map { it.session })
                }
        if (selection.sessionId == null) {
            select(RemoteSelection(host.routeId, projectId), host, projects, sessions.project, sessions.sessions)
            return
        }
        val chat = cachedChat?.takeIf { it.selection == selection }
        val session = chat?.session ?: sessions.sessions.find { it.text("id") == selection.sessionId }
            ?: return
        select(selection, host, projects, sessions.project, sessions.sessions, session, chat)
    }

    override fun closeSession(sessionId: String) {
        val selection = state.value.selection
        val projectId = selection.projectId ?: return
        val session = state.value.sessions.find { it.text("id") == sessionId }
        if (
            sessionId.isBlank() ||
                sessionId.length > 256 ||
                sessionId in closingSessions ||
                session?.text("origin") != "rpc" ||
                session.text("status") !in setOf("idle", "running", "waiting") ||
                !state.value.connected ||
                SESSION_CLOSE_CAPABILITY !in state.value.capabilities
        )
            return
        val epoch = selectionEpoch
        closingSessions += sessionId
        scope.launch {
            try {
                val result =
                    request(
                        "session.close",
                        epoch,
                        "projectId" to projectId,
                        "sessionId" to sessionId,
                    )
                require(result.text("kind") == "accepted" && result.text("sessionId") == sessionId)
                if (epoch == selectionEpoch) {
                    cachedSessions = null
                    if (cachedChat?.selection?.sessionId == sessionId) cachedChat = null
                    activate(state.value.selection, ActivationMode.RESTORE, force = true)
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (_: Exception) {
                if (epoch == selectionEpoch) reportError(R.string.remote_request_error)
            } finally {
                closingSessions -= sessionId
            }
        }
    }

    override fun unshareProject(projectId: String) {
        val current = state.value
        if (projectId.isBlank() || projectId.length > 256 || !current.connected || current.loading ||
            current.selection.projectId != null ||
            projectId in unsharingProjects ||
            current.projects.none { it.text("id") == projectId } ||
            PROJECT_UNSHARE_CAPABILITY !in current.capabilities
        ) return
        val epoch = selectionEpoch
        unsharingProjects += projectId
        scope.launch {
            try {
                val result = request("project.unshare", epoch, "projectId" to projectId)
                require(result.text("kind") == "accepted")
                if (epoch == selectionEpoch) {
                    clearNavigationCache()
                    activate(state.value.selection, ActivationMode.RESTORE, force = true, useCache = false)
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (_: Exception) {
                if (epoch == selectionEpoch) reportError(R.string.remote_request_error)
            } finally {
                unsharingProjects -= projectId
            }
        }
    }

    private fun cancelRecovery() {
        recoveryJob?.cancel()
        recoveryJob = null
        deferredRecovery = null
    }

    private fun hasPendingRecoveryWork() =
        state.value.let {
            it.sending ||
                it.answering.isNotEmpty() ||
                it.configurationLoading ||
                it.configurationChanging ||
                it.commandsLoading ||
                it.importingAttachments
        }

    private fun resumeDeferredRecovery() {
        val signal = deferredRecovery ?: return
        if (state.value.loading || pairing?.isActive == true || hasPendingRecoveryWork()) return
        deferredRecovery = null
        recover(signal)
    }

    override fun dismissError() = update { it.copy(error = null) }

    override fun dismissFollowUp(requestId: String) {
        val key = currentDraftKey() ?: return
        val draft = drafts[key] ?: return
        if (draft.followUps.none { it.requestId == requestId && it.status !in setOf("pending", "accepted") }) return
        val entries = draft.followUps.filterNot { it.requestId == requestId }
        drafts[key] = draft.copy(followUps = entries)
        persist()
        update { it.copy(followUps = entries) }
    }

    override fun reportError(resource: Int) = update { it.copy(error = resource) }

    private fun checkEpoch(epoch: Long) {
        if (epoch != selectionEpoch) throw CancellationException("Selection changed")
    }

    private fun invalidateRequests(failure: Exception? = null) {
        requests.clear()
        completions.values.toList().forEach {
            if (failure == null) it.cancel() else it.completeExceptionally(failure)
        }
        completions.clear()
    }

    override fun cancelSelection() {
        cacheCurrentScreen(immediate = true)
        projectChatsJob?.cancel()
        attentionRefreshJob?.cancel()
        cancelRecovery()
        importJob?.cancel()
        if (state.value.attachmentProgress != null) sendJob?.cancel()
        stopToolOutput()
        sessionRefreshJob?.cancel()
        selectionEpoch++
        configurationVersion++
        configurationNeedsRefresh = false
        contextVersion++
        commandsVersion++
        if (pairing?.isActive == true) {
            pairing?.cancel()
            transport.close()
        }
        invalidateRequests()
        resynchronizing = false
        update {
            it.copy(
                loading = false,
                sending = false,
                answering = emptySet(),
                configurationLoading = false,
                configurationChanging = false,
                contextLoading = false,
                compactionRequesting = false,
                commandsLoading = false,
                toolOutput = null,
            )
        }
    }

    private fun markPendingFollowUpsUncertain(routeId: String?): Boolean {
        if (routeId == null) return false
        var changed = false
        drafts.keys.filter { it.routeId == routeId }.forEach { key ->
            val draft = drafts.getValue(key)
            if (draft.followUps.any { it.status in setOf("pending", "accepted") }) {
                drafts[key] = draft.copy(followUps = draft.followUps.map { entry ->
                    if (entry.status in setOf("pending", "accepted")) entry.copy(status = "uncertain") else entry
                })
                changed = true
            }
        }
        if (changed) {
            currentDraftKey()?.takeIf { it.routeId == routeId }?.let { key ->
                update { it.copy(followUps = drafts[key]?.followUps.orEmpty()) }
            }
        }
        return changed
    }

    private suspend fun connect(host: PairedHost, epoch: Long) {
        if (activeHost?.routeId == host.routeId && state.value.connected) return
        if (activeHost?.routeId != host.routeId || ready?.isActive != true) {
            val previousRoute = activeHost?.routeId
            if (previousRoute != host.routeId && markPendingFollowUpsUncertain(previousRoute)) {
                persist().await()
            }
            if (previousRoute != host.routeId) clearNavigationCache()
            cachedProjectChats = cachedProjectChats?.takeIf { it.first == host.routeId }
                ?.let { it.first to it.second.map { chat -> chat.copy(verified = false) } }
            projectChatCandidates.replaceAll { _, chats ->
                chats.map { it.copy(verified = false) }
            }
            generation++
            failFolderRequests()
            cloneStatusJob?.cancel()
            providerStatusJob?.cancel()
            providerVersion++
            earlyProviderUpdates.clear()
            folderVersion++
            folderSession++
            activeOpen = null
            sessionStatusRevisions.clear()
            sessionStatusOverrides.clear()
            sessionTitleRevisions.clear()
            sessionTitleOverrides.clear()
            ready?.cancel()
            activeHost = host
            ready = CompletableDeferred()
            stopToolOutput()
            update {
                it.copy(
                    connected = false,
                    connection = R.string.remote_connecting,
                    compaction = null,
                    compactionRequesting = false,
                    toolOutput = null,
                    questions = emptyList(),
                    capabilities = emptySet(),
                    unavailableCapabilities = emptySet(),
                    childControlUnsupported = emptySet(),
                    configuration = null,
                    advisor = null,
                    advisorLoading = false,
                    advisorChanging = false,
                    commands = emptyList(),
                    status = "offline",
                    // A login goes on on the Mac; the same host recovers it once it is reachable.
                    providerAuth =
                        if (previousRoute != host.routeId) ProviderAuthState()
                        else it.providerAuth.offline(),
                    folders = it.folders.copy(
                        loaded = it.folders.loaded && !it.folders.loading,
                        loading = false,
                        error = null,
                        working = false,
                        trust = null,
                        notice = null,
                    ),
                )
            }
            transport.connect(host)
        }
        if (withTimeoutOrNull(30000) { checkNotNull(ready).await() } == null) {
            if (epoch == selectionEpoch) {
                generation++
                transport.close()
                ready?.cancel()
                ready = null
                activeHost = null
                update { it.copy(connected = false, connection = R.string.remote_offline) }
            }
            throw IllegalStateException("Connection timed out")
        }
        checkEpoch(epoch)
    }

    private suspend fun request(
        type: String,
        epoch: Long,
        vararg fields: Pair<String, Any?>,
        draft: String? = null,
        requestId: String = Wire.random(),
        onSent: (() -> Unit)? = null,
    ): JsonObject {
        checkEpoch(epoch)
        check(state.value.connected)
        // Older hosts accept this as an opaque request ID and return ordinary projects.
        val id = if (type == "projects.list") "capabilities.v1:$requestId" else requestId
        val payload = Wire.objectOf("type" to type, "requestId" to id, *fields)
        val completion = CompletableDeferred<JsonObject>()
        requests.add(
            id,
            PendingRequest(
                type,
                payload.optionalText("sessionId"),
                epoch,
                payload.optionalText("cursor"),
                draft,
                payload.optionalText("questionId"),
            ),
        )
        completions[id] = completion
        try {
            // Set before sending: a send that throws may still have reached the host.
            onSent?.invoke()
            transport.send(payload)
            val result =
                withTimeoutOrNull(30000) { completion.await() }
                    ?: throw IllegalStateException("Request timed out")
            checkEpoch(epoch)
            if (type == "projects.list" && result.text("kind") == "projects") {
                val advertised = (result["capabilities"] as? JsonArray)
                    ?.takeIf { it.size <= 16 }
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                val merged = routeCapabilities.filter { it in advertised }
                if (merged.isNotEmpty()) {
                    // Capabilities merge in additively as later projects.list replies arrive; a list
                    // lease that only now becomes possible starts leasing at once, not at the next
                    // session change or reconnect.
                    val hadLease = canLeaseJobsList(state.value)
                    update { it.copy(capabilities = it.capabilities + merged) }
                    if (!hadLease && canLeaseJobsList(state.value)) jobsController.refresh()
                }
                if (PROJECT_OPEN_CAPABILITY in merged) resumeClone()
                if (PROVIDER_AUTH_CAPABILITY in merged) resumeProviderAuth()
            }
            return result
        } finally {
            requests.take(id, epoch)
            completions.remove(id)
        }
    }

    private fun receive(payload: JsonObject) {
        try {
            when (payload.text("type")) {
                "result" -> {
                    val id = payload.text("requestId")
                    folderCompletions.remove(id)?.let { completion ->
                        if (payload.flag("ok")) completion.complete(payload.obj("data"))
                        else
                            payload.obj("error").let { error ->
                                completion.completeExceptionally(
                                    RemoteRequestException(error.text("code"), error["details"] as? JsonObject)
                                )
                            }
                        return
                    }
                    requests.take(id, selectionEpoch) ?: return
                    val completion = completions.remove(id) ?: return
                    if (payload.flag("ok")) completion.complete(payload.obj("data"))
                    else
                        payload.obj("error").let { error ->
                            completion.completeExceptionally(
                                RemoteRequestException(error.text("code"), error["details"] as? JsonObject)
                            )
                        }
                }
                "event" -> {
                    if (payload.text("kind") == "session.status") updateSessionStatus(payload)
                    if (payload.text("kind") == "message.upsert" &&
                        payload.text("sessionId") == state.value.selection.sessionId &&
                        // A held list lease already gets session.jobs.changed for this; older hosts need the hint.
                        !canLeaseJobsList(state.value) &&
                        (payload["message"] as? JsonObject)?.let {
                            it.optionalText("role") == "tool" && it.optionalText("toolName") in setOf("bash_bg", "bash_kill")
                        } == true
                    ) jobsController.refreshSoon()
                    if (payload.text("kind") == "advisor.status") {
                        val sessionId = payload.text("sessionId")
                        if (sessionId == state.value.selection.sessionId) {
                            // A live event is newer than any snapshot requested before it.
                            // Invalidate that request before it can overwrite this status.
                            advisorVersion++
                            val status = payload.obj("advisor")
                            val previous = state.value.advisor
                            if (previous == null) {
                                update { it.copy(advisorLoading = false, advisorChanging = false) }
                                refreshAdvisor()
                            }
                            else {
                                val merged = JsonObject(
                                    status + mapOf(
                                        "kind" to JsonPrimitive("advisor"),
                                        "choices" to JsonArray(previous.choices.map { choice ->
                                            buildJsonObject {
                                                put("provider", choice.provider)
                                                put("id", choice.id)
                                                put("name", choice.name)
                                                put("levels", JsonArray(choice.levels.map(::JsonPrimitive)))
                                            }
                                        }),
                                    )
                                )
                                update { it.copy(advisor = advisor(merged, sessionId),
                                    advisorLoading = false, advisorChanging = false) }
                            }
                        }
                    }
                    if (payload.text("kind") == "session.title") {
                        val title = payload.text("title")
                        val id = payload.text("sessionId")
                        val revision = payload.long("revision")
                        require(revision >= 0 && title.encodeToByteArray().size <= 4096)
                        if (revision > (sessionTitleRevisions[id] ?: -1L)) {
                            sessionTitleRevisions[id] = revision
                            sessionTitleOverrides[id] = title
                            if (sessionTitleRevisions.size > 4096) {
                                val oldest = sessionTitleRevisions.keys.first()
                                sessionTitleRevisions.remove(oldest)
                                sessionTitleOverrides.remove(oldest)
                            }
                            updateSessionTitle(id, title)
                        }
                    }
                    if (payload.text("kind") == "command.status") commandReceipt(payload)
                    if (payload.text("kind") in setOf("follow_up.status", "steer.status")) followUpReceipt(payload)
                    val timelineEvent = if (payload.text("kind") in setOf("follow_up.status", "steer.status"))
                        JsonObject(payload + ("kind" to JsonPrimitive("command.status"))) else payload
                    if (timeline?.event(timelineEvent) == true && !resynchronizing) snapshotAsync()
                    publishTimeline()
                    if (payload.text("kind") == "session.compaction" &&
                        payload.text("sessionId") == state.value.selection.sessionId &&
                        payload.text("state") == "done") {
                        update { it.copy(contextUsage = null) }
                        refreshContextUsage()
                    }
                }
                "host.event" ->
                    if (payload.optionalText("kind")?.startsWith("provider.auth.") == true) {
                        receiveProviderAuthEvent(payload)
                    } else {
                        cloneEvent(payload)?.let(::applyCloneUpdate)
                            ?: jobEvent(payload)?.let(jobsController::onEvent)
                    }
                else -> error("Unknown payload")
            }
        } catch (_: Exception) {
            failed(false, R.string.remote_connection_error)
        }
    }

    private fun followUpReceipt(event: JsonObject) {
        val key = DraftKey(activeHost?.routeId ?: return, event.text("sessionId"))
        val latest = drafts[key] ?: return
        val id = event.text("requestId")
        val status = event.text("status")
        require(status in setOf("accepted", "delivered", "cancelled", "uncertain"))
        val delivery = if (event.text("kind") == "steer.status") "steer" else "follow_up"
        if (latest.followUps.none { it.requestId == id }) {
            earlyFollowUpReceipts[id to delivery] = status
            while (earlyFollowUpReceipts.size > 128) earlyFollowUpReceipts.remove(earlyFollowUpReceipts.keys.first())
            return
        }
        val entries = latest.followUps.map {
            if (it.requestId == id && it.delivery == delivery && it.status !in setOf("delivered", "cancelled", "uncertain"))
                it.copy(status = status) else it
        }
        drafts[key] = latest.copy(followUps = entries)
        persist()
        if (currentDraftKey() == key) update { it.copy(followUps = entries) }
    }

    private fun withSessionStatus(session: JsonObject): JsonObject =
        sessionStatusOverrides[session.text("id")]?.let { status ->
            JsonObject(session + ("status" to JsonPrimitive(status)))
        } ?: session

    private fun withSessionTitle(session: JsonObject): JsonObject =
        sessionTitleOverrides[session.text("id")]?.let { title ->
            JsonObject(session + ("title" to JsonPrimitive(title)))
        } ?: session

    private fun updateSessionTitle(sessionId: String, title: String, expected: String? = null) {
        fun renamed(session: JsonObject): JsonObject =
            if (session.text("id") == sessionId &&
                (expected == null || session.text("title") == expected))
                JsonObject(session + ("title" to JsonPrimitive(title)))
            else session

        update { current ->
            current.copy(
                sessions = current.sessions.map(::renamed),
                session = current.session?.let(::renamed),
            )
        }
        val routeId = activeHost?.routeId
        if (state.value.connected && state.value.host?.routeId == routeId) {
            if (routeId != null) {
                projectChatCandidates.replaceAll { _, chats ->
                    chats.map { it.copy(session = renamed(it.session)) }
                }
                publishProjectChats(routeId)
            }
            cachedSessions?.takeIf { it.routeId == routeId }?.let { cached ->
                cachedSessions = cached.copy(sessions = cached.sessions.map(::renamed))
            }
            cachedChat?.takeIf { it.selection.routeId == routeId }?.let { cached ->
                cachedChat = cached.copy(session = renamed(cached.session))
            }
            persistNavigation()
        }
    }

    private fun updateSessionStatus(event: JsonObject) {
        val id = event.text("sessionId")
        val revision = event.long("revision")
        val status = event.text("status")
        require(revision >= 0 && status in setOf("idle", "running", "waiting", "offline"))
        if (revision <= (sessionStatusRevisions[id] ?: -1L)) return
        val known =
            sessionStatusOverrides[id]
                ?: state.value.sessions.firstOrNull { it.optionalText("id") == id }?.optionalText("status")
        sessionStatusRevisions[id] = revision
        sessionStatusOverrides[id] = status
        if (status != known) dropStaleChildControl(id)
        // Job events need a watch lease; a status change is the list's hint that jobs changed.
        // A held list lease already gets session.jobs.changed for this; older hosts need the hint.
        if (id == state.value.selection.sessionId && !canLeaseJobsList(state.value)) jobsController.refreshSoon()
        if (sessionStatusRevisions.size > 4096) {
            val oldest = sessionStatusRevisions.keys.first()
            sessionStatusRevisions.remove(oldest)
            sessionStatusOverrides.remove(oldest)
        }
        if (state.value.host?.routeId == activeHost?.routeId) {
            update { it.copy(sessions = it.sessions.map(::withSessionStatus)) }
            val routeId = activeHost?.routeId
            if (routeId != null && state.value.connected) {
                refreshMissingAttention(id, status)
                projectChatCandidates.replaceAll { _, chats ->
                    chats.map { item ->
                        if (item.session.text("id") == id)
                            item.copy(session = withSessionStatus(item.session), verified = true)
                        else item
                    }
                }
                publishProjectChats(routeId)
            }
            cachedSessions?.takeIf { it.routeId == routeId }?.let { cached ->
                cachedSessions = cached.copy(sessions = cached.sessions.map(::withSessionStatus))
            }
            cachedChat?.takeIf {
                it.selection.routeId == routeId && it.selection.sessionId == id
            }?.let { cached ->
                cachedChat =
                    cached.copy(session = withSessionStatus(cached.session), status = status)
            }
            persistNavigation()
        }
    }

    private suspend fun listSessions(projectId: String, epoch: Long): List<JsonObject> {
        sessionStatusOverrides.clear()
        sessionTitleOverrides.clear()
        return request("sessions.list", epoch, "projectId" to projectId)
            .array("items")
            .map { withSessionTitle(withSessionStatus(it)) }
    }

    private fun publishTimeline() {
        timeline?.let { t ->
            if (t.status != state.value.status) state.value.selection.sessionId?.let(::dropStaleChildControl)
            update {
                it.copy(
                    messages = t.messages,
                    questions = t.questions,
                    status = t.status,
                    nextCursor = t.nextCursor,
                    compaction = t.compaction,
                )
            }
            cacheCurrentScreen()
        }
    }

    private suspend fun snapshot(epoch: Long, older: Boolean = false) {
        val current = timeline ?: return
        val cursor = if (older) current.nextCursor ?: return else null
        resynchronizing = true
        try {
            val data =
                if (cursor == null)
                    request("session.snapshot", epoch, "sessionId" to current.sessionId)
                else
                    request(
                        "session.snapshot",
                        epoch,
                        "sessionId" to current.sessionId,
                        "cursor" to cursor,
                    )
            checkEpoch(epoch)
            if (current !== timeline) return
            current.snapshot(data, older)
            publishTimeline()
        } finally {
            if (epoch == selectionEpoch) resynchronizing = false
        }
        if (!older && current.needsSnapshot && epoch == selectionEpoch) snapshotAsync()
    }

    private fun snapshotAsync(older: Boolean = false) {
        val epoch = selectionEpoch
        scope.launch {
            try {
                snapshot(epoch, older)
            } catch (_: CancellationException) {} catch (_: Exception) {
                if (epoch == selectionEpoch) reportError(R.string.remote_request_error)
            }
        }
    }

    private fun select(
        selection: RemoteSelection,
        host: PairedHost?,
        projects: List<JsonObject>,
        project: JsonObject? = null,
        sessions: List<JsonObject> = emptyList(),
        session: JsonObject? = null,
        cachedChat: CachedChat? = null,
        // True only when `sessions` was actually fetched from the host during this call, as
        // opposed to a reused cache — a cached list may already be stale even on the same
        // connection, so it must never be trusted to declare a session closed.
        sessionsFresh: Boolean = false,
    ) {
        timeline = session?.let {
            Timeline(it.text("id")).apply { beginSnapshotEpoch(it.text("status")) }
        }
        val stored =
            selection.sessionId?.let { drafts[DraftKey(checkNotNull(selection.routeId), it)] }
                ?: StoredDraft()
        stopToolOutput()
        val previous = state.value.selection
        val sameSession = selection.sessionId != null && selection.routeId == previous.routeId &&
            selection.sessionId == previous.sessionId
        if (!sameSession) jobsController.stop()
        update {
            it.copy(
                selection = selection,
                compaction = null,
                compactionRequesting = false,
                toolOutput = null,
                changes = null,
                files = null,
                // A reconnect keeps the open jobs view; the refresh after it watches again.
                jobs = it.jobs?.takeIf { jobs -> sameSession && jobs.sessionId == selection.sessionId },
                host = host,
                projects = projects,
                projectChats = cachedProjectChats
                    ?.takeIf { cached -> cached.first == selection.routeId }
                    ?.second.orEmpty(),
                project = project,
                sessions = sessions.map { withSessionTitle(withSessionStatus(it)) },
                sessionsFresh = sessionsFresh,
                session = session?.let(::withSessionTitle),
                messages = cachedChat?.messages ?: emptyList(),
                questions = emptyList(),
                draft = stored.text,
                quote = stored.quote,
                attachments = stored.attachments,
                importingAttachments = false,
                attachmentProgress = null,
                uncertain = stored.mutationId != null,
                followUps = stored.followUps,
                sending = false,
                answering = emptySet(),
                status =
                    if (state.value.connected && activeHost?.routeId == selection.routeId)
                        cachedChat?.status ?: session?.text("status") ?: "offline"
                    else "offline",
                nextCursor = cachedChat?.nextCursor,
                configuration = null,
                contextUsage = null,
                advisor = null,
                advisorLoading = false,
                advisorChanging = false,
                contextLoading = false,
                unavailableCapabilities = emptySet(),
                childControlUnsupported = emptySet(),
                configurationLoading = false,
                configurationChanging = false,
                commands = emptyList(),
                commandsLoading = false,
                commandsTruncated = false,
                commandNotice = null,
            )
        }
        cacheCurrentScreen()
    }

    override suspend fun activate(
        selection: RemoteSelection,
        mode: ActivationMode,
    ): RemoteSelection {
        cancelRecovery()
        if (mode == ActivationMode.USER_OPEN) reconnectEnabled = true
        return activate(selection, mode, force = false)
    }

    private suspend fun activate(
        selection: RemoteSelection,
        mode: ActivationMode,
        force: Boolean,
        useCache: Boolean = true,
    ): RemoteSelection {
        initialized.await()
        val previousSelection = state.value.selection
        if (
            !force &&
                selection == state.value.selection &&
                !state.value.loading &&
                (selection.routeId == null ||
                    (state.value.connected && activeHost?.routeId == selection.routeId))
        )
            return selection
        if (!force) cancelSelection()
        if (!useCache) clearNavigationCache()
        val epoch = selectionEpoch
        retry?.cancel()
        update { it.copy(loading = true) }
        try {
            val host = state.value.hosts.find { it.routeId == selection.routeId }
            if (host == null) {
                select(RemoteSelection(), null, emptyList())
                return state.value.selection
            }
            val alreadyConnected = state.value.connected && activeHost?.routeId == host.routeId
            if (!alreadyConnected) {
                stopToolOutput()
                update {
                    it.copy(
                        connected = false,
                        connection = R.string.remote_connecting,
                        compaction = null,
                        compactionRequesting = false,
                        toolOutput = null,
                        questions = emptyList(),
                        capabilities = emptySet(),
                        unavailableCapabilities = emptySet(),
                        childControlUnsupported = emptySet(),
                        configuration = null,
                        contextUsage = null,
                        advisor = null,
                        advisorLoading = false,
                        advisorChanging = false,
                        contextLoading = false,
                        commands = emptyList(),
                        status = "offline",
                    )
                }
            }
            if (useCache && mode == ActivationMode.RESTORE) showCachedSelection(selection, host)
            connect(host, epoch)
            if (useCache && mode == ActivationMode.RESTORE) showCachedSelection(selection, host)
            val projects =
                cachedProjects
                    ?.takeIf { useCache && !force && alreadyConnected && it.first == host.routeId }
                    ?.second
                    ?: request("projects.list", epoch).array("items")
            checkEpoch(epoch)
            cachedProjects = host.routeId to projects
            val project = projects.find { it.text("id") == selection.projectId }
            val parent = RemoteSelection(host.routeId, project?.text("id"))
            if (project == null) {
                select(parent, host, projects)
                refreshProjectChats(host.routeId, projects, epoch)
                return parent
            }
            val cached =
                cachedSessions
                    ?.takeIf {
                        useCache &&
                            !force &&
                            alreadyConnected &&
                            it.routeId == host.routeId &&
                            it.projectId == project.text("id")
                    }
                    ?.sessions
            // A session opened by hand may have registered after the cached list was fetched
            // (a subagent child, for example), so a miss there asks the host again.
            var sessionsFetchedNow = false
            val sessions =
                cached?.takeUnless { list ->
                    mode == ActivationMode.USER_OPEN &&
                        selection.sessionId != null &&
                        list.none { it.text("id") == selection.sessionId }
                } ?: listSessions(project.text("id"), epoch).also { sessionsFetchedNow = true }
            checkEpoch(epoch)
            cachedSessions = CachedSessions(host.routeId, project.text("id"), project, sessions)
            val session = sessions.find { it.text("id") == selection.sessionId }
            if (
                session == null ||
                    (mode == ActivationMode.RESTORE &&
                        (session.optionalText("origin") !in setOf("rpc", "tui") ||
                            session.text("status") == "offline"))
            ) {
                select(parent, host, projects, project, sessions, sessionsFresh = sessionsFetchedNow)
                return parent
            }
            val chosen =
                if (mode == ActivationMode.USER_OPEN)
                    request("sessions.open", epoch, "sessionId" to session.text("id"))
                        .obj("session")
                else session
            val canonicalProject =
                projects.find { it.text("id") == chosen.text("projectId") }
                    ?: run {
                        select(parent, host, projects, project, sessions, sessionsFresh = sessionsFetchedNow)
                        return parent
                    }
            val canonical =
                RemoteSelection(host.routeId, canonicalProject.text("id"), chosen.text("id"))
            select(
                canonical,
                host,
                projects,
                canonicalProject,
                sessions,
                chosen,
                cachedChat?.takeIf { useCache && it.selection == canonical },
                sessionsFresh = sessionsFetchedNow,
            )
            try {
                snapshot(epoch)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                checkEpoch(epoch)
                if (cachedChat?.selection == canonical) {
                    reportError(R.string.remote_request_error)
                    return canonical
                }
                cachedChat = null
                select(parent, host, projects, project, sessions, sessionsFresh = sessionsFetchedNow)
                reportError(R.string.remote_request_error)
                return parent
            }
            refreshConfiguration()
            refreshAdvisor()
            refreshCommands()
            return canonical
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            checkEpoch(epoch)
            reportError(R.string.remote_request_error)
            return if (mode == ActivationMode.USER_OPEN) previousSelection else state.value.selection
        } finally {
            if (epoch == selectionEpoch) {
                update { it.copy(loading = false) }
                cacheCurrentScreen()
            }
        }
    }

    override suspend fun createSession(routeId: String, projectId: String): RemoteSelection {
        val parent = RemoteSelection(routeId, projectId)
        if (activate(parent) != parent) return state.value.selection
        cancelSelection()
        val epoch = selectionEpoch
        update { it.copy(loading = true) }
        try {
            val session = request("sessions.create", epoch, "projectId" to projectId).obj("session")
            cachedSessions = null
            cachedChat = null
            val project =
                state.value.projects.find { it.text("id") == session.text("projectId") }
                    ?: return parent
            val canonical = RemoteSelection(routeId, project.text("id"), session.text("id"))
            select(
                canonical,
                state.value.host,
                state.value.projects,
                project,
                state.value.sessions,
                session,
            )
            snapshot(epoch)
            refreshConfiguration()
            refreshAdvisor()
            refreshCommands()
            return canonical
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            reportError(R.string.remote_request_error)
            return state.value.selection
        } finally {
            if (epoch == selectionEpoch) update { it.copy(loading = false) }
        }
    }

    override suspend fun forkSession(
        source: RemoteSelection,
        messageId: String,
        mode: ForkMode,
    ): RemoteSelection {
        val routeId = source.routeId ?: return source
        val projectId = source.projectId ?: return source
        val sourceId = source.sessionId ?: return source
        val current = state.value
        // Older hosts close the connection on unknown commands.
        if (
            SESSION_FORK_CAPABILITY !in current.capabilities ||
                current.selection != source ||
                current.session?.optionalText("id") != sourceId
        )
            return source
        // The source chat stays shown until the host answers; only a fork switches to it.
        cancelSelection()
        val epoch = selectionEpoch
        update { it.copy(loading = true) }
        var failure = R.string.remote_request_error
        try {
            val result =
                request(
                    "session.fork",
                    epoch,
                    "sessionId" to sourceId,
                    "messageId" to messageId,
                    "mode" to mode.wire,
                )
            val session = result.obj("session")
            check(
                result.text("kind") == "fork" &&
                    result.text("sourceSessionId") == sourceId &&
                    session.text("projectId") == projectId
            )
            val project =
                state.value.project?.takeIf { it.optionalText("id") == projectId }
                    ?: state.value.projects.find { it.optionalText("id") == projectId }
            checkNotNull(project)
            cachedSessions = null
            cachedChat = null
            val canonical = RemoteSelection(routeId, projectId, session.text("id"))
            var notice: Int? = null
            if (mode == ForkMode.EDIT) {
                val draft = forkDraft(result.text("text"))
                drafts[DraftKey(routeId, session.text("id"))] =
                    StoredDraft(text = draft.text, quote = draft.quote)
                persist()
                if (draft.droppedAttachments) notice = R.string.remote_fork_attachments_dropped
            } else {
                notice = forkResendNotice(result.optionalText("resend"))
            }
            select(
                canonical,
                state.value.host,
                state.value.projects,
                project,
                state.value.sessions,
                session,
            )
            snapshot(epoch)
            refreshConfiguration()
            refreshAdvisor()
            refreshCommands()
            notice?.let(::reportError)
            return canonical
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = forkError((e as? RemoteRequestException)?.code)
        } finally {
            if (epoch == selectionEpoch) update { it.copy(loading = false) }
        }
        // A refused fork leaves the selection and the source chat as they were. cancelSelection()
        // (here and in the navigator) stopped the chat's background work, so it starts again.
        if (epoch == selectionEpoch) {
            if (state.value.connected) {
                snapshotAsync()
                refreshConfiguration()
                refreshAdvisor()
                refreshCommands()
                refreshSessions()
            } else refresh()
            reportError(failure)
        }
        return source
    }

    override suspend fun openNotification(routeId: String, sessionId: String): RemoteSelection? {
        cancelRecovery()
        initialized.await()
        if (
            routeId.isBlank() ||
                sessionId.isBlank() ||
                routeId.length > 256 ||
                sessionId.length > 256
        )
            return null
        val host = state.value.hosts.find { it.routeId == routeId } ?: return null
        val previous = state.value
        val epoch = selectionEpoch
        var resolved: RemoteSelection? = null
        update { it.copy(loading = true) }
        try {
            connect(host, epoch)
            val projects = request("projects.list", epoch).array("items")
            for (project in projects) {
                val sessions = listSessions(project.text("id"), epoch)
                if (sessions.any { it.text("id") == sessionId }) {
                    resolved = RemoteSelection(routeId, project.text("id"), sessionId)
                    break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (epoch == selectionEpoch) reportError(R.string.remote_request_error)
        }
        if (resolved != null) return activate(resolved, ActivationMode.USER_OPEN)
        if (epoch == selectionEpoch && previous.host != null && previous.host.routeId != routeId) {
            try {
                connect(previous.host, epoch)
            } catch (_: Exception) {
                reportError(R.string.remote_connection_error)
            }
            if (epoch == selectionEpoch) {
                mutable.value =
                    previous.copy(
                        connected = state.value.connected,
                        connection = state.value.connection,
                        followUps = currentDraftKey()?.let { drafts[it]?.followUps }.orEmpty(),
                        // connect() above cancelled any download; the snapshot re-derives compaction.
                        toolOutput = null,
                        compaction = null,
                    )
                if (previous.session != null) {
                    timeline?.beginSnapshotEpoch(previous.status)
                    snapshotAsync()
                }
            }
        }
        if (epoch == selectionEpoch) update { it.copy(loading = false) }
        return null
    }

    override suspend fun pair(text: String): RemoteSelection? {
        val value =
            try {
                Wire.pairing(text.trim())
            } catch (_: Exception) {
                reportError(R.string.remote_invalid_qr)
                return null
            }
        cancelRecovery()
        initialized.await()
        if (!pairingsLoaded) {
            reportError(R.string.remote_storage_error)
            return null
        }
        val routeId = value.text("routeId")
        removalJobs[routeId]?.join()
        removedRoutes.remove(routeId)
        cancelSelection()
        clearNavigationCache()
        retry?.cancel()
        val epoch = selectionEpoch
        pairing?.cancel()
        pairing = CompletableDeferred()
        pairingEpoch = epoch
        if (markPendingFollowUpsUncertain(activeHost?.routeId)) persist().await()
        activeHost = null
        update {
            it.copy(loading = true, connected = false, connection = R.string.remote_connecting)
        }
        try {
            transport.pair(value)
            val host =
                withTimeoutOrNull(120000) { checkNotNull(pairing).await() }
                    ?: throw IllegalStateException("Pairing timed out")
            checkEpoch(epoch)
            update { it.copy(loading = false) }
            return activate(RemoteSelection(host.routeId))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (state.value.error == null) reportError(R.string.remote_connection_error)
            return null
        } finally {
            if (epoch == selectionEpoch) update { it.copy(loading = false) }
        }
    }

    override fun disconnect() {
        reconnectEnabled = false
        cancelSelection()
        clearNavigationCache()
        retry?.cancel()
        generation++
        transport.close()
        ready?.cancel()
        pairing?.cancel()
        if (markPendingFollowUpsUncertain(activeHost?.routeId)) persist()
        activeHost = null
        timeline = null
        stopToolOutput()
        update { RemoteState(hosts = it.hosts) }
    }

    override fun remove(routeId: String) {
        if (
            cachedProjects?.first == routeId ||
                cachedSessions?.routeId == routeId ||
                cachedChat?.selection?.routeId == routeId
        ) clearNavigationCache()
        if (activeHost?.routeId == routeId) reconnectEnabled = false
        if (activeHost?.routeId == routeId) disconnect()
        removedRoutes += routeId
        savedNavigation.remove(routeId)
        queueNavigationWrite(immediate = true)
        update { it.copy(hosts = it.hosts.filterNot { host -> host.routeId == routeId }) }
        onUnpaired(routeId)
        val removal = scope.launch(start = CoroutineStart.LAZY) {
            try {
                initialized.await()
                check(pairingsLoaded)
                pairingWrites.withLock {
                    val remaining = state.value.hosts
                    drafts.keys.filter { it.routeId == routeId }.forEach { drafts.remove(it) }
                    persist().await()
                    cleanupAttachments()
                    withContext(io) { pairings.save(remaining) }
                    try {
                        navigationWriteMutex.withLock {
                            if (ensureNavigationLoaded()) {
                                val snapshots = savedNavigation.toMap()
                                withContext(io) { navigationStorage?.save(snapshots) }
                            }
                        }
                    } catch (_: Exception) {
                        reportError(R.string.remote_storage_error)
                    }
                    if (activeHost?.routeId == routeId) disconnect()
                    update { it.copy(hosts = remaining) }
                }
            } catch (_: Exception) {
                reportError(R.string.remote_storage_error)
            } finally {
                if (removalJobs[routeId] === coroutineContext[Job]) removalJobs.remove(routeId)
            }
        }
        removalJobs[routeId] = removal
        removal.start()
    }

    private fun failed(reconnect: Boolean, error: Int) {
        deferredRecovery = null
        generation++
        folderVersion++
        folderSession++
        activeOpen = null
        cloneStatusJob?.cancel()
        providerStatusJob?.cancel()
        providerVersion++
        earlyProviderUpdates.clear()
        failFolderRequests()
        transport.close()
        ready?.completeExceptionally(IllegalStateException("Disconnected"))
        pairing?.completeExceptionally(IllegalStateException("Pairing failed"))
        invalidateRequests(IllegalStateException("Connection lost"))
        drafts.replaceAll { _, draft -> draft.copy(followUps = draft.followUps.map { entry ->
            if (entry.status in setOf("pending", "accepted")) entry.copy(status = "uncertain") else entry
        }) }
        persist()
        projectChatsJob?.cancel()
        attentionRefreshJob?.cancel()
        projectChatCandidates.replaceAll { _, chats ->
            chats.map { it.copy(verified = false) }
        }
        cachedProjectChats = cachedProjectChats?.let { it.first to it.second.map { chat ->
            chat.copy(verified = false)
        } }
        stopToolOutput()
        sessionRefreshJob?.cancel()
        update {
            it.copy(
                connected = false,
                compaction = null,
                compactionRequesting = false,
                toolOutput = null,
                projectChats = it.projectChats.map { chat -> chat.copy(verified = false) },
                followUps = currentDraftKey()?.let { key -> drafts[key]?.followUps }.orEmpty(),
                connection = R.string.remote_offline,
                error = error,
                loading = false,
                sending = false,
                answering = emptySet(),
                questions = emptyList(),
                capabilities = emptySet(),
                unavailableCapabilities = emptySet(),
                childControlUnsupported = emptySet(),
                configuration = null,
                advisor = null,
                advisorLoading = false,
                advisorChanging = false,
                commands = emptyList(),
                status = "offline",
                providerAuth = it.providerAuth.offline(),
                // A listing lost with the connection loads again after the reconnect.
                folders = it.folders.copy(
                    loaded = it.folders.loaded && !it.folders.loading,
                    loading = false,
                    error = null,
                    working = false,
                    trust = null,
                    notice = null,
                ),
            )
        }
        val host = activeHost
        if (error == R.string.remote_denied) reconnectEnabled = false
        if (
            reconnect &&
                reconnectEnabled &&
                recovery.canRecover &&
                host != null &&
                reconnectAttempts < 5
        ) {
            val generationAtFailure = generation
            retry?.cancel()
            retry = scope.launch {
                delay((1000L shl reconnectAttempts++).coerceAtMost(30000))
                if (generationAtFailure == generation && recovery.canRecover && reconnectEnabled) {
                    val selection = state.value.selection
                    retry = null
                    activate(selection, ActivationMode.RESTORE)
                }
            }
        }
    }

    override fun refresh() {
        reconnectEnabled = true
        val selection = state.value.selection
        clearNavigationCache()
        if (selection.sessionId != null && state.value.connected) {
            snapshotAsync()
            refreshConfiguration()
            refreshAdvisor()
            refreshCommands()
            return
        }
        reconnectAttempts = 0
        scope.launch {
            update { it.copy(loading = true) }
            activate(selection, ActivationMode.RESTORE, force = false, useCache = false)
        }
    }

    override fun renameSession(sessionId: String, title: String) {
        val current = state.value
        val normalized = title.trim()
        val session = current.sessions.find { it.text("id") == sessionId }
            ?: current.session?.takeIf { it.text("id") == sessionId }
            ?: return
        if (!current.connected || current.loading ||
            RENAME_CAPABILITY !in current.capabilities ||
            session.text("origin") !in setOf("tui", "rpc") ||
            session.text("status") == "offline" ||
            normalized.isEmpty() || normalized.encodeToByteArray().size > 4096 ||
            !renamingIds.add(sessionId)) return
        val originalTitle = session.text("title")
        val epoch = selectionEpoch
        scope.launch {
            try {
                val confirmed = request(
                    "session.rename", epoch,
                    "sessionId" to sessionId,
                    "title" to normalized,
                ).obj("session")
                check(confirmed.text("id") == sessionId)
                if (epoch == selectionEpoch)
                    updateSessionTitle(sessionId, confirmed.text("title"), originalTitle)
            } catch (_: CancellationException) {
            } catch (_: Exception) {
                if (epoch == selectionEpoch) reportError(R.string.remote_request_error)
            } finally {
                renamingIds.remove(sessionId)
            }
        }
    }

    override fun setForeground(foreground: Boolean) {
        jobsController.setForeground(foreground)
        val signal = recovery.foreground(foreground)
        if (!foreground) {
            queueNavigationWrite(immediate = true)
            retry?.cancel()
            retry = null
            cancelRecovery()
            return
        }
        signal ?: return
        onRecoverySignal(signal)
        reconnectAttempts = 0
        recover(signal)
    }

    override fun setValidatedNetwork(identity: String?) {
        val signal = recovery.network(identity)
        if (identity == null) {
            retry?.cancel()
            retry = null
            cancelRecovery()
            return
        }
        signal ?: return
        onRecoverySignal(signal)
        reconnectAttempts = 0
        recover(signal)
    }

    private fun recover(signal: RemoteRecovery.Signal) {
        if (!reconnectEnabled || !recovery.canRecover) return
        if (pairing?.isActive == true || state.value.loading) return
        if (signal == RemoteRecovery.Signal.NETWORK_REPLACED && activeHost != null) {
            failed(true, R.string.remote_connection_error)
            return
        }
        if (hasPendingRecoveryWork()) {
            deferredRecovery = signal
            return
        }
        retry?.cancel()
        retry = null
        if (recoveryJob?.isActive == true) return
        val selection = state.value.selection
        val epoch = selectionEpoch
        if (selection.routeId == null || state.value.hosts.none { it.routeId == selection.routeId }) return
        recoveryJob =
            scope.launch {
                try {
                    if (epoch == selectionEpoch && recovery.canRecover && reconnectEnabled)
                        activate(selection, ActivationMode.RESTORE, force = true)
                } catch (_: CancellationException) {
                } catch (_: Exception) {
                    if (epoch == selectionEpoch) reportError(R.string.remote_request_error)
                }
            }
    }

    override fun refreshConfiguration() {
        val current = state.value
        val sessionId = current.selection.sessionId ?: return
        if (
            !current.connected ||
                CONFIGURATION_CAPABILITY !in current.capabilities ||
                CONFIGURATION_CAPABILITY in current.unavailableCapabilities ||
                current.configurationChanging
        )
            return
        val epoch = selectionEpoch
        val version = ++configurationVersion
        update { it.copy(configurationLoading = true) }
        scope.launch {
            try {
                val confirmed =
                    configuration(
                        request("session.configuration.get", epoch, "sessionId" to sessionId),
                        sessionId,
                    )
                if (epoch == selectionEpoch && version == configurationVersion) {
                    configurationNeedsRefresh = false
                    update { it.copy(configuration = confirmed) }
                }
            } catch (_: CancellationException) {} catch (e: Exception) {
                if (epoch == selectionEpoch && version == configurationVersion) {
                    if (e.message == "unsupported")
                        update {
                            it.copy(
                                configuration = null,
                                unavailableCapabilities =
                                    it.unavailableCapabilities + CONFIGURATION_CAPABILITY,
                            )
                        }
                    else reportError(R.string.remote_configuration_error)
                }
            } finally {
                if (epoch == selectionEpoch && version == configurationVersion)
                    update { it.copy(configurationLoading = false) }
                resumeDeferredRecovery()
            }
        }
    }

    override fun refreshContextUsage() {
        val current = state.value
        val sessionId = current.selection.sessionId ?: return
        if (!current.connected ||
            CONTEXT_CAPABILITY !in current.capabilities ||
            CONTEXT_CAPABILITY in current.unavailableCapabilities) return
        val epoch = selectionEpoch
        val version = ++contextVersion
        update { it.copy(contextLoading = true) }
        scope.launch {
            try {
                val usage = contextUsage(
                    request("session.context.get", epoch, "sessionId" to sessionId),
                    sessionId,
                )
                if (epoch == selectionEpoch && version == contextVersion &&
                    state.value.selection.sessionId == sessionId) {
                    update { it.copy(contextUsage = usage) }
                }
            } catch (_: CancellationException) {} catch (e: Exception) {
                if (epoch == selectionEpoch && version == contextVersion) {
                    if (e.message == "unsupported")
                        update { it.copy(
                            contextUsage = null,
                            unavailableCapabilities = it.unavailableCapabilities + CONTEXT_CAPABILITY,
                        ) }
                    else update { it.copy(contextUsage = null) }
                }
            } finally {
                if (epoch == selectionEpoch && version == contextVersion)
                    update { it.copy(contextLoading = false) }
            }
        }
    }

    override fun compactContext() {
        val current = state.value
        val sessionId = current.selection.sessionId ?: return
        if (!current.connected || current.loading || current.status != "idle" ||
            current.sending || current.answering.isNotEmpty() || current.configurationChanging ||
            current.compactionRequesting || compactionVisible(current.compaction, now()) ||
            COMPACT_CAPABILITY !in current.capabilities ||
            COMPACT_CAPABILITY in current.unavailableCapabilities ||
            activeHost?.routeId != current.selection.routeId
        ) return
        val epoch = selectionEpoch
        update { it.copy(compactionRequesting = true) }
        scope.launch {
            try {
                val result = request("session.compact", epoch, "sessionId" to sessionId)
                require(result.text("kind") == "accepted" && result.text("sessionId") == sessionId)
            } catch (_: CancellationException) {} catch (e: Exception) {
                if (epoch == selectionEpoch) {
                    if (e.message == "unsupported")
                        update { it.copy(unavailableCapabilities = it.unavailableCapabilities + COMPACT_CAPABILITY) }
                    reportError(R.string.remote_compact_error)
                }
            } finally {
                if (epoch == selectionEpoch) update { it.copy(compactionRequesting = false) }
            }
        }
    }

    private fun canChangeConfiguration(): Boolean =
        state.value.let {
            it.connected &&
                !it.loading &&
                it.status == "idle" &&
                !it.sending &&
                it.answering.isEmpty() &&
                !it.configurationLoading &&
                !it.configurationChanging &&
                CONFIGURATION_CAPABILITY in it.capabilities &&
                CONFIGURATION_CAPABILITY !in it.unavailableCapabilities
        }

    override fun setModel(provider: String, id: String) {
        val model =
            state.value.configuration?.models?.find { it.provider == provider && it.id == id }
                ?: return
        changeConfiguration(
            Wire.objectOf("kind" to "model", "provider" to model.provider, "id" to model.id)
        )
    }

    override fun setThinkingLevel(level: String) {
        val confirmed = state.value.configuration ?: return
        val model = confirmed.model ?: return
        if (level !in confirmed.thinkingLevels) return
        changeConfiguration(
            Wire.objectOf(
                "kind" to "thinking",
                "level" to level,
                "expectedModel" to Wire.objectOf("provider" to model.provider, "id" to model.id),
            )
        )
    }

    override fun changeSettings(autoCompaction: Boolean?, steeringMode: String?, followUpMode: String?) {
        val current = state.value
        if (SETTINGS_CAPABILITY !in current.capabilities ||
            SETTINGS_CAPABILITY in current.unavailableCapabilities ||
            current.configuration?.settings == null ||
            (autoCompaction == null && steeringMode == null && followUpMode == null) ||
            listOfNotNull(steeringMode, followUpMode).any { it != QUEUE_MODE_ONE_AT_A_TIME && it != QUEUE_MODE_ALL }
        ) return
        changeConfiguration(
            JsonObject(
                buildMap {
                    put("kind", JsonPrimitive("settings"))
                    autoCompaction?.let { put("autoCompaction", JsonPrimitive(it)) }
                    steeringMode?.let { put("steeringMode", JsonPrimitive(it)) }
                    followUpMode?.let { put("followUpMode", JsonPrimitive(it)) }
                }
            ),
            rereadOnError = true,
        )
    }

    private fun changeConfiguration(change: JsonObject, rereadOnError: Boolean = false) {
        if (!canChangeConfiguration()) return
        if (configurationNeedsRefresh) {
            refreshConfiguration()
            return
        }
        val sessionId = state.value.selection.sessionId ?: return
        val epoch = selectionEpoch
        val version = ++configurationVersion
        contextVersion++
        update { it.copy(configurationChanging = true, contextUsage = null, contextLoading = false) }
        scope.launch {
            var failed = false
            try {
                val confirmed =
                    configuration(
                        request(
                            "session.configuration.set",
                            epoch,
                            "sessionId" to sessionId,
                            "change" to change,
                        ),
                        sessionId,
                    )
                if (epoch == selectionEpoch && version == configurationVersion)
                    update { it.copy(configuration = confirmed, contextUsage = null) }
            } catch (_: CancellationException) {} catch (e: Exception) {
                failed = true
                if (epoch == selectionEpoch && version == configurationVersion) {
                    configurationNeedsRefresh = true
                    if (e.message == "unsupported" && rereadOnError) {
                        // Only the settings change is unsupported; model and thinking still work.
                        update { it.copy(unavailableCapabilities = it.unavailableCapabilities + SETTINGS_CAPABILITY) }
                        reportError(R.string.remote_configuration_error)
                    } else if (e.message == "unsupported")
                        update {
                            it.copy(
                                configuration = null,
                                unavailableCapabilities =
                                    it.unavailableCapabilities + CONFIGURATION_CAPABILITY,
                            )
                        }
                    else reportError(R.string.remote_configuration_error)
                }
            } finally {
                if (epoch == selectionEpoch && version == configurationVersion)
                    update { it.copy(configurationChanging = false) }
                // A failed settings change may be partly applied: show what the host holds now.
                if (failed && rereadOnError && epoch == selectionEpoch && version == configurationVersion)
                    refreshConfiguration()
                resumeDeferredRecovery()
            }
        }
    }

    override fun refreshAdvisor() {
        val current = state.value
        val sessionId = current.selection.sessionId ?: return
        // A session can gain support after its advisor extension is loaded. A previous
        // unsupported response must not prevent a fresh read from the visible advisor entry.
        if (!current.connected || ADVISOR_CAPABILITY !in current.capabilities || current.advisorLoading) return
        val epoch = selectionEpoch
        val version = ++advisorVersion
        update { it.copy(advisorLoading = true) }
        scope.launch {
            try {
                val confirmed = advisor(request("session.advisor.get", epoch, "sessionId" to sessionId), sessionId)
                if (epoch == selectionEpoch && version == advisorVersion &&
                    state.value.selection.sessionId == sessionId)
                    update { it.copy(advisor = confirmed, advisorChanging = false,
                        unavailableCapabilities = it.unavailableCapabilities - ADVISOR_CAPABILITY) }
            } catch (_: CancellationException) {} catch (e: Exception) {
                if (epoch == selectionEpoch && version == advisorVersion) {
                    if (e.message == "unsupported")
                        update { it.copy(advisor = null,
                            unavailableCapabilities = it.unavailableCapabilities + ADVISOR_CAPABILITY) }
                    else reportError(R.string.remote_request_error)
                }
            } finally {
                if (epoch == selectionEpoch && version == advisorVersion)
                    update { it.copy(advisorLoading = false, advisorChanging = false) }
            }
        }
    }

    override fun setAdvisor(provider: String?, id: String?, level: String?) {
        val current = state.value
        val sessionId = current.selection.sessionId ?: return
        if (!current.connected || current.status != "idle" || current.advisorChanging ||
            current.sending || current.answering.isNotEmpty() ||
            ADVISOR_CAPABILITY !in current.capabilities ||
            ADVISOR_CAPABILITY in current.unavailableCapabilities) return
        val command = if (provider == null) "/advisor off" else {
            val choice = current.advisor?.choices?.find { it.provider == provider && it.id == id } ?: return
            if (level !in choice.levels) return
            "/advisor $provider/$id --thinking $level"
        }
        val epoch = selectionEpoch
        update { it.copy(advisorChanging = true) }
        scope.launch {
            try {
                request("session.command", epoch, "sessionId" to sessionId, "text" to command)
                delay(1200)
                if (epoch == selectionEpoch && state.value.selection.sessionId == sessionId)
                    refreshAdvisor()
            } catch (_: CancellationException) {} catch (_: Exception) {
                if (epoch == selectionEpoch && state.value.selection.sessionId == sessionId) {
                    update { it.copy(advisorChanging = false) }
                    reportError(R.string.remote_request_error)
                }
            }
        }
    }

    override fun refreshCommands() {
        val current = state.value
        val sessionId = current.selection.sessionId ?: return
        if (
            !current.connected ||
                COMMANDS_CAPABILITY !in current.capabilities ||
                COMMANDS_CAPABILITY in current.unavailableCapabilities ||
                current.commandsLoading
        )
            return
        val epoch = selectionEpoch
        val version = ++commandsVersion
        update { it.copy(commandsLoading = true) }
        scope.launch {
            try {
                val result = request("session.commands.get", epoch, "sessionId" to sessionId)
                val catalog = commands(result, sessionId)
                if (epoch == selectionEpoch && version == commandsVersion)
                    update {
                        it.copy(commands = catalog, commandsTruncated = result.flag("truncated"))
                    }
            } catch (_: CancellationException) {} catch (e: Exception) {
                if (epoch == selectionEpoch && version == commandsVersion) {
                    if (e.message == "unsupported")
                        update {
                            it.copy(
                                commands = emptyList(),
                                unavailableCapabilities =
                                    it.unavailableCapabilities + COMMANDS_CAPABILITY,
                            )
                        }
                    else reportError(R.string.remote_commands_error)
                }
            } finally {
                if (epoch == selectionEpoch && version == commandsVersion)
                    update { it.copy(commandsLoading = false) }
                resumeDeferredRecovery()
            }
        }
    }

    override fun selectCommand(command: RemoteCommand) {
        if (command !in state.value.commands || commandName(state.value.draft) == null) return
        draft(selectCommand(state.value.draft, command))
    }

    private fun commandReceipt(event: JsonObject) {
        val dispatch = commandDispatches[event.text("requestId")] ?: return
        if (
            dispatch.epoch != selectionEpoch ||
                dispatch.key != currentDraftKey() ||
                event.text("sessionId") != dispatch.key.sessionId
        )
            return
        if (event.long("revision") <= dispatch.revision) return
        dispatch.revision = event.long("revision")
        val notice =
            when (event.text("status")) {
                "accepted" -> R.string.remote_command_accepted
                "failed" -> R.string.remote_command_failed
                "uncertain" -> R.string.remote_command_uncertain
                else -> return
            }
        update { it.copy(commandNotice = notice) }
    }

    override fun older() = snapshotAsync(true)

    private fun currentDraftKey(): DraftKey? =
        state.value.selection.let { selection ->
            if (selection.routeId != null && selection.sessionId != null)
                DraftKey(selection.routeId, selection.sessionId)
            else null
        }

    private fun persist(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also {
            if (!draftsAvailable) {
                reportError(R.string.remote_storage_error)
                it.completeExceptionally(IllegalStateException("Draft storage unavailable"))
            } else check(writes.trySend(Write(drafts.toMap(), it)).isSuccess)
        }

    override suspend fun flush() {
        initialized.await()
        val done = CompletableDeferred<Unit>()
        writes.send(Write(null, done))
        done.await()
    }

    override fun draft(text: String) {
        if (text.toByteArray().size > 128 * 1024) return
        val key = currentDraftKey() ?: return
        drafts[key] = (drafts[key] ?: StoredDraft()).copy(text = text)
        update { it.copy(draft = text) }
        persist()
    }

    override fun quote(messageId: String?) {
        if (state.value.loading) return
        val key = currentDraftKey() ?: return
        val quote =
            if (messageId == null) null
            else {
                val message = state.value.messages.find { it.text("id") == messageId } ?: return
                quoteFromMessage(message) ?: return
            }
        drafts[key] = (drafts[key] ?: StoredDraft()).copy(quote = quote)
        update { it.copy(quote = quote) }
        persist()
    }

    private suspend fun cleanupAttachments() {
        val storage = attachmentStorage ?: return
        attachmentMutex.withLock {
            val keep =
                drafts.values
                    .flatMap { it.attachments + it.submittedAttachments }
                    .map { it.id }
                    .toSet() + attachmentInFlight + persistedAttachmentIds
            withContext(io) { storage.cleanup(keep) }
        }
    }

    override fun importAttachments(selection: RemoteSelection, uris: List<String>, photo: Boolean) {
        val importer = attachmentImporter ?: return
        if (
            selection != state.value.selection ||
                state.value.loading ||
                state.value.importingAttachments ||
                state.value.sending
        )
            return
        if (ATTACHMENTS_CAPABILITY !in state.value.capabilities) {
            reportError(R.string.remote_attachments_unsupported)
            return
        }
        val key = currentDraftKey() ?: return
        if (uris.isEmpty()) return
        if (uris.size + state.value.attachments.size > 5) {
            reportError(R.string.remote_attachment_limits)
            return
        }
        val epoch = selectionEpoch
        update { it.copy(importingAttachments = true) }
        importJob = scope.launch {
            try {
                for (uri in uris) {
                    attachmentMutex.withLock {
                        val imported = importer(uri, photo)
                        val latest = drafts[key] ?: StoredDraft()
                        try {
                            checkEpoch(epoch)
                            check(currentDraftKey() == key)
                            val attachments = latest.attachments + imported
                            require(validAttachmentSet(attachments))
                            drafts[key] = latest.copy(attachments = attachments)
                            persist().await()
                            update { it.copy(attachments = attachments) }
                        } catch (e: Exception) {
                            if (drafts[key]?.attachments?.any { it.id == imported.id } == true) {
                                drafts[key] =
                                    (drafts[key] ?: latest).let {
                                        it.copy(
                                            attachments =
                                                it.attachments.filterNot { attachment ->
                                                    attachment.id == imported.id
                                                }
                                        )
                                    }
                                withContext(NonCancellable) { runCatching { persist().await() } }
                                if (currentDraftKey() == key)
                                    update {
                                        it.copy(attachments = drafts[key]?.attachments.orEmpty())
                                    }
                            }
                            if (imported.id !in persistedAttachmentIds)
                                withContext(NonCancellable + io) {
                                    attachmentStorage?.remove(imported.id)
                                }
                            throw e
                        }
                    }
                }
            } catch (_: CancellationException) {} catch (_: Exception) {
                if (epoch == selectionEpoch) reportError(R.string.remote_attachment_import_error)
            } finally {
                if (epoch == selectionEpoch) update { it.copy(importingAttachments = false) }
            }
        }
    }

    override fun removeAttachment(id: String) {
        val key = currentDraftKey() ?: return
        val latest = drafts[key] ?: return
        if (latest.attachments.none { it.id == id }) return
        drafts[key] = latest.copy(attachments = latest.attachments.filterNot { it.id == id })
        update { it.copy(attachments = drafts.getValue(key).attachments) }
        val persisted = persist()
        val epoch = selectionEpoch
        scope.launch {
            try {
                persisted.await()
                if (id !in attachmentInFlight && latest.submittedAttachments.none { it.id == id }) {
                    latest.uploads
                        .find { it.localId == id }
                        ?.let { upload ->
                            cancelUpload(key, upload.remoteId, epoch)
                            drafts[key] =
                                (drafts[key] ?: StoredDraft()).let {
                                    it.copy(
                                        uploads = it.uploads.filterNot { entry -> entry == upload }
                                    )
                                }
                            persist().await()
                        }
                }
            } catch (_: CancellationException) {} catch (_: Exception) {} finally {
                runCatching { cleanupAttachments() }
            }
        }
    }

    override fun cancelAttachmentWork() {
        importJob?.cancel()
        val uploadJob = sendJob.takeIf { state.value.attachmentProgress != null }
        uploadJob?.cancel()
        val key = currentDraftKey()
        val epoch = selectionEpoch
        if (uploadJob != null && key != null)
            scope.launch {
                uploadJob.join()
                try {
                    for (upload in
                        drafts[key]?.uploads.orEmpty().filter { it.attachment == null }) {
                        cancelUpload(key, upload.remoteId, epoch)
                        drafts[key] =
                            (drafts[key] ?: StoredDraft()).let {
                                it.copy(uploads = it.uploads.filterNot { entry -> entry == upload })
                            }
                        persist().await()
                    }
                } catch (_: Exception) {}
            }
        update { it.copy(importingAttachments = false, attachmentProgress = null) }
    }

    private suspend fun cancelUpload(key: DraftKey, remoteId: String, epoch: Long) {
        try {
            request(
                "session.attachments.cancel",
                epoch,
                "sessionId" to key.sessionId,
                "attachmentId" to remoteId,
            )
        } catch (e: Exception) {
            if (e.message !in setOf("forbidden", "invalid_request")) throw e
        }
        checkEpoch(epoch)
        check(state.value.connected)
    }

    private suspend fun uploadAttachments(
        key: DraftKey,
        projectId: String,
        values: List<LocalAttachment>,
        epoch: Long,
    ): List<RemoteAttachment> {
        val storage = checkNotNull(attachmentStorage)
        val submitted = drafts[key]?.submittedAttachments.orEmpty().map { it.id }.toSet()
        for (upload in
            drafts[key]?.uploads.orEmpty().filter { old ->
                values.none { it.id == old.localId } && old.localId !in submitted
            }) {
            cancelUpload(key, upload.remoteId, epoch)
            drafts[key] =
                (drafts[key] ?: StoredDraft()).let {
                    it.copy(uploads = it.uploads.filterNot { entry -> entry == upload })
                }
            persist().await()
        }
        val total = values.sumOf { it.size }.coerceAtLeast(1)
        var uploaded = 0L
        return values.map { local ->
            checkEpoch(epoch)
            val bytes = attachmentMutex.withLock {
                withContext(io) {
                    storage.read(local).also {
                        require(
                            it.size.toLong() == local.size &&
                                AttachmentImportRules.sha256(it) == local.sha256
                        )
                    }
                }
            }
            val previous = drafts[key]?.uploads?.find { it.localId == local.id }
            val confirmed = previous?.attachment
            if (
                previous?.projectId == projectId &&
                    confirmed != null &&
                    confirmed.expiresAt > now() &&
                    confirmed.sha256 == local.sha256
            ) {
                uploaded += local.size
                update { it.copy(attachmentProgress = uploaded.toFloat() / total) }
                confirmed
            } else {
                if (previous != null) {
                    cancelUpload(key, previous.remoteId, epoch)
                    drafts[key] =
                        (drafts[key] ?: StoredDraft()).let {
                            it.copy(
                                uploads =
                                    it.uploads.filterNot { upload -> upload.localId == local.id }
                            )
                        }
                    persist().await()
                }
                val begin =
                    request(
                        "session.attachments.begin",
                        epoch,
                        "sessionId" to key.sessionId,
                        "attachment" to local.descriptor(),
                    )
                require(
                    begin.text("kind") == "attachment.upload" &&
                        begin.text("sessionId") == key.sessionId &&
                        begin.long("offset") == 0L
                )
                val remoteId = begin.text("attachmentId")
                require(AttachmentImportRules.validId(remoteId))
                val upload = AttachmentUpload(local.id, remoteId, projectId)
                drafts[key] =
                    (drafts[key] ?: StoredDraft()).let { it.copy(uploads = it.uploads + upload) }
                persist().await()
                var offset = 0
                while (offset < bytes.size) {
                    delay(200)
                    checkEpoch(epoch)
                    val end = minOf(offset + 64 * 1024, bytes.size)
                    val chunk =
                        java.util.Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(bytes.copyOfRange(offset, end))
                    val result =
                        request(
                            "session.attachments.chunk",
                            epoch,
                            "sessionId" to key.sessionId,
                            "attachmentId" to remoteId,
                            "offset" to offset,
                            "data" to chunk,
                        )
                    require(
                        result.text("kind") == "attachment.upload" &&
                            result.text("sessionId") == key.sessionId &&
                            result.text("attachmentId") == remoteId &&
                            result.long("offset") == end.toLong()
                    )
                    offset = end
                    update { it.copy(attachmentProgress = (uploaded + offset).toFloat() / total) }
                }
                val result =
                    request(
                        "session.attachments.commit",
                        epoch,
                        "sessionId" to key.sessionId,
                        "attachmentId" to remoteId,
                    )
                require(
                    result.text("kind") == "attachment" && result.text("sessionId") == key.sessionId
                )
                val attachment = remoteAttachment(result.obj("attachment"))
                require(
                    attachment.id == remoteId &&
                        attachment.sha256 == local.sha256 &&
                        attachment.size == local.size &&
                        attachment.name == local.name &&
                        attachment.kind == local.kind &&
                        attachment.mimeType == local.mimeType
                )
                drafts[key] =
                    (drafts[key] ?: StoredDraft()).let {
                        it.copy(
                            uploads =
                                it.uploads.map { entry ->
                                    if (entry.localId == local.id)
                                        upload.copy(attachment = attachment)
                                    else entry
                                }
                        )
                    }
                persist().await()
                uploaded += local.size
                attachment
            }
        }
    }

    override fun prompt() = sendPrompt(null)

    override fun followUp() = sendPrompt("follow_up")

    override fun steer() = sendPrompt("steer")

    private fun sendPrompt(delivery: String?) {
        val queued = delivery != null
        val current = state.value
        if (
            !current.connected ||
                current.loading ||
                activeHost?.routeId != current.selection.routeId ||
                current.status != (if (queued) "running" else "idle") ||
                (queued && (!(if (delivery == "steer") canSteer(current) else canFollowUp(current)) ||
                    current.questions.isNotEmpty() || current.followUps.size >= 64)) ||
                current.sending ||
                current.importingAttachments ||
                current.configurationChanging ||
                (current.draft.isBlank() && current.attachments.isEmpty())
        )
            return
        if (queued && isChildSession(current) && current.attachments.isNotEmpty()) {
            reportError(R.string.remote_child_attachments_unsupported)
            return
        }
        val key = currentDraftKey() ?: return
        val projectId = current.selection.projectId ?: return
        val command = commandName(current.draft)
        if (queued && (command != null || current.draft.trimStart().startsWith("/"))) return
        if (command != null) {
            if (current.quote != null || current.attachments.isNotEmpty()) {
                reportError(R.string.remote_command_remove_context)
                return
            }
            if (
                COMMANDS_CAPABILITY !in current.capabilities ||
                    COMMANDS_CAPABILITY in current.unavailableCapabilities ||
                    current.commandsLoading ||
                    current.commands.none { it.name == command }
            ) {
                reportError(R.string.remote_command_unknown)
                return
            }
        }
        if (current.attachments.isNotEmpty()) {
            if (ATTACHMENTS_CAPABILITY !in current.capabilities) {
                reportError(R.string.remote_attachments_unsupported)
                return
            }
            if (!validAttachmentSet(current.attachments)) {
                reportError(R.string.remote_attachment_limits)
                return
            }
            if (
                current.attachments.any { it.kind == "image" } &&
                    current.configuration?.model?.input?.contains("image") != true
            ) {
                reportError(R.string.remote_attachment_model_error)
                return
            }
        }
        val encoded =
            try {
                QuoteCodec.encode(current.draft, current.quote)
            } catch (_: IllegalArgumentException) {
                reportError(R.string.remote_prompt_too_long)
                return
            }
        val epoch = selectionEpoch
        val id = Wire.random()
        attachmentInFlight.addAll(current.attachments.map { it.id })
        update {
            it.copy(
                sending = true,
                attachmentProgress = if (current.attachments.isEmpty()) null else 0f,
                commandNotice = null,
            )
        }
        if (command != null) {
            commandDispatches[id] = CommandDispatch(key, epoch)
            while (commandDispatches.size > 128) commandDispatches.remove(
                commandDispatches.keys.first()
            )
        }
        sendJob = scope.launch {
            try {
                val uploaded =
                    if (current.attachments.isEmpty()) emptyList()
                    else uploadAttachments(key, projectId, current.attachments, epoch)
                checkEpoch(epoch)
                check(state.value.connected && state.value.status == (if (queued) "running" else "idle") &&
                    (!queued || ((if (delivery == "steer") canSteer(state.value) else canFollowUp(state.value)) &&
                        state.value.questions.isEmpty() &&
                        (drafts[key]?.followUps?.size ?: 0) < 64)))
                drafts[key] =
                    (drafts[key] ?: StoredDraft()).copy(
                        mutationId = id,
                        submittedText = current.draft,
                        submittedQuote = current.quote,
                        submittedAttachments = current.attachments,
                    )
                persist().await()
                checkEpoch(epoch)
                update { it.copy(uncertain = true, attachmentProgress = null) }
                val fields =
                    mutableListOf<Pair<String, Any?>>(
                        "sessionId" to key.sessionId,
                        "text" to encoded,
                    )
                if (queued) fields += "delivery" to delivery
                if (uploaded.isNotEmpty())
                    fields += "attachmentIds" to JsonArray(uploaded.map { JsonPrimitive(it.id) })
                val acknowledgement =
                    request(
                        if (command == null) "session.prompt" else "session.command",
                        epoch,
                        *fields.toTypedArray(),
                        draft = current.draft,
                        requestId = id,
                    )
                if (queued) require(acknowledgement.text("kind") == "accepted" &&
                    acknowledgement.text("sessionId") == key.sessionId)
                if (command != null)
                    require(
                        acknowledgement.text("kind") == "command" &&
                            acknowledgement.text("sessionId") == key.sessionId &&
                            acknowledgement.text("status") == "dispatched"
                    )
                if (command != null && state.value.commandNotice == null)
                    update { it.copy(commandNotice = R.string.remote_command_dispatched) }
                val latest = drafts[key]
                if (latest?.mutationId == id) {
                    val pending = if (queued) {
                        val receipt = earlyFollowUpReceipts.remove(id to delivery)
                        check(latest.followUps.size < 64)
                        latest.followUps + PendingFollowUp(id, current.draft, receipt ?: "accepted", delivery)
                    } else latest.followUps
                    val unchanged = latest.text == current.draft && latest.quote == current.quote
                    val attachments =
                        if (latest.attachments == current.attachments) emptyList()
                        else latest.attachments
                    drafts[key] =
                        StoredDraft(
                            text = if (unchanged) "" else latest.text,
                            quote = if (unchanged) null else latest.quote,
                            attachments = attachments,
                            uploads =
                                latest.uploads.filter { upload ->
                                    attachments.any { it.id == upload.localId }
                                },
                            followUps = pending,
                            // A sent message leaves the changes view's pending review alone.
                            reviewComments = latest.reviewComments,
                        )
                    persist().await()
                    if (epoch == selectionEpoch && currentDraftKey() == key) {
                        val stored = drafts.getValue(key)
                        update {
                            it.copy(
                                draft = stored.text,
                                quote = stored.quote,
                                attachments = stored.attachments,
                                uncertain = false,
                                followUps = stored.followUps,
                            )
                        }
                    }
                }
            } catch (_: CancellationException) {} catch (e: Exception) {
                if (
                    current.attachments.isNotEmpty() &&
                        e.message in setOf("invalid_request", "forbidden") &&
                        epoch == selectionEpoch
                ) {
                    drafts[key] =
                        (drafts[key] ?: StoredDraft()).let {
                            it.copy(
                                uploads =
                                    it.uploads.map { upload ->
                                        if (
                                            current.attachments.any { attachment ->
                                                attachment.id == upload.localId
                                            }
                                        )
                                            upload.copy(attachment = null)
                                        else upload
                                    }
                            )
                        }
                    persist()
                }
                if (
                    queued && (e as? RemoteRequestException)?.code == "not_running" &&
                        epoch == selectionEpoch
                ) {
                    // The child finished: the host did not take the input, so the draft stays
                    // for a resume and no submission is left to reconcile.
                    drafts[key]?.let {
                        drafts[key] =
                            it.copy(
                                mutationId = null,
                                submittedText = null,
                                submittedQuote = null,
                                submittedAttachments = emptyList(),
                            )
                        persist()
                    }
                    setChildControl(key.sessionId, ChildControl(ChildControlPhase.NOT_RUNNING))
                    update { it.copy(uncertain = false) }
                } else if (epoch == selectionEpoch && storageFailure == null) {
                    val childCode =
                        (e as? RemoteRequestException)?.code?.takeIf {
                            queued && childControlsAvailable(current) && it in setOf("unsupported", "offline")
                        }
                    if (childCode == "unsupported") markChildControlUnsupported(key.sessionId)
                    reportError(
                        if (childCode != null) childControlError(childCode)
                        else if (current.attachments.isEmpty()) R.string.remote_request_error
                        else if (
                            e.message == "unsupported" &&
                                current.attachments.any { it.kind == "image" }
                        )
                            R.string.remote_attachment_model_error
                        else R.string.remote_attachment_send_error
                    )
                    if (current.attachments.isNotEmpty() && e.message == "unsupported")
                        refreshConfiguration()
                    if (command != null)
                        update { it.copy(commandNotice = R.string.remote_command_uncertain) }
                }
            } finally {
                attachmentInFlight.removeAll(current.attachments.map { it.id }.toSet())
                if (epoch == selectionEpoch)
                    update { it.copy(sending = false, attachmentProgress = null) }
                resumeDeferredRecovery()
                withContext(NonCancellable) { runCatching { cleanupAttachments() } }
            }
        }
    }

    override fun abort() {
        val current = state.value
        if (!canAbortRemoteRun(current, activeHost?.routeId)) return
        val sessionId = checkNotNull(current.selection.sessionId)
        val epoch = selectionEpoch
        scope.launch {
            try {
                request("session.abort", epoch, "sessionId" to sessionId)
            } catch (_: CancellationException) {} catch (_: Exception) {
                if (epoch == selectionEpoch) reportError(R.string.remote_request_error)
            }
        }
    }

    override fun abortSession(sessionId: String) {
        val current = state.value
        if (!canAbortSubagent(current, sessionId, activeHost?.routeId)) return
        val epoch = selectionEpoch
        scope.launch {
            try {
                request("session.abort", epoch, "sessionId" to sessionId)
            } catch (_: CancellationException) {} catch (_: Exception) {
                if (epoch == selectionEpoch) reportError(R.string.remote_request_error)
            }
        }
    }

    /** A status change outdates a reply-bound phase; STOPPING and RESUMING wait for their reply. */
    private fun dropStaleChildControl(sessionId: String) {
        val control = state.value.childControls[sessionId] ?: return
        if (control.phase !in STALE_CHILD_CONTROL_PHASES) return
        // The agent a resume started still names the next resume; only the outcome goes.
        setChildControl(sessionId, control.agentId?.let { ChildControl(null, agentId = it) })
    }

    private fun setChildControl(sessionId: String, control: ChildControl?) = update {
        it.copy(
            childControls =
                if (control == null) it.childControls - sessionId
                else it.childControls + (sessionId to control)
        )
    }

    private fun canControlChild(current: RemoteState): Boolean =
        current.connected && !current.loading && activeHost?.routeId == current.selection.routeId &&
            current.selection.sessionId != null && childControlsAvailable(current) &&
            childControl(current)?.phase !in setOf(ChildControlPhase.STOPPING, ChildControlPhase.RESUMING)

    override fun stopChild() {
        val current = state.value
        // The confirmation can outlive what it asked about: the child went idle, or its parent
        // turned out too old, while the dialog was open. Say why nothing is stopped.
        if (current.connected && !current.loading && isChildSession(current)) {
            val notice =
                when {
                    !childControlsAvailable(current) -> R.string.remote_child_stop_unavailable
                    current.status == "offline" -> R.string.remote_child_stop_child_offline
                    current.status !in setOf("running", "waiting") -> R.string.remote_child_stop_not_running
                    else -> null
                }
            if (notice != null) {
                reportError(notice)
                return
            }
        }
        if (!canControlChild(current) || current.status !in setOf("running", "waiting")) return
        val sessionId = checkNotNull(current.selection.sessionId)
        val previous = childControl(current)
        val epoch = selectionEpoch
        setChildControl(sessionId, ChildControl(ChildControlPhase.STOPPING, agentId = previous?.agentId))
        scope.launch {
            var sent = false
            val control =
                try {
                    val result =
                        subagentControlResult(
                            request(
                                "session.subagent.stop",
                                epoch,
                                "sessionId" to sessionId,
                                onSent = { sent = true },
                            ),
                            sessionId,
                        )
                    when (result.status) {
                        "accepted" ->
                            ChildControl(
                                ChildControlPhase.STOPPED_BY_YOU,
                                agentId = result.agentId ?: previous?.agentId,
                            )
                        "refused" ->
                            ChildControl(ChildControlPhase.REFUSED, result.reason, previous?.agentId)
                        "not_found" -> ChildControl(ChildControlPhase.NOT_FOUND)
                        else -> uncertainChildControl(previous?.agentId)
                    }
                } catch (e: CancellationException) {
                    setChildControl(
                        sessionId,
                        if (sent) ChildControl(ChildControlPhase.UNCERTAIN, agentId = previous?.agentId)
                        else previous,
                    )
                    throw e
                } catch (e: RemoteRequestException) {
                    when (e.code) {
                        // A plain abort would stop the run while the parent's manager, which is
                        // offline, may still restart the child: say so and leave the state.
                        "offline" -> {
                            if (epoch == selectionEpoch) reportError(R.string.remote_child_stop_parent_offline)
                            previous
                        }
                        // An old parent cannot stop the child; the child's own abort still stops
                        // this run, but the parent may restart the subagent.
                        "unsupported" -> {
                            markChildControlUnsupported(sessionId)
                            val aborted =
                                try {
                                    request("session.abort", epoch, "sessionId" to sessionId)
                                    true
                                } catch (c: CancellationException) {
                                    setChildControl(sessionId, previous)
                                    throw c
                                } catch (_: Exception) {
                                    false
                                }
                            if (epoch == selectionEpoch)
                                reportError(
                                    if (aborted) R.string.remote_child_stopped_run_only
                                    else childControlError(e.code)
                                )
                            previous
                        }
                        else -> childControlFailure(e, previous, epoch, sent)
                    }
                } catch (e: Exception) {
                    childControlFailure(e, previous, epoch, sent)
                }
            setChildControl(sessionId, control)
        }
    }

    override fun resumeChild(message: String) {
        val current = state.value
        if (offersChildResume(current)) {
            val refusal =
                when {
                    current.attachments.isNotEmpty() -> R.string.remote_child_attachments_unsupported
                    current.quote != null -> R.string.remote_child_quote_unsupported
                    message.trimStart().startsWith("/") -> R.string.remote_child_command_unsupported
                    message.toByteArray().size > CHILD_RESUME_MAX_BYTES -> R.string.remote_prompt_too_long
                    else -> null
                }
            if (refusal != null) {
                reportError(refusal)
                return
            }
        }
        if (
            !canControlChild(current) || !offersChildResume(current) || current.sending ||
                message.isBlank() || message.toByteArray().size > CHILD_RESUME_MAX_BYTES ||
                current.attachments.isNotEmpty() || current.quote != null ||
                message.trimStart().startsWith("/")
        )
            return
        val sessionId = checkNotNull(current.selection.sessionId)
        val key = currentDraftKey() ?: return
        val previous = childControl(current)
        val epoch = selectionEpoch
        setChildControl(sessionId, ChildControl(ChildControlPhase.RESUMING, agentId = previous?.agentId))
        scope.launch {
            var sent = false
            val control =
                try {
                    val result =
                        subagentControlResult(
                            request(
                                "session.subagent.resume",
                                epoch,
                                "sessionId" to sessionId,
                                "message" to message,
                                // The agent an earlier resume started; a resume from disk renames it.
                                *listOfNotNull(previous?.agentId?.let { "agentId" to it }).toTypedArray(),
                                onSent = { sent = true },
                            ),
                            sessionId,
                        )
                    when (result.status) {
                        "accepted" -> {
                            // Only the sent text leaves the composer; newer typing stays.
                            val latest = drafts[key]
                            if (latest != null && latest.text == message) {
                                drafts[key] = latest.copy(text = "")
                                persist()
                                if (epoch == selectionEpoch && currentDraftKey() == key)
                                    update { it.copy(draft = "") }
                            }
                            refreshSessions()
                            ChildControl(
                                ChildControlPhase.RESUMED,
                                agentId = result.agentId ?: previous?.agentId,
                            )
                        }
                        "refused" ->
                            ChildControl(ChildControlPhase.REFUSED, result.reason, previous?.agentId)
                        "not_found" -> ChildControl(ChildControlPhase.NOT_FOUND)
                        else -> uncertainChildControl(previous?.agentId)
                    }
                } catch (e: CancellationException) {
                    setChildControl(
                        sessionId,
                        if (sent) ChildControl(ChildControlPhase.UNCERTAIN, agentId = previous?.agentId)
                        else previous,
                    )
                    throw e
                } catch (e: Exception) {
                    if ((e as? RemoteRequestException)?.code == "unsupported")
                        markChildControlUnsupported(sessionId)
                    childControlFailure(e, previous, epoch, sent)
                }
            setChildControl(sessionId, control)
        }
    }

    private fun markChildControlUnsupported(sessionId: String) = update {
        it.copy(childControlUnsupported = it.childControlUnsupported + sessionId)
    }

    private fun childControlError(code: String?): Int =
        when (code) {
            "unsupported" -> R.string.remote_child_unsupported
            "offline" -> R.string.remote_child_parent_offline
            else -> R.string.remote_request_error
        }

    private fun uncertainChildControl(agentId: String? = null): ChildControl {
        refreshSessions()
        return ChildControl(ChildControlPhase.UNCERTAIN, agentId = agentId)
    }

    /**
     * A stop or resume that failed. A host error code is definite, except `internal`, which the
     * host also reports for a lost parent reply: the command may have run. Any other failure once
     * the request left the phone (lost connection, timeout, malformed reply) is uncertain too;
     * only a failure before sending leaves the previous state.
     */
    private fun childControlFailure(
        e: Exception,
        previous: ChildControl?,
        epoch: Long,
        sent: Boolean,
    ): ChildControl? {
        val code = (e as? RemoteRequestException)?.code
        return when {
            code == "internal" || (code == null && sent) -> uncertainChildControl(previous?.agentId)
            code == "not_found" -> ChildControl(ChildControlPhase.NOT_FOUND)
            else -> {
                if (epoch == selectionEpoch) reportError(childControlError(code))
                previous
            }
        }
    }

    override fun refreshSessions() {
        val current = state.value
        val selection = current.selection
        val routeId = selection.routeId ?: return
        val projectId = selection.projectId ?: return
        if (!current.connected || current.loading || activeHost?.routeId != routeId ||
            sessionRefreshJob?.isActive == true
        ) return
        val last = lastSessionRefreshAt
        if (last != null && now() - last in 0 until SESSION_REFRESH_INTERVAL_MILLIS) return
        lastSessionRefreshAt = now()
        val epoch = selectionEpoch
        sessionRefreshJob = scope.launch {
            try {
                val sessions = listSessions(projectId, epoch)
                if (epoch != selectionEpoch || state.value.selection != selection ||
                    activeHost?.routeId != routeId
                ) return@launch
                cachedSessions?.takeIf { it.routeId == routeId && it.projectId == projectId }?.let {
                    cachedSessions = it.copy(sessions = sessions)
                }
                update { it.copy(sessions = sessions, sessionsFresh = true) }
                cacheCurrentScreen()
            } catch (_: CancellationException) {} catch (_: Exception) {
                // The previous list stays; the next refresh or reconnect retries.
            }
        }
    }

    private fun stopToolOutput() {
        toolOutputVersion++
        toolOutputJob?.cancel()
        toolOutputJob = null
    }

    override fun cancelToolOutput() {
        stopToolOutput()
        update { it.copy(toolOutput = null) }
    }

    override fun loadToolOutput(toolCallId: String) {
        stopToolOutput()
        val current = state.value
        val sessionId = current.selection.sessionId
        fun failure(reason: ToolOutputFailure) = ToolOutputDownload(
            toolCallId, 0, null, null, truncated = false, isError = false, failure = reason,
        )
        if (
            sessionId == null ||
                !current.connected ||
                TOOL_OUTPUT_CAPABILITY !in current.capabilities ||
                TOOL_OUTPUT_CAPABILITY in current.unavailableCapabilities
        ) {
            // An older host closes the connection on a command it does not know.
            update { it.copy(toolOutput = failure(ToolOutputFailure.UNSUPPORTED)) }
            return
        }
        if (!opaqueId(toolCallId)) {
            update { it.copy(toolOutput = failure(ToolOutputFailure.FAILED)) }
            return
        }
        val epoch = selectionEpoch
        val version = toolOutputVersion
        fun active() =
            epoch == selectionEpoch && version == toolOutputVersion &&
                state.value.selection.sessionId == sessionId
        update {
            it.copy(toolOutput = ToolOutputDownload(toolCallId, 0, null, null, false, false, null))
        }
        toolOutputJob = scope.launch {
            val buffer = java.io.ByteArrayOutputStream()
            var offset = 0L
            var total: Long? = null
            try {
                while (true) {
                    val chunk = validatedToolOutputChunk(
                        request(
                            "session.tool_output.get",
                            epoch,
                            "sessionId" to sessionId,
                            "toolCallId" to toolCallId,
                            "offset" to offset,
                        ),
                        sessionId,
                        toolCallId,
                        offset,
                        total,
                    )
                    if (!active()) return@launch
                    total = chunk.totalBytes
                    buffer.write(chunk.bytes)
                    offset += chunk.bytes.size
                    val complete = offset == chunk.totalBytes
                    update {
                        it.copy(
                            toolOutput = ToolOutputDownload(
                                toolCallId,
                                offset,
                                chunk.totalBytes,
                                if (complete) String(buffer.toByteArray(), Charsets.UTF_8) else null,
                                chunk.truncated,
                                chunk.isError,
                                null,
                            )
                        )
                    }
                    if (complete) return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!active()) return@launch
                val reason = when (e.message) {
                    "unsupported" -> ToolOutputFailure.UNSUPPORTED
                    "not_found" -> ToolOutputFailure.NOT_FOUND
                    "offline" -> ToolOutputFailure.OFFLINE
                    else -> ToolOutputFailure.FAILED
                }
                update {
                    it.copy(
                        unavailableCapabilities =
                            if (reason == ToolOutputFailure.UNSUPPORTED)
                                it.unavailableCapabilities + TOOL_OUTPUT_CAPABILITY
                            else it.unavailableCapabilities,
                        toolOutput = ToolOutputDownload(
                            toolCallId, offset, total, null, false, false, reason,
                        ),
                    )
                }
            }
        }
    }

    private fun updateFolders(block: (FolderBrowserState) -> FolderBrowserState) =
        update { it.copy(folders = block(it.folders)) }

    /** Applies [block] unless the connection or the shown host changed since [session]. */
    private fun folderWrite(routeId: String, session: Long, block: (FolderBrowserState) -> FolderBrowserState) {
        if (session == folderSession && state.value.folders.routeId == routeId) updateFolders(block)
    }

    /** The route whose folders this device may browse right now, or null. */
    private fun folderRoute(routeId: String? = state.value.folders.routeId): String? {
        val current = state.value
        return routeId?.takeIf {
            canOpenFolders(current) && activeHost?.routeId == it && current.selection.routeId == it
        }
    }

    /**
     * Sends a folder or clone command. Unlike [request] it is not bound to the selection, so
     * leaving the browser cannot drop a clone this device already started; only a lost or
     * replaced connection ends it.
     */
    private suspend fun folderRequest(type: String, vararg fields: Pair<String, Any?>): JsonObject {
        check(state.value.connected && folderCompletions.size < 32)
        val id = Wire.random()
        val completion = CompletableDeferred<JsonObject>()
        folderCompletions[id] = completion
        try {
            transport.send(Wire.objectOf("type" to type, "requestId" to id, *fields))
            return withTimeoutOrNull(30000) { completion.await() }
                ?: throw IllegalStateException("Request timed out")
        } finally {
            folderCompletions.remove(id)
        }
    }

    private fun failFolderRequests() {
        val pending = folderCompletions.values.toList()
        folderCompletions.clear()
        pending.forEach { it.completeExceptionally(IllegalStateException("Connection lost")) }
    }

    override fun browseFolder(routeId: String, path: String) {
        if (routeId.isBlank() || relativePathSegments(path) == null) return
        val version = ++folderVersion
        updateFolders { folders ->
            if (folders.routeId != routeId) {
                folderEntries.clear()
                FolderBrowserState(routeId = routeId, path = path, clones = folders.clones)
            } else {
                val same = folders.path == path
                folders.copy(
                    path = path,
                    entries = if (same) folders.entries else emptyList(),
                    truncated = folders.truncated && same,
                    loaded = folders.loaded && same,
                    loading = false,
                    error = null,
                    trust = folders.trust.takeIf { same },
                    notice = folders.notice.takeIf { same },
                )
            }
        }
        if (folderRoute(routeId) == null) return
        updateFolders { it.copy(loading = true) }
        scope.launch {
            try {
                val listing = parseFolderListing(folderRequest("fs.browse", "path" to path), path)
                if (version != folderVersion) return@launch
                listing.entries.forEach { folderEntries[joinFolderPath(path, it.name)] = it }
                while (folderEntries.size > 4096) folderEntries.remove(folderEntries.keys.first())
                updateFolders {
                    it.copy(
                        root = listing.root,
                        entries = listing.entries,
                        truncated = listing.truncated,
                        loaded = true,
                        loading = false,
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                if (version == folderVersion) updateFolders { it.copy(loading = false) }
                throw e
            } catch (e: Exception) {
                if (version == folderVersion)
                    updateFolders {
                        it.copy(loading = false, error = folderErrorMessage(e, FolderCommand.BROWSE))
                    }
            }
        }
    }

    override fun createFolder(name: String) {
        val folders = state.value.folders
        val routeId = folderRoute() ?: return
        if (folders.working || !validFolderName(name)) return
        val parent = folders.path
        val session = folderSession
        updateFolders { it.copy(working = true, notice = null) }
        scope.launch {
            try {
                val result = folderRequest("fs.mkdir", "parent" to parent, "name" to name)
                require(result.text("kind") == "fs.created")
                val path = result.text("path")
                require(relativePathSegments(path)?.isNotEmpty() == true)
                updateFolders { it.copy(working = false) }
                if (state.value.folders.routeId == routeId && state.value.folders.path == parent)
                    browseFolder(routeId, path)
            } catch (e: CancellationException) {
                folderWrite(routeId, session) { it.copy(working = false) }
                throw e
            } catch (e: Exception) {
                folderWrite(routeId, session) {
                    it.copy(working = false, notice = folderErrorMessage(e, FolderCommand.MKDIR))
                }
            }
        }
    }

    override fun cloneRepository(url: String, name: String?) {
        val folders = state.value.folders
        val routeId = folderRoute() ?: return
        if (
            folders.working ||
                folders.clones[routeId]?.stage == CloneStage.RUNNING ||
                !validCloneUrl(url) ||
                (name != null && !validFolderName(name))
        )
            return
        val parent = folders.path
        val session = folderSession
        updateFolders { it.copy(working = true, notice = null) }
        scope.launch {
            try {
                val fields =
                    listOfNotNull("parent" to parent, "url" to url, name?.let { "name" to it })
                val result = folderRequest("project.clone", *fields.toTypedArray())
                require(result.text("kind") == "clone.started")
                val cloneId = result.text("cloneId")
                val path = result.text("path")
                require(cloneId.isNotEmpty() && cloneId.length <= 64)
                require(relativePathSegments(path)?.isNotEmpty() == true)
                updateFolders {
                    it.copy(
                        working = false,
                        clones = it.clones + (routeId to CloneProgress(routeId, cloneId, parent, path)),
                    )
                }
                earlyCloneEvents.remove(cloneId)?.let(::applyCloneUpdate)
                earlyCloneEvents.clear()
            } catch (e: CancellationException) {
                folderWrite(routeId, session) { it.copy(working = false) }
                throw e
            } catch (e: Exception) {
                folderWrite(routeId, session) {
                    it.copy(working = false, notice = folderErrorMessage(e, FolderCommand.CLONE))
                }
            }
        }
    }

    /** Applies a validated clone host.event or status result to the tracked clone. */
    private fun applyCloneUpdate(value: JsonObject) {
        val cloneId = value.text("cloneId")
        val routeId = activeHost?.routeId
        val clone = routeId?.let { state.value.folders.clones[it] }
        if (clone == null || clone.cloneId != cloneId) {
            if (value.optionalText("type") == "host.event" && state.value.folders.working) {
                earlyCloneEvents[cloneId] = value
                while (earlyCloneEvents.size > 4) earlyCloneEvents.remove(earlyCloneEvents.keys.first())
            }
            return
        }
        if (clone.stage != CloneStage.RUNNING) return
        val updated = clone.updatedBy(value)
        updateFolders { it.copy(clones = it.clones + (clone.routeId to updated)) }
        val folders = state.value.folders
        if (
            updated.stage == CloneStage.SUCCEEDED &&
                folders.routeId == updated.routeId &&
                folders.path == updated.parent
        )
            browseFolder(updated.routeId, folders.path)
    }

    override fun refreshClone() = resumeClone()

    /**
     * Host events sent while this device was offline are lost, so a running clone is re-read.
     * Failures other than `not_found` retry with backoff while the connection lasts.
     */
    private fun resumeClone() {
        val routeId = activeHost?.routeId ?: return
        val clone = state.value.folders.clones[routeId]?.takeIf { it.stage == CloneStage.RUNNING } ?: return
        if (cloneStatusJob?.isActive == true || !canOpenFolders(state.value)) return
        cloneStatusJob = scope.launch {
            var attempt = 0
            while (true) {
                val current = state.value.folders.clones[routeId]
                if (
                    current?.cloneId != clone.cloneId ||
                        current.stage != CloneStage.RUNNING ||
                        activeHost?.routeId != routeId ||
                        !canOpenFolders(state.value)
                )
                    return@launch
                try {
                    val result = folderRequest("project.clone.status", "cloneId" to clone.cloneId)
                    require(result.text("kind") == "clone" && result.text("cloneId") == clone.cloneId)
                    applyCloneUpdate(result)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.message == "not_found") {
                        if (state.value.folders.clones[routeId]?.cloneId == clone.cloneId)
                            updateFolders {
                                it.copy(
                                    clones = it.clones + (routeId to clone.copy(
                                        stage = CloneStage.FAILED,
                                        failure = CloneFailure.UNKNOWN,
                                    )),
                                )
                            }
                        return@launch
                    }
                    if (++attempt >= 5) return@launch
                    delay(1000L shl attempt)
                }
            }
        }
    }

    /** Hides the shown host's clone card. A running clone goes on on the Mac, just untracked. */
    override fun dismissClone() {
        val routeId = state.value.folders.routeId ?: return
        updateFolders { it.copy(clones = it.clones - routeId) }
    }

    override fun dismissFolderNotice() = updateFolders { it.copy(notice = null) }

    private fun ProviderAuthState.offline() =
        copy(
            loading = false,
            working = false,
            error = null,
            notice = null,
            flow = flow?.copy(answering = false, cancelling = false, notice = null),
        )

    private fun updateProviders(block: (ProviderAuthState) -> ProviderAuthState) =
        update { it.copy(providerAuth = block(it.providerAuth)) }

    /** Applies [block] unless the connection or the shown host changed since [session]. */
    private fun providerWrite(routeId: String, session: Long, block: (ProviderAuthState) -> ProviderAuthState) {
        if (session == folderSession && state.value.providerAuth.routeId == routeId) updateProviders(block)
    }

    private fun updateFlow(loginId: String, block: (LoginFlow) -> LoginFlow) =
        updateProviders { auth ->
            auth.flow?.takeIf { it.loginId == loginId }?.let { auth.copy(flow = block(it)) } ?: auth
        }

    /** The route whose providers this device may manage right now, or null. */
    private fun providerRoute(routeId: String? = state.value.providerAuth.routeId): String? =
        routeId?.takeIf { canManageProviders(state.value) && activeHost?.routeId == it }

    private fun providerName(auth: ProviderAuthState, providerId: String) =
        auth.providers.firstOrNull { it.id == providerId }?.name ?: providerId

    override fun browseProviders(routeId: String) {
        updateProviders {
            if (it.routeId != routeId) ProviderAuthState(routeId = routeId)
            else it.copy(notice = null, error = null)
        }
        refreshProviders()
    }

    override fun refreshProviders() {
        val routeId = providerRoute() ?: return
        val session = folderSession
        val version = ++providerVersion
        updateProviders { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val list = parseProviderList(folderRequest("provider.auth.list"))
                if (version != providerVersion) return@launch
                providerWrite(routeId, session) { it.withList(list) }
                // A login this device knows, or the host reports as ours, may have moved on.
                if (state.value.providerAuth.flow?.finished == false) recoverLogin()
            } catch (e: CancellationException) {
                if (version == providerVersion) updateProviders { it.copy(loading = false) }
                throw e
            } catch (e: Exception) {
                if (version == providerVersion)
                    providerWrite(routeId, session) {
                        it.copy(loading = false, error = providerErrorMessage(e))
                    }
            }
        }
    }

    /** After a reconnect the host's events are lost, so the list and a running login are re-read. */
    private fun resumeProviderAuth() {
        val auth = state.value.providerAuth
        if (auth.routeId != activeHost?.routeId) return
        if (auth.loaded || auth.flow?.finished == false) refreshProviders()
    }

    /**
     * Re-reads the running login with `provider.auth.login.status`: its prompt, latest event or
     * outcome. Failures other than `not_found` retry with backoff while the connection lasts.
     */
    private fun recoverLogin() {
        val routeId = activeHost?.routeId ?: return
        val auth = state.value.providerAuth
        val flow = auth.flow?.takeIf { !it.finished && auth.routeId == routeId } ?: return
        if (providerStatusJob?.isActive == true || providerRoute(routeId) == null) return
        val session = folderSession
        providerStatusJob = scope.launch {
            var attempt = 0
            while (true) {
                val current = state.value.providerAuth.flow
                if (session != folderSession || current?.loginId != flow.loginId) return@launch
                val live = current.live
                try {
                    val status =
                        parseLoginStatus(folderRequest("provider.auth.login.status", "loginId" to flow.loginId))
                    require(status.loginId == flow.loginId)
                    providerWrite(routeId, session) { auth ->
                        auth.copy(flow = auth.flow?.takeIf { it.loginId == flow.loginId }?.updatedBy(status, live) ?: auth.flow)
                    }
                    if (status.state.finished) refreshProviders()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.message == "not_found") {
                        providerWrite(routeId, session) { auth ->
                            if (auth.flow?.loginId != flow.loginId) auth
                            else auth.copy(flow = null, notice = ProviderNotice(R.string.providers_login_lost))
                        }
                        return@launch
                    }
                    if (++attempt >= 5) return@launch
                    delay(1000L shl attempt)
                }
            }
        }
    }

    /**
     * A provider login event that fails validation is dropped instead of closing the channel; the
     * flow then reads its state from the host, which may have moved on.
     */
    private fun receiveProviderAuthEvent(payload: JsonObject) {
        val update =
            try {
                providerAuthEvent(payload)
            } catch (_: Exception) {
                if (state.value.providerAuth.flow?.finished == false) recoverLogin()
                return
            }
        update?.let(::applyProviderAuthUpdate)
    }

    /** Applies a validated login `host.event` to the flow it names. */
    private fun applyProviderAuthUpdate(update: ProviderAuthUpdate) {
        val auth = state.value.providerAuth
        val flow = auth.flow
        if (flow == null || flow.loginId != update.loginId) {
            // The start result may still be on its way; keep a few events for it.
            if (auth.working) {
                earlyProviderUpdates += update
                while (earlyProviderUpdates.size > 16) earlyProviderUpdates.removeAt(0)
            }
            return
        }
        updateFlow(flow.loginId) { it.updatedBy(update, now()) }
        if (update is ProviderAuthUpdate.Finished) refreshProviders()
    }

    override fun startLogin(providerId: String, method: ProviderAuthMethod, replace: Boolean) {
        val routeId = providerRoute() ?: return
        val auth = state.value.providerAuth
        if (auth.working || auth.busy) return
        val provider = auth.providers.firstOrNull { it.id == providerId } ?: return
        if (method !in provider.methods) return
        val session = folderSession
        earlyProviderUpdates.clear()
        updateProviders { it.copy(working = true, notice = null, flow = null) }
        scope.launch {
            try {
                val fields = mutableListOf<Pair<String, Any?>>("providerId" to providerId, "method" to method.wire)
                if (replace) fields += "replace" to true
                val started = parseLoginStarted(folderRequest("provider.auth.login.start", *fields.toTypedArray()))
                require(started.providerId == providerId && started.method == method)
                if (session != folderSession) return@launch
                providerWrite(routeId, session) {
                    it.copy(working = false, flow = LoginFlow(started.loginId, providerId, method))
                }
                val early = earlyProviderUpdates.filter { it.loginId == started.loginId }
                earlyProviderUpdates.clear()
                early.forEach(::applyProviderAuthUpdate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                earlyProviderUpdates.clear()
                providerWrite(routeId, session) {
                    it.copy(working = false, notice = ProviderNotice(providerErrorMessage(e)))
                }
                // Another login or a credential we did not know: show what the host has now.
                if (e.message == "login_in_progress" || e.message == "exists") refreshProviders()
            }
        }
    }

    override fun answerLogin(promptId: String, value: String): Boolean {
        val routeId = providerRoute() ?: return false
        val flow = state.value.providerAuth.flow ?: return false
        val prompt = flow.prompt?.takeIf { it.promptId == promptId } ?: return false
        if (flow.finished || flow.answering) return false
        if (value.toByteArray().size > MAX_PROVIDER_AUTH_ANSWER_BYTES) return false
        if (prompt.prompt.type == PromptType.SELECT && prompt.prompt.options.none { it.id == value }) return false
        val session = folderSession
        updateFlow(flow.loginId) { it.copy(answering = true, notice = null) }
        scope.launch {
            try {
                requireAccepted(
                    folderRequest(
                        "provider.auth.login.answer",
                        "loginId" to flow.loginId,
                        "promptId" to promptId,
                        "value" to value,
                    )
                )
                providerWrite(routeId, session) { auth ->
                    auth.copy(flow = auth.flow?.takeIf { it.loginId == flow.loginId }?.answered(promptId) ?: auth.flow)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                providerWrite(routeId, session) { auth ->
                    auth.copy(
                        flow = auth.flow?.takeIf { it.loginId == flow.loginId }?.let { current ->
                            when (e.message) {
                                // The prompt is gone; the status says what happened to the login.
                                "already_resolved" -> current.answered(promptId)
                                else ->
                                    current.copy(answering = false, notice = ProviderNotice(providerErrorMessage(e)))
                            }
                        } ?: auth.flow
                    )
                }
                if (e.message == "already_resolved" || e.message == "not_found") recoverLogin()
            }
        }
        return true
    }

    override fun cancelLogin() {
        val routeId = providerRoute() ?: return
        val flow = state.value.providerAuth.flow ?: return
        if (flow.finished || flow.cancelling) return
        val session = folderSession
        updateFlow(flow.loginId) { it.copy(cancelling = true, notice = null) }
        scope.launch {
            try {
                requireAccepted(folderRequest("provider.auth.login.cancel", "loginId" to flow.loginId))
                // The outcome arrives as the finished event, and it may still be a success.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                providerWrite(routeId, session) { auth ->
                    auth.copy(
                        flow = auth.flow?.takeIf { it.loginId == flow.loginId }?.copy(
                            cancelling = false,
                            notice = ProviderNotice(providerErrorMessage(e)),
                        ) ?: auth.flow
                    )
                }
                if (e.message == "already_resolved" || e.message == "not_found") recoverLogin()
            }
        }
    }

    override fun logoutProvider(providerId: String) {
        val routeId = providerRoute() ?: return
        val auth = state.value.providerAuth
        if (auth.working || auth.providers.none { it.id == providerId }) return
        val session = folderSession
        updateProviders { it.copy(working = true, notice = null) }
        scope.launch {
            try {
                parseLogout(folderRequest("provider.auth.logout", "providerId" to providerId), providerId)
                providerWrite(routeId, session) {
                    it.copy(working = false, notice = ProviderNotice(R.string.providers_logout_done, providerName(it, providerId)))
                }
                refreshProviders()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                providerWrite(routeId, session) {
                    it.copy(working = false, notice = ProviderNotice(providerErrorMessage(e)))
                }
                if (e.message == "login_in_progress") refreshProviders()
            }
        }
    }

    override fun dismissLogin() = updateProviders {
        if (it.flow?.finished == true) it.copy(flow = null) else it
    }

    override fun dismissProviderNotice() = updateProviders { it.copy(notice = null) }

    override fun cancelFolderTrust() = updateFolders { it.copy(trust = null) }

    /** Whether [path] came from [routeId]'s browser: the shown folder, a listed one or its clone. */
    private fun openablePath(routeId: String, path: String): Boolean {
        val folders = state.value.folders
        if (folders.routeId != routeId || relativePathSegments(path)?.isNotEmpty() != true) return false
        return path == folders.path ||
            path in folderEntries ||
            folders.clones[routeId]?.let { it.stage == CloneStage.SUCCEEDED && it.path == path } == true
    }

    override fun beginOpenFolder(routeId: String, path: String, confirmed: FolderTrustPrompt?): Long? {
        // The browse root is never offered: opening it would trust everything below it.
        val folders = state.value.folders
        if (folderRoute(routeId) == null || folders.working || !openablePath(routeId, path)) return null
        // trust is only sent for the prompt the user was shown.
        if (confirmed != null && (confirmed != folders.trust || confirmed.routeId != routeId || confirmed.path != path)) {
            return null
        }
        val openId = ++openCounter
        activeOpen =
            PendingOpen(
                openId,
                routeId,
                path,
                trust = confirmed != null,
                session = folderSession,
            )
        updateFolders { it.copy(working = true, trust = null, notice = null) }
        return openId
    }

    override fun endOpenFolder(openId: Long) {
        if (activeOpen?.id != openId) return
        activeOpen = null
        updateFolders { it.copy(working = false) }
    }

    /**
     * Opens a folder begun with [beginOpenFolder]. The host decides everything when the command
     * runs; the app only reacts: `trust_required` becomes the prompt the user must confirm, any
     * other error (such as `cloning`) a notice.
     */
    override suspend fun openFolder(routeId: String, path: String, openId: Long): RemoteSelection? {
        val open = activeOpen?.takeIf { it.id == openId && it.routeId == routeId && it.path == path }
            ?: return null
        // A reply from a replaced connection or another host must not prompt or notify here.
        fun current() = activeOpen?.id == openId && folderSession == open.session &&
            state.value.folders.routeId == routeId
        try {
            if (folderRoute(routeId) == null) return null
            val fields = listOfNotNull(("trust" to true).takeIf { open.trust })
            val result = request("project.open", selectionEpoch, "path" to path, *fields.toTypedArray())
            require(result.text("kind") == "project.opened")
            val projectId = result.obj("project").text("id")
            val sessionId = result.obj("session").text("id")
            clearNavigationCache()
            cachedSessions = null
            cachedChat = null
            return activate(
                RemoteSelection(routeId, projectId, sessionId),
                ActivationMode.RESTORE,
                force = true,
                useCache = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!current()) return null
            if (e.message == "trust_required") {
                // A missing or malformed flag errs toward the warning.
                val piConfig =
                    ((e as? RemoteRequestException)?.details?.get("piConfig") as? JsonPrimitive)
                        ?.takeUnless { it.isString }
                        ?.booleanOrNull ?: true
                updateFolders { it.copy(trust = FolderTrustPrompt(routeId, path, piConfig)) }
            } else updateFolders { it.copy(notice = folderErrorMessage(e, FolderCommand.OPEN)) }
            return null
        } finally {
            endOpenFolder(openId)
        }
    }

    override fun answer(questionId: String, answer: JsonObject) {
        val sessionId = state.value.selection.sessionId ?: return
        if (
            !state.value.connected ||
                state.value.loading ||
                activeHost?.routeId != state.value.selection.routeId ||
                questionId in state.value.answering
        )
            return
        update { it.copy(answering = it.answering + questionId) }
        action(
            "question.answer",
            "sessionId" to sessionId,
            "questionId" to questionId,
            "answer" to answer,
        )
    }

    private fun action(type: String, vararg fields: Pair<String, Any?>) {
        val epoch = selectionEpoch
        scope.launch {
            try {
                request(type, epoch, *fields)
            } catch (_: CancellationException) {} catch (_: Exception) {
                if (epoch == selectionEpoch) reportError(R.string.remote_request_error)
            } finally {
                if (epoch == selectionEpoch) {
                    val questionId = fields.find { it.first == "questionId" }?.second as? String
                    if (questionId != null)
                        update { it.copy(answering = it.answering - questionId) }
                }
                resumeDeferredRecovery()
            }
        }
    }

    override fun setPushToken(token: String?) {
        pushToken = token
        pushTokenKnown = true
        registerPush()
    }

    private fun registerPush() {
        if (state.value.connected && pushTokenKnown)
            action(
                "push.register",
                "token" to pushToken,
                "locale" to if (Locale.getDefault().language == "de") "de" else "en",
            )
    }

    // ---- Background jobs (session.background_jobs.v1) -------------------------------------

    private fun requireJobs() {
        val current = state.value
        if (!current.connected) throw RemoteRequestException("offline")
        // An older host closes the connection on a command it does not know.
        if (!canUseBackgroundJobs(current)) throw RemoteRequestException("unsupported")
    }

    // Built on first use. Lazy does not make it safe to reach during construction: the delegate
    // is assigned in declaration order like any other property.
    private val jobsController by lazy {
        BackgroundJobsController(
            scope,
            object : JobSource {
                override suspend fun listJobs(sessionId: String, lease: Boolean): List<BackgroundJob> {
                    requireJobs()
                    val fields = mutableListOf<Pair<String, Any?>>("sessionId" to sessionId)
                    if (lease) fields += "lease" to true
                    return parseJobList(
                        request("session.jobs.list", selectionEpoch, *fields.toTypedArray()),
                        sessionId,
                    )
                }

                override suspend fun watchJob(sessionId: String, jobId: String, since: Long?): JobChunk {
                    requireJobs()
                    val fields = listOfNotNull("sessionId" to sessionId, "jobId" to jobId, since?.let { "since" to it })
                    return parseJobWatch(
                        request("session.jobs.watch", selectionEpoch, *fields.toTypedArray()),
                        sessionId,
                        jobId,
                        since,
                    )
                }

                override suspend fun killJob(sessionId: String, jobId: String) {
                    requireJobs()
                    val result = request("session.jobs.kill", selectionEpoch, "sessionId" to sessionId, "jobId" to jobId)
                    require(result.text("kind") == "accepted" && result.text("sessionId") == sessionId)
                }
            },
            { state.value },
            ::update,
        )
    }

    override fun refreshJobs() = jobsController.refresh()

    override fun openJobs() = jobsController.openList()

    override fun closeJobs() = jobsController.closeList()

    override fun openJob(jobId: String) = jobsController.openJob(jobId)

    override fun closeJob() = jobsController.closeJob()

    override fun stopJob(jobId: String) = jobsController.kill(jobId)

    // ---- Git changes (session.git.v1) ----------------------------------------------------

    private fun requireGit(sessionId: String) {
        val current = state.value
        if (!current.connected) throw RemoteRequestException("offline")
        // An older host closes the connection on a command it does not know.
        if (GIT_CAPABILITY !in current.capabilities ||
            GIT_CAPABILITY in current.unavailableCapabilities || sessionId.isEmpty()
        ) throw RemoteRequestException("unsupported")
    }

    override suspend fun gitStatus(sessionId: String, base: GitBase): GitStatus {
        requireGit(sessionId)
        val data =
            request("session.git.status", selectionEpoch, "sessionId" to sessionId, "base" to base.wire)
        return parseGitStatus(data, sessionId, base)
    }

    override suspend fun gitDiff(sessionId: String, snapshotId: String, path: String): GitDiff {
        requireGit(sessionId)
        val data =
            request(
                "session.git.diff",
                selectionEpoch,
                "sessionId" to sessionId,
                "snapshotId" to snapshotId,
                "path" to path,
            )
        return parseGitDiff(data, sessionId, snapshotId, path)
    }

    override suspend fun gitLog(sessionId: String, snapshotId: String): GitLog {
        requireGit(sessionId)
        val data =
            request("session.git.log", selectionEpoch, "sessionId" to sessionId, "snapshotId" to snapshotId)
        return parseGitLog(data, sessionId, snapshotId)
    }

    private val changesLoader =
        GitChangesLoader(
            scope,
            object : GitSource {
                override suspend fun gitStatus(sessionId: String, base: GitBase) =
                    this@DefaultRemoteRepository.gitStatus(sessionId, base)

                override suspend fun gitDiff(sessionId: String, snapshotId: String, path: String) =
                    this@DefaultRemoteRepository.gitDiff(sessionId, snapshotId, path)

                override suspend fun gitLog(sessionId: String, snapshotId: String) =
                    this@DefaultRemoteRepository.gitLog(sessionId, snapshotId)
            },
            { state.value },
            ::update,
        )

    // Changes and files share one place on screen: opening one closes the other, which also
    // stops its loader.
    override fun openChanges() {
        filesLoader.close()
        changesLoader.open(currentDraftKey()?.let { drafts[it]?.reviewComments }.orEmpty())
    }

    override fun closeChanges() = changesLoader.close()

    override fun selectChangesBase(base: GitBase) = changesLoader.selectBase(base)

    override fun reloadChanges() = changesLoader.reload()

    override fun openChangesFile(path: String?) = changesLoader.openFile(path)

    // ---- Project files (session.files.v1) ------------------------------------------------

    private fun requireFiles(sessionId: String) {
        val current = state.value
        if (!current.connected) throw RemoteRequestException("offline")
        // An older host closes the connection on a command it does not know.
        if (FILES_CAPABILITY !in current.capabilities ||
            FILES_CAPABILITY in current.unavailableCapabilities || sessionId.isEmpty()
        ) throw RemoteRequestException("unsupported")
    }

    override suspend fun filesList(sessionId: String, path: String, after: String?): FileListing {
        requireFiles(sessionId)
        val fields = listOfNotNull("sessionId" to sessionId, "path" to path, after?.let { "after" to it })
        val data = request("session.files.list", selectionEpoch, *fields.toTypedArray())
        return parseFilesList(data, sessionId, path)
    }

    override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?): FileChunk {
        requireFiles(sessionId)
        // The first page sends neither offset nor version, like the shared fixture.
        val fields =
            listOfNotNull(
                "sessionId" to sessionId,
                "path" to path,
                if (offset > 0) "offset" to offset else null,
                if (offset > 0) version?.let { "version" to it } else null,
            )
        val data = request("session.files.read", selectionEpoch, *fields.toTypedArray())
        return parseFilesRead(data, sessionId, path, offset, version)
    }

    private val filesLoader =
        ProjectFilesLoader(
            scope,
            object : FileSource {
                override suspend fun filesList(sessionId: String, path: String, after: String?) =
                    this@DefaultRemoteRepository.filesList(sessionId, path, after)

                override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) =
                    this@DefaultRemoteRepository.filesRead(sessionId, path, offset, version)
            },
            { state.value },
            ::update,
        )

    override fun openFiles() {
        changesLoader.close()
        filesLoader.open()
    }

    override fun closeFiles() = filesLoader.close()

    override fun openFilesDir(path: String) = filesLoader.openDir(path)

    override fun openFilesFile(path: String?) = filesLoader.openFile(path)

    override fun loadMoreFiles() = filesLoader.loadMore()

    override fun reloadFiles() = filesLoader.reload()

    override fun requestFilesPreview(path: String) = filesLoader.requestPreview(path)

    override fun showFilesPeek(path: String, type: FileEntryType) = filesLoader.showPeek(path, type)

    override fun dismissFilesPeek() = filesLoader.dismissPeek()

    override fun selectFileLines(selection: LineSelection?) = filesLoader.selectLines(selection)

    private fun editReviewComments(block: (List<ReviewComment>) -> List<ReviewComment>) {
        val key = currentDraftKey() ?: return
        val draft = drafts[key] ?: StoredDraft()
        val comments = block(draft.reviewComments)
        if (comments == draft.reviewComments) return
        drafts[key] = draft.copy(reviewComments = comments)
        changesLoader.setComments(comments)
        persist()
    }

    override fun setReviewComment(comment: ReviewComment): Boolean {
        if (!validReviewComment(comment) || currentDraftKey() == null || !draftsAvailable) return false
        var saved = false
        editReviewComments { comments ->
            val next = comments.filterNot { it.sameLine(comment) } + comment
            saved = next.size <= MAX_REVIEW_COMMENTS
            if (saved) next else comments
        }
        return saved
    }

    override fun removeReviewComment(comment: ReviewComment) =
        editReviewComments { comments -> comments.filterNot { it.sameLine(comment) } }

    override fun clearReviewComments() = editReviewComments { emptyList() }
}
