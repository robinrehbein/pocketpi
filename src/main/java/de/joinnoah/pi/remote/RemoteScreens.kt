package de.joinnoah.pi.remote

import android.Manifest
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
private const val CHILD_REFRESH_ATTEMPTS = 12
private const val CHILD_REFRESH_INTERVAL_MILLIS = 5_000L
private const val JUMP_HIGHLIGHT_MILLIS = 2_000L
private const val COMPACTION_TICK_MILLIS = 30_000L
private const val PRIVACY_POLICY_URL = "https://robinrehbein.de/privacy"
private val LIST_TO_HEADER_END_SHIFT = 4.dp

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
    timelineVisibility: TimelineVisibility,
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
    val detailPane = LocalDetailPane.current && key is RemoteNavKey.Chat
    val sideInspector = key is RemoteNavKey.Chat && paneLayout.inspector != InspectorMode.OVERLAY
    // Hardware Enter and Ctrl+Enter only send in the two-pane layout; phones keep Enter as a newline.
    val hardwareKeyboard = LocalHardwareKeyboard.current && paneLayout.twoPane
    var sessionQuery by rememberSaveable(key) { mutableStateOf("") }
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
        remember(visibleSessions, sessionQuery, listPane != null) {
            if (listPane != null) filterSessions(visibleSessions, sessionQuery) else visibleSessions
        }
    if (listPane != null && twoPane != null)
        LaunchedEffect(listedSessions) { twoPane.visibleSessionIds = listedSessions.map { it.id } }
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
    val messageFork =
        if (chatKey != null && canFork(state))
            MessageFork(
                ready = state.connected && !state.loading && forkStopped(state),
                stopFirst = forkRunning(state),
                onFork = { messageId, mode -> navigator.forkSession(chatKey, messageId, mode) },
            )
        else null
    val questionPending = state.questions.isNotEmpty()
    val markers =
        remember(shownItems, questionPending) {
            timelineMarkers(shownItems) +
                if (questionPending)
                    listOf(TimelineMarker(PENDING_QUESTION_MARKER, TimelineMarkerKind.QUESTION, 1f))
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
    if (listPane != null && twoPane != null)
        LaunchedEffect(twoPane.searchRequests) {
            // Ctrl+K: each request focuses the search field once, also when it expanded the list.
            if (twoPane.searchRequests == twoPane.searchHandled) return@LaunchedEffect
            twoPane.searchHandled = twoPane.searchRequests
            listState.scrollToItem(0)
            withFrameNanos { }
            runCatching { sessionSearchFocus.requestFocus() }
        }
    var composerSize by remember { mutableStateOf(IntSize.Zero) }
    var headerSize by remember { mutableStateOf(IntSize.Zero) }
    val composerHeight = with(LocalDensity.current) { composerSize.height.toDp() }
    val headerHeight = with(LocalDensity.current) { headerSize.height.toDp() }
    val listScope = rememberCoroutineScope()
    var autoFollow by rememberSaveable(key) { mutableStateOf(savedViewport?.following ?: true) }
    var timelineScrollActive by remember(key) { mutableStateOf(false) }
    var tuiInfoOpen by remember { mutableStateOf(false) }
    var scrollingProgrammatically by remember { mutableStateOf(false) }
    LaunchedEffect(key, listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            if (!scrollingProgrammatically) timelineScrollActive = true
        } else {
            // Leave time to tap a marker or the collapse button after lifting a finger.
            delay(1500)
            timelineScrollActive = false
        }
    }
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
            listState.animateScrollToItem(leadingItems.count + index)
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
    val snackbar = remember { SnackbarHostState() }
    val childNotice = stringResource(R.string.remote_insights_child_not_found)
    val askFixPrompt = stringResource(R.string.remote_insights_ask_fix_prompt)
    val acceptsPrompts =
        state.connected && !state.loading && state.questions.isEmpty() && !state.sending &&
            (state.status == "idle" ||
                (state.status == "running" && (canSteer(state) || canFollowUp(state))))
    fun askToFix(messageId: String) {
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
    /**
     * Runs the draft when it names an available [LocalCommand] and clears it; false leaves the draft
     * for the normal send, which reports unknown or unavailable commands.
     */
    fun runLocalCommand(): Boolean {
        val chat = key as? RemoteNavKey.Chat ?: return false
        val invocation =
            localInvocation(state, availableLocalCommands(state, true, System.currentTimeMillis(), reloading))
                ?: return false
        model.draft("")
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
            val actionLayout =
                chatActionLayout(
                    chatModel?.chatActions ?: DEFAULT_CHAT_ACTIONS,
                    buildSet {
                        if (canViewChanges(state) && state.connected) add(ChatAction.CHANGES)
                        if (canBrowseFiles(state) && state.connected) add(ChatAction.FILES)
                        if (canRename) add(ChatAction.RENAME)
                        if (state.connected && !state.loading) add(ChatAction.NEW_SESSION)
                        // Offered while pi runs (the sheet then explains it is read-only); `/settings` is typed into an idle draft only.
                        if (state.connected && !state.loading && sessionSettingsAvailable(state))
                            add(ChatAction.SESSION_SETTINGS)
                        add(ChatAction.REFRESH)
                        add(ChatAction.SETTINGS)
                    },
                )
            fun runAction(action: ChatAction) {
                chatModel?.recordChatAction(action)
                when (action) {
                    ChatAction.CHANGES -> chatModel?.openChanges()
                    ChatAction.FILES -> chatModel?.openFiles()
                    ChatAction.RENAME ->
                        state.session?.let { session -> startRename(session.text("id"), session.text("title")) }
                    ChatAction.NEW_SESSION -> navigator.createSession(key.routeId, key.projectId)
                    ChatAction.REFRESH -> model.refresh()
                    ChatAction.SESSION_SETTINGS -> settingsSheetRequested = true
                    ChatAction.SETTINGS -> navigator.settings()
                }
            }
            val actionsControl: @Composable () -> Unit = {
                ChatActionPill(actionLayout, headerColor, ::runAction)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val narrow = maxWidth < 360.dp
                val actionsWidth =
                    48.dp * (actionLayout.shown.size + if (actionLayout.menu.isEmpty()) 0 else 1)
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
                        if (!narrow) actionsControl()
                    }
                    if (narrow) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            actionsControl()
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
                    remember(chatModel) { changesActions(checkNotNull(chatModel)) { composerFocusRequest++ } },
                    Modifier.fillMaxSize(),
                )
            }
        }
    val filesOpen = state.files?.takeIf { it.sessionId == state.selection.sessionId && chatModel != null }
    val filesContent: (@Composable () -> Unit)? =
        filesOpen?.let { files ->
            {
                FilesPane(
                    files,
                    remember(chatModel) { filesActions(checkNotNull(chatModel)) { composerFocusRequest++ } },
                    Modifier.fillMaxSize(),
                    projectName = state.project?.optionalText("name")?.takeIf(String::isNotBlank),
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
    val inspectorContent: (@Composable () -> Unit)? =
        if (changesContent == null && filesContent == null && toolContent == null) null
        else {
            {
                changesContent?.invoke()
                filesContent?.invoke()
                toolContent?.invoke()
            }
        }
    InspectorRow(
        shown = sideInspector && (paneLayout.inspector == InspectorMode.PERSISTENT || inspectorContent != null),
        width = paneLayout.inspectorWidth,
        inspector = inspectorContent,
    ) {
    Scaffold(
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
                            if (key is RemoteNavKey.Sessions) state.connected && !state.loading
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
        val timelineControlsShown = key is RemoteNavKey.Chat && timelineRailVisible(markers) &&
            listScrollable && timelineScrollActive
        val timelineExpanded = chatKey?.let(timelineVisibility::expanded) ?: true
        val railShown = timelineControlsShown && timelineExpanded
        LazyColumn(
            if (key is RemoteNavKey.Chat) Modifier.fillMaxSize().testTag("conversationList").then(underlay)
            else Modifier.fillMaxSize(),
            state = listState,
            contentPadding =
                PaddingValues(
                    start = 20.dp + readingInset,
                    // Room for the timeline rail, so it never covers a card's controls.
                    end = maxOf(if (railShown) 76.dp else 20.dp, 20.dp + readingInset),
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
            if (state.error != null && key !is RemoteNavKey.Chat &&
                state.error != R.string.remote_command_unknown &&
                listPane?.selectedSessionId == null
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
                                .selectable(selected = theme == "system", role = Role.RadioButton) {
                                    (model as SettingsViewModel).setTheme("system")
                                }
                                .testTag("themeSystem")
                                .padding(vertical = 8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = theme == "system", onClick = null)
                            Text(
                                stringResource(R.string.remote_theme_system),
                                Modifier.padding(start = 12.dp).weight(1f),
                            )
                            SystemThemeIcon()
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            if (maxWidth < 240.dp) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ThemeChoice("light", R.string.remote_theme_light, theme == "light") {
                                        (model as SettingsViewModel).setTheme("light")
                                    }
                                    ThemeChoice("dark", R.string.remote_theme_dark, theme == "dark") {
                                        (model as SettingsViewModel).setTheme("dark")
                                    }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.weight(1f)) {
                                        ThemeChoice("light", R.string.remote_theme_light, theme == "light") {
                                            (model as SettingsViewModel).setTheme("light")
                                        }
                                    }
                                    Box(Modifier.weight(1f)) {
                                        ThemeChoice("dark", R.string.remote_theme_dark, theme == "dark") {
                                            (model as SettingsViewModel).setTheme("dark")
                                        }
                                    }
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
                                    unshare = projectUnshare(state.connected, state.loading, state.capabilities),
                                    onClick = {
                                        navigator.open(RemoteNavKey.Sessions(key.routeId, projectId))
                                    },
                                    onUnshare = { unsharing = projectId to project.text("name") },
                                )
                            }
                        }
                        if (state.projectChats.isNotEmpty()) {
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
                        if (listPane != null && state.sessions.isNotEmpty())
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
                            item { ChatSkeleton() }
                        }
                        if (state.nextCursor != null) {
                            leading++
                            item {
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
                        items(shownItems, key = { it.id }) { item ->
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
                                    onOpenTool = { openToolId = it.id },
                                    onOpenAgent = ::openAgent,
                                    onAskToFix = if (acceptsPrompts) ::askToFix else null,
                                    highlighted = highlighted,
                                    fork = messageFork?.takeIf { item.id in forkableIds },
                                    images = sentImages,
                                    projectImages = projectImages,
                                    copyButton = item.id == copyButtonId,
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
                    if (key != RemoteNavKey.Hosts)
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
                    if (!settings)
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
        if (railShown) {
            Box(
                Modifier.align(Alignment.TopCenter)
                    .widthIn(max = MAX_READING_WIDTH + 32.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = headerHeight + 8.dp,
                        bottom = composerHeight + 8.dp,
                    )
                    .then(underlay),
            ) {
                TimelineRail(
                    markers = markers,
                    onJump = ::jumpTo,
                    scrollState = listState,
                    // Share the composer's inset and the toggle button's center axis.
                    modifier = Modifier.align(Alignment.TopEnd).width(48.dp).fillMaxHeight(),
                )
            }
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
                    if (touched.total > 0 || errorCount > 0 || onlyErrorsShown ||
                        strip.entries.isNotEmpty() || !autoFollow || timelineControlsShown
                    )
                        Row(
                            Modifier.fillMaxWidth().testTag("chatInsights"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Row(
                                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TouchedFilesSummary(touched, touchedLines) {
                                    when (touchedFilesTarget(canViewChanges(state) && state.connected)) {
                                        TouchedFilesTarget.CHANGES -> chatModel?.openChanges()
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
                                SubagentStrip(
                                    strip,
                                    canAbort = { entry ->
                                        entry.sessionId?.let { canAbortSubagent(state, it) } == true
                                    },
                                    onOpen = { entry -> entry.sessionId?.takeIf { entry.openable }?.let(::openChildSession) },
                                    onAbort = { entry -> if (entry.sessionId != null) abortChild = entry },
                                )
                            }
                            if (timelineControlsShown || !autoFollow) {
                                Spacer(Modifier.width(8.dp))
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    if (timelineControlsShown)
                                        SmallFloatingActionButton(
                                            onClick = { chatKey?.let(timelineVisibility::toggle) },
                                            modifier = Modifier.testTag("timelineToggle"),
                                        ) {
                                            Icon(
                                                if (timelineExpanded) Icons.Default.ChevronRight
                                                else Icons.Default.ChevronLeft,
                                                stringResource(
                                                    if (timelineExpanded) R.string.remote_panel_timeline_hide
                                                    else R.string.remote_panel_timeline_show,
                                                ),
                                            )
                                        }
                                    if (!autoFollow)
                                        SmallFloatingActionButton(onClick = ::scrollToLatestItem) {
                                            Icon(
                                                Icons.Default.ArrowDownward,
                                                stringResource(R.string.remote_scroll_to_bottom),
                                            )
                                        }
                                }
                            }
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
                        )
                    ChatComposer(
                        state = state,
                        onDraft = model::draft,
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
                            SessionControls(
                                state,
                                model::refreshConfiguration,
                                model::refreshContextUsage,
                                model::compactContext,
                                model::refreshAdvisor,
                                model::setAdvisor,
                                model::setModel,
                                model::setThinkingLevel,
                                model::refreshCommands,
                                model::selectCommand,
                                refreshJobs = { chatModel?.refreshJobs() },
                                openJobs = chatModel?.let { chat -> chat::openJobs },
                                localCommands =
                                    availableLocalCommands(state, key is RemoteNavKey.Chat, System.currentTimeMillis(), reloading),
                                selectLocalCommand = { command ->
                                    // The tree only reads, so it opens at once, also while the chat runs,
                                    // when a /tree draft could not be sent.
                                    if (command == LocalCommand.TREE) {
                                        // Like a sent /tree, a command draft does not stay in the composer.
                                        if (state.draft.trimStart().startsWith("/")) model.draft("")
                                        treeSheetRequested = true
                                    }
                                    // A terminal session only explains itself; nothing to send.
                                    else if (command == LocalCommand.RELOAD &&
                                        (reloading || reloadAvailability(state) == ReloadAvailability.TERMINAL_ONLY)
                                    ) {
                                        if (state.draft.trimStart().startsWith("/")) model.draft("")
                                        showToast(
                                            if (reloading) R.string.remote_reload_running
                                            else R.string.remote_reload_terminal_hint
                                        )
                                    }
                                    else model.draft(selectCommandName(state.draft, command.commandName))
                                },
                                reloading = reloading,
                                modelPickerRequested = modelPickerRequested,
                                onModelPickerRequestHandled = { modelPickerRequested = false },
                                changeSettings = model::changeSettings,
                                settingsSheetRequested = settingsSheetRequested,
                                onSettingsSheetRequestHandled = { settingsSheetRequested = false },
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
                    it.listOpen && it.sessionId == state.selection.sessionId && chatModel != null
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
private fun SystemThemeIcon() {
    val outline = MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(28.dp).clearAndSetSemantics {}) {
        val circle = Path().apply { addOval(Rect(0f, 0f, size.width, size.height)) }
        val darkHalf = Path().apply {
            moveTo(0f, size.height)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            close()
        }
        clipPath(circle) {
            drawRect(Color(0xFFF4F7FF))
            drawPath(darkHalf, Color(0xFF30343C))
        }
        drawCircle(outline, style = Stroke(width = 1.5.dp.toPx()))
    }
}

@Composable
private fun ThemeChoice(value: String, label: Int, selected: Boolean, onSelect: () -> Unit) {
    val outline =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag("theme${value.replaceFirstChar { it.uppercase() }}")
            .border(2.dp, outline, RoundedCornerShape(16.dp))
            .padding(8.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ThemePreview(dark = value == "dark")
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun ThemePreview(dark: Boolean) {
    val shell = if (dark) Color(0xFF30343C) else Color(0xFFECEEF3)
    val panel = if (dark) Color(0xFF505968) else Color.White
    val line = if (dark) Color(0xFFB8C2D1) else Color(0xFF6B7586)
    val dot = if (dark) Color(0xFF90B9FF) else Color(0xFF245DC8)
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
                    Box(Modifier.size(8.dp).background(dot, CircleShape))
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
