package de.joinnoah.pi.remote

import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.random.Random
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

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
/** A project's session list read more recently than this is fresh enough to show without a read. */
internal const val SESSION_REFRESH_INTERVAL_MILLIS = 5_000L
/** The project list read more recently than this on the same connection is not read again on a visit. */
internal const val PROJECTS_REVALIDATE_MILLIS = 10_000L
/** A list of every project read more recently than this on the same connection is not read again. */
internal const val PROJECT_LISTS_REFRESH_MILLIS = 30_000L

/** Project lists a tapped notification's walk reads at a time while it looks for its session. */
internal const val NOTIFICATION_WALK_PARALLELISM = 4
/** On Projects, a waiting or running chat the lists miss reads them again once they are this old. */
private const val ATTENTION_REFRESH_MILLIS = 1_500L
/** A session no project's list held after all of them were read is not looked for again this soon. */
internal const val MISSING_SESSION_RETRY_MILLIS = 120_000L
private const val ATTACHMENT_READ_SPACING_MILLIS = 200L
/** How long typing waits for more keystrokes before its draft is written. */
private const val DRAFT_WRITE_DELAY_MILLIS = 300L
private const val CAPABILITIES_V1_ROUTE = "capabilities.v1:"
private const val CAPABILITIES_V2_ROUTE = "capabilities.v2:"
/** The v2 list holds the v1 entries plus newer ones; installed apps accept 16, this one 32. */
private const val MAX_ROUTE_CAPABILITIES = 32

/** Sessions whose unsaved editor is kept while another session is open; the oldest goes first. */
private const val MAX_PARKED_EDITORS = 4
/** Events kept for a held snapshot's chat, as many as a Timeline buffers; older ones need a resync. */
private const val MAX_HELD_EVENTS = 128
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
/** The relay announced a restart: the retry waits 250 to 750 ms, so the Mac can reconnect first. */
internal const val RESTART_RETRY_MIN_MILLIS = 250L
internal const val RESTART_RETRY_SPREAD_MILLIS = 500L
/** connect() outlasts the transport's own handshake timeout, so only one of them fires. */
internal const val CONNECT_READY_WAIT_MILLIS = HANDSHAKE_TIMEOUT_MILLIS + 2_000L
/** The relay answered but the Mac has not, after this long: say so instead of "Connecting…". */
internal const val HOST_WAIT_HINT_MILLIS = 5_000L

/**
 * While a chat streams, the navigation snapshot falls behind it by at most this long: events mark
 * it dirty instead of writing it, and a timer writes it. Structure changes and the background
 * write at once.
 */
internal const val NAVIGATION_DIRTY_WRITE_MILLIS = 15_000L

/**
 * While a message streams, the chat on screen is published at most once per this long: a
 * `message.upsert` that arrives sooner after a publish waits for one trailing publish. Every other
 * event, a snapshot and a selection change publish at once.
 */
internal const val TIMELINE_PUBLISH_MILLIS = 16L

/**
 * Chats left for another chat or a list whose Timelines are kept per Mac, newest first: selecting
 * one again resumes from where it stopped instead of loading it anew.
 */
internal const val DETACHED_TIMELINES = 4

/**
 * A plain prompt typed while the connection is down waits this long for the reconnect; after it
 * the prompt is marked not confirmed and the user sends it again by hand.
 */
internal const val QUEUED_PROMPT_MILLIS = 15 * 60_000L

/** A `remote.stream.v1` connection is pinged this often while the app is in the foreground. */
internal const val PING_INTERVAL_MILLIS = 30_000L

/** A `remote.ping` without its `pong` after this long is recorded as a timeout, and nothing else. */
internal const val PING_TIMEOUT_MILLIS = 10_000L

/**
 * The first failure of a streak reconnects quietly; the error card follows only if the Mac is
 * still unreachable this long after it, or at once on the next failure.
 */
internal const val QUIET_RECONNECT_MILLIS = 5_000L

/** The default wait for a request's reply. */
internal const val REQUEST_TIMEOUT_MILLIS = 30_000L

/** Connection-bound requests (folder, fan-out, background) that may be outstanding at once. */
private const val MAX_CONNECTION_REQUESTS = 32

/** Back this soon, on the same screen and with frames flowing, the app resumes without a request. */
internal const val RESUME_QUIET_AWAY_MILLIS = 15_000L

/**
 * After the app went to the background, the chat on screen stays in the connection's
 * `session.focus` this long, so a return within the quiet-resume window needs no catch-up. Later,
 * without a live run kept for it, the focus narrows to none and the chat's Timeline detaches.
 */
