package de.joinnoah.pi.remote

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.handwriting.handwritingDetector
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first

private data class AbortRunTarget(val sessionId: String, val questionIds: Set<String>)

/** A jump to a conversation item; [nonce] makes a repeated jump to the same item run again. */
private data class JumpRequest(val itemId: String, val nonce: Long)

/** Counts the list items the chat emits before the conversation, for index-based jumps. */
private class LeadingItems {
    var count = 0
}

/** Rail marker for a pending question; it has no conversation item and jumps to the latest one. */
private const val PENDING_QUESTION_MARKER = "__pending_question__"

/** From this header width on, History gets its own button beside the action pill. */
private val HISTORY_BUTTON_MIN_HEADER = 600.dp
private const val CHILD_REFRESH_ATTEMPTS = 12
private const val CHILD_REFRESH_INTERVAL_MILLIS = 5_000L
private const val TOUCHED_LINES_STREAMING_MILLIS = 1_000L
private const val JUMP_HIGHLIGHT_MILLIS = 2_000L
// How long a pull's indicator stays: a pull reads the lists again, and they answer later.
private const val PULL_REFRESH_INDICATOR_MILLIS = 1_200L
private const val COMPACTION_TICK_MILLIS = 30_000L
/** How long review mode waits for the Changes pane Ctrl+Shift+D opened for it. */
private const val REVIEW_OPEN_GRACE_MILLIS = 2_000L
private const val PRIVACY_POLICY_URL = "https://robinrehbein.de/privacy"
private val LIST_TO_HEADER_END_SHIFT = 4.dp

/** Keys with which Compose moves focus into a focused group's first child. */
private val FOCUS_ENTER_KEYS = setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter)

/** "Try again", or "Reconnect now" while an automatic reconnect is scheduled. */
@Composable
private fun retryLabel(reconnectAt: Long?): String =
    stringResource(
        if (reconnectAt != null) R.string.remote_reconnect_now else R.string.remote_error_retry
    )

