package de.joinnoah.pi.remote

import android.Manifest
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
private const val JUMP_HIGHLIGHT_MILLIS = 2_000L
private const val COMPACTION_TICK_MILLIS = 30_000L
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
    val sideInspector = key is RemoteNavKey.Chat && paneLayout.inspector != InspectorMode.OVERLAY
    // With a hardware keyboard Enter and Ctrl+Enter send at every window size, phones included.
    val hardwareKeyboard = LocalHardwareKeyboard.current
    val shortcutTargets = LocalShortcutTargets.current
    var sessionQuery by rememberSaveable(key) { mutableStateOf("") }
    // The phone's session list shows its search field behind a search icon.
    var sessionSearchOpen by rememberSaveable(key) { mutableStateOf(false) }
    val sessionSearchShown = key is RemoteNavKey.Sessions && (listPane != null || sessionSearchOpen)
    var collapsedSessionIds by rememberSaveable(key) { mutableStateOf(arrayListOf<String>()) }
    val collapsedIds = collapsedSessionIds.toSet()
    val visibleSessions =
        remember(state.sessions, state.connected, state.loading, hideOfflineSessions, collapsedIds) {
            visibleSessionListItems(
                state.sessions,
                state.connected,
                state.loading,
                hideOfflineSessions && state.connected,
                collapsedIds,
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
        remember(state.sessions, state.connected, state.loading, hideOfflineSessions) {
            visibleSessionListItems(
                state.sessions,
                state.connected,
                state.loading,
                hideOfflineSessions && state.connected,
            ).size
        }
    val sessionActive =
        state.connected && !state.loading && state.status in setOf("running", "waiting")
    val conversation =
        remember(state.messages, sessionActive) { conversationItems(state.messages, sessionActive) }
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
    // there; while the chat loads it takes no edits.
    val composerDraft =
        if (chatKey != null)
            rememberComposerDraft(
                chatKey.selection(),
                state.draft,
                editable = !state.loading && state.selection == chatKey.selection(),
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
            remember(chatModel, sessionId, state.connected, state.capabilitiesKnown, supported) {
                SentImageSource(
                    sessionId, state.connected, state.capabilitiesKnown, supported,
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
    val touched = remember(conversation) { touchedFiles(conversation) }
    // Diffing every edit can take a moment in a long chat; keep it off the main thread.
    val touchedLines by
        produceState<TouchedLineCounts?>(null, conversation) {
            value = withContext(Dispatchers.Default) { touchedLineCounts(conversation) }
        }
    val errorCount = remember(conversation) { conversation.count(::isErrorItem) }
    val children =
        remember(chatKey, state.sessions, state.connected, state.loading) {
            if (chatKey == null) emptyList()
            else sessionListItems(state.sessions, state.connected, state.loading)
        }
    val strip =
        remember(chatKey, conversation, children) {
            if (chatKey == null) SubagentStrip(emptyList(), 0, emptySet(), 0)
            else subagentStrip(conversation, children, chatKey.sessionId)
        }
    var onlyErrorsShown by rememberSaveable(key) { mutableStateOf(false) }
    val shownItems =
        remember(visibleConversation, onlyErrorsShown) {
            if (onlyErrorsShown) onlyErrors(visibleConversation) else visibleConversation
        }
    val forkableIds = remember(shownItems) { forkableBubbleIds(shownItems) }
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
            timelineMarkers(shownItems) +
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
    val sessionSearchFocus = remember { FocusRequester() }
    if (key is RemoteNavKey.Sessions && shortcutTargets != null)
        LaunchedEffect(shortcutTargets.searchRequests) {
            // Ctrl+K: each request focuses the search field once, also when it expanded the list
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
    var headerSize by remember { mutableStateOf(IntSize.Zero) }
    val composerHeight = with(LocalDensity.current) { composerSize.height.toDp() }
    val headerHeight = with(LocalDensity.current) { headerSize.height.toDp() }
    val listScope = rememberCoroutineScope()
    var autoFollow by rememberSaveable(key) { mutableStateOf(savedViewport?.following ?: true) }
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
    var touchedSheet by rememberSaveable(key) { mutableStateOf(false) }
    var childCandidates by remember(key) { mutableStateOf<List<SessionListItem>?>(null) }
    var abortChild by remember(key) { mutableStateOf<SubagentStripEntry?>(null) }
    val composerFocus = remember { FocusRequester() }
    var composerFocusRequest by remember { mutableStateOf(0) }
    LaunchedEffect(composerFocusRequest) {
        if (composerFocusRequest == 0) return@LaunchedEffect
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
    fun askToFix(messageId: String) {
        // The prompt fills only a blank draft, so the repository must first hold what was typed.
        composerDraft?.flush()
        chatModel?.askToFix(messageId, askFixPrompt)
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
    var settingsSheetRequested by remember(key) { mutableStateOf(false) }
    var treeSheetRequested by remember(key) { mutableStateOf(false) }
    var artifactsSheetRequested by remember(key) { mutableStateOf(false) }
    // A sheet closed by a lost connection or capability must not reappear by itself after a reconnect.
    val artifactsAvailable = canShowArtifacts(state)
    LaunchedEffect(artifactsAvailable) { if (!artifactsAvailable) artifactsSheetRequested = false }
    /**
     * Runs the draft when it names an available [LocalCommand] and clears it; false leaves the draft
     * for the normal send, which reports unknown or unavailable commands.
     */
    fun runLocalCommand(): Boolean {
        val chat = key as? RemoteNavKey.Chat ?: return false
        val composer = composerDraft ?: return false
        // The composer's text can be ahead of the draft this screen last saw.
        val current = state.copy(draft = composer.text)
        val invocation =
            localInvocation(current, availableLocalCommands(current, true, System.currentTimeMillis(), reloading))
                ?: return false
        // Keeps the typed `/new` while another create is in flight; the navigator would drop it.
        if (invocation.command == LocalCommand.NEW && navigator.mutating) return true
        composer.replace("")
        when (invocation.command) {
            LocalCommand.NEW -> navigator.createSession(chat.routeId, chat.projectId)
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
                if (artifactsAvailable) artifactsSheetRequested = true
                else showToast(R.string.remote_artifacts_host_outdated)
            LocalCommand.NAME -> {
                val session = state.session ?: return true
                if (invocation.argument.isEmpty()) startRename(session.text("id"), session.text("title"))
                else model.renameSession(session.text("id"), invocation.argument)
            }
        }
        return true
    }
    var abortTarget by remember { mutableStateOf<AbortRunTarget?>(null) }
    var disconnecting by rememberSaveable(key) { mutableStateOf(false) }
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
                    RemoteNavKey.Settings -> R.string.remote_settings
                }
        )
    val chatHeader: @Composable () -> Unit = {
        if (key is RemoteNavKey.Chat) {
            val statusLabel =
                stringResource(
                    when {
                        !state.connected || state.status == "offline" -> R.string.remote_status_offline
                        state.status == "running" -> R.string.remote_status_running
                        state.status == "waiting" -> R.string.remote_status_waiting
                        state.status == "idle" -> R.string.remote_status_idle
                        else -> R.string.remote_status_unknown
                    }
                )
            val statusColor = chatStatusColor(state.status, state.connected)
            val canRename = canRenameSession(state)
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
                        if (canRename) add(ChatAction.RENAME)
                        if (state.connected && !state.loading && !navigator.mutating) add(ChatAction.NEW_SESSION)
                        // Offered while pi runs (the sheet then explains it is read-only); `/settings` is typed into an idle draft only.
                        if (state.connected && !state.loading && sessionSettingsAvailable(state))
                            add(ChatAction.SESSION_SETTINGS)
                        add(ChatAction.REFRESH)
                        add(ChatAction.SETTINGS)
                    },
                ).let { if (withHistory) it.withHistory() else it }
            fun runAction(action: ChatAction) {
                chatModel?.recordChatAction(action)
                when (action) {
                    ChatAction.CHANGES -> {
                        historyOpen = false
                        chatModel?.openChanges()
                    }
                    ChatAction.FILES -> {
                        historyOpen = false
                        chatModel?.openFiles()
                    }
                    ChatAction.RENAME ->
                        state.session?.let { session -> startRename(session.text("id"), session.text("title")) }
                    ChatAction.NEW_SESSION -> navigator.createSession(key.routeId, key.projectId)
                    ChatAction.REFRESH -> model.refresh()
                    ChatAction.SESSION_SETTINGS -> settingsSheetRequested = true
                    ChatAction.SETTINGS -> navigator.settings()
                    ChatAction.HISTORY -> historyOpen = !historyOpen
                }
            }
            val actionsControl: @Composable (ChatActionLayout, Boolean) -> Unit = { actionLayout, standaloneHistory ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (standaloneHistory) FloatingSurface(shape = CircleShape, color = headerColor) {
                        IconButton(
                            onClick = { historyOpen = !historyOpen },
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
                                Row(
                                    Modifier.padding(end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        state.session?.optionalText("title")?.takeIf(String::isNotBlank)
                                            ?: title,
                                        modifier = Modifier.widthIn(max = maxTitleWidth),
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
    val changesOpen =
        state.changes?.takeIf { it.sessionId == state.selection.sessionId && chatModel != null }
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
                )
            }
        }
    val filesOpen = state.files?.takeIf { it.sessionId == state.selection.sessionId && chatModel != null }
    val filesContent: (@Composable () -> Unit)? =
        filesOpen?.let { files ->
            {
                FilesPane(
                    files,
                    remember(chatModel, composerDraft) {
                        filesActions(checkNotNull(chatModel), { composerDraft?.flush() }) { composerFocusRequest++ }
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
                    onAskToFix = { tool.outputMessageId?.let(::askToFix) },
                    onQuote = {
                        model.quote(tool.outputMessageId ?: tool.sourceId)
                        closeTool()
                    },
                    onClose = ::closeTool,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    // Wide windows show tool details, diffs and files beside the chat; the tool detail stays on top of
    // the diff there too, as in the overlay.
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
        if (historyOpen && key is RemoteNavKey.Chat && sideInspector) {
            { ChatHistoryPane(history, onOpen = ::jumpTo, onClose = { historyOpen = false }, initialIndex = historyStart) }
        } else null
    val inspectorContent: (@Composable () -> Unit)? =
        if (changesContent == null && filesContent == null && toolContent == null && historyContent == null) null
        else {
            {
                changesContent?.invoke()
                filesContent?.invoke()
                toolContent?.invoke()
                historyContent?.invoke()
            }
        }
    val inspectorShown = sideInspector && (paneLayout.inspector == InspectorMode.PERSISTENT || inspectorContent != null)
    val focusManager = LocalFocusManager.current
    // With a hardware keyboard a chat opens ready for typing, unless a question or a dialog is up
    // or TalkBack runs: then focus stays where the user put it.
    val chatDialogOpen = renameSessionId != null || abortTargetIsCurrent || abortChild != null || tuiInfoOpen
    val jobsOpen =
        state.jobs?.let { (it.listOpen || it.view != null) && it.sessionId == state.selection.sessionId } == true
    // Files, Changes, a tool detail or the jobs fill the chat's place and hide the composer.
    val composerCovered =
        chatKey != null && chatModel != null &&
            (jobsOpen || (!sideInspector && (changesOpen != null || filesOpen != null || openTool != null)))
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
            Shortcut.CHANGES ->
                when {
                    changesOpen != null -> chat.closeChanges().let { true }
                    canViewChanges(state) && state.connected -> {
                        historyOpen = false
                        chat.openChanges()
                        true
                    }
                    else -> false
                }
            Shortcut.FILES ->
                when {
                    filesOpen != null -> chat.closeFiles().let { true }
                    canBrowseFiles(state) && state.connected -> {
                        historyOpen = false
                        chat.openFiles()
                        true
                    }
                    else -> false
                }
            Shortcut.JOBS ->
                when {
                    jobsOpen -> chat.closeJobs().let { true }
                    jobsAvailable && state.connected -> {
                        chat.openJobs()
                        true
                    }
                    else -> false
                }
            Shortcut.HISTORY -> {
                historyOpen = !historyOpen
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
        }
    }
    CompositionLocalProvider(LocalChatKeyTarget provides chatKeyTarget.takeIf { chatKey != null }) {
    InspectorRow(
        shown = inspectorShown,
        width = { twoPane?.inspectorWidth(paneLayout) ?: paneLayout.inspectorWidth },
        onResize = twoPane?.let { pane -> { width -> pane.resizeInspector(paneLayout, width) } },
        inspector = inspectorContent,
    ) {
    Scaffold(
        // Esc parks keyboard focus here, on the chat itself: clearing it would hand it to the
        // first button (Back) while a keyboard is in use, and Enter would then press it. It is
        // focusable only while parked, so Tab and the initial focus never stop on it. Enter on
        // it is used up: Compose would move focus into the first button (Back) instead.
        modifier =
            if (chatKey != null)
                Modifier.focusRequester(chatFocus)
                    .onFocusChanged {
                        // Only leaving the parked focus ends it; the composer's focus passing
                        // through on its way here does not.
                        if (chatFocused && !it.isFocused) chatFocusParked = false
                        chatFocused = it.isFocused
                    }
                    .onKeyEvent { chatFocused && it.key in FOCUS_ENTER_KEYS }
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
                            is RemoteNavKey.Projects -> {
                                if (canManageProviders(state))
                                    OutlinedButton(
                                        onClick = { navigator.openProviders(key.routeId) },
                                        enabled = !state.loading,
                                        modifier = Modifier.fillMaxWidth().testTag("providersButton"),
                                    ) {
                                        Text(stringResource(R.string.providers_open))
                                    }
                                OutlinedButton(
                                    onClick = { disconnecting = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.remote_disconnect))
                                }
                            }
                        }
                    }
                }
        },
    ) { insets ->
        BoxWithConstraints(
            if (key is RemoteNavKey.Chat)
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()
            else Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
        ) {
        // While the tool detail covers the chat, the chat below must not be reachable by TalkBack.
        val underlay = if (openTool != null && !sideInspector) Modifier.clearAndSetSemantics {} else Modifier
        // A wide chat pane centres its bubbles in a reading column; phones never reach the limit.
        val readingInset =
            if (key is RemoteNavKey.Chat) ((maxWidth - 40.dp - MAX_READING_WIDTH) / 2).coerceAtLeast(0.dp)
            else 0.dp
        // The inspector's resize handle reaches into the chat's end: the scrollbar lane starts
        // clear of it. Constant while the inspector is open, so scrolling never moves anything.
        val laneInset =
            if (key is RemoteNavKey.Chat && inspectorShown && twoPane != null) maxOf(readingInset, PANE_RESIZE_HANDLE_OVERHANG)
            else readingInset
        LazyColumn(
            if (key is RemoteNavKey.Chat)
                Modifier.fillMaxSize().testTag("conversationList").then(underlay)
                    .onFocusChanged { conversationFocused = it.hasFocus }
            else Modifier.fillMaxSize(),
            state = listState,
            contentPadding =
                PaddingValues(
                    start = 20.dp + readingInset,
                    // Constant, so nothing rewraps while scrolling; the scrollbar lives in this lane.
                    end = CHAT_SCROLLBAR_LANE + laneInset,
                    top = headerHeight + if (key is RemoteNavKey.Chat) 16.dp else 48.dp,
                    bottom =
                        if (
                            key is RemoteNavKey.Sessions || key is RemoteNavKey.Hosts ||
                                (key is RemoteNavKey.Projects && canOpenFolders(state))
                        ) 112.dp
                        else if (key is RemoteNavKey.Chat) composerHeight + 24.dp
                        else 24.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Every item emitted before the conversation counts here, so a jump can turn an index
            // in shownItems into a list index.
            var leading = 0
            if (key !is RemoteNavKey.Chat)
                item(key = "page-title") {
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
                item {
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
                item {
                    Section(stringResource(R.string.remote_keyboard_title)) {
                        Text(
                            stringResource(R.string.remote_keyboard_help),
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
                        Column(
                            Modifier.testTag("keyboardShortcuts"),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            listOf(
                                R.string.remote_keyboard_shortcut_send,
                                R.string.remote_keyboard_shortcut_search,
                                R.string.remote_keyboard_shortcut_switch,
                                R.string.remote_keyboard_shortcut_project,
                            ).forEach {
                                Text(
                                    stringResource(it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item { PredictionSettingsSection() }
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
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
                                }
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.remote_settings_privacy_open))
                        }
                    }
                }
            } else {
                if (
                    (state.host != null || state.connection != R.string.remote_offline) &&
                        !(state.connected &&
                            (key is RemoteNavKey.Sessions || key is RemoteNavKey.Chat))
                ) {
                    leading++
                    item {
                        Text(
                            stringResource(state.connection),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (state.uncertain) {
                    leading++
                    item {
                        Text(
                            stringResource(R.string.remote_draft_uncertain),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                when (key) {
                    RemoteNavKey.Settings -> Unit
                    is RemoteNavKey.FolderBrowser -> Unit
                    is RemoteNavKey.Providers -> Unit
                    RemoteNavKey.Hosts -> {
                        if (state.hosts.isEmpty()) {
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
                        if (state.projects.isEmpty())
                            item { Text(stringResource(R.string.remote_empty_projects)) }
                        else {
                            item(key = "shared_projects_heading") {
                                Text(
                                    stringResource(R.string.remote_projects_shared),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 28.dp, bottom = 4.dp)
                                        .semantics { heading() },
                                )
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
                                )
                            }
                        }
                        // Quick chats only refresh while no project is open; beside one they hide.
                        if (state.projectChats.isNotEmpty() && !projectSelected) {
                            item(key = "project_chats_heading") {
                                Text(
                                    stringResource(R.string.remote_projects_quick_chats),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 28.dp, bottom = 4.dp)
                                        .semantics { heading() },
                                )
                            }
                            val featuredChats = state.projectChats.take(3)
                            val groups = listOf(
                                R.string.remote_projects_waiting to featuredChats.filter {
                                    it.verified && state.connected && it.session.text("status") == "waiting"
                                },
                                R.string.remote_projects_running to featuredChats.filter {
                                    it.verified && state.connected && it.session.text("status") == "running"
                                },
                                R.string.remote_projects_recent to featuredChats.filter {
                                    !it.verified || !state.connected ||
                                        it.session.text("status") !in setOf("waiting", "running")
                                },
                            )
                            groups.forEach { (label, chats) ->
                                if (chats.isNotEmpty()) {
                                    item(key = "project_chats_group_$label") {
                                        Text(
                                            stringResource(label),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 12.dp),
                                        )
                                    }
                                    items(
                                        chats,
                                        key = { "chat:${it.projectId}:${it.session.text("id")}" },
                                    ) { chat ->
                                        ProjectChatRow(
                                            chat = chat,
                                            connected = state.connected,
                                            enabled = !state.loading,
                                            onClick = {
                                                navigator.open(RemoteNavKey.Chat(
                                                    key.routeId,
                                                    chat.projectId,
                                                    chat.session.text("id"),
                                                ))
                                            },
                                        )
                                    }
                                }
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
                        else if (visibleSessions.isEmpty() && state.connected && !state.loading)
                            item { Text(stringResource(R.string.remote_offline_sessions_hidden)) }
                        else if (listedSessions.isEmpty() && visibleSessions.isNotEmpty())
                            item { Text(stringResource(R.string.remote_session_search_empty)) }
                        items(listedSessions, key = SessionListItem::id) { session ->
                            SessionListRow(
                                item = session,
                                selected = session.id == listPane?.selectedSessionId,
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
                                        historyOpen = false
                                        openToolId = it.id
                                    },
                                    onOpenAgent = ::openAgent,
                                    onAskToFix = if (acceptsPrompts) ::askToFix else null,
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
        if (key !is RemoteNavKey.Chat) {
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .onSizeChanged { headerSize = it },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Beside the projects column, its own Back pill leads back.
                    if (key != RemoteNavKey.Hosts && listPane?.besideProjects != true)
                        FloatingSurface(
                            modifier = Modifier.testTag("navigationPill"),
                            shape = CircleShape,
                        ) {
                            IconButton(onClick = navigator::back, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    stringResource(R.string.remote_back),
                                )
                            }
                        }
                    Spacer(Modifier.weight(1f))
                    // The full projects column beside the list already offers refresh and settings;
                    // the strip beside a chat does not.
                    if (!settings && !(listPane?.besideProjects == true && !listPane.projectsStrip))
                        FloatingSurface(
                            modifier = Modifier.testTag("headerActionsPill"),
                            shape = CircleShape,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                                IconButton(onClick = navigator::settings, modifier = Modifier.size(48.dp)) {
                                    Icon(
                                        Icons.Default.Settings,
                                        stringResource(R.string.remote_settings),
                                    )
                                }
                            }
                        }
                }
            }
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
                                    TouchedFilesTarget.CHANGES -> {
                                        historyOpen = false
                                        chatModel?.openChanges()
                                    }
                                    TouchedFilesTarget.SHEET -> touchedSheet = true
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
                                    BackgroundJobsChip(jobs, remember(chat) {
                                        JobsActions(chat::refreshJobs, chat::openJob, chat::closeJob, chat::closeJobs, chat::stopJob)
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
                    if (artifactsSheetRequested && chatKey != null && treeSessionId != null && treeChat != null &&
                        canShowArtifacts(state))
                        SessionArtifactsSheet(
                            sessionId = treeSessionId,
                            list = { treeChat.listSessionArtifacts(treeSessionId) },
                            open = { artifact, listed ->
                                // The listed version is pinned to the listed digest; a reload takes the latest.
                                treeChat.openSessionArtifact(
                                    treeSessionId, artifact.id,
                                    if (listed) artifact.version else null,
                                    artifact.type,
                                    if (listed) artifact.sha256 else null,
                                )
                            },
                            onDismiss = { artifactsSheetRequested = false },
                        )
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
                        onDismissFollowUp = model::dismissFollowUp,
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
                                        else if (artifactsAvailable) artifactsSheetRequested = true
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
                                changeSettings = model::changeSettings,
                                settingsSheetRequested = settingsSheetRequested,
                                onSettingsSheetRequestHandled = { settingsSheetRequested = false },
                                slashSuggestions = !composer.suggestionsClosed,
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
            if (!sideInspector) changesContent?.invoke()
            if (!sideInspector) filesContent?.invoke()
            state.jobs
                ?.takeIf {
                    // Stays open through a reconnect, which clears the capabilities for a moment.
                    (it.listOpen || it.view != null) && it.sessionId == state.selection.sessionId && chatModel != null
                }
                ?.let { jobs ->
                    val chat = checkNotNull(chatModel)
                    BackgroundJobsScreen(
                        jobs,
                        remember(chat) {
                            JobsActions(chat::refreshJobs, chat::openJob, chat::closeJob, chat::closeJobs, chat::stopJob)
                        },
                        Modifier.fillMaxSize(),
                    )
                }
            if (!sideInspector) toolContent?.invoke()
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
    if (touchedSheet && key is RemoteNavKey.Chat)
        TouchedFilesSheet(touched, onDismiss = { touchedSheet = false }, onOpen = ::jumpTo)
    if (historyOpen && key is RemoteNavKey.Chat && !sideInspector)
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
    removing?.let { host ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remote_remove)) },
            text = { Text(stringResource(R.string.remote_remove_help)) },
            confirmButton = {
                TextButton(
                    onClick = {
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
                        (model as? SessionsViewModel)?.close(session.id)
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

@Composable
private fun ProjectChatRow(
    chat: ProjectChatSummary,
    connected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Text(
            chat.projectName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SessionListRow(
            item = sessionListItem(chat.session, connected && chat.verified, loading = false),
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
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
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