internal const val BACKGROUND_FOCUS_GRACE_MILLIS = RESUME_QUIET_AWAY_MILLIS

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
        // Idempotent: a reconnect sends the focus again.
        "session.focus",
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

    /** The push wake window ended in the background: the app closed the socket itself. */
    WAKE_ENDED,
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
    /**
     * Where the app's other stores last saw a session, for a tapped notification: the project ID
     * the home snapshot or the label worker's names hold for a route and session, or null.
     */
    private val projectHint: suspend (routeId: String, sessionId: String) -> String? = { _, _ -> null },
    /** Uniform value in [0, 1) that spreads reconnect delays; injectable for tests. */
    private val jitter: () -> Double = { Random.nextDouble() },
    /** Sent and project images kept on disk between runs; null keeps them in memory only. */
    private val media: MediaDiskCache? = null,
    /**
     * Whether a live run keeps the process in the background (the [LiveRunService] runs), which
     * is the only case where a push wake leaves its socket open at the window's end.
     */
    private val liveRunKept: () -> Boolean = { false },
) : RemoteRepository {
    private val routeCapabilities =
        setOf(
            STEER_CAPABILITY,
            // Compact and session settings, too, arrive only here: the host's handshake list is frozen at eight.
            COMPACT_CAPABILITY,
            SETTINGS_CAPABILITY,
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
            // Only on the capabilities.v2 route, and only while the Mac's switch is on.
            SESSION_BASH_CAPABILITY,
            // Also v2 only: it lets terminal sessions and host workers run them as well.
            SESSION_BASH_WORKER_CAPABILITY,
            // Only on the capabilities.v2 route; writes need the project's `writable` flag as well.
            TICKETS_CAPABILITY,
            SESSION_TASKS_CAPABILITY,
            // Only on the capabilities.v2 route: focus, `after`, ping and acks.
            STREAM_CAPABILITY,
            // Only on the capabilities.v2 route, which gives extension sessions their controls.
            HOST_WORKER_V2_CAPABILITY,
        )
    /** The request ID prefix of `projects.list`; falls back to v1 once for a host without v2. */
    private var capabilityRoute = CAPABILITIES_V2_ROUTE
    private var tasksCapabilityKnown = false
    private val mutable = MutableStateFlow(RemoteState())
    override val state = mutable.asStateFlow()
    private val attachmentMutex = Mutex()
    private var importJob: Job? = null
    private var sendJob: Job? = null

    /** The plain prompt that waits for the connection ([PendingSendState.QUEUED]); at most one. */
    private data class QueuedPrompt(
        val key: DraftKey,
        val id: String,
        val attempt: Any,
        val cleared: ClearedComposer,
        val expiry: Job,
    )
    private var queuedPrompt: QueuedPrompt? = null
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
    /** Completed tool outputs, so opening one again sends no request. */
    private val toolOutputs = ToolOutputCache()
    /** The download that stopped part way: its bytes are kept, and the next load resumes at them. */
    private var toolOutputProgress: ToolOutputProgress? = null
    private var sessionRefreshJob: Job? = null
    private var lastSessionRefreshAt: Long? = null
    private var folderVersion = 0L
    private var cloneStatusJob: Job? = null
    /** Entries seen in listings of the current folder host, by path, to know a folder's trust. */
    private val folderEntries = LinkedHashMap<String, FolderEntry>()
    /** host.event payloads that arrived before their clone.started result, by clone ID. */
    private val earlyCloneEvents = linkedMapOf<String, JsonObject>()
    /**
     * Connection-bound requests: folder, clone and provider commands ([folderRequest]), the
     * project fan-out ([connectionRequest]) and background work ([withHealthyConnection]). A
     * selection change does not cancel them; a lost or replaced connection fails them all
     * ([failConnectionRequests]). At most [MAX_CONNECTION_REQUESTS] are outstanding.
     */
    private val connectionCompletions = mutableMapOf<String, CompletableDeferred<JsonObject>>()
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
    /**
     * The follow-ups still pending or accepted when their Mac was left on purpose, by chat. The
     * next snapshot of that chat checks them: a receipt it holds settles its entry, and the rest
     * become uncertain, as a lost connection would have left them.
     */
    private val followUpsToRecheck = mutableMapOf<DraftKey, Set<String>>()
    private var selectionEpoch = 0L
    private var generation = 0L
    // Connection-scoped attempts and UI owners start at the send tap.
    private val promptAttempts = mutableMapOf<DraftKey, Any>()
    private val promptOwners = mutableMapOf<DraftKey, Any>()
    // Journal ownership starts only on publication and survives disconnects.
    private val journalOwners = mutableMapOf<DraftKey, Any>()
    // IDs are remembered for the whole connection: a reused ID cannot identify an attempt.
    private val issuedRequestIds = mutableSetOf<String>()
    private val ambiguousRequestIds = mutableSetOf<String>()
    private var lateAcknowledgementsDisabled = false
    private data class LateAcknowledgement(val at: Long, val sessionId: String, val accept: (JsonObject) -> Unit)
    private val lateAcknowledgements = linkedMapOf<String, LateAcknowledgement>()
    private fun clearLateAcknowledgements() {
        promptAttempts.clear()
        issuedRequestIds.clear()
        ambiguousRequestIds.clear()
        lateAcknowledgements.clear()
        lateAcknowledgementsDisabled = false
    }

    private fun recordIssuedRequest(id: String) {
        if (lateAcknowledgementsDisabled) return
        if (id in issuedRequestIds) {
            ambiguousRequestIds.add(id)
            lateAcknowledgements.remove(id)
        } else if (issuedRequestIds.size >= 4096) {
            lateAcknowledgementsDisabled = true
            lateAcknowledgements.clear()
            issuedRequestIds.clear()
            ambiguousRequestIds.clear()
        } else issuedRequestIds.add(id)
    }

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
        /** The [generation] whose connection read [sessions]; null for the saved navigation's. */
        val readOn: Long? = null,
    )

    private data class CachedChat(
        val selection: RemoteSelection,
        val session: JsonObject,
        val messages: List<JsonObject>,
        val status: String,
        val nextCursor: String?,
    )

    private var cachedProjects: Pair<String, List<JsonObject>>? = null
        set(value) {
            // Another Mac's list, or the saved one, was not read on the connection that read this.
            if (value?.first != field?.first) projectsReadOn = null
            field = value
        }
    /** The [generation] whose connection read [cachedProjects]; null while they are the saved ones. */
    private var projectsReadOn: Long? = null
    /** When [cachedProjects] was last read, or a read of it started, by [now]. */
    private var projectsReadAt: Long? = null
    private var projectsRevalidateJob: Job? = null
    /** The last [readSessions] started: by Mac, project and connection, and when by [now]. */
    private var sessionReadAttempt: Pair<Triple<String, String, Long>, Long>? = null
    private var cachedProjectChats: Pair<String, List<ProjectChatSummary>>? = null
    private val projectChatCandidates = linkedMapOf<String, List<ProjectChatSummary>>()
    private var projectChatsJob: Job? = null
    private var attentionRefreshJob: Job? = null
    /** Sessions [attentionRefreshJob] looks for, with when each was last reported, by [now]. */
    private val unknownSessions = linkedMapOf<String, Long>()
    private var unknownSessionsAttention = false
    /** Sessions no list held after all were read: the [generation] and when, by [now]. */
    private val missingSessions = linkedMapOf<String, Pair<Long, Long>>()
    /** When the latest fan-out of [refreshProjectChats] started, by [now], and the [generation] it read on. */
    private var lastProjectChatsRefreshAt: Long? = null
    private var projectChatsReadOn: Long? = null
    /** Each project's sessions on [projectSessionsRoute], which [RemoteState.projectSessions] shows. */
    private val projectSessionsAggregate = linkedMapOf<String, ProjectSessions>()
    private var projectSessionsRoute: String? = null
    /** When each project's list on [projectSessionsRoute] was last read: the [generation] and [now]. */
    private val projectListReads = mutableMapOf<String, Pair<Long, Long>>()
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

    /** The chat whose remembered pane [select] put on screen still unread; [refreshPanes] reads it. */
    private var pendingPane: DraftKey? = null
    private val savedNavigation = linkedMapOf<String, NavigationSnapshot>()
    private data class NavigationWrite(
        val version: Long,
    )

    private val navigationWrites = Channel<NavigationWrite>(Channel.CONFLATED)
    private val navigationWriteMutex = Mutex()
    private var navigationVersion = 0L
    private var navigationWriteJob: Job? = null
    /**
     * The chat on screen changed since [savedNavigation] took it: the next persist, at the latest
     * [navDirtyJob] after [NAVIGATION_DIRTY_WRITE_MILLIS], brings it up to date.
     */
    private var navDirty = false
    private var navDirtyJob: Job? = null

    /** When [publishTimeline] last ran, and the publish a streaming upsert waits for ([TIMELINE_PUBLISH_MILLIS]). */
    private var lastTimelinePublishAt = Long.MIN_VALUE
    private var trailingPublish: Job? = null
    private var navigationLoaded = navigationStorage == null
    private var pairingsLoaded = false
    private val removedRoutes = mutableSetOf<String>()
    private val removalJobs = mutableMapOf<String, Job>()
    private var resynchronizing = false
    private var taskRefreshPending = false
    private var taskActivationEpoch: Long? = null
    /**
     * The connection was opened by [connectEarly] and no [connect] took it over yet: the caches
     * hold the saved navigation, not lists this connection read, and push is not registered.
     */
    private var earlyConnection = false
    /**
     * [connectEarly] opened the connection for a restore that has not started its activation yet.
     * Until one starts, nothing on screen owns the connection: a failure leaves the reconnect to
     * that activation instead of a timed retry of the selection from before it.
     */
    private var restorePending = false

    /**
     * The Timeline select() made for a snapshot that follows; until that snapshot starts, an event
     * it buffers waits for it instead of asking for one more.
     */
    private var snapshotPending: Timeline? = null

    /**
     * A snapshot read before select() made its chat's Timeline: a restore reads it alongside the
     * lists, an open of a live chat alongside `sessions.open`. The chat's events that arrive
     * meanwhile wait here for that Timeline, so none is lost before it exists.
     */
    private class HeldSnapshot(val sessionId: String, val includeTasks: Boolean, val base: ResumeBase?) {
        lateinit var reply: Deferred<Result<JsonObject>>
        val events = ArrayDeque<JsonObject>()
    }

    private var heldSnapshot: HeldSnapshot? = null

    /** A chat's Timeline kept after the chat was left, with the tasks it showed then. */
    private class DetachedTimeline(val timeline: Timeline, val tasks: SessionTasks)

    /** At most [DETACHED_TIMELINES] per Mac, least recently left first. */
    private val detachedTimelines = LinkedHashMap<DraftKey, DetachedTimeline>()

    /** The connection ([generation]) whose `projects.list` advertised `remote.stream.v1`, or -1. */
    private var streamGeneration = -1L
    /** The `session.focus` last sent, on the connection [focusSentOn]. */
    private var focusSent: List<String>? = null
    private var focusSentOn = -1L
    /** Set while the app is in the background; see [desiredFocus]. */
    private var inBackground = false
    /**
     * Set by [backgroundFocusTimer] once [BACKGROUND_FOCUS_GRACE_MILLIS] passed in the background,
     * on the dispatcher's monotonic clock, so a wall clock set back cannot keep the focus.
     */
    private var backgroundGraceOver = false
    /** Narrows the focus once [BACKGROUND_FOCUS_GRACE_MILLIS] passed in the background. */
    private var backgroundFocusTimer: Job? = null
    /**
     * The route of the open push wake window (see [pushWake]), or null. Written on the main
     * thread, read by [awaitPushWake] from any.
     */
    @Volatile private var pushWakeRoute: String? = null
    /** Ends the push wake window after [PUSH_WAKE_WINDOW_MILLIS]. */
    private var pushWakeTimer: Job? = null
    /**
     * Counts the first-page reads [snapshot] started and [snapshotAsync] scheduled, so a failed read
     * knows whether a later one will still come back to [releaseQueuedPrompt].
     */
    private var firstPageReads = 0L
    /** Sends `remote.ping` on a `remote.stream.v1` connection; see [startPings]. */
    private var pingJob: Job? = null

    /** Which first-page request a snapshot sends; see [firstPageRequestId]. */
    private enum class SnapshotIntent {
        /**
         * Continues from the Timeline's journal base when it has one: the reconnect of the same
         * chat, the reselect of a kept chat, a revision gap, the foreground probe.
         */
        RESUME,

        /** Needs state a replay cannot carry, so the host always answers with a snapshot. */
        FRESH,
    }
    private var pushToken: String? = null
    private var pushTokenKnown = false
    private val drafts = mutableMapOf<DraftKey, StoredDraft>()
    private data class ComposerVersions(val text: Long = 0, val quote: Long = 0, val attachments: Long = 0)
    private val composerVersions = mutableMapOf<DraftKey, ComposerVersions>()
    private fun composerVersion(key: DraftKey) = composerVersions[key] ?: ComposerVersions()
    private fun composerEdited(key: DraftKey, field: String) {
        val version = composerVersion(key)
        composerVersions[key] = when (field) {
            "text" -> version.copy(text = version.text + 1)
            "quote" -> version.copy(quote = version.quote + 1)
            else -> version.copy(attachments = version.attachments + 1)
        }
    }
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

    /**
     * The send journal of each draft as the last queued write saved it. A journal change is queued
     * at once; any other change to the drafts waits for [draftWriteTimer].
     */
    private var journalQueued: Map<DraftKey, SendJournal>? = null
    private var draftWriteTimer: Job? = null

    private val writes = Channel<Write>(Channel.UNLIMITED)
    private var storageFailure: Exception? = null
    private var draftsAvailable = false
    private val initialized = CompletableDeferred<Unit>()
    /** The paired Macs and the drafts are loaded; [initialized] also waits for the saved navigation. */
    private val connectable = CompletableDeferred<Unit>()
    private var pairingEpoch: Long? = null
    private val pairingWrites = Mutex()

    init {
        scope.launch {
            // Independent disk reads overlap. Capture failures so one corrupt store does not
            // cancel initialization or prevent another store from being recovered.
            val loadedNavigation = async(io) {
                runCatching { navigationStorage?.load().orEmpty() }
            }
            val loadedDrafts = async(io) { runCatching { draftStorage.load() to draftStorage.takeReset() } }
            // A store found unreadable was reset to empty (see EncryptedFileStore), so pairing and
            // typing work again; the user is told once.
            var pairingsReset = false
            val hosts =
                try {
                    withContext(io) { pairings.load().also { pairingsReset = pairings.takeReset() } }
                        .also { pairingsLoaded = true }
                } catch (swallowed: Exception) {
                    Diagnostics.caught("repo.loadPairings", swallowed)
                    // The Hosts screen says so once the error card is gone, instead of loading.
                    update { it.copy(hostsUnreadable = true) }
                    reportError(R.string.remote_storage_error)
                    null
                }
            if (hosts != null) {
                update {
                    it.copy(hosts = hosts.filterNot { host -> host.routeId in removedRoutes }, hostsLoaded = true)
                }
                // Images kept for a Mac no longer paired are left over from an unpair that did not
                // finish: they go now, as they cannot be asked for again.
                val paired = hosts.map { host -> host.routeId }
                scope.launch(io) { media?.retainRoutes(paired) }
                if (pairingsReset) reportError(R.string.remote_storage_pairings_reset)
            }
            try {
                val (storedDrafts, draftsReset) = loadedDrafts.await().getOrThrow()
                drafts.putAll(storedDrafts.mapValues { (_, value) ->
                    value.restoringSubmitted().copy(followUps = value.followUps.map { entry ->
                        if (entry.status in setOf("pending", "accepted")) entry.copy(status = "uncertain") else entry
                    })
                })
                persistedAttachmentIds = journalAttachmentIds(drafts)
                journalQueued = drafts.journals()
                draftsAvailable = true
                // The attachment cleanup below then removes the blobs no remaining draft references.
                if (draftsReset && !pairingsReset) reportError(R.string.remote_storage_drafts_reset)
            } catch (e: Exception) {
                Diagnostics.caught("repo.loadDrafts", e)
                storageFailure = e
                reportError(R.string.remote_storage_error)
            }
            // The Macs and the drafts their follow-ups live in are known: a restore may connect
            // while the saved navigation, the larger read, still loads.
            connectable.complete(Unit)
            if (hosts != null) {
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
            initialized.complete(Unit)
            // Only directory IO, which no activation needs to wait for; imports and sends hold the
            // mutex or mark their files in flight, so none of theirs goes.
            if (draftsAvailable) {
                try {
                    cleanupAttachments()
                } catch (e: CancellationException) {
                    throw e
                } catch (swallowed: Exception) {
                    Diagnostics.caught("repo.cleanupAttachments", swallowed)
                }
            }
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
                    Diagnostics.closeCode(code)
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
                        Diagnostics.count(Diagnostics.Counter.RECONNECTS)
                    }
                    offlineSince = null
                    // The backoff resets only once this connection proves itself, so a host that
                    // accepts and then drops connections at once does not cause a tight loop.
                    connectionReady()
                    capabilityRoute = CAPABILITIES_V2_ROUTE
                    tasksCapabilityKnown = false
                    taskRefreshPending = false
                    update {
                        it.copy(
                            connected = true,
                            // Only the capabilities.v2 route may advertise host worker controls.
                            capabilities = capabilities - HOST_WORKER_V2_CAPABILITY,
                            capabilitiesKnown = false,
                            connection = R.string.remote_connected,
                            error = null,
                            reconnectAt = null,
                        )
                    }
                    ready?.complete(Unit)
                    // An early connection registers once an activation took it over: the
                    // selection change that starts one would cancel a request sent before.
                    if (!earlyConnection) registerPush()
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

                override fun inbound() {
                    // At receipt: the frame is decoded off the main thread before message().
                    lastInboundAt = now()
                }

                override fun message(payload: JsonObject) {
                    inboundFrames++
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
            tasks = SessionTasks(),
            unavailableCapabilities = emptySet(),
            childControlUnsupported = emptySet(),
            sessionControlUnsupported = emptyMap(),
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
        projectsRevalidateJob?.cancel()
        cachedProjectChats = null
        projectChatCandidates.clear()
        projectChatsJob?.cancel()
        attentionRefreshJob?.cancel()
        unknownSessions.clear()
        missingSessions.clear()
        projectSessionsAggregate.clear()
        projectSessionsRoute = null
        projectListReads.clear()
        // A refresh by hand reads every project again.
        projectChatsReadOn = null
        cachedSessions = null
        cachedChat = null
        sessionCache.clear()
        chatCache.clear()
        sessionMeta.clear()
        paneMemos.clear()
        sessionReadAttempt = null
        changesLoader.forget()
        filesLoader.forget()
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

    /** Returns whether it queued a write of the active host's snapshot. */
    private fun persistNavigation(immediate: Boolean = false): Boolean {
        if (navigationStorage == null) return false
        val routeId = activeHost?.routeId ?: return false
        if (routeId in removedRoutes) return false
        val projects = cachedProjects?.takeIf { it.first == routeId }?.second ?: return false
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
        navDirty = false
        navDirtyJob?.cancel()
        navDirtyJob = null
        queueNavigationWrite(immediate)
        return true
    }

    /**
     * The chat on screen changed in a way a process kill may lose for a while (a streamed event):
     * the snapshot is written within [NAVIGATION_DIRTY_WRITE_MILLIS], or sooner by any persist.
     */
    private fun markNavigationDirty() {
        if (navigationStorage == null) return
        navDirty = true
        if (navDirtyJob?.isActive == true) return
        navDirtyJob =
            scope.launch {
                delay(NAVIGATION_DIRTY_WRITE_MILLIS)
                navDirtyJob = null
                if (navDirty) persistNavigation(immediate = true)
            }
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
        if (projectSessionsRoute != routeId) seedProjectSessions(routeId, saved)
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

    /**
     * Fills the aggregate of [routeId] from its saved navigation: the saved project's list and the
     * featured chats of the others, none of them confirmed by the Mac yet.
     */
    private fun seedProjectSessions(routeId: String, saved: NavigationSnapshot) {
        projectSessionsAggregate.clear()
        projectListReads.clear()
        projectSessionsRoute = routeId
        val chats = cachedProjectChats?.takeIf { it.first == routeId }?.second.orEmpty().groupBy { it.projectId }
        saved.projects.forEach { project ->
            val projectId = project.optionalText("id")?.takeIf { it.isNotBlank() } ?: return@forEach
            val listed = if (projectId == saved.projectId) saved.sessions else emptyList()
            val ids = listed.mapTo(mutableSetOf()) { it.optionalText("id") }
            val rows = listed + chats[projectId].orEmpty().map { it.session }.filter { it.optionalText("id") !in ids }
            if (rows.isNotEmpty())
                projectSessionsAggregate[projectId] =
                    projectSessions(projectId, project.optionalText("name").orEmpty(), rows, verified = false)
        }
    }

    /** The aggregate's lists are no longer confirmed: the connection that read them is gone. */
    private fun unverifyProjectSessions() {
        projectSessionsAggregate.replaceAll { _, entry -> entry.unverified() }
        projectListReads.clear()
    }

    private fun publishProjectSessions(routeId: String) {
        if (activeHost?.routeId != routeId || state.value.selection.routeId != routeId) return
        val aggregate = projectSessionsAggregate.takeIf { projectSessionsRoute == routeId }?.toMap().orEmpty()
        if (state.value.projectSessions != aggregate) update { it.copy(projectSessions = aggregate) }
    }

    /**
     * Keeps a list the Mac just sent for [project] on [routeId] in the aggregate, the featured
     * chats and the session cache, so opening the project needs no read of its own. The cache
     * gains no entry here: with many projects, those of the projects the user visited would make
     * way. When the project's list is on screen, it shows the new rows too.
     */
    private fun applyProjectList(routeId: String, project: JsonObject, sessions: List<JsonObject>) {
        val projectId = project.text("id")
        val name = project.optionalText("name").orEmpty()
        if (projectSessionsRoute != routeId) {
            projectSessionsAggregate.clear()
            projectListReads.clear()
            projectSessionsRoute = routeId
        }
        projectSessionsAggregate[projectId] = projectSessions(projectId, name, sessions, verified = true)
        projectListReads[projectId] = generation to now()
        projectChatCandidates[projectId] =
            sortedProjectChats(sessions.map { ProjectChatSummary(projectId, name, it, verified = true) })
        val entry = CachedSessions(routeId, projectId, project, sessions, generation)
        if (cachedSessions?.let { it.routeId == routeId && it.projectId == projectId } == true) cachedSessions = entry
        else sessionCache.replace(routeId to projectId, entry)
        val current = state.value
        if (
            !current.loading && current.selection.routeId == routeId && current.selection.projectId == projectId &&
                current.project?.optionalText("id") == projectId && current.sessions != sessions
        ) update { it.copy(sessions = sessions, sessionsFresh = true) }
        publishProjectChats(routeId)
        publishProjectSessions(routeId)
    }

    /**
     * The Mac's list of [projectId] on [routeId] gained or lost a chat this app made or closed:
     * neither the session cache nor the aggregate stands in for a read of it any more.
     */
    private fun invalidateProjectList(routeId: String, projectId: String) {
        sessionCache.remove(routeId to projectId)
        if (projectSessionsRoute == routeId) projectListReads.remove(projectId)
    }

    /** [persist]: false when the caller decides itself whether the change is worth a write. */
    private fun publishProjectChats(routeId: String, persist: Boolean = true) {
        if (activeHost?.routeId != routeId || state.value.selection.routeId != routeId) return
        val chats = sortedProjectChats(projectChatCandidates.values.flatten())
        cachedProjectChats = routeId to chats
        update { it.copy(projectChats = chats) }
        if (persist) persistNavigation()
    }

    /**
     * Reads every project's list for the featured chats and the aggregate, four at a time. Skipped
     * while the latest fan-out on this connection runs or, unless [force]d, started less than
     * [PROJECT_LISTS_REFRESH_MILLIS] ago; a reconnect or a refresh by hand always reads again. The
     * reads are bound to the connection, not the selection, so opening a project keeps the replies.
     */
    private fun refreshProjectChats(routeId: String, projects: List<JsonObject>, force: Boolean = false) {
        val connection = generation
        // Rows keep their verified flag until their project's reply replaces them, so waiting and
        // running chats do not drop into Recent and back while the lists come in. A project no
        // longer shared drops out at once, even when the lists are not read again.
        val projectsById = projects.associateBy { it.text("id") }
        if (projectChatCandidates.isEmpty()) {
            cachedProjectChats?.takeIf { it.first == routeId }?.second.orEmpty()
                .groupBy { it.projectId }
                .forEach { (id, chats) -> projectChatCandidates[id] = chats }
        }
        projectChatCandidates.keys.retainAll(projectsById.keys)
        projectChatCandidates.replaceAll { id, chats ->
            val name = projectsById.getValue(id).optionalText("name").orEmpty()
            chats.map { it.copy(projectName = name) }
        }
        if (projectSessionsRoute == routeId) {
            projectSessionsAggregate.keys.retainAll(projectsById.keys)
            projectListReads.keys.retainAll(projectsById.keys)
        }
        publishProjectChats(routeId)
        publishProjectSessions(routeId)
        val last = lastProjectChatsRefreshAt
        if (
            projectChatsReadOn == connection &&
                (projectChatsJob?.isActive == true ||
                    (!force && last != null && now() - last in 0 until PROJECT_LISTS_REFRESH_MILLIS))
        ) return
        projectChatsJob?.cancel()
        lastProjectChatsRefreshAt = now()
        projectChatsReadOn = connection
        projectChatsJob = scope.launch {
            val permits = Semaphore(4)
            projects.forEach { project ->
                launch {
                    try {
                        permits.withPermit { readProjectList(routeId, project, connection) }
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

    /** Reads [project]'s list on [connection] and keeps it; returns its rows, or null when it went. */
    private suspend fun readProjectList(routeId: String, project: JsonObject, connection: Long): List<JsonObject>? {
        if (connection != generation || activeHost?.routeId != routeId) return null
        val sessions = connectionRequest("sessions.list", "projectId" to project.text("id")).array("items")
        // A reply to a connection that is gone, or to another Mac, says nothing about this one.
        if (connection != generation || activeHost?.routeId != routeId) return null
        val rows = sessions.map { withSessionTitle(withSessionStatus(withHostWorker(it))) }
        applyProjectList(routeId, project, rows)
        return rows
    }

    /** Whether any list or screen of the connected Mac holds [sessionId]. */
    private fun knowsSession(sessionId: String): Boolean {
        val current = state.value
        return current.selection.sessionId == sessionId ||
            current.sessions.any { it.optionalText("id") == sessionId } ||
            projectSessionsAggregate.values.any { entry ->
                entry.sessions.any { it.optionalText("id") == sessionId }
            } ||
            projectChatCandidates.values.any { chats -> chats.any { it.session.text("id") == sessionId } }
    }

    /**
     * A session reported a status no list holds: it started after its project was read. Reads, one
     * at a time and the most recently active project first, each list read before the report (or
     * just after it, which may still miss it) until one holds the session. A list is read again only
     * once it is [PROJECT_LISTS_REFRESH_MILLIS] old, so the job waits for lists that are younger. On
     * Projects, a waiting or running chat is featured there, so lists [ATTENTION_REFRESH_MILLIS] old
     * are read again. On a project's Sessions list, that list is read at once, and again once it is
     * [SESSION_REFRESH_INTERVAL_MILLIS] old: a chat started on the Mac most likely joins it. A
     * session no list held after all of them were read is skipped for
     * [MISSING_SESSION_RETRY_MILLIS] on this connection: a subagent child before it registers, or a
     * chat in a folder that is not shared, would otherwise read every list at each status.
     */
    private fun refreshUnknownSession(sessionId: String, status: String) {
        val current = state.value
        val routeId = current.selection.routeId ?: return
        if (status == "offline" || !current.connected || activeHost?.routeId != routeId ||
            current.projects.isEmpty() || knowsSession(sessionId)
        ) return
        val connection = generation
        val missed = missingSessions[sessionId]
        if (missed != null && missed.first == connection && now() - missed.second in 0 until MISSING_SESSION_RETRY_MILLIS)
            return
        if (current.selection.projectId != null && current.selection.sessionId == null) revalidateSessions()
        val attention = current.selection.projectId == null && status in setOf("waiting", "running")
        if (sessionId !in unknownSessions && unknownSessions.size >= 32) return
        unknownSessions[sessionId] = now()
        // A running job reads the new session's lists too, unless the session now needs the
        // quicker reads of the Projects page.
        if (attentionRefreshJob?.isActive == true && (unknownSessionsAttention || !attention)) return
        attentionRefreshJob?.cancel()
        unknownSessionsAttention = attention
        attentionRefreshJob = scope.launch { readUnknownSessions(routeId, connection, attention) }
    }

    private suspend fun readUnknownSessions(routeId: String, connection: Long, attention: Boolean) {
        val minAge = if (attention) ATTENTION_REFRESH_MILLIS else PROJECT_LISTS_REFRESH_MILLIS
        /** The Sessions list on screen is read again sooner than the other projects' lists. */
        fun minAge(projectId: String) =
            state.value.selection
                .takeIf { it.projectId == projectId && it.sessionId == null }
                ?.let { minOf(minAge, SESSION_REFRESH_INTERVAL_MILLIS) }
                ?: minAge
        fun gone() = connection != generation || activeHost?.routeId != routeId || !state.value.connected ||
            state.value.selection.routeId != routeId
        fun pending(): Boolean {
            unknownSessions.keys.removeAll(::knowsSession)
            return unknownSessions.isNotEmpty()
        }
        delay(300)
        withTimeoutOrNull(1200) { projectChatsJob?.join() }
        val failed = mutableSetOf<String>()
        while (true) {
            if (gone() || !pending()) return
            // The Mac may report a new chat before its list holds it: a list read just after the
            // report counts as read before it.
            val time = now()
            val since = unknownSessions.values.max().coerceAtMost(time) + ATTENTION_REFRESH_MILLIS
            /** When [project]'s list may be read again, or null when it was read since the report. */
            fun readyAt(project: JsonObject): Long? {
                val id = project.text("id")
                if (id in failed) return null
                val read = projectListReads[id]
                    ?.takeIf { projectSessionsRoute == routeId && it.first == connection }
                    // A clock set back makes every list stale.
                    ?.takeIf { it.second <= time }
                    ?: return since
                return if (read.second >= since) null else maxOf(read.second + minAge(id), since)
            }
            val due = state.value.projects.mapNotNull { project -> readyAt(project)?.let { project to it } }
            if (due.isEmpty()) break
            val wait = due.minOf { it.second } - time
            if (wait > 0) {
                delay(wait)
                continue
            }
            // A new session most likely joins a project that was active lately.
            val project = due.filter { it.second <= time }.maxBy { (project, _) ->
                projectSessionsAggregate[project.text("id")]?.sessions
                    ?.maxOfOrNull { it["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L }
                    ?: Long.MAX_VALUE
            }.first
            val rows =
                try {
                    readProjectList(routeId, project, connection)
                } catch (e: CancellationException) {
                    throw e
                } catch (swallowed: Exception) {
                    Diagnostics.caught("repo.refreshUnknownSession", swallowed)
                    failed += project.text("id")
                    continue
                } ?: return
            // A list over the aggregate's cap may hold it in a row the aggregate leaves out.
            rows.forEach { row -> row.optionalText("id")?.let(unknownSessions::remove) }
        }
        if (gone()) return
        // Every list was read since the report and none holds these sessions.
        val time = now()
        missingSessions.values.removeAll { (read, at) ->
            read != connection || time - at !in 0 until MISSING_SESSION_RETRY_MILLIS
        }
        if (failed.isEmpty()) unknownSessions.keys.forEach { missingSessions[it] = connection to time }
        unknownSessions.clear()
    }

    private fun cacheCurrentScreen(immediate: Boolean = false) {
        if (rememberCurrentScreen()) persistNavigation(immediate)
    }

    /**
     * Keeps what is on screen in the in-memory caches the snapshot is built from; returns false
     * when the screen is not the connected, loaded active host's.
     */
    private fun rememberCurrentScreen(): Boolean {
        val current = state.value
        val routeId = current.selection.routeId ?: return false
        if (!current.connected || current.loading || activeHost?.routeId != routeId) return false
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
            // What is on screen was read when its cache entry was, if at all.
            val readOn = sessionCache[routeId to projectId]?.readOn
            cachedSessions = CachedSessions(routeId, projectId, project, sessions, readOn)
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
        return true
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
                    // The project lists' fan-out read more projects than the cache keeps.
                    projectSessionsAggregate[projectId]
                        ?.takeIf { projectSessionsRoute == host.routeId && it.sessions.isNotEmpty() }
                        ?.let { return@run CachedSessions(host.routeId, projectId, project, it.sessions) }
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
                session == null ||
                !hostOwned(state.value, session) ||
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
                        invalidateProjectList(routeId, projectId)
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

    /**
     * The chat [selection] as the caches last held it, with its stored draft. A history session
     * without a cached chat still gets its list row, so the preview can say it continues as a copy.
     */
    override fun peekDraftText(selection: RemoteSelection): String? {
        val route = selection.routeId ?: return null
        val session = selection.sessionId ?: return null
        if (!draftsAvailable || route in removedRoutes || state.value.hosts.none { it.routeId == route }) return null
        return drafts[DraftKey(route, session)]?.text.orEmpty()
    }

    override fun peekCachedChat(selection: RemoteSelection): ChatPreview? {
        val routeId = selection.routeId ?: return null
        val projectId = selection.projectId ?: return null
        val sessionId = selection.sessionId ?: return null
        val chat = chatCache[selection]
        val row =
            chat?.session
                ?: sessionCache[routeId to projectId]?.sessions?.find { it.optionalText("id") == sessionId }
        if (chat == null && row?.optionalText("origin") != "history") return null
        return ChatPreview(
            session = row?.let(::withSessionTitle),
            messages = chat?.messages.orEmpty(),
            draft = drafts[DraftKey(routeId, sessionId)]?.text.orEmpty(),
        )
    }

    private var preparedSource: DraftKey? = null

    override fun cancelSelection() {
        preparedSource = null
        update { it.copy(preparedText = null) }
        cacheCurrentScreen(immediate = true)
        // The project lists' reads are bound to the connection, not the selection: a project
        // opened from Projects keeps the replies still on their way.
        cancelRecovery()
        importJob?.cancel()
        if (state.value.attachmentProgress != null) sendJob?.cancel()
        stopToolOutput()
        sessionRefreshJob?.cancel()
        selectionEpoch++
        taskActivationEpoch = null
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
                questionBlocks = emptyMap(),
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

    /** Notes the open follow-ups of [routeId]'s chats, which a clean switch leaves as they are. */
    private fun rememberFollowUpsToRecheck(routeId: String?) {
        if (routeId == null) return
        drafts.forEach { (key, draft) ->
            if (key.routeId != routeId) return@forEach
            val open = draft.followUps.filter { it.status in setOf("pending", "accepted") }.map { it.requestId }
            if (open.isNotEmpty()) followUpsToRecheck[key] = open.toSet()
        }
    }

    /**
     * Checks the follow-ups [rememberFollowUpsToRecheck] noted for [sessionId], after its snapshot
     * applied the receipts it holds: those that are still open are uncertain, which can be dismissed.
     */
    private fun recheckFollowUps(sessionId: String) {
        val key = DraftKey(activeHost?.routeId ?: return, sessionId)
        val noted = followUpsToRecheck.remove(key) ?: return
        val draft = drafts[key] ?: return
        val entries = draft.followUps.map { entry ->
            if (entry.requestId in noted && entry.status in setOf("pending", "accepted")) entry.copy(status = "uncertain")
            else entry
        }
        if (entries == draft.followUps) return
        drafts[key] = draft.copy(followUps = entries)
        persist()
        if (currentDraftKey() == key) update { it.copy(followUps = entries) }
    }

    private suspend fun connect(host: PairedHost, epoch: Long, closesCleanly: Boolean = false) {
        if (activeHost?.routeId != host.routeId || !state.value.connected) {
            if (activeHost?.routeId != host.routeId || ready?.isActive != true) openConnection(host, closesCleanly = closesCleanly)
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
        // The first activation on an early connection registers it; ready() left that to it.
        if (earlyConnection) {
            earlyConnection = false
            registerPush()
        }
    }

    /**
     * Starts a connection to [host] in place of the current one, without waiting for it; [connect]
     * waits for it to become ready. [early]: [connectEarly] opens it ahead of its activation.
     */
    private suspend fun openConnection(host: PairedHost, early: Boolean = false, closesCleanly: Boolean = false) {
        earlyConnection = early
        val previousRoute = activeHost?.routeId ?: timedOutRoute
        timedOutRoute = null
        // A switch from a connected Mac closes its connection on purpose: that Mac's open follow-ups
        // stay as they are, and the next snapshot of their chat checks them ([recheckFollowUps]). A
        // connection that was already lost or timed out does not close cleanly, so its follow-ups
        // are marked uncertain as before.
        if (previousRoute != host.routeId) {
            if (closesCleanly) rememberFollowUpsToRecheck(previousRoute)
            else if (markPendingFollowUpsUncertain(previousRoute)) persistNow()
        }
        // Its byte and bitmap caches are keyed by route, so they stay for when it is selected again.
        if (previousRoute != host.routeId) {
            offlineSince = null
            cancelQuietReconnect()
            resetReconnect()
            clearNavigationCache()
        }
        cachedProjectChats = cachedProjectChats?.takeIf { it.first == host.routeId }
            ?.let { it.first to it.second.map { chat -> chat.copy(verified = false) } }
        projectChatCandidates.replaceAll { _, chats ->
            chats.map { it.copy(verified = false) }
        }
        projectChatsJob?.cancel()
        attentionRefreshJob?.cancel()
        unknownSessions.clear()
        missingSessions.clear()
        unverifyProjectSessions()
        // A connection replaced on purpose is no failure.
        connectionEnded()
        generation++
        clearLateAcknowledgements()
        failConnectionRequests()
        cloneStatusJob?.cancel()
        providerStatusJob?.cancel()
        providerVersion++
        earlyProviderUpdates.clear()
        folderVersion++
        folderSession++
        ticketsLoader.connectionLost()
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
                suggestion = null,
                pullRequest = null,
                compactionRequesting = false,
                // A finished download stays readable; a running one stopped above.
                toolOutput = it.toolOutput?.takeIf { output -> keep && output.text != null },
                // A login goes on on the Mac; the same host recovers it once it is reachable.
                providerAuth =
                    if (previousRoute != host.routeId) ProviderAuthState()
                    else it.providerAuth.offline(),
                projectSessions = it.projectSessions.mapValues { (_, entry) -> entry.unverified() },
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

    private suspend fun request(
        type: String,
        epoch: Long,
        vararg fields: Pair<String, Any?>,
        draft: String? = null,
        requestId: String = Wire.random(),
        onSent: (() -> Unit)? = null,
        timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
        // Runs once the request went out, for one that follows it without waiting for its reply.
        thenSend: (() -> Unit)? = null,
        onLateAccepted: ((JsonObject) -> Unit)? = null,
    ): JsonObject {
        checkEpoch(epoch)
        check(state.value.connected)
        // Older hosts accept this as an opaque request ID and return ordinary projects.
        val id = if (type == "projects.list") "$capabilityRoute$requestId" else sessionsListRequestId(type, requestId)
        val payload = Wire.objectOf("type" to type, "requestId" to id, *fields)
        recordIssuedRequest(id)
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
            thenSend?.invoke()
            val result =
                withTimeoutOrNull(timeoutMillis) { completion.await() }
                    ?: run {
                        outcome = "timeout"
                        if (type == "session.prompt" && onLateAccepted != null &&
                            connection == generation && !lateAcknowledgementsDisabled && id !in ambiguousRequestIds) {
                            lateAcknowledgements.entries.removeAll { Diagnostics.now() - it.value.at >= 120_000 }
                            while (lateAcknowledgements.size >= 64) lateAcknowledgements.remove(lateAcknowledgements.keys.first())
                            payload.optionalText("sessionId")?.let { sessionId ->
                                lateAcknowledgements[id] = LateAcknowledgement(Diagnostics.now(), sessionId, onLateAccepted)
                            }
                        }
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
                val merged = routeCapabilities.filter {
                    it in advertised && ((it != SESSION_TASKS_CAPABILITY && it != HOST_WORKER_V2_CAPABILITY) || id.startsWith(CAPABILITIES_V2_ROUTE))
                }
                val hadTasks = canReadTasks()
                if (id.startsWith(CAPABILITIES_V2_ROUTE) && SESSION_TASKS_CAPABILITY in merged) tasksCapabilityKnown = true
                val hadTickets = TICKETS_CAPABILITY in state.value.capabilities
                if (merged.isNotEmpty()) {
                    // Capabilities merge in additively as later projects.list replies arrive; a list
                    // lease that only now becomes possible starts leasing at once, not at the next
                    // session change or reconnect.
                    val hadLease = canLeaseJobsList(state.value)
                    update { it.copy(capabilities = it.capabilities + merged) }
                    if (!hadLease && canLeaseJobsList(state.value)) jobsController.refresh()
                }
                update { it.copy(capabilitiesKnown = true) }
                if (!hadTasks && canReadTasks()) {
                    taskRefreshPending = true
                    drainTaskRefresh()
                }
                if (PROJECT_OPEN_CAPABILITY in merged) resumeClone()
                if (STREAM_CAPABILITY in merged && connection == generation && streamGeneration != generation) streamReady()
                if (PROVIDER_AUTH_CAPABILITY in merged) resumeProviderAuth()
                if (TICKETS_CAPABILITY in merged && !hadTickets) ticketsLoader.refresh(force = false)
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

    /**
     * Runs [block] beside the caller, started at once so its request goes out before the caller's
     * next one. Its result or failure waits for the caller to take; it never fails the caller on
     * its own, and the caller cancels it when it does not take it.
     */
    private fun <T> CoroutineScope.alongside(block: suspend () -> T): Deferred<Result<T>> =
        async(start = CoroutineStart.UNDISPATCHED) {
            try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun canReadTasks() = state.value.capabilitiesKnown && tasksCapabilityKnown && SESSION_TASKS_CAPABILITY in state.value.capabilities

    private fun drainTaskRefresh() {
        val current = timeline ?: return
        val selected = state.value.selection
        if (!taskRefreshPending || !canReadTasks() || !state.value.connected ||
            taskActivationEpoch == selectionEpoch || resynchronizing || snapshotPending === current ||
            selected.sessionId != current.sessionId || selected.routeId != activeHost?.routeId
        ) return
        taskRefreshPending = false
        snapshotAsync()
    }

    private fun snapshotFields(sessionId: String, includeTasks: Boolean): Array<Pair<String, Any?>> =
        if (includeTasks) arrayOf("sessionId" to sessionId, "includeTasks" to true)
        else arrayOf("sessionId" to sessionId)

    /**
     * Asks for the snapshot of [sessionId] on [routeId] before select() makes its Timeline; see
     * [HeldSnapshot]. It resumes from the Timeline that select() will continue, when there is one.
     */
    private fun CoroutineScope.holdSnapshot(routeId: String, sessionId: String, epoch: Long): HeldSnapshot {
        val held = HeldSnapshot(sessionId, canReadTasks(), resumeSource(routeId, sessionId)?.resumeBase())
        // The focus names the chat before its read goes out, so every event of it that the host
        // emits after the reply's revision reaches this phone. Another chat on screen detaches;
        // should this activation not select the new one, its end refocuses and resumes it.
        syncFocus(desiredFocus(routeId, sessionId))
        // Set first: an event that follows the request is kept for the Timeline.
        heldSnapshot = held
        held.reply =
            alongside {
                request(
                    "session.snapshot",
                    epoch,
                    *snapshotFields(sessionId, held.includeTasks),
                    requestId = firstPageRequestId(held.base),
                )
            }
        return held
    }

    /**
     * The Timeline select() continues for [sessionId] on [routeId]: the one on screen for a
     * reconnect of the same chat, or one kept since the chat was left. Null when none can resume.
     */
    private fun resumeSource(routeId: String, sessionId: String): Timeline? {
        val selected = state.value.selection
        val shown = timeline?.takeIf { selected.routeId == routeId && selected.sessionId == sessionId && it.sessionId == sessionId }
        val source = shown ?: detachedTimelines[DraftKey(routeId, sessionId)]?.timeline
        return source?.takeIf { it.sessionId == sessionId && it.resumeBase() != null }
    }

    /** The request ID of a first-page `session.snapshot`: the resume form with [base], else the fresh form. */
    private fun firstPageRequestId(base: ResumeBase?): String =
        if (base == null) freshRequestId() else resumeRequestId(base.epoch, base.revision)

    /**
     * Keeps [leaving], the Timeline of the chat [previous] that the selection leaves, to resume
     * from when the chat is selected again, with the tasks on screen. Only a Timeline that can
     * resume is kept; past [DETACHED_TIMELINES] for its Mac, the one left longest ago goes.
     */
    private fun detachTimeline(previous: RemoteSelection, leaving: Timeline?) {
        val routeId = previous.routeId ?: return
        val sessionId = previous.sessionId ?: return
        if (leaving == null || leaving.sessionId != sessionId || leaving.resumeBase() == null) return
        leaving.detached = true
        val key = DraftKey(routeId, sessionId)
        detachedTimelines.remove(key)
        detachedTimelines[key] = DetachedTimeline(leaving, state.value.tasks)
        val ofRoute = detachedTimelines.keys.filter { it.routeId == routeId }
        ofRoute.take(maxOf(0, ofRoute.size - DETACHED_TIMELINES)).forEach { detachedTimelines.remove(it) }
    }

    /**
     * The sessions whose upserts this connection should get: [sessionId] on [routeId] (the selected
     * chat by default) while the app is in the foreground, while a live run is kept for it in the
     * background, or for [BACKGROUND_FOCUS_GRACE_MILLIS] after the app went to the background;
     * none on the lists, for another Mac, during a push wake window, and in the background
     * otherwise.
     */
    private fun desiredFocus(
        routeId: String? = state.value.selection.routeId,
        sessionId: String? = state.value.selection.sessionId,
    ): List<String> {
        if (routeId == null || sessionId == null || routeId != activeHost?.routeId || !opaqueId(sessionId)) return emptyList()
        if (!inBackground) return listOf(sessionId)
        // A connection a push woke streams nothing: the push's upgrade needs requests only. Only
        // a live run the service keeps in the background still follows its chat.
        if (pushWakeRoute != null && !liveRunKept()) return emptyList()
        if (liveRunTarget(state.value) == LiveRunTarget(routeId, sessionId)) return listOf(sessionId)
        return if (backgroundGraceOver) emptyList() else listOf(sessionId)
    }

    /**
     * Whether the host sends this connection the upserts of [sessionId]: it took no focus yet (a
     * legacy connection, or one before its first `session.focus`), or the last one names it.
     */
    private fun inSentFocus(sessionId: String) =
        streamGeneration != generation || focusSentOn != generation || focusSent?.contains(sessionId) == true

    /** The Timeline of the chat on screen, or null on the lists. */
    private fun shownTimeline(): Timeline? {
        val selected = state.value.selection
        return timeline?.takeIf { selected.routeId == activeHost?.routeId && it.sessionId == selected.sessionId }
    }

    /**
     * Sends `session.focus` with [target] when this connection advertised `remote.stream.v1` and
     * did not get that focus last; never to a host without the capability. The focus is not held
     * back by [MAX_CONNECTION_REQUESTS]: it is one small idempotent command per change, and a
     * Timeline that the host already filters must not miss it.
     *
     * [shown], the Timeline on screen, detaches when the focus stops naming its session, so the gaps
     * the host leaves in its stream never ask for a snapshot. One that waits for a reply (its first
     * page or a resume) cannot detach, since a detached Timeline could not take it: its focus lapses
     * instead ([Timeline.focusLapsed]). The events it applies after the reply may carry `after` over
     * upserts that focus filtered, so once the reply is in (see [snapshot]) it detaches if the
     * session is still out of focus, and otherwise, with [readLapsed], reads a fresh first page
     * with its events held.
     *
     * When the focus names a detached [shown] again, it stops being detached and resumes: it holds
     * its session's events from before the focus goes out, and the resume request follows the
     * focus, so every event the host emitted meanwhile is applied once, after the reply.
     */
    private fun syncFocus(target: List<String> = desiredFocus(), shown: Timeline? = shownTimeline(), readLapsed: Boolean = true) {
        if (!state.value.connected || streamGeneration != generation) return
        val refocus = shown?.takeIf { it.detached && it.sessionId in target }
        if (refocus != null) {
            refocus.detached = false
            if (refocus.resumeBase() != null) refocus.awaitReply()
            // Without a base the fresh snapshot follows; a gap meanwhile does not ask for another.
            else snapshotPending = refocus
        }
        if (focusSentOn != generation || focusSent != target) sendFocus(target)
        var reread = false
        if (shown != null && shown.sessionId !in target) {
            if (!shown.awaitingReply && snapshotPending !== shown) shown.detached = true else shown.focusLapsed = true
        } else if (
            readLapsed && shown != null && refocus == null && shown.focusLapsed && !shown.detached && !shown.awaitingReply &&
                snapshotPending !== shown
        ) {
            snapshotPending = shown
            reread = true
        }
        if (refocus != null) snapshotAsync(intent = if (refocus.resumeBase() != null) SnapshotIntent.RESUME else SnapshotIntent.FRESH)
        // A pending snapshot reads it afresh as well; see [snapshot].
        else if (reread) snapshotAsync()
    }

    private fun sendFocus(target: List<String>) {
        focusSent = target
        focusSentOn = generation
        val payload = sessionFocusPayload(Wire.random(), target)
        // Undispatched, so the focus is on the wire before whatever the caller sends next.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                sessionFocusResult(awaitConnectionReply(payload, REQUEST_TIMEOUT_MILLIS, record = true, liveness = true))
            } catch (_: CancellationException) {
            } catch (_: RemoteRequestException) {
                // A lost connection sends it again on the next one; a stale one already reconnects.
            } catch (swallowed: Exception) {
                Diagnostics.caught("repo.focus", swallowed)
            }
        }
    }

    /**
     * This connection advertised `remote.stream.v1`: it gets its focus, and pings start. During an
     * activation the focus waits for its select(), which names the chat it opens; until then the
     * connection gets every event.
     */
    private fun streamReady() {
        streamGeneration = generation
        if (!state.value.loading) syncFocus()
        startPings(generation)
    }

    /**
     * Sends `remote.ping` once now, then on [connection] every [PING_INTERVAL_MILLIS] while the app
     * is in the foreground, until the connection ends. Each round trip, from the send to the reply
     * on the main thread, is recorded in [Diagnostics]; a timeout is recorded and ends nothing,
     * since a slow link may delay a whole message while the socket is fine (OkHttp's ping and the
     * liveness reads judge the socket).
     */
    private fun startPings(connection: Long) {
        pingJob?.cancel()
        ping()
        pingJob =
            scope.launch {
                while (true) {
                    delay(PING_INTERVAL_MILLIS)
                    if (connection != generation || !state.value.connected) return@launch
                    if (!inBackground) ping()
                }
            }
    }

    private fun ping() {
        if (!connectionRequestsOpen()) return
        val payload = remotePingPayload(Wire.random())
        val sent = Diagnostics.now()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val relayRtt = pongResult(awaitConnectionReply(payload, PING_TIMEOUT_MILLIS, record = true))
                Diagnostics.rtt(Diagnostics.now() - sent, relayRtt)
            } catch (_: CancellationException) {
            } catch (e: RemoteRequestException) {
                // Recorded with its round trip; a ping never ends the connection.
                if (e.kind == RemoteRequestException.Kind.TIMEOUT) Diagnostics.count(Diagnostics.Counter.PING_TIMEOUTS)
            } catch (swallowed: Exception) {
                Diagnostics.caught("repo.ping", swallowed)
            }
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
                                ?: bashOutputEvent(payload)?.let(::receiveBashOutput)
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
            connectionCompletions.remove(id)
                ?: run {
                    if (requests.take(id, selectionEpoch) == null) {
                        val late = lateAcknowledgements[id] ?: return
                        if (lateAcknowledgementsDisabled || id in ambiguousRequestIds || Diagnostics.now() - late.at >= 120_000) {
                            lateAcknowledgements.remove(id)
                            return
                        }
                        try {
                            val data = payload.obj("data")
                            if (payload.flag("ok") && data.text("kind") == "accepted" &&
                                data.text("sessionId") == late.sessionId) {
                                lateAcknowledgements.remove(id)
                                late.accept(data)
                            }
                        } catch (_: Exception) { /* Ignore malformed late results. */ }
                        return
                    }
                    completions.remove(id) ?: return
                }
        try {
            if (payload.flag("ok")) completion.complete(payload.obj("data"))
            else
                payload.obj("error").let { error ->
                    completion.completeExceptionally(
                        RemoteRequestException(
                            error.text("code"),
                            error["details"] as? JsonObject,
                            hostMessage(error),
                            reason = (error["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                        )
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
        val forTimeline = timelineForm(payload)
        val held = heldSnapshot
        if (held != null && (payload["sessionId"] as? JsonPrimitive)?.content == held.sessionId) {
            // Kept for the Timeline the held snapshot is for, which select() has not made yet.
            held.events.addLast(forTimeline)
            while (held.events.size > MAX_HELD_EVENTS) held.events.removeFirst()
        } else timeline?.let { current -> timelineEvent(current, forTimeline) }
        // A live run that ends in the background lets the focus narrow.
        if (inBackground && payload.optionalText("kind") == "session.status" && !state.value.loading) syncFocus()
    }

    private fun timelineEvent(current: Timeline, event: JsonObject) {
        // Out of focus: the host leaves its upserts out, so its gaps mean nothing until the refocus
        // resumes it. Its side effects ran on arrival, so the resume does not run them again.
        if (current.detached) {
            current.heardWhileDetached(event)
            return
        }
        try {
            // The snapshot select() set up takes in what this buffered; asking for another
            // one would only be discarded. A gap can be filled from the host's journal;
            // snapshot.required needs the fresh state.
            val need = current.eventNeed(event)
            if (need != SnapshotNeed.NONE) {
                update { it.copy(tasks = staleTasks(it.tasks)) }
                if (!resynchronizing && snapshotPending !== current)
                    snapshotAsync(intent = if (need == SnapshotNeed.GAP) SnapshotIntent.RESUME else SnapshotIntent.FRESH)
            }
        } catch (malformed: Exception) {
            Diagnostics.caught("repo.receive.timeline", malformed)
            current.unknownKindSeen = true
        }
        resyncAfterUnknownEvents(current)
        // Another session's event cannot change this chat.
        if ((event["sessionId"] as? JsonPrimitive)?.content != current.sessionId) return
        if ((event["kind"] as? JsonPrimitive)?.content == "message.upsert") publishStreamingTimeline(current)
        else publishTimeline()
    }

    /**
     * Publishes the Timeline for a streaming upsert: at once when the last publish is at least
     * [TIMELINE_PUBLISH_MILLIS] ago, otherwise one trailing publish [TIMELINE_PUBLISH_MILLIS] after
     * it. Further upserts meanwhile ride on that one; [publishTimeline] cancels it.
     */
    private fun publishStreamingTimeline(current: Timeline) {
        val since = now() - lastTimelinePublishAt
        // A clock set back counts as long ago.
        if (lastTimelinePublishAt == Long.MIN_VALUE || since !in 0 until TIMELINE_PUBLISH_MILLIS) {
            publishTimeline()
            return
        }
        if (trailingPublish?.isActive == true) return
        trailingPublish =
            scope.launch {
                delay(TIMELINE_PUBLISH_MILLIS - since)
                trailingPublish = null
                // A selection change or a disconnect since made this Timeline obsolete.
                if (timeline === current) publishTimeline()
            }
    }

    /**
     * Ends [held]. The events that waited go to the Timeline of its chat, if there is one: the one
     * select() just made, which buffers them for the snapshot that follows, or the chat still on
     * screen when the activation ended without it, which takes them as they would have come.
     */
    private fun releaseHeldSnapshot(held: HeldSnapshot) {
        if (heldSnapshot !== held) return
        heldSnapshot = null
        val current = timeline?.takeIf { it.sessionId == held.sessionId }
        while (held.events.isNotEmpty()) {
            val event = held.events.removeFirst()
            if (current != null && current === timeline) timelineEvent(current, event)
        }
    }

    /**
     * The parts of an event outside the timeline: statuses, titles, receipts, notices and the
     * context usage a finished compaction changed.
     */
    private fun eventEffects(payload: JsonObject) {
        if (payload.text("kind") == "session.status") updateSessionStatus(payload)
        // Live or replayed, a finished compaction of the open chat makes its context usage stale.
        if (payload.text("kind") == "session.compaction" &&
            payload.optionalText("sessionId") == state.value.selection.sessionId &&
            payload.optionalText("state") == "done"
        ) {
            update { it.copy(contextUsage = null) }
            refreshContextUsage()
        }
        if (payload.text("kind") == "message.upsert" &&
            payload.text("sessionId") == state.value.selection.sessionId &&
            // A held list lease already gets session.jobs.changed for this; older hosts need the hint.
            !canLeaseJobsList(state.value) &&
            (payload["message"] as? JsonObject)?.let {
                it.optionalText("role") == "tool" && isJobChangingTool(it.optionalText("toolName"))
            } == true
        ) jobsController.refreshSoon()
        // The open Changes view follows the agent's edits.
        if (payload.text("kind") == "message.upsert" &&
            payload.text("sessionId") == state.value.selection.sessionId &&
            (payload["message"] as? JsonObject)?.let(::mayChangeFiles) == true
        ) changesLoader.reloadSoon()
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
                    submittedDelivery = null,
                    followUps = latest.followUps + PendingFollowUp(id, latest.submittedText, "delivered", delivery),
                )
                drafts[key] = confirmed
                journalOwners.remove(key)
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
                if (projectSessionsRoute == routeId) {
                    projectSessionsAggregate.replaceAll { _, entry -> entry.patched(::renamed) }
                    publishProjectSessions(routeId)
                }
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

    /**
     * Forgets the revisions of [sessionId]'s last status and title events, so the events of a new
     * journal epoch, which count from the start again, reach the lists.
     */
    private fun forgetListRevisions(sessionId: String) {
        sessionStatusRevisions.remove(sessionId)
        sessionTitleRevisions.remove(sessionId)
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
        // A finished run may have changed files the open Changes view shows.
        if (id == state.value.selection.sessionId && (known ?: state.value.status) == "running" && status == "idle")
            changesLoader.reloadSoon()
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
                refreshUnknownSession(id, status)
                projectChatCandidates.replaceAll { _, chats ->
                    chats.map { item ->
                        if (item.session.text("id") == id)
                            item.copy(session = withSessionStatus(item.session), verified = true)
                        else item
                    }
                }
                publishProjectChats(routeId, persist = false)
                if (projectSessionsRoute == routeId) {
                    projectSessionsAggregate.replaceAll { _, entry ->
                        if (entry.sessions.none { it.optionalText("id") == id }) entry
                        else entry.patched { if (it.optionalText("id") == id) withSessionStatus(it) else it }
                    }
                    publishProjectSessions(routeId)
                }
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
            // A repeated status of another session changes nothing the snapshot keeps.
            if (status != known || id == state.value.selection.sessionId) persistNavigation()
        }
    }

    private suspend fun listSessions(projectId: String, epoch: Long): List<JsonObject> {
        sessionStatusOverrides.clear()
        sessionTitleOverrides.clear()
        return request("sessions.list", epoch, "projectId" to projectId)
            .array("items")
            .map { withSessionTitle(withSessionStatus(withHostWorker(it))) }
    }

    /**
     * [persist]: the chat's structure changed (a snapshot), so the navigation snapshot is written
     * as for a selection change. An event only marks it dirty, unless it starts or ends a run.
     */
    private fun publishTimeline(persist: Boolean = false): Unit = Diagnostics.trace("PocketPi.publishTimeline") {
        trailingPublish?.cancel()
        trailingPublish = null
        lastTimelinePublishAt = now()
        val t = timeline ?: return@trace
        val previous = state.value.status
        if (t.status != previous) state.value.selection.sessionId?.let(::dropStaleChildControl)
        val open = t.questions.map { it.text("id") }.toSet()
        update {
            // Before its first snapshot the Timeline holds no messages yet: what is on screen (a
            // cached or the last-known chat) stays until the snapshot replaces it.
            if (!t.hasSnapshot) it.copy(
                status = t.status,
                compaction = t.compaction,
                suggestion = t.suggestion,
                pullRequest = t.pullRequest,
                pendingSend = settledSend(it.pendingSend, it.messages),
            )
            else
                it.copy(
                    messages = t.messages,
                    questions = t.questions,
                    // A question that closed (here or elsewhere) is no longer being answered.
                    answering = it.answering.filter { id -> id in open }.toSet(),
                    questionBlocks = it.questionBlocks.filterKeys { id -> id in open },
                    status = t.status,
                    nextCursor = t.nextCursor,
                    compaction = t.compaction,
                    suggestion = t.suggestion,
                    pullRequest = t.pullRequest,
                    pendingSend = settledSend(it.pendingSend, t.messages),
                )
        }
        val runEdge = t.status != previous && (t.status == "running" || previous == "running")
        if (persist || runEdge) cacheCurrentScreen() else if (rememberCurrentScreen()) markNavigationDirty()
    }

    /**
     * [timeoutMillis] lets a probe give up sooner; a timeout with no frame meanwhile reconnects.
     * [held] is the reply of a request already sent for this Timeline's session, taken instead of
     * a new one; [heldBase] is the resume base that request named. [intent] picks the request
     * form of a first page. With [dropOlderPages], the first page replaces the older pages on
     * screen (the `stale_cursor` recovery); [retryStaleCursor] lets an older page recover once.
     * A first page that names a resume base, or replaces the older pages, makes the Timeline hold
     * its session's events until the reply; see [Timeline.awaitingReply].
     */
    private suspend fun snapshot(
        epoch: Long,
        older: Boolean = false,
        timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
        held: Deferred<Result<JsonObject>>? = null,
        heldIncludesTasks: Boolean = false,
        heldBase: ResumeBase? = null,
        intent: SnapshotIntent = SnapshotIntent.FRESH,
        dropOlderPages: Boolean = false,
        retryStaleCursor: Boolean = true,
    ) {
        val current = timeline ?: return
        val cursor = if (older) current.nextCursor ?: return else null
        // Cleared before anything else: activation relies on it once the snapshot started.
        if (snapshotPending === current) snapshotPending = null
        resynchronizing = true
        if (!older) update { it.copy(tasks = staleTasks(it.tasks)) }
        var includedTasks = if (held != null && cursor == null) heldIncludesTasks else !older && canReadTasks()
        // A Timeline whose focus lapsed takes a fresh first page read with its session in focus
        // again, its events held, in place of what it applied meanwhile; see [syncFocus].
        val reread = !older && held == null && current.focusLapsed && inSentFocus(current.sessionId)
        // The resume base the request names; only a request with one may be answered by a replay.
        var base =
            when {
                reread -> null
                cursor != null -> null
                held != null -> heldBase
                intent == SnapshotIntent.RESUME -> current.resumeBase()
                else -> null
            }
        // The reply did not fit the Timeline, or an older page's cursor expired.
        var refetch = false
        var staleCursor = false
        // This call made the Timeline wait, so only it ends the wait; another read ending meanwhile
        // without a first page for it (an older page, a failed fresh one) leaves the events held.
        var awaited = false
        // The request was answered and its reply handled; otherwise the exception propagates.
        var answered = false
        // The first page the reread asked for replaced what the Timeline showed.
        var replaced = false
        var cancelled = false
        // A drop ends this read with the connection already gone, though `connected` turns false
        // only after the awaiter ran on an immediate dispatcher; see [queuedReadFailed].
        val connection = generation
        // Which first page this is; a later one, scheduled or running, owns the queued prompt.
        val firstPage = if (older) -1L else ++firstPageReads
        try {
            // Cleared as the request goes out: a focus that excludes the session again while it is
            // awaited lapses it again.
            if (reread) current.focusLapsed = false
            // Live events of the session wait for the reply from here on (a held request's
            // Timeline already waits since select() made it). The `stale_cursor` recovery holds
            // them too: a live event applied before its reply would make the page outdated, so its
            // cursor, which the retry needs, would not be taken.
            if (base != null || dropOlderPages || reread) {
                current.awaitReply()
                awaited = true
            }
            val data =
                if (held != null && cursor == null)
                    held.await().getOrElse { failed ->
                        // It was read before the lists or the open confirmed the chat, so a host
                        // error may not hold any more: it is asked once again. A reply that never
                        // came fails as any other would.
                        if (failed.requestKind != RemoteRequestException.Kind.HOST_ERROR) throw failed
                        includedTasks = canReadTasks()
                        base = if (heldBase != null) current.resumeBase() else null
                        if (base != null) {
                            current.awaitReply()
                            awaited = true
                        }
                        request(
                            "session.snapshot",
                            epoch,
                            *snapshotFields(current.sessionId, includedTasks),
                            requestId = firstPageRequestId(base),
                            timeoutMillis = timeoutMillis,
                        )
                    }
                else if (cursor == null)
                    request(
                        "session.snapshot",
                        epoch,
                        *snapshotFields(current.sessionId, includedTasks),
                        requestId = firstPageRequestId(base),
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
            if (!older && data.optionalText("kind") == "session.events") {
                // A host answers a replay only to a request that named a base.
                checkNotNull(base)
                Diagnostics.count(Diagnostics.Counter.RESUMES_REPLAYED)
                refetch = !applyReplay(current, sessionEventsReply(data))
            } else {
                // A resume the host could not serve from its journal comes back as a snapshot.
                if (!older && base != null) Diagnostics.count(Diagnostics.Counter.RESUMES_SNAPSHOT)
                val hadSnapshot = current.hasSnapshot
                val previousEpoch = current.journalEpoch
                val accepted = current.snapshot(data, older, keepOlder = !dropOlderPages) && !older
                // A first page of another journal epoch: the session's revisions count from the
                // start again, so the list's newer-revision-wins guards start over too.
                if (accepted && hadSnapshot && current.journalEpoch != previousEpoch) forgetListRevisions(current.sessionId)
                replaced = accepted
                val sameSession = data.optionalText("sessionId") == current.sessionId
                // The same epoch's snapshot at a lower revision: one from another epoch starts over.
                val outdated = !older && sameSession && !accepted
                if (accepted && includedTasks && !current.needsSnapshot) {
                    taskRefreshPending = false
                    val tasks = parseSessionTasks(data["tasks"])
                    current.tasksKnown = tasks.freshness == TaskFreshness.LIVE
                    update { it.copy(tasks = tasks) }
                } else if ((includedTasks && outdated) || (!older && !includedTasks && canReadTasks())) {
                    taskRefreshPending = true
                }
                current.messages.forEach { messageDeliveryReceipt(current.sessionId, it) }
                if (!older) recheckFollowUps(current.sessionId)
                publishTimeline(persist = !older)
                // A fresh snapshot is the host's word on what is still open, so an uncertain answer is
                // settled either way.
                if (!older) settleUncertainQuestions()
                // The chat on screen is read again, also when the activation after a reconnect failed
                // to read it and a later refresh did.
                if (!older && state.value.connected) staleReplaced()
                resyncAfterUnknownEvents(current)
            }
            answered = true
        } catch (e: RemoteRequestException) {
            // Another device's first pages retired this page's cursor: recovered below, once.
            if (!older || !retryStaleCursor || e.kind != RemoteRequestException.Kind.HOST_ERROR || e.code != "stale_cursor") throw e
            staleCursor = true
            answered = true
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } finally {
            // A reply that never came or was not taken: the events held for it apply now.
            val heldArrivals = awaited && current.heldArrivals
            val drained = awaited && current.endAwaiting()
            if (drained && current === timeline) publishTimeline()
            if (epoch == selectionEpoch) resynchronizing = false
            // A reread that failed leaves the Timeline lapsed for the next focus change or read.
            val rereadFailed = reread && !replaced
            if (rereadFailed) current.focusLapsed = true
            if (drained && !answered) afterFailedWait(current, epoch, heldArrivals)
            // A focus that excluded the chat while this reply was awaited: the Timeline detaches now,
            // or, back in focus, reads a fresh first page (not again right after a failed one).
            if (!older && current.focusLapsed && current === timeline) syncFocus(readLapsed = !rereadFailed)
            // No read follows that would release a queued prompt waiting on this one.
            if (!older && !answered && !cancelled && epoch == selectionEpoch && connection == generation && firstPage == firstPageReads)
                queuedReadFailed(current)
        }
        if (staleCursor) {
            checkEpoch(epoch)
            if (current !== timeline) return
            Diagnostics.record(Diagnostics.Kind.RECOVERY, "snapshot.stale_cursor")
            // A fresh first page with its own cursor, without the older pages it cannot continue.
            snapshot(epoch, intent = SnapshotIntent.FRESH, dropOlderPages = true)
            if (current === timeline) snapshot(epoch, older = true, retryStaleCursor = false)
            return
        }
        // A prompt typed offline goes out now that the chat is read again (not when another read follows).
        // Not from a reconnect a push woke in the background: Robin does not see that prompt go out,
        // so it waits for the foreground, whose read (see [resumeTier]) releases it.
        if (!older && !refetch && !inBackground && epoch == selectionEpoch && current === timeline)
            releaseQueuedPrompt(current)
        // Taken in any case: a fresh snapshot that follows covers what the resume would fetch.
        val owedResume = current.takeOwedResume()
        if (epoch == selectionEpoch) {
            if (refetch || current.needsSnapshot) {
                taskRefreshPending = false
                snapshotAsync()
            } else if (owedResume) snapshotAsync(intent = SnapshotIntent.RESUME)
            else drainTaskRefresh()
        }
    }

    /**
     * Runs after a request that made [current] hold its session's events failed and the held events
     * applied: what they need is asked for as if they had arrived without the hold. An unknown or
     * malformed one resyncs, and when [heldArrivals] (an event arrived while the Timeline waited)
     * and the events still wait behind a gap, one resume follows. Without such an arrival the
     * failed request's own gap is left for the next live event, so a host that keeps failing is
     * not asked again in a loop. Launched, so the failure still reaches the caller.
     */
    private fun afterFailedWait(current: Timeline, epoch: Long, heldArrivals: Boolean) {
        if (current !== timeline || epoch != selectionEpoch) return
        resyncAfterUnknownEvents(current)
        if (heldArrivals && current.needsSnapshot && state.value.connected && snapshotPending !== current) {
            Diagnostics.record(Diagnostics.Kind.RECOVERY, "snapshot.after_failed_wait")
            snapshotAsync(intent = SnapshotIntent.RESUME)
        }
    }

    /** Settles the question blocks whose answer was uncertain: the host's reply says what is still open. */
    private fun settleUncertainQuestions() =
        update {
            it.copy(questionBlocks = it.questionBlocks.filterValues { block -> block != QuestionBlock.UNCERTAIN })
        }

    /**
     * Applies a `session.events` reply to [current] and runs for each event it applied what a live
     * event runs at receipt ([eventEffects]: list status, titles, receipts, job and Changes hints),
     * except a notice, which is transient. A reply that fit then counts like an accepted snapshot:
     * follow-ups are rechecked, uncertain questions settled, the chat persisted and published once,
     * and the lists read again. Tasks the Timeline knew stay current, since task changes emit
     * `snapshot.required`, which no replay spans; unknown ones are read with a fresh snapshot.
     * Returns whether the reply fit; otherwise one fresh snapshot follows.
     */
    private fun applyReplay(current: Timeline, reply: SessionEventsReply): Boolean {
        val result = current.replay(reply)
        for (event in result.applied) {
            if (event.optionalText("kind") == "session.notice") continue
            try {
                eventEffects(event)
            } catch (dropped: Exception) {
                Diagnostics.caught("repo.replay.event", dropped)
            }
        }
        if (result.fits) {
            recheckFollowUps(current.sessionId)
            val shown = state.value.tasks.freshness
            if (current.tasksKnown && (shown == TaskFreshness.LIVE || shown == TaskFreshness.STALE)) {
                taskRefreshPending = false
                update { it.copy(tasks = liveTasks(it.tasks)) }
            } else if (canReadTasks()) taskRefreshPending = true
        }
        publishTimeline(persist = true)
        if (result.fits) {
            settleUncertainQuestions()
            if (state.value.connected) staleReplaced()
        }
        resyncAfterUnknownEvents(current)
        return result.fits
    }

    private fun snapshotAsync(older: Boolean = false, intent: SnapshotIntent = SnapshotIntent.FRESH) {
        val epoch = selectionEpoch
        // Counted when scheduled, so a read that fails meanwhile leaves the queued prompt to this one.
        if (!older) firstPageReads++
        scope.launch {
            try {
                snapshot(epoch, older, intent = intent)
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
     * and one it showed unread from the chat's last visit opens where it was (base or folder, and
     * file, as on screen now). With [unreadOnly], only the latter: an activation that ended before
     * the snapshot must not leave that pane loading for good.
     */
    private fun refreshPanes(unreadOnly: Boolean = false) {
        val current = state.value
        val routeId = current.selection.routeId ?: return
        val sessionId = current.selection.sessionId ?: return
        val unread = pendingPane == DraftKey(routeId, sessionId)
        pendingPane = null
        val changes = current.changes?.takeIf { it.sessionId == sessionId }
        val files = current.files?.takeIf { it.sessionId == sessionId }
        when {
            changes != null && unread && changes.status == null ->
                changesLoader.open(changes.comments, changes.base, changes.file)
            files != null && unread && files.listing == null && files.file?.version == null && files.editor == null ->
                filesLoader.open(files.path, files.file?.path)
            unreadOnly -> Unit
            changes != null -> changesLoader.refresh()
            files != null -> filesLoader.refresh()
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
        // A prompt queued in the chat left behind is given back: the queue is for the chat on screen.
        if (!sameSession) queuedPrompt?.let { takeBackQueuedPrompt(it, "left_chat") }
        trailingPublish?.cancel()
        trailingPublish = null
        val leaving = timeline
        // A chat left for another one or for a list is kept, to resume when it is selected again.
        if (!sameSession) detachTimeline(previous, leaving)
        val kept =
            if (sameSession) null
            else selection.routeId?.let { routeId -> session?.let { detachedTimelines.remove(DraftKey(routeId, it.text("id"))) } }
        // The reconnect of the same chat continues the Timeline on screen; a kept chat its own.
        val source = (if (sameSession) leaving else kept?.timeline)?.takeIf { source ->
            source.sessionId == session?.text("id") && source.resumeBase() != null
        }
        timeline = session?.let {
            Timeline(it.text("id")).apply {
                if (source != null) continueFrom(source)
                else {
                    beginSnapshotEpoch(it.text("status"))
                    // Older pages loaded on screen survive the snapshot where it overlaps them.
                    if (sameSession) retain(state.value.messages, state.value.nextCursor)
                }
            }
        }
        // What a kept chat showed comes back with it, its tasks stale until the reply.
        val resumed = timeline?.takeIf { source != null && !sameSession }
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
        val key = selection.sessionId?.let { DraftKey(checkNotNull(selection.routeId), it) }
        val meta = if (sameSession) null else key?.let { sessionMeta[it] }
        // The pane another chat had open shows from the first frame, loading, so it never opens over
        // the composer later; its reads start after the snapshot. A parked edit wins over it.
        val memo = if (sameSession) null else key?.let { paneMemos.remove(it) }?.takeIf { resumedFiles == null }
        if (!sameSession) pendingPane = key?.takeIf { memo != null }
        update {
            it.copy(
                selection = selection,
                tasks =
                    if (sameSession) staleTasks(it.tasks)
                    else if (resumed != null) staleTasks(checkNotNull(kept).tasks)
                    else SessionTasks(),
                compaction = null,
                suggestion = null,
                pullRequest = null,
                compactionRequesting = false,
                // Only a download that can still finish, or has, stays; a stopped one would spin.
                toolOutput = it.toolOutput?.takeIf { output ->
                    sameSession &&
                        (output.text != null || output.failure != null || toolOutputJob?.isActive == true)
                },
                // The panes of the chat on screen stay open; the reads after the snapshot refresh
                // them in place.
                changes =
                    if (sameSession) it.changes?.takeIf { changes -> changes.sessionId == selection.sessionId }
                    else (memo as? PaneMemo.Changes)?.let { pane ->
                        ChangesState(
                            checkNotNull(selection.sessionId),
                            base = pane.base,
                            file = pane.file,
                            diffLoading = pane.file != null,
                            comments = stored.reviewComments,
                        )
                    },
                // Another session brings back its own parked edit, if any.
                files = resumedFiles
                    ?: if (sameSession) it.files?.takeIf { files -> files.sessionId == selection.sessionId }
                    else (memo as? PaneMemo.Files)?.let { pane ->
                        FilesState(checkNotNull(selection.sessionId), path = pane.dir, file = pane.file?.let(::OpenFile))
                    },
                // A reconnect keeps the open jobs view; the refresh after it watches again.
                jobs = it.jobs?.takeIf { jobs -> sameSession && jobs.sessionId == selection.sessionId },
                // Another chat drops the panel, not the command: the Mac runs it to its end.
                bash = it.bash?.takeIf { run -> sameSession && run.sessionId == selection.sessionId },
                host = host,
                projects = projects,
                projectChats = cachedProjectChats
                    ?.takeIf { cached -> cached.first == selection.routeId }
                    ?.second.orEmpty(),
                projectSessions = projectSessionsAggregate
                    .takeIf { projectSessionsRoute == selection.routeId }
                    ?.toMap().orEmpty(),
                project = project,
                sessions = sessions.map { withSessionTitle(withSessionStatus(it)) },
                sessionsFresh = sessionsFresh,
                session = session?.let(::withSessionTitle),
                messages = resumed?.messages ?: cachedChat?.messages ?: if (sameSession) it.messages else emptyList(),
                questions = resumed?.questions ?: if (sameSession) it.questions else emptyList(),
                draft = stored.text,
                quote = stored.quote,
                attachments = stored.attachments,
                // An import or upload of this chat that still runs keeps its progress.
                importingAttachments = sameSession && it.importingAttachments && importJob?.isActive == true,
                attachmentProgress = it.attachmentProgress?.takeIf { sameSession && sendJob?.isActive == true },
                // A queued prompt is journaled but not unconfirmed: it has not been sent.
                uncertain = stored.mutationId != null &&
                    !(sameSession && it.pendingSend?.state == PendingSendState.QUEUED),
                followUps = stored.followUps,
                sending = false,
                answering = emptySet(),
                questionBlocks = emptyMap(),
                status =
                    if (state.value.connected && activeHost?.routeId == selection.routeId)
                        cachedChat?.status ?: session?.text("status") ?: "offline"
                    else "offline",
                nextCursor =
                    if (resumed != null) resumed.nextCursor
                    else if (cachedChat != null) cachedChat.nextCursor else if (sameSession) it.nextCursor else null,
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
                sessionControlUnsupported = emptyMap(),
                configurationLoading = false,
                configurationChanging = false,
                commands = if (sameSession) it.commands else meta?.commands.orEmpty(),
                commandsLoading = false,
                commandsTruncated = if (sameSession) it.commandsTruncated else meta?.commandsTruncated == true,
                commandNotice = null,
                notice = it.notice?.takeIf { sameSession },
                // Another chat has no prompt in flight to show; an unconfirmed one is in its composer.
                pendingSend = it.pendingSend?.takeIf { sameSession },
            )
        }
        cacheCurrentScreen()
        // Before the snapshot that follows: the focus names the chat now selected, or none.
        syncFocus()
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
        if (mode == ActivationMode.RESTORE) connectEarly(selection)
        return activate(selection, mode, force = false)
    }

    /**
     * On a cold start the UI's restore connects to its own Mac as soon as the pairings and drafts
     * are loaded, while the saved navigation still loads. Only the route that restore activates
     * next, and only from the UI: a process that FCM, a worker or the widget started never
     * connects, and no other Mac's follow-ups turn uncertain. A navigation that replaced the
     * restore meanwhile (a notification, an open) cancels it, or changes [selectionEpoch].
     */
    private suspend fun connectEarly(selection: RemoteSelection) {
        if (initialized.isCompleted || selection.routeId == null) return
        val epoch = selectionEpoch
        val started = Diagnostics.now()
        connectable.await()
        if (initialized.isCompleted || epoch != selectionEpoch) return
        if (activeHost != null || timedOutRoute != null || ready != null || pairing?.isActive == true) return
        val host = state.value.hosts.find { it.routeId == selection.routeId } ?: return
        // How long the pairings and drafts kept the connection waiting.
        Diagnostics.record(Diagnostics.Kind.ACTIVATE, "activate.connect_early", Diagnostics.now() - started)
        restorePending = true
        openConnection(host, early = true)
    }

    private suspend fun activate(
        selection: RemoteSelection,
        mode: ActivationMode,
        force: Boolean,
        useCache: Boolean = true,
        onFailed: (() -> Unit)? = null,
    ): RemoteSelection {
        initialized.await()
        restorePending = false
        // The early connection was refused as paused or revoked while the saved navigation
        // loaded: a restore does not try that device again behind the user's back either.
        if (
            mode == ActivationMode.RESTORE && !reconnectEnabled && selection.routeId != null &&
                selection.routeId == state.value.deniedRouteId
        )
            return state.value.selection
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
        taskActivationEpoch = epoch
        activationBackgrounds = backgrounds
        update { it.copy(loading = true, deniedRouteId = null) }
        val started = Diagnostics.now()
        val cookie = Diagnostics.beginAsync("PocketPi.activate")
        var outcome = "failed"
        var projectsCached = false
        var sessionsCached = false
        var chatCached = false
        var cachedShown = false
        // The requested list was selected from the caches: a failed open stays on it, offline.
        var cacheSelected = false
        // The snapshot and session list read alongside other requests; see [HeldSnapshot].
        var held: HeldSnapshot? = null
        var earlySessions: Deferred<Result<List<JsonObject>>>? = null
        var snapshotHeld = false
        // The chat's first read began: from then on a pending snapshot is another read's, scheduled.
        var snapshotStarted = false
        // Their coroutines belong to this activation: its cancellation ends them too.
        val beside = CoroutineScope(currentCoroutineContext())
        try {
            val host = state.value.hosts.find { it.routeId == selection.routeId }
            if (host == null) {
                select(RemoteSelection(), null, emptyList())
                outcome = "no_host"
                return state.value.selection
            }
            fun showCached(): Boolean {
                val shown = showCachedSelection(selection, host)
                if (shown) cacheSelected = true
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
            // Read before the connection is marked closed below: a switch away from a live Mac is clean.
            val leavesLiveConnection = state.value.connected && activeHost != null
            if (!alreadyConnected) {
                stopToolOutput()
                if (!reconnecting) update(::lastKnownCleared)
                val line = connectingLine(host)
                update {
                    it.copy(
                        connected = false,
                        connection = line,
                        compaction = null,
                        suggestion = null,
                        pullRequest = null,
                        compactionRequesting = false,
                        // A finished download stays readable; a running one stopped above.
                        toolOutput = it.toolOutput?.takeIf { output -> reconnecting && output.text != null },
                    )
                }
            }
            // Explicit opens can fork historical sessions: wait for canonical IDs before
            // changing selection, but retain cached content while the snapshot refreshes. A
            // reconnect already shows this selection, fresher than the cache.
            // The project and session lists are read-only, so a user's open shows them cached
            // too; their rows stay unverified until the Mac confirms them.
            val showCache =
                useCache && (mode == ActivationMode.RESTORE || selection.sessionId == null) &&
                    !(reconnecting && selection == state.value.selection)
            // The cache shown before connecting stays; only a miss tries again once connected.
            val shownBeforeConnect = showCache && showCached()
            // connect() empties every cache when it changes the Mac, a cold start included; what
            // was just shown goes back into them, so a failed snapshot keeps the chat on screen.
            val shownProjects = cachedProjects?.takeIf { shownBeforeConnect && it.first == host.routeId }
            val shownSessions =
                selection.projectId?.takeIf { shownBeforeConnect }?.let { sessionCache[host.routeId to it] }
            val shownChat = selection.sessionId?.takeIf { shownBeforeConnect }?.let { chatCache[selection] }
            connect(host, epoch, closesCleanly = leavesLiveConnection)
            if (showCache && shownBeforeConnect) {
                restoreNavigation(host.routeId)
                if (cachedProjects?.first != host.routeId) shownProjects?.let { cachedProjects = it }
                shownSessions?.let { shown ->
                    val key = shown.routeId to shown.projectId
                    if (sessionCache[key] == null) sessionCache[key] = shown
                }
                shownChat?.let { if (chatCache[it.selection] == null) chatCache[it.selection] = it }
            } else if (showCache) {
                showCached()
            }
            // Only lists this connection read are reused: the saved navigation's, which the
            // caches show until then, are read again.
            val reusedProjects =
                cachedProjects
                    ?.takeIf {
                        useCache && !force && alreadyConnected && it.first == host.routeId &&
                            projectsReadOn == generation
                    }
                    ?.second
            projectsCached = reusedProjects != null
            fun reusedSessions(projectId: String): List<JsonObject>? =
                (sessionCache[host.routeId to projectId]?.takeIf { it.readOn == generation }?.sessions
                    // The project lists' fan-out read more projects than the cache keeps.
                    ?: projectSessionsAggregate[projectId]
                        ?.takeIf {
                            projectSessionsRoute == host.routeId && it.verified && !it.truncated &&
                                projectListReads[projectId]?.first == generation
                        }
                        ?.sessions)
                    ?.takeIf { useCache && !force && alreadyConnected }
                    // A session opened by hand may have registered after the cached list was
                    // fetched (a subagent child, for example), so a miss there asks the host again.
                    ?.takeUnless { list ->
                        mode == ActivationMode.USER_OPEN &&
                            selection.sessionId != null &&
                            list.none { it.text("id") == selection.sessionId }
                    }
            // A restored chat's IDs come from the user's own stack, so its snapshot and its session
            // list are read alongside the projects instead of one after another. The snapshot is
            // applied only once the lists confirm the chat; a row known not to be live skips it.
            val restoredProjectId = selection.projectId
            val restoredSessionId =
                selection.sessionId?.takeIf { sessionId ->
                    mode == ActivationMode.RESTORE && restoredProjectId != null &&
                        sessionCache[host.routeId to restoredProjectId]
                            ?.sessions
                            ?.find { it.text("id") == sessionId }
                            .let { row ->
                                row == null ||
                                    (row.optionalText("origin") in setOf("rpc", "tui") &&
                                        row.optionalText("status") != "offline")
                            }
                }
            if (restoredSessionId != null && restoredProjectId != null) {
                held = beside.holdSnapshot(host.routeId, restoredSessionId, epoch)
                if (reusedSessions(restoredProjectId) == null)
                    earlySessions = beside.alongside { listSessions(restoredProjectId, epoch) }
            }
            val projects = reusedProjects ?: request("projects.list", epoch).array("items")
            checkEpoch(epoch)
            cachedProjects = host.routeId to projects
            if (reusedProjects == null) projectsReadAt = now()
            projectsReadOn = generation
            val project = projects.find { it.text("id") == selection.projectId }
            val parent = RemoteSelection(host.routeId, project?.text("id"))
            if (project == null) {
                select(parent, host, projects)
                refreshProjectChats(host.routeId, projects)
                outcome = "parent"
                return parent
            }
            var sessionsFetchedNow = false
            val sessions =
                earlySessions?.let { early -> early.await().getOrThrow().also { sessionsFetchedNow = true } }
                    ?: reusedSessions(project.text("id"))
                    ?: listSessions(project.text("id"), epoch).also { sessionsFetchedNow = true }
            sessionsCached = !sessionsFetchedNow
            checkEpoch(epoch)
            cachedSessions = CachedSessions(host.routeId, project.text("id"), project, sessions, generation)
            if (sessionsFetchedNow) applyProjectList(host.routeId, project, sessions)
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
                if (mode == ActivationMode.USER_OPEN) {
                    // An open of a live chat keeps its ID, so its snapshot follows the open without
                    // waiting for its reply. An open of a history session forks it: its snapshot
                    // waits for the fork's ID.
                    val live =
                        session.optionalText("origin") in setOf("rpc", "tui") && session.text("status") != "offline"
                    request(
                        "sessions.open",
                        epoch,
                        "sessionId" to session.text("id"),
                        thenSend = { if (live) held = beside.holdSnapshot(host.routeId, session.text("id"), epoch) },
                    ).obj("session").let(::withHostWorker)
                } else session
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
            if (mode == ActivationMode.USER_OPEN && selection.sessionId != canonical.sessionId) {
                preparedSource = DraftKey(host.routeId, checkNotNull(selection.sessionId))
                update { it.copy(preparedText = drafts[preparedSource]?.text?.takeIf(String::isNotEmpty)) }
            }
            // Only a snapshot of the chat now selected is taken; another one is dropped below. The
            // events that waited for it go to the new Timeline, which buffers them until it is in.
            val taken = held?.takeIf { it.sessionId == canonical.sessionId }
            val heldReply = taken?.reply
            // A held resume's Timeline waits for its reply from the start: the events held so far
            // and those that follow apply after it.
            if (taken?.base != null) timeline?.awaitReply()
            held?.let(::releaseHeldSnapshot)
            snapshotHeld = heldReply != null
            // The controls need only the connection and its capabilities, so they are read while
            // the snapshot is.
            refreshConfiguration()
            refreshAdvisor()
            refreshCommands()
            try {
                snapshotStarted = true
                snapshot(
                    epoch,
                    held = heldReply,
                    heldIncludesTasks = held?.includeTasks == true,
                    heldBase = taken?.base,
                    intent = SnapshotIntent.RESUME,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (swallowed: Exception) {
                Diagnostics.caught("repo.activate.2", swallowed)
                checkEpoch(epoch)
                // The reconnect after a lost connection opens the chat again.
                if (chatCache[canonical] != null || lostWithConnection(swallowed)) {
                    if (swallowed.requestKind == RemoteRequestException.Kind.HOST_ERROR) {
                        // The host refused the chat, so its controls' reads, started before the
                        // snapshot, would only add their own errors to this one.
                        configurationVersion++
                        advisorVersion++
                        commandsVersion++
                        update {
                            it.copy(
                                configurationLoading = false,
                                advisorLoading = false,
                                advisorChanging = false,
                                commandsLoading = false,
                            )
                        }
                    }
                    reportReadError(swallowed)
                    outcome = "snapshot_failed"
                    return canonical
                }
                cachedChat = null
                // The controls read for the chat left behind no longer land.
                configurationVersion++
                advisorVersion++
                commandsVersion++
                preparedSource = null
                select(parent, host, projects, project, sessions, sessionsFresh = sessionsFetchedNow)
                update { it.copy(preparedText = null) }
                reportError(R.string.remote_request_error)
                outcome = "snapshot_failed"
                return parent
            }
            refreshPanes()
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
            onFailed?.invoke()
            return if (mode == ActivationMode.USER_OPEN && !cacheSelected) previousSelection else state.value.selection
        } finally {
            // What was read alongside and not taken is dropped; its late replies find no request.
            held?.let { unused ->
                releaseHeldSnapshot(unused)
                unused.reply.cancel()
            }
            earlySessions?.cancel()
            Diagnostics.endAsync("PocketPi.activate", cookie)
            fun hit(cached: Boolean) = if (cached) "hit" else "miss"
            Diagnostics.record(
                Diagnostics.Kind.ACTIVATE,
                "activate." + mode.name.lowercase() + if (force) ".force" else "",
                Diagnostics.now() - started,
                outcome =
                    "$outcome:projects.${hit(projectsCached)}:sessions.${hit(sessionsCached)}:chat.${hit(chatCached)}" +
                        if (snapshotHeld) ":snapshot.held" else "",
            )
            if (epoch == selectionEpoch) {
                activationStartedAt = null
                taskActivationEpoch = null
                // A cached chat whose snapshot never started (the activation failed before it)
                // asks for one itself when its events need it. Once it started, a pending snapshot
                // is one already scheduled (the reread of a lapsed focus, a refocus): it stays, or
                // the focus sync below would read the chat a second time.
                timeline?.takeIf { !snapshotStarted && snapshotPending === it }?.let { pending ->
                    snapshotPending = null
                    // A held resume whose reply is dropped: the events held for it apply now.
                    pending.endAwaiting()
                    if (pending.needsSnapshot && state.value.connected) snapshotAsync(intent = SnapshotIntent.RESUME)
                }
                if (outcome != "ok") refreshPanes(unreadOnly = true)
                update { it.copy(loading = false) }
                if (outcome == "ok" || outcome == "parent" || outcome == "no_host") staleReplaced()
                cacheCurrentScreen()
                // Lists reused from this connection show at once and are read again behind them:
                // the Mac sends no event for a new session or a newly shared project.
                if (outcome == "ok" || outcome == "parent") {
                    if (projectsCached) revalidateProjects()
                    if (sessionsCached && state.value.selection.sessionId == null) revalidateSessions()
                }
                drainTaskRefresh()
                // A focus sent for a chat this activation then did not select names the selection again.
                syncFocus()
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
            val session = withHostWorker(request("sessions.create", epoch, "projectId" to projectId).obj("session"))
            // Only the project's list misses the new chat; other lists and chats stay cached.
            invalidateProjectList(routeId, projectId)
            invalidateProjectList(routeId, session.text("projectId"))
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
            invalidateProjectList(routeId, projectId)
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

    /**
     * The project of [sessionId] on [routeId] as the repository's memory holds it: the open chat,
     * the cached chats and lists, the project lists' aggregate and the saved navigation.
     */
    private fun localProject(routeId: String, sessionId: String): String? {
        fun List<JsonObject>.holds() = any { it.optionalText("id") == sessionId }
        state.value.selection.takeIf { it.routeId == routeId && it.sessionId == sessionId }?.projectId?.let { return it }
        chatCache.entries().lastOrNull { (key, _) -> key.routeId == routeId && key.sessionId == sessionId }
            ?.first?.projectId?.let { return it }
        sessionCache.entries().lastOrNull { (key, entry) -> key.first == routeId && entry.sessions.holds() }
            ?.first?.second?.let { return it }
        if (projectSessionsRoute == routeId)
            projectSessionsAggregate.values.find { it.sessions.holds() }?.projectId?.let { return it }
        cachedProjectChats?.takeIf { it.first == routeId }?.second
            ?.find { it.session.optionalText("id") == sessionId }?.projectId?.let { return it }
        val saved = savedNavigation[routeId] ?: return null
        if (saved.sessions.holds()) saved.projectId?.let { return it }
        return saved.projectChats
            .find { (it["session"] as? JsonObject)?.optionalText("id") == sessionId }
            ?.optionalText("projectId")
    }

    override suspend fun notificationProject(routeId: String, sessionId: String): String? {
        initialized.await()
        if (!opaqueId(routeId) || !opaqueId(sessionId) || state.value.hosts.none { it.routeId == routeId }) return null
        localProject(routeId, sessionId)?.let { return it }
        return try {
            projectHint(routeId, sessionId)?.takeIf(::opaqueId)
        } catch (e: CancellationException) {
            throw e
        } catch (swallowed: Exception) {
            Diagnostics.caught("repo.notificationProject", swallowed)
            null
        }
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
        // A project known here opens the chat directly; the walk over every project is only for a
        // session the app never saw. A stale project (the session is not in it) lands on that
        // project's list or the projects, and the walk still finds the session.
        notificationProject(routeId, sessionId)?.let { projectId ->
            reconnectEnabled = true
            var failed = false
            val opened =
                activate(
                    RemoteSelection(routeId, projectId, sessionId),
                    ActivationMode.USER_OPEN,
                    force = false,
                    onFailed = { failed = true },
                )
            // A failed open (the Mac unreachable, a request refused) hands back the selection from
            // before it, which may be another Mac's: that is no answer to the tap. The walk would
            // only fail the same way, so the previous screen comes back as when the walk fails.
            if (failed || opened.routeId != routeId) return restorePrevious(previous, routeId, selectionEpoch)
            val stale =
                opened == RemoteSelection(routeId) ||
                    (opened == RemoteSelection(routeId, projectId) &&
                        state.value.sessions.none { it.optionalText("id") == sessionId })
            if (!stale) return opened
        }
        val epoch = selectionEpoch
        var resolved: RemoteSelection? = null
        update { it.copy(loading = true) }
        try {
            connect(host, epoch)
            // Read again rather than reused: a session no cache knows may be in a newly shared project.
            val projects = request("projects.list", epoch).array("items")
            cachedProjects = routeId to projects
            projectsReadOn = generation
            projectsReadAt = now()
            val found = findSessionProject(routeId, projects, sessionId)
            checkEpoch(epoch)
            if (found != null) {
                // The open that follows reuses the list the walk just read.
                sessionCache[routeId to found.first.text("id")] =
                    CachedSessions(routeId, found.first.text("id"), found.first, found.second, generation)
                resolved = RemoteSelection(routeId, found.first.text("id"), sessionId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (swallowed: Exception) {
            Diagnostics.caught("repo.openNotification", swallowed)
            if (epoch == selectionEpoch) reportError(R.string.remote_request_error)
        }
        if (resolved != null) return activate(resolved, ActivationMode.USER_OPEN)
        return restorePrevious(previous, routeId, epoch)
    }

    /**
     * After a notification's open failed: reconnects the Mac that was on screen before when the
     * notification was for another one, puts its [previous] state back, and returns null so the
     * navigator restores its own stack.
     */
    private suspend fun restorePrevious(previous: RemoteState, routeId: String, epoch: Long): RemoteSelection? {
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
                        suggestion = null,
                        pullRequest = null,
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

    /**
     * Reads the lists of [projects] on [routeId], [NOTIFICATION_WALK_PARALLELISM] at a time, until
     * one holds [sessionId]; the reads still waiting then never go out. Each list read is kept
     * like the project lists' fan-out keeps it. Returns the project and its list, or null; without
     * a hit, a failed read fails the walk.
     */
    private suspend fun findSessionProject(
        routeId: String,
        projects: List<JsonObject>,
        sessionId: String,
    ): Pair<JsonObject, List<JsonObject>>? = coroutineScope {
        val connection = generation
        val permits = Semaphore(NOTIFICATION_WALK_PARALLELISM)
        val hit = CompletableDeferred<Pair<JsonObject, List<JsonObject>>?>()
        var failure: Exception? = null
        val reads =
            projects.filter { it.optionalText("id") != null }.map { project ->
                launch {
                    permits.withPermit {
                        if (hit.isCompleted) return@withPermit
                        try {
                            val rows = readProjectList(routeId, project, connection)
                                ?: throw RemoteRequestException.connectionLost()
                            if (rows.any { it.optionalText("id") == sessionId }) hit.complete(project to rows)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (failure == null) failure = e
                        }
                    }
                }
            }
        launch {
            reads.joinAll()
            hit.complete(null)
        }
        val result = hit.await()
        coroutineContext.cancelChildren()
        result ?: failure?.let { throw it }
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
        if (markPendingFollowUpsUncertain(activeHost?.routeId)) persistNow()
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
        endPushWake(close = false)
        offlineSince = null
        cancelHostWaitHint()
        cancelQuietReconnect()
        cancelSelection()
        clearNavigationCache()
        // The other Macs' images and pages stay; the bitmaps are keyed by content and cannot tell
        // the routes apart, so they all go.
        activeHost?.routeId?.let { route ->
            projectImageBytes.clearRoute(route)
            projectArtifacts.clearRoute(route)
        }
        ProjectImageBitmaps.clear()
        ArtifactThumbnails.clear()
        retry?.cancel()
        connectionEnded()
        generation++
        clearLateAcknowledgements()
        transport.close()
        ready?.cancel()
        pairing?.cancel()
        if (markPendingFollowUpsUncertain(activeHost?.routeId)) persist()
        forgetQueuedPrompt()
        activeHost = null
        timedOutRoute = null
        trailingPublish?.cancel()
        trailingPublish = null
        timeline = null
        stopToolOutput()
        update { RemoteState(hosts = it.hosts, hostsLoaded = it.hostsLoaded, hostsUnreadable = it.hostsUnreadable) }
    }

    override fun remove(routeId: String) {
        if (
            cachedProjects?.first == routeId ||
                projectSessionsRoute == routeId ||
                cachedSessions?.routeId == routeId ||
                cachedChat?.selection?.routeId == routeId
        ) clearNavigationCache()
        if (activeHost?.routeId == routeId) reconnectEnabled = false
        if (activeHost?.routeId == routeId) disconnect()
        if (queuedPrompt?.key?.routeId == routeId) forgetQueuedPrompt()
        if (timedOutRoute == routeId) timedOutRoute = null
        attachmentBytes.clearRoute(routeId)
        SentImageThumbnails.clearRoute(routeId)
        projectImageBytes.clearRoute(routeId)
        confirmedImages.removeAll { it.routeId == routeId }
        projectArtifacts.clearRoute(routeId)
        toolOutputs.clearRoute(routeId)
        if (toolOutputProgress?.key?.routeId == routeId) toolOutputProgress = null
        // The bitmaps are keyed by content, so they cannot tell the routes apart: they all go.
        ProjectImageBitmaps.clear()
        ArtifactThumbnails.clear()
        followUpsToRecheck.keys.removeAll { it.routeId == routeId }
        removedRoutes += routeId
        parkedFiles.keys.removeAll { it.routeId == routeId }
        detachedTimelines.keys.removeAll { it.routeId == routeId }
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
                    journalOwners.keys.removeAll { it.routeId == routeId }
                    persistNow()
                    cleanupAttachments()
                    // The kept images go before the unpair is saved: a crash in between keeps the
                    // pairing and costs a download, and never leaves images of an unpaired Mac.
                    withContext(io) { media?.clearRoute(routeId) }
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
        clearLateAcknowledgements()
        folderVersion++
        folderSession++
        ticketsLoader.connectionLost()
        activeOpen = null
        cloneStatusJob?.cancel()
        providerStatusJob?.cancel()
        providerVersion++
        earlyProviderUpdates.clear()
        failConnectionRequests()
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
        unknownSessions.clear()
        missingSessions.clear()
        projectChatCandidates.replaceAll { _, chats ->
            chats.map { it.copy(verified = false) }
        }
        cachedProjectChats = cachedProjectChats?.let { it.first to it.second.map { chat ->
            chat.copy(verified = false)
        } }
        unverifyProjectSessions()
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
        if (transient && !waitingForNetwork && cause != FailureCause.WAKE_ENDED) failureStreak++
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
                if (keep) it.copy(stale = true, staleSince = it.staleSince ?: now(), tasks = staleTasks(it.tasks))
                else lastKnownCleared(it)
            shown.copy(
                connected = false,
                compaction = null,
                suggestion = null,
                pullRequest = null,
                compactionRequesting = false,
                // A finished download stays readable; a running one stopped above.
                toolOutput = it.toolOutput?.takeIf { output -> keep && output.text != null },
                projectChats = it.projectChats.map { chat -> chat.copy(verified = false) },
                projectSessions = it.projectSessions.mapValues { (_, entry) -> entry.unverified() },
                // A verdict on the Mac's version lasts only for the connection that gave it.
                childControlUnsupported = emptySet(),
                sessionControlUnsupported = emptyMap(),
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
                questionBlocks = emptyMap(),
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
        // An early connection no activation took over yet: the restore that waits for the saved
        // navigation connects again itself, and a retry now would activate the empty selection
        // from before it.
        val unowned = earlyConnection && restorePending
        if (quiet && !waitingForNetwork && !unowned && cause != FailureCause.WAKE_ENDED) startQuietReconnect()
        else cancelQuietReconnect()
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
        } else if (retrying && !unowned) {
            val generationAtFailure = generation
            // The relay announced a restart, so one retry per failure streak waits only 250 to
            // 750 ms: the Mac needs a moment to reconnect to the relay too.
            val restartRetry = restart && !restartRetryUsed && !broken
            if (restartRetry) {
                restartRetryUsed = true
                reconnectAttempts = 0
            }
            // A dead socket or a replaced network says nothing about the Mac: try again at once.
            val immediate = cause == FailureCause.STALE || cause == FailureCause.NETWORK_REPLACED
            val wait =
                if (restartRetry && !immediate) {
                    reconnectAttempts++
                    RESTART_RETRY_MIN_MILLIS +
                        (RESTART_RETRY_SPREAD_MILLIS * jitter().coerceIn(0.0, 1.0)).toLong()
                } else reconnectDelay(reconnectAttempts++, immediate)
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
            R.string.remote_storage_pairings_reset -> "pairings_reset"
            R.string.remote_storage_drafts_reset -> "drafts_reset"
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
        ticketsLoader.refresh(force = true)
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
        // In front, the usual rules hold; the window has nothing left to bound.
        if (foreground) endPushWake(close = false)
        val signal = recovery.foreground(foreground)
        backgroundFocusTimer?.cancel()
        backgroundFocusTimer = null
        inBackground = !foreground
        backgroundGraceOver = false
        if (!foreground) {
            backgroundFocusTimer = scope.launch {
                delay(BACKGROUND_FOCUS_GRACE_MILLIS)
                backgroundFocusTimer = null
                backgroundGraceOver = true
                // An activation sends its own focus and syncs again once it selected; see [streamReady].
                if (!state.value.loading) syncFocus()
            }
            jobsController.setForeground(false)
            backgroundedAt = now()
            backgrounds++
            selectionAtBackground = state.value.selection
            // A process kill in the background must not lose the tail a streaming chat is ahead.
            if (!navDirty || !persistNavigation(immediate = true)) queueNavigationWrite(immediate = true)
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
        // After the tier: a reconnect it started leaves this to the next connection's capabilities,
        // and a probe it started already refocused at its start (see [revalidate]), so this one
        // finds nothing left to do. An activation in flight still names the screen it leaves, so it
        // keeps the focus it sent and syncs once it selected.
        if (!state.value.loading) syncFocus()
        // After the tier: a reconnect it starts lists the jobs once ready, so this one does not.
        jobsController.setForeground(true)
    }

    override fun pushWake(routeId: String) {
        fun note(outcome: String) = Diagnostics.record(Diagnostics.Kind.RECOVERY, "push.wake", outcome = outcome)
        when {
            // A cold process has no active Mac: it keeps BackgroundRemoteClient (R18).
            activeHost?.routeId != routeId -> return note("other_route")
            !reconnectEnabled -> return note("disabled")
            recovery.isForeground -> return note("foreground")
            !recovery.hasNetwork -> return note("no_network")
            pairing?.isActive == true -> return note("busy")
        }
        val open = pushWakeRoute == routeId
        // Connected with no window: the connection exists for another reason and needs nothing.
        if (state.value.connected && !open) return note("connected")
        pushWakeRoute = routeId
        recovery.wake(true)
        pushWakeTimer?.cancel()
        pushWakeTimer =
            scope.launch {
                delay(PUSH_WAKE_WINDOW_MILLIS)
                pushWakeTimer = null
                endPushWake(close = true)
            }
        note(if (open) "extended" else "open")
        if (state.value.connected) return
        // A push says nothing about the Mac being unreachable: try at once, with a fresh backoff.
        resetBackoff()
        recover(RemoteRecovery.Signal.FRESH_NETWORK)
    }

    override suspend fun awaitPushWake(routeId: String, maxMillis: Long) {
        // Stale until the activation after a reconnect has read the chat again; a healthy
        // connection (see [withHealthyConnection]) is neither.
        fun ready(current: RemoteState) = current.connected && !current.stale
        withTimeoutOrNull(maxMillis) {
            // Checked on the repository's own dispatcher, the main thread in the app, so a worker
            // that starts while the main thread is busy sees a [pushWake] posted before it started.
            val waiting =
                withContext(scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext) {
                    pushWakeRoute == routeId && !ready(state.value)
                }
            if (waiting) state.first(::ready)
        }
    }

    /**
     * Closes the push wake window. With [close], a window that ends in the background closes the
     * socket it opened, unless the live-run service keeps the process anyway ([liveRunKept]; a
     * running status alone does not, the service only starts when the app goes to the background),
     * instead of leaving the socket to the freezer.
     */
    private fun endPushWake(close: Boolean) {
        pushWakeTimer?.cancel()
        pushWakeTimer = null
        if (pushWakeRoute == null) return
        pushWakeRoute = null
        recovery.wake(false)
        Diagnostics.record(Diagnostics.Kind.RECOVERY, "push.wake", outcome = if (close) "closed" else "ended")
        if (!close || recovery.isForeground) return
        if (liveRunKept()) return
        stopReconnectTimer()
        if (state.value.connected || state.value.loading)
            failed(true, R.string.remote_connection_error, cause = FailureCause.WAKE_ENDED)
    }

    /** Acts on [signal]; [pathMoved]: the network in use only moved its route. */
    private fun resume(signal: RemoteRecovery.Signal, pathMoved: Boolean = false) {
        onRecoverySignal(signal)
        if (signal == RemoteRecovery.Signal.FRESH_FOREGROUND) ticketsLoader.refresh(force = false)
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
            // A prompt a background wake held (see [snapshot]) needs this read to go out.
            alive && away < RESUME_QUIET_AWAY_MILLIS && selection == selectionAtBackground && queuedPrompt == null ->
                ResumeTier.NONE
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
     * a resume of the Timeline on screen (a replay of what it missed, or a snapshot), elsewhere the
     * project list. The lists are then read
     * again and applied in place; only a selection they no longer hold is selected anew, on its
     * parent. Configuration, advisor and commands are read again only if the chat moved on.
     */
    private suspend fun revalidate(selection: RemoteSelection, epoch: Long) {
        val host = activeHost?.takeIf { it.routeId == selection.routeId } ?: return
        val routeId = host.routeId
        // The refocus goes first, whatever the scope's dispatcher: its session.focus must be on the
        // wire before any resume, and the resume it sends for a detached chat makes the probe below
        // use the list instead of a second resume.
        syncFocus()
        val chat = timeline?.takeIf { selection.sessionId != null && it.sessionId == selection.sessionId }
        val before = chat?.let { it.revision to it.status }
        // That resume runs on its own, so its reply may land after the probe's: the chat may still
        // move after the comparison below.
        val resumeElsewhere = chat != null && (chat.awaitingReply || snapshotPending === chat)
        val hint =
            scope.launch {
                delay(RESUME_PROBE_HINT_MILLIS)
                if (epoch == selectionEpoch && state.value.connected && state.value.connection == R.string.remote_connected)
                    update { it.copy(connection = R.string.remote_checking_connection) }
            }
        val started = Diagnostics.now()
        var projects: List<JsonObject>? = null
        try {
            // A chat that already waits for a resume or has a fresh read pending (its refocus sent
            // or started one) is probed with the list.
            if (chat != null && !resumeElsewhere)
                snapshot(epoch, timeoutMillis = RESUME_PROBE_TIMEOUT_MILLIS, intent = SnapshotIntent.RESUME)
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
        if (chat != null && chat === timeline && (resumeElsewhere || before != (chat.revision to chat.status))) {
            refreshConfiguration()
            refreshAdvisor()
            refreshCommands()
        }
        try {
            val fresh = projects ?: request("projects.list", epoch).array("items")
            checkEpoch(epoch)
            if (!state.value.connected || activeHost?.routeId != routeId || state.value.selection != selection) return
            cachedProjects = routeId to fresh
            projectsReadAt = now()
            projectsReadOn = generation
            val projectId = selection.projectId
            val project = projectId?.let { id -> fresh.find { it.text("id") == id } }
            if (project == null) {
                if (projectId == null) update { it.copy(projects = fresh) }
                else select(RemoteSelection(routeId), host, fresh)
                refreshProjectChats(routeId, fresh)
                return
            }
            val sessions = listSessions(project.text("id"), epoch)
            checkEpoch(epoch)
            if (!state.value.connected || activeHost?.routeId != routeId || state.value.selection != selection) return
            cachedSessions = CachedSessions(routeId, project.text("id"), project, sessions, generation)
            applyProjectList(routeId, project, sessions)
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

    /**
     * Queues the drafts for storage without waiting for the write. A change to a send journal (what
     * a restart needs to resolve an unacknowledged send) is queued at once; any other change, the
     * text typed in the composer for one, waits [DRAFT_WRITE_DELAY_MILLIS] so a run of keystrokes
     * is one write. A barrier that must see the write finish uses [persistNow] instead.
     */
    private fun persist() {
        if (!draftsAvailable) {
            reportError(R.string.remote_storage_error)
            return
        }
        if (journalChanged()) queueDrafts(CompletableDeferred())
        else {
            draftWriteTimer?.cancel()
            draftWriteTimer = scope.launch {
                delay(DRAFT_WRITE_DELAY_MILLIS)
                queueDrafts(CompletableDeferred())
            }
        }
    }

    /** Stores the drafts before it returns: the barrier before a send, and the one in [flush]. */
    private suspend fun persistNow() {
        if (!draftsAvailable) {
            reportError(R.string.remote_storage_error)
            throw IllegalStateException("Draft storage unavailable")
        }
        val done = CompletableDeferred<Unit>()
        queueDrafts(done)
        done.await()
    }

    /** Writes a prospective snapshot without publishing it to the shared drafts. */
    private suspend fun persistSnapshot(values: Map<DraftKey, StoredDraft>) {
        if (!draftsAvailable) throw IllegalStateException("Draft storage unavailable")
        val done = CompletableDeferred<Unit>()
        check(writes.trySend(Write(values, done)).isSuccess)
        done.await()
    }

    /** Queues a write of the drafts as they are now, which replaces any debounced write still waiting. */
    private fun queueDrafts(done: CompletableDeferred<Unit>) {
        draftWriteTimer?.cancel()
        draftWriteTimer = null
        // Without drafts loaded a write carries no values: it only reports the earlier failure, and
        // never saves an empty map over the stored drafts.
        val values = if (draftsAvailable) drafts.toMap() else null
        if (values != null) journalQueued = values.journals()
        check(writes.trySend(Write(values, done)).isSuccess)
    }

    private fun journalChanged(): Boolean = journalQueued != drafts.journals()

    override suspend fun flush() {
        initialized.await()
        val done = CompletableDeferred<Unit>()
        // Write pending keystrokes or retry the current snapshot after a known storage failure.
        // Never save an empty map when loading drafts failed.
        if (draftWriteTimer != null || (draftsAvailable && storageFailure != null)) queueDrafts(done)
        else writes.send(Write(null, done))
        done.await()
    }

    override fun draft(text: String) {
        if (text.toByteArray().size > 128 * 1024) return
        val key = currentDraftKey() ?: return
        if ((drafts[key] ?: StoredDraft()).text != text) composerEdited(key, "text")
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
        composerEdited(key, "text")
        drafts[key] = stored.copy(text = text)
        if (preparedSource == key) update { it.copy(preparedText = text.takeIf(String::isNotEmpty)) }
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
        if ((drafts[key] ?: StoredDraft()).quote != quote) composerEdited(key, "quote")
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

    override fun importAttachments(selection: RemoteSelection, uris: List<String>, photo: Boolean) =
        importAttachments(selection, uris.map { Picked(it, photo) }) {}

    override fun importAttachments(selection: RemoteSelection, batch: List<Picked>, onDone: () -> Unit) {
        // Every refusal below gives the batch back at once; a started import does so once it ends.
        var started = false
        try {
            startImport(selection, batch, onDone)?.let { job ->
                started = true
                importJob = job
            }
        } finally {
            if (!started) onDone()
        }
    }

    /** Starts importing [batch] one URI after another, or returns null when it is refused. */
    private fun startImport(selection: RemoteSelection, batch: List<Picked>, onDone: () -> Unit): Job? {
        val importer = attachmentImporter ?: return null
        if (selection != state.value.selection || state.value.loading) return null
        // A drop or a paste arrives while the + button is disabled: say why nothing is attached.
        if (state.value.importingAttachments || state.value.sending) {
            reportError(R.string.remote_attachments_busy)
            return null
        }
        if (ATTACHMENTS_CAPABILITY !in state.value.capabilities) {
            reportError(R.string.remote_attachments_unsupported)
            return null
        }
        val key = currentDraftKey() ?: return null
        if (batch.isEmpty()) return null
        if (batch.size + state.value.attachments.size > 5) {
            reportError(R.string.remote_attachment_limits)
            return null
        }
        val epoch = selectionEpoch
        update { it.copy(importingAttachments = true) }
        val job = scope.launch {
            try {
                for ((uri, photo) in batch) {
                    attachmentMutex.withLock {
                        val imported = importer(uri, photo)
                        val latest = drafts[key] ?: StoredDraft()
                        try {
                            checkEpoch(epoch)
                            check(currentDraftKey() == key)
                            val attachments = latest.attachments + imported
                            require(validAttachmentSet(attachments))
                            composerEdited(key, "attachments")
                            drafts[key] = latest.copy(attachments = attachments)
                            persistNow()
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
                                withContext(NonCancellable) { runCatching { persistNow() } }
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
        // Also when the job is cancelled before it ran: no URI is read after this.
        job.invokeOnCompletion { onDone() }
        return job
    }

    override fun removeAttachment(id: String) {
        val key = currentDraftKey() ?: return
        val latest = drafts[key] ?: return
        if (latest.attachments.none { it.id == id }) return
        composerEdited(key, "attachments")
        drafts[key] = latest.copy(attachments = latest.attachments.filterNot { it.id == id })
        update { it.copy(attachments = drafts.getValue(key).attachments) }
        val epoch = selectionEpoch
        scope.launch {
            try {
                persistNow()
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
                            persistNow()
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
                        persistNow()
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
            persistNow()
        }
        val total = values.sumOf { it.size }.coerceAtLeast(1)
        var uploaded = 0L
        return values.map { local ->
            checkEpoch(epoch)
            // Checked before anything is sent, as the whole file always was. The file is read once in
            // pieces to digest it, so no copy of it is held in memory.
            val verified =
                attachmentMutex.withLock {
                    withContext(io) { storage.open(local).use { AttachmentImportRules.digest(it) } }
                }
            require(verified.size == local.size && verified.sha256 == local.sha256) {
                "Attachment changed before it was sent"
            }
            val previous = drafts[key]?.uploads?.find { it.localId == local.id }
            val confirmed = previous?.attachment
            // Only an image's bytes are kept for the sent prompt; any other file is only streamed.
            var imageBytes: ByteArray? = null
            val remote = if (
                previous?.projectId == projectId &&
                    confirmed != null &&
                    confirmed.expiresAt > now() &&
                    confirmed.sha256 == local.sha256
            ) {
                uploaded += local.size
                update { it.copy(attachmentProgress = uploaded.toFloat() / total) }
                if (local.kind == "image") {
                    imageBytes = attachmentMutex.withLock { withContext(io) { storage.read(local) } }
                }
                confirmed
            } else {
                // Opened before anything is sent, so a file that cannot be read starts no upload.
                val input = attachmentMutex.withLock { withContext(io) { storage.open(local) } }
                input.use { stream ->
                    if (previous != null) {
                        cancelUpload(key, previous.remoteId, epoch)
                        drafts[key] =
                            (drafts[key] ?: StoredDraft()).let {
                                it.copy(
                                    uploads =
                                        it.uploads.filterNot { upload -> upload.localId == local.id }
                                )
                            }
                        persistNow()
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
                    persistNow()
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    val collected =
                        if (local.kind == "image") java.io.ByteArrayOutputStream(local.size.toInt())
                        else null
                    var offset = 0L
                    while (offset < local.size) {
                        delay(200)
                        checkEpoch(epoch)
                        val length = minOf(buffer.size.toLong(), local.size - offset).toInt()
                        val read =
                            withContext(io) { AttachmentImportRules.readInto(stream, buffer, length) }
                        require(read == length) { "Attachment changed while it was sent" }
                        digest.update(buffer, 0, length)
                        collected?.write(buffer, 0, length)
                        val end = offset + length
                        val chunk =
                            java.util.Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(buffer.copyOf(length))
                        val result =
                            request(
                                "session.attachments.chunk",
                                epoch,
                                "sessionId" to key.sessionId,
                                "attachmentId" to remoteId,
                                "offset" to offset.toInt(),
                                "data" to chunk,
                            )
                        require(
                            result.text("kind") == "attachment.upload" &&
                                result.text("sessionId") == key.sessionId &&
                                result.text("attachmentId") == remoteId &&
                                result.long("offset") == end
                        )
                        offset = end
                        update { it.copy(attachmentProgress = (uploaded + offset).toFloat() / total) }
                    }
                    // The bytes must end where the attachment ends and match its digest before the commit.
                    require(withContext(io) { stream.read() } < 0) {
                        "Attachment changed while it was sent"
                    }
                    require(AttachmentImportRules.hex(digest.digest()) == local.sha256) {
                        "Attachment changed while it was sent"
                    }
                    imageBytes = collected?.toByteArray()
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
                    persistNow()
                    uploaded += local.size
                    attachment
                }
            }
            // The image bytes are kept under the server ID before the prompt can appear in the
            // conversation; draft cleanup may delete the local file as soon as sending succeeds.
            imageBytes?.let {
                attachmentBytes[AttachmentByteCache.Key(key.routeId, key.sessionId, remote.id, remote.sha256)] = it
                keepMedia(key.routeId) { cache -> cache.put(key.routeId, remote.sha256, it) }
            }
            remote
        }
    }

    override fun prompt() {
        if (takeBackQueuedBeforeSend()) return
        // A prompt typed while the connection is down waits for it; see [queuePrompt].
        if (!state.value.connected && queuePrompt()) return
        sendPrompt(null)
    }

    override fun followUp() {
        if (takeBackQueuedBeforeSend()) return
        sendPrompt("follow_up")
    }

    override fun steer() {
        if (takeBackQueuedBeforeSend()) return
        sendPrompt("steer")
    }

    /**
     * A send of newly typed text while a prompt still waits in the queue would overwrite the queue's
     * journal. The queued text comes back into the composer ahead of the typed text instead, and
     * nothing is sent: the user sees what goes out and sends it with the next tap.
     */
    private fun takeBackQueuedBeforeSend(): Boolean {
        val queued = queuedPrompt ?: return false
        // Offline, nothing else can go out; the queue keeps its place.
        if (!state.value.connected || state.value.draft.isBlank()) return false
        takeBackQueuedPrompt(queued, "superseded")
        return true
    }

    /**
     * Sends [delivery]'s prompt from the composer, or, with [resend], the queued prompt it names
     * (its text and quote, not the composer's; [resendCleared] is what the queue took out of the
     * composer, so a failed send gives it back). Returns whether a send started.
     */
    private fun sendPrompt(delivery: String?, resend: PendingSend? = null, resendCleared: ClearedComposer? = null): Boolean {
        val queued = delivery != null
        val current =
            if (resend == null) state.value
            else state.value.copy(draft = resend.text, quote = resend.quote, attachments = emptyList())
        if (
            !current.connected ||
                // A queued prompt goes out when the reconnect's snapshot is in, while the activation
                // that read it still loads.
                (resend == null && current.loading) ||
                activeHost?.routeId != current.selection.routeId ||
                current.status != (if (queued) "running" else "idle") ||
                (queued && (!(if (delivery == "steer") canSteer(current) else canFollowUp(current)) ||
                    current.questions.isNotEmpty() || current.followUps.size >= 64)) ||
                current.sending ||
                (resend == null && (current.importingAttachments || current.configurationChanging)) ||
                (current.draft.isBlank() && current.attachments.isEmpty())
        )
            return false
        if (queued && isChildSession(current) && current.attachments.isNotEmpty()) {
            reportError(R.string.remote_child_attachments_unsupported)
            return false
        }
        val key = currentDraftKey() ?: return false
        val projectId = current.selection.projectId ?: return false
        val command = commandName(current.draft)
        if (queued && (command != null || current.draft.trimStart().startsWith("/"))) return false
        if (command != null) {
            if (current.quote != null || current.attachments.isNotEmpty()) {
                reportError(R.string.remote_command_remove_context)
                return false
            }
            if (
                COMMANDS_CAPABILITY !in current.capabilities ||
                    COMMANDS_CAPABILITY in current.unavailableCapabilities ||
                    current.commandsLoading ||
                    current.commands.none { it.name == command }
            ) {
                reportError(R.string.remote_command_unknown)
                return false
            }
        }
        if (current.attachments.isNotEmpty()) {
            if (ATTACHMENTS_CAPABILITY !in current.capabilities) {
                reportError(R.string.remote_attachments_unsupported)
                return false
            }
            if (!validAttachmentSet(current.attachments)) {
                reportError(R.string.remote_attachment_limits)
                return false
            }
            if (
                current.attachments.any { it.kind == "image" } &&
                    current.configuration?.model?.input?.contains("image") != true
            ) {
                reportError(R.string.remote_attachment_model_error)
                return false
            }
        }
        val encoded =
            try {
                QuoteCodec.encode(current.draft, current.quote)
            } catch (swallowed: IllegalArgumentException) {
                Diagnostics.caught("repo.sendPrompt", swallowed)
                reportError(R.string.remote_prompt_too_long)
                return false
            }
        val epoch = selectionEpoch
        // A plain prompt is sent through the composer and shown at the chat's tail; the other two
        // keep their draft until the host acknowledges them.
        val plain = !queued && command == null
        val kind = delivery ?: if (command != null) "command" else "prompt"
        val submitted = PromptSnapshot(current.draft, current.quote, current.attachments)
        val sentRevision = composerVersion(key)
        val tapped = Diagnostics.now()
        val id = resend?.mutationId ?: (if (plain) unconfirmedMutationId(key, current) else null) ?: Wire.random()
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
        // Set once the host acknowledges the request: a write that fails after that still leaves the
        // plain prompt confirmed in the chat.
        var acknowledged = false
        var restoredAfterTimeout: RestoredComposer? = null
        // A resend starts with what the queue cleared, so a failure before the journal step restores it too.
        var clearedComposer: ClearedComposer? = resendCleared
        val connection = generation
        val attempt = Any()
        promptAttempts[key] = attempt
        promptOwners[key] = attempt
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
                val stored = drafts[key] ?: StoredDraft()
                // Text and attachments typed since the send began stay; what was sent leaves the
                // composer and lives in the journal. Uploads stay, so a resend reuses them.
                val version = composerVersion(key)
                // A queued prompt left the composer when it was queued: what is typed there now stays.
                val clearTextAndQuote = plain && resend == null && version.text == sentRevision.text &&
                    version.quote == sentRevision.quote && stored.text == current.draft && stored.quote == current.quote
                val clearText = clearTextAndQuote
                val clearQuote = clearTextAndQuote
                val clearAttachments = plain && resend == null && version.attachments == sentRevision.attachments &&
                    stored.attachments == current.attachments
                clearedComposer = resendCleared ?: ClearedComposer(version, clearText, clearQuote, clearAttachments)
                drafts[key] =
                    stored.copy(
                        text = if (clearText) "" else stored.text,
                        quote = if (clearQuote) null else stored.quote,
                        attachments = if (clearAttachments) emptyList() else stored.attachments,
                        mutationId = id,
                        submittedText = current.draft,
                        submittedQuote = current.quote,
                        submittedAttachments = current.attachments,
                        submittedDelivery = kind,
                    )
                journalOwners[key] = attempt
                persistNow()
                checkEpoch(epoch)
                val journaled = drafts.getValue(key)
                update {
                    it.copy(
                        uncertain = true,
                        attachmentProgress = null,
                        draft = if (plain) journaled.text else it.draft,
                        quote = if (plain) journaled.quote else it.quote,
                        attachments = if (plain) journaled.attachments else it.attachments,
                        pendingSend =
                            if (plain) PendingSend(
                                id,
                                current.draft,
                                current.quote,
                                current.attachments,
                                PendingSendState.SENDING,
                                // The newest message the chat holds now: the host's copy of this
                                // prompt comes after it, whatever earlier prompt reads the same. The
                                // Timeline is read, since a streaming upsert may not be published yet.
                                afterMessageId =
                                    chatMessages(key.sessionId, it.messages).lastOrNull()?.optionalText("id")?.ifEmpty { null },
                            )
                            else it.pendingSend,
                    )
                }
                if (plain) Diagnostics.record(Diagnostics.Kind.REQUEST, "send.pending", Diagnostics.now() - tapped)
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
                        onLateAccepted = if (command != null) null else { result ->
                            if (result.optionalText("kind") == "accepted" && result.optionalText("sessionId") == key.sessionId &&
                                connection == generation && activeHost?.routeId == key.routeId) {
                                scope.launch {
                                    try {
                                        if (connection == generation && activeHost?.routeId == key.routeId &&
                                            !lateAcknowledgementsDisabled && id !in ambiguousRequestIds &&
                                            settlePrompt(key, id, submitted, queued, plain, kind, connection,
                                                attempt, sentRevision, restoredAfterTimeout)) {
                                            if (plain) confirmPendingSend(key, id, attempt, tapped, late = true)
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Diagnostics.caught("repo.sendPrompt.late", e)
                                        if (storageFailure == null) reportError(R.string.remote_storage_error)
                                    } finally {
                                        if (promptAttempts[key] === attempt) promptAttempts.remove(key)
                                        if (promptOwners[key] === attempt) promptOwners.remove(key)
                                    }
                                }
                            }
                        },
                    )
                acknowledged = true
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
                settlePrompt(key, id, submitted, queued, plain, kind, connection, attempt, sentRevision)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
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
                val refusedControl =
                    (if (delivery == "steer") ExtensionControl.STEER else ExtensionControl.FOLLOW_UP)
                        .takeIf { queued && e.isExtensionControlRefusal && extensionControls(current, it) }
                if (
                    (queued && (e as? RemoteRequestException)?.code == "not_running" || refusedControl != null) &&
                        epoch == selectionEpoch
                ) {
                    // The child finished or this pi must reload: the host did not take the input,
                    // so the draft stays for a retry and no submission is left to reconcile.
                    drafts[key]?.takeIf { journalOwners[key] === attempt }?.let {
                        drafts[key] =
                            it.copy(
                                mutationId = null,
                                submittedText = null,
                                submittedQuote = null,
                                submittedAttachments = emptyList(),
                                submittedDelivery = null,
                            )
                        if (journalOwners[key] === attempt) journalOwners.remove(key)
                        persist()
                    }
                    if (refusedControl != null) {
                        markSessionControlUnsupported(key.sessionId, refusedControl)
                        if (storageFailure == null) reportError(R.string.remote_session_control_reload_pi)
                    } else setChildControl(key.sessionId, ChildControl(ChildControlPhase.NOT_RUNNING))
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
                // The host's acknowledgement confirms a plain prompt whatever the writes after it did.
                // Without one it is not confirmed: failed, cancelled by leaving the chat, or still
                // unanswered when the coroutine ends.
                val ownsAttempt = promptOwners[key] === attempt
                if (plain && ownsAttempt) {
                    if (acknowledged) {
                        if (connection == generation && activeHost?.routeId == key.routeId)
                            confirmPendingSend(key, id, attempt, tapped)
                    } else restoredAfterTimeout = unconfirmPendingSend(key, id, attempt, clearedComposer)
                }
                if (ownsAttempt || promptOwners[key] == null)
                    attachmentInFlight.removeAll(current.attachments.map { it.id }.toSet())
                if (ownsAttempt && epoch == selectionEpoch)
                    update { it.copy(sending = false, attachmentProgress = null) }
                if (ownsAttempt && (acknowledged || lateAcknowledgements[id] == null)) {
                    if (promptAttempts[key] === attempt) promptAttempts.remove(key)
                    promptOwners.remove(key)
                }
                resumeDeferredRecovery()
                withContext(NonCancellable) { runCatching { cleanupAttachments() } }
            }
        }
        return true
    }

    /**
     * A plain prompt typed while the connection is down (R16): it leaves the composer into the send
     * journal, as a send does, and shows at the chat's tail as [PendingSendState.QUEUED]. It goes
     * out through the normal send once the reconnect's snapshot or resume shows that the chat did
     * not move on ([releaseQueuedPrompt]). Returns whether the prompt was taken, also to refuse it.
     */
    private fun queuePrompt(): Boolean {
        val current = state.value
        if (!canQueuePrompt(current) || queuedPrompt != null || activeHost?.routeId != current.selection.routeId)
            return false
        val key = currentDraftKey() ?: return false
        if (!draftsAvailable || storageFailure != null) return false
        try {
            QuoteCodec.encode(current.draft, current.quote)
        } catch (swallowed: IllegalArgumentException) {
            Diagnostics.caught("repo.queuePrompt", swallowed)
            reportError(R.string.remote_prompt_too_long)
            return true
        }
        val id = unconfirmedMutationId(key, current) ?: Wire.random()
        val stored = drafts[key] ?: StoredDraft()
        val clear = stored.text == current.draft && stored.quote == current.quote
        val cleared = ClearedComposer(composerVersion(key), clear, clear, true, queued = true)
        val attempt = Any()
        drafts[key] =
            stored.copy(
                text = if (clear) "" else stored.text,
                quote = if (clear) null else stored.quote,
                mutationId = id,
                submittedText = current.draft,
                submittedQuote = current.quote,
                submittedAttachments = emptyList(),
                submittedDelivery = "prompt",
            )
        journalOwners[key] = attempt
        promptOwners[key] = attempt
        persist()
        val journaled = drafts.getValue(key)
        val queuedAt = now()
        update {
            it.copy(
                draft = journaled.text,
                quote = journaled.quote,
                uncertain = false,
                pendingSend = PendingSend(
                    id,
                    current.draft,
                    current.quote,
                    emptyList(),
                    PendingSendState.QUEUED,
                    // The newest message the chat shows now: the prompt goes out only if it is still
                    // the newest after the reconnect.
                    afterMessageId = chatMessages(key.sessionId, it.messages).lastOrNull()?.optionalText("id")?.ifEmpty { null },
                    queuedAt = queuedAt,
                ),
            )
        }
        val expiry = scope.launch {
            delay(QUEUED_PROMPT_MILLIS)
            queuedPrompt?.takeIf { it.id == id }?.let { markQueuedNotConfirmed(it, "expired") }
        }
        queuedPrompt = QueuedPrompt(key, id, attempt, cleared, expiry)
        Diagnostics.record(Diagnostics.Kind.OFFLINE, "prompt.queued")
        return true
    }

    /**
     * Called when a first page (a snapshot or a resume) of [source] was applied: sends the queued
     * prompt if the chat did not move on while the phone was offline, its newest message is still
     * the one shown when the prompt was queued and pi is idle. Otherwise the prompt is marked not
     * confirmed, its text back in the composer, for the user to send again.
     */
    private fun releaseQueuedPrompt(source: Timeline) {
        val queued = queuedPrompt ?: return
        val current = state.value
        val pending = current.pendingSend?.takeIf { it.mutationId == queued.id && it.state == PendingSendState.QUEUED }
        if (pending == null) {
            queued.expiry.cancel()
            queuedPrompt = null
            return
        }
        // Waits for a reply from this connection to the chat the prompt was typed in.
        if (
            !current.connected ||
                source !== timeline ||
                source.sessionId != queued.key.sessionId ||
                currentDraftKey() != queued.key ||
                activeHost?.routeId != queued.key.routeId ||
                source.needsSnapshot ||
                // Events held during the snapshot are still to be fetched by the owed resume, which
                // reads the chat again and comes back here.
                source.resumeIsOwed ||
                // The view may miss the newest message: a focus that lapsed while the reply was
                // awaited (a push wake's empty focus) schedules a fresh read, a detached chat
                // applies nothing, and a reply still awaited may move the chat. That read's own
                // snapshot comes back here.
                source.focusLapsed ||
                source.detached ||
                source.awaitingReply ||
                snapshotPending === source
        )
            return
        val newest = source.messages.lastOrNull()?.optionalText("id")?.ifEmpty { null }
        val age = now() - (pending.queuedAt ?: 0L)
        when {
            age >= QUEUED_PROMPT_MILLIS -> markQueuedNotConfirmed(queued, "expired")
            newest != pending.afterMessageId || current.status != "idle" -> markQueuedNotConfirmed(queued, "moved_on")
            else -> {
                queued.expiry.cancel()
                queuedPrompt = null
                // The send takes the journal and the pending bubble over under the same request ID.
                if (sendPrompt(null, pending, queued.cleared)) Diagnostics.record(Diagnostics.Kind.OFFLINE, "prompt.queue_sent", age)
                else markQueuedNotConfirmed(queued, "not_sent")
            }
        }
    }

    /**
     * A first page of [source] failed on a connection that is still up, and no later read is
     * scheduled or running for it ([snapshot] checks both). [releaseQueuedPrompt] relies on such a
     * read to come back to it, so the queued prompt turns not confirmed instead of keeping its
     * queued label until the expiry. A read a drop ended leaves the prompt for the reconnect. In
     * the background the foreground's read decides, as it does for a release.
     */
    private fun queuedReadFailed(source: Timeline) {
        val queued = queuedPrompt ?: return
        val current = state.value
        if (
            !current.connected ||
                inBackground ||
                source !== timeline ||
                source.sessionId != queued.key.sessionId ||
                currentDraftKey() != queued.key ||
                activeHost?.routeId != queued.key.routeId ||
                // A snapshot still owed for the chat reads it again and comes back to the release.
                snapshotPending === source ||
                current.pendingSend?.let { it.mutationId == queued.id && it.state == PendingSendState.QUEUED } != true
        )
            return
        markQueuedNotConfirmed(queued, "read_failed")
    }

    /** The queued prompt did not go out: not confirmed, with its text back in the composer. */
    private fun markQueuedNotConfirmed(queued: QueuedPrompt, reason: String) {
        if (queuedPrompt === queued) queuedPrompt = null
        queued.expiry.cancel()
        // Only a journal that still holds this prompt is uncertain; a newer send owns it otherwise.
        val journaled = drafts[queued.key]?.mutationId == queued.id && promptOwners[queued.key] === queued.attempt
        unconfirmPendingSend(queued.key, queued.id, queued.attempt, queued.cleared)
        if (promptOwners[queued.key] === queued.attempt) promptOwners.remove(queued.key)
        if (journaled && currentDraftKey() == queued.key) update { it.copy(uncertain = true) }
        Diagnostics.record(Diagnostics.Kind.OFFLINE, "prompt.queue_dropped", outcome = reason)
    }

    /**
     * The screen the queued prompt waits on goes (a disconnect, or its Mac removed): the wait and
     * its expiry end, and the journal entry stays, so opening the chat again shows the prompt as not
     * confirmed, as after a process death.
     */
    private fun forgetQueuedPrompt() {
        val queued = queuedPrompt ?: return
        queuedPrompt = null
        queued.expiry.cancel()
        if (promptOwners[queued.key] === queued.attempt) promptOwners.remove(queued.key)
        Diagnostics.record(Diagnostics.Kind.OFFLINE, "prompt.queue_dropped", outcome = "disconnected")
    }

    override fun cancelQueuedPrompt() {
        queuedPrompt?.let { takeBackQueuedPrompt(it, "cancelled") }
    }

    /**
     * The queued prompt never left the phone, so taking it back leaves nothing to reconcile: its
     * text returns to the composer (ahead of what was typed there since) and the journal entry goes.
     */
    private fun takeBackQueuedPrompt(queued: QueuedPrompt, reason: String) {
        queuedPrompt = null
        queued.expiry.cancel()
        val key = queued.key
        if (promptOwners[key] === queued.attempt) promptOwners.remove(key)
        val journal = drafts[key]?.takeIf { it.mutationId == queued.id && journalOwners[key] === queued.attempt }
        if (journal != null) {
            val submitted = journal.submittedText.orEmpty()
            drafts[key] =
                journal.copy(
                    text = if (journal.text.isEmpty()) submitted else submitted + "\n\n" + journal.text,
                    quote = journal.quote ?: journal.submittedQuote,
                    mutationId = null,
                    submittedText = null,
                    submittedQuote = null,
                    submittedAttachments = emptyList(),
                    submittedDelivery = null,
                )
            journalOwners.remove(key)
            persist()
        }
        val restored = drafts[key]
        if (currentDraftKey() == key && restored != null)
            update {
                it.copy(
                    draft = restored.text,
                    quote = restored.quote,
                    uncertain = false,
                    pendingSend = it.pendingSend?.takeIf { pending -> pending.mutationId != queued.id },
                )
            }
        Diagnostics.record(Diagnostics.Kind.OFFLINE, "prompt.queue_taken_back", outcome = reason)
    }

    private data class PromptSnapshot(val draft: String, val quote: MessageQuote?, val attachments: List<LocalAttachment>)
    private data class ClearedComposer(
        val revision: ComposerVersions,
        val text: Boolean,
        val quote: Boolean,
        val attachments: Boolean,
        /** The queue took the text out: if the composer changed since, the text returns ahead of what was typed. */
        val queued: Boolean = false,
    )
    private data class RestoredComposer(val revision: ComposerVersions, val quote: Boolean, val attachments: Boolean)

    private suspend fun settlePrompt(
        key: DraftKey, id: String, submitted: PromptSnapshot, queued: Boolean, plain: Boolean,
        delivery: String, connection: Long, attempt: Any, sentRevision: ComposerVersions,
        restored: RestoredComposer? = null,
    ): Boolean {
        fun owns(draft: StoredDraft) = journalOwners[key] === attempt && draft.mutationId == id &&
            draft.submittedText == submitted.draft && draft.submittedQuote == submitted.quote &&
            draft.submittedAttachments == submitted.attachments && draft.submittedDelivery == delivery
        val receiptKey = Triple(key, id, delivery)
        fun settled(latest: StoredDraft): StoredDraft? {
            if (queued && latest.followUps.size >= 64) return null
            val version = composerVersion(key)
            val originalText = !plain && version.text == sentRevision.text && version.quote == sentRevision.quote
            // Any edit makes the whole timeout-restored composer the user's again.
            val restoredText = plain && restored != null && version == restored.revision
            val originalAttachments = !plain && version.attachments == sentRevision.attachments
            val restoredAttachments = restoredText && restored?.attachments == true
            val attachments = if (originalAttachments || restoredAttachments) emptyList() else latest.attachments
            return StoredDraft(
                text = if (originalText || restoredText) "" else latest.text,
                quote = if (originalText || (restoredText && restored?.quote == true)) null else latest.quote,
                attachments = attachments,
                uploads = latest.uploads.filter { upload -> attachments.any { it.id == upload.localId } },
                followUps = if (queued) latest.followUps + PendingFollowUp(id, submitted.draft,
                    earlyFollowUpReceipts[receiptKey] ?: "accepted", delivery) else latest.followUps,
                reviewComments = latest.reviewComments,
            )
        }
        val before = drafts[key] ?: return false
        if (!owns(before)) return false
        val prospective = settled(before) ?: return false
        // Until this succeeds, every concurrent composer write still contains the journal.
        persistSnapshot(drafts.toMap() + (key to prospective))
        val current = drafts[key]
        if (current == null || !owns(current)) return false
        val reconciled = settled(current) ?: return false
        drafts[key] = reconciled
        if (journalOwners[key] === attempt) journalOwners.remove(key)
        if (connection == generation && activeHost?.routeId == key.routeId && currentDraftKey() == key) {
            update { it.copy(draft = reconciled.text, quote = reconciled.quote, attachments = reconciled.attachments,
                uncertain = false, followUps = reconciled.followUps) }
        }
        try {
            // The staged acceptance is committed; this orders intervening composer and metadata edits.
            persistNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The writer reported the failure. Do not undo the known acceptance in memory.
            Diagnostics.caught("repo.settlePrompt.reconcile", e)
        }
        return connection == generation && activeHost?.routeId == key.routeId && promptAttempts[key] === attempt
    }

    /**
     * The mutation ID of an unconfirmed send of this same prompt, which a resend reuses so the host
     * sees one prompt. Null when the composer holds something else: that is a new prompt.
     */
    private fun unconfirmedMutationId(key: DraftKey, current: RemoteState): String? {
        val stored = drafts[key] ?: return null
        val mutationId = stored.mutationId ?: return null
        // A journal of a follow-up, steer or command keeps its request ID for that request; a plain
        // prompt sent under it would reach the host as a different kind of request.
        if (stored.submittedDelivery != null && stored.submittedDelivery != "prompt") return null
        return mutationId.takeIf { stored.submittedText == current.draft && stored.submittedQuote == current.quote }
    }

    /**
     * The messages the chat holds for [sessionId]: the Timeline's, which may be ahead of the
     * [published] list while a streaming upsert waits for its trailing publish ([TIMELINE_PUBLISH_MILLIS]).
     * A send's baseline and its acknowledgement must read the same list.
     */
    private fun chatMessages(sessionId: String, published: List<JsonObject>): List<JsonObject> =
        timeline?.takeIf { it.sessionId == sessionId && it.hasSnapshot }?.messages ?: published

    /**
     * The host acknowledged the plain prompt [id]: the chat keeps it until its timeline has it. The
     * chat shown decides, not the selection epoch: a chat left and opened again keeps its send.
     */
    private fun confirmPendingSend(key: DraftKey, id: String, attempt: Any, tapped: Long, late: Boolean = false) {
        if (promptOwners[key] !== attempt || currentDraftKey() != key) return
        val pending = state.value.pendingSend?.takeIf { it.mutationId == id &&
            (it.state == PendingSendState.SENDING || (late && it.state == PendingSendState.NOT_CONFIRMED)) }
            ?: return
        update {
            it.copy(pendingSend = settledSend(pending.copy(state = PendingSendState.CONFIRMED), chatMessages(key.sessionId, it.messages)))
        }
        Diagnostics.record(Diagnostics.Kind.REQUEST, "send.confirmed", Diagnostics.now() - tapped)
    }

    /**
     * A plain prompt the host did not confirm: its text goes back into the composer, to send again
     * with the same mutation ID, and the chat marks the bubble as not confirmed. The bubble is
     * marked even when the journal was already resolved, so it never waits for a confirmation that
     * cannot come; the composer is only restored from a journal that still holds the send.
     */
    private fun unconfirmPendingSend(key: DraftKey, id: String, attempt: Any, cleared: ClearedComposer?): RestoredComposer? {
        if (promptOwners[key] !== attempt) return null
        val before = drafts[key]?.takeIf { it.mutationId == id }
        val version = composerVersion(key)
        val restoreText = before != null && cleared != null && cleared.text &&
            version.text == cleared.revision.text && before.text.isEmpty() && before.submittedText != null
        val restoreQuote = restoreText && cleared!!.quote && version.quote == cleared.revision.quote && before!!.quote == null
        val restoreAttachments = restoreText && cleared!!.attachments &&
            version.attachments == cleared.revision.attachments && before!!.attachments.isEmpty()
        // A queued prompt whose composer changed since: its text comes back ahead of what was typed.
        val mergeQueued = !restoreText && before != null && cleared != null && cleared.queued && cleared.text &&
            before.submittedText != null
        val restored = if (restoreText) before!!.copy(text = before.submittedText!!,
            quote = if (restoreQuote) before.submittedQuote else before.quote,
            attachments = if (restoreAttachments) before.submittedAttachments else before.attachments)
        else if (mergeQueued) before!!.copy(
            text = if (before.text.isEmpty()) before.submittedText!! else before.submittedText!! + "\n\n" + before.text,
            quote = before.quote ?: if (cleared!!.quote) before.submittedQuote else null,
        )
        else before
        // A merged composer holds typed text too, so a late acknowledgement never clears it.
        val ownership = if (restoreText) RestoredComposer(version, restoreQuote, restoreAttachments)
            else if (mergeQueued && before!!.text.isEmpty()) RestoredComposer(version, cleared!!.quote, false)
            else null
        if (restored != null) {
            drafts[key] = restored
            persist()
        }
        if (currentDraftKey() != key) return ownership
        update { state ->
            val pending = state.pendingSend?.let { p ->
                if (p.mutationId == id && p.state != PendingSendState.CONFIRMED)
                    p.copy(state = PendingSendState.NOT_CONFIRMED)
                else p
            }
            if (restored == null) state.copy(pendingSend = pending)
            else
                state.copy(
                    draft = restored.text,
                    quote = restored.quote,
                    attachments = restored.attachments,
                    pendingSend = pending,
                )
        }
        return ownership
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

    private fun markSessionControlUnsupported(sessionId: String, control: ExtensionControl) = update {
        it.copy(
            sessionControlUnsupported =
                it.sessionControlUnsupported + (sessionId to (it.sessionControlUnsupported[sessionId].orEmpty() + control)),
        )
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
        val last = lastSessionRefreshAt
        if (last != null && now() - last in 0 until SESSION_REFRESH_INTERVAL_MILLIS) return
        readSessions()
    }

    /**
     * Reads the selected project's list again behind what it shows, unless this connection read it
     * less than [SESSION_REFRESH_INTERVAL_MILLIS] ago. Unlike [refreshSessions], another project's
     * read just before does not hold it back.
     */
    private fun revalidateSessions() {
        val current = state.value
        val routeId = current.selection.routeId ?: return
        val projectId = current.selection.projectId ?: return
        val read = projectListReads[projectId]?.takeIf { projectSessionsRoute == routeId && it.first == generation }
        if (read != null && now() - read.second in 0 until SESSION_REFRESH_INTERVAL_MILLIS) return
        // A read that failed is not tried again at each status either.
        val attempt = sessionReadAttempt?.takeIf { it.first == Triple(routeId, projectId, generation) }?.second
        if (attempt != null && now() - attempt in 0 until SESSION_REFRESH_INTERVAL_MILLIS) return
        readSessions()
    }

    private fun readSessions() {
        val current = state.value
        val selection = current.selection
        val routeId = selection.routeId ?: return
        val projectId = selection.projectId ?: return
        if (!current.connected || current.loading || activeHost?.routeId != routeId ||
            sessionRefreshJob?.isActive == true
        ) return
        lastSessionRefreshAt = now()
        sessionReadAttempt = Triple(routeId, projectId, generation) to now()
        val epoch = selectionEpoch
        sessionRefreshJob = scope.launch {
            try {
                val sessions = listSessions(projectId, epoch)
                if (epoch != selectionEpoch || state.value.selection != selection ||
                    activeHost?.routeId != routeId
                ) return@launch
                cachedSessions?.takeIf { it.routeId == routeId && it.projectId == projectId }?.let {
                    cachedSessions = it.copy(sessions = sessions, readOn = generation)
                }
                current.project
                    ?.takeIf { it.text("id") == projectId }
                    ?.let { applyProjectList(routeId, it, sessions) }
                update { it.copy(sessions = sessions, sessionsFresh = true) }
                cacheCurrentScreen()
            } catch (_: CancellationException) {} catch (swallowed: Exception) {
                Diagnostics.caught("repo.refreshSessions", swallowed)
                // The previous list stays; the next refresh or reconnect retries.
            }
        }
    }

    /**
     * Reads the project list again behind what it shows, unless this connection read it less than
     * [PROJECTS_REVALIDATE_MILLIS] ago or [force]. A list that changed replaces it, and a newly
     * shared project's sessions are read too, unless a fan-out of every project just started.
     */
    private fun revalidateProjects(force: Boolean = false) {
        val current = state.value
        val routeId = current.selection.routeId ?: return
        if (!current.connected || activeHost?.routeId != routeId || projectsRevalidateJob?.isActive == true) return
        val last = projectsReadAt
        if (
            !force && projectsReadOn == generation && cachedProjects?.first == routeId &&
                last != null && now() - last in 0 until PROJECTS_REVALIDATE_MILLIS
        ) return
        val connection = generation
        projectsReadAt = now()
        projectsRevalidateJob = scope.launch {
            try {
                val fresh = connectionRequest("projects.list").array("items")
                if (connection != generation || activeHost?.routeId != routeId) return@launch
                // An activation still running holds the list it started with and writes it back
                // when it ends, so the end of that activation reads the list once more.
                if (state.value.loading) projectsReadAt = null
                val known = cachedProjects?.takeIf { it.first == routeId }?.second
                if (known != null) projectsReadOn = connection
                val shown = state.value.takeIf { it.selection.routeId == routeId }?.projects
                if (fresh == known && (shown == null || shown == fresh)) return@launch
                cachedProjects = routeId to fresh
                projectsReadOn = connection
                if (shown != null && shown != fresh) update { it.copy(projects = fresh) }
                val knownIds = known.orEmpty().mapTo(mutableSetOf()) { it.text("id") }
                val added = fresh.filter { it.text("id") !in knownIds }.takeIf { known != null }.orEmpty()
                val fanOut = lastProjectChatsRefreshAt
                if (state.value.selection.let { it.routeId == routeId && it.projectId == null })
                    refreshProjectChats(routeId, fresh)
                cacheCurrentScreen()
                if (lastProjectChatsRefreshAt != fanOut) return@launch
                for (project in added) {
                    try {
                        readProjectList(routeId, project, connection) ?: return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (swallowed: Exception) {
                        Diagnostics.caught("repo.revalidateProjects.2", swallowed)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (swallowed: Exception) {
                Diagnostics.caught("repo.revalidateProjects", swallowed)
                // The list shown stays; the next visit or reconnect reads it again.
            }
        }
    }

    override fun revalidateLists() {
        val current = state.value
        val routeId = current.selection.routeId ?: return
        if (current.loading) return
        // Without a connection, a pull asks for one, as the refresh button does.
        if (!current.connected || activeHost?.routeId != routeId) {
            refresh()
            return
        }
        revalidateProjects(force = true)
        if (current.selection.projectId != null) readSessions()
        else refreshProjectChats(routeId, current.projects, force = true)
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
        val current = state.value
        val sessionId = current.selection.sessionId
        val routeId = current.selection.routeId
        val key = if (sessionId != null && routeId != null) ToolOutputCache.Key(routeId, sessionId, toolCallId) else null
        // The same output of the same chat is downloading already: it keeps going.
        if (key != null && toolOutputJob?.isActive == true && toolOutputProgress?.key == key) return
        // A completed output never changes, so it opens from memory, offline too, without a request.
        val completed = key?.let { toolOutputs[it] }
        if (completed != null) {
            stopToolOutput()
            update { it.copy(toolOutput = completed) }
            return
        }
        stopToolOutput()
        fun failure(reason: ToolOutputFailure) = ToolOutputDownload(
            toolCallId, 0, null, null, truncated = false, isError = false, failure = reason,
        )
        // A quiet reconnect keeps the capabilities on screen: offline is no missing capability.
        if (sessionId != null && !current.connected) {
            // A stopped download of this output keeps the text it has, as the bytes are kept for the resume.
            val kept = toolOutputProgress?.takeIf { it.key == key }
            update { it.copy(toolOutput = kept?.download(ToolOutputFailure.OFFLINE) ?: failure(ToolOutputFailure.OFFLINE)) }
            return
        }
        if (
            key == null || sessionId == null ||
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
        // A download stopped part way on this output resumes at the bytes it has; another output starts over.
        val progress =
            toolOutputProgress?.takeIf { it.key == key }
                ?: ToolOutputProgress(key, toolCallId).also { toolOutputProgress = it }
        update { it.copy(toolOutput = progress.download()) }
        toolOutputJob = scope.launch {
            try {
                while (true) {
                    val chunk = validatedToolOutputChunk(
                        request(
                            "session.tool_output.get",
                            epoch,
                            "sessionId" to sessionId,
                            "toolCallId" to toolCallId,
                            "offset" to progress.offset,
                        ),
                        sessionId,
                        toolCallId,
                        progress.offset,
                        progress.total,
                    )
                    if (!active()) return@launch
                    progress.total = chunk.totalBytes
                    progress.text.append(chunk.bytes)
                    progress.offset += chunk.bytes.size
                    progress.truncated = chunk.truncated
                    progress.isError = chunk.isError
                    if (progress.offset == chunk.totalBytes) {
                        val done = ToolOutputDownload(
                            toolCallId,
                            progress.offset,
                            chunk.totalBytes,
                            progress.text.finish(),
                            chunk.truncated,
                            chunk.isError,
                            null,
                        )
                        toolOutputs[key] = done
                        toolOutputProgress = null
                        update { it.copy(toolOutput = done) }
                        return@launch
                    }
                    update { it.copy(toolOutput = progress.download()) }
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
                // A lost connection keeps the bytes, so the next load resumes; any other failure starts over.
                if (reason != ToolOutputFailure.OFFLINE) toolOutputProgress = null
                update {
                    it.copy(
                        unavailableCapabilities =
                            if (reason == ToolOutputFailure.UNSUPPORTED)
                                it.unavailableCapabilities + TOOL_OUTPUT_CAPABILITY
                            else it.unavailableCapabilities,
                        toolOutput = ToolOutputDownload(
                            toolCallId, progress.offset, progress.total, null, false, false, reason,
                            // A lost connection keeps the text on screen, as the bytes are kept for the resume.
                            preview = if (reason == ToolOutputFailure.OFFLINE) progress.text.preview() else null,
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
     * Sends a request bound to the connection rather than the selection and waits up to
     * [timeoutMillis] for its reply. Navigating away does not drop the reply; only a lost or
     * replaced connection fails it ([failConnectionRequests]). The caller checks that the
     * connection is up, that [requestId] is free and that fewer than [MAX_CONNECTION_REQUESTS]
     * are outstanding. With [record], Diagnostics records the round trip like [request]'s and a
     * reply proves the connection.
     */
    private suspend fun awaitConnectionReply(
        requestId: String,
        type: String,
        fields: Array<out Pair<String, Any?>>,
        timeoutMillis: Long,
        record: Boolean,
    ): JsonObject =
        awaitConnectionReply(Wire.objectOf("type" to type, "requestId" to requestId, *fields), timeoutMillis, record)

    /**
     * Sends [payload], a command with its `requestId`, bound to the connection, and waits for its
     * result. With [liveness], a type in [LIVENESS_READS] that times out with no frame since it went
     * out ends the connection as stale, as [request] does.
     */
    private suspend fun awaitConnectionReply(
        payload: JsonObject,
        timeoutMillis: Long,
        record: Boolean,
        liveness: Boolean = false,
    ): JsonObject {
        val requestId = payload.text("requestId")
        val type = payload.text("type")
        recordIssuedRequest(requestId)
        val completion = CompletableDeferred<JsonObject>()
        connectionCompletions[requestId] = completion
        val started = Diagnostics.now()
        val connection = generation
        val inboundAtSend = inboundFrames
        var outcome = "failed"
        try {
            transport.send(payload)
            val result =
                withTimeoutOrNull(timeoutMillis) { completion.await() }
                    ?: run {
                        outcome = "timeout"
                        if (liveness && type in LIVENESS_READS && inboundFrames == inboundAtSend && state.value.connected && connection == generation)
                            connectionStale(type)
                        throw RemoteRequestException.timeout()
                    }
            outcome = "ok"
            if (record) connectionProven()
            return result
        } catch (e: Throwable) {
            if (outcome != "timeout") outcome = requestOutcome(e)
            throw e
        } finally {
            connectionCompletions.remove(requestId)
            if (record)
                Diagnostics.request(type, Diagnostics.now() - started, payload.toString().length.toLong(), outcome)
        }
    }

    /** Whether another connection-bound request may be sent now. */
    private fun connectionRequestsOpen() =
        state.value.connected && connectionCompletions.size < MAX_CONNECTION_REQUESTS

    /**
     * Sends a folder or clone command. Unlike [request] it is not bound to the selection, so
     * leaving the browser cannot drop a clone this device already started; only a lost or
     * replaced connection ends it.
     */
    private suspend fun folderRequest(type: String, vararg fields: Pair<String, Any?>): JsonObject {
        check(connectionRequestsOpen())
        return awaitConnectionReply(Wire.random(), type, fields, REQUEST_TIMEOUT_MILLIS, record = false)
    }

    /**
     * Sends a read bound to the connection rather than the selection, like [folderRequest]:
     * navigating away does not drop its reply, and only a lost or replaced connection fails it.
     * Diagnostics records its round trip like [request]'s.
     */
    private suspend fun connectionRequest(type: String, vararg fields: Pair<String, Any?>): JsonObject {
        check(connectionRequestsOpen())
        return awaitConnectionReply(sessionsListRequestId(type, Wire.random()), type, fields, REQUEST_TIMEOUT_MILLIS, record = true)
    }

    /** Thrown inside [withHealthyConnection] when a plain request's connection went or changed: the caller falls back. */
    private class HealthyConnectionGone : Exception()

    /**
     * Runs [block] over this connection for work outside the UI, such as an answer or a reply from
     * a notification, when it is connected to [routeId], not stale, and a frame arrived within
     * [HEALTHY_CONNECTION_MILLIS]. Otherwise it returns null without sending anything, and the
     * caller opens a connection of its own. A half-open socket would make that work slower, not
     * faster, so a quiet one does not count. The requests are independent of the selection, like
     * folder requests, count against [MAX_CONNECTION_REQUESTS], and wait at most
     * [HEALTHY_REQUEST_TIMEOUT_MILLIS] for their reply. [onSent] runs as each one goes out.
     *
     * A host error throws [RemoteCommandException] from either kind of request. Otherwise the two
     * differ in what the caller learns:
     * - [MutationCommands.mutate], under the caller's mutation ID, reports every failure: a request
     *   refused before it was sent (the connection went, changed or is full, or the ID is in use)
     *   throws [MutationNotSentException]; a timeout or a lost connection after sending throws its
     *   [RemoteRequestException], so the caller decides whether a mutation that may have arrived
     *   goes out again.
     * - [RemoteCommands.request], under a fresh ID, ends the whole call with null in those cases,
     *   so the caller falls back to its own connection; [onSent] told it whether the Mac may
     *   already have the request.
     */
    override suspend fun <T> withHealthyConnection(
        routeId: String,
        onSent: () -> Unit,
        block: suspend (MutationCommands) -> T,
    ): T? {
        val main = scope.coroutineContext.minusKey(Job)
        val connection =
            withContext(main) {
                val inbound = lastInboundAt
                val current = state.value
                generation.takeIf {
                    current.connected && !current.stale && activeHost?.routeId == routeId && inbound != null &&
                        now() - inbound in 0 until HEALTHY_CONNECTION_MILLIS
                }
            } ?: return null
        suspend fun send(requestId: String, type: String, fields: Array<out Pair<String, Any?>>): JsonObject =
            withContext(main) {
                if (
                    connection != generation || !connectionRequestsOpen() || activeHost?.routeId != routeId ||
                        requestId in connectionCompletions
                )
                    throw MutationNotSentException()
                onSent()
                try {
                    awaitConnectionReply(requestId, type, fields, HEALTHY_REQUEST_TIMEOUT_MILLIS, record = false)
                } catch (e: RemoteRequestException) {
                    // The host answered: its code means the same on any connection.
                    if (e.answered) throw RemoteCommandException(e.code)
                    throw e
                }
            }
        return try {
            block(
                object : MutationCommands {
                    override suspend fun request(type: String, vararg fields: Pair<String, Any?>): JsonObject =
                        try {
                            send(Wire.random(), type, fields)
                        } catch (e: CancellationException) {
                            // CancellationException is an IllegalStateException; let it cancel.
                            throw e
                        } catch (e: IllegalStateException) {
                            // Not sent (disconnected meanwhile, another connection, too many in
                            // flight), or sent without a reply in time or before the connection went.
                            Diagnostics.caught("repo.withHealthyConnection", e)
                            throw HealthyConnectionGone()
                        }

                    override suspend fun mutate(
                        requestId: String,
                        type: String,
                        vararg fields: Pair<String, Any?>,
                    ): JsonObject = send(requestId, type, fields)
                }
            )
        } catch (_: HealthyConnectionGone) {
            null
        }
    }

    /** Fails every connection-bound request; called when the connection is lost or replaced. */
    private fun failConnectionRequests() {
        val pending = connectionCompletions.values.toList()
        connectionCompletions.clear()
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
        if (questionId in state.value.questionBlocks) return
        update { it.copy(answering = it.answering + questionId) }
        val epoch = selectionEpoch
        scope.launch {
            try {
                request(
                    "question.answer",
                    epoch,
                    "sessionId" to sessionId,
                    "questionId" to questionId,
                    "answer" to answer,
                )
            } catch (_: CancellationException) {} catch (e: Exception) {
                if (epoch == selectionEpoch) answerFailed(sessionId, questionId, e)
            } finally {
                if (epoch == selectionEpoch)
                    update { it.copy(answering = it.answering - questionId) }
                resumeDeferredRecovery()
            }
        }
    }

    private fun answerFailed(sessionId: String, questionId: String, error: Exception) {
        // A close that arrived while the answer was in flight already settled the card.
        if (state.value.questions.none { it.text("id") == questionId }) return
        when (questionAnswerFailure(error)) {
            QuestionAnswerFailure.ANSWERED_ELSEWHERE -> {
                timeline?.takeIf { it.sessionId == sessionId }?.dropQuestion(questionId)
                publishTimeline()
                reportError(R.string.remote_question_answered_elsewhere)
            }
            QuestionAnswerFailure.UNSUPPORTED ->
                update {
                    it.copy(questionBlocks = it.questionBlocks + (questionId to QuestionBlock.LOCAL_ONLY))
                }
            QuestionAnswerFailure.UNCERTAIN ->
                update {
                    it.copy(
                        questionBlocks =
                            it.questionBlocks +
                                (questionId to
                                    (it.questionBlocks[questionId] ?: QuestionBlock.UNCERTAIN))
                    )
                }
            QuestionAnswerFailure.OFFLINE -> reportError(R.string.remote_question_session_offline)
            QuestionAnswerFailure.OTHER -> reportError(R.string.remote_request_error)
        }
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

    // ---- Tickets (tickets.v1) ------------------------------------------------------------

    private fun requireTickets() {
        val current = state.value
        if (!current.connected) throw RemoteRequestException("offline")
        // An older host closes the connection on a command it does not know.
        if (!canUseTickets(current)) throw RemoteRequestException.unsupported()
    }

    override suspend fun ticketProjects(): List<TicketProject> {
        requireTickets()
        return parseTicketProjects(connectionRequest("tickets.projects"))
    }

    override suspend fun ticketsPage(projectId: String, cursor: String?): TicketsPage {
        requireTickets()
        requireTicketProjectId(projectId)
        requireTicketCursor(cursor)
        val fields = listOfNotNull("projectId" to projectId, cursor?.let { "cursor" to it })
        return parseTicketsPage(connectionRequest("tickets.list", *fields.toTypedArray()), projectId)
    }

    override suspend fun ticketDetail(projectId: String, ref: String): TicketDetail {
        requireTickets()
        requireTicketProjectId(projectId)
        requireTicketRef(ref)
        val data = connectionRequest("tickets.get", "projectId" to projectId, "ref" to ref)
        return parseTicketDetail(data, projectId).also { require(it.ticket == null || it.ticket.ref == ref) }
    }

    override suspend fun reloadTickets(projectId: String): Long {
        requireTickets()
        requireTicketProjectId(projectId)
        return parseTicketReloaded(connectionRequest("tickets.reload", "projectId" to projectId), projectId)
    }

    // The writes go through folderRequest: leaving the screen must not drop the reply of a write.
    override suspend fun commentTicket(projectId: String, ref: String, text: String) {
        requireTickets()
        requireTicketProjectId(projectId)
        requireTicketRef(ref)
        requireTicketComment(text)
        parseTicketCommented(
            folderRequest("tickets.comment", "projectId" to projectId, "ref" to ref, "text" to text),
            projectId,
            ref,
        )
    }

    override suspend fun moveTicket(projectId: String, ref: String, state: String, comment: String?): Boolean {
        requireTickets()
        requireTicketProjectId(projectId)
        requireTicketRef(ref)
        requireTicketMove(state, comment)
        val fields = listOfNotNull("projectId" to projectId, "ref" to ref, "state" to state, comment?.let { "comment" to it })
        return parseTicketMoved(folderRequest("tickets.move", *fields.toTypedArray()), projectId, ref, state)
    }

    override suspend fun startTicket(projectId: String, ref: String, note: String?): String {
        requireTickets()
        requireTicketProjectId(projectId)
        requireTicketRef(ref)
        requireTicketNote(note)
        val fields = listOfNotNull("projectId" to projectId, "ref" to ref, note?.let { "note" to it })
        return parseTicketStartStarted(folderRequest("tickets.start", *fields.toTypedArray()))
    }

    override suspend fun ticketStartStatus(startId: String): TicketStart {
        requireTickets()
        requireStartId(startId)
        return parseTicketStart(folderRequest("tickets.start.status", "startId" to startId), startId)
    }

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

    private val ticketsLoader =
        TicketsLoader(
            scope,
            object : TicketSource {
                override suspend fun ticketProjects() = this@DefaultRemoteRepository.ticketProjects()

                override suspend fun ticketsPage(projectId: String, cursor: String?) =
                    this@DefaultRemoteRepository.ticketsPage(projectId, cursor)

                override suspend fun ticketDetail(projectId: String, ref: String) =
                    this@DefaultRemoteRepository.ticketDetail(projectId, ref)

                override suspend fun reloadTickets(projectId: String) =
                    this@DefaultRemoteRepository.reloadTickets(projectId)

                override suspend fun commentTicket(projectId: String, ref: String, text: String) =
                    this@DefaultRemoteRepository.commentTicket(projectId, ref, text)

                override suspend fun moveTicket(projectId: String, ref: String, state: String, comment: String?) =
                    this@DefaultRemoteRepository.moveTicket(projectId, ref, state, comment)

                override suspend fun startTicket(projectId: String, ref: String, note: String?) =
                    this@DefaultRemoteRepository.startTicket(projectId, ref, note)

                override suspend fun ticketStartStatus(startId: String) =
                    this@DefaultRemoteRepository.ticketStartStatus(startId)
            },
            { state.value },
            now,
            ::update,
        )

    override fun refreshTickets(force: Boolean) = ticketsLoader.refresh(force)

    override fun openTicket(projectId: String, ref: String) = ticketsLoader.loadDetail(projectId, ref)

    override fun queueAccept(projectId: String, ref: String, comment: String) =
        ticketsLoader.queueAccept(projectId, ref, comment)

    override fun undoAccept() = ticketsLoader.undo()

    override fun dismissTicketNotice() = ticketsLoader.dismissNotice()

    override fun commentOnTicket(projectId: String, ref: String, text: String) =
        ticketsLoader.comment(projectId, ref, text)

    override fun moveTicketTo(projectId: String, ref: String, state: String, comment: String?) =
        ticketsLoader.move(projectId, ref, state, comment)

    override fun startTicketJob(projectId: String, ref: String, note: String?) =
        ticketsLoader.start(projectId, ref, note)

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
            now,
            ::update,
        )

    // On a phone changes and files share one place on screen: opening one closes the other,
    // which also stops its loader. A side inspector shows both as tabs, and each loader closes
    // only with its own tab.
    override fun openChanges() = openChanges(exclusive = true)

    override fun openChanges(exclusive: Boolean) {
        if (exclusive) {
            // An unsaved edit asks first; its prompt runs this again through confirmFileEditorLeave.
            if (filesLoader.requestLeave(EditorLeave.CHANGES)) return
            filesLoader.close()
        }
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
        val routeId = state.value.selection.routeId
        if (
            attachment.kind != "image" ||
                attachment.size !in 0..MAX_ATTACHMENT_IMAGE_BYTES ||
                !canonicalAttachmentId(attachment.id) ||
                !opaqueId(sessionId) ||
                routeId == null ||
                sessionId != state.value.selection.sessionId
        ) return AttachmentReadResult.Unavailable
        val key = AttachmentByteCache.Key(routeId, sessionId, attachment.id, attachment.sha256)
        attachmentBytes[key]?.let { return AttachmentReadResult.Loaded(it) }
        // Captured before the disk read suspends: a switch made meanwhile must not reach this read.
        val epoch = selectionEpoch
        // A picture kept on disk is the same bytes: it opens without the Mac, offline too.
        withContext(io) { media?.get(routeId, attachment.sha256) }?.let {
            attachmentBytes[key] = it
            return AttachmentReadResult.Loaded(it)
        }
        if (!stillSelected(epoch, routeId, sessionId)) return AttachmentReadResult.Failed
        val current = state.value
        if (!current.connected) return AttachmentReadResult.Failed
        if (ATTACHMENT_READ_CAPABILITY !in current.capabilities)
            // Until projects.list has been answered the host's capabilities are not known yet.
            return if (current.capabilitiesKnown) AttachmentReadResult.Unsupported
            else AttachmentReadResult.Failed
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
        val bytes = reassembler.bytes()
        attachmentBytes[key] = bytes
        keepMedia(key.routeId) { it.put(key.routeId, attachment.sha256, bytes) }
        return AttachmentReadResult.Loaded(bytes)
    }

    // ---- Agent images (session.files.media.v1) --------------------------------------------

    private class ProjectImageRead {
        lateinit var job: Deferred<ProjectImageResult>
        var waiters = 0
    }

    private val projectImageBytes = ProjectImageByteCache()
    /**
     * The images whose disk pointer this run has read from the Mac. A pointer kept from an earlier
     * run may name a file the agent has since overwritten, so while the Mac can answer, it is asked
     * once before the kept copy counts; offline, the kept copy shows.
     */
    private val confirmedImages: MutableSet<ProjectImageByteCache.Key> = Collections.synchronizedSet(mutableSetOf())
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
        val routeId = state.value.selection.routeId
        if (!opaqueId(sessionId) || !validMediaPath(path) || routeId == null || sessionId != state.value.selection.sessionId)
            return ProjectImageResult.Unavailable
        val cacheKey = ProjectImageByteCache.Key(routeId, sessionId, path)
        if (!fresh) projectImageBytes[cacheKey]?.let { return it }
        // Captured before the disk read suspends: a switch made meanwhile must not reach this read.
        val epoch = selectionEpoch
        // A kept copy is the bytes the host sent before; a fresh read asks the Mac again, and so
        // does the first read this run of a copy kept from an earlier one while the Mac can answer.
        val confirmed = cacheKey in confirmedImages
        if (!fresh && (confirmed || !mediaReadable(state.value))) diskImage(routeId, sessionId, path)?.let {
            // Unconfirmed, it stays out of memory: once connected, the next read asks the Mac.
            if (confirmed) projectImageBytes[cacheKey] = it
            return it
        }
        if (!stillSelected(epoch, routeId, sessionId)) return ProjectImageResult.Failed
        val current = state.value
        if (!current.connected) return ProjectImageResult.Failed
        if (FILES_MEDIA_CAPABILITY !in current.capabilities || FILES_MEDIA_CAPABILITY in current.unavailableCapabilities)
            // Until projects.list has been answered the host's capabilities are not known yet.
            return if (current.capabilitiesKnown) ProjectImageResult.Unsupported else ProjectImageResult.Failed
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

    /** The Mac is connected and reads project images, so a kept pointer can be checked with it. */
    private fun mediaReadable(current: RemoteState): Boolean =
        current.connected && FILES_MEDIA_CAPABILITY in current.capabilities &&
            FILES_MEDIA_CAPABILITY !in current.unavailableCapabilities

    /** The image [path] of [sessionId] as the disk cache holds it, or null. */
    private suspend fun diskImage(routeId: String, sessionId: String, path: String): ProjectImageResult.Loaded? =
        withContext(io) {
            val cache = media ?: return@withContext null
            val pointer = cache.pointer(routeId, sessionId, path) ?: return@withContext null
            val bytes = cache.get(routeId, pointer.sha256) ?: return@withContext null
            ProjectImageResult.Loaded(pointer.path, pointer.mimeType, pointer.sha256, bytes)
        }

    /** Whether the selection is still the one a read started on, by [epoch], [routeId] and [sessionId]. */
    private fun stillSelected(epoch: Long, routeId: String, sessionId: String): Boolean =
        epoch == selectionEpoch && state.value.selection.routeId == routeId && state.value.selection.sessionId == sessionId

    /**
     * Keeps a copy of media on disk for [routeId] while the caller carries on: the image on screen,
     * or the send, does not wait for the encrypted write. A copy not written before a restart is only
     * a download again. A Mac unpaired meanwhile keeps nothing; its folder goes with the unpair.
     */
    private fun keepMedia(routeId: String, write: (MediaDiskCache) -> Unit) {
        val cache = media ?: return
        if (routeId in removedRoutes) return
        scope.launch(io) {
            try {
                write(cache)
            } catch (e: Exception) {
                Diagnostics.caught("repo.keepMedia", e)
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
                        confirmedImages += cacheKey
                        // A kept pointer from an earlier run may have named older bytes, too.
                        ProjectImageBitmaps.dropStale(sessionId, path, it.sha256)
                        keepMedia(cacheKey.routeId) { cache ->
                            cache.put(cacheKey.routeId, it.sha256, it.bytes)
                            cache.putPointer(
                                cacheKey.routeId, sessionId, path,
                                MediaPointer(it.path, it.mimeType, it.sha256),
                            )
                        }
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
        val routeId = state.value.selection.routeId
        if (!opaqueId(sessionId) || !validMediaPath(path) || routeId == null || sessionId != state.value.selection.sessionId)
            return ProjectArtifactResult.Unavailable
        val cacheKey = ProjectArtifactCache.Key(routeId, sessionId, path)
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
        // artifactAccess() guarantees that the selection is this session, so its route is known.
        val routeId = state.value.selection.routeId ?: return SessionArtifactOpenResult.Unavailable
        if (version != null) {
            val logical = "artifacts/$artifactId/$version"
            for (kind in listOf(ArtifactType.HTML, ArtifactType.MERMAID))
                if (type == null || type == kind)
                    projectArtifacts[artifactCacheKey(routeId, sessionId, "$logical.${kind.mime.extension}")]
                        ?.takeIf { sha256 == null || it.sha256 == sha256 }
                        ?.let { return SessionArtifactOpenResult.Text(kind, it) }
            if (type == null || type == ArtifactType.SVG)
                projectImageBytes[artifactImageCacheKey(routeId, sessionId, "$logical.svg")]
                    ?.takeIf { sha256 == null || it.sha256 == sha256 }
                    ?.let { return SessionArtifactOpenResult.Svg(it) }
        }
        val epoch = selectionEpoch
        return try {
            projectArtifactDownloads.withPermit { fetchSessionArtifact(epoch, routeId, sessionId, artifactId, version, type) }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            SessionArtifactOpenResult.ConnectionFailure
        }
    }

    private fun artifactCacheKey(routeId: String, sessionId: String, logicalPath: String) =
        ProjectArtifactCache.Key(routeId, sessionId, artifactCachePath(logicalPath))

    private fun artifactImageCacheKey(routeId: String, sessionId: String, logicalPath: String) =
        ProjectImageByteCache.Key(routeId, sessionId, artifactCachePath(logicalPath))

    private suspend fun fetchSessionArtifact(
        epoch: Long,
        routeId: String,
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
                                .also { if (version != null) projectImageBytes[artifactImageCacheKey(routeId, sessionId, meta.path)] = it }
                        )
                    } else {
                        // The host's sniff is not trusted: the bytes must be text on this side as well.
                        val text = decodeArtifactHtml(download.bytes)
                        if (text == null) SessionArtifactOpenResult.NotAnArtifact
                        else {
                            val loaded = ProjectArtifactResult.Loaded(meta.path, meta.sha256, text, download.bytes.size)
                            if (version != null) projectArtifacts[artifactCacheKey(routeId, sessionId, meta.path)] = loaded
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
        val runs = current.session?.optionalText("origin") == "rpc" || extensionControls(current, ExtensionControl.RELOAD)
        if (!runs || RELOAD_CAPABILITY !in current.capabilities || RELOAD_CAPABILITY in current.unavailableCapabilities)
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
            val refused = e.isExtensionControlRefusal && extensionControls(current, ExtensionControl.RELOAD)
            if (refused) markSessionControlUnsupported(sessionId, ExtensionControl.RELOAD)
            val failure = if (refused) ReloadFailure.ReloadPi else reloadFailure(e)
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

    // ---- Shell commands (session.bash.v1) -------------------------------------------------

    /** The streamed output of [RemoteState.bash] while it runs. */
    private var bashStream = BashOutputStream()

    private fun updateBash(requestId: String, block: (BashRun) -> BashRun) =
        update { state -> state.bash?.takeIf { it.requestId == requestId }?.let { state.copy(bash = block(it)) } ?: state }

    override fun runBash(command: String, excludeFromContext: Boolean): Boolean {
        val current = state.value
        val sessionId = current.selection.sessionId ?: return false
        if (!canRunShellCommands(current) || !bashCommandFits(command) || current.bash?.running == true ||
            activeHost?.routeId != current.selection.routeId)
            return false
        val requestId = Wire.random()
        val epoch = selectionEpoch
        bashStream = BashOutputStream()
        update { it.copy(bash = BashRun(sessionId, requestId, command, excludeFromContext)) }
        scope.launch {
            var result: BashResult? = null
            var failure: BashFailure? = null
            try {
                val data =
                    request(
                        "session.bash",
                        epoch,
                        *bashFields(sessionId, command, excludeFromContext),
                        requestId = requestId,
                        // Set before the send: one that throws may still have reached the Mac.
                        onSent = { updateBash(requestId) { it.copy(sent = true) } },
                        timeoutMillis = BASH_REQUEST_TIMEOUT_MILLIS,
                    )
                result =
                    try {
                        parseBashResult(data, sessionId)
                    } catch (malformed: Exception) {
                        Diagnostics.caught("repo.runBash.result", malformed)
                        failure = BashFailure.FAILED
                        null
                    }
            } catch (e: CancellationException) {
                // A selection change or a lost connection cancels the request, not this coroutine.
                currentCoroutineContext().ensureActive()
                failure = BashFailure.CONNECTION
            } catch (e: Exception) {
                Diagnostics.caught("repo.runBash", e)
                failure = bashFailure(e)
            }
            val ended = result
            val reason = failure ?: BashFailure.FAILED
            val lines = ended?.let(::bashResultLines).orEmpty()
            updateBash(requestId) { run ->
                when {
                    !run.running -> run
                    ended != null -> run.copy(result = ended, lines = lines, firstLine = 0, stopping = false)
                    else -> run.copy(failure = reason, stopping = false)
                }
            }
        }
        return true
    }

    /** Output of this phone's running command; another request's, or any after the result, is dropped. */
    private fun receiveBashOutput(event: BashOutput) {
        val run = state.value.bash ?: return
        if (!run.running || run.requestId != event.requestId || run.sessionId != event.sessionId) return
        if (!bashStream.append(event)) return
        val stream = bashStream
        updateBash(run.requestId) {
            if (it.running) it.copy(lines = stream.lines, firstLine = stream.firstLine, shownBytes = stream.bytes) else it
        }
    }

    override fun abortBash() {
        val current = state.value
        val run = current.bash ?: return
        if (!run.stoppable || run.stopping || !current.connected || run.sessionId != current.selection.sessionId) return
        val epoch = selectionEpoch
        updateBash(run.requestId) { it.copy(stopping = true) }
        // A run that ended without its result waits for no result: the attempt itself ends the stop.
        val ended = !run.running
        scope.launch {
            var accepted = false
            try {
                validatedBashAborted(request("session.bash.abort", epoch, "sessionId" to run.sessionId), run.sessionId)
                accepted = true
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Diagnostics.caught("repo.abortBash", e)
                // `not_running`: the command ended meanwhile, and its result is on its way.
                if (!ended && e.hostCode != "not_running") updateBash(run.requestId) { it.copy(stopping = false) }
            } finally {
                if (ended)
                    updateBash(run.requestId) { it.copy(stopping = false, stopAttempted = true, stopSent = accepted) }
            }
        }
    }

    override fun closeBash() {
        update { if (it.bash?.running == false) it.copy(bash = null) else it }
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

    override fun openFiles() = openFiles(exclusive = true)

    override fun openFiles(exclusive: Boolean) {
        // The pane already shows the open editor; opening the browser again would drop its edit.
        val current = state.value
        if (current.files?.takeIf { it.sessionId == current.selection.sessionId }?.editor != null) return
        if (exclusive) changesLoader.close()
        filesLoader.open()
    }

    override fun closeFiles() = filesLoader.close()

    override fun openFilesDir(path: String) = filesLoader.openDir(path)

    override fun openFilesFile(path: String?) = filesLoader.openFile(path)

    override fun openFileAt(path: String, lines: LineSelection?, exclusive: Boolean): Boolean {
        // An open editor stays, as with openFiles; its own prompt guards the unsaved edit. Nothing
        // opens then, so the caller says so (it copies the path) rather than showing that editor.
        val current = state.value
        if (!isSessionPath(path)) return false
        if (current.files?.takeIf { it.sessionId == current.selection.sessionId }?.editor != null) return false
        if (exclusive) changesLoader.close()
        return filesLoader.openAt(path, lines)
    }

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

/** What a restart needs to resolve a send the host did not confirm; a change to it is written at once. */
private data class SendJournal(
    val mutationId: String?,
    val submittedText: String?,
    val submittedQuote: MessageQuote?,
    val submittedAttachments: List<LocalAttachment>,
    val followUps: List<PendingFollowUp>,
    val submittedDelivery: String?,
)

private fun StoredDraft.journal() =
    SendJournal(mutationId, submittedText, submittedQuote, submittedAttachments, followUps, submittedDelivery)

/** The drafts that hold a send journal; a draft that is only typed in has none, so typing never counts. */
private fun Map<DraftKey, StoredDraft>.journals(): Map<DraftKey, SendJournal> =
    filterValues { it.mutationId != null || it.followUps.isNotEmpty() }.mapValues { (_, draft) -> draft.journal() }

/**
 * A plain prompt cleared from the composer while it was sent keeps its text only in the journal.
 * Once the send is unconfirmed, that text goes back into the composer so it can be sent again;
 * text typed since stays where it is, and the pending bubble keeps showing what was sent.
 */
private fun StoredDraft.restoringSubmitted(): StoredDraft {
    val submitted = submittedText ?: return this
    if (mutationId == null || text.isNotEmpty()) return this
    return copy(
        text = submitted,
        quote = quote ?: submittedQuote,
        attachments = attachments.ifEmpty { submittedAttachments },
    )
}

/**
 * The pending prompt once the timeline may show the host's copy of it: a confirmed prompt leaves
 * the chat when a user message sent after the chat's newest message at the tap reads as it does.
 * Checked on every timeline change, since the acknowledgement and the message can arrive in either
 * order. An identical prompt that was already in the chat never settles it.
 */
private fun settledSend(pending: PendingSend?, messages: List<JsonObject>): PendingSend? {
    if (pending == null || pending.state != PendingSendState.CONFIRMED) return pending
    val baseline = pending.afterMessageId?.let { id -> messages.indexOfFirst { it.optionalText("id") == id } }
    // A baseline the timeline no longer holds leaves every message a candidate.
    val sentSince = messages.drop(if (baseline == null || baseline < 0) 0 else baseline + 1)
    val wanted = promptKey(pending.text)
    val arrived = sentSince.any { it.text("role") == "user" && promptKey(userMessageBody(it)) == wanted }
    return if (arrived) null else pending
}

/** Prompt text as the host and the phone compare it: line endings and surrounding whitespace aside. */
private fun promptKey(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n').trim()