/** Live "Reconnecting in N s" line for the next automatic reconnect attempt. */
@Composable
private fun ReconnectCountdown(reconnectAt: Long?, now: () -> Long = System::currentTimeMillis) {
    if (reconnectAt == null) return
    val seconds by produceState(reconnectSeconds(reconnectAt, now), reconnectAt) {
        while (true) {
            value = reconnectSeconds(reconnectAt, now)
            delay(250)
        }
    }
    Text(
        stringResource(R.string.remote_reconnecting_in, seconds),
        Modifier.testTag("reconnectCountdown"),
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun reconnectSeconds(reconnectAt: Long, now: () -> Long): Int =
    ((reconnectAt - now() + 999) / 1000).toInt().coerceAtLeast(0)

/** The space between the items of the chat and of the list pages. */
private val LIST_ITEM_SPACING = 12.dp

/** Keys of the chat list's own rows beside the messages, like "waiting-for-output". */
internal const val CHAT_SKELETON_KEY = "chat-skeleton"
internal const val LOAD_OLDER_KEY = "load-older"

/**
 * The chat list's content type of [item], so a scrolled-out row is reused only for one laid out
 * the same way: a user and an assistant bubble differ, like a tool row and a thinking row.
 */
internal fun conversationContentType(item: ConversationItem): String =
    when (item) {
        is ConversationItem.Bubble -> if (item.role == "user") "bubble-user" else "bubble"
        is ConversationItem.Thinking -> "thinking"
        is ConversationItem.Activity -> "activity"
        is ConversationItem.Subagent -> "subagent"
    }

/** Errors a reload can plausibly fix; only these offer "Try again" on the error card. */
private val RETRYABLE_ERRORS =
    setOf(
        R.string.remote_connection_error,
        R.string.remote_unreachable,
        R.string.remote_request_error,
        R.string.remote_configuration_error,
        R.string.remote_commands_error,
        R.string.remote_device_paused,
    )

@Composable
internal fun RemoteScreen(
    key: RemoteNavKey,
    model: DestinationViewModel,
    navigator: RemoteNavigator,
    pushConfigured: Boolean,
    pushEnabled: Boolean,
    enablePush: () -> Unit,
    disablePush: () -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    // The first list the user came for is on screen: the time to full display that startup
    // benchmarks measure. A list from the caches is not it until the Mac confirms it.
    when (key) {
        RemoteNavKey.Hosts -> ReportDrawnWhen { state.hostsLoaded || state.hostsUnreadable || state.hosts.isNotEmpty() }
        // Offline or asleep, a Mac never confirms its lists: the cached page, settled, is what shows.
        is RemoteNavKey.Projects, is RemoteNavKey.Sessions -> ReportDrawnWhen {
            if (state.connected) !state.loading else state.hostsLoaded && !state.loading
        }
        else -> Unit
    }
    val context = LocalContext.current
    val settings = key == RemoteNavKey.Settings
    val preferences =
        (model as? SettingsViewModel)?.preferences?.collectAsStateWithLifecycle()?.value
    val theme = preferences?.theme ?: "system"
    val chatPreferences =
        (model as? ChatViewModel)?.preferences?.collectAsStateWithLifecycle()?.value
    val sessionPreferences =
        (model as? SessionsViewModel)?.preferences?.collectAsStateWithLifecycle()?.value
    val hideOfflineSessions = sessionPreferences?.hideOfflineSessions ?: false
    // Two-pane roles: the session list on the left, the chat on the right. Both null/false in the
    // phone layout, which then renders exactly as before.
    val paneLayout = LocalPaneLayout.current
    val twoPane = LocalTwoPane.current
    val listPane = LocalListPane.current?.takeIf { key is RemoteNavKey.Sessions }
    // The projects column of the tablet layout; a selected project has its sessions beside it.
    val projectsColumn = LocalProjectsColumn.current?.takeIf { key is RemoteNavKey.Projects }
    val projectSelected = projectsColumn?.selectedProjectId != null
    val detailPane = LocalDetailPane.current && key is RemoteNavKey.Chat
    // The layout opens the inspector beside this chat (see sideInspector below for where it
    // actually does).
    val sideCapable = key is RemoteNavKey.Chat && paneLayout.inspector != InspectorMode.OVERLAY
    // With a hardware keyboard Enter and Ctrl+Enter send at every window size, phones included.
    val hardwareKeyboard = LocalHardwareKeyboard.current
    val shortcutTargets = LocalShortcutTargets.current
    var sessionQuery by rememberSaveable(key) { mutableStateOf("") }
    // The phone's session list shows its search field behind a search icon.
    var sessionSearchOpen by rememberSaveable(key) { mutableStateOf(false) }
    val sessionSearchShown = key is RemoteNavKey.Sessions && (listPane != null || sessionSearchOpen)
    var collapsedSessionIds by rememberSaveable(key) { mutableStateOf(arrayListOf<String>()) }
    val collapsedIds = collapsedSessionIds.toSet()
    // Pinned sessions list first and unread ones carry their dot; the open chat is never unread.
    val sessionMarkStore = LocalSessionMarks.current
    val sessionMarkMap by sessionMarksState()
    val routeMarks = remember(sessionMarkMap, state.host?.routeId) { sessionMarksById(sessionMarkMap, state.host?.routeId) }
    // The selection names the chat beside the list; the screen's state strips the chat's session.
    val shownSessionId = state.selection.sessionId
    val visibleSessions =
        remember(state.sessions, state.connected, state.loading, state.stale, hideOfflineSessions, collapsedIds, routeMarks) {
            visibleSessionListItems(
                state.sessions,
                state.connected,
                state.loading,
                hidesOfflineSessions(hideOfflineSessions, state.connected, state.stale),
                collapsedIds,
                stale = state.stale,
                marks = routeMarks,
                openSessionId = shownSessionId,
            )
        }
    val listedSessions =
        remember(visibleSessions, sessionQuery, sessionSearchShown) {
            if (sessionSearchShown) filterSessions(visibleSessions, sessionQuery) else visibleSessions
        }
    if (key is RemoteNavKey.Sessions && shortcutTargets != null) {
        LaunchedEffect(listedSessions) { shortcutTargets.listed(key, listedSessions.map { it.id }) }
        // Off screen the list's rows go stale; the shortcuts then read the project's sessions.
        DisposableEffect(key, shortcutTargets) { onDispose { shortcutTargets.unlisted(key) } }
    }
    if (shortcutTargets != null)
        DisposableEffect(key, model, shortcutTargets) {
            val refresh = model::refresh
            shortcutTargets.registerRefresh(key, refresh)
            onDispose { shortcutTargets.unregisterRefresh(key, refresh) }
        }
    val sessionCount =
        remember(state.sessions, state.connected, state.loading, state.stale, hideOfflineSessions) {
            visibleSessionListItems(
                state.sessions,
                state.connected,
                state.loading,
                hidesOfflineSessions(hideOfflineSessions, state.connected, state.stale),
                stale = state.stale,
            ).size
        }
    val sessionActive =
        state.connected && !state.loading && state.status in setOf("running", "waiting")
    // Built per message: a streamed delta rebuilds the items of the message it changed, and what
    // is derived below reuses the rest (docs/runbooks/performance.md).
    val presenter = remember(key) { ConversationPresenter() }
    val presentation =
        remember(presenter, state.messages, sessionActive) { presenter.present(state.messages, sessionActive) }
    // The prompt the timeline does not show yet goes at the tail, where the reply will follow it.
    val conversation =
        remember(presentation, state.pendingSend) {
            state.pendingSend?.let { presentation.items + pendingSendItem(it) } ?: presentation.items
        }
    val thinkingDisplay = chatPreferences?.thinkingDisplay ?: "status"
    val thinkingActive = state.connected && !state.loading && state.status == "running"
    val visibleConversation =
        remember(conversation, thinkingDisplay, thinkingActive) {
            conversation.filter { conversationItemVisible(it, thinkingDisplay, thinkingActive) }
        }
    val chatKey = key as? RemoteNavKey.Chat
    val chatModel = model as? ChatViewModel
    // The composer's text lives here rather than in the state, so typing recomposes only the
    // composer; this screen's own draft changes go through it or flush it first. It belongs to
    // this destination's chat from the start, so text typed before a load or a navigation lands
    // there. It takes edits once the chat is selected and its stored draft shown, also while the
    // chat loads (a reconnect, a resume); before that it takes none.
    val composerDraft =
        if (chatKey != null)
            rememberComposerDraft(
                chatKey.selection(),
                state.draft,
                editable = state.selection == chatKey.selection(),
                push = model::draft,
            )
        else null
    val jobsAvailable = canUseBackgroundJobs(state)
    val jobsLeasable = canLeaseJobsList(state)
    // A destination cannot refresh while loading. Retry when its snapshot is ready so the
    // initial list (and its event lease) is not lost when capabilities arrive before the snapshot.
    LaunchedEffect(state.selection.sessionId, state.connected, state.loading, jobsAvailable, jobsLeasable) {
        if (state.connected && !state.loading && jobsAvailable) chatModel?.refreshJobs()
    }
    val statusJobs = visibleJobs(state)?.takeIf { chatModel != null }
    val sentImages =
        state.selection.sessionId?.takeIf { chatModel != null }?.let { sessionId ->
            val supported = ATTACHMENT_READ_CAPABILITY in state.capabilities
            val routeId = state.selection.routeId
            remember(chatModel, routeId, sessionId, state.connected, state.capabilitiesKnown, supported) {
                SentImageSource(
                    routeId.orEmpty(), sessionId, state.connected, state.capabilitiesKnown, supported,
                    checkNotNull(chatModel)::readAttachment,
                )
            }
        }
    val projectImages =
        state.selection.sessionId?.takeIf { chatModel != null }?.let { sessionId ->
            val supported = FILES_MEDIA_CAPABILITY in state.capabilities
            val artifactSupported = FILES_ARTIFACT_CAPABILITY in state.capabilities
            remember(chatModel, sessionId, state.connected, state.capabilitiesKnown, supported, artifactSupported) {
                ProjectImageSource(
                    sessionId, state.connected, state.capabilitiesKnown, supported,
                    checkNotNull(chatModel)::readProjectImage,
                    checkNotNull(chatModel)::readProjectArtifact,
                    artifactSupported,
                )
            }
        }
    val touched = presentation.touchedFiles
    // Off the main thread, diffing only edits that are new or changed. While pi streams, the
    // counts follow at most every TOUCHED_LINES_STREAMING_MILLIS, from the latest conversation.
    val touchedLineCache = remember(key) { TouchedLineCache() }
    val latestConversation by rememberUpdatedState(conversation)
    val streamingNow by rememberUpdatedState(sessionActive)
    val touchedLines by
        produceState<TouchedLineCounts?>(null, touchedLineCache) {
            snapshotFlow { latestConversation }.conflate().collect { items ->
                value = withContext(Dispatchers.Default) { touchedLineCache.counts(items) }
                if (streamingNow) delay(TOUCHED_LINES_STREAMING_MILLIS)
            }
        }
    val errorCount = presentation.errorCount
    val children =
        remember(chatKey, state.sessions, state.connected, state.loading, state.stale) {
            if (chatKey == null) emptyList()
            else sessionListItems(state.sessions, state.connected, state.loading, state.stale)
        }
    val strip =
        remember(chatKey, presentation, children) {
            if (chatKey == null) SubagentStrip(emptyList(), 0, emptySet(), 0)
            else presenter.subagentStrip(presentation, children, chatKey.sessionId)
        }
    var onlyErrorsShown by rememberSaveable(key) { mutableStateOf(false) }
    val shownItems =
        remember(visibleConversation, onlyErrorsShown) {
            if (onlyErrorsShown) onlyErrors(visibleConversation) else visibleConversation
        }
    // Bubbles are always visible, so only the error filter narrows the conversation's set.
    val forkableIds =
        remember(presentation, shownItems, onlyErrorsShown) {
            if (onlyErrorsShown) forkableBubbleIds(shownItems) else presentation.forkableBubbleIds
        }
    val forkReady = state.connected && !state.loading && !navigator.mutating && forkStopped(state)
    val forkStopFirst = forkRunning(state)
    // The same instance while nothing it holds changes, so the bubbles that get it can skip.
    val messageFork =
        if (chatKey != null && canFork(state))
            remember(chatKey, navigator, forkReady, forkStopFirst) {
                MessageFork(
                    ready = forkReady,
                    stopFirst = forkStopFirst,
                    onFork = { messageId, mode -> navigator.forkSession(chatKey, messageId, mode) },
                )
            }
        else null
    val questionPending = state.questions.isNotEmpty()
    val markers =
        remember(shownItems, questionPending) {
            presenter.timelineMarkers(shownItems) +
                if (questionPending)
                    listOf(TimelineMarker(PENDING_QUESTION_MARKER, TimelineMarkerKind.QUESTION, shownItems.size))
                else emptyList()
        }
    var historyOpen by rememberSaveable(key) { mutableStateOf(false) }
    // The history lists the whole chat, also while the error filter narrows the list. It is built
    // only while open: a streamed delta rebuilds the conversation many times a second.
    val history =
        remember(visibleConversation, questionPending, historyOpen) {
            if (!historyOpen) emptyList()
            else
                chatHistory(visibleConversation) +
                    if (questionPending)
                        listOf(ChatHistoryEntry(PENDING_QUESTION_MARKER, TimelineMarkerKind.QUESTION, null))
                    else emptyList()
        }
    // The host announces no new child session, and progress can name a child before its bridge
    // registers: poll the session list for a while so the strip and "open" can find it.
    LaunchedEffect(chatKey, strip.missingSessionIds, strip.unmatchedRunning) {
        if (chatKey == null || (strip.missingSessionIds.isEmpty() && strip.unmatchedRunning == 0))
            return@LaunchedEffect
        repeat(CHILD_REFRESH_ATTEMPTS) {
            delay(CHILD_REFRESH_INTERVAL_MILLIS)
            chatModel?.refreshSessions()
        }
    }
    val viewportPositions = LocalChatViewportPositions.current
    var savedViewport by remember(chatKey, viewportPositions) {
        mutableStateOf(chatKey?.let { viewportPositions?.get(it) })
    }
    var viewportRestorePending by remember(key) {
        mutableStateOf(savedViewport?.following == false)
    }
    val listState = rememberLazyListState()
    // The large page title leaves the list for the header's back pill once it scrolls under the header.
    var headerSize by remember { mutableStateOf(IntSize.Zero) }
    val titleInPill by remember(listState) { derivedStateOf { pageTitleScrolledAway(listState.layoutInfo, headerSize.height) } }
    val sessionSearchFocus = remember { FocusRequester() }
    if (key is RemoteNavKey.Sessions && shortcutTargets != null)
        LaunchedEffect(shortcutTargets.searchRequests) {
            // Ctrl+F: each request focuses the search field once, also when it expanded the list
            // or opened the phone's search field.
            if (shortcutTargets.searchRequests == shortcutTargets.searchHandled) return@LaunchedEffect
            shortcutTargets.searchHandled = shortcutTargets.searchRequests
            sessionSearchOpen = true
            listState.scrollToItem(0)
            withFrameNanos { }
            runCatching { sessionSearchFocus.requestFocus() }
        }
    BackHandler(enabled = sessionSearchOpen && listPane == null) {
        sessionSearchOpen = false
        sessionQuery = ""
    }
    var composerSize by remember { mutableStateOf(IntSize.Zero) }
    val composerHeight = with(LocalDensity.current) { composerSize.height.toDp() }
    // The conversation list's after-padding: its last item can scroll to just above the composer.
    val conversationEndPadding = composerHeight + 24.dp
    val headerHeight = with(LocalDensity.current) { headerSize.height.toDp() }
    val listScope = rememberCoroutineScope()
    var autoFollow by rememberSaveable(key) { mutableStateOf(savedViewport?.following ?: true) }
    // The chat counts as read once it shows its end; a newer update after that is unread again.
    // The chat is only the selected one once the repository confirmed it; a chat opened from a
    // list, a notification or a restore first shows cached content with an empty selection.
    val chatSelected = chatKey != null && chatSelected(state, chatKey.sessionId)
    // The list row moves ahead of the session snapshot taken at selection while the chat runs.
    val chatUpdatedAt =
        remember(chatKey, state.sessions, state.session) {
            chatKey?.let { liveUpdatedAt(state, it.sessionId) }
        }
    LaunchedEffect(chatKey, autoFollow, chatUpdatedAt, chatSelected) {
        val chat = chatKey ?: return@LaunchedEffect
        if (autoFollow && chatSelected)
            sessionMarkStore?.markSeen(chat.routeId, chat.sessionId, chatUpdatedAt, System.currentTimeMillis())
    }
    // The chat counts as open for the marks only while it is on screen and the app is resumed.
    if (chatKey != null && sessionMarkStore != null)
        LifecycleResumeEffect(chatKey, sessionMarkStore) {
            sessionMarkStore.chatShown(chatKey.routeId, chatKey.sessionId)
            onPauseOrDispose { sessionMarkStore.chatHidden(chatKey.routeId, chatKey.sessionId) }
        }
    var tuiInfoOpen by remember { mutableStateOf(false) }
    var scrollingProgrammatically by remember { mutableStateOf(false) }
    var waitingForOutput by remember { mutableStateOf(false) }
    LaunchedEffect(sessionActive, state.status, state.questions, conversation) {
        waitingForOutput = false
        if (sessionActive && state.status == "running" && state.questions.isEmpty()) {
            delay(1200)
            waitingForOutput = true
        }
    }
    LaunchedEffect(key, listState) {
        if (key is RemoteNavKey.Chat)
            snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
                .collect { (scrolling, canScrollForward) ->
                    if (scrolling && !scrollingProgrammatically) {
                        if (!onlyErrorsShown) viewportRestorePending = false
                        autoFollow = !canScrollForward
                    }
                }
    }
    val viewportHeight = listState.layoutInfo.viewportSize.height
    LaunchedEffect(
        key, conversation, state.questions, state.error, waitingForOutput,
        viewportHeight, composerHeight, headerHeight, onlyErrorsShown, autoFollow,
    ) {
        // The error filter shows a fixed selection; following new output would only jump around.
        if (key is RemoteNavKey.Chat && autoFollow && !onlyErrorsShown && !viewportRestorePending) {
            withFrameNanos { }
            if (!autoFollow || onlyErrorsShown) return@LaunchedEffect
            scrollingProgrammatically = true
            try {
                listState.scrollToLatest()
            } finally {
                scrollingProgrammatically = false
            }
        }
    }
    val leadingItems = remember { LeadingItems() }
    LaunchedEffect(key, state.loading, shownItems, viewportHeight, composerHeight, headerHeight,
        viewportRestorePending, onlyErrorsShown, savedViewport) {
        val position = savedViewport ?: return@LaunchedEffect
        if (!viewportRestorePending || state.loading || onlyErrorsShown ||
            viewportHeight <= 0 || composerHeight == 0.dp || headerHeight == 0.dp) return@LaunchedEffect
        val index = restoredChatIndex(position, shownItems.map { it.id }, leadingItems.count)
            ?: return@LaunchedEffect
        withFrameNanos { }
        // User intent can change while waiting for the layout frame.
        if (!viewportRestorePending || onlyErrorsShown || state.loading) return@LaunchedEffect
        scrollingProgrammatically = true
        try {
            listState.scrollToItem(index, position.offset)
            viewportRestorePending = false
        } finally {
            scrollingProgrammatically = false
        }
    }
    val viewportSaveBlocked by rememberUpdatedState(
        viewportRestorePending || state.loading || onlyErrorsShown || shownItems.isEmpty()
    )
    LaunchedEffect(chatKey, viewportPositions, listState) {
        if (chatKey == null || viewportPositions == null) return@LaunchedEffect
        snapshotFlow {
            if (viewportSaveBlocked || listState.layoutInfo.totalItemsCount == 0) null
            else {
                val first = listState.firstVisibleItemIndex
                val anchor = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == first }?.key as? String
                ChatViewportPosition(
                    anchor, (first - leadingItems.count).coerceAtLeast(0),
                    listState.firstVisibleItemScrollOffset, autoFollow,
                )
            }
        }.collect { position ->
            if (position != null) viewportPositions[chatKey] = position
        }
    }
    var jumpRequest by remember { mutableStateOf<JumpRequest?>(null) }
    var highlightedId by remember { mutableStateOf<String?>(null) }
    val jumpFocus = remember { FocusRequester() }
    LaunchedEffect(highlightedId) {
        if (highlightedId == null) return@LaunchedEffect
        // The target item was just recomposed into the tree: let it lay out before focusing it.
        withFrameNanos { }
        runCatching { jumpFocus.requestFocus() }
    }
    val currentShownItems by rememberUpdatedState(shownItems)
    fun scrollToLatestItem() {
        viewportRestorePending = false
        autoFollow = true
        listScope.launch {
            scrollingProgrammatically = true
            try {
                listState.scrollToLatest()
            } finally {
                scrollingProgrammatically = false
            }
        }
    }
    fun jumpTo(itemId: String) {
        if (itemId == PENDING_QUESTION_MARKER) {
            scrollToLatestItem()
            return
        }
        if (onlyErrorsShown && shownItems.none { it.id == itemId }) onlyErrorsShown = false
        viewportRestorePending = false
        autoFollow = false
        jumpRequest = JumpRequest(itemId, (jumpRequest?.nonce ?: 0L) + 1)
    }
    LaunchedEffect(jumpRequest) {
        val request = jumpRequest ?: return@LaunchedEffect
        // Let the list measure first: turning the error filter off changes its items.
        withFrameNanos { }
        val index = currentShownItems.indexOfFirst { it.id == request.itemId }
        if (index < 0) return@LaunchedEffect
        highlightedId = null
        scrollingProgrammatically = true
        try {
            if (ValueAnimator.areAnimatorsEnabled()) listState.animateScrollToItem(leadingItems.count + index)
            else listState.scrollToItem(leadingItems.count + index)
        } finally {
            scrollingProgrammatically = false
        }
        highlightedId = request.itemId
        delay(JUMP_HIGHLIGHT_MILLIS)
        highlightedId = null
    }
    val listScrollable by remember {
        derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
    }
    var openToolId by rememberSaveable(key) { mutableStateOf<String?>(null) }
    val openTool =
        openToolId?.let { id ->
            conversation.firstOrNull { it.id == id } as? ConversationItem.Activity
        }
    fun closeTool() {
        val download = state.toolOutput
        if (
            openTool?.toolCallId != null && download?.toolCallId == openTool.toolCallId &&
                download.text == null && download.failure == null
        )
            chatModel?.cancelToolOutput()
        openToolId = null
    }
    LaunchedEffect(openToolId, openTool == null, state.loading) {
        // The item is gone (another session, or history reloaded without it): drop the overlay.
        if (openToolId != null && openTool == null && !state.loading) openToolId = null
    }
    val changesOpen =
        state.changes?.takeIf { it.sessionId == state.selection.sessionId && chatModel != null }
    val filesOpen = state.files?.takeIf { it.sessionId == state.selection.sessionId && chatModel != null }
    val jobsOpen =
        state.jobs?.let { (it.listOpen || it.view != null) && it.sessionId == state.selection.sessionId } == true
    // The touched files and the session's artifacts: sheets on a phone, tabs beside the chat.
    var touchedSheet by rememberSaveable(key) { mutableStateOf(false) }
    var artifactsSheetRequested by remember(key) { mutableStateOf(false) }
    // The artifact or image open in the ARTIFACT tab, and the newest versions a gallery listed.
    val viewerScope = rememberCoroutineScope()
    val viewerSlot = remember(key) { InspectorViewerSlot(viewerScope) }
    var listedVersions by remember(key) { mutableStateOf(emptyMap<String, Int>()) }
    /** The newest change [viewer] can show: the chat's last edit of its file, or its newest listed version. */
    fun viewerChange(viewer: InspectorViewer?): String? =
        when (val watch = (viewer as? InspectorViewer.Artifact)?.watch) {
            is ArtifactWatch.File -> artifactChange(conversation, watch.path)
            is ArtifactWatch.Listed -> maxOf(watch.version, listedVersions[watch.id] ?: 0).toString()
            null -> null
        }
    val openViewer = viewerSlot.viewer
    val openViewerChange = remember(openViewer, conversation, listedVersions) { viewerChange(openViewer) }
    LaunchedEffect(viewerSlot, openViewerChange) { viewerSlot.changed(openViewerChange) }
    // The inspector's tabs beside this chat. What backs each tab decides which exist: the
    // repository's changes, files and jobs, the open tool, the history, the open viewer, the
    // gallery and the touched files (see the reconcile below).
    var inspectorTabs by rememberSaveable(key, stateSaver = InspectorTabs.Saver) { mutableStateOf(InspectorTabs()) }
    /** Shows [tab] in the inspector and makes room for it; the caller opens what backs it. */
    fun showTab(tab: InspectorTab) {
        inspectorTabs = inspectorTabs.open(tab)
        // A phone's history is a sheet over everything else.
        if (!sideCapable && tab != InspectorTab.HISTORY) historyOpen = false
        // At once rather than after a frame, so a portrait chat never shows the pane over itself.
        if (sideCapable && detailPane && paneLayout.autoCollapseList) twoPane?.autoCollapseList()
    }
    /**
     * Opens [tab], or switches to it where it is open already. A side inspector keeps the changes
     * and the files open together; the phone shows one at a time. Wherever the inspector can go
     * beside the chat it does once [showTab] made room, which collapses a list in its way.
     */
    fun openInspector(tab: InspectorTab) {
        val chat = chatModel ?: return
        showTab(tab)
        when (tab) {
            InspectorTab.CHANGES -> if (changesOpen == null) chat.openChanges(exclusive = !sideCapable)
            InspectorTab.FILES -> if (filesOpen == null) chat.openFiles(exclusive = !sideCapable)
            InspectorTab.JOBS -> if (!jobsOpen) chat.openJobs()
            InspectorTab.HISTORY -> historyOpen = true
            InspectorTab.GALLERY -> artifactsSheetRequested = true
            InspectorTab.TOUCHED -> touchedSheet = true
            InspectorTab.TOOL, InspectorTab.ARTIFACT -> Unit
        }
    }
    /** Closes [tab] and what backs it; the tab before it becomes active. */
    fun closeTab(tab: InspectorTab) {
        inspectorTabs = inspectorTabs.close(tab)
        when (tab) {
            InspectorTab.CHANGES -> chatModel?.closeChanges()
            InspectorTab.FILES -> chatModel?.closeFiles()
            InspectorTab.JOBS -> chatModel?.closeJobs()
            InspectorTab.TOOL -> closeTool()
            InspectorTab.HISTORY -> historyOpen = false
            InspectorTab.ARTIFACT -> viewerSlot.close()
            InspectorTab.GALLERY -> artifactsSheetRequested = false
            InspectorTab.TOUCHED -> touchedSheet = false
        }
    }
    // The panes show beside the chat, or would if the user had not hidden them (Ctrl+\); set
    // once the tabs are known below, read by the actions.
    val panesBesideNow = remember { mutableStateOf(false) }
    // A file the chat names (a tool card, a touched file, a diff line, a path:line in pi's text)
    // opens in Files at its lines, as a tab beside the chat or over it on a phone.
    val copyPath = rememberCopyToClipboard(stringResource(R.string.remote_menu_copy_path))
    val copyPathNow by rememberUpdatedState(copyPath)
    val openFileNow by rememberUpdatedState<(FileTarget) -> Unit> { target ->
        val chat = chatModel
        if (chat != null) {
            if (chat.openFileAt(target.path, target.lines, exclusive = !sideCapable)) {
                showTab(InspectorTab.FILES)
                // Over the chat the tool detail is drawn above Files and would hide it.
                if (!panesBesideNow.value) closeTool()
            } else {
                // Files keeps an open editor, so nothing shows; the copy says the tap landed.
                copyPathNow(target.path)
            }
        }
    }
    val toolPaths = remember(touched) { (touched.changed + touched.read).map { it.path } }
    val folderHints = remember(state.session, state.project, toolPaths) { sessionFolderHints(state.session, state.project, toolPaths) }
    val canOpenFiles = chatModel != null && canBrowseFiles(state)
    val chatFileOpener = remember(key) { FileOpener(folderHints, canOpenFiles, { openFileNow(it) }, { copyPathNow(it) }) }
    SideEffect {
        chatFileOpener.hints = folderHints
        chatFileOpener.canOpen = canOpenFiles
    }
    val fileOpener = chatFileOpener.takeIf { chatKey != null && chatModel != null }
    /**
     * Ctrl+Shift+G, E, J, Ctrl+H and the history button: open [tab], or switch to it where it is
     * open behind another tab or hidden; the tab already shown, or a pane over the chat, closes.
     */
    fun toggleTab(tab: InspectorTab, open: Boolean, canOpen: Boolean): Boolean {
        when {
            open && (!panesBesideNow.value || (inspectorTabs.active == tab && !inspectorTabs.hidden)) -> closeTab(tab)
            open -> showTab(tab)
            canOpen -> openInspector(tab)
            else -> return false
        }
        return true
    }
    fun toggleHistory() {
        toggleTab(InspectorTab.HISTORY, historyOpen, canOpen = true)
    }
    /** `/artifacts`: the gallery's tab beside the chat, selected even when open behind another tab; a sheet on a phone. */
    fun openGallery() {
        if (sideCapable) openInspector(InspectorTab.GALLERY) else artifactsSheetRequested = true
    }
    val showViewer by rememberUpdatedState<(InspectorViewer) -> Unit> { viewer ->
        viewerSlot.show(viewer, viewerChange(viewer))
        showTab(InspectorTab.ARTIFACT)
    }
    // Where the inspector can go beside the chat, artifacts and images open in its tab.
    val inspectorViewers =
        remember(key) {
            InspectorViewers(
                show = { showViewer(it) },
                listed = { artifacts ->
                    listedVersions = listedVersions + artifacts.associate { it.id to maxOf(it.version, listedVersions[it.id] ?: 0) }
                },
            )
        }
    var childCandidates by remember(key) { mutableStateOf<List<SessionListItem>?>(null) }
    var abortChild by remember(key) { mutableStateOf<SubagentStripEntry?>(null) }
    val composerFocus = remember { FocusRequester() }
    var composerFocusRequest by remember { mutableStateOf(0) }
    // An expanded inspector leaves the chat only a strip (see InspectorRow).
    var chatStripped by remember { mutableStateOf(false) }
    LaunchedEffect(composerFocusRequest) {
        if (composerFocusRequest == 0) return@LaunchedEffect
        // A prompt filled in or a composer asked for behind the strip: the chat comes back first,
        // so the user sees what they type into.
        if (chatStripped && twoPane?.inspectorExpanded == true) {
            if (twoPane.reviewing) twoPane.leaveReview() else twoPane.inspectorExpanded = false
            withTimeoutOrNull(1_000) { snapshotFlow { chatStripped }.first { !it } }
        }
        withFrameNanos { }
        runCatching { composerFocus.requestFocus() }
    }
    // The chat's side of the layout's keys (Esc, typing, the chat shortcuts); see ChatKeyTarget.
    val chatKeyTarget = remember(key) { ChatKeyTarget() }
    if (chatKey != null && shortcutTargets != null)
        DisposableEffect(chatKey, chatKeyTarget, shortcutTargets) {
            shortcutTargets.register(chatKey, chatKeyTarget)
            onDispose { shortcutTargets.unregister(chatKey, chatKeyTarget) }
        }
    // A session this user just created starts with the composer focused, on every device.
    if (chatKey != null)
        LaunchedEffect(chatKey, navigator.freshSession) {
            if (navigator.takeFreshSession(chatKey)) composerFocusRequest++
        }
    // Text selected in the chat; Esc clears it by taking focus away from the selection.
    var conversationFocused by remember { mutableStateOf(false) }
    val chatFocus = remember { FocusRequester() }
    // Ctrl+Shift+N opens the rewind menu of this bubble once it is on screen.
    var forkMenuRequest by remember(key) { mutableStateOf<String?>(null) }
    // A session row's "Rewind from the latest message" opened this chat: once it has loaded, the
    // latest message's rewind menu opens as for Ctrl+Shift+N. A chat with nothing to rewind
    // simply stays open.
    if (chatKey != null && navigator.forkLatestRequest == chatKey) {
        val loaded = !state.loading && state.session?.optionalText("id") == chatKey.sessionId
        LaunchedEffect(chatKey, loaded) {
            if (!loaded || !navigator.takeForkLatest(chatKey)) return@LaunchedEffect
            val latest = shownItems.lastOrNull { it.id in forkableIds }
            if (messageFork != null && latest != null) {
                jumpTo(latest.id)
                forkMenuRequest = latest.id
            }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val childNotice = stringResource(R.string.remote_insights_child_not_found)
    // An extension's notice (`session.notice`) shows once, in the chat only, which holds the snackbar
    // host; the repository forgets it at once, so a recomposition or a return does not show it again.
    val notice = state.notice?.takeIf { key is RemoteNavKey.Chat && it.sessionId == state.selection.sessionId }
    LaunchedEffect(notice?.id) {
        val shown = notice ?: return@LaunchedEffect
        model.dismissNotice(shown.id)
        listScope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(
                shown.message,
                withDismissAction = true,
                duration = if (shown.level == "info") SnackbarDuration.Short else SnackbarDuration.Long,
            )
        }
    }
    val askFixPrompt = stringResource(R.string.remote_insights_ask_fix_prompt)
    val acceptsPrompts =
        state.connected && !state.loading && state.questions.isEmpty() && !state.sending &&
            (state.status == "idle" ||
                (state.status == "running" && (canSteer(state) || canFollowUp(state))))
    val askFixMessagePrompt = stringResource(R.string.remote_menu_ask_fix_prompt)
    fun askToFix(messageId: String, prompt: String = askFixPrompt) {
        // The prompt fills only a blank draft, so the repository must first hold what was typed.
        composerDraft?.flush()
        chatModel?.askToFix(messageId, prompt)
        openToolId = null
        composerFocusRequest++
    }
    fun openChildSession(sessionId: String) {
        val parent = chatKey ?: return
        navigator.openChild(parent, RemoteNavKey.Chat(parent.routeId, parent.projectId, sessionId))
    }
    fun openAgent(item: ConversationItem.Subagent, index: Int) {
        val parent = chatKey ?: return
        when (val resolution = resolveSubagentChild(item, index, parent.sessionId, children)) {
            is ChildResolution.Exact -> openChildSession(resolution.sessionId)
            is ChildResolution.Candidates -> childCandidates = resolution.sessions
            ChildResolution.None -> {
                chatModel?.refreshSessions()
                listScope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(childNotice)
                }
            }
        }
    }
    val compactionNow by
        produceState(System.currentTimeMillis(), state.compaction) {
            value = System.currentTimeMillis()
            if (state.compaction == null) return@produceState
            while (true) {
                delay(COMPACTION_TICK_MILLIS)
                value = System.currentTimeMillis()
            }
        }
    var attachmentSelection by
        rememberSaveable(
            stateSaver =
                androidx.compose.runtime.saveable.Saver<RemoteSelection?, List<String>>(
                    save = {
                        it?.let { selection ->
                            listOf(
                                selection.routeId.orEmpty(),
                                selection.projectId.orEmpty(),
                                selection.sessionId.orEmpty(),
                            )
                        } ?: emptyList()
                    },
                    restore = { if (it.size == 3) RemoteSelection(it[0], it[1], it[2]) else null },
                )
        ) {
            mutableStateOf<RemoteSelection?>(null)
        }
    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris
            ->
            attachmentSelection?.let {
                (model as? ChatViewModel)?.acceptAttachments(
                    it,
                    uris.map { uri -> uri.toString() },
                    true,
                )
            }
            attachmentSelection = null
        }
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            attachmentSelection?.let {
                (model as? ChatViewModel)?.acceptAttachments(
                    it,
                    uris.map { uri -> uri.toString() },
                    false,
                )
            }
            attachmentSelection = null
        }
    // Images, files and text dragged onto the chat from another app.
    var dropHovered by remember(key) { mutableStateOf(false) }
    val activity = LocalActivity.current
    val chatDrop =
        if (chatKey == null || chatModel == null || composerDraft == null) Modifier
        else {
            val target =
                remember(chatModel, composerDraft, activity, chatKeyTarget) {
                    object : DragAndDropTarget {
                        override fun onEntered(event: DragAndDropEvent) {
                            dropHovered = true
                        }

                        override fun onExited(event: DragAndDropEvent) {
                            dropHovered = false
                        }

                        override fun onEnded(event: DragAndDropEvent) {
                            dropHovered = false
                        }

                        override fun onDrop(event: DragAndDropEvent): Boolean {
                            dropHovered = false
                            val dragEvent = event.toAndroidDragEvent()
                            val clip = dragEvent.clipData ?: return false
                            // The URIs stay readable only while the permissions are held: they go
                            // back once the import read every one.
                            val permissions = runCatching { activity?.requestDragAndDropPermissions(dragEvent) }.getOrNull()
                            return acceptChatDrop(
                                clip.parts(context.contentResolver::getType),
                                attach = { items, onDone -> chatModel.acceptAttachments(chatKey.selection(), items, onDone) },
                                insertText = { text ->
                                    composerDraft.insert(text).also {
                                        if (it) {
                                            composerDraft.flush()
                                            chatKeyTarget.draftEdited()
                                        }
                                    }
                                },
                                // Picked code lines go in quoted with their path and numbers, as "Send lines" writes them.
                                localState = dragEvent.localState,
                                release = { permissions?.release() },
                            )
                        }
                    }
                }
            Modifier.dragAndDropTarget(
                // A drag from PocketPi itself (an image card, a file tile) is meant for another
                // app: the chat neither covers itself nor takes it.
                shouldStartDragAndDrop = { acceptsDrop(it.toAndroidDragEvent().localState, it.mimeTypes()) },
                target = target,
            )
        }
    // The file the camera app writes, kept across a process restart behind the camera.
    var photoCapture by rememberSaveable { mutableStateOf<String?>(null) }
    val photoScope = rememberCoroutineScope()
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            PhotoCapture.onResult(
                saved,
                photoCapture,
                attachmentSelection,
                accept = { selection, uris, photo, done -> (model as? ChatViewModel)?.acceptAttachments(selection, uris, photo, done) ?: false },
                // Not the screen's scope: the import may end after the screen is gone.
                discard = { uri -> PhotoCapture.discardLater(context, uri) },
            )
            photoCapture = null
            attachmentSelection = null
        }
    fun launchCamera() {
        photoScope.launch {
            val uri =
                try {
                    withContext(Dispatchers.IO) { PhotoCapture.newUri(context) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    attachmentSelection = null
                    Toast.makeText(context, R.string.remote_camera_unavailable, Toast.LENGTH_LONG).show()
                    return@launch
                }
            photoCapture = uri.toString()
            try {
                camera.launch(uri)
            } catch (_: android.content.ActivityNotFoundException) {
                photoCapture = null
                attachmentSelection = null
                withContext(Dispatchers.IO) { PhotoCapture.discard(context, uri.toString()) }
                Toast.makeText(context, R.string.remote_camera_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }
    // The manifest asks for the camera (QR pairing), so the camera app's capture needs it granted.
    val cameraPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera()
            else {
                attachmentSelection = null
                Toast.makeText(context, R.string.remote_camera_permission_needed, Toast.LENGTH_LONG).show()
            }
        }
    val hasCamera = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var scan by rememberSaveable { mutableStateOf(false) }
    var paste by rememberSaveable(key) { mutableStateOf(false) }
    var qrText by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<PairedHost?>(null) }
    var unsharing by remember(key) { mutableStateOf<Pair<String, String>?>(null) }
    var closing by remember { mutableStateOf<SessionListItem?>(null) }
    var filterSheet by rememberSaveable { mutableStateOf(false) }
    var renameSessionId by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var renameDraft by
        rememberSaveable(key, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    fun startRename(sessionId: String, title: String) {
        renameSessionId = sessionId
        renameDraft = TextFieldValue(title, TextRange(title.length))
    }
    var exportState by remember(key) { mutableStateOf<ExportState?>(null) }
    val exportScope = rememberCoroutineScope()
    val exportChooserTitle = stringResource(R.string.remote_export_chooser)
    fun shareExport(uri: String) {
        // No app may accept the file; the status stays so the user can try again.
        runCatching {
            context.startActivity(exportShareIntent(Uri.parse(uri), exportChooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    fun startExport(sessionId: String) {
        val chat = model as? ChatViewModel ?: return
        if (exportState == ExportState.Exporting) return
        exportState = ExportState.Exporting
        exportScope.launch {
            exportState =
                when (val result = chat.exportSession(sessionId)) {
                    is ExportResult.Failed -> ExportState.Failed(result.failure)
                    is ExportResult.Ready ->
                        try {
                            val uri = withContext(Dispatchers.IO) { storeExport(context, result) }
                            shareExport(uri.toString())
                            ExportState.Done(uri.toString())
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ExportState.Failed(ExportFailure.STORAGE)
                        }
                }
        }
    }
    var reloading by remember(key) { mutableStateOf(false) }
    val resources = androidx.compose.ui.platform.LocalResources.current
    fun showToast(@androidx.annotation.StringRes text: Int) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
    fun startReload(sessionId: String) {
        val chat = model as? ChatViewModel ?: return
        if (reloading) return
        reloading = true
        showToast(R.string.remote_reload_running)
        exportScope.launch {
            try {
                when (val result = chat.reloadSession(sessionId)) {
                    ReloadResult.Done -> showToast(R.string.remote_reload_done)
                    is ReloadResult.Failed -> {
                        val failure = result.failure
                        val message = (failure as? ReloadFailure.Failed)?.message
                        if (message == null) showToast(reloadFailureText(failure))
                        else Toast.makeText(context, resources.getString(reloadFailureText(failure), message), Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                reloading = false
            }
        }
    }
    var modelPickerRequested by remember(key) { mutableStateOf(false) }
    // The palette's thinking level or advisor, which SessionControls opens like the model picker.
    var controlRequested by remember(key) { mutableStateOf<SessionControl?>(null) }
    var settingsSheetRequested by remember(key) { mutableStateOf(false) }
    var treeSheetRequested by remember(key) { mutableStateOf(false) }
    // A sheet closed by a lost connection or capability must not reappear by itself after a reconnect.
    val artifactsAvailable = canShowArtifacts(state)
    LaunchedEffect(artifactsAvailable) { if (!artifactsAvailable) artifactsSheetRequested = false }
    /** Runs the local command [command] with its [argument], as a typed command would. */
    fun runLocal(command: LocalCommand, argument: String) {
        when (command) {
            LocalCommand.NEW -> (key as? RemoteNavKey.Chat)?.let { navigator.createSession(it.routeId, it.projectId) }
            LocalCommand.COMPACT -> model.compactContext()
            LocalCommand.MODEL -> modelPickerRequested = true
            LocalCommand.SETTINGS -> settingsSheetRequested = true
            LocalCommand.EXPORT -> state.selection.sessionId?.let(::startExport)
            LocalCommand.TREE -> treeSheetRequested = true
            LocalCommand.RELOAD ->
                if (reloadAvailability(state) == ReloadAvailability.TERMINAL_ONLY)
                    showToast(R.string.remote_reload_terminal_hint)
                else if (reloading) showToast(R.string.remote_reload_running)
                else state.selection.sessionId?.let(::startReload)
            LocalCommand.ARTIFACTS ->
                if (artifactsAvailable) openGallery()
                else showToast(R.string.remote_artifacts_host_outdated)
            LocalCommand.NAME -> {
                val session = state.session ?: return
                if (argument.isEmpty()) startRename(session.text("id"), session.text("title"))
                else model.renameSession(session.text("id"), argument)
            }
        }
    }

    /**
     * Runs the draft when it names an available [LocalCommand] and clears it; false leaves the draft
     * for the normal send, which reports unknown or unavailable commands.
     */
    fun runLocalCommand(): Boolean {
        if (key !is RemoteNavKey.Chat) return false
        val composer = composerDraft ?: return false
        // The composer's text can be ahead of the draft this screen last saw.
        val current = state.copy(draft = composer.text)
        val invocation =
            localInvocation(current, availableLocalCommands(current, true, System.currentTimeMillis(), reloading))
                ?: return false
        // Keeps the typed `/new` while another create is in flight; the navigator would drop it.
        if (invocation.command == LocalCommand.NEW && navigator.mutating) return true
        composer.replace("")
        runLocal(invocation.command, invocation.argument)
        return true
    }

    /** Runs a header action in this chat, as its pill button does. Close is left to the pill's own path. */
    fun runChatAction(action: ChatAction) {
        val chat = key as? RemoteNavKey.Chat ?: return
        // Close is never ranked, so its uses need no record.
        if (action != ChatAction.CLOSE) chatModel?.recordChatAction(action)
        when (action) {
            ChatAction.CHANGES -> openInspector(InspectorTab.CHANGES)
            ChatAction.FILES -> openInspector(InspectorTab.FILES)
            ChatAction.JOBS -> openInspector(InspectorTab.JOBS)
            ChatAction.RENAME ->
                state.session?.let { session -> startRename(session.text("id"), session.text("title")) }
            ChatAction.NEW_SESSION -> navigator.createSession(chat.routeId, chat.projectId)
            ChatAction.REFRESH -> model.refresh()
            ChatAction.SESSION_SETTINGS -> settingsSheetRequested = true
            ChatAction.SETTINGS -> navigator.settings()
            ChatAction.HISTORY -> toggleHistory()
            ChatAction.CLOSE -> Unit
        }
    }

    /** Runs a row the command palette chose in this chat, as the chat's own control or command would. */
    fun performChatCommand(command: ChatCommand) {
        when (command) {
            is ChatCommand.Action -> runChatAction(command.action)
            is ChatCommand.Control ->
                when (command.control) {
                    SessionControl.MODEL -> modelPickerRequested = true
                    SessionControl.THINKING, SessionControl.ADVISOR -> controlRequested = command.control
                    SessionControl.COMPACT -> model.compactContext()
                }
            is ChatCommand.Local -> runLocal(command.command, "")
            is ChatCommand.Host ->
                composerDraft?.let { draft ->
                    draft.replace(paletteCommandDraft(draft.text, command.command.name))
                    composerFocusRequest++
                }
            // The same path as a file the chat names: it refuses while an editor keeps the pane, and
            // then copies the path, so no unsaved edit is dropped.
            is ChatCommand.File -> openFileNow(FileTarget(command.path))
        }
    }
    var abortTarget by remember { mutableStateOf<AbortRunTarget?>(null) }
    var disconnecting by rememberSaveable(key) { mutableStateOf(false) }
    var macDetails by rememberSaveable(key) { mutableStateOf(false) }
    // A Projects page is its Mac: the Mac's name is the title, and the connection is under it.
    val projectsPage = key as? RemoteNavKey.Projects
    val macHost = projectsPage?.let { page -> state.hosts.firstOrNull { it.routeId == page.routeId } }
    val macName = macHost?.name?.takeIf(String::isNotBlank)
    // The Mac's status in words, as the connection chip above says it, so the title and the details
    // dialog never disagree with it. Another Mac's page is not connected, whatever the chip says.
    val macStatus: Int =
        if (projectsPage == null || projectsPage.routeId != state.host?.routeId) R.string.remote_design_not_connected
        else connectionStatusLine(state) ?: if (state.connected) R.string.remote_connected else R.string.remote_design_not_connected
    // A pull refreshes the lists behind what they show, which answer later and change only when
    // the Mac's state did. The indicator therefore stays for a moment rather than until they answer.
    var pullRefreshing by remember(key) { mutableStateOf(false) }
    LaunchedEffect(pullRefreshing) {
        if (pullRefreshing) {
            delay(PULL_REFRESH_INDICATOR_MILLIS)
            pullRefreshing = false
        }
    }
    val abortTargetIsCurrent =
        abortTarget?.let { target ->
            state.selection.sessionId == target.sessionId &&
                state.questions.any { it.text("id") in target.questionIds }
        } == true
    val abortBlocked = !state.connected || state.loading || state.answering.isNotEmpty()
    LaunchedEffect(abortTarget, state.selection.sessionId, state.questions) {
        if (abortTarget != null && !abortTargetIsCurrent) abortTarget = null
    }
    var notificationGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    // The user can change this in Android's settings while the app is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationGranted =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
    }
    var notificationDenied by remember { mutableStateOf(false) }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationGranted = granted
            if (!granted) notificationDenied = true
            if (granted) enablePush()
        }
    fun enableNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationGranted)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else enablePush()
    }
    val title =
        stringResource(
            if (settings) R.string.remote_settings
            else
                when (key) {
                    RemoteNavKey.Hosts -> R.string.remote_hosts
                    is RemoteNavKey.Projects -> R.string.remote_projects
                    is RemoteNavKey.Sessions -> R.string.remote_sessions
                    is RemoteNavKey.Chat -> R.string.remote_chat
                    is RemoteNavKey.FolderBrowser -> R.string.remote_folders_title
                    is RemoteNavKey.Providers -> R.string.providers_title
                    is RemoteNavKey.Activity -> R.string.remote_activity_title
                    RemoteNavKey.Settings -> R.string.remote_settings
                }
        )
    val chatHeader: @Composable () -> Unit = {
        if (key is RemoteNavKey.Chat) {
            val statusLabel = stringResource(chatStatusLabel(state))
            val statusColor = chatStatusColor(state.status, state.connected)
            val canRename = canRenameSession(state)
            // The open session's row in the project's list, when that row could close it. The
            // repository closes only a session of that list, and its entries are complete rows.
            // A refresh does not take Close away for its duration; the confirmation waits for it.
            val closableSession =
                state.session?.optionalText("id")
                    ?.let { id -> state.sessions.firstOrNull { it.optionalText("id") == id } }
                    ?.let { sessionListItem(it, state.connected, loading = false, state.stale) }
                    ?.takeIf { canCloseSession(it, state.connected, loading = false, state.capabilities) }
            val hasTuiInfo =
                state.session?.optionalText("origin") == "tui" && !childControlsAvailable(state)
            val headerColor = floatingHeaderColor()
            // History never competes for the pill's ranked places: it would push Settings into the menu.
            fun actionLayout(withHistory: Boolean) =
                chatActionLayout(
                    chatModel?.chatActions ?: DEFAULT_CHAT_ACTIONS,
                    buildSet {
                        if (canViewChanges(state) && state.connected) add(ChatAction.CHANGES)
                        if (canBrowseFiles(state) && state.connected) add(ChatAction.FILES)
                        if (statusJobs != null) add(ChatAction.JOBS)
                        if (canRename) add(ChatAction.RENAME)
                        if (state.connected && !state.loading && !navigator.mutating) add(ChatAction.NEW_SESSION)
                        // Offered while pi runs (the sheet then explains it is read-only); `/settings` is typed into an idle draft only.
                        if (state.connected && !state.loading && sessionSettingsAvailable(state))
                            add(ChatAction.SESSION_SETTINGS)
                        add(ChatAction.REFRESH)
                        add(ChatAction.SETTINGS)
                    },
                ).let { if (withHistory) it.withHistory() else it }
                    .let { if (closableSession != null) it.withClose() else it }
            fun runAction(action: ChatAction) {
                if (action == ChatAction.CLOSE) closing = closableSession
                else runChatAction(action)
            }
            val actionsControl: @Composable (ChatActionLayout, Boolean) -> Unit = { actionLayout, standaloneHistory ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (standaloneHistory) FloatingSurface(shape = CircleShape, color = headerColor) {
                        IconButton(
                            onClick = ::toggleHistory,
                            modifier = Modifier.size(48.dp).testTag("chatHistoryButton"),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.remote_chat_history))
                        }
                    }
                    ChatActionPill(actionLayout, headerColor, ::runAction)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val narrow = maxWidth < 360.dp
                // A phone's title keeps its room: History then joins the pill or its menu.
                val standaloneHistory = maxWidth >= HISTORY_BUTTON_MIN_HEADER
                val actionLayout = actionLayout(!standaloneHistory)
                val actionsWidth =
                    48.dp * (actionLayout.shown.size + if (actionLayout.menu.isEmpty()) 0 else 1) +
                        if (standaloneHistory) 56.dp else 0.dp
                val maxPillWidth =
                    maxWidth - 32.dp - if (narrow) 0.dp else actionsWidth + 8.dp
                val maxTitleWidth =
                    (maxPillWidth - 72.dp - (if (hasTuiInfo) 40.dp else 0.dp) -
                        if (detailPane) 48.dp else 0.dp)
                        .coerceAtLeast(24.dp)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FloatingSurface(
                            modifier = Modifier.widthIn(max = maxPillWidth),
                            shape = CircleShape,
                            color = headerColor,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (detailPane && twoPane != null) {
                                    val collapsed = twoPane.listCollapsed
                                    IconButton(
                                        onClick = { twoPane.listCollapsed = !collapsed },
                                        modifier = Modifier.size(48.dp).testTag("toggleSessionList"),
                                    ) {
                                        Icon(
                                            if (collapsed) Icons.Default.Menu
                                            else Icons.AutoMirrored.Filled.MenuOpen,
                                            stringResource(
                                                if (collapsed) R.string.remote_session_list_show
                                                else R.string.remote_session_list_hide
                                            ),
                                        )
                                    }
                                }
                                IconButton(onClick = navigator::back, modifier = Modifier.size(48.dp)) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        stringResource(R.string.remote_back),
                                    )
                                }
                                Box(
                                    Modifier.padding(end = 8.dp).size(8.dp)
                                        .background(statusColor, CircleShape)
                                        .semantics {
                                            contentDescription = statusLabel
                                            liveRegion = LiveRegionMode.Polite
                                        }
                                        .testTag("chatStatusDot")
                                )
                                val switchSession = stringResource(R.string.remote_palette_switch_session)
                                Row(
                                    Modifier.padding(end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Only the title opens the switcher, so the TUI info button stays a target of its own.
                                    Text(
                                        state.session?.optionalText("title")?.takeIf(String::isNotBlank)
                                            ?: title,
                                        modifier =
                                            Modifier.widthIn(max = maxTitleWidth)
                                                .heightIn(min = 48.dp)
                                                .clickable(role = Role.Button, onClickLabel = switchSession) {
                                                    shortcutTargets?.openPalette(PaletteMode.SESSIONS)
                                                }
                                                .wrapContentHeight(Alignment.CenterVertically)
                                                .testTag("chatTitle"),
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    if (hasTuiInfo)
                                        IconButton(
                                            onClick = { tuiInfoOpen = true },
                                            modifier = Modifier.size(40.dp).testTag("tuiInfo"),
                                        ) {
                                            Icon(
                                                Icons.Default.LaptopMac,
                                                stringResource(R.string.remote_tui_info_title),
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                }
                            }
                        }
                        if (!narrow) actionsControl(actionLayout, standaloneHistory)
                    }
                    if (narrow) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            actionsControl(actionLayout, standaloneHistory)
                        }
                    }
                    if (compactionVisible(state.compaction, compactionNow))
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            CompactionBanner(state.compaction, compactionNow)
                        }
                    if (exportState != null)
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            ExportStatus(exportState, ::shareExport) {
                                if (exportState is ExportState.Done)
                                    exportScope.launch(Dispatchers.IO) { ExportStorage.clear(context.cacheDir) }
                                exportState = null
                            }
                        }
                }
            }
        }
    }
    val changesContent: (@Composable () -> Unit)? =
        changesOpen?.let { changes ->
            {
                ChangesPane(
                    changes,
                    remember(chatModel, composerDraft) {
                        changesActions(checkNotNull(chatModel), { composerDraft?.flush() }) { composerFocusRequest++ }
                    },
                    Modifier.fillMaxSize(),
                    projectName = state.project?.optionalText("name")?.takeIf(String::isNotBlank),
                    offline = !state.connected,
                )
            }
        }
    val filesContent: (@Composable () -> Unit)? =
        filesOpen?.let { files ->
            {
                FilesPane(
                    files,
                    remember(chatModel, composerDraft) {
                        filesActions(checkNotNull(chatModel), { composerDraft?.flush() }, { panesBesideNow.value }) { composerFocusRequest++ }
                    },
                    Modifier.fillMaxSize(),
                    projectName = state.project?.optionalText("name")?.takeIf(String::isNotBlank),
                    canEdit = canEditFile(state),
                )
            }
        }
    val toolContent: (@Composable () -> Unit)? =
        openTool?.let { tool ->
            {
                val download = state.toolOutput?.takeIf { it.toolCallId == tool.toolCallId }
                ToolDetailScreen(
                    item = tool,
                    download = download,
                    canLoadFullOutput =
                        TOOL_OUTPUT_CAPABILITY in state.capabilities &&
                            TOOL_OUTPUT_CAPABILITY !in state.unavailableCapabilities,
                    canAskToFix =
                        tool.state == "error" && tool.outputMessageId != null && acceptsPrompts,
                    onLoadFullOutput = { tool.toolCallId?.let { chatModel?.loadToolOutput(it) } },
                    onCancelFullOutput = { chatModel?.cancelToolOutput() },
                    onAskToFix = { tool.outputMessageId?.let { id -> askToFix(id) } },
                    onQuote = {
                        model.quote(tool.outputMessageId ?: tool.sourceId)
                        closeTool()
                    },
                    onClose = ::closeTool,
                    modifier = Modifier.fillMaxSize(),
                    popOut = panesBesideNow.value,
                )
            }
        }
    // The jobs' actions, for the screen over or beside the chat and the chip's popover. Asking pi
    // about a job's output keeps it beside the chat, and closes it where it covers the composer.
    val jobsActions =
        chatModel?.let { chat ->
            remember(chat, composerDraft) {
                JobsActions(
                    chat::refreshJobs, chat::openJob, chat::closeJob, chat::closeJobs, chat::stopJob,
                    onAsk = { prompt ->
                        composerDraft?.flush()
                        chat.prefillPrompt(prompt).also { done ->
                            if (done) {
                                if (!panesBesideNow.value) chat.closeJobs()
                                composerFocusRequest++
                            }
                        }
                    },
                )
            }
        }
    val jobsContent: (@Composable () -> Unit)? =
        state.jobs?.takeIf { jobsOpen && jobsActions != null }?.let { jobs ->
            { BackgroundJobsScreen(jobs, checkNotNull(jobsActions), Modifier.fillMaxSize()) }
        }
    // The history opens at the entry for the top of the chat's viewport.
    val historyStart =
        remember(historyOpen) {
            if (!historyOpen) 0
            else
                historyAnchor(
                    history,
                    visibleConversation.map { it.id },
                    shownItems.getOrNull(listState.firstVisibleItemIndex - leadingItems.count)?.id,
                )
        }
    // The chat history opens last, so it lies on top; opening anything else closes it.
    val historyContent: (@Composable () -> Unit)? =
        if (historyOpen && key is RemoteNavKey.Chat && sideCapable) {
            { ChatHistoryPane(history, onOpen = ::jumpTo, onClose = { historyOpen = false }, initialIndex = historyStart) }
        } else null
    // Artifacts, images, the gallery and the touched files: dialogs and sheets on a phone.
    val artifactContent: (@Composable () -> Unit)? =
        if (chatKey != null && sideCapable && viewerSlot.viewer != null) {
            { InspectorViewerPane(viewerSlot, onClose = { closeTab(InspectorTab.ARTIFACT) }) }
        } else null
    val artifactsSessionId = state.selection.sessionId
    val artifactsChat = chatModel
    val listArtifacts: (suspend () -> SessionArtifactListResult)? =
        if (artifactsSessionId != null && artifactsChat != null) ({ artifactsChat.listSessionArtifacts(artifactsSessionId) })
        else null
    val openArtifact: suspend (SessionArtifact, Boolean) -> SessionArtifactOpenResult = { artifact, listed ->
        // The listed version is pinned to the listed digest; a reload takes the latest.
        checkNotNull(artifactsChat).openSessionArtifact(
            checkNotNull(artifactsSessionId), artifact.id,
            if (listed) artifact.version else null,
            artifact.type,
            if (listed) artifact.sha256 else null,
        )
    }
    val artifactsShown =
        artifactsSheetRequested && chatKey != null && listArtifacts != null && artifactsSessionId != null && canShowArtifacts(state)
    // The gallery tab's list while the tab stays open; it loads again once the tab reopens.
    val keptGallery = remember(key, artifactsSessionId) { mutableStateOf<ArtifactGalleryState.Loaded?>(null) }
    LaunchedEffect(artifactsShown) { if (!artifactsShown) keptGallery.value = null }
    val galleryContent: (@Composable () -> Unit)? =
        if (artifactsShown && sideCapable) {
            {
                SessionArtifactsPane(
                    artifactsSessionId, listArtifacts, openArtifact,
                    onClose = { closeTab(InspectorTab.GALLERY) },
                    kept = keptGallery,
                )
            }
        } else null
    val touchedContent: (@Composable () -> Unit)? =
        if (touchedSheet && chatKey != null && sideCapable) {
            { TouchedFilesPane(touched, onOpen = ::jumpTo, onClose = { closeTab(InspectorTab.TOUCHED) }) }
        } else null
    fun tabContent(tab: InspectorTab): (@Composable () -> Unit)? =
        when (tab) {
            InspectorTab.CHANGES -> changesContent
            InspectorTab.FILES -> filesContent
            InspectorTab.JOBS -> jobsContent
            InspectorTab.TOOL -> toolContent
            InspectorTab.HISTORY -> historyContent
            InspectorTab.ARTIFACT -> artifactContent
            InspectorTab.GALLERY -> galleryContent
            InspectorTab.TOUCHED -> touchedContent
        }
    // A tab whose pane closed elsewhere (its own close button, a reconnect, another session)
    // goes, and one that opened elsewhere (a notification, the jobs chip) joins.
    val availableTabs =
        buildList {
            if (changesContent != null) add(InspectorTab.CHANGES)
            if (filesContent != null) add(InspectorTab.FILES)
            if (jobsContent != null) add(InspectorTab.JOBS)
            if (toolContent != null) add(InspectorTab.TOOL)
            if (historyOpen && sideCapable) add(InspectorTab.HISTORY)
            if (galleryContent != null) add(InspectorTab.GALLERY)
            if (artifactContent != null) add(InspectorTab.ARTIFACT)
            if (touchedContent != null) add(InspectorTab.TOUCHED)
        }
    val tabs = remember(inspectorTabs, availableTabs) { inspectorTabs.reconcile(availableTabs) }
    val persistentInspector = paneLayout.inspector == InspectorMode.PERSISTENT
    // The inspector would show: it has tabs, or is always there, and the user did not hide it.
    val inspectorWanted = sideCapable && !tabs.hidden && (persistentInspector || tabs.open.isNotEmpty())
    // A tab that opened elsewhere (the jobs chip, a notification) collapses the list in the effect
    // below, a frame late; it counts as collapsed already, so the pane never starts over the chat.
    val listMakingWay =
        inspectorWanted && detailPane && twoPane != null && paneLayout.autoCollapseList &&
            tabs.open.any { it !in inspectorTabs.open }
    // The inspector shows beside the chat. Where it does not fit beside the session list, opening
    // it collapses the list; while the user keeps the list shown it covers the chat as on a phone.
    val sideInspector =
        sideCapable &&
            (!detailPane || twoPane == null || twoPane.listCollapsed || paneLayout.inspectorBesideList || listMakingWay)
    // Hidden tabs (Ctrl+\) stay out of the chat's way too: they are the inspector's, not panes
    // over the chat, even where the list came back meanwhile.
    val panesBeside = sideInspector || (sideCapable && tabs.hidden)
    panesBesideNow.value = panesBeside
    SideEffect {
        if (inspectorTabs == tabs) return@SideEffect
        inspectorTabs = tabs
        if (listMakingWay) twoPane?.autoCollapseList()
    }
    val inspectorShown = sideInspector && inspectorWanted
    // The list makes way where the chat would get cramped, and comes back with the last tab.
    if (detailPane && twoPane != null)
        LaunchedEffect(twoPane, inspectorWanted, paneLayout.autoCollapseList) {
            if (inspectorWanted) {
                if (paneLayout.autoCollapseList) twoPane.autoCollapseList()
            } else {
                twoPane.restoreList()
                twoPane.inspectorExpanded = false
            }
        }
    // DeX keeps the inspector open: a chat starts with its running jobs there, else its changes.
    // Once per chat, so a pane the user closes stays closed.
    var persistentDefaultShown by rememberSaveable(key) { mutableStateOf(false) }
    val jobsKnown =
        !jobsAvailable ||
            state.jobs?.let { it.sessionId == state.selection.sessionId && (it.loaded || it.unsupported || it.failure != null) } == true
    if (chatKey != null && persistentInspector && sideCapable)
        LaunchedEffect(state.connected, state.loading, jobsKnown) {
            if (persistentDefaultShown || !state.connected || state.loading || !jobsKnown) return@LaunchedEffect
            persistentDefaultShown = true
            if (inspectorTabs.open.isNotEmpty()) return@LaunchedEffect
            when {
                state.jobs?.jobs.orEmpty().any { it.status == JobStatus.RUNNING } -> openInspector(InspectorTab.JOBS)
                canViewChanges(state) -> openInspector(InspectorTab.CHANGES)
            }
        }
    val inspectorContent: (@Composable () -> Unit)? =
        if (tabs.open.isEmpty()) null
        else {
            {
                InspectorHost(
                    tabs,
                    content = ::tabContent,
                    onSelect = { inspectorTabs = inspectorTabs.open(it) },
                    onClose = ::closeTab,
                    expanded = twoPane?.inspectorExpanded == true,
                    onExpand = {
                        twoPane?.let {
                            // Review mode holds the pane wide; narrowing it ends review mode.
                            if (it.reviewing) it.leaveReview() else it.inspectorExpanded = !it.inspectorExpanded
                        }
                    },
                    // An unsaved edit closes through its own prompt in the pane.
                    closable = { tab -> tab != InspectorTab.FILES || filesOpen?.editor?.changed != true },
                )
            }
        }
    val focusManager = LocalFocusManager.current
    // With a hardware keyboard a chat opens ready for typing, unless a question or a dialog is up
    // or TalkBack runs: then focus stays where the user put it.
    val chatDialogOpen = renameSessionId != null || abortTargetIsCurrent || abortChild != null || tuiInfoOpen
    // Files, Changes, a tool detail or the jobs fill the chat's place and hide the composer, and so
    // does an expanded inspector that leaves the chat only a strip.
    val paneOverChat =
        chatKey != null && chatModel != null && !panesBeside &&
            (jobsOpen || changesOpen != null || filesOpen != null || openTool != null)
    // The strip reports itself a frame late; a pane no longer expanded gives the chat back at once.
    val composerCovered = paneOverChat || (chatKey != null && inspectorShown && chatStripped && twoPane?.inspectorExpanded == true)
    if (chatKey != null)
        LaunchedEffect(chatKey, hardwareKeyboard) {
            if (!hardwareKeyboard || questionPending || chatDialogOpen || composerCovered || touchExplorationEnabled(context))
                return@LaunchedEffect
            composerFocusRequest++
        }
    // Esc and covering panes park keyboard focus on the chat itself (see the Scaffold); it can
    // take focus only then, so Tab never stops on it.
    var chatFocusParked by remember { mutableStateOf(false) }
    var chatFocused by remember { mutableStateOf(false) }
    fun parkFocus() {
        chatFocusParked = true
        runCatching { chatFocus.requestFocus() }.onFailure {
            chatFocusParked = false
            focusManager.clearFocus()
        }
    }
    // A pane over the chat takes focus out of the hidden composer, so Enter or a typed key never
    // reaches it; once the pane closes, focus goes back if nothing else took it meanwhile.
    var composerParkedForPane by remember { mutableStateOf(false) }
    if (chatKey != null)
        LaunchedEffect(composerCovered) {
            if (composerCovered) {
                if (chatKeyTarget.composerFocused) {
                    parkFocus()
                    composerParkedForPane = true
                }
            } else if (composerParkedForPane) {
                composerParkedForPane = false
                if (chatFocusParked) composerFocusRequest++
            }
        }
    // Ctrl+Shift+D entered review mode while Changes was still opening for it.
    var reviewOpening by remember(key) { mutableStateOf(false) }
    /** A chat shortcut from the keyboard ([KeyContext.CHAT]); false when it does not apply now. */
    fun chatShortcut(shortcut: Shortcut): Boolean {
        val chat = chatModel ?: return false
        return when (shortcut) {
            Shortcut.FOCUS_COMPOSER -> {
                // A pane over the chat hides the composer.
                if (composerCovered) return false
                composerFocusRequest++
                true
            }
            Shortcut.CHANGES -> toggleTab(InspectorTab.CHANGES, changesOpen != null, canViewChanges(state) && state.connected)
            Shortcut.FILES -> toggleTab(InspectorTab.FILES, filesOpen != null, canBrowseFiles(state) && state.connected)
            Shortcut.JOBS -> toggleTab(InspectorTab.JOBS, jobsOpen, jobsAvailable && state.connected)
            Shortcut.HISTORY -> toggleTab(InspectorTab.HISTORY, historyOpen, canOpen = true)
            Shortcut.EXPAND_INSPECTOR -> {
                val pane = twoPane ?: return false
                if (!inspectorShown || tabs.open.isEmpty()) return false
                if (pane.reviewing) pane.leaveReview() else pane.inspectorExpanded = !pane.inspectorExpanded
                true
            }
            Shortcut.REVIEW -> {
                val pane = twoPane ?: return false
                if (pane.reviewing) {
                    pane.leaveReview()
                    return true
                }
                if (!canViewChanges(state)) return false
                if (changesOpen == null) {
                    if (!state.connected) return false
                    openInspector(InspectorTab.CHANGES)
                    reviewOpening = true
                } else showTab(InspectorTab.CHANGES)
                pane.enterReview()
                true
            }
            Shortcut.TOGGLE_INSPECTOR -> {
                // Hides the pane and keeps its tabs; the same key brings them back.
                if (!sideCapable || (tabs.open.isEmpty() && !persistentInspector)) return false
                // Shown again as a tab opens, with the list making way at once.
                val tab = tabs.active ?: tabs.open.firstOrNull()
                if (!tabs.hidden) inspectorTabs = tabs.copy(hidden = true)
                else if (tab != null) showTab(tab)
                else inspectorTabs = tabs.copy(hidden = false)
                true
            }
            Shortcut.RENAME -> {
                val session = state.session?.takeIf { canRenameSession(state) } ?: return false
                startRename(session.text("id"), session.text("title"))
                true
            }
            Shortcut.FORK -> {
                val latest = shownItems.lastOrNull { it.id in forkableIds }
                if (messageFork == null || latest == null) return false
                jumpTo(latest.id)
                forkMenuRequest = latest.id
                true
            }
            Shortcut.PAGE_UP,
            Shortcut.PAGE_DOWN -> {
                // A page of what shows between the header and the composer; whether to follow
                // new output then depends on where it ends, as for a scroll by hand.
                val page = chatPageDistance(listState.layoutInfo.viewportSize.height, headerSize.height + composerSize.height)
                val up = shortcut == Shortcut.PAGE_UP
                viewportRestorePending = false
                if (up && listState.canScrollBackward) autoFollow = false
                listScope.launch {
                    scrollingProgrammatically = true
                    try {
                        listState.scrollBy(if (up) -page else page)
                        // The list's ends are known once it laid out the new position.
                        withFrameNanos { }
                    } finally {
                        scrollingProgrammatically = false
                    }
                    autoFollow = !listState.canScrollForward
                }
                true
            }
            Shortcut.CHAT_START -> {
                viewportRestorePending = false
                autoFollow = false
                listScope.launch {
                    scrollingProgrammatically = true
                    try {
                        listState.scrollToItem(0)
                    } finally {
                        scrollingProgrammatically = false
                    }
                }
                if (state.nextCursor != null && state.connected) chat.older()
                true
            }
            Shortcut.CHAT_END -> {
                scrollToLatestItem()
                true
            }
            else -> false
        }
    }
    if (chatKey != null) {
        val linesSelected = filesOpen?.file?.selection != null
        SideEffect {
            chatKeyTarget.focusComposer = { composerFocusRequest++ }
            chatKeyTarget.composerCovered = composerCovered
            chatKeyTarget.blurComposer = { parkFocus() }
            chatKeyTarget.selectionActive = { conversationFocused || linesSelected }
            chatKeyTarget.clearSelection = {
                // Without focus a text selection ends; a line selection in Files is state.
                if (conversationFocused) parkFocus() else chatModel?.selectFileLines(null)
            }
            chatKeyTarget.shortcut = ::chatShortcut
            chatKeyTarget.perform = ::performChatCommand
            chatKeyTarget.reviewing = { twoPane?.reviewing == true }
            chatKeyTarget.leaveReview = { twoPane?.leaveReview() }
            chatKeyTarget.leaveReviewToType = leave@{
                val pane = twoPane
                if (pane?.reviewing != true || paneOverChat) return@leave false
                pane.leaveReview()
                // Uncovered from now on, so keys typed before the next frame reach the draft too.
                chatKeyTarget.composerCovered = false
                composerFocusRequest++
                true
            }
        }
    }
    // Review mode lives in the Changes pane: it ends once that closes, or another tab takes its place.
    val reviewable = changesOpen != null && (!panesBeside || (inspectorShown && (tabs.active ?: tabs.open.firstOrNull()) == InspectorTab.CHANGES))
    if (chatKey != null && twoPane != null)
        LaunchedEffect(twoPane, reviewable, twoPane.reviewing) {
            if (reviewable || !twoPane.reviewing) {
                reviewOpening = false
                return@LaunchedEffect
            }
            // Changes opening for review mode gets a moment to show; one that never does ends it.
            if (reviewOpening) {
                delay(REVIEW_OPEN_GRACE_MILLIS)
                reviewOpening = false
            }
            twoPane.leaveReview()
        }
    // F6 into the chat goes to the composer, or with a question to the chat itself; a pane over
    // the chat is a region of its own.
    val chatRegion =
        if (chatKey == null) Modifier
        else
            rememberFocusRegion(FocusRegion.CHAT, enabled = !composerCovered) {
                if (questionPending) parkFocus() else composerFocusRequest++
                true
            }
    // The rows F6 comes back to: the list's here, or the pane's over or beside the chat.
    val regionChildren = remember(key) { FocusRegionChildren() }
    val overlayChildren = remember(key) { FocusRegionChildren() }
    val overlayRegion = rememberFocusRegion(FocusRegion.OVERLAY, enabled = paneOverChat, children = overlayChildren)
    CompositionLocalProvider(
        LocalChatKeyTarget provides chatKeyTarget.takeIf { chatKey != null },
        LocalFocusRegionChildren provides regionChildren,
        LocalInspectorViewers provides inspectorViewers.takeIf { chatKey != null && sideCapable },
        LocalFileOpener provides fileOpener,
    ) {
    InspectorRow(
        shown = inspectorShown,
        width = { twoPane?.inspectorWidth(paneLayout) ?: paneLayout.inspectorWidth },
        onResize = twoPane?.let { pane -> { width -> pane.resizeInspector(paneLayout, width) } },
        inspector = inspectorContent,
        minWidth = paneLayout.inspectorMinWidth,
        expanded = twoPane?.inspectorExpanded == true,
        chatStrip =
            twoPane?.takeIf { chatKey != null }?.let { pane ->
                {
                    ChatStrip(
                        chatStatusColor(state.status, state.connected),
                        stringResource(chatStatusLabel(state)),
                        onShowChat = { if (pane.reviewing) pane.leaveReview() else pane.inspectorExpanded = false },
                    )
                }
            },
        onChatStripped = { chatStripped = it },
        review = twoPane?.reviewing == true,
    ) {
    Scaffold(
        // Esc parks keyboard focus here, on the chat itself: clearing it would hand it to the
        // first button (Back) while a keyboard is in use, and Enter would then press it. It is
        // focusable only while parked, so Tab and the initial focus never stop on it. Enter on
        // it is used up: Compose would move focus into the first button (Back) instead. In review
        // mode it is review's "Use review" ([ReviewKey]).
        modifier =
            if (chatKey != null)
                chatRegion.focusRequester(chatFocus)
                    .onFocusChanged {
                        // Only leaving the parked focus ends it; the composer's focus passing
                        // through on its way here does not.
                        if (chatFocused && !it.isFocused) chatFocusParked = false
                        chatFocused = it.isFocused
                    }
                    .onKeyEvent {
                        if (!chatFocused || it.key !in FOCUS_ENTER_KEYS) return@onKeyEvent false
                        if (it.type == KeyEventType.KeyDown && twoPane?.reviewing == true) chatKeyTarget.reviewKey(ReviewKey.USE_REVIEW)
                        true
                    }
                    .focusProperties { canFocus = chatFocusParked }
                    .focusTarget()
            else Modifier,
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            if (key is RemoteNavKey.Projects && canOpenFolders(state))
                OpenFolderButton(
                    enabled = !state.loading,
                    onClick = { navigator.openFolders(key.routeId) },
                )
            if (key is RemoteNavKey.Sessions || key is RemoteNavKey.Hosts) {
                FloatingSurface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    IconButton(
                        onClick = {
                            if (key is RemoteNavKey.Sessions)
                                navigator.createSession(key.routeId, key.projectId)
                            else scan = true
                        },
                        enabled =
                            if (key is RemoteNavKey.Sessions) state.connected && !state.loading && !navigator.mutating
                            else true,
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            if (key is RemoteNavKey.Sessions) Icons.Default.Add
                            else Icons.Default.QrCodeScanner,
                            contentDescription =
                                stringResource(
                                    if (key is RemoteNavKey.Sessions) R.string.remote_new_session
                                    else R.string.remote_scan
                                ),
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (!settings && key !is RemoteNavKey.Sessions && key !is RemoteNavKey.Chat)
                Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)
                    ) {
                        when (key) {
                            RemoteNavKey.Settings -> Unit
                            RemoteNavKey.Hosts -> {
                                TextButton(
                                    onClick = { paste = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.remote_paste))
                                }
                            }
                            is RemoteNavKey.Sessions -> Unit
                            is RemoteNavKey.Chat -> Unit
                            is RemoteNavKey.FolderBrowser -> Unit
                            is RemoteNavKey.Providers -> Unit
                            is RemoteNavKey.Activity -> Unit
                            // Providers, Open folder, Disconnect and Remove pairing live in the Mac's
                            // overflow in the header, so the list keeps the full height.
                            is RemoteNavKey.Projects -> {
                                OutlinedButton(
                                    onClick = { navigator.openActivity(key.routeId) },
                                    enabled = state.host != null,
                                    modifier = Modifier.fillMaxWidth().testTag("activityButton"),
                                ) {
                                    Text(stringResource(R.string.remote_activity_title))
                                }
                            }
                        }
                    }
                }
        },
    ) { insets ->
        BoxWithConstraints(
            if (key is RemoteNavKey.Chat)
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding().then(chatDrop)
            else Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
        ) {
        // While the tool detail covers the chat, the chat below must not be reachable by TalkBack.
        val underlay = if (openTool != null && !panesBeside) Modifier.clearAndSetSemantics {} else Modifier
        // A wide chat pane centres its bubbles in a reading column; phones never reach the limit.
        val readingInset =
            if (key is RemoteNavKey.Chat) ((maxWidth - 40.dp - MAX_READING_WIDTH) / 2).coerceAtLeast(0.dp)
            else 0.dp
        // The inspector's resize handle reaches into the chat's end: the scrollbar lane starts
        // clear of it. Constant while the inspector is open, so scrolling never moves anything.
        val laneInset =
            if (key is RemoteNavKey.Chat && inspectorShown && twoPane != null) maxOf(readingInset, PANE_RESIZE_HANDLE_OVERHANG)
            else readingInset
        // F6 reaches the project and session lists, and comes back to the row it left there.
        val listRegion =
            when (key) {
                is RemoteNavKey.Projects -> rememberFocusRegion(FocusRegion.PROJECTS, children = regionChildren)
                is RemoteNavKey.Sessions -> rememberFocusRegion(FocusRegion.SESSIONS, children = regionChildren)
                else -> Modifier
            }
        // The lists of projects and sessions pull to refresh: the lists are read again behind them.
        val list: @Composable () -> Unit = {
            LazyColumn(
                if (key is RemoteNavKey.Chat)
                    Modifier.fillMaxSize().testTag("conversationList").then(underlay)
                        .onFocusChanged { conversationFocused = it.hasFocus }
                else Modifier.fillMaxSize().then(listRegion),
                state = listState,
                contentPadding =
                    PaddingValues(
                        start = 20.dp + readingInset,
                        // Constant, so nothing rewraps while scrolling; the scrollbar lives in this lane.
                        end = CHAT_SCROLLBAR_LANE + laneInset,
                        // A list page's connection chip brings the header's height itself (see
                        // connectionStatusItem); with the spacing after it the page title starts where
                        // it would at headerHeight + 48dp.
                        top =
                            if (key is RemoteNavKey.Chat) headerHeight + 16.dp
                            else 48.dp - LIST_ITEM_SPACING,
                        bottom =
                            if (
                                key is RemoteNavKey.Sessions || key is RemoteNavKey.Hosts ||
                                    (key is RemoteNavKey.Projects && canOpenFolders(state))
                            ) 112.dp
                            else if (key is RemoteNavKey.Chat) conversationEndPadding
                            else 24.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(LIST_ITEM_SPACING),
            ) {
                // Every item emitted before the conversation counts here, so a jump can turn an index
                // in shownItems into a list index.
                var leading = 0
                // The chat shows the connection beside its composer instead. Beside an open chat or a
                // project's sessions, that pane speaks for the connection (as with the error card
                // below); the item stays, empty, so the indices after it do not move.
                if (key !is RemoteNavKey.Chat)
                    connectionStatusItem(
                        connectionStatusLine(state).takeIf { listPane?.selectedSessionId == null && !projectSelected },
                        headerHeight,
                    )
                if (key !is RemoteNavKey.Chat)
                    item(key = PAGE_TITLE_KEY) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val projectName =
                                if (key is RemoteNavKey.Sessions)
                                    state.project?.optionalText("name")?.takeIf(String::isNotBlank)
                                else null
                            if (projectName != null)
                                Column(Modifier.weight(1f)) {
                                    if (!(state.loading && state.sessions.isEmpty()))
                                        Text(
                                            pluralStringResource(
                                                R.plurals.remote_sessions_count,
                                                sessionCount,
                                                sessionCount,
                                            ),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    Text(
                                        projectName,
                                        style = MaterialTheme.typography.headlineLarge,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("sessionsProjectTitle").semantics { heading() },
                                    )
                                }
                            else if (macName != null)
                                Column(Modifier.weight(1f).testTag("projectsMacHeading")) {
                                    Text(
                                        stringResource(macStatus),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                    Text(
                                        macName,
                                        style = MaterialTheme.typography.headlineLarge,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("projectsMacTitle").semantics { heading() },
                                    )
                                }
                            else
                                Text(
                                    title,
                                    modifier = Modifier.weight(1f).semantics { heading() },
                                    style = MaterialTheme.typography.headlineLarge,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            if (key is RemoteNavKey.Sessions && listPane == null && state.sessions.isNotEmpty()) {
                                FloatingSurface(
                                    modifier = Modifier.offset(x = LIST_TO_HEADER_END_SHIFT).padding(end = 8.dp),
                                    shape = CircleShape,
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (sessionSearchOpen) sessionQuery = ""
                                            else shortcutTargets?.requestSearch()
                                            sessionSearchOpen = !sessionSearchOpen
                                        },
                                        modifier = Modifier.size(48.dp).testTag("sessionSearchToggle"),
                                    ) {
                                        Icon(
                                            if (sessionSearchOpen) Icons.Default.Close else Icons.Default.Search,
                                            stringResource(
                                                if (sessionSearchOpen) R.string.remote_session_search_close
                                                else R.string.remote_session_search
                                            ),
                                        )
                                    }
                                }
                            }
                            if (key is RemoteNavKey.Sessions) {
                                val filterLabel = stringResource(R.string.remote_design_filter_sessions)
                                val activeCountLabel =
                                    if (hideOfflineSessions)
                                        stringResource(R.string.remote_design_filter_active_count, 1)
                                    else null
                                BadgedBox(
                                    badge = {
                                        if (hideOfflineSessions)
                                            Badge(modifier = Modifier.testTag("sessionsFilterBadge")) {
                                                Text("1")
                                            }
                                    },
                                    // The list is inset 20dp and the header pills 16dp: shift the
                                    // filter so its end lines up with the actions pill above it.
                                    modifier = Modifier.offset(x = LIST_TO_HEADER_END_SHIFT),
                                ) {
                                    FloatingSurface(
                                        modifier = Modifier.testTag("sessionsFilterPill"),
                                        shape = CircleShape,
                                    ) {
                                        IconButton(
                                            onClick = { filterSheet = true },
                                            modifier = Modifier.size(48.dp).semantics {
                                                contentDescription =
                                                    listOfNotNull(filterLabel, activeCountLabel)
                                                        .joinToString(", ")
                                            },
                                        ) {
                                            Icon(Icons.Default.FilterAlt, contentDescription = null)
                                        }
                                    }
                                }
                            }
                        }
                    }
                // Chat feedback belongs beside the composer, not above the conversation.
                // Do not carry an unavailable-command warning into the session overview.
                // Beside a project's sessions, the session list shows the error, not both columns.
                if (state.error != null && key !is RemoteNavKey.Chat &&
                    state.error != R.string.remote_command_unknown &&
                    listPane?.selectedSessionId == null &&
                    !projectSelected
                ) {
                    leading++
                    item(key = "error-card") {
                        Card(
                            modifier = Modifier.fillMaxWidth().testTag("errorCard"),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null)
                                    Text(stringResource(state.error!!), Modifier.weight(1f))
                                }
                                ReconnectCountdown(state.reconnectAt)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement =
                                        Arrangement.spacedBy(8.dp, Alignment.End),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(
                                        onClick = model::dismissError,
                                        colors =
                                            ButtonDefaults.textButtonColors(
                                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                                            ),
                                    ) {
                                        Text(stringResource(R.string.remote_error_close))
                                    }
                                    if (state.error == R.string.remote_device_revoked)
                                        state.hosts.find { it.routeId == state.deniedRouteId }?.let { host ->
                                            FilledTonalButton(
                                                onClick = { removing = host },
                                                modifier = Modifier.testTag("removePairing"),
                                            ) {
                                                Text(stringResource(R.string.remote_remove))
                                            }
                                        }
                                    if (!state.loading && state.error in RETRYABLE_ERRORS)
                                        FilledTonalButton(
                                            onClick = {
                                                model.dismissError()
                                                model.refresh()
                                            },
                                            modifier = Modifier.testTag("reconnectNow"),
                                        ) {
                                            Icon(
                                                Icons.Default.Refresh,
                                                contentDescription = null,
                                                modifier = Modifier.size(ButtonDefaults.IconSize),
                                            )
                                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                            Text(retryLabel(state.reconnectAt))
                                        }
                                }
                            }
                        }
                    }
                }
                if (settings) {
                    item {
                        Section(stringResource(R.string.remote_thinking_setting)) {
                            Text(
                                stringResource(R.string.remote_design_thinking_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val showThinking = preferences?.thinkingDisplay == "text"
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(value = showThinking, role = Role.Switch) {
                                        (model as SettingsViewModel).setThinkingDisplay(
                                            if (it) "text" else "status"
                                        )
                                    }
                                    .testTag("thinkingSwitch")
                                    .padding(vertical = 8.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.remote_design_show_thinking),
                                    Modifier.weight(1f),
                                )
                                Switch(checked = showThinking, onCheckedChange = null)
                            }
                        }
                    }
                    item {
                        Section(stringResource(R.string.remote_appearance)) {
                            Text(
                                stringResource(R.string.remote_design_appearance_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(value = theme == "synthwave", role = Role.Switch) {
                                        (model as SettingsViewModel).setTheme(if (it) "synthwave" else "system")
                                    }
                                    .testTag("themeSynthwaveSwitch")
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.remote_theme_system))
                                Switch(
                                    checked = theme == "synthwave",
                                    onCheckedChange = null,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                                Text(stringResource(R.string.remote_theme_synthwave))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                listOf(false, true).forEach { dark ->
                                    Column(
                                        Modifier.weight(1f),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        ThemePreview(
                                            if (theme == "synthwave") {
                                                if (dark) "synthwave" else "synthwave-light"
                                            } else if (dark) "dark" else "light"
                                        )
                                        Text(
                                            stringResource(if (dark) R.string.remote_theme_dark else R.string.remote_theme_light),
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Section(stringResource(R.string.remote_swipe_title)) {
                            Text(
                                stringResource(R.string.remote_swipe_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SwipeActionChoice(
                                label = R.string.remote_swipe_end_to_start,
                                tagPrefix = "swipeEndToStart",
                                selected = preferences?.swipeEndToStart,
                                onSelect = { (model as SettingsViewModel).setSwipeEndToStart(it) },
                            )
                            SwipeActionChoice(
                                label = R.string.remote_swipe_start_to_end,
                                tagPrefix = "swipeStartToEnd",
                                selected = preferences?.swipeStartToEnd,
                                onSelect = { (model as SettingsViewModel).setSwipeStartToEnd(it) },
                            )
                        }
                    }
                    // Only where a keyboard can be in use: one is attached, or the wide layout of a
                    // tablet, which usually has one nearby.
                    if (keyboardSettingsShown(hardwareKeyboard, paneLayout.twoPane))
                        item {
                            Section(stringResource(R.string.remote_keyboard_title), Modifier.testTag("keyboardSettings")) {
                                val helpKeys =
                                    chordText(
                                        checkNotNull(tooltipChord(Shortcut.HELP)),
                                        androidx.compose.ui.platform.LocalResources.current,
                                        rememberKeyCaps(),
                                    )
                                Text(
                                    stringResource(R.string.remote_keyboard_help, helpKeys),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val enterSends = preferences?.enterSends ?: true
                                Row(
                                    Modifier.fillMaxWidth()
                                        .toggleable(value = enterSends, role = Role.Switch) {
                                            (model as SettingsViewModel).setEnterSends(it)
                                        }
                                        .testTag("enterSendsSwitch")
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(stringResource(R.string.remote_keyboard_enter_sends), Modifier.weight(1f))
                                    Switch(checked = enterSends, onCheckedChange = null)
                                }
                                ShortcutList(null, Modifier.testTag("keyboardShortcuts"))
                            }
                        }
                    item { PredictionSettingsSection() }
                    // Only the wide layout has widths and columns to reset.
                    twoPane?.let { item { LayoutSettingsSection(it) } }
                    item {
                        Section(stringResource(R.string.remote_notifications)) {
                            val hint =
                                stringResource(
                                    when {
                                        !pushConfigured -> R.string.remote_notification_unconfigured
                                        !notificationGranted -> R.string.remote_notification_denied
                                        !pushEnabled -> R.string.remote_notification_toggle_off
                                        else -> R.string.remote_notification_help
                                    }
                                )
                            if (pushConfigured) {
                                // One focusable row: TalkBack reads the label, the state and the hint.
                                Column(
                                    Modifier.fillMaxWidth()
                                        .toggleable(value = pushEnabled, role = Role.Switch) {
                                            if (it) enableNotifications() else disablePush()
                                        }
                                        .testTag("pushSwitch")
                                        .padding(vertical = 8.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Notifications, contentDescription = null)
                                        Spacer(Modifier.width(14.dp))
                                        Text(
                                            stringResource(R.string.remote_notification_toggle),
                                            Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Switch(checked = pushEnabled, onCheckedChange = null)
                                    }
                                    Text(
                                        hint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (
                                    Build.VERSION.SDK_INT >= 33 &&
                                        !notificationGranted &&
                                        (notificationDenied || preferences?.notificationPromptShown == true)
                                ) {
                                    // After a denial or two Android no longer shows its own prompt.
                                    TextButton(
                                        onClick = {
                                            // Some OEM builds have no per-app notification screen.
                                            runCatching {
                                                context.startActivity(
                                                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                )
                                            }
                                        }
                                    ) {
                                        Text(stringResource(R.string.remote_notification_open_settings))
                                    }
                                }
                            } else {
                                Text(
                                    hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            LiveRunSettingRow()
                        }
                    }
                    item {
                        val uriHandler = LocalUriHandler.current
                        val unavailable = stringResource(R.string.remote_settings_privacy_unavailable)
                        Section(stringResource(R.string.remote_settings_privacy_title)) {
                            Text(
                                stringResource(R.string.remote_settings_privacy_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = {
                                    try {
                                        uriHandler.openUri(PRIVACY_POLICY_URL)
                                    } catch (_: IllegalArgumentException) {
                                        // No app opens the link; the URI handler wraps ActivityNotFoundException.
                                        Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
                                    }
                                },
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.remote_settings_privacy_open))
                            }
                            AppLockSettingRow()
                        }
                    }
                    item {
                        var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
                        Section(stringResource(R.string.remote_diagnostics_title)) {
                            Text(
                                stringResource(R.string.remote_diagnostics_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = { diagnosticsOpen = true },
                                modifier = Modifier.testTag("openDiagnostics"),
                            ) {
                                Icon(Icons.Default.Speed, contentDescription = null)
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.remote_diagnostics_open))
                            }
                        }
                        if (diagnosticsOpen) DiagnosticsDialog(onDismiss = { diagnosticsOpen = false })
                    }
                } else {
                    when (key) {
                        RemoteNavKey.Settings -> Unit
                        is RemoteNavKey.FolderBrowser -> Unit
                        is RemoteNavKey.Providers -> Unit
                        // The board is its own screen (ActivityBoardScreen), never a RemoteScreen page.
                        is RemoteNavKey.Activity -> Unit
                        RemoteNavKey.Hosts -> {
                            // Until the pairings are read, no list is no news: the first-run text
                            // would flash before the Macs appear. A read that failed says so instead.
                            if (state.hosts.isEmpty() && state.hostsUnreadable) {
                                item { Text(stringResource(R.string.remote_storage_error)) }
                                item { Text(stringResource(R.string.remote_pair_help)) }
                            } else if (state.hosts.isEmpty() && !state.hostsLoaded) {
                                item(key = "hosts_loading") { HostListSkeleton() }
                            } else if (state.hosts.isEmpty()) {
                                item {
                                    Text(
                                        stringResource(R.string.remote_welcome),
                                        style = MaterialTheme.typography.headlineSmall,
                                    )
                                }
                                item { Text(stringResource(R.string.remote_pair_help)) }
                                item { Text(stringResource(R.string.remote_empty_hosts)) }
                            }
                            items(state.hosts, key = { it.routeId }) { host ->
                                HostCard(
                                    host,
                                    connected = state.connected && state.host?.routeId == host.routeId,
                                    onOpen = { navigator.open(RemoteNavKey.Projects(host.routeId)) },
                                    onRemove = { removing = host },
                                )
                            }
                        }
                        is RemoteNavKey.Projects -> {
                            // What needs the user leads the page: the featured chats waiting for an answer, then
                            // the running ones. Then the shared projects, then the recent chats. Beside an open
                            // project the chats hide, as before.
                            // Every waiting chat leads, since the heading promises all of them; running and recent
                            // chats are capped at three each.
                            val allChats = if (projectSelected) emptyList() else state.projectChats
                            val waitingChats =
                                allChats.filter { it.verified && state.connected && it.session.text("status") == "waiting" }
                            val runningChats =
                                allChats.filter { it.verified && state.connected && it.session.text("status") == "running" }.take(3)
                            val recentChats =
                                allChats.filter {
                                    !it.verified || !state.connected || it.session.text("status") !in setOf("waiting", "running")
                                }.take(3)
                            if (waitingChats.isNotEmpty()) {
                                item(key = "project_chats_waiting_heading") {
                                    ProjectsSectionHeading(stringResource(R.string.remote_status_needs_you))
                                }
                                projectChatRows(waitingChats, routeMarks, state.connected, !state.loading) { chat ->
                                    navigator.open(RemoteNavKey.Chat(key.routeId, chat.projectId, chat.session.text("id")))
                                }
                            }
                            if (runningChats.isNotEmpty()) {
                                item(key = "project_chats_running_heading") {
                                    ProjectsSectionHeading(stringResource(R.string.remote_status_working))
                                }
                                projectChatRows(runningChats, routeMarks, state.connected, !state.loading) { chat ->
                                    navigator.open(RemoteNavKey.Chat(key.routeId, chat.projectId, chat.session.text("id")))
                                }
                            }
                            if (state.projects.isEmpty()) {
                                // Only a list read on the live connection says there are no projects.
                                if (state.loading) item(key = "projects_loading") { ProjectListSkeleton() }
                                else if (state.connected) item { Text(stringResource(R.string.remote_empty_projects)) }
                                else item { Text(stringResource(R.string.remote_projects_unread)) }
                            } else {
                                item(key = "shared_projects_heading") {
                                    ProjectsSectionHeading(stringResource(R.string.remote_projects_shared))
                                }
                                items(state.projects, key = { it.text("id") }) { project ->
                                    val projectId = project.text("id")
                                    ProjectRow(
                                        projectId = projectId,
                                        name = project.text("name"),
                                        // The host only unshares while no project is open.
                                        unshare =
                                            if (projectSelected) ProjectUnshare.NONE
                                            else projectUnshare(state.connected, state.loading, state.capabilities),
                                        onClick = { openProject(navigator, twoPane, key.routeId, projectId) },
                                        onUnshare = { unsharing = projectId to project.text("name") },
                                        selected = projectsColumn?.let { projectId == it.selectedProjectId },
                                        onNewSession =
                                            if (state.connected && !state.loading && !navigator.mutating) {
                                                { navigator.createSession(key.routeId, projectId) }
                                            } else null,
                                        // Only a list the Mac confirmed has counts; a cached row has none.
                                        counts = state.projectSessions[projectId]?.counts,
                                    )
                                }
                            }
                            // Quick chats only refresh while no project is open; beside one they hide.
                            if (recentChats.isNotEmpty()) {
                                item(key = "project_chats_heading") {
                                    ProjectsSectionHeading(stringResource(R.string.remote_projects_quick_chats))
                                }
                                item(key = "project_chats_group_recent") {
                                    Text(
                                        stringResource(R.string.remote_projects_recent),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 12.dp),
                                    )
                                }
                                projectChatRows(recentChats, routeMarks, state.connected, !state.loading) { chat ->
                                    navigator.open(RemoteNavKey.Chat(key.routeId, chat.projectId, chat.session.text("id")))
                                }
                            }
                            if (pushConfigured && !pushEnabled) item {
                                Section(stringResource(R.string.remote_notifications)) {
                                    Text(stringResource(R.string.remote_notification_help))
                                    Button(onClick = { enableNotifications() }) {
                                        Text(stringResource(R.string.remote_notification_allow))
                                    }
                                }
                            }
                        }
                        is RemoteNavKey.Sessions -> {
                            if (sessionSearchShown && state.sessions.isNotEmpty())
                                item(key = "session-search") {
                                    SessionSearchField(
                                        query = sessionQuery,
                                        onQuery = { sessionQuery = it },
                                        onSearch = {
                                            listedSessions.firstOrNull()?.let { first ->
                                                navigator.open(RemoteNavKey.Chat(key.routeId, key.projectId, first.id))
                                            }
                                        },
                                        focusRequester = sessionSearchFocus,
                                    )
                                }
                            if (state.sessions.isEmpty()) {
                                if (state.loading) item { SessionListSkeleton() }
                                else item { Text(stringResource(R.string.remote_empty_sessions)) }
                            }
                            else if (visibleSessions.isEmpty() && (state.connected || state.stale) && !state.loading)
                                item { Text(stringResource(R.string.remote_offline_sessions_hidden)) }
                            else if (listedSessions.isEmpty() && visibleSessions.isNotEmpty())
                                item { Text(stringResource(R.string.remote_session_search_empty)) }
                            items(listedSessions, key = SessionListItem::id) { session ->
                                SessionListRow(
                                    item = session,
                                    selected = session.id == listPane?.selectedSessionId,
                                    onTogglePin = {
                                        sessionMarkStore?.setPinned(key.routeId, session.id, !session.pinned)
                                    },
                                    onMarkRead =
                                        if (!session.unread) null
                                        else {
                                            {
                                                sessionMarkStore?.markSeen(key.routeId, session.id, session.updatedAt, System.currentTimeMillis())
                                            }
                                        },
                                    enabled = !state.connected || !state.loading,
                                    closeAvailable =
                                        canCloseSession(
                                            session,
                                            state.connected,
                                            state.loading,
                                            state.capabilities,
                                        ),
                                    onClick = {
                                        navigator.open(
                                            RemoteNavKey.Chat(
                                                key.routeId,
                                                key.projectId,
                                                session.id,
                                            )
                                        )
                                    },
                                    onRename =
                                        if (
                                            state.connected && !state.loading &&
                                            RENAME_CAPABILITY in state.capabilities &&
                                            session.availability != SessionAvailability.OFFLINE &&
                                            !session.continuesAsCopy
                                        ) {
                                            {
                                                startRename(session.id, session.title)
                                            }
                                        } else null,
                                    onClose = { closing = session },
                                    onForkLatest =
                                        if (
                                            state.connected && !state.loading && !navigator.mutating &&
                                                SESSION_FORK_CAPABILITY in state.capabilities &&
                                                session.parentSessionId == null
                                        ) {
                                            { navigator.openAndForkLatest(RemoteNavKey.Chat(key.routeId, key.projectId, session.id)) }
                                        } else null,
                                    swipe =
                                        (sessionPreferences?.swipeEndToStart ?: SwipeAction.CLOSE) to
                                            (sessionPreferences?.swipeStartToEnd ?: SwipeAction.RENAME),
                                    childrenExpanded = session.id !in collapsedIds,
                                    onToggleChildren =
                                        if (session.hasChildren) {
                                            {
                                                collapsedSessionIds = ArrayList(
                                                    if (session.id in collapsedIds)
                                                        collapsedSessionIds.filterNot { it == session.id }
                                                    else collapsedSessionIds + session.id
                                                )
                                            }
                                        } else null,
                                )
                            }
                        }

                        is RemoteNavKey.Chat -> {
                            if (conversation.isEmpty() && state.questions.isEmpty() && state.loading) {
                                leading++
                                item(key = CHAT_SKELETON_KEY, contentType = CHAT_SKELETON_KEY) { ChatSkeleton() }
                            }
                            if (state.nextCursor != null) {
                                leading++
                                item(key = LOAD_OLDER_KEY, contentType = LOAD_OLDER_KEY) {
                                    TextButton(
                                        onClick = model::older,
                                        enabled = state.connected,
                                    ) {
                                        Text(stringResource(R.string.remote_load_older))
                                    }
                                }
                            }
                            leadingItems.count = leading
                            val copyButtonId =
                                shownItems.lastOrNull {
                                    it is ConversationItem.Bubble && it.role == "assistant"
                                }?.id
                            items(shownItems, key = { it.id }, contentType = ::conversationContentType) { item ->
                                val highlighted = item.id == highlightedId
                                Box(
                                    if (highlighted)
                                        Modifier.testTag("jumpTarget").focusRequester(jumpFocus).focusable()
                                    else Modifier
                                ) {
                                    ConversationMessage(
                                        item,
                                        thinkingDisplay,
                                        thinkingActive = thinkingActive,
                                        onQuote = model::quote,
                                        onOpenTool = {
                                            openToolId = it.id
                                            showTab(InspectorTab.TOOL)
                                        },
                                        onOpenAgent = ::openAgent,
                                        onAskToFix = if (acceptsPrompts) { id -> askToFix(id) } else null,
                                        onAskToFixMessage = if (acceptsPrompts) { id -> askToFix(id, askFixMessagePrompt) } else null,
                                        highlighted = highlighted,
                                        fork = messageFork?.takeIf { item.id in forkableIds },
                                        images = sentImages,
                                        projectImages = projectImages,
                                        copyButton = item.id == copyButtonId,
                                        forkMenuRequested = item.id == forkMenuRequest,
                                        onForkMenuRequestHandled = { forkMenuRequest = null },
                                    )
                                }
                            }
                            if (
                                !onlyErrorsShown && waitingForOutput && sessionActive &&
                                    conversation.lastOrNull().let {
                                        it !is ConversationItem.Thinking || !it.streaming
                                    }
                            )
                                item(key = "waiting-for-output") {
                                    Row(
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Text(
                                            stringResource(R.string.remote_waiting_for_output),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                        }
                    }
                }
            }
        }
        if (key is RemoteNavKey.Projects || key is RemoteNavKey.Sessions)
            PullToRefreshBox(
                isRefreshing = pullRefreshing,
                onRefresh = {
                    pullRefreshing = true
                    model.revalidateLists()
                },
                modifier = Modifier.fillMaxSize().testTag("listPullRefresh"),
            ) {
                list()
            }
        else list()
        if (key !is RemoteNavKey.Chat) {
            // The title of a Sessions page is its project's name, as in the list's large title.
            val pageTitle = if (key is RemoteNavKey.Sessions) state.project?.optionalText("name")?.takeIf(String::isNotBlank) ?: title else macName ?: title
            // The beside-projects column of the wide layout keeps its own Back pill, and the strip
            // beside a chat has no refresh and settings: the full projects column has them already.
            val ownBack = key != RemoteNavKey.Hosts && listPane?.besideProjects != true
            val actionsShown = !settings && !(listPane?.besideProjects == true && !listPane.projectsStrip)
            PageHeader(
                title = pageTitle,
                titleInPill = titleInPill,
                onBack = if (ownBack) navigator::back else null,
                modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { headerSize = it },
                actions =
                    if (actionsShown) {
                        {
                            if (state.host != null) {
                                val rotation =
                                    if (state.loading && ValueAnimator.areAnimatorsEnabled()) {
                                        val transition = rememberInfiniteTransition(label = "refresh")
                                        transition.animateFloat(
                                            initialValue = 0f,
                                            targetValue = 360f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(900, easing = LinearEasing),
                                                repeatMode = RepeatMode.Restart,
                                            ),
                                            label = "refresh-rotation",
                                        ).value
                                    } else 0f
                                val refreshing = stringResource(R.string.remote_refreshing)
                                // The projects column is too narrow for a refresh button beside the
                                // Mac's own actions; its Mac overflow refreshes instead.
                                if (projectsColumn == null)
                                    IconButton(
                                        onClick = model::refresh,
                                        modifier = Modifier.size(48.dp).semantics {
                                            if (state.loading) stateDescription = refreshing
                                        },
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            stringResource(R.string.remote_refresh),
                                            modifier = Modifier.graphicsLayer { rotationZ = rotation },
                                        )
                                    }
                            }
                            // The Mac's own actions, for the page that is the Mac.
                            if (projectsPage != null && macHost != null)
                                MacOverflow(
                                    enabled = !state.loading,
                                    providers =
                                        if (canManageProviders(state)) {
                                            { navigator.openProviders(projectsPage.routeId) }
                                        } else null,
                                    folders =
                                        if (canOpenFolders(state)) {
                                            { navigator.openFolders(projectsPage.routeId) }
                                        } else null,
                                    // The narrow column has no refresh button of its own; the overflow keeps the full reload.
                                    refresh = if (projectsColumn != null) model::refresh else null,
                                    onDetails = { macDetails = true },
                                    onDisconnect = { disconnecting = true },
                                    onRemove = { removing = macHost },
                                )
                            IconButton(onClick = navigator::settings, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    Icons.Default.Settings,
                                    stringResource(R.string.remote_settings),
                                )
                            }
                            // The full projects column kept beside a chat shrinks back to the strip. It
                            // is outside the host guard: the column can collapse whether or not the host is known.
                            if (projectsColumn?.canCollapse == true && twoPane != null)
                                IconButton(
                                    onClick = { twoPane.projectsExpanded = false },
                                    modifier = Modifier.size(48.dp).testTag("collapseProjects"),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.MenuOpen,
                                        stringResource(R.string.remote_projects_collapse),
                                    )
                                }
                        }
                    } else null,
            )
        }
        if (key is RemoteNavKey.Chat && !composerCovered) {
            // Writing with the S Pen in the empty space between the conversation and the composer
            // starts a prompt. The area lies above the list's after-padding, so it exists only
            // while the items end above where a list scrolled to its end would stop; such a list
            // cannot scroll, and the area takes no fling or wheel scroll from it.
            val emptyBelow by remember(listState) { derivedStateOf { emptySpaceBelowList(listState.layoutInfo) } }
            if (emptyBelow > 0)
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = conversationEndPadding)
                        .height(with(LocalDensity.current) { emptyBelow.toDp() })
                        .testTag("handwritingArea")
                        .handwritingDetector { runCatching { composerFocus.requestFocus() } }
                )
        }
        if (key is RemoteNavKey.Chat) {
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .onSizeChanged { headerSize = it }
                    .then(underlay),
            ) {
                chatHeader()
            }
        }
        if (key is RemoteNavKey.Chat && chatScrollbarVisible(listScrollable, markers.size, shownItems.size)) {
            ChatScrollbar(
                listState = listState,
                markers = markers,
                listIndex = { leadingItems.count + it.index },
                onJump = ::jumpTo,
                // The list's end padding lane, between the header and the composer.
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .padding(end = laneInset, top = headerHeight + 8.dp, bottom = composerHeight + 8.dp)
                        .then(underlay),
                // A drag is the user's own scroll: follow new output only when it ends at the bottom.
                onSeek = { atEnd ->
                    viewportRestorePending = false
                    autoFollow = atEnd
                },
            )
        }
        if (key is RemoteNavKey.Chat)
            // Floats above the composer, left of the scrollbar lane, and only fades: neither the
            // composer nor the list changes size when it comes and goes.
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = laneInset + CHAT_SCROLLBAR_LANE + 4.dp, bottom = composerHeight + 8.dp)
                    .then(underlay),
            ) {
                ScrollToLatestSlot(!autoFollow, ::scrollToLatestItem)
            }
        if (key is RemoteNavKey.Chat) {
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .widthIn(max = MAX_READING_WIDTH + 32.dp)
                    .fillMaxWidth()
                    .onSizeChanged { composerSize = it }.testTag("floatingComposer")
                    .then(underlay)
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.error?.let { error ->
                        Card(
                            modifier = Modifier.fillMaxWidth().testTag("errorCard"),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null)
                                    Text(stringResource(error), Modifier.weight(1f))
                                }
                                ReconnectCountdown(state.reconnectAt)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(
                                        onClick = model::dismissError,
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                        ),
                                    ) {
                                        Text(stringResource(R.string.remote_error_close))
                                    }
                                    if (error == R.string.remote_device_revoked)
                                        state.hosts.find { it.routeId == state.deniedRouteId }?.let { host ->
                                            FilledTonalButton(
                                                onClick = { removing = host },
                                                modifier = Modifier.testTag("removePairing"),
                                            ) {
                                                Text(stringResource(R.string.remote_remove))
                                            }
                                        }
                                    if (!state.loading && error in RETRYABLE_ERRORS)
                                        FilledTonalButton(
                                            onClick = {
                                                model.dismissError()
                                                model.refresh()
                                            },
                                            modifier = Modifier.testTag("reconnectNow"),
                                        ) {
                                            Text(retryLabel(state.reconnectAt))
                                        }
                                }
                            }
                        }
                    }
                    if (touched.total > 0 || errorCount > 0 || onlyErrorsShown || strip.entries.isNotEmpty() ||
                        statusJobs != null
                    )
                        Row(
                            Modifier.fillMaxWidth().testTag("chatInsights").horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TouchedFilesSummary(touched, touchedLines) {
                                when (touchedFilesTarget(canViewChanges(state) && state.connected, touched.changed.isNotEmpty())) {
                                    TouchedFilesTarget.CHANGES -> openInspector(InspectorTab.CHANGES)
                                    TouchedFilesTarget.SHEET -> {
                                        touchedSheet = true
                                        if (sideCapable) showTab(InspectorTab.TOUCHED)
                                    }
                                }
                            }
                            ErrorFilterChip(errorCount, onlyErrorsShown) {
                                onlyErrorsShown = !onlyErrorsShown
                                if (onlyErrorsShown) autoFollow = false
                                else if (chatKey != null && viewportPositions != null) {
                                    savedViewport = viewportPositions[chatKey]
                                    viewportRestorePending = savedViewport?.following == false
                                    autoFollow = savedViewport?.following ?: false
                                }
                            }
                            statusJobs?.let { jobs ->
                                key(jobs.sessionId) {
                                    val chat = checkNotNull(chatModel)
                                    val openJobs by rememberUpdatedState { openInspector(InspectorTab.JOBS) }
                                    BackgroundJobsChip(jobs, remember(chat) {
                                        JobsActions(
                                            chat::refreshJobs, chat::openJob, chat::closeJob, chat::closeJobs, chat::stopJob,
                                            onOpenList = { openJobs() },
                                        )
                                    })
                                }
                            }
                            SubagentStrip(
                                strip,
                                canAbort = { entry ->
                                    entry.sessionId?.let { canAbortSubagent(state, it) } == true
                                },
                                onOpen = { entry -> entry.sessionId?.takeIf { entry.openable }?.let(::openChildSession) },
                                onAbort = { entry -> if (entry.sessionId != null) abortChild = entry },
                                sessionId = chatKey?.sessionId,
                            )
                        }
                    val treeSessionId = state.selection.sessionId
                    val treeChat = model as? ChatViewModel
                    if (treeSheetRequested && chatKey != null && treeSessionId != null && treeChat != null)
                        SessionTreeSheet(
                            state = state,
                            // Outlives the sheet, so a move that finishes after it closed still lands.
                            scope = exportScope,
                            load = { treeChat.loadSessionTree(treeSessionId) },
                            navigate = { nodeId, summarize ->
                                treeChat.navigateSessionTree(treeSessionId, nodeId, summarize)
                            },
                            onFork = { nodeId ->
                                treeSheetRequested = false
                                navigator.forkSessionAtNode(chatKey, nodeId)
                            },
                            onAbort = model::abort,
                            onDismiss = { treeSheetRequested = false },
                            forkBlocked = navigator.mutating,
                        )
                    // Beside the chat the gallery is a tab of the inspector.
                    if (artifactsShown && !panesBeside)
                        SessionArtifactsSheet(
                            sessionId = artifactsSessionId,
                            list = listArtifacts,
                            open = openArtifact,
                            onDismiss = { artifactsSheetRequested = false },
                        )
                    FollowUpStatus(state, model::dismissFollowUp)
                    ChatPreviewHint(state)
                    ChatComposerNotices(connectionStatusLine(state), state.uncertain)
                    val composer = checkNotNull(composerDraft)
                    ChatComposer(
                        state = state,
                        // Typed text goes through `composer`, which pushes it to model::draft.
                        draft = composer,
                        onSend = { if (!runLocalCommand()) model.prompt() },
                        onFollowUp = model::followUp,
                        onSteer = model::steer,
                        onStopChild = model::stopChild,
                        onResumeChild = model::resumeChild,
                        onStop = model::abort,
                        onRemoveQuote = { model.quote(null) },
                        onAbort = {
                            val sessionId = state.selection.sessionId
                            val questionIds = state.questions.map { it.text("id") }.toSet()
                            if (sessionId != null && questionIds.isNotEmpty())
                                abortTarget = AbortRunTarget(sessionId, questionIds)
                        },
                        onAnswer = model::answer,
                        suggestions = {
                            val typed = composer.text
                            SessionControls(
                                remember(state, typed) { state.copy(draft = typed) },
                                model::refreshConfiguration,
                                model::refreshContextUsage,
                                model::compactContext,
                                model::refreshAdvisor,
                                model::setAdvisor,
                                model::setModel,
                                model::setThinkingLevel,
                                model::refreshCommands,
                                { command ->
                                    // The suggestions come from the text on screen, which can
                                    // run ahead of the repository's draft.
                                    val current = composer.text
                                    if (command in state.commands && commandName(current) != null)
                                        composer.replace(selectCommand(current, command))
                                },
                                localCommands =
                                    availableLocalCommands(state, key is RemoteNavKey.Chat, System.currentTimeMillis(), reloading),
                                selectLocalCommand = { command ->
                                    // The tree and the artifacts only read, so they open at once, also while
                                    // the chat runs, when a /tree or /artifacts draft could not be sent.
                                    if (command == LocalCommand.TREE || command == LocalCommand.ARTIFACTS) {
                                        // Like a sent command, a command draft does not stay in the composer.
                                        if (composer.text.trimStart().startsWith("/")) composer.replace("")
                                        if (command == LocalCommand.TREE) treeSheetRequested = true
                                        else if (artifactsAvailable) openGallery()
                                        else showToast(R.string.remote_artifacts_host_outdated)
                                    }
                                    // A terminal session only explains itself; nothing to send.
                                    else if (command == LocalCommand.RELOAD &&
                                        (reloading || reloadAvailability(state) == ReloadAvailability.TERMINAL_ONLY)
                                    ) {
                                        if (composer.text.trimStart().startsWith("/")) composer.replace("")
                                        showToast(
                                            if (reloading) R.string.remote_reload_running
                                            else R.string.remote_reload_terminal_hint
                                        )
                                    }
                                    else composer.replace(selectCommandName(composer.text, command.commandName))
                                },
                                reloading = reloading,
                                modelPickerRequested = modelPickerRequested,
                                onModelPickerRequestHandled = { modelPickerRequested = false },
                                controlRequest = controlRequested,
                                onControlRequestHandled = { controlRequested = null },
                                changeSettings = model::changeSettings,
                                settingsSheetRequested = settingsSheetRequested,
                                onSettingsSheetRequestHandled = { settingsSheetRequested = false },
                                slashSuggestions = !composer.suggestionsClosed,
                                onManageProviders = state.selection.routeId?.let { route -> { navigator.openProviders(route) } },
                            )
                        },
                        onRemoveAttachment = model::removeAttachment,
                        onCancelAttachments = model::cancelAttachmentWork,
                        onPickPhotos = {
                            attachmentSelection = state.selection
                            photoPicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        onPickFiles = {
                            attachmentSelection = state.selection
                            filePicker.launch(arrayOf("*/*"))
                        },
                        onPasteAttachments = { items, onDone ->
                            val selection = chatKey?.selection()
                            if (selection != null && chatModel != null) chatModel.acceptAttachments(selection, items, onDone)
                            else onDone()
                        },
                        onTakePhoto =
                            if (!hasCamera) null
                            else {
                                {
                                    attachmentSelection = state.selection
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                                        launchCamera()
                                    else cameraPermission.launch(Manifest.permission.CAMERA)
                                }
                            },
                        focusRequester = composerFocus,
                        hardwareKeyboard = hardwareKeyboard,
                        enterSends = chatPreferences?.enterSends ?: true,
                    )
                }
            }
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).padding(bottom = composerHeight + 8.dp),
            )
            Box(Modifier.fillMaxSize().then(overlayRegion)) {
            CompositionLocalProvider(LocalFocusRegionChildren provides overlayChildren) {
            // Beside the chat these are the inspector's tabs. The jobs stay open through a
            // reconnect, which clears the capabilities for a moment.
            if (!panesBeside) changesContent?.invoke()
            if (!panesBeside) filesContent?.invoke()
            if (!panesBeside) jobsContent?.invoke()
            if (!panesBeside) toolContent?.invoke()
            }
            }
            if (dropHovered) DropScrim()
        }
        }
    }
    }
    }
    val hideOfflineLabel = stringResource(R.string.remote_hide_offline_sessions)
    if (tuiInfoOpen && key is RemoteNavKey.Chat && state.session?.optionalText("origin") == "tui")
        AlertDialog(
            onDismissRequest = { tuiInfoOpen = false },
            title = { Text(stringResource(R.string.remote_tui_info_title)) },
            text = { Text(stringResource(R.string.remote_tui_info_body)) },
            confirmButton = {
                TextButton(onClick = { tuiInfoOpen = false }) {
                    Text(stringResource(R.string.remote_tui_info_close))
                }
            },
        )
    if (abortTargetIsCurrent)
        AlertDialog(
            onDismissRequest = { abortTarget = null },
            title = { Text(stringResource(R.string.remote_abort_run)) },
            text = { Text(stringResource(R.string.remote_abort_run_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.abort()
                        abortTarget = null
                    },
                    enabled = !abortBlocked,
                    modifier = Modifier.testTag("confirmAbortRun"),
                ) { Text(stringResource(R.string.remote_abort_run)) }
            },
            dismissButton = {
                TextButton(onClick = { abortTarget = null }) {
                    Text(stringResource(R.string.remote_cancel))
                }
            },
        )
    if (touchedSheet && key is RemoteNavKey.Chat && !panesBeside)
        CompositionLocalProvider(LocalFileOpener provides fileOpener) {
            TouchedFilesSheet(touched, onDismiss = { touchedSheet = false }, onOpen = ::jumpTo)
        }
    // The open viewer popped out of its tab, or where its pane would cover the chat.
    if (chatKey != null && viewerSlot.viewer != null && (viewerSlot.poppedOut || !panesBeside))
        InspectorViewerDialog(viewerSlot) {
            if (panesBeside) viewerSlot.poppedOut = false else closeTab(InspectorTab.ARTIFACT)
        }
    if (historyOpen && key is RemoteNavKey.Chat && !panesBeside)
        ChatHistorySheet(history, onDismiss = { historyOpen = false }, onOpen = ::jumpTo, initialIndex = historyStart)
    childCandidates?.let { candidates ->
        ChildSessionPickerSheet(
            candidates,
            onPick = ::openChildSession,
            onDismiss = { childCandidates = null },
        )
    }
    abortChild?.let { entry ->
        val sessionId = entry.sessionId
        val allowed = sessionId != null && canAbortSubagent(state, sessionId)
        LaunchedEffect(allowed) {
            // The child finished or left the list while the dialog was open.
            if (!allowed) abortChild = null
        }
        AlertDialog(
            onDismissRequest = { abortChild = null },
            title = { Text(stringResource(R.string.remote_insights_abort_child_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.remote_insights_abort_child_body,
                        entry.agent ?: entry.title,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (sessionId != null) chatModel?.abortSession(sessionId)
                        abortChild = null
                    },
                    enabled = allowed,
                    modifier = Modifier.testTag("confirmAbortChild"),
                ) { Text(stringResource(R.string.remote_insights_abort_child_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { abortChild = null }) {
                    Text(stringResource(R.string.remote_cancel))
                }
            },
        )
    }
    renameSessionId?.let { sessionId ->
        val renameAllowed = state.connected && !state.loading && renameDraft.text.trim().let {
            it.isNotEmpty() && it.encodeToByteArray().size <= 4096
        }
        val confirmRename = {
            model.renameSession(sessionId, renameDraft.text)
            renameSessionId = null
        }
        AlertDialog(
            onDismissRequest = { renameSessionId = null },
            title = { Text(stringResource(R.string.remote_rename_session)) },
            text = {
                val renameFocus = remember { FocusRequester() }
                LaunchedEffect(sessionId) {
                    withFrameNanos { }
                    runCatching { renameFocus.requestFocus() }
                }
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text(stringResource(R.string.remote_session_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (renameAllowed) confirmRename() }),
                    modifier = Modifier.focusRequester(renameFocus).testTag("renameField"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = confirmRename,
                    enabled = renameAllowed,
                ) { Text(stringResource(R.string.remote_rename_session)) }
            },
            dismissButton = {
                TextButton(onClick = { renameSessionId = null }) {
                    Text(stringResource(R.string.remote_cancel))
                }
            },
        )
    }
    if (filterSheet)
        ModalBottomSheet(onDismissRequest = { filterSheet = false }) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.remote_design_filter_sessions),
                    style = MaterialTheme.typography.titleLarge,
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(hideOfflineLabel)
                        Text(
                            stringResource(R.string.remote_design_filter_offline_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = hideOfflineSessions,
                        onCheckedChange = { (model as? SessionsViewModel)?.setHideOfflineSessions(it) },
                        modifier =
                            Modifier.semantics {
                                contentDescription = hideOfflineLabel
                            },
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = { (model as? SessionsViewModel)?.setHideOfflineSessions(false) }
                    ) { Text(stringResource(R.string.remote_design_reset)) }
                    Button(onClick = { filterSheet = false }) {
                        Text(stringResource(R.string.remote_design_done))
                    }
                }
            }
        }
    if (scan)
        QrScanner(
            onCode = {
                scan = false
                navigator.pair(it)
            },
            onClose = { scan = false },
            onPaste = {
                scan = false
                paste = true
            },
        )
    if (paste) {
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { paste = false },
            title = { Text(stringResource(R.string.remote_pair)) },
            text = {
                OutlinedTextField(
                    value = qrText,
                    onValueChange = { if (it.length <= 8192) qrText = it },
                    label = { Text(stringResource(R.string.remote_pair_code)) },
                    placeholder = { Text(stringResource(R.string.remote_design_pair_input_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions =
                        KeyboardActions(onDone = {
                            if (qrText.isNotBlank()) {
                                paste = false
                                navigator.pair(qrText)
                                qrText = ""
                            }
                        }),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                clipboard.getText()?.text?.trim()?.takeIf { it.length <= 8192 }
                                    ?.let { qrText = it }
                            },
                        ) {
                            Icon(
                                Icons.Default.ContentPaste,
                                stringResource(R.string.remote_paste_from_clipboard),
                            )
                        }
                    },
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        paste = false
                        navigator.pair(qrText)
                        qrText = ""
                    },
                    enabled = qrText.isNotBlank(),
                ) {
                    Text(stringResource(R.string.remote_pair))
                }
            },
            dismissButton = {
                TextButton(onClick = { paste = false }) {
                    Text(stringResource(R.string.remote_cancel))
                }
            },
        )
    }
    if (disconnecting && key is RemoteNavKey.Projects)
        AlertDialog(
            onDismissRequest = { disconnecting = false },
            title = {
                Text(
                    state.host?.name?.takeIf(String::isNotBlank)
                        ?.let { stringResource(R.string.remote_disconnect_title, it) }
                        ?: stringResource(R.string.remote_disconnect_title_generic)
                )
            },
            text = { Text(stringResource(R.string.remote_disconnect_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        disconnecting = false
                        // Disconnect keeps the pairing, but a cold launch should not reconnect to the Mac the
                        // user just let go of: the next launch opens the hosts list (or the only Mac).
                        projectsPage?.routeId?.let(navigator::forgetLastRoute)
                        navigator.disconnect()
                    },
                    modifier = Modifier.testTag("confirmDisconnect"),
                ) {
                    Text(stringResource(R.string.remote_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { disconnecting = false }) {
                    Text(stringResource(R.string.remote_cancel))
                }
            },
        )
    if (macDetails && projectsPage != null && macHost != null)
        MacConnectionDetailsDialog(
            host = macHost,
            status = stringResource(macStatus),
            onDismiss = { macDetails = false },
        )
    removing?.let { host ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remote_remove)) },
            text = { Text(stringResource(R.string.remote_remove_help)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // The Mac's own Projects page is on screen: leave it first. A removed Mac cannot stay
                        // open, and an offline Mac's selection would otherwise outlive its pairing.
                        if (projectsPage?.routeId == host.routeId) navigator.disconnect()
                        navigator.forgetLastRoute(host.routeId)
                        model.remove(host.routeId)
                        removing = null
                    }
                ) {
                    Text(stringResource(R.string.remote_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) {
                    Text(stringResource(R.string.remote_cancel))
                }
            },
        )
    }
    unsharing?.let { (projectId, name) ->
        AlertDialog(
            onDismissRequest = { unsharing = null },
            title = { Text(stringResource(R.string.remote_project_unshare)) },
            text = { Text(stringResource(R.string.remote_project_unshare_confirmation, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        (model as? ProjectsViewModel)?.unshare(projectId)
                        unsharing = null
                    },
                    enabled = state.connected && !state.loading &&
                        PROJECT_UNSHARE_CAPABILITY in state.capabilities &&
                        state.projects.any { it.text("id") == projectId },
                ) {
                    Text(stringResource(R.string.remote_project_unshare))
                }
            },
            dismissButton = {
                TextButton(onClick = { unsharing = null }) {
                    Text(stringResource(R.string.remote_cancel))
                }
            },
        )
    }
    closing?.let { session ->
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text(stringResource(R.string.remote_session_close)) },
            text = { Text(stringResource(R.string.remote_session_close_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (model) {
                            is SessionsViewModel -> model.close(session.id)
                            is ChatViewModel -> model.closeSession(session.id)
                            else -> Unit
                        }
                        closing = null
                    },
                    enabled = canCloseSession(
                        session,
                        state.connected,
                        state.loading,
                        state.capabilities,
                    ),
                ) {
                    Text(stringResource(R.string.remote_session_close))
                }
            },
            dismissButton = {
                TextButton(onClick = { closing = null }) {
                    Text(stringResource(R.string.remote_cancel))
                }
            },
        )
    }
}

