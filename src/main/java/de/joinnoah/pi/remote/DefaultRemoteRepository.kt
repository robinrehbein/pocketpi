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
import kotlin.random.Random

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
private const val ATTACHMENT_READ_SPACING_MILLIS = 200L
private const val CAPABILITIES_V1_ROUTE = "capabilities.v1:"
private const val CAPABILITIES_V2_ROUTE = "capabilities.v2:"
/** The v2 list holds the v1 entries plus newer ones; installed apps accept 16, this one 32. */
private const val MAX_ROUTE_CAPABILITIES = 32

/** Sessions whose unsaved editor is kept while another session is open; the oldest goes first. */
private const val MAX_PARKED_EDITORS = 4
private val DENIED_DEVICE_ERRORS =
    setOf(R.string.remote_device_revoked, R.string.remote_device_paused)
private val CONNECTION_ERRORS =
    setOf(R.string.remote_connection_error, R.string.remote_unreachable) + DENIED_DEVICE_ERRORS
/** Errors that "Waiting for network…" replaces once the network is gone. */
private val NETWORK_WAIT_ERRORS = setOf(R.string.remote_connection_error, R.string.remote_unreachable)
/** The connection lines of an attempt that is under way and has not heard from the Mac yet. */
private val ATTEMPT_LINES = setOf(R.string.remote_connecting, R.string.remote_reconnecting)
private const val RECONNECT_BASE_MILLIS = 1_000L
private const val RECONNECT_MAX_MILLIS = 30_000L
private const val RECONNECT_JITTER = 0.2
private const val UNREACHABLE_AFTER_FAILURES = 3

/** A connection is proven by its first successful request, or by staying up this long. */
internal const val CONNECTION_PROVEN_MILLIS = 30_000L

/** An unproven connection that fails sooner than this after ready counts as short-lived. */
internal const val SHORT_LIVED_CONNECTION_MILLIS = 5_000L

/** Automatic reconnects stop after this many short-lived connections in a row. */
internal const val MAX_SHORT_LIVED_CONNECTIONS = 5

/**
 * Automatic reconnects stop after this many connections in a row that an unreadable frame ended
 * before they stayed up for [CONNECTION_PROVEN_MILLIS].
 */
internal const val MAX_BROKEN_CONNECTIONS = 5

/** At most one snapshot per this interval for events of unknown kinds or malformed ones. */
internal const val RESYNC_INTERVAL_MILLIS = 10_000L
private const val SERVICE_RESTART_CLOSE_CODE = 1012
/** connect() outlasts the transport's own handshake timeout, so only one of them fires. */
internal const val CONNECT_READY_WAIT_MILLIS = HANDSHAKE_TIMEOUT_MILLIS + 2_000L
/** The relay answered but the Mac has not, after this long: say so instead of "Connecting…". */
internal const val HOST_WAIT_HINT_MILLIS = 5_000L

/**
 * The first failure of a streak reconnects quietly; the error card follows only if the Mac is
 * still unreachable this long after it, or at once on the next failure.
 */
internal const val QUIET_RECONNECT_MILLIS = 5_000L

/** The default wait for a request's reply. */
internal const val REQUEST_TIMEOUT_MILLIS = 30_000L

/** Back this soon, on the same screen and with frames flowing, the app resumes without a request. */
internal const val RESUME_QUIET_AWAY_MILLIS = 15_000L

/** A socket that delivered a frame this recently counts as alive when the app resumes. */
internal const val RESUME_LIVE_SOCKET_MILLIS = 25_000L

/** Back this soon, or with a live socket, a connected app probes the socket instead of reconnecting. */
internal const val RESUME_PROBE_AWAY_MILLIS = 60_000L

/** How long the resume probe waits for its reply; a timeout with no frame meanwhile reconnects. */
internal const val RESUME_PROBE_TIMEOUT_MILLIS = 5_000L

/** A resume probe still pending after this long says so on the connection line. */
internal const val RESUME_PROBE_HINT_MILLIS = 1_000L

/**
 * Idempotent reads that tell whether the socket is alive: one that times out with no frame since
 * it was sent ends the connection. Tool output, media, attachment and export reads queue behind
 * each other on the host and may stay silent for long, so their timeouts never do.
 */
private val LIVENESS_READS =
    setOf(
        "projects.list",
        "sessions.list",
        "session.snapshot",
        "session.configuration.get",
        "session.commands.get",
        "session.advisor.get",
        "session.context.get",
    )

/** Why the connection ended, which decides how soon and how quietly it comes back. */
private enum class FailureCause {
    /** The transport failed or the relay closed the socket. */
    LOST,

    /** A liveness read timed out with no frame since it was sent: the socket is dead. */
    STALE,

    /** Android moved to another network, so the old socket is gone. */
    NETWORK_REPLACED,

    /** The handshake did not finish in time. */
    HANDSHAKE_TIMEOUT,
}

internal fun opaqueId(value: String): Boolean =
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