@Composable
private fun SwipeActionChoice(
    label: Int,
    tagPrefix: String,
    selected: SwipeAction?,
    onSelect: (SwipeAction) -> Unit,
) {
    val options =
        listOf(
            SwipeAction.CLOSE to R.string.remote_swipe_action_close,
            SwipeAction.RENAME to R.string.remote_swipe_action_rename,
            SwipeAction.NONE to R.string.remote_swipe_action_none,
        )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (action, text) ->
                SegmentedButton(
                    selected = selected == action,
                    onClick = { onSelect(action) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    modifier = Modifier.testTag("$tagPrefix-${action.name}"),
                    icon = {},
                ) {
                    Text(
                        stringResource(text),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemePreview(value: String) {
    val colors = remoteColorScheme(dark = value != "light" && value != "synthwave-light", synthwave = value.startsWith("synthwave"))
    val shell = colors.background
    val panel = colors.surfaceContainerHigh
    val line = colors.onSurfaceVariant
    val dot = colors.primary
    Box(
        Modifier.fillMaxWidth().height(92.dp)
            .background(shell, RoundedCornerShape(12.dp))
            .clearAndSetSemantics {}
            .padding(10.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().background(panel, RoundedCornerShape(8.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(2) { index ->
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier.size(8.dp).background(
                            if (value.startsWith("synthwave") && index == 1) colors.secondary else dot,
                            CircleShape,
                        )
                    )
                    Box(
                        Modifier.fillMaxWidth(if (index == 0) 0.82f else 0.6f)
                            .height(5.dp).background(line, RoundedCornerShape(3.dp))
                    )
                }
            }
        }
    }
}

/**
 * The Mac's own actions, in the header of its Projects page: sign-in and folders, the connection
 * details, and the two ways of letting go of the Mac, disconnecting (the pairing stays) or removing it.
 */
@Composable
private fun MacOverflow(
    enabled: Boolean,
    providers: (() -> Unit)?,
    folders: (() -> Unit)?,
    refresh: (() -> Unit)?,
    onDetails: () -> Unit,
    onDisconnect: () -> Unit,
    onRemove: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.remote_mac_menu)
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp).testTag("macOverflow")) {
            Icon(Icons.Default.MoreVert, label)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            refresh?.let { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remote_refresh)) },
                    onClick = { open = false; action() },
                    modifier = Modifier.testTag("macRefresh"),
                )
            }
            providers?.let { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.providers_open)) },
                    onClick = { open = false; action() },
                    enabled = enabled,
                    modifier = Modifier.testTag("macProviders"),
                )
            }
            folders?.let { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remote_folders_open_folder)) },
                    onClick = { open = false; action() },
                    enabled = enabled,
                    modifier = Modifier.testTag("macOpenFolder"),
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remote_mac_details)) },
                onClick = { open = false; onDetails() },
                modifier = Modifier.testTag("macDetails"),
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remote_mac_disconnect)) },
                onClick = { open = false; onDisconnect() },
                modifier = Modifier.testTag("macDisconnect"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remote_remove)) },
                onClick = { open = false; onRemove() },
                modifier = Modifier.testTag("macRemove"),
            )
        }
    }
}

/** The heading of a section of the Projects page; the rows under it belong to it. */
@Composable
private fun ProjectsSectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 28.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** One row per featured chat of a Projects section, in the order given. */
private fun LazyListScope.projectChatRows(
    chats: List<ProjectChatSummary>,
    marks: Map<String, SessionMark>,
    connected: Boolean,
    enabled: Boolean,
    onOpen: (ProjectChatSummary) -> Unit,
) {
    items(chats, key = { "chat:${it.projectId}:${it.session.text("id")}" }) { chat ->
        ProjectChatRow(
            chat = chat,
            connected = connected,
            enabled = enabled,
            mark = chat.session.optionalText("id")?.let(marks::get),
            onClick = { onOpen(chat) },
        )
    }
}

@Composable
private fun ProjectChatRow(
    chat: ProjectChatSummary,
    connected: Boolean,
    enabled: Boolean,
    mark: SessionMark?,
    onClick: () -> Unit,
) {
    Column {
        Text(
            chat.projectName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SessionListRow(
            item =
                sessionListItem(chat.session, connected && chat.verified, loading = false).let {
                    it.copy(pinned = mark?.pinned == true, unread = sessionUnread(mark, it.updatedAt, open = false))
                },
            enabled = enabled,
            closeAvailable = false,
            onClick = onClick,
            onClose = {},
        )
    }
}

@Composable
private fun SessionListSkeleton() {
    val label = stringResource(R.string.remote_sessions_loading)
    val placeholder = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        repeat(3) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(44.dp).background(placeholder, CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SkeletonBar(Modifier.fillMaxWidth(0.62f))
                    SkeletonBar(Modifier.fillMaxWidth(0.86f))
                    SkeletonBar(Modifier.width(70.dp))
                }
            }
        }
    }
}