/**
 * The app's single connection to a paired Mac and the state the UI renders from it.
 *
 * Threading: all mutable state, [state] writes included, is confined to the main thread. The
 * [scope] runs on `Dispatchers.Main.immediate` in the app, transport callbacks arrive on it, and
 * only storage work hops to [io] and back. That is why `update {}` may read, modify and write
 * without a lock; debug builds check it there (see [checkMainThread]).
 */
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
    /** Uniform value in [0, 1) that spreads reconnect delays; injectable for tests. */
    private val jitter: () -> Double = { Random.nextDouble() },
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
            // Only on the capabilities.v2 route, and only when the host enables writes.
            FILES_WRITE_CAPABILITY,
            PROVIDER_AUTH_CAPABILITY,
            ATTACHMENT_READ_CAPABILITY,
            EXPORT_CAPABILITY,
            SESSION_TREE_CAPABILITY,
            // Advertised only on the capabilities.v2 route, which the first projects.list uses.
            FILES_MEDIA_CAPABILITY,
            FILES_ARTIFACT_CAPABILITY,
            RELOAD_CAPABILITY,
            ARTIFACTS_CAPABILITY,
        )
    /** The request ID prefix of `projects.list`; falls back to v1 once for a host without v2. */
    private var capabilityRoute = CAPABILITIES_V2_ROUTE
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
    private val earlyFollowUpReceipts = linkedMapOf<Triple<DraftKey, String, String>, String>()
    private var selectionEpoch = 0L
    private var generation = 0L
    private val requests = RequestLedger()
    private val completions = mutableMapOf<String, CompletableDeferred<JsonObject>>()
    private var activeHost: PairedHost? = null
    private var ready: CompletableDeferred<Unit>? = null
    private var pairing: CompletableDeferred<PairedHost>? = null
    /** When the running pairing started, by [Diagnostics.now]; its deadline counts from here. */
    private var pairingStartedAt: Long? = null
    private var retry: Job? = null
    private var recoveryJob: Job? = null
    /** The running [recoveryJob] activates the selection again, rather than probing the socket. */
    private var recoveryActivates = false
    private var deferredRecovery: RemoteRecovery.Signal? = null
    private var reconnectAttempts = 0
    private var failureStreak = 0
    /** When the current connection became ready, by [now]; null while there is none. */
    private var readyAt: Long? = null
    /** The current connection answered a request or stayed up; only that resets the backoff. */
    private var connectionProven = false
    private var provenTimer: Job? = null
    /** Connections in a row that failed within [SHORT_LIVED_CONNECTION_MILLIS] of ready. */
    private var shortLivedConnections = 0
    /**
     * Connections in a row that an unreadable frame ended. A reply does not reset this, since a
     * host can answer once and then break every connection the same way; staying up does.
     */
    private var brokenConnections = 0
    /** The transport reported that an unreadable frame ends the current connection. */
    private var brokenTeardown = false
    private var serviceRestart = false
    /** When the connection was lost, for the offline interval Diagnostics records on ready(). */
    private var offlineSince: Long? = null
    /**
     * Counts the frames that arrived, so a read can tell whether any came after it was sent. A
     * counter, not a clock: a read sent in the same millisecond as a frame must not count it.
     */
    private var inboundFrames = 0L
    /** When the app last went to the background, by [now]; null while it never did. */
    private var backgroundedAt: Long? = null
    /** When the latest frame arrived, by [now]; how long the socket was quiet picks the resume tier. */
    private var lastInboundAt: Long? = null
    /** What was selected when the app went to the background; a resume elsewhere is not free. */
    private var selectionAtBackground: RemoteSelection? = null
    /** How long the app was away before its latest return, also for a resume that was deferred. */
    private var resumeAway: Long? = null
    /** Connections that became ready; a count, so one ready in the same millisecond still counts. */
    private var readyConnections = 0L
    /** [readyConnections] when [deferredRecovery] was deferred: a connection since then read it all. */
    private var deferredRecoveryReady = 0L
    /** When the running activation started, by [now]; null while none runs. */
    private var activationStartedAt: Long? = null
    /** Times the app went to the background; a count, so the same millisecond still orders. */
    private var backgrounds = 0L
    /** [backgrounds] when the running activation started. */
    private var activationBackgrounds = 0L
    /** Raises the error card once a quiet reconnect has taken [QUIET_RECONNECT_MILLIS]. */
    private var quietReconnect: Job? = null
    /**
     * The Mac whose handshake timed out, which clears [activeHost]: its retry is a reconnect to the
     * same Mac, not a switch that resets the failure streak and the quiet-reconnect timer.
     */
    private var timedOutRoute: String? = null
    private var hostWaitHint: Job? = null
    /** Whether the current attempt reached the relay and the Mac has been silent for the hint delay. */
    private var macSilent = false
    private var restartRetryUsed = false
    private var reconnectEnabled = true
    /** The selection the latest activation was opening, whether or not it got selected. */
    private var requestedSelection: RemoteSelection? = null
    private val recovery = RemoteRecovery()
    private var timeline: Timeline? = null
    /** When the latest snapshot for an unknown or malformed event started, by [now]. */
    private var lastResyncAt: Long? = null
    private var resyncJob: Job? = null
    private var resyncEpoch = -1L
    private var noticeCount = 0L
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
    private val sessionCache = NavigationMemoryCache<Pair<String, String>, CachedSessions>(8)
    private val chatCache = NavigationMemoryCache<RemoteSelection, CachedChat>(8)
    // The current entries, which the saved navigation holds. Null clears only the pointer: an
    // invalidation removes its own LRU entries, and clearNavigationCache() all of them.
    private var cachedSessions: CachedSessions? = null
        set(value) {
            field = value
            if (value != null) sessionCache[value.routeId to value.projectId] = value
        }
    private var cachedChat: CachedChat? = null
        set(value) {
            field = value
            if (value != null) chatCache[value.selection] = value
        }

    /** What a chat's controls last showed, so a return to it shows them while they load again. */
    private data class SessionMeta(
        val configuration: SessionConfiguration?,
        val commands: List<RemoteCommand>,
        val commandsTruncated: Boolean,
        val advisor: SessionAdvisor?,
    )

    private val sessionMeta = NavigationMemoryCache<DraftKey, SessionMeta>(8)

    /** The Changes or Files pane a chat had open when it was left; a return opens it again. */
    private sealed interface PaneMemo {
        data class Changes(val base: GitBase, val file: String?) : PaneMemo

        data class Files(val dir: String, val file: String?) : PaneMemo
    }

    private val paneMemos = NavigationMemoryCache<DraftKey, PaneMemo>(8)
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

    /**
     * The Timeline select() made for a snapshot that follows; until that snapshot starts, an event
     * it buffers waits for it instead of asking for one more.
     */
    private var snapshotPending: Timeline? = null
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
            // Independent disk reads overlap. Capture failures so one corrupt store does not
            // cancel initialization or prevent another store from being recovered.
            val loadedNavigation = async(io) {
                runCatching { navigationStorage?.load().orEmpty() }
            }
            val loadedDrafts = async(io) { runCatching { draftStorage.load() } }
            val hosts =
                try {
                    withContext(io) { pairings.load() }.also { pairingsLoaded = true }
                } catch (swallowed: Exception) {
                    Diagnostics.caught("repo.loadPairings", swallowed)
                    reportError(R.string.remote_storage_error)
                    null
                }
            if (hosts != null) {
                update { it.copy(hosts = hosts.filterNot { host -> host.routeId in removedRoutes }) }
                navigationStorage?.let {
                    try {
                        val allowed = hosts.mapTo(mutableSetOf()) { it.routeId }
                        savedNavigation.putAll(
                            loadedNavigation.await().getOrThrow()
                                .filterKeys { it in allowed && it !in removedRoutes }
                        )
                        navigationLoaded = true
                    } catch (swallowed: Exception) {
                        Diagnostics.caught("repo.loadNavigation", swallowed)
                        reportError(R.string.remote_storage_error)
                    }
                }
            }
            try {
                drafts.putAll(loadedDrafts.await().getOrThrow().mapValues { (_, value) ->
                    value.copy(followUps = value.followUps.map { entry ->
                        if (entry.status in setOf("pending", "accepted")) entry.copy(status = "uncertain") else entry
                    })
                })
                persistedAttachmentIds = journalAttachmentIds(drafts)
                draftsAvailable = true
                cleanupAttachments()
            } catch (e: Exception) {
                Diagnostics.caught("repo.loadDrafts", e)
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
                    Diagnostics.caught("repo.writeDrafts", e)
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
                            withContext(io) {
                                Diagnostics.trace("PocketPi.nav.save") { navigationStorage?.save(snapshots) }
                            }
                        }
                    }
                } catch (swallowed: Exception) {
                    Diagnostics.caught("repo.writeNavigation", swallowed)
                    reportError(R.string.remote_storage_error)
                }
            }
        }
        transport.listener =
            object : RemoteTransport.Listener {
                override fun closed(code: Int) {
                    serviceRestart = code == SERVICE_RESTART_CLOSE_CODE
                }

                override fun broken() {
                    brokenTeardown = true
                }

                override fun progress(phase: HandshakePhase) {
                    handshakeProgress(phase)
                }

                override fun ready(capabilities: Set<String>) {
                    inboundFrames++
                    readyConnections++
                    lastInboundAt = now()
                    cancelHostWaitHint()
                    cancelQuietReconnect()
                    offlineSince?.let {
                        Diagnostics.record(Diagnostics.Kind.OFFLINE, "reconnected", Diagnostics.now() - it)
                    }
                    offlineSince = null
                    // The backoff resets only once this connection proves itself, so a host that
                    // accepts and then drops connections at once does not cause a tight loop.
                    connectionReady()
                    capabilityRoute = CAPABILITIES_V2_ROUTE
                    update {
                        it.copy(
                            connected = true,
                            capabilities = capabilities,
                            capabilitiesKnown = false,
                            connection = R.string.remote_connected,
                            error = null,
                            reconnectAt = null,
                        )
                    }
                    ready?.complete(Unit)
                    registerPush()
                }

                override fun approval() {
                    update {
                        it.copy(
                            connection = R.string.remote_approval,
                            pairingDeadline = pairingStartedAt?.let { started -> started + PAIRING_TIMEOUT_MILLIS },
                        )
                    }
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
                        Diagnostics.caught("repo.persistPairing", e)
                        reportError(R.string.remote_storage_error)
                        throw e
                    }
                    if (pairing?.isActive != true) {
                        // Cancelled during the write: the Mac never gets pair.ack, so the host
                        // must not stay paired here either.
                        try {
                            withContext(io) { pairings.save(state.value.hosts) }
                        } catch (e: Exception) {
                            Diagnostics.caught("repo.persistPairing.rollback", e)
                        }
                        error("Pairing cancelled")
                    }
                    checkEpoch(epoch)
                    update { it.copy(hosts = hosts) }
                }

                override fun paired(host: PairedHost) {
                    pairing?.complete(host)
                }

                override fun message(payload: JsonObject) {
                    inboundFrames++
                    lastInboundAt = now()
                    receive(payload)
                }

                override fun failed(reconnect: Boolean, error: Int) {
                    val broken = brokenTeardown
                    brokenTeardown = false
                    this@DefaultRemoteRepository.failed(reconnect, error, broken)
                }
            }
    }

    private fun update(block: (RemoteState) -> RemoteState) {
        checkMainThread()
        mutable.value = block(mutable.value)
    }

    /**
     * Once the relay is reached and the Mac stays silent for [HOST_WAIT_HINT_MILLIS], the
     * connection line says the app waits for the Mac; any later phase puts "Connecting…" back.
     */
    private fun handshakeProgress(phase: HandshakePhase) {
        cancelHostWaitHint()
        if (state.value.connection == R.string.remote_waiting_for_mac)
            update { it.copy(connection = attemptLine()) }
        if (phase != HandshakePhase.CHALLENGE) return
        hostWaitHint = scope.launch {
            delay(HOST_WAIT_HINT_MILLIS)
            macSilent = true
            if (!state.value.connected && state.value.connection in ATTEMPT_LINES)
                update { it.copy(connection = R.string.remote_waiting_for_mac) }
        }
    }

    private fun cancelHostWaitHint() {
        hostWaitHint?.cancel()
        hostWaitHint = null
        macSilent = false
    }

    /**
     * The connection line while [host] is not connected: an activation that joins an attempt
     * still waiting on a silent Mac keeps the hint, since no new phase will bring it back.
     */
    private fun connectingLine(host: PairedHost) =
        if (macSilent && ready?.isActive == true && activeHost?.routeId == host.routeId)
            R.string.remote_waiting_for_mac
        else attemptLine()

    /** "Reconnecting…" while the last-known state stays on screen, "Connecting…" otherwise. */
    private fun attemptLine() =
        if (state.value.stale) R.string.remote_reconnecting else R.string.remote_connecting

    /** Whether the state on screen belongs to [routeId], so a reconnect to it may keep it. */
    private fun showsRoute(routeId: String?) = routeId != null && state.value.host?.routeId == routeId

    /**
     * [state] without what the last connection reported about its sessions: for another Mac, or
     * when a lost connection's state is not the one on screen.
     */
    private fun lastKnownCleared(state: RemoteState) =
        state.copy(
            stale = false,
            staleSince = null,
            questions = emptyList(),
            capabilities = emptySet(),
            capabilitiesKnown = false,
            unavailableCapabilities = emptySet(),
            childControlUnsupported = emptySet(),
            configuration = null,
            contextUsage = null,
            contextLoading = false,
            advisor = null,
            advisorLoading = false,
            advisorChanging = false,
            commands = emptyList(),
            status = "offline",
        )

    private fun clearNavigationCache() {
        cachedProjects = null
        cachedProjectChats = null
        projectChatCandidates.clear()
        projectChatsJob?.cancel()
        attentionRefreshJob?.cancel()
        cachedSessions = null
        cachedChat = null
        sessionCache.clear()
        chatCache.clear()
        sessionMeta.clear()
        paneMemos.clear()
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
        } catch (swallowed: Exception) {
            Diagnostics.caught("repo.ensureNavigationLoaded", swallowed)
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
                    } catch (swallowed: Exception) {
                        Diagnostics.caught("repo.refreshProjectChats", swallowed)
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

    /** Selects [selection] from the caches; returns whether they held it. */
    private fun showCachedSelection(selection: RemoteSelection, host: PairedHost): Boolean {
        restoreNavigation(host.routeId)
        val projects = cachedProjects?.takeIf { it.first == host.routeId }?.second ?: return false
        val projectId = selection.projectId
        if (projectId == null) {
            select(RemoteSelection(host.routeId), host, projects)
            return true
        }
        val sessions =
            sessionCache[host.routeId to projectId]
                ?: run {
                    val project = projects.find { it.text("id") == projectId } ?: return false
                    val summaries = cachedProjectChats?.takeIf { it.first == host.routeId }
                        ?.second.orEmpty().filter { it.projectId == projectId }
                    if (summaries.isEmpty()) return false
                    CachedSessions(host.routeId, projectId, project, summaries.map { it.session })
                }
        if (selection.sessionId == null) {
            select(RemoteSelection(host.routeId, projectId), host, projects, sessions.project, sessions.sessions)
            return true
        }
        val chat = chatCache[selection]
        val session = chat?.session ?: sessions.sessions.find { it.text("id") == selection.sessionId }
            ?: return false
        select(selection, host, projects, sessions.project, sessions.sessions, session, chat)
        return true
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
                    // Only this project's list and the closed chat are out of date.
                    selection.routeId?.let { routeId ->
                        sessionCache.remove(routeId to projectId)
                        chatCache.remove(RemoteSelection(routeId, projectId, sessionId))
                    }
                    cachedSessions = null
                    if (cachedChat?.selection?.sessionId == sessionId) cachedChat = null
                    activate(state.value.selection, ActivationMode.RESTORE, force = true)
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (swallowed: Exception) {
                Diagnostics.caught("repo.closeSession", swallowed)
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
            } catch (swallowed: Exception) {
                Diagnostics.caught("repo.unshareProject", swallowed)
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
        // A connection that came up after the signal was followed by an activation that read it all.
        if (readyConnections != deferredRecoveryReady) {
            Diagnostics.record(Diagnostics.Kind.RECOVERY, "recover." + signal.name.lowercase(), outcome = "fresh")
            return
        }
        recover(signal)
    }

    override fun dismissError() = update { it.copy(error = null) }

    override fun dismissNotice(id: Long) = update { if (it.notice?.id == id) it.copy(notice = null) else it }

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

    /** The connection is down and the connection line or card says so, or will shortly. */
    private fun connectionDown(): Boolean =
        state.value.let { !it.connected && (it.error == null || it.error in CONNECTION_ERRORS) }

    /**
     * Reports [resource] for a read that failed, unless it got no reply because the connection
     * went down: the reconnect reads it again, and the error card is the connection's to raise.
     */
    private fun reportReadError(error: Throwable, resource: Int = R.string.remote_request_error) {
        if (!lostWithConnection(error)) reportError(resource)
    }

    /** [error] ended a request that got no reply because the connection went down. */
    private fun lostWithConnection(error: Throwable): Boolean =
        (error.requestKind == RemoteRequestException.Kind.TIMEOUT ||
            error.requestKind == RemoteRequestException.Kind.CONNECTION_LOST) &&
            connectionDown()

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
                // A reconnect re-activates the chat on screen, and select() decides what stays.
                toolOutput = it.toolOutput?.takeIf { output -> it.stale && output.text != null },
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
            val previousRoute = activeHost?.routeId ?: timedOutRoute
            timedOutRoute = null
            if (previousRoute != host.routeId && markPendingFollowUpsUncertain(previousRoute)) {
                persist().await()
            }
            if (previousRoute != host.routeId) {
                offlineSince = null
                cancelQuietReconnect()
                resetReconnect()
                clearNavigationCache()
                attachmentBytes.clear()
                SentImageThumbnails.clear()
                projectImageBytes.clear()
                projectArtifacts.clear()
                ProjectImageBitmaps.clear()
                ArtifactThumbnails.clear()
            }
            cachedProjectChats = cachedProjectChats?.takeIf { it.first == host.routeId }
                ?.let { it.first to it.second.map { chat -> chat.copy(verified = false) } }
            projectChatCandidates.replaceAll { _, chats ->
                chats.map { it.copy(verified = false) }
            }
            // A connection replaced on purpose is no failure.
            connectionEnded()
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
            // A reconnect to the Mac on screen keeps its last-known state; another Mac starts empty.
            val keep = state.value.stale && showsRoute(host.routeId)
            val line = if (keep) R.string.remote_reconnecting else R.string.remote_connecting
            activeHost = host
            ready = CompletableDeferred()
            stopToolOutput()
            update {
                val shown = if (keep) it else lastKnownCleared(it)
                shown.copy(
                    connected = false,
                    connection = line,
                    compaction = null,
                    compactionRequesting = false,
                    // A finished download stays readable; a running one stopped above.
                    toolOutput = it.toolOutput?.takeIf { output -> keep && output.text != null },
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
        if (withTimeoutOrNull(CONNECT_READY_WAIT_MILLIS) { checkNotNull(ready).await() } == null) {
            Diagnostics.record(Diagnostics.Kind.FAILURE, "connect.wait", CONNECT_READY_WAIT_MILLIS, outcome = "timeout")
            if (epoch == selectionEpoch) {
                // Goes offline and schedules the next attempt like any transport failure.
                failed(true, R.string.remote_connection_error, cause = FailureCause.HANDSHAKE_TIMEOUT)
                ready?.cancel()
                ready = null
                timedOutRoute = activeHost?.routeId
                activeHost = null
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
        timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
    ): JsonObject {
        checkEpoch(epoch)
        check(state.value.connected)
        // Older hosts accept this as an opaque request ID and return ordinary projects.
        val id = if (type == "projects.list") "$capabilityRoute$requestId" else requestId
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
        val started = Diagnostics.now()
        val traceName = "PocketPi.req.$type"
        val cookie = Diagnostics.beginAsync(traceName)
        var outcome = "failed"
        var ended: Long? = null
        val connection = generation
        val inboundAtSend = inboundFrames
        try {
            // Set before sending: a send that throws may still have reached the host.
            onSent?.invoke()
            transport.send(payload)
            val result =
                withTimeoutOrNull(timeoutMillis) { completion.await() }
                    ?: run {
                        outcome = "timeout"
                        // Not a frame since the read went out: the socket is dead, not the host slow.
                        if (
                            type in LIVENESS_READS &&
                                inboundFrames == inboundAtSend &&
                                state.value.connected &&
                                connection == generation
                        )
                            connectionStale(type)
                        throw RemoteRequestException.timeout()
                    }
            outcome = "ok"
            connectionProven()
            checkEpoch(epoch)
            if (type == "projects.list" && result.text("kind") == "projects") {
                if (id.startsWith(CAPABILITIES_V2_ROUTE) && result["capabilities"] !is JsonArray) {
                    // A host that predates the v2 route treats the ID as opaque and sends no list.
                    capabilityRoute = CAPABILITIES_V1_ROUTE
                    // The retry records its own round trip; this one ends here.
                    outcome = "retry.v1"
                    ended = Diagnostics.now()
                    return request(type, epoch, *fields, draft = draft, timeoutMillis = timeoutMillis)
                }
                val advertised = (result["capabilities"] as? JsonArray)
                    ?.takeIf { it.size <= MAX_ROUTE_CAPABILITIES }
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
                update { it.copy(capabilitiesKnown = true) }
                if (PROJECT_OPEN_CAPABILITY in merged) resumeClone()
                if (PROVIDER_AUTH_CAPABILITY in merged) resumeProviderAuth()
            }
            return result
        } catch (e: Throwable) {
            if (outcome != "timeout") outcome = requestOutcome(e)
            throw e
        } finally {
            requests.take(id, epoch)
            completions.remove(id)
            Diagnostics.endAsync(traceName, cookie)
            // The payload is ASCII JSON in all but rare cases, so its length stands for its size.
            Diagnostics.request(type, (ended ?: Diagnostics.now()) - started, payload.toString().length.toLong(), outcome)
        }
    }

    /** A request's outcome for Diagnostics: the host's error code, or what ended the wait. */
    private fun requestOutcome(error: Throwable): String =
        when (error) {
            is RemoteRequestException ->
                when (error.kind) {
                    RemoteRequestException.Kind.TIMEOUT -> "timeout"
                    RemoteRequestException.Kind.CONNECTION_LOST -> "lost"
                    else -> "error." + error.code
                }
            is CancellationException -> "cancelled"
            else -> error.javaClass.simpleName
        }

    /** The error's `message`, shortened; only some callers read it. */
    private fun hostMessage(error: JsonObject): String? =
        (error["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(256)

    /**
     * Handles one decrypted payload. A newer host may send payload types and event kinds this app
     * does not know: they are ignored, and a malformed event is dropped (a timeline event asks for
     * a snapshot instead), so neither costs the connection. Only a frame without a readable type
     * or request ID tears it down, and the reconnect follows as for any lost connection.
     */
    private fun receive(payload: JsonObject) {
        try {
            when (payload.text("type")) {
                "result" -> receiveResult(payload)
                "event" -> receiveEvent(payload)
                "host.event" ->
                    try {
                        if (payload.optionalText("kind")?.startsWith("provider.auth.") == true) {
                            receiveProviderAuthEvent(payload)
                        } else {
                            cloneEvent(payload)?.let(::applyCloneUpdate)
                                ?: jobEvent(payload)?.let(jobsController::onEvent)
                        }
                    } catch (dropped: Exception) {
                        Diagnostics.caught("repo.receive.hostEvent", dropped)
                    }
                // A payload type from a newer host.
                else -> Unit
            }
        } catch (swallowed: Exception) {
            Diagnostics.caught("repo.receive", swallowed)
            failed(true, R.string.remote_connection_error, broken = true)
        }
    }

    private fun receiveResult(payload: JsonObject) {
        val id = payload.text("requestId")
        val completion =
            folderCompletions.remove(id)
                ?: run {
                    requests.take(id, selectionEpoch) ?: return
                    completions.remove(id) ?: return
                }
        try {
            if (payload.flag("ok")) completion.complete(payload.obj("data"))
            else
                payload.obj("error").let { error ->
                    completion.completeExceptionally(
                        RemoteRequestException(error.text("code"), error["details"] as? JsonObject, hostMessage(error))
                    )
                }
        } catch (malformed: Exception) {
            // The request fails now instead of waiting for its timeout; the connection stays.
            Diagnostics.caught("repo.receive.result", malformed)
            completion.completeExceptionally(malformed)
        }
    }

    private fun receiveEvent(payload: JsonObject) {
        try {
            eventEffects(payload)
        } catch (dropped: Exception) {
            // A malformed status, title, receipt or notice is dropped; the timeline still sees the
            // event, so its revision stays in step.
            Diagnostics.caught("repo.receive.event", dropped)
        }
        timeline?.let { current ->
            try {
                val timelineEvent =
                    if (payload.optionalText("kind") in setOf("follow_up.status", "steer.status"))
                        JsonObject(payload + ("kind" to JsonPrimitive("command.status")))
                    else payload
                // The snapshot select() set up takes in what this buffered; asking for another
                // one would only be discarded.
                if (current.event(timelineEvent) && !resynchronizing && snapshotPending !== current)
                    snapshotAsync()
            } catch (malformed: Exception) {
                Diagnostics.caught("repo.receive.timeline", malformed)
                current.unknownKindSeen = true
            }
            resyncAfterUnknownEvents(current)
            // Another session's event cannot change this chat.
            if ((payload["sessionId"] as? JsonPrimitive)?.content == current.sessionId) publishTimeline()
        }
        try {
            if (payload.optionalText("kind") == "session.compaction" &&
                payload.optionalText("sessionId") == state.value.selection.sessionId &&
                payload.optionalText("state") == "done") {
                update { it.copy(contextUsage = null) }
                refreshContextUsage()
            }
        } catch (dropped: Exception) {
            Diagnostics.caught("repo.receive.compaction", dropped)
        }
    }

    /** The parts of an event outside the timeline: statuses, titles, receipts and notices. */
    private fun eventEffects(payload: JsonObject) {
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
        if (payload.text("kind") == "message.upsert") {
            messageDeliveryReceipt(payload.text("sessionId"), payload.obj("message"))
        }
        if (payload.text("kind") == "command.status") commandReceipt(payload)
        if (payload.text("kind") in setOf("follow_up.status", "steer.status")) followUpReceipt(payload)
        if (payload.text("kind") == "session.notice") sessionNotice(payload)
    }

    /**
     * After an event of an unknown kind or a malformed one, the chat may miss what it changed, so
     * a snapshot follows: at once, or at the end of the current [RESYNC_INTERVAL_MILLIS] window, so
     * a host that keeps sending such events causes at most one snapshot per window.
     */
    private fun resyncAfterUnknownEvents(current: Timeline) {
        if (!current.unknownKindSeen) return
        current.unknownKindSeen = false
        val epoch = selectionEpoch
        if (resyncJob?.isActive == true && resyncEpoch == epoch) return
        resyncJob?.cancel()
        val wait =
            lastResyncAt?.let { RESYNC_INTERVAL_MILLIS - (now() - it) }?.coerceIn(0, RESYNC_INTERVAL_MILLIS) ?: 0
        resyncEpoch = epoch
        resyncJob = scope.launch {
            delay(wait)
            if (epoch != selectionEpoch || !state.value.connected || timeline !== current) return@launch
            lastResyncAt = now()
            Diagnostics.record(Diagnostics.Kind.RECOVERY, "resync.unknown", wait)
            snapshotAsync()
        }
    }

    private fun followUpReceipt(event: JsonObject) {
        val delivery = if (event.text("kind") == "steer.status") "steer" else "follow_up"
        applyFollowUpReceipt(event.text("sessionId"), event.text("requestId"), delivery, event.text("status"))
    }

    private fun messageDeliveryReceipt(sessionId: String, message: JsonObject) {
        if (message.optionalText("role") != "user") return
        val metadata = message["remoteDelivery"] as? JsonObject ?: return
        val id = (metadata["requestId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        val delivery = (metadata["delivery"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        if (id.isEmpty() || id.toByteArray().size > 256 || id.any { it.code <= 32 || it.code == 127 } ||
            delivery !in setOf("steer", "follow_up")) return
        applyFollowUpReceipt(sessionId, id, delivery, "delivered")
    }

    private fun mergedFollowUpStatus(previous: String, incoming: String): String = when {
        previous == "delivered" -> previous
        incoming == "delivered" -> incoming
        previous in setOf("cancelled", "uncertain") -> previous
        else -> incoming
    }

    private fun applyFollowUpReceipt(sessionId: String, id: String, delivery: String, status: String) {
        require(status in setOf("accepted", "delivered", "cancelled", "uncertain"))
        val key = DraftKey(activeHost?.routeId ?: return, sessionId)
        val latest = drafts[key] ?: return
        if (latest.followUps.none { it.requestId == id && it.delivery == delivery }) {
            // Only the in-flight mutation can receive a receipt before its acknowledgement.
            if (latest.mutationId != id) return
            if (status == "delivered" && latest.submittedText != null) {
                // Consumption proves delivery even if the prompt acknowledgement was lost.
                val unchanged = latest.text == latest.submittedText && latest.quote == latest.submittedQuote
                val attachments = if (latest.attachments == latest.submittedAttachments) emptyList()
                    else latest.attachments
                val confirmed = latest.copy(
                    text = if (unchanged) "" else latest.text,
                    quote = if (unchanged) null else latest.quote,
                    attachments = attachments,
                    uploads = latest.uploads.filter { upload -> attachments.any { it.id == upload.localId } },
                    mutationId = null,
                    submittedText = null,
                    submittedQuote = null,
                    submittedAttachments = emptyList(),
                    followUps = latest.followUps + PendingFollowUp(id, latest.submittedText, "delivered", delivery),
                )
                drafts[key] = confirmed
                persist()
                if (currentDraftKey() == key) update { it.copy(
                    draft = confirmed.text, quote = confirmed.quote, attachments = confirmed.attachments,
                    uncertain = false, followUps = confirmed.followUps,
                ) }
                return
            }
            val receiptKey = Triple(key, id, delivery)
            earlyFollowUpReceipts[receiptKey] = mergedFollowUpStatus(
                earlyFollowUpReceipts[receiptKey] ?: "pending", status)
            while (earlyFollowUpReceipts.size > 128) earlyFollowUpReceipts.remove(earlyFollowUpReceipts.keys.first())
            return
        }
        val entries = latest.followUps.map {
            if (it.requestId == id && it.delivery == delivery)
                it.copy(status = mergedFollowUpStatus(it.status, status)) else it
        }
        if (entries == latest.followUps) return
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
            sessionCache.transformValues { cached ->
                if (cached.routeId == routeId) cached.copy(sessions = cached.sessions.map(::renamed))
                else cached
            }
            chatCache.transformValues { cached ->
                if (cached.selection.routeId == routeId) cached.copy(session = renamed(cached.session))
                else cached
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
        require(revision >= 0 && status in SESSION_STATUSES)
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
            sessionCache.transformValues { cached ->
                if (cached.routeId == routeId)
                    cached.copy(sessions = cached.sessions.map(::withSessionStatus))
                else cached
            }
            chatCache.transformValues { cached ->
                if (cached.selection.routeId == routeId && cached.selection.sessionId == id)
                    cached.copy(session = withSessionStatus(cached.session), status = status)
                else cached
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

    private fun publishTimeline(): Unit = Diagnostics.trace("PocketPi.publishTimeline") {
        val t = timeline ?: return@trace
        if (t.status != state.value.status) state.value.selection.sessionId?.let(::dropStaleChildControl)
        update {
            // Before its first snapshot the Timeline holds no messages yet: what is on screen (a
            // cached or the last-known chat) stays until the snapshot replaces it.
            if (!t.hasSnapshot) it.copy(status = t.status, compaction = t.compaction)
            else
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

    /** [timeoutMillis] lets a probe give up sooner; a timeout with no frame meanwhile reconnects. */
    private suspend fun snapshot(
        epoch: Long,
        older: Boolean = false,
        timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
    ) {
        val current = timeline ?: return
        val cursor = if (older) current.nextCursor ?: return else null
        resynchronizing = true
        if (snapshotPending === current) snapshotPending = null
        try {
            val data =
                if (cursor == null)
                    request(
                        "session.snapshot",
                        epoch,
                        "sessionId" to current.sessionId,
                        timeoutMillis = timeoutMillis,
                    )
                else
                    request(
                        "session.snapshot",
                        epoch,
                        "sessionId" to current.sessionId,
                        "cursor" to cursor,
                        timeoutMillis = timeoutMillis,
                    )
            checkEpoch(epoch)
            if (current !== timeline) return
            current.snapshot(data, older)
            current.messages.forEach { messageDeliveryReceipt(current.sessionId, it) }
            publishTimeline()
            // The chat on screen is read again, also when the activation after a reconnect failed
            // to read it and a later refresh did.
            if (!older && state.value.connected) staleReplaced()
            resyncAfterUnknownEvents(current)
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
            } catch (_: CancellationException) {} catch (swallowed: Exception) {
                Diagnostics.caught("repo.snapshotAsync", swallowed)
                if (epoch == selectionEpoch) reportReadError(swallowed)
            }
        }
    }

    /**
     * Unsaved edits of sessions left without the discard prompt (a notification tap, Back, a
     * restore), by session, oldest first. Not persisted; they come back when their session opens.
     * Past [MAX_PARKED_EDITORS] the oldest is dropped with a message, and an unpaired host's go too.
     */
    private val parkedFiles = LinkedHashMap<DraftKey, FilesState>()

    /**
     * Parks the file browser of [previous] when its editor holds unsaved changes, and returns the
     * one parked for [selection], ready to show again: nothing loading, and a save that was still
     * running counts as unanswered, so Retry resends it.
     */
    private fun switchParkedEditor(previous: RemoteSelection, selection: RemoteSelection): FilesState? {
        val leaving = state.value.files
        val previousRoute = previous.routeId
        if (previousRoute != null && leaving != null && leaving.sessionId == previous.sessionId && leaving.editor?.changed == true) {
            val key = DraftKey(previousRoute, leaving.sessionId)
            parkedFiles.remove(key)
            parkedFiles[key] = leaving
            if (parkedFiles.size > MAX_PARKED_EDITORS) {
                while (parkedFiles.size > MAX_PARKED_EDITORS) parkedFiles.remove(parkedFiles.keys.first())
                // The oldest edit is gone for good: say so once instead of losing it silently.
                reportError(R.string.remote_files_editor_parked_dropped)
            }
        }
        val routeId = selection.routeId ?: return null
        val sessionId = selection.sessionId ?: return null
        val parked = parkedFiles.remove(DraftKey(routeId, sessionId)) ?: return null
        val editor = checkNotNull(parked.editor)
        return parked.copy(
            loading = false,
            failure = parked.failure ?: if (parked.listing == null) FilesFailure.FAILED else null,
            moreLoading = false,
            previews = emptyMap(),
            peek = null,
            file = parked.file?.copy(loading = false, moreLoading = false),
            editor = editor.copy(
                saving = false,
                saveFailure = if (editor.saving) FilesFailure.OFFLINE else editor.saveFailure,
                leaveRequest = null,
            ),
        )
    }

    /**
     * Remembers what the chat [previous] showed as it is left: its controls, and the Changes or
     * Files pane open in it. An unsaved edit is parked by [switchParkedEditor] instead.
     */
    private fun rememberSession(previous: RemoteSelection) {
        val routeId = previous.routeId ?: return
        val sessionId = previous.sessionId ?: return
        val key = DraftKey(routeId, sessionId)
        val current = state.value
        // A chat whose controls were cleared (a lost connection) keeps what it had before.
        if (current.configuration != null || current.commands.isNotEmpty() || current.advisor != null)
            sessionMeta[key] =
                SessionMeta(current.configuration, current.commands, current.commandsTruncated, current.advisor)
        val changes = current.changes?.takeIf { it.sessionId == sessionId }
        val files = current.files?.takeIf { it.sessionId == sessionId && it.editor?.changed != true }
        when {
            changes != null ->
                paneMemos[key] =
                    PaneMemo.Changes(changes.base, changes.file?.takeIf { changes.fileSession == null })
            files != null -> paneMemos[key] = PaneMemo.Files(files.path, files.file?.path)
            else -> paneMemos.remove(key)
        }
    }

    /**
     * After the snapshot of the selected chat: a pane kept open by [select] reads again in place,
     * and one the chat had open when it was last left opens again.
     */
    private fun refreshPanes() {
        val current = state.value
        val routeId = current.selection.routeId ?: return
        val sessionId = current.selection.sessionId ?: return
        val memo = paneMemos.remove(DraftKey(routeId, sessionId))
        when {
            current.changes?.sessionId == sessionId -> changesLoader.refresh()
            current.files?.sessionId == sessionId -> filesLoader.refresh()
            memo is PaneMemo.Changes ->
                changesLoader.open(drafts[DraftKey(routeId, sessionId)]?.reviewComments.orEmpty(), memo.base, memo.file)
            memo is PaneMemo.Files -> filesLoader.open(memo.dir, memo.file)
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
        val previous = state.value.selection
        // The same chat of the same Mac selected again (a reconnect, a resume, a restore): what it
        // shows stays until the snapshot and the reads after it replace it.
        val sameSession = selection.sessionId != null && selection.routeId == previous.routeId &&
            selection.sessionId == previous.sessionId
        timeline = session?.let {
            Timeline(it.text("id")).apply {
                beginSnapshotEpoch(it.text("status"))
                // Older pages loaded on screen survive the snapshot where it overlaps them.
                if (sameSession) retain(state.value.messages, state.value.nextCursor)
            }
        }
        snapshotPending = timeline
        val stored =
            selection.sessionId?.let { drafts[DraftKey(checkNotNull(selection.routeId), it)] }
                ?: StoredDraft()
        // A download of the chat on screen keeps going; one of another chat or Mac stops.
        if (!sameSession) {
            stopToolOutput()
            jobsController.stop()
            rememberSession(previous)
        }
        val resumedFiles = if (sameSession) null else switchParkedEditor(previous, selection)
        val meta =
            if (sameSession) null
            else selection.sessionId?.let { sessionMeta[DraftKey(checkNotNull(selection.routeId), it)] }
        update {
            it.copy(
                selection = selection,
                compaction = null,
                compactionRequesting = false,
                toolOutput = it.toolOutput?.takeIf { sameSession },
                // The panes of the chat on screen stay open; the reads after the snapshot refresh
                // them in place.
                changes = it.changes?.takeIf { changes -> sameSession && changes.sessionId == selection.sessionId },
                // Another session brings back its own parked edit, if any.
                files = resumedFiles
                    ?: it.files?.takeIf { files -> sameSession && files.sessionId == selection.sessionId },
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
                messages = cachedChat?.messages ?: if (sameSession) it.messages else emptyList(),
                questions = if (sameSession) it.questions else emptyList(),
                draft = stored.text,
                quote = stored.quote,
                attachments = stored.attachments,
                // An import or upload of this chat that still runs keeps its progress.
                importingAttachments = sameSession && it.importingAttachments && importJob?.isActive == true,
                attachmentProgress = it.attachmentProgress?.takeIf { sameSession && sendJob?.isActive == true },
                uncertain = stored.mutationId != null,
                followUps = stored.followUps,
                sending = false,
                answering = emptySet(),
                status =
                    if (state.value.connected && activeHost?.routeId == selection.routeId)
                        cachedChat?.status ?: session?.text("status") ?: "offline"
                    else "offline",
                nextCursor =
                    if (cachedChat != null) cachedChat.nextCursor else if (sameSession) it.nextCursor else null,
                // Another chat shows what it showed when it was left, until its reads replace it.
                configuration = if (sameSession) it.configuration else meta?.configuration,
                contextUsage = if (sameSession) it.contextUsage else null,
                advisor = if (sameSession) it.advisor else meta?.advisor,
                advisorLoading = false,
                advisorChanging = false,
                contextLoading = false,
                // Learned again by the reads after the selection, also after a reconnect: the host
                // may have been updated meanwhile.
                unavailableCapabilities = emptySet(),
                childControlUnsupported = emptySet(),
                configurationLoading = false,
                configurationChanging = false,
                commands = if (sameSession) it.commands else meta?.commands.orEmpty(),
                commandsLoading = false,
                commandsTruncated = if (sameSession) it.commandsTruncated else meta?.commandsTruncated == true,
                commandNotice = null,
                notice = it.notice?.takeIf { sameSession },
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
        if (mode == ActivationMode.RESTORE && state.value.deniedRouteId != null) {
            // A restore on navigation must not retry the paused or revoked device behind the
            // user's back; any other route, the Hosts list included, is unaffected by it.
            if (selection.routeId == state.value.deniedRouteId) return state.value.selection
            reconnectEnabled = true
        }
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
        cacheCurrentScreen()
        if (!force) cancelSelection()
        if (!useCache) clearNavigationCache()
        val epoch = selectionEpoch
        stopReconnectTimer()
        requestedSelection = selection
        activationStartedAt = now()
        activationBackgrounds = backgrounds
        update { it.copy(loading = true, deniedRouteId = null) }
        val started = Diagnostics.now()
        val cookie = Diagnostics.beginAsync("PocketPi.activate")
        var outcome = "failed"
        var projectsCached = false
        var sessionsCached = false
        var chatCached = false
        var cachedShown = false
        try {
            val host = state.value.hosts.find { it.routeId == selection.routeId }
            if (host == null) {
                select(RemoteSelection(), null, emptyList())
                outcome = "no_host"
                return state.value.selection
            }
            fun showCached(): Boolean {
                val shown = showCachedSelection(selection, host)
                if (!cachedShown && state.value.selection.routeId == host.routeId && state.value.projects.isNotEmpty()) {
                    cachedShown = true
                    Diagnostics.record(
                        Diagnostics.Kind.ACTIVATE,
                        "activate.cached",
                        Diagnostics.now() - started,
                    )
                }
                return shown
            }
            val alreadyConnected = state.value.connected && activeHost?.routeId == host.routeId
            // A reconnect to the Mac on screen keeps its last-known state until it is read again.
            val reconnecting = !alreadyConnected && state.value.stale && showsRoute(host.routeId)
            if (!alreadyConnected) {
                stopToolOutput()
                if (!reconnecting) update(::lastKnownCleared)
                val line = connectingLine(host)
                update {
                    it.copy(
                        connected = false,
                        connection = line,
                        compaction = null,
                        compactionRequesting = false,
                        // A finished download stays readable; a running one stopped above.
                        toolOutput = it.toolOutput?.takeIf { output -> reconnecting && output.text != null },
                    )
                }
            }
            // Explicit opens can fork historical sessions: wait for canonical IDs before
            // changing selection, but retain cached content while the snapshot refreshes. A
            // reconnect already shows this selection, fresher than the cache.
            val showCache =
                useCache && mode == ActivationMode.RESTORE &&
                    !(reconnecting && selection == state.value.selection)
            // The cache shown before connecting stays; only a miss tries again once connected.
            val shownBeforeConnect = showCache && showCached()
            connect(host, epoch)
            if (showCache && !shownBeforeConnect) showCached()
            val reusedProjects =
                cachedProjects
                    ?.takeIf { useCache && !force && alreadyConnected && it.first == host.routeId }
                    ?.second
            projectsCached = reusedProjects != null
            val projects = reusedProjects ?: request("projects.list", epoch).array("items")
            checkEpoch(epoch)
            cachedProjects = host.routeId to projects
            val project = projects.find { it.text("id") == selection.projectId }
            val parent = RemoteSelection(host.routeId, project?.text("id"))
            if (project == null) {
                select(parent, host, projects)
                refreshProjectChats(host.routeId, projects, epoch)
                outcome = "parent"
                return parent
            }
            val cached =
                sessionCache[host.routeId to project.text("id")]
                    ?.takeIf { useCache && !force && alreadyConnected }
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
            sessionsCached = !sessionsFetchedNow
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
                outcome = "parent"
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
                        outcome = "parent"
                        return parent
                    }
            val canonical =
                RemoteSelection(host.routeId, canonicalProject.text("id"), chosen.text("id"))
            chatCached = useCache && chatCache[canonical] != null
            select(
                canonical,
                host,
                projects,
                canonicalProject,
                sessions,
                chosen,
                chatCache[canonical]?.takeIf { useCache },
                sessionsFresh = sessionsFetchedNow,
            )
            try {
                snapshot(epoch)
            } catch (e: CancellationException) {
                throw e
            } catch (swallowed: Exception) {
                Diagnostics.caught("repo.activate.2", swallowed)
                checkEpoch(epoch)
                // The reconnect after a lost connection opens the chat again.
                if (chatCache[canonical] != null || lostWithConnection(swallowed)) {
                    reportReadError(swallowed)
                    outcome = "snapshot_failed"
                    return canonical
                }
                cachedChat = null
                select(parent, host, projects, project, sessions, sessionsFresh = sessionsFetchedNow)
                reportError(R.string.remote_request_error)
                outcome = "snapshot_failed"
                return parent
            }
            refreshPanes()
            refreshConfiguration()
            refreshAdvisor()
            refreshCommands()
            outcome = "ok"
            return canonical
        } catch (e: CancellationException) {
            outcome = "cancelled"
            throw e
        } catch (e: Exception) {
            Diagnostics.caught("repo.activate", e)
            checkEpoch(epoch)
            // A lost connection already set its own, more specific error, or reconnects quietly.
            if (!connectionDown()) reportError(R.string.remote_request_error)
            return if (mode == ActivationMode.USER_OPEN) previousSelection else state.value.selection
        } finally {
            Diagnostics.endAsync("PocketPi.activate", cookie)
            fun hit(cached: Boolean) = if (cached) "hit" else "miss"
            Diagnostics.record(
                Diagnostics.Kind.ACTIVATE,
                "activate." + mode.name.lowercase() + if (force) ".force" else "",
                Diagnostics.now() - started,
                outcome = "$outcome:projects.${hit(projectsCached)}:sessions.${hit(sessionsCached)}:chat.${hit(chatCached)}",
            )
            if (epoch == selectionEpoch) {
                activationStartedAt = null
                // A cached chat whose snapshot never started (the activation failed before it)
                // asks for one itself when its events need it.
                timeline?.takeIf { snapshotPending === it }?.let { pending ->
                    snapshotPending = null
                    if (pending.needsSnapshot && state.value.connected) snapshotAsync()
                }
                update { it.copy(loading = false) }
                if (outcome == "ok" || outcome == "parent" || outcome == "no_host") staleReplaced()
                cacheCurrentScreen()
                // A foreground or network signal that came in while this loaded runs now.
                resumeDeferredRecovery()
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
            // Only the project's list misses the new chat; other lists and chats stay cached.
            sessionCache.remove(routeId to projectId)
            sessionCache.remove(routeId to session.text("projectId"))
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
        } catch (swallowed: Exception) {
            Diagnostics.caught("repo.createSession", swallowed)
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
    ): RemoteSelection =
        forkFrom(
            source,
            SESSION_FORK_CAPABILITY,
            "session.fork",
            { sourceId -> arrayOf("sessionId" to sourceId, "messageId" to messageId, "mode" to mode.wire) },
            setOf(mode.wire),
            { forkError(it.hostCode) },
        )

    override suspend fun forkSessionAtNode(source: RemoteSelection, nodeId: String): RemoteSelection =
        if (
            SESSION_TREE_CAPABILITY !in state.value.capabilities ||
                SESSION_TREE_CAPABILITY in state.value.unavailableCapabilities ||
                !opaqueId(nodeId)
        )
            source
        else
            forkFrom(
                source,
                SESSION_FORK_CAPABILITY,
                "session.tree.fork",
                { sourceId -> treeForkFields(sourceId, nodeId) },
                setOf("edit", "at"),
                ::treeForkError,
            )

    /**
     * Forks [source] with the request [type] and switches to the fork. [modes] are the result modes
     * the request may produce: `edit` puts the host's text into the new chat's composer, `retry`
     * reports its resend, `at` opens the fork as it is.
     */
    private suspend fun forkFrom(
        source: RemoteSelection,
        capability: String,
        type: String,
        fields: (sourceId: String) -> Array<Pair<String, Any?>>,
        modes: Set<String>,
        errorText: (Exception) -> Int,
    ): RemoteSelection {
        val routeId = source.routeId ?: return source
        val projectId = source.projectId ?: return source
        val sourceId = source.sessionId ?: return source
        val current = state.value
        // Older hosts close the connection on unknown commands. A quiet reconnect keeps the
        // capabilities on screen, and cancelling the selection would stop its activation.
        if (
            !current.connected ||
                capability !in current.capabilities ||
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
            val result = validatedFork(request(type, epoch, *fields(sourceId)), sourceId, projectId, modes)
            val session = result.session
            val project =
                state.value.project?.takeIf { it.optionalText("id") == projectId }
                    ?: state.value.projects.find { it.optionalText("id") == projectId }
            checkNotNull(project)
            // Only the project's list misses the fork; other lists and chats stay cached.
            sessionCache.remove(routeId to projectId)
            cachedSessions = null
            cachedChat = null
            val canonical = RemoteSelection(routeId, projectId, session.text("id"))
            var notice: Int? = null
            when (result.mode) {
                "edit" -> {
                    val draft = forkDraft(checkNotNull(result.text))
                    drafts[DraftKey(routeId, session.text("id"))] =
                        StoredDraft(text = draft.text, quote = draft.quote)
                    persist()
                    if (draft.droppedAttachments) notice = R.string.remote_fork_attachments_dropped
                }
                "retry" -> notice = forkResendNotice(result.resend)
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
            Diagnostics.caught("repo.forkFrom", e)
            failure = errorText(e)
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
        } catch (swallowed: Exception) {
            Diagnostics.caught("repo.openNotification", swallowed)
            if (epoch == selectionEpoch) reportError(R.string.remote_request_error)
        }
        if (resolved != null) return activate(resolved, ActivationMode.USER_OPEN)
        if (epoch == selectionEpoch && previous.host != null && previous.host.routeId != routeId) {
            try {
                connect(previous.host, epoch)
            } catch (swallowed: Exception) {
                Diagnostics.caught("repo.openNotification.2", swallowed)
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
            } catch (swallowed: Exception) {
                Diagnostics.caught("repo.pair", swallowed)
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
        val deferred = CompletableDeferred<PairedHost>()
        pairing = deferred
        pairingEpoch = epoch
        if (markPendingFollowUpsUncertain(activeHost?.routeId)) persist().await()
        // cancelPairing() or a newer pair() during the write gave this pairing up already.
        if (!deferred.isActive || pairing !== deferred) return null
        activeHost = null
        timedOutRoute = null
        cancelQuietReconnect()
        update {
            it.copy(loading = true, connected = false, connection = R.string.remote_connecting, pairingDeadline = null)
        }
        try {
            // The transport's own pairing timer starts with the attempt, and so does the deadline.
            pairingStartedAt = Diagnostics.now()
            transport.pair(value)
            val host =
                withTimeoutOrNull(PAIRING_TIMEOUT_MILLIS) { deferred.await() }
                    ?: run {
                        // The code expired unapproved; this is no network problem. The transport's
                        // own timer usually reports this first, through failed().
                        deferred.cancel()
                        if (pairing === deferred) transport.close()
                        update { it.copy(connection = R.string.remote_offline, error = R.string.remote_pairing_timeout) }
                        return null
                    }
            checkEpoch(epoch)
            update { it.copy(loading = false) }
            return activate(RemoteSelection(host.routeId))
        } catch (e: CancellationException) {
            // cancelPairing() or a new selection gave the pairing up: it ends without a host.
            if (deferred.isCancelled && currentCoroutineContext().isActive) return null
            throw e
        } catch (swallowed: Exception) {
            Diagnostics.caught("repo.pair.2", swallowed)
            if (state.value.error == null) reportError(R.string.remote_connection_error)
            return null
        } finally {
            if (pairing === deferred) {
                pairingStartedAt = null
                update { it.copy(pairingDeadline = null) }
            }
            if (epoch == selectionEpoch) update { it.copy(loading = false) }
        }
    }

    override fun cancelPairing() {
        val deferred = pairing?.takeIf { it.isActive } ?: return
        Diagnostics.record(Diagnostics.Kind.FAILURE, "pairing", outcome = "cancelled")
        deferred.cancel()
        transport.close()
        pairingStartedAt = null
        update {
            it.copy(loading = false, connection = R.string.remote_offline, pairingDeadline = null)
        }
    }

    override fun disconnect() {
        reconnectEnabled = false
        offlineSince = null
        cancelHostWaitHint()
        cancelQuietReconnect()
        cancelSelection()
        clearNavigationCache()
        projectImageBytes.clear()
        projectArtifacts.clear()
        ProjectImageBitmaps.clear()
        ArtifactThumbnails.clear()
        retry?.cancel()
        connectionEnded()
        generation++
        transport.close()
        ready?.cancel()
        pairing?.cancel()
        if (markPendingFollowUpsUncertain(activeHost?.routeId)) persist()
        activeHost = null
        timedOutRoute = null
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
        if (timedOutRoute == routeId) timedOutRoute = null
        attachmentBytes.clear()
        SentImageThumbnails.clear()
        projectImageBytes.clear()
        projectArtifacts.clear()
        ProjectImageBitmaps.clear()
        ArtifactThumbnails.clear()
        removedRoutes += routeId
        parkedFiles.keys.removeAll { it.routeId == routeId }
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
                    } catch (swallowed: Exception) {
                        Diagnostics.caught("repo.remove", swallowed)
                        reportError(R.string.remote_storage_error)
                    }
                    if (activeHost?.routeId == routeId) disconnect()
                    update { it.copy(hosts = remaining) }
                }
            } catch (swallowed: Exception) {
                Diagnostics.caught("repo.remove.2", swallowed)
                reportError(R.string.remote_storage_error)
            } finally {
                if (removalJobs[routeId] === coroutineContext[Job]) removalJobs.remove(routeId)
            }
        }
        removalJobs[routeId] = removal
        removal.start()
    }

    /**
     * Ends the current connection. [broken]: an unreadable frame ended it, see [brokenConnections].
     * [cause]: a dead socket or a replaced network reconnects at once.
     *
     * A transient failure ([R.string.remote_connection_error] with [reconnect]) keeps the
     * last-known state of the Mac on screen as [RemoteState.stale]. The first one of a streak
     * reconnects quietly: no error, and the error card follows only if the Mac is still unreachable
     * [QUIET_RECONNECT_MILLIS] later or the next attempt fails too.
     */
    private fun failed(
        reconnect: Boolean,
        error: Int,
        broken: Boolean = false,
        cause: FailureCause = FailureCause.LOST,
    ) {
        Diagnostics.record(
            Diagnostics.Kind.FAILURE,
            "connection." + if (state.value.connected) "lost" else "attempt",
            outcome = errorName(error) + "." + cause.name.lowercase() + if (reconnect) ".retry" else ".stop",
        )
        if (offlineSince == null && activeHost != null) offlineSince = Diagnostics.now()
        // A connection that came up and failed before proving itself counts like a failed attempt;
        // one that lived longer ends a run of short-lived ones.
        val hadConnection = readyAt != null
        val shortLived = connectionEnded()
        if (shortLived) shortLivedConnections++ else if (hadConnection) shortLivedConnections = 0
        if (broken) {
            brokenConnections++
            // Each broken connection waits longer than the one before, even after a reply reset
            // the backoff, so a host that breaks every connection after its first answer backs off.
            reconnectAttempts = maxOf(reconnectAttempts, brokenConnections - 1)
        }
        cancelHostWaitHint()
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
        invalidateRequests(RemoteRequestException.connectionLost())
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
        val host = activeHost
        if (error == R.string.remote_denied || error in DENIED_DEVICE_ERRORS) reconnectEnabled = false
        val restart = serviceRestart
        serviceRestart = false
        val transient = reconnect && error == R.string.remote_connection_error
        // The network went away in the foreground: the reconnect waits for its return, which says
        // nothing about the Mac, so it neither counts as a failure nor raises the card.
        val waitingForNetwork = transient && reconnectEnabled && host != null && recovery.waitingForNetwork
        if (transient && !waitingForNetwork) failureStreak++
        val retrying = reconnect && reconnectEnabled && recovery.canRecover && host != null
        // The Mac accepts connections and drops them at once, or breaks each one with a frame
        // this app cannot read: stop retrying on a timer. Retry, the next foreground and a
        // network change still try again (they reset the counts).
        val stopped =
            retrying &&
                (shortLivedConnections >= MAX_SHORT_LIVED_CONNECTIONS ||
                    brokenConnections >= MAX_BROKEN_CONNECTIONS)
        val quiet = transient && failureStreak < 2 && !stopped
        // What the Mac on screen last reported stays while it reconnects; anything else goes.
        val keep = transient && showsRoute(host?.routeId)
        val shownError =
            when {
                waitingForNetwork -> null
                stopped || (transient && failureStreak >= UNREACHABLE_AFTER_FAILURES) -> R.string.remote_unreachable
                quiet -> null
                else -> error
            }
        update {
            val shown =
                if (keep) it.copy(stale = true, staleSince = it.staleSince ?: now())
                else lastKnownCleared(it)
            shown.copy(
                connected = false,
                compaction = null,
                compactionRequesting = false,
                // A finished download stays readable; a running one stopped above.
                toolOutput = it.toolOutput?.takeIf { output -> keep && output.text != null },
                projectChats = it.projectChats.map { chat -> chat.copy(verified = false) },
                // A verdict on the Mac's version lasts only for the connection that gave it.
                childControlUnsupported = emptySet(),
                followUps = currentDraftKey()?.let { key -> drafts[key]?.followUps }.orEmpty(),
                connection =
                    when {
                        waitingForNetwork -> R.string.remote_waiting_for_network
                        quiet && retrying -> R.string.remote_reconnecting
                        else -> R.string.remote_offline
                    },
                error = shownError,
                loading = false,
                pairingDeadline = null,
                sending = false,
                answering = emptySet(),
                advisorLoading = false,
                advisorChanging = false,
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
                // The host this denial belongs to, for the error card's actions.
                deniedRouteId = if (error in DENIED_DEVICE_ERRORS) host?.routeId else null,
            )
        }
        if (quiet && !waitingForNetwork) startQuietReconnect() else cancelQuietReconnect()
        if (stopped) {
            Diagnostics.record(
                Diagnostics.Kind.RECOVERY,
                "reconnect.stopped",
                outcome =
                    if (brokenConnections >= MAX_BROKEN_CONNECTIONS) "broken.$brokenConnections"
                    else "short_lived.$shortLivedConnections",
            )
            retry?.cancel()
            retry = null
            update { it.copy(reconnectAt = null) }
        } else if (retrying) {
            val generationAtFailure = generation
            // The relay announced a restart, so one retry per failure streak does not wait.
            val restartRetry = restart && !restartRetryUsed && !broken
            if (restartRetry) {
                restartRetryUsed = true
                reconnectAttempts = 0
            }
            // A dead socket or a replaced network says nothing about the Mac: try again at once.
            val immediate =
                restartRetry || cause == FailureCause.STALE || cause == FailureCause.NETWORK_REPLACED
            val wait = reconnectDelay(reconnectAttempts++, immediate)
            Diagnostics.record(Diagnostics.Kind.RECOVERY, "reconnect.backoff", wait, outcome = "attempt.$reconnectAttempts")
            retry?.cancel()
            update { it.copy(reconnectAt = now() + wait) }
            retry = scope.launch {
                delay(wait)
                if (generationAtFailure == generation && recovery.canRecover && reconnectEnabled) {
                    val selection = state.value.selection
                    retry = null
                    update { it.copy(reconnectAt = null) }
                    activate(selection, ActivationMode.RESTORE)
                }
            }
        } else update { it.copy(reconnectAt = null) }
    }

    /** A liveness read got no reply and no frame arrived meanwhile: reconnect at once, quietly. */
    private fun connectionStale(type: String) {
        Diagnostics.record(Diagnostics.Kind.FAILURE, "connection.stale", outcome = type)
        failed(true, R.string.remote_connection_error, cause = FailureCause.STALE)
    }

    /** Raises the error card if the quiet reconnect that starts now has not succeeded in time. */
    private fun startQuietReconnect() {
        if (quietReconnect?.isActive == true) return
        quietReconnect = scope.launch {
            delay(QUIET_RECONNECT_MILLIS)
            quietReconnect = null
            if (!state.value.connected && state.value.error == null && reconnectEnabled && !recovery.waitingForNetwork) {
                Diagnostics.record(Diagnostics.Kind.RECOVERY, "reconnect.slow", QUIET_RECONNECT_MILLIS)
                update { it.copy(error = R.string.remote_connection_error) }
            }
        }
    }

    private fun cancelQuietReconnect() {
        quietReconnect?.cancel()
        quietReconnect = null
    }

    /**
     * The activation after a reconnect has read the chat and lists again, so what is on screen is
     * no longer only last-known; the reads it started refresh the rest in place.
     */
    private fun staleReplaced() {
        val since = state.value.staleSince ?: return
        Diagnostics.record(Diagnostics.Kind.OFFLINE, "stale", (now() - since).coerceAtLeast(0))
        update { it.copy(stale = false, staleSince = null) }
    }

    /** A failure's message resource as a word for Diagnostics, never the text itself. */
    private fun errorName(error: Int): String =
        when (error) {
            R.string.remote_connection_error -> "connection_error"
            R.string.remote_unreachable -> "unreachable"
            R.string.remote_device_revoked -> "revoked"
            R.string.remote_device_paused -> "paused"
            R.string.remote_denied -> "denied"
            R.string.remote_storage_error -> "storage_error"
            R.string.remote_pairing_timeout -> "pairing_timeout"
            else -> "other"
        }

    private fun reconnectDelay(attempt: Int, immediate: Boolean): Long {
        if (immediate) return 0
        val base = RECONNECT_BASE_MILLIS shl attempt.coerceAtMost(6)
        val unit = jitter().coerceIn(0.0, 1.0)
        // At the cap the delay only spreads downward, so it never exceeds the cap.
        val spread =
            if (base >= RECONNECT_MAX_MILLIS) 1.0 - RECONNECT_JITTER * unit
            else 1.0 + RECONNECT_JITTER * (2 * unit - 1)
        return (base.coerceAtMost(RECONNECT_MAX_MILLIS) * spread).toLong()
    }

    private fun resetReconnect() {
        resetBackoff()
        brokenConnections = 0
    }

    /** Resets the backoff but keeps the count of [brokenConnections]. */
    private fun resetBackoff() {
        reconnectAttempts = 0
        failureStreak = 0
        restartRetryUsed = false
        shortLivedConnections = 0
    }

    /** The transport is ready: the connection proves itself by a reply or by staying up. */
    private fun connectionReady() {
        readyAt = now()
        connectionProven = false
        provenTimer?.cancel()
        val connection = generation
        provenTimer = scope.launch {
            delay(CONNECTION_PROVEN_MILLIS)
            if (connection == generation && state.value.connected) {
                // Staying up also ends a run of connections broken by unreadable frames.
                brokenConnections = 0
                connectionProven()
            }
        }
    }

    /**
     * The current connection works, so the next failure starts the backoff from the beginning,
     * unless an unreadable frame ends it again (see [brokenConnections]).
     */
    private fun connectionProven() {
        if (connectionProven || readyAt == null) return
        connectionProven = true
        // The timer keeps running: only staying up resets [brokenConnections].
        resetBackoff()
    }

    /**
     * Ends the bookkeeping of the current connection. Returns whether it was short-lived: ready,
     * never proven, and lost within [SHORT_LIVED_CONNECTION_MILLIS].
     */
    private fun connectionEnded(): Boolean {
        val lived = readyAt?.let { now() - it }
        val shortLived = lived != null && !connectionProven && lived < SHORT_LIVED_CONNECTION_MILLIS
        readyAt = null
        connectionProven = false
        provenTimer?.cancel()
        provenTimer = null
        return shortLived
    }

    private fun stopReconnectTimer() {
        retry?.cancel()
        retry = null
        if (state.value.reconnectAt != null) update { it.copy(reconnectAt = null) }
    }

    override fun refresh() {
        reconnectEnabled = true
        // Retrying a denied device reopens what was being opened, which may not be selected yet.
        val selection =
            if (state.value.deniedRouteId != null) requestedSelection ?: state.value.selection
            else state.value.selection
        // A connected chat reads itself again in place; other caches stay valid.
        if (selection.sessionId != null && state.value.connected) {
            snapshotAsync()
            refreshConfiguration()
            refreshAdvisor()
            refreshCommands()
            return
        }
        clearNavigationCache()
        resetReconnect()
        stopReconnectTimer()
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
            } catch (swallowed: Exception) {
                Diagnostics.caught("repo.renameSession", swallowed)
                if (epoch == selectionEpoch) reportError(R.string.remote_request_error)
            } finally {
                renamingIds.remove(sessionId)
            }
        }
    }

    override fun setForeground(foreground: Boolean) {
        val signal = recovery.foreground(foreground)
        if (!foreground) {
            jobsController.setForeground(false)
            backgroundedAt = now()
            backgrounds++
            selectionAtBackground = state.value.selection
            queueNavigationWrite(immediate = true)
            stopReconnectTimer()
            cancelRecovery()
            return
        }
        // How long the app was away picks the resume tier, also for a resume that waits for a
        // deferred signal or for the network, which Android may unblock only after this return.
        val away = backgroundedAt?.let { (now() - it).coerceAtLeast(0) }
        resumeAway = away
        if (signal != null) {
            away?.let { Diagnostics.record(Diagnostics.Kind.RECOVERY, "foreground", it) }
            resume(signal)
        }
        // After the tier: a reconnect it starts lists the jobs once ready, so this one does not.
        jobsController.setForeground(true)
    }

    /** Acts on [signal]; [pathMoved]: the network in use only moved its route. */
    private fun resume(signal: RemoteRecovery.Signal, pathMoved: Boolean = false) {
        onRecoverySignal(signal)
        // A moved route says nothing about the Mac, so a backoff under way keeps its count.
        if (!pathMoved) resetReconnect()
        if (
            signal == RemoteRecovery.Signal.FRESH_FOREGROUND &&
                (resumeAway ?: 0) > RESUME_QUIET_AWAY_MILLIS &&
                abandonActivationFromBefore()
        )
            return
        recover(signal)
    }

    /**
     * An activation that started before the app went to the background and still runs while the
     * socket has been quiet is likely stuck on a socket that died meanwhile: the connection ends
     * as stale, so the activation's requests fail and it returns through its usual error path (a
     * user's open with the selection from before it), and the reconnect restores the selection.
     * Returns whether it did; on a socket that still delivers frames the activation runs on, and
     * the resume waits for it.
     */
    private fun abandonActivationFromBefore(): Boolean {
        val started = activationStartedAt ?: return false
        if (!state.value.loading || activationBackgrounds == backgrounds || activeHost == null) return false
        if (pairing?.isActive == true || hasPendingRecoveryWork()) return false
        val quiet = lastInboundAt?.let { (now() - it).coerceAtLeast(0) } ?: Long.MAX_VALUE
        if (state.value.connected && quiet < RESUME_LIVE_SOCKET_MILLIS) return false
        Diagnostics.record(Diagnostics.Kind.RECOVERY, "resume.abandon", (now() - started).coerceAtLeast(0))
        failed(true, R.string.remote_connection_error, cause = FailureCause.STALE)
        return true
    }

    /**
     * Whether a recovery is about to activate the selection again, which lists the jobs anew: a
     * reconnect waiting to run, or a recovery job that activates rather than probes.
     */
    private fun recoveryStarting(): Boolean =
        retry?.isActive == true || (recoveryJob?.isActive == true && recoveryActivates)

    override fun setNetwork(identity: String?, validated: Boolean, path: String?) {
        val wasValidated = recovery.validated
        val wasWaiting = recovery.waitingForNetwork
        val signal = recovery.network(identity, validated, path)
        if (identity == null) {
            stopReconnectTimer()
            cancelRecovery()
            waitForNetwork()
            return
        }
        if (signal == null) {
            // Validation of the network in use: a reconnect that waits for its timer goes now.
            if (validated && !wasValidated && !state.value.connected) {
                Diagnostics.record(Diagnostics.Kind.RECOVERY, "network.validated")
                resetReconnect()
                recover(RemoteRecovery.Signal.FRESH_NETWORK)
            }
            return
        }
        // A revalidation on a network that never went away is a moved route: the socket is worth
        // a probe, but a reconnect that waits for its backoff keeps waiting.
        val pathMoved = signal == RemoteRecovery.Signal.NETWORK_REVALIDATED && !wasWaiting
        if (pathMoved && !state.value.connected) {
            Diagnostics.record(Diagnostics.Kind.RECOVERY, "recover.network_revalidated", outcome = "backoff")
            return
        }
        resume(signal, pathMoved)
    }

    /**
     * Offline in the foreground with no network: the connection line waits for one instead of
     * showing the error card and a countdown, and the network's return reconnects at once.
     */
    private fun waitForNetwork() {
        val current = state.value
        if (!recovery.waitingForNetwork || current.connected || !reconnectEnabled) return
        if (current.selection.routeId == null || current.loading) return
        if (current.error != null && current.error !in NETWORK_WAIT_ERRORS) return
        cancelQuietReconnect()
        update { it.copy(connection = R.string.remote_waiting_for_network, error = null, reconnectAt = null) }
    }

    private enum class ResumeTier {
        /** Back soon with events flowing: nothing to do. */
        NONE,
        /** A short probe of the socket and the lists read in place, without a reload. */
        PROBE,
        /** A quiet reconnect at once, with what is on screen kept until it is read again. */
        RECONNECT,
    }

    /**
     * The resume tier for [signal]: how long the app was away and how long the socket has been
     * quiet decide between nothing, a probe and a reconnect. A socket that a foreground service
     * kept delivering frames is probed, however long the app was away.
     */
    private fun resumeTier(signal: RemoteRecovery.Signal, selection: RemoteSelection): ResumeTier {
        val connected = state.value.connected && activeHost?.routeId == selection.routeId
        if (!connected) return ResumeTier.RECONNECT
        val quiet = lastInboundAt?.let { (now() - it).coerceAtLeast(0) } ?: Long.MAX_VALUE
        val alive = quiet < RESUME_LIVE_SOCKET_MILLIS
        // A network signal says nothing about the time away; it is the socket that needs a probe.
        if (signal != RemoteRecovery.Signal.FRESH_FOREGROUND) return ResumeTier.PROBE
        val away = resumeAway ?: Long.MAX_VALUE
        return when {
            alive && away < RESUME_QUIET_AWAY_MILLIS && selection == selectionAtBackground -> ResumeTier.NONE
            alive || away < RESUME_PROBE_AWAY_MILLIS -> ResumeTier.PROBE
            else -> ResumeTier.RECONNECT
        }
    }

    private fun recover(signal: RemoteRecovery.Signal) {
        fun note(outcome: String) =
            Diagnostics.record(Diagnostics.Kind.RECOVERY, "recover." + signal.name.lowercase(), outcome = outcome)
        if (!reconnectEnabled || !recovery.canRecover) return note("disabled")
        if (pairing?.isActive == true) return note("busy")
        if (signal == RemoteRecovery.Signal.NETWORK_REPLACED && activeHost != null) {
            // Also ends an attempt under way, which would wait out its timeout on the old route.
            note("reconnect")
            failed(true, R.string.remote_connection_error, cause = FailureCause.NETWORK_REPLACED)
            return
        }
        if (state.value.loading || hasPendingRecoveryWork() || recoveryJob?.isActive == true) {
            // Runs once the activation, the pending work or the running recovery ends.
            deferredRecovery = signal
            deferredRecoveryReady = readyConnections
            return note("deferred")
        }
        val selection = state.value.selection
        if (selection.routeId == null || state.value.hosts.none { it.routeId == selection.routeId }) return note("no_host")
        val tier = resumeTier(signal, selection)
        note(tier.name.lowercase())
        when (tier) {
            ResumeTier.NONE -> Unit
            ResumeTier.PROBE -> {
                val epoch = selectionEpoch
                startRecovery(activates = false) { revalidate(selection, epoch) }
            }
            ResumeTier.RECONNECT -> {
                stopReconnectTimer()
                if (state.value.connected && activeHost != null) {
                    // Ends the socket and retries at once; the retry restores the selection while
                    // the last-known state stays on screen.
                    failed(true, R.string.remote_connection_error, cause = FailureCause.STALE)
                } else {
                    // Already offline, with no attempt under way (an activation defers this):
                    // reconnect now instead of failing a connection that is already gone.
                    val epoch = selectionEpoch
                    startRecovery(activates = true) {
                        if (epoch == selectionEpoch && recovery.canRecover && reconnectEnabled)
                            activate(selection, ActivationMode.RESTORE, force = true)
                    }
                }
            }
        }
    }

    /** Runs [block] as the recovery job; a signal deferred meanwhile runs once it ends. */
    private fun startRecovery(activates: Boolean, block: suspend () -> Unit) {
        val epoch = selectionEpoch
        recoveryActivates = activates
        recoveryJob =
            scope.launch {
                try {
                    block()
                } catch (_: CancellationException) {
                    return@launch
                } catch (swallowed: Exception) {
                    Diagnostics.caught("repo.recover", swallowed)
                    if (epoch == selectionEpoch) reportError(R.string.remote_request_error)
                }
                if (recoveryJob === coroutineContext[Job]) recoveryJob = null
                resumeDeferredRecovery()
            }
    }

    /**
     * Tier 1: probes the socket with a short read instead of reconnecting. In a chat the probe is
     * a snapshot into the Timeline on screen, elsewhere the project list. The lists are then read
     * again and applied in place; only a selection they no longer hold is selected anew, on its
     * parent. Configuration, advisor and commands are read again only if the chat moved on.
     */
    private suspend fun revalidate(selection: RemoteSelection, epoch: Long) {
        val host = activeHost?.takeIf { it.routeId == selection.routeId } ?: return
        val routeId = host.routeId
        val chat = timeline?.takeIf { selection.sessionId != null && it.sessionId == selection.sessionId }
        val before = chat?.let { it.revision to it.status }
        val hint =
            scope.launch {
                delay(RESUME_PROBE_HINT_MILLIS)
                if (epoch == selectionEpoch && state.value.connected && state.value.connection == R.string.remote_connected)
                    update { it.copy(connection = R.string.remote_checking_connection) }
            }
        val started = Diagnostics.now()
        var projects: List<JsonObject>? = null
        try {
            if (chat != null) snapshot(epoch, timeoutMillis = RESUME_PROBE_TIMEOUT_MILLIS)
            else projects = request("projects.list", epoch, timeoutMillis = RESUME_PROBE_TIMEOUT_MILLIS).array("items")
            Diagnostics.record(Diagnostics.Kind.RECOVERY, "resume.probe", Diagnostics.now() - started, outcome = "ok")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.record(Diagnostics.Kind.RECOVERY, "resume.probe", Diagnostics.now() - started, outcome = requestOutcome(e))
            // A probe that got no reply and saw no frame meanwhile already reconnects quietly; one
            // behind a slow host leaves the socket alone. A refusal still lets the lists tell why.
            if (!state.value.connected || e.requestKind == RemoteRequestException.Kind.TIMEOUT) return
        } finally {
            hint.cancel()
            if (state.value.connection == R.string.remote_checking_connection && state.value.connected)
                update { it.copy(connection = R.string.remote_connected) }
        }
        if (chat != null && chat === timeline && before != (chat.revision to chat.status)) {
            refreshConfiguration()
            refreshAdvisor()
            refreshCommands()
        }
        try {
            val fresh = projects ?: request("projects.list", epoch).array("items")
            checkEpoch(epoch)
            if (!state.value.connected || activeHost?.routeId != routeId || state.value.selection != selection) return
            cachedProjects = routeId to fresh
            val projectId = selection.projectId
            val project = projectId?.let { id -> fresh.find { it.text("id") == id } }
            if (project == null) {
                if (projectId == null) update { it.copy(projects = fresh) }
                else select(RemoteSelection(routeId), host, fresh)
                refreshProjectChats(routeId, fresh, epoch)
                return
            }
            val sessions = listSessions(project.text("id"), epoch)
            checkEpoch(epoch)
            if (!state.value.connected || activeHost?.routeId != routeId || state.value.selection != selection) return
            cachedSessions = CachedSessions(routeId, project.text("id"), project, sessions)
            val sessionId = selection.sessionId
            val session = sessionId?.let { id -> sessions.find { it.text("id") == id } }
            if (
                sessionId != null &&
                    (session == null ||
                        session.optionalText("origin") !in setOf("rpc", "tui") ||
                        session.text("status") == "offline")
            ) {
                // Gone, or no longer live: the same parent a restore lands on.
                select(RemoteSelection(routeId, project.text("id")), host, fresh, project, sessions, sessionsFresh = true)
                return
            }
            update {
                it.copy(
                    projects = fresh,
                    project = project,
                    sessions = sessions,
                    sessionsFresh = true,
                    session = session?.let(::withSessionTitle) ?: it.session,
                )
            }
            cacheCurrentScreen()
        } catch (e: CancellationException) {
            throw e
        } catch (swallowed: Exception) {
            // The chat is live again; lists that failed to load stay as they were.
            Diagnostics.caught("repo.revalidate", swallowed)
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
                Diagnostics.caught("repo.refreshConfiguration", e)
                if (epoch == selectionEpoch && version == configurationVersion) {
                    if (e.isUnsupported)
                        update {
                            it.copy(
                                configuration = null,
                                unavailableCapabilities =
                                    it.unavailableCapabilities + CONFIGURATION_CAPABILITY,
                            )
                        }
                    else reportReadError(e, R.string.remote_configuration_error)
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
                Diagnostics.caught("repo.refreshContextUsage", e)
                if (epoch == selectionEpoch && version == contextVersion) {
                    if (e.isUnsupported)
                        update { it.copy(
                            contextUsage = null,
                            unavailableCapabilities = it.unavailableCapabilities + CONTEXT_CAPABILITY,
                        ) }
                    // A reply lost with the connection keeps the last-known usage for the reconnect.
                    else if (!lostWithConnection(e)) update { it.copy(contextUsage = null) }
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
                Diagnostics.caught("repo.compactContext", e)
                if (epoch == selectionEpoch) {
                    if (e.isUnsupported)
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
                Diagnostics.caught("repo.changeConfiguration", e)
                failed = true
                if (epoch == selectionEpoch && version == configurationVersion) {
                    configurationNeedsRefresh = true
                    if (e.isUnsupported && rereadOnError) {
                        // Only the settings change is unsupported; model and thinking still work.
                        update { it.copy(unavailableCapabilities = it.unavailableCapabilities + SETTINGS_CAPABILITY) }
                        reportError(R.string.remote_configuration_error)
                    } else if (e.isUnsupported)
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
                Diagnostics.caught("repo.refreshAdvisor", e)
                if (epoch == selectionEpoch && version == advisorVersion) {
                    if (e.isUnsupported)
                        update { it.copy(advisor = null,
                            unavailableCapabilities = it.unavailableCapabilities + ADVISOR_CAPABILITY) }
                    else reportReadError(e)
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
            } catch (_: CancellationException) {} catch (swallowed: Exception) {
                Diagnostics.caught("repo.setAdvisor", swallowed)
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
                Diagnostics.caught("repo.refreshCommands", e)
                if (epoch == selectionEpoch && version == commandsVersion) {
                    if (e.isUnsupported)
                        update {
                            it.copy(
                                commands = emptyList(),
                                unavailableCapabilities =
                                    it.unavailableCapabilities + COMMANDS_CAPABILITY,
                            )
                        }
                    else reportReadError(e, R.string.remote_commands_error)
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

    /**
     * Shows an extension's notice for the selected session once: a replayed event, at or below the
     * timeline's revision, is ignored. It runs before the timeline applies the event, and a notice
     * after a revision gap shows at once, while the timeline waits for the events before it.
     */
    private fun sessionNotice(payload: JsonObject) {
        val current = timeline ?: return
        if (payload.text("sessionId") != current.sessionId || payload.long("revision") <= current.revision) return
        if (current.sessionId != state.value.selection.sessionId) return
        val notice = sessionNotice(payload, ++noticeCount) ?: return
        update { it.copy(notice = notice) }
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

    override fun draft(selection: RemoteSelection, text: String) {
        val routeId = selection.routeId ?: return
        val key = DraftKey(routeId, selection.sessionId ?: return)
        if (key == currentDraftKey()) return draft(text)
        // A composer's flush on the way out of a chat, after the selection moved on: the text
        // goes to that chat's stored draft, which it shows when it opens again.
        if (!draftsAvailable || routeId in removedRoutes || state.value.hosts.none { it.routeId == routeId }) return
        if (text.toByteArray().size > 128 * 1024) return
        val stored = drafts[key] ?: StoredDraft()
        if (stored.text == text) return
        drafts[key] = stored.copy(text = text)
        persist()
    }

    override fun quote(messageId: String?) {
        // Like the draft, a quote is local to the chat's stored draft, so it needs no loaded chat.
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
                            Diagnostics.caught("repo.importAttachments", e)
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
            } catch (_: CancellationException) {} catch (swallowed: Exception) {
                Diagnostics.caught("repo.importAttachments.2", swallowed)
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
            } catch (_: CancellationException) {} catch (swallowed: Exception) { Diagnostics.caught("repo.removeAttachment", swallowed) } finally {
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
                } catch (swallowed: Exception) { Diagnostics.caught("repo.cancelAttachmentWork", swallowed) }
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
            Diagnostics.caught("repo.cancelUpload", e)
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
            val remote = if (
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
            // The upload already read and verified these exact bytes. Keep them under the
            // server ID before the prompt can appear in the conversation; draft cleanup may
            // delete the local file as soon as sending succeeds.
            if (remote.kind == "image") {
                attachmentBytes[AttachmentByteCache.Key(key.sessionId, remote.id, remote.sha256)] = bytes
            }
            remote
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
            } catch (swallowed: IllegalArgumentException) {
                Diagnostics.caught("repo.sendPrompt", swallowed)
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
                        val receipt = earlyFollowUpReceipts.remove(Triple(key, id, delivery))
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
                Diagnostics.caught("repo.sendPrompt.2", e)
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
                    val childError =
                        queued && childControlsAvailable(current) && (e.isUnsupported || e.hostCode == "offline")
                    if (childError && e.isUnsupported) markChildControlUnsupported(key.sessionId)
                    reportError(
                        if (childError) childControlError(e)
                        else if (current.attachments.isEmpty()) R.string.remote_request_error
                        else if (
                            e.isUnsupported &&
                                current.attachments.any { it.kind == "image" }
                        )
                            R.string.remote_attachment_model_error
                        else R.string.remote_attachment_send_error
                    )
                    if (current.attachments.isNotEmpty() && e.isUnsupported)
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
            } catch (_: CancellationException) {} catch (swallowed: Exception) {
                Diagnostics.caught("repo.abort", swallowed)
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
            } catch (_: CancellationException) {} catch (swallowed: Exception) {
                Diagnostics.caught("repo.abortSession", swallowed)
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
                    Diagnostics.caught("repo.stopChild", e)
                    when {
                        // A plain abort would stop the run while the parent's manager, which is
                        // offline, may still restart the child: say so and leave the state.
                        e.hostCode == "offline" -> {
                            if (epoch == selectionEpoch) reportError(R.string.remote_child_stop_parent_offline)
                            previous
                        }
                        // An old parent cannot stop the child; the child's own abort still stops
                        // this run, but the parent may restart the subagent.
                        e.isUnsupported -> {
                            markChildControlUnsupported(sessionId)
                            val aborted =
                                try {
                                    request("session.abort", epoch, "sessionId" to sessionId)
                                    true
                                } catch (c: CancellationException) {
                                    setChildControl(sessionId, previous)
                                    throw c
                                } catch (swallowed: Exception) {
                                    Diagnostics.caught("repo.stopChild.2", swallowed)
                                    false
                                }
                            if (epoch == selectionEpoch)
                                reportError(
                                    if (aborted) R.string.remote_child_stopped_run_only
                                    else childControlError(e)
                                )
                            previous
                        }
                        else -> childControlFailure(e, previous, epoch, sent)
                    }
                } catch (e: Exception) {
                    Diagnostics.caught("repo.stopChild.3", e)
                    childControlFailure(e, previous, epoch, sent)
                }
            setChildControl(sessionId, control)
        }
    }

    override fun resumeChild(message: String) {
        val current = state.value
        // Offline, the capabilities kept by a quiet reconnect do not make the resume possible.
        if (current.connected && offersChildResume(current)) {
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
                    Diagnostics.caught("repo.resumeChild", e)
                    if (e.isUnsupported) markChildControlUnsupported(sessionId)
                    childControlFailure(e, previous, epoch, sent)
                }
            setChildControl(sessionId, control)
        }
    }

    private fun markChildControlUnsupported(sessionId: String) = update {
        it.copy(childControlUnsupported = it.childControlUnsupported + sessionId)
    }

    private fun childControlError(e: Exception): Int =
        when {
            e.isUnsupported -> R.string.remote_child_unsupported
            e.hostCode == "offline" -> R.string.remote_child_parent_offline
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
        val code = e.hostCode
        return when {
            code == "internal" || (code == null && sent) -> uncertainChildControl(previous?.agentId)
            code == "not_found" -> ChildControl(ChildControlPhase.NOT_FOUND)
            else -> {
                if (epoch == selectionEpoch) reportError(childControlError(e))
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
            } catch (_: CancellationException) {} catch (swallowed: Exception) {
                Diagnostics.caught("repo.refreshSessions", swallowed)
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
        // A quiet reconnect keeps the capabilities on screen: offline is no missing capability.
        if (sessionId != null && !current.connected) {
            update { it.copy(toolOutput = failure(ToolOutputFailure.OFFLINE)) }
            return
        }
        if (
            sessionId == null ||
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
                Diagnostics.caught("repo.loadToolOutput", e)
                if (!active()) return@launch
                val reason = when {
                    e.isUnsupported -> ToolOutputFailure.UNSUPPORTED
                    e.hostCode == "not_found" -> ToolOutputFailure.NOT_FOUND
                    e.hostCode == "offline" -> ToolOutputFailure.OFFLINE
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
                ?: throw RemoteRequestException.timeout()
        } finally {
            folderCompletions.remove(id)
        }
    }

    private fun failFolderRequests() {
        val pending = folderCompletions.values.toList()
        folderCompletions.clear()
        pending.forEach { it.completeExceptionally(RemoteRequestException.connectionLost()) }
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
                Diagnostics.caught("repo.browseFolder", e)
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
                Diagnostics.caught("repo.createFolder", e)
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
                Diagnostics.caught("repo.cloneRepository", e)
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
                    Diagnostics.caught("repo.resumeClone", e)
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
                Diagnostics.caught("repo.refreshProviders", e)
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
                    Diagnostics.caught("repo.recoverLogin", e)
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
            } catch (swallowed: Exception) {
                Diagnostics.caught("repo.receiveProviderAuthEvent", swallowed)
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
                Diagnostics.caught("repo.startLogin", e)
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
                Diagnostics.caught("repo.answerLogin", e)
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
                Diagnostics.caught("repo.cancelLogin", e)
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
                Diagnostics.caught("repo.logoutProvider", e)
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
            Diagnostics.caught("repo.openFolder", e)
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
            } catch (_: CancellationException) {} catch (swallowed: Exception) {
                Diagnostics.caught("repo.action", swallowed)
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
        if (!canUseBackgroundJobs(current)) throw RemoteRequestException.unsupported()
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
            recoveryStarting = ::recoveryStarting,
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
        ) throw RemoteRequestException.unsupported()
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
        // An unsaved edit asks first; its prompt runs this again through confirmFileEditorLeave.
        if (filesLoader.requestLeave(EditorLeave.CHANGES)) return
        filesLoader.close()
        changesLoader.open(currentDraftKey()?.let { drafts[it]?.reviewComments }.orEmpty())
    }

    override fun closeChanges() = changesLoader.close()

    override fun selectChangesBase(base: GitBase) = changesLoader.selectBase(base)

    override fun reloadChanges() = changesLoader.reload()

    override fun openChangesFile(path: String?) = changesLoader.openFile(path)

    override fun openChangesSubagentFile(sessionId: String, path: String) =
        changesLoader.openSubagentFile(sessionId, path)

    override fun requestChangesPreview(path: String) = changesLoader.requestPreview(path)

    // ---- Project files (session.files.v1) ------------------------------------------------

    private fun requireFiles(sessionId: String) {
        val current = state.value
        if (!current.connected) throw RemoteRequestException("offline")
        // An older host closes the connection on a command it does not know.
        if (FILES_CAPABILITY !in current.capabilities ||
            FILES_CAPABILITY in current.unavailableCapabilities || sessionId.isEmpty()
        ) throw RemoteRequestException.unsupported()
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

    override suspend fun filesWrite(
        sessionId: String,
        path: String,
        baseVersion: String,
        content: String,
        requestId: String,
    ): FileWritten {
        requireFiles(sessionId)
        val current = state.value
        if (FILES_WRITE_CAPABILITY !in current.capabilities || FILES_WRITE_CAPABILITY in current.unavailableCapabilities)
            throw RemoteRequestException.unsupported()
        val data =
            request(
                "session.files.write",
                selectionEpoch,
                "sessionId" to sessionId,
                "path" to path,
                "baseVersion" to baseVersion,
                "content" to content,
                requestId = requestId,
            )
        return parseFilesWritten(data, sessionId, path)
    }

    // ---- Sent attachment content (session.attachments.read.v1) ---------------------------

    private class AttachmentRead {
        lateinit var job: Deferred<AttachmentReadResult>
        var waiters = 0
    }

    private val attachmentBytes = AttachmentByteCache()
    private val attachmentReads = mutableMapOf<AttachmentByteCache.Key, AttachmentRead>()
    private val attachmentReadMutex = Mutex()
    private var lastAttachmentReadStart: Long? = null

    override suspend fun readAttachment(
        sessionId: String,
        attachment: RemoteAttachment,
    ): AttachmentReadResult {
        if (
            attachment.kind != "image" ||
                attachment.size !in 0..MAX_ATTACHMENT_IMAGE_BYTES ||
                !canonicalAttachmentId(attachment.id) ||
                !opaqueId(sessionId) ||
                sessionId != state.value.selection.sessionId
        ) return AttachmentReadResult.Unavailable
        val key = AttachmentByteCache.Key(sessionId, attachment.id, attachment.sha256)
        attachmentBytes[key]?.let { return AttachmentReadResult.Loaded(it) }
        val current = state.value
        if (!current.connected) return AttachmentReadResult.Failed
        if (ATTACHMENT_READ_CAPABILITY !in current.capabilities)
            // Until projects.list has been answered the host's capabilities are not known yet.
            return if (current.capabilitiesKnown) AttachmentReadResult.Unsupported
            else AttachmentReadResult.Failed
        val epoch = selectionEpoch
        // Concurrent callers for one image share a single read; it stops when the last one leaves.
        val read =
            synchronized(attachmentReads) {
                attachmentReads
                    .getOrPut(key) {
                        val entry = AttachmentRead()
                        entry.job =
                            scope.async(start = CoroutineStart.LAZY) {
                                try {
                                    fetchAttachment(epoch, sessionId, attachment, key)
                                } finally {
                                    // Only this read's own entry: a newer one may have replaced it.
                                    synchronized(attachmentReads) { attachmentReads.remove(key, entry) }
                                }
                            }
                        entry
                    }
                    .also { it.waiters++ }
            }
        read.job.start()
        try {
            return read.job.await()
        } catch (e: CancellationException) {
            // The shared read was cancelled; a caller that is still active should see a failure
            // it can retry rather than wait forever.
            currentCoroutineContext().ensureActive()
            return AttachmentReadResult.Failed
        } finally {
            synchronized(attachmentReads) {
                if (--read.waiters == 0 && read.job.isActive) {
                    read.job.cancel()
                    attachmentReads.remove(key, read)
                }
            }
        }
    }

    private suspend fun fetchAttachment(
        epoch: Long,
        sessionId: String,
        attachment: RemoteAttachment,
        key: AttachmentByteCache.Key,
    ): AttachmentReadResult {
        val reassembler = AttachmentReassembler(sessionId, attachment)
        try {
            while (true) {
                val data =
                    // One read at a time, each start at least 200 ms after the last, retries included.
                    attachmentReadMutex.withLock {
                        lastAttachmentReadStart?.let {
                            val wait = ATTACHMENT_READ_SPACING_MILLIS - (now() - it)
                            if (wait > 0) delay(wait.coerceAtMost(ATTACHMENT_READ_SPACING_MILLIS))
                        }
                        lastAttachmentReadStart = now()
                        // Once sent, the request is awaited even if the caller leaves, so the next
                        // read never starts while the host may still be answering this one.
                        withContext(NonCancellable) {
                            request(
                                "session.attachments.get",
                                epoch,
                                *attachmentGetFields(sessionId, attachment.id, reassembler.offset),
                            )
                        }
                    }
                if (reassembler.accept(data)) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteRequestException) {
            Diagnostics.caught("repo.fetchAttachment", e)
            return when (e.code) {
                "not_found", "forbidden", "invalid_request" -> AttachmentReadResult.Unavailable
                else -> AttachmentReadResult.Failed
            }
        } catch (e: AttachmentProtocolException) {
            Diagnostics.caught("repo.fetchAttachment.2", e)
            return AttachmentReadResult.Unavailable
        } catch (e: Exception) {
            Diagnostics.caught("repo.fetchAttachment.3", e)
            return AttachmentReadResult.Failed
        }
        return AttachmentReadResult.Loaded(reassembler.bytes().also { attachmentBytes[key] = it })
    }

    // ---- Agent images (session.files.media.v1) --------------------------------------------

    private class ProjectImageRead {
        lateinit var job: Deferred<ProjectImageResult>
        var waiters = 0
    }

    private val projectImageBytes = ProjectImageByteCache()
    private val projectImageReads = mutableMapOf<Triple<String, String, Boolean>, ProjectImageRead>()
    private val projectImageReadMutex = Mutex()
    /** The host keeps four copies per device; two downloads leave room for a share or a save. */
    private val projectImageDownloads = Semaphore(2)
    /**
     * Artifacts get their own permit so a slow page never starves images. Together with the two image
     * permits that is at most three downloads, under the host's four-per-device cap.
     */
    private val projectArtifactDownloads = Semaphore(1)
    private var lastProjectImageRead: Long? = null

    override suspend fun readProjectImage(sessionId: String, path: String, fresh: Boolean): ProjectImageResult {
        if (!opaqueId(sessionId) || !validMediaPath(path) || sessionId != state.value.selection.sessionId)
            return ProjectImageResult.Unavailable
        val cacheKey = ProjectImageByteCache.Key(sessionId, path)
        if (!fresh) projectImageBytes[cacheKey]?.let { return it }
        val current = state.value
        if (!current.connected) return ProjectImageResult.Failed
        if (FILES_MEDIA_CAPABILITY !in current.capabilities || FILES_MEDIA_CAPABILITY in current.unavailableCapabilities)
            // Until projects.list has been answered the host's capabilities are not known yet.
            return if (current.capabilitiesKnown) ProjectImageResult.Unsupported else ProjectImageResult.Failed
        val epoch = selectionEpoch
        val key = Triple(sessionId, path, fresh)
        // Concurrent callers for one image share a single read; it stops when the last one leaves.
        val read =
            synchronized(projectImageReads) {
                projectImageReads
                    .getOrPut(key) {
                        val entry = ProjectImageRead()
                        entry.job =
                            scope.async(start = CoroutineStart.LAZY) {
                                try {
                                    projectImageDownloads.withPermit {
                                        fetchProjectImage(epoch, sessionId, path, cacheKey, fresh)
                                    }
                                } finally {
                                    synchronized(projectImageReads) { projectImageReads.remove(key, entry) }
                                }
                            }
                        entry
                    }
                    .also { it.waiters++ }
            }
        read.job.start()
        try {
            return read.job.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return ProjectImageResult.Failed
        } finally {
            synchronized(projectImageReads) {
                if (--read.waiters == 0 && read.job.isActive) {
                    read.job.cancel()
                    projectImageReads.remove(key, read)
                }
            }
        }
    }

    /** One request at a time, each start at least 200 ms after the last, retries included. */
    private suspend fun spacedMediaRequest(epoch: Long, type: String, vararg fields: Pair<String, Any?>): JsonObject =
        projectImageReadMutex.withLock {
            lastProjectImageRead?.let {
                val wait = ATTACHMENT_READ_SPACING_MILLIS - (now() - it)
                if (wait > 0) delay(wait.coerceAtMost(ATTACHMENT_READ_SPACING_MILLIS))
            }
            lastProjectImageRead = now()
            // Once sent, the request is awaited even if the caller leaves, so the next one
            // never starts while the host may still be answering this one.
            withContext(NonCancellable) { request(type, epoch, *fields) }
        }

    private suspend fun fetchProjectImage(
        epoch: Long,
        sessionId: String,
        path: String,
        cacheKey: ProjectImageByteCache.Key,
        fresh: Boolean,
    ): ProjectImageResult {
        suspend fun spaced(type: String, vararg fields: Pair<String, Any?>): JsonObject =
            spacedMediaRequest(epoch, type, *fields)
        return try {
            when (
                val download =
                    downloadMedia(
                        sessionId,
                        path,
                        start = { spaced("session.files.media", *mediaFields(sessionId, path)) },
                        read = { mediaId, offset ->
                            spaced("session.files.media.get", *mediaGetFields(sessionId, mediaId, offset))
                        },
                        accept = { !it.isHtml },
                    )
            ) {
                is MediaDownload.Omitted ->
                    when (download.reason) {
                        MediaOmitted.TOO_LARGE -> ProjectImageResult.TooLarge
                        MediaOmitted.NOT_AN_IMAGE -> ProjectImageResult.NotAnImage
                    }
                is MediaDownload.Ready ->
                    ProjectImageResult.Loaded(
                        download.meta.path,
                        download.meta.mime.wire,
                        download.meta.sha256,
                        download.bytes,
                    ).also {
                        // A fresh read replaces the copy, so Share, Save and the next view agree.
                        projectImageBytes[cacheKey] = it
                        if (fresh) ProjectImageBitmaps.dropStale(sessionId, path, it.sha256)
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaException) {
            Diagnostics.caught("repo.fetchProjectImage", e)
            when (e.failure) {
                MediaFailure.UNAVAILABLE -> ProjectImageResult.Unavailable
                MediaFailure.BUSY -> ProjectImageResult.Busy
                MediaFailure.FAILED -> ProjectImageResult.Failed
            }
        } catch (e: Exception) {
            Diagnostics.caught("repo.fetchProjectImage.2", e)
            ProjectImageResult.Failed
        }
    }

    // ---- HTML artifacts (session.files.artifact.v1) ---------------------------------------

    private class ProjectArtifactRead {
        lateinit var job: Deferred<ProjectArtifactResult>
        var waiters = 0
    }

    private val projectArtifacts = ProjectArtifactCache()
    private val projectArtifactReads = mutableMapOf<Triple<String, String, Boolean>, ProjectArtifactRead>()

    override suspend fun readProjectArtifact(sessionId: String, path: String, fresh: Boolean): ProjectArtifactResult {
        if (!opaqueId(sessionId) || !validMediaPath(path) || sessionId != state.value.selection.sessionId)
            return ProjectArtifactResult.Unavailable
        val cacheKey = ProjectArtifactCache.Key(sessionId, path)
        if (!fresh) projectArtifacts[cacheKey]?.let { return it }
        val current = state.value
        if (!current.connected) return ProjectArtifactResult.ConnectionFailure
        if (FILES_ARTIFACT_CAPABILITY !in current.capabilities || FILES_ARTIFACT_CAPABILITY in current.unavailableCapabilities)
            // Until projects.list has been answered the host's capabilities are not known yet.
            return if (current.capabilitiesKnown) ProjectArtifactResult.UnsupportedHost
            else ProjectArtifactResult.ConnectionFailure
        val epoch = selectionEpoch
        val key = Triple(sessionId, path, fresh)
        val read =
            synchronized(projectArtifactReads) {
                projectArtifactReads
                    .getOrPut(key) {
                        val entry = ProjectArtifactRead()
                        entry.job =
                            scope.async(start = CoroutineStart.LAZY) {
                                try {
                                    projectArtifactDownloads.withPermit {
                                        fetchProjectArtifact(epoch, sessionId, path, cacheKey)
                                    }
                                } finally {
                                    synchronized(projectArtifactReads) { projectArtifactReads.remove(key, entry) }
                                }
                            }
                        entry
                    }
                    .also { it.waiters++ }
            }
        read.job.start()
        try {
            return read.job.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return ProjectArtifactResult.ConnectionFailure
        } finally {
            synchronized(projectArtifactReads) {
                if (--read.waiters == 0 && read.job.isActive) {
                    read.job.cancel()
                    projectArtifactReads.remove(key, read)
                }
            }
        }
    }

    private suspend fun fetchProjectArtifact(
        epoch: Long,
        sessionId: String,
        path: String,
        cacheKey: ProjectArtifactCache.Key,
    ): ProjectArtifactResult =
        try {
            when (
                val download =
                    downloadMedia(
                        sessionId,
                        path,
                        start = { spacedMediaRequest(epoch, "session.files.media", *mediaFields(sessionId, path)) },
                        read = { mediaId, offset ->
                            spacedMediaRequest(epoch, "session.files.media.get", *mediaGetFields(sessionId, mediaId, offset))
                        },
                        accept = { it.isHtml },
                    )
            ) {
                is MediaDownload.Omitted ->
                    when (download.reason) {
                        MediaOmitted.TOO_LARGE -> ProjectArtifactResult.TooLarge
                        MediaOmitted.NOT_AN_IMAGE -> ProjectArtifactResult.NotAnArtifact
                    }
                is MediaDownload.Ready -> {
                    // The host's sniff is not trusted: the bytes must be text on this side as well.
                    val html = if (download.meta.mime.isHtml) decodeArtifactHtml(download.bytes) else null
                    if (html == null) ProjectArtifactResult.NotAnArtifact
                    else
                        ProjectArtifactResult.Loaded(download.meta.path, download.meta.sha256, html, download.bytes.size)
                            .also { projectArtifacts[cacheKey] = it }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaException) {
            Diagnostics.caught("repo.fetchProjectArtifact", e)
            when (e.failure) {
                MediaFailure.UNAVAILABLE -> ProjectArtifactResult.Unavailable
                MediaFailure.BUSY -> ProjectArtifactResult.Busy
                MediaFailure.FAILED -> ProjectArtifactResult.ConnectionFailure
            }
        } catch (e: Exception) {
            Diagnostics.caught("repo.fetchProjectArtifact.2", e)
            ProjectArtifactResult.ConnectionFailure
        }

    // ---- Session artifacts (session.artifacts.v1) -----------------------------------------

    private enum class ArtifactAccess { OK, UNSUPPORTED, OFFLINE, INVALID }

    private fun artifactAccess(sessionId: String): ArtifactAccess {
        val current = state.value
        return when {
            !opaqueId(sessionId) || sessionId != current.selection.sessionId -> ArtifactAccess.INVALID
            !current.connected -> ArtifactAccess.OFFLINE
            ARTIFACTS_CAPABILITY !in current.capabilities || ARTIFACTS_CAPABILITY in current.unavailableCapabilities ->
                // Until projects.list has been answered the host's capabilities are not known yet.
                if (current.capabilitiesKnown) ArtifactAccess.UNSUPPORTED else ArtifactAccess.OFFLINE
            else -> ArtifactAccess.OK
        }
    }

    override suspend fun listSessionArtifacts(sessionId: String): SessionArtifactListResult {
        when (artifactAccess(sessionId)) {
            ArtifactAccess.INVALID -> return SessionArtifactListResult.Unavailable
            ArtifactAccess.UNSUPPORTED -> return SessionArtifactListResult.Unsupported
            ArtifactAccess.OFFLINE -> return SessionArtifactListResult.Failed
            ArtifactAccess.OK -> {}
        }
        val epoch = selectionEpoch
        return try {
            val data = request("session.artifacts.list", epoch, "sessionId" to sessionId)
            SessionArtifactListResult.Loaded(
                try {
                    parseArtifactsList(data, sessionId)
                } catch (e: Exception) {
                    Diagnostics.caught("repo.listSessionArtifacts", e)
                    return SessionArtifactListResult.Unavailable
                }
            )
        } catch (e: CancellationException) {
            // The request was cancelled by a disconnect or selection change, not this caller.
            currentCoroutineContext().ensureActive()
            SessionArtifactListResult.Failed
        } catch (e: RemoteRequestException) {
            Diagnostics.caught("repo.listSessionArtifacts.2", e)
            if (e.isUnsupported || e.hostCode in setOf("not_found", "forbidden", "invalid_request"))
                SessionArtifactListResult.Unavailable
            else SessionArtifactListResult.Failed
        } catch (e: Exception) {
            Diagnostics.caught("repo.listSessionArtifacts.3", e)
            SessionArtifactListResult.Failed
        }
    }

    override suspend fun openSessionArtifact(
        sessionId: String,
        artifactId: String,
        version: Int?,
        type: ArtifactType?,
        sha256: String?,
    ): SessionArtifactOpenResult {
        if (!validArtifactId(artifactId) ||
            (version != null && (version < 1 || version.toLong() > MAX_ARTIFACT_VERSION)))
            return SessionArtifactOpenResult.Unavailable
        when (artifactAccess(sessionId)) {
            ArtifactAccess.INVALID -> return SessionArtifactOpenResult.Unavailable
            ArtifactAccess.UNSUPPORTED -> return SessionArtifactOpenResult.Unsupported
            ArtifactAccess.OFFLINE -> return SessionArtifactOpenResult.ConnectionFailure
            ArtifactAccess.OK -> {}
        }
        // A version is an immutable snapshot, so its copy is safe to keep; the latest is read again.
        // A kept copy whose digest differs from the listed one is stale and never wins.
        if (version != null) {
            val logical = "artifacts/$artifactId/$version"
            for (kind in listOf(ArtifactType.HTML, ArtifactType.MERMAID))
                if (type == null || type == kind)
                    projectArtifacts[artifactCacheKey(sessionId, "$logical.${kind.mime.extension}")]
                        ?.takeIf { sha256 == null || it.sha256 == sha256 }
                        ?.let { return SessionArtifactOpenResult.Text(kind, it) }
            if (type == null || type == ArtifactType.SVG)
                projectImageBytes[artifactImageCacheKey(sessionId, "$logical.svg")]
                    ?.takeIf { sha256 == null || it.sha256 == sha256 }
                    ?.let { return SessionArtifactOpenResult.Svg(it) }
        }
        val epoch = selectionEpoch
        return try {
            projectArtifactDownloads.withPermit { fetchSessionArtifact(epoch, sessionId, artifactId, version, type) }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            SessionArtifactOpenResult.ConnectionFailure
        }
    }

    private fun artifactCacheKey(sessionId: String, logicalPath: String) =
        ProjectArtifactCache.Key(sessionId, artifactCachePath(logicalPath))

    private fun artifactImageCacheKey(sessionId: String, logicalPath: String) =
        ProjectImageByteCache.Key(sessionId, artifactCachePath(logicalPath))

    private suspend fun fetchSessionArtifact(
        epoch: Long,
        sessionId: String,
        artifactId: String,
        version: Int?,
        type: ArtifactType?,
    ): SessionArtifactOpenResult =
        try {
            when (
                val download =
                    downloadMedia(
                        sessionId,
                        "artifacts/$artifactId",
                        start = {
                            spacedMediaRequest(epoch, "session.artifacts.open", *artifactOpenFields(sessionId, artifactId, version))
                        },
                        read = { mediaId, offset ->
                            spacedMediaRequest(epoch, "session.files.media.get", *mediaGetFields(sessionId, mediaId, offset))
                        },
                        // An answer of another type than the listed one ends before any byte is read.
                        accept = { (it.isHtml || it.isSvg || it.isMermaid) && (type == null || it == type.mime) },
                        artifactPath = { artifactPathMatches(it, artifactId, version) },
                    )
            ) {
                is MediaDownload.Omitted ->
                    when (download.reason) {
                        MediaOmitted.TOO_LARGE -> SessionArtifactOpenResult.TooLarge
                        MediaOmitted.NOT_AN_IMAGE -> SessionArtifactOpenResult.NotAnArtifact
                    }
                is MediaDownload.Ready -> {
                    val meta = download.meta
                    // The extension must agree with the type, so a path never names another kind.
                    if (!meta.path.endsWith(".${meta.mime.extension}")) SessionArtifactOpenResult.Unavailable
                    else if (meta.mime.isSvg) {
                        SessionArtifactOpenResult.Svg(
                            ProjectImageResult.Loaded(meta.path, meta.mime.wire, meta.sha256, download.bytes)
                                .also { if (version != null) projectImageBytes[artifactImageCacheKey(sessionId, meta.path)] = it }
                        )
                    } else {
                        // The host's sniff is not trusted: the bytes must be text on this side as well.
                        val text = decodeArtifactHtml(download.bytes)
                        if (text == null) SessionArtifactOpenResult.NotAnArtifact
                        else {
                            val loaded = ProjectArtifactResult.Loaded(meta.path, meta.sha256, text, download.bytes.size)
                            if (version != null) projectArtifacts[artifactCacheKey(sessionId, meta.path)] = loaded
                            SessionArtifactOpenResult.Text(
                                if (meta.mime.isMermaid) ArtifactType.MERMAID else ArtifactType.HTML,
                                loaded,
                            )
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaException) {
            Diagnostics.caught("repo.fetchSessionArtifact", e)
            when (e.failure) {
                MediaFailure.UNAVAILABLE -> SessionArtifactOpenResult.Unavailable
                MediaFailure.BUSY -> SessionArtifactOpenResult.Busy
                MediaFailure.FAILED -> SessionArtifactOpenResult.ConnectionFailure
            }
        } catch (e: Exception) {
            Diagnostics.caught("repo.fetchSessionArtifact.2", e)
            SessionArtifactOpenResult.ConnectionFailure
        }

    // ---- Session export (session.export.v1) -----------------------------------------------

    override suspend fun exportSession(sessionId: String): ExportResult {
        val current = state.value
        if (!opaqueId(sessionId) || sessionId != current.selection.sessionId)
            return ExportResult.Failed(ExportFailure.FAILED)
        if (!current.connected) return ExportResult.Failed(ExportFailure.OFFLINE)
        if (current.session?.optionalText("origin") != "rpc" || EXPORT_CAPABILITY !in current.capabilities ||
            EXPORT_CAPABILITY in current.unavailableCapabilities)
            return ExportResult.Failed(ExportFailure.UNSUPPORTED)
        val epoch = selectionEpoch
        return try {
            downloadExport(
                sessionId,
                start = { request("session.export", epoch, "sessionId" to sessionId) },
                read = { exportId, offset ->
                    request("session.export.get", epoch, *exportGetFields(sessionId, exportId, offset))
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExportException) {
            Diagnostics.caught("repo.exportSession", e)
            ExportResult.Failed(e.failure)
        } catch (e: Exception) {
            Diagnostics.caught("repo.exportSession.2", e)
            ExportResult.Failed(ExportFailure.FAILED)
        }
    }

    // ---- Session reload (session.reload.v1) -----------------------------------------------

    private var reloadingSession: String? = null

    override suspend fun reloadSession(sessionId: String): ReloadResult {
        val current = state.value
        if (!opaqueId(sessionId) || sessionId != current.selection.sessionId)
            return ReloadResult.Failed(ReloadFailure.Failed(null))
        if (!current.connected) return ReloadResult.Failed(ReloadFailure.ConnectionFailure)
        if (current.session?.optionalText("origin") != "rpc" || RELOAD_CAPABILITY !in current.capabilities ||
            RELOAD_CAPABILITY in current.unavailableCapabilities)
            return ReloadResult.Failed(ReloadFailure.Unsupported)
        if (reloadingSession != null) return ReloadResult.Failed(ReloadFailure.Busy)
        reloadingSession = sessionId
        val epoch = selectionEpoch
        return try {
            // The host waits 30 s for pi's report, then resyncs, before it answers.
            val data = request("session.reload", epoch, "sessionId" to sessionId, timeoutMillis = 60_000)
            val valid = runCatching { validatedReloaded(data, sessionId) }.isSuccess
            // Whatever the reply looks like, pi was asked to reload.
            refreshAfterReload(epoch)
            if (valid) ReloadResult.Done else ReloadResult.Failed(ReloadFailure.Failed(null))
        } catch (e: CancellationException) {
            // A disconnect or selection change cancels the request, not this caller.
            currentCoroutineContext().ensureActive()
            ReloadResult.Failed(ReloadFailure.Unknown)
        } catch (e: Exception) {
            Diagnostics.caught("repo.reloadSession", e)
            val failure = reloadFailure(e)
            if (reloadResultUnknown(failure)) refreshAfterReload(epoch)
            ReloadResult.Failed(failure)
        } finally {
            reloadingSession = null
        }
    }

    /** New prompts, skills and extension commands may exist, and the host rewrote the message list. */
    private fun refreshAfterReload(epoch: Long) {
        if (epoch != selectionEpoch || !state.value.connected) return
        snapshotAsync()
        // A catalog request already in flight predates the reload: drop it and ask again.
        commandsVersion++
        update { it.copy(commandsLoading = false) }
        refreshCommands()
    }

    // ---- Session tree (session.tree.v1) ---------------------------------------------------

    private fun treeRefused(sessionId: String): TreeFailure? {
        val current = state.value
        return when {
            !opaqueId(sessionId) || sessionId != current.selection.sessionId -> TreeFailure.FAILED
            !current.connected -> TreeFailure.OFFLINE
            SESSION_TREE_CAPABILITY !in current.capabilities ||
                SESSION_TREE_CAPABILITY in current.unavailableCapabilities -> TreeFailure.UNSUPPORTED
            else -> null
        }
    }

    override suspend fun loadSessionTree(sessionId: String): TreeLoadResult {
        treeRefused(sessionId)?.let { return TreeLoadResult.Failed(it) }
        val epoch = selectionEpoch
        return try {
            val data = request("session.tree", epoch, *treeFields(sessionId))
            TreeLoadResult.Loaded(
                try {
                    validatedTree(data, sessionId)
                } catch (e: Exception) {
                    throw TreeException(TreeFailure.PROTOCOL)
                }
            )
        } catch (e: CancellationException) {
            // The request was cancelled by a disconnect or selection change, not this caller.
            currentCoroutineContext().ensureActive()
            TreeLoadResult.Failed(TreeFailure.FAILED)
        } catch (e: Exception) {
            Diagnostics.caught("repo.loadSessionTree", e)
            // A read has no result that could be unknown.
            val failure = treeFailure(e)
            TreeLoadResult.Failed(if (treeResultUnknown(failure)) TreeFailure.FAILED else failure)
        }
    }

    override suspend fun navigateSessionTree(
        sessionId: String,
        nodeId: String,
        summarize: Boolean,
    ): TreeNavigateResult {
        treeRefused(sessionId)?.let { return TreeNavigateResult.Failed(it) }
        if (!validTreeNodeId(nodeId)) return TreeNavigateResult.Failed(TreeFailure.INVALID)
        val epoch = selectionEpoch
        return try {
            // The host waits 30 s for the move, 180 s with a summary, before it answers.
            val data =
                request(
                    "session.tree.navigate",
                    epoch,
                    *treeNavigateFields(sessionId, nodeId, summarize),
                    timeoutMillis = if (summarize) 195_000 else 45_000,
                )
            val navigation =
                try {
                    validatedNavigation(data, sessionId)
                } catch (e: Exception) {
                    throw TreeException(TreeFailure.PROTOCOL)
                }
            if (epoch == selectionEpoch && state.value.selection.sessionId == sessionId) {
                treeDraft(navigation.text)?.let { draft ->
                    val key = currentDraftKey()
                    val now = state.value
                    if (key != null) {
                        // Never replace what the user has typed or quoted since.
                        if (now.draft.isBlank() && now.quote == null) {
                            drafts[key] = (drafts[key] ?: StoredDraft()).copy(text = draft.text, quote = draft.quote)
                            update { it.copy(draft = draft.text, quote = draft.quote) }
                            persist()
                            if (draft.droppedAttachments) reportError(R.string.remote_fork_attachments_dropped)
                        } else reportError(R.string.remote_tree_text_not_inserted)
                    }
                }
                // The host's snapshot.required event does the same; do not depend on its order.
                snapshotAsync()
            }
            TreeNavigateResult.Done(navigation)
        } catch (e: CancellationException) {
            // A disconnect or selection change cancels the request, not this caller; the move may
            // still have happened, and the sheet must not wait for an answer that never comes.
            currentCoroutineContext().ensureActive()
            TreeNavigateResult.Failed(TreeFailure.UNKNOWN_RESULT)
        } catch (e: Exception) {
            Diagnostics.caught("repo.navigateSessionTree", e)
            val failure = treeFailure(e, navigating = true)
            // The move may have happened; show what the host has now.
            if (treeResultUnknown(failure) && epoch == selectionEpoch && state.value.connected) snapshotAsync()
            TreeNavigateResult.Failed(failure)
        }
    }

    private val filesLoader =
        ProjectFilesLoader(
            scope,
            object : FileSource {
                override suspend fun filesList(sessionId: String, path: String, after: String?) =
                    this@DefaultRemoteRepository.filesList(sessionId, path, after)

                override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) =
                    this@DefaultRemoteRepository.filesRead(sessionId, path, offset, version)

                override suspend fun filesWrite(
                    sessionId: String,
                    path: String,
                    baseVersion: String,
                    content: String,
                    requestId: String,
                ) = this@DefaultRemoteRepository.filesWrite(sessionId, path, baseVersion, content, requestId)
            },
            { state.value },
            ::update,
        )

    override fun openFiles() {
        // The pane already shows the open editor; opening the browser again would drop its edit.
        val current = state.value
        if (current.files?.takeIf { it.sessionId == current.selection.sessionId }?.editor != null) return
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

    override fun startFileEditing() = filesLoader.startEditing()

    override fun updateFileEditorText(text: String) = filesLoader.updateEditorText(text)

    override fun saveFileEditor() = filesLoader.saveEditor()

    override fun discardFileEditor() = filesLoader.discardEditor()

    override fun reloadFileAfterConflict() = filesLoader.reloadAfterConflict()

    override fun fileEditorPrompt(intro: String?) = filesLoader.editorPrompt(intro)

    override fun confirmFileEditorLeave() {
        when (filesLoader.confirmLeave()) {
            EditorLeave.CHANGES -> openChanges()
            null -> {}
        }
    }

    override fun cancelFileEditorLeave() = filesLoader.cancelLeave()

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