@Composable
private fun HostListSkeleton() {
    val label = stringResource(R.string.remote_hosts_loading)
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
            .padding(16.dp)
            .clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape))
        Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SkeletonBar(Modifier.fillMaxWidth(0.48f))
            SkeletonBar(Modifier.fillMaxWidth(0.72f))
        }
    }
}

@Composable
private fun ProjectListSkeleton() {
    val label = stringResource(R.string.remote_projects_loading)
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp).clearAndSetSemantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        repeat(3) { index -> SkeletonBar(Modifier.fillMaxWidth(if (index == 1) 0.44f else 0.6f)) }
    }
}

@Composable
private fun ChatSkeleton() {
    val label = stringResource(R.string.remote_chat_loading)
    val placeholder = MaterialTheme.colorScheme.surfaceContainer
    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(3) { index ->
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment =
                    if (index == 1) androidx.compose.ui.Alignment.End
                    else androidx.compose.ui.Alignment.Start,
            ) {
                Column(
                    Modifier.fillMaxWidth(if (index == 1) 0.66f else 0.78f)
                        .background(placeholder, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SkeletonBar(Modifier.fillMaxWidth(0.42f))
                    SkeletonBar(Modifier.fillMaxWidth())
                    SkeletonBar(Modifier.fillMaxWidth(0.72f))
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(modifier: Modifier) {
    Box(
        modifier.height(10.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(5.dp))
    )
}

@Composable
internal fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

private suspend fun LazyListState.scrollToLatest() {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    scrollToItem(last)
    scroll {
        while (canScrollForward) {
            if (scrollBy(10_000f) == 0f) break
        }
    }
}

/** "Scroll to latest" in a slot of fixed size: it only fades, so the composer keeps its size. */
@Composable
private fun ScrollToLatestSlot(visible: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        val motion = ValueAnimator.areAnimatorsEnabled()
        AnimatedVisibility(
            visible,
            enter = if (motion) fadeIn() else EnterTransition.None,
            exit = if (motion) fadeOut() else ExitTransition.None,
        ) {
            SmallFloatingActionButton(onClick = onClick) {
                Icon(Icons.Default.ArrowDownward, stringResource(R.string.remote_scroll_to_bottom))
            }
        }
    }
}

/** What the chat's status dot says. */
@androidx.annotation.StringRes
internal fun chatStatusLabel(state: RemoteState): Int =
    when {
        !state.connected || state.status == "offline" -> R.string.remote_status_offline
        state.status == "running" -> R.string.remote_status_running
        state.status == "waiting" -> R.string.remote_status_waiting
        state.status == "idle" -> R.string.remote_status_idle
        else -> R.string.remote_status_unknown
    }

/**
 * Shown above the app while a share from another app waits for its session: tapping a session or
 * New session in a project sends it there ([RemoteNavigator.sharePick]).
 */
@Composable
internal fun SharePickBanner(onCancel: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().testTag("sharePickBanner"),
    ) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
            // Announced when it appears, so TalkBack users learn what a tap on a session does now.
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(
                    stringResource(R.string.remote_share_pick_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.remote_share_pick_help), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onCancel, modifier = Modifier.testTag("sharePickCancel")) {
                Text(stringResource(R.string.remote_cancel))
            }
        }
    }
}

/** Puts the column widths, the hidden session list and the expanded inspector back to their defaults. */
@Composable
private fun LayoutSettingsSection(twoPane: TwoPaneState) {
    val context = LocalContext.current
    val done = stringResource(R.string.remote_layout_reset_done)
    Section(stringResource(R.string.remote_layout_title), Modifier.testTag("layoutSettings")) {
        Text(
            stringResource(R.string.remote_layout_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = {
                twoPane.resetLayout()
                Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
            },
            enabled = twoPane.customized,
            modifier = Modifier.testTag("resetLayout"),
        ) {
            Icon(Icons.Default.Restore, contentDescription = null)
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.remote_layout_reset))
        }
    }
}
