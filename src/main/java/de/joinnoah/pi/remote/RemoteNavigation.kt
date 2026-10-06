package de.joinnoah.pi.remote

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.metadata
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
sealed interface RemoteNavKey : NavKey {
    @Serializable data object Hosts : RemoteNavKey

    @Serializable data class Projects(val routeId: String) : RemoteNavKey

    @Serializable data class Sessions(val routeId: String, val projectId: String) : RemoteNavKey

    @Serializable
    data class Chat(val routeId: String, val projectId: String, val sessionId: String) :
        RemoteNavKey

    @Serializable data object Settings : RemoteNavKey

    /**
     * The folder browser of one host. It sits on top of that host's Projects and keeps the
     * Projects selection; the browsed path lives in [RemoteState.folders].
     */
    @Serializable data class FolderBrowser(val routeId: String) : RemoteNavKey

    /**
     * The providers of one host (`provider.auth.v1`), on top of that host's Projects like
     * [FolderBrowser]. Only the route is saved; the login flow lives in [RemoteState.providerAuth]
     * and is recovered from the host.
     */
    @Serializable data class Providers(val routeId: String) : RemoteNavKey
}

internal fun RemoteNavKey.selection(): RemoteSelection =
    when (this) {
        RemoteNavKey.Hosts,
        RemoteNavKey.Settings -> RemoteSelection()
        is RemoteNavKey.Projects -> RemoteSelection(routeId)
        is RemoteNavKey.FolderBrowser -> RemoteSelection(routeId)
        is RemoteNavKey.Providers -> RemoteSelection(routeId)
        is RemoteNavKey.Sessions -> RemoteSelection(routeId, projectId)
        is RemoteNavKey.Chat -> RemoteSelection(routeId, projectId, sessionId)
    }

internal fun RemoteSelection.keys(): List<RemoteNavKey> = buildList {
    add(RemoteNavKey.Hosts)
    val route = routeId ?: return@buildList
    add(RemoteNavKey.Projects(route))
    val project = projectId ?: return@buildList
    add(RemoteNavKey.Sessions(route, project))
    sessionId?.let { add(RemoteNavKey.Chat(route, project, it)) }
}

/** Keeps each chat's timeline choice when its navigation entry is replaced by another chat. */
internal class TimelineVisibility(initialCollapsed: Set<String> = emptySet()) {
    private var collapsed by mutableStateOf(initialCollapsed)

    private fun id(key: RemoteNavKey.Chat): String =
        listOf(key.routeId, key.projectId, key.sessionId).joinToString("") { "${it.length}:$it" }

    fun expanded(key: RemoteNavKey.Chat): Boolean = id(key) !in collapsed

    fun toggle(key: RemoteNavKey.Chat) {
        val id = id(key)
        collapsed = if (id in collapsed) collapsed - id else collapsed + id
    }

    companion object {
        val Saver = Saver<TimelineVisibility, ArrayList<String>>(
            save = { ArrayList(it.collapsed) },
            restore = { TimelineVisibility(it.toSet()) },
        )
    }
}

internal class RemoteNavigator(
    private val repository: RemoteRepository,
    private val stack: MutableList<NavKey>,
    private val scope: CoroutineScope,
    /** Called after a pairing succeeded and its selection was opened. */
    private val onPaired: () -> Unit = {},
) {
    private var request = 0L
    private var activation: Job? = null
    private var consumePendingNotification: (() -> Unit)? = null

    private fun invalidate(): Long {
        consumePendingNotification?.invoke()
        consumePendingNotification = null
        activation?.cancel()
        repository.cancelSelection()
        return ++request
    }

    private fun setStack(target: List<RemoteNavKey>, keepSettings: Boolean) {
        val settings = keepSettings && stack.lastOrNull() == RemoteNavKey.Settings
        val keys = target + if (settings) listOf(RemoteNavKey.Settings) else emptyList()
        // Keep equal entries so their ViewModel stores and saved UI state survive.
        val common = stack.zip(keys).takeWhile { (old, new) -> old == new }.size
        while (stack.size > common) stack.removeAt(stack.lastIndex)
        stack.addAll(keys.drop(common))
    }

    private fun topKey(): RemoteNavKey? =
        stack.lastOrNull { it != RemoteNavKey.Settings } as? RemoteNavKey

    /** Chat entries stacked directly beneath the top chat in the same project, oldest first. */
    private fun chatsBeneathTop(): List<RemoteNavKey.Chat> {
        val entries = stack.filter { it != RemoteNavKey.Settings }
        val top = entries.lastOrNull() as? RemoteNavKey.Chat ?: return emptyList()
        return entries
            .dropLast(1)
            .asReversed()
            .asSequence()
            .map { it as? RemoteNavKey.Chat }
            .takeWhile { it != null && it.routeId == top.routeId && it.projectId == top.projectId }
            .filterNotNull()
            .toList()
            .asReversed()
    }

    private fun sessionKeys(chat: RemoteNavKey.Chat): List<RemoteNavKey> =
        chat.selection().keys().dropLast(1)

    private fun stacked(chats: List<RemoteNavKey.Chat>): List<RemoteNavKey.Chat> =
        chats.takeLast(MAX_STACKED_CHATS)

    private fun replace(
        selection: RemoteSelection,
        keepSettings: Boolean = true,
        keepStackedChats: Boolean = true,
    ) {
        val keys = selection.keys()
        // The folder browser shares the Projects selection, so it survives a restore on top of it.
        val folders = topKey()?.takeIf { it is RemoteNavKey.FolderBrowser || it is RemoteNavKey.Providers }
        if (folders != null && selection == folders.selection()) {
            setStack(keys + folders, keepSettings)
            return
        }
        val chat = keys.lastOrNull() as? RemoteNavKey.Chat
        // A child chat opened on top of its parent keeps the parent beneath it across
        // restores and reconnects.
        val beneath =
            if (keepStackedChats && chat != null && topKey() == chat) chatsBeneathTop()
            else emptyList()
        if (chat == null || beneath.isEmpty()) {
            setStack(keys, keepSettings)
        } else {
            setStack(sessionKeys(chat) + stacked(beneath + chat), keepSettings)
        }
    }

    private fun activate(key: RemoteNavKey, mode: ActivationMode) {
        val current = invalidate()
        activation = scope.launch {
            val canonical = repository.activate(key.selection(), mode)
            if (current != request) return@launch
            val chat = canonical.keys().lastOrNull() as? RemoteNavKey.Chat
            // The restored chat's own session is gone (offline, or otherwise dropped by the host).
            // Walk the stacked ancestors closest first, actually re-activating each through the
            // repository directly (never through this function, which would reset invalidate()'s
            // request and race a later navigation) so its own selection and loaded state match,
            // and land on the first one that is still live. An ancestor that is itself gone is
            // skipped in favor of the next one further out; only when none of them resolve does
            // the whole stack collapse to the sessions list underneath it.
            if (mode == ActivationMode.RESTORE && chat == null && key is RemoteNavKey.Chat && topKey() == key) {
                val ancestors = chatsBeneathTop()
                for (index in ancestors.indices.reversed()) {
                    val candidate = ancestors[index]
                    val restored = repository.activate(candidate.selection(), ActivationMode.RESTORE)
                    if (current != request) return@launch
                    if (restored == candidate.selection()) {
                        setStack(sessionKeys(candidate) + stacked(ancestors.subList(0, index + 1)), keepSettings = true)
                        return@launch
                    }
                }
            }
            replace(canonical)
        }
    }

    fun reconcile(state: RemoteState) {
        if (state.loading || activation?.isActive == true) return
        val current = stack.lastOrNull { it != RemoteNavKey.Settings } as? RemoteNavKey ?: return
        val keys = current.selection().keys()
        val validated = state.selection.keys()
        if (validated.size < keys.size && keys.take(validated.size) == validated) {
            replace(state.selection)
        }
    }

    fun restore() {
        val key =
            stack.lastOrNull { it != RemoteNavKey.Settings } as? RemoteNavKey ?: RemoteNavKey.Hosts
        activate(key, ActivationMode.RESTORE)
    }

    fun open(key: RemoteNavKey) {
        replace(key.selection(), keepSettings = false, keepStackedChats = false)
        activate(key, ActivationMode.USER_OPEN)
    }

    /**
     * Opens [child] on top of [parent] so back returns to the parent chat. Only the selection
     * survives process death, so a restored child comes back without its parent.
     */
    fun openChild(parent: RemoteNavKey.Chat, child: RemoteNavKey.Chat) {
        if (
            parent.routeId != child.routeId ||
                parent.projectId != child.projectId ||
                parent.sessionId == child.sessionId
        ) {
            open(child)
            return
        }
        val ancestors =
            (if (topKey() == parent) chatsBeneathTop() else emptyList()) + parent
        val base = sessionKeys(parent)
        val current = invalidate()
        setStack(base + stacked(ancestors + child), keepSettings = false)
        activation = scope.launch {
            val canonical = repository.activate(child.selection(), ActivationMode.USER_OPEN)
            if (current != request) return@launch
            val sessionId = canonical.sessionId
            when {
                canonical == parent.selection() -> setStack(base + stacked(ancestors), true)
                canonical.routeId == parent.routeId &&
                    canonical.projectId == parent.projectId &&
                    sessionId != null -> {
                    val chat = RemoteNavKey.Chat(parent.routeId, parent.projectId, sessionId)
                    setStack(base + stacked(ancestors + chat), true)
                }
                canonical == RemoteSelection(parent.routeId, parent.projectId) -> {
                    // The child is unknown to the host's session list: stay in the parent chat
                    // and say so instead of dropping the user onto the Sessions list.
                    setStack(base + stacked(ancestors), true)
                    val restored = repository.activate(parent.selection(), ActivationMode.RESTORE)
                    if (current != request) return@launch
                    if (restored != parent.selection()) replace(restored)
                    repository.reportError(R.string.remote_insights_child_not_found)
                }
                else -> replace(canonical)
            }
        }
    }

    fun back() {
        if (stack.size <= 1) return
        if (stack.last() == RemoteNavKey.Settings) {
            stack.removeAt(stack.lastIndex)
            return
        }
        val top = stack.last()
        val folders = repository.state.value.folders
        if (top is RemoteNavKey.FolderBrowser && folders.routeId == top.routeId && folders.path.isNotEmpty()) {
            repository.browseFolder(top.routeId, parentFolderPath(folders.path))
            return
        }
        stack.removeAt(stack.lastIndex)
        activate(stack.last() as RemoteNavKey, ActivationMode.RESTORE)
    }

    fun settings() {
        if (stack.lastOrNull() != RemoteNavKey.Settings) stack.add(RemoteNavKey.Settings)
    }

    fun createSession(routeId: String, projectId: String) {
        val current = invalidate()
        activation = scope.launch {
            val selection = repository.createSession(routeId, projectId)
            if (current == request) replace(selection, keepSettings = false)
        }
    }

    /**
     * Forks the chat [source] at the user message [messageId]. The fork replaces [source] on the
     * stack, so Back leads to the session list, as after [createSession].
     */
    fun forkSession(source: RemoteNavKey.Chat, messageId: String, mode: ForkMode) {
        if (topKey() != source) return
        val current = invalidate()
        activation = scope.launch {
            val selection = repository.forkSession(source.selection(), messageId, mode)
            if (current == request) replace(selection, keepSettings = false)
        }
    }

    /** Opens the folder browser at the browse root, on top of the host's Projects. */
    fun openFolders(routeId: String) {
        if (topKey() != RemoteNavKey.Projects(routeId)) return
        repository.cancelFolderTrust()
        repository.dismissFolderNotice()
        repository.browseFolder(routeId, "")
        stack.add(RemoteNavKey.FolderBrowser(routeId))
    }

    /** Opens the providers of [routeId] on top of the host's Projects. */
    fun openProviders(routeId: String) {
        if (topKey() != RemoteNavKey.Projects(routeId)) return
        repository.dismissProviderNotice()
        repository.browseProviders(routeId)
        stack.add(RemoteNavKey.Providers(routeId))
    }

    /** Shows [path] in the open folder browser; the breadcrumb jumps here. */
    fun browseFolder(routeId: String, path: String) {
        if (topKey() == RemoteNavKey.FolderBrowser(routeId)) repository.browseFolder(routeId, path)
    }

    /**
     * Opens [path] as a project and goes straight into the new session's chat, like
     * [createSession]. Without trust the repository may ask for it first and nothing moves.
     */
    fun openFolder(routeId: String, path: String, confirmed: FolderTrustPrompt? = null) {
        if (topKey() != RemoteNavKey.FolderBrowser(routeId)) return
        // Synchronous, so a double tap sends one project.open.
        val openId = repository.beginOpenFolder(routeId, path, confirmed) ?: return
        val current = invalidate()
        activation = scope.launch {
            val selection = repository.openFolder(routeId, path, openId)
            if (current == request && selection != null) replace(selection, keepSettings = false)
        }.also { job ->
            // A job cancelled before it first ran never reaches openFolder's cleanup.
            job.invokeOnCompletion { repository.endOpenFolder(openId) }
        }
    }

    fun pair(text: String) {
        val current = invalidate()
        activation = scope.launch {
            val selection = repository.pair(text)
            if (current == request && selection != null) replace(selection, keepSettings = false)
            if (selection != null) onPaired()
        }
    }

    /**
     * Opens the session of a tapped notification. With [jobs] it then opens that session's
     * background jobs, and [jobId]'s output on top when given.
     */
    fun notification(
        routeId: String,
        sessionId: String,
        jobs: Boolean = false,
        jobId: String? = null,
        consumed: () -> Unit = {},
    ) {
        if (
            routeId.isBlank() ||
                routeId.length > 256 ||
                sessionId.isBlank() ||
                sessionId.length > 256
        ) {
            consumed()
            return
        }
        val current = invalidate()
        var didConsume = false
        val consumeOnce = {
            if (!didConsume) {
                didConsume = true
                consumed()
            }
        }
        consumePendingNotification = consumeOnce
        val operation = scope.launch {
            val selection = repository.openNotification(routeId, sessionId)
            if (current == request) {
                if (selection != null) replace(selection, keepSettings = false) else restore()
                // Only once the session itself is open, since the jobs belong to the selection.
                if (
                    jobs &&
                        selection != null &&
                        selection.routeId == routeId &&
                        selection.sessionId == sessionId &&
                        repository.state.value.selection == selection
                ) {
                    repository.openJobs()
                    // Nullable on purpose: the strict push-id check, not the looser String one.
                    jobId.takeIf(::isOpaqueId)?.let(repository::openJob)
                }
            }
        }
        activation = operation
        operation.invokeOnCompletion { consumeOnce() }
    }

    /**
     * Runs a two-pane keyboard shortcut. Only the session list and its chat react; hosts,
     * projects, folders and settings leave the keys alone.
     */
    fun shortcut(shortcut: KeyboardShortcut, twoPane: TwoPaneState): Boolean {
        val top = stack.lastOrNull()
        val sessions =
            when (top) {
                is RemoteNavKey.Chat -> RemoteNavKey.Sessions(top.routeId, top.projectId)
                is RemoteNavKey.Sessions -> top
                else -> return false
            }
        when (shortcut) {
            KeyboardShortcut.SEARCH -> twoPane.requestSearch()
            KeyboardShortcut.PREVIOUS_SESSION,
            KeyboardShortcut.NEXT_SESSION -> {
                val step = if (shortcut == KeyboardShortcut.NEXT_SESSION) 1 else -1
                val current = (top as? RemoteNavKey.Chat)?.sessionId
                adjacentSession(twoPane.visibleSessionIds, current, step)
                    ?.takeIf { it != current }
                    ?.let { open(RemoteNavKey.Chat(sessions.routeId, sessions.projectId, it)) }
            }
        }
        return true
    }

    fun disconnect() {
        invalidate()
        repository.disconnect()
        replace(RemoteSelection(), keepSettings = false)
    }
}

/** Most chats kept stacked on one back stack (a parent chain ending in the open child). */
private const val MAX_STACKED_CHATS = 4

internal data class RemoteNotification(
    val routeId: String,
    val sessionId: String,
    val delivery: Long,
    /** Opens the session's background jobs, and [jobId] among them when given. */
    val jobs: Boolean = false,
    val jobId: String? = null,
)

// Parent and child screens share a depth axis.
private fun depthTransition(forward: Boolean): ContentTransform =
    (fadeIn(animationSpec = tween(140)) +
        scaleIn(
            initialScale = if (forward) 0.96f else 1.04f,
            animationSpec = tween(180, easing = FastOutSlowInEasing),
        )) togetherWith
        (fadeOut(animationSpec = tween(140)) +
            scaleOut(
                targetScale = if (forward) 1.04f else 0.96f,
                animationSpec = tween(180, easing = FastOutSlowInEasing),
            ))

// Settings has no parent-child relationship with the current destination.
private fun settingsTransition(): ContentTransform =
    (fadeIn(animationSpec = tween(140)) +
        scaleIn(initialScale = 0.96f, animationSpec = tween(180, easing = FastOutSlowInEasing)))
        .togetherWith(fadeOut(animationSpec = tween(140)))

@Composable
internal fun RemoteNavigation(
    repository: RemoteRepository,
    settings: SettingsRepository,
    pushConfigured: Boolean,
    enablePush: () -> Unit,
    notification: RemoteNotification? = null,
    consumeNotification: (RemoteNotification) -> Unit = {},
    disablePush: () -> Unit = {},
) {
    val pushSettings by settings.state.collectAsStateWithLifecycle()
    val stack = rememberNavBackStack(RemoteNavKey.Hosts)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var askForNotifications by rememberSaveable { mutableStateOf(false) }
    val navigator =
        remember(repository, stack) {
            RemoteNavigator(repository, stack, scope) {
                val granted =
                    Build.VERSION.SDK_INT < 33 ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                if (
                    offerNotificationPermission(
                        Build.VERSION.SDK_INT,
                        granted,
                        pushConfigured,
                        settings,
                    )
                )
                    askForNotifications = true
            }
        }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) enablePush()
        }
    if (askForNotifications) {
        AlertDialog(
            onDismissRequest = { askForNotifications = false },
            title = { Text(stringResource(R.string.remote_notification_prompt_title)) },
            text = { Text(stringResource(R.string.remote_notification_prompt_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askForNotifications = false
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                ) {
                    Text(stringResource(R.string.remote_notification_prompt_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { askForNotifications = false }) {
                    Text(stringResource(R.string.remote_notification_prompt_later))
                }
            },
        )
    }
    val timelineVisibility = rememberSaveable(saver = TimelineVisibility.Saver) { TimelineVisibility() }
    val chatViewports = rememberSaveable(saver = ChatViewportPositions.Saver) { ChatViewportPositions() }
    LaunchedEffect(navigator) {
        navigator.restore()
        repository.state.collect { navigator.reconcile(it) }
    }
    LaunchedEffect(notification, navigator) {
        notification?.let {
            navigator.notification(it.routeId, it.sessionId, it.jobs, it.jobId) {
                consumeNotification(it)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Recomputed on every size change: rotation, split-screen resizes and DeX windows.
        val layout = paneLayout(maxWidth)
        val twoPane = rememberSaveable(saver = TwoPaneState.Saver) { TwoPaneState() }
        val hardwareKeyboard = hardwareKeyboardAttached()
        val shortcuts = layout.twoPane && hardwareKeyboard
        val collapsed = twoPane.listCollapsed
        val sceneStrategies =
            remember(layout, twoPane, collapsed) {
                if (layout.twoPane)
                    listOf<SceneStrategy<NavKey>>(
                        ListDetailSceneStrategy(layout, twoPane, collapsed),
                        SinglePaneSceneStrategy(),
                    )
                else listOf<SceneStrategy<NavKey>>(SinglePaneSceneStrategy())
            }
        // Only a change of layout bucket rebuilds this; resize ticks within one keep it.
        val onBack =
            remember(layout, twoPane, navigator, stack) {
                {
                    when (twoPaneBackStep(layout, twoPane.listCollapsed, stack.lastOrNull())) {
                        BackStep.EXPAND_LIST -> twoPane.listCollapsed = false
                        BackStep.NAVIGATE -> navigator.back()
                    }
                }
            }
        // With nothing focused, Compose sees no key events at all; the window then reports them
        // as unhandled. Shortcuts run from there without moving focus, so TalkBack's focus stays
        // where it is, and an open dialog (its own window) keeps every key to itself.
        val view = LocalView.current
        if (shortcuts)
            DisposableEffect(view, navigator, twoPane) {
                val listener =
                    ViewCompat.OnUnhandledKeyEventListenerCompat { _, event ->
                        navigator.shortcut(KeyEvent(event), twoPane)
                    }
                ViewCompat.addOnUnhandledKeyEventListener(view, listener)
                onDispose { ViewCompat.removeOnUnhandledKeyEventListener(view, listener) }
            }
        CompositionLocalProvider(
            LocalChatViewportPositions provides chatViewports,
            LocalPaneLayout provides layout,
            LocalTwoPane provides twoPane.takeIf { layout.twoPane },
            LocalHardwareKeyboard provides hardwareKeyboard,
        ) {
            Box(
                // While something in the layout has focus (the composer, the search field), the
                // shortcuts run here, before that field sees the keys.
                if (shortcuts) Modifier.fillMaxSize().onPreviewKeyEvent { navigator.shortcut(it, twoPane) }
                else Modifier.fillMaxSize()
            ) {
                RemoteNavDisplay(
                    stack,
                    navigator,
                    onBack,
                    sceneStrategies,
                    repository,
                    settings,
                    pushConfigured,
                    pushSettings.pushEnabled,
                    enablePush,
                    timelineVisibility,
                    disablePush,
                )
            }
        }
    }
}

/** Runs a hardware keyboard shortcut of the two-pane layout; false leaves the key alone. */
private fun RemoteNavigator.shortcut(event: KeyEvent, twoPane: TwoPaneState): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val shortcut =
        keyboardShortcut(event.key, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed)
            ?: return false
    return shortcut(shortcut, twoPane)
}

@Composable
private fun RemoteNavDisplay(
    stack: NavBackStack<NavKey>,
    navigator: RemoteNavigator,
    onBack: () -> Unit,
    sceneStrategies: List<SceneStrategy<NavKey>>,
    repository: RemoteRepository,
    settings: SettingsRepository,
    pushConfigured: Boolean,
    pushEnabled: Boolean,
    enablePush: () -> Unit,
    timelineVisibility: TimelineVisibility,
    disablePush: () -> Unit,
) {
    NavDisplay(
        backStack = stack,
        onBack = onBack,
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        sceneStrategies = sceneStrategies,
        entryProvider =
            entryProvider {
                entry<RemoteNavKey.Hosts> { key ->
                    val model = viewModel { HostsViewModel(repository) }
                    RemoteScreen(key, model, navigator, pushConfigured, pushEnabled, enablePush, timelineVisibility, disablePush)
                }
                entry<RemoteNavKey.Projects> { key ->
                    val model = viewModel { ProjectsViewModel(repository, key) }
                    RemoteScreen(key, model, navigator, pushConfigured, pushEnabled, enablePush, timelineVisibility, disablePush)
                }
                entry<RemoteNavKey.Sessions>(metadata = { key -> navKeyMetadata(key) }) { key ->
                    val model = viewModel { SessionsViewModel(repository, key, settings) }
                    RemoteScreen(key, model, navigator, pushConfigured, pushEnabled, enablePush, timelineVisibility, disablePush)
                }
                entry<RemoteNavKey.FolderBrowser> { key ->
                    val model = viewModel { FolderBrowserViewModel(repository, key) }
                    FolderBrowserScreen(key, model, navigator)
                }
                entry<RemoteNavKey.Providers> { key ->
                    val model = viewModel { ProvidersViewModel(repository, key) }
                    ProvidersScreen(key, model, navigator)
                }
                entry<RemoteNavKey.Chat>(metadata = { key -> navKeyMetadata(key) }) { key ->
                    val model = viewModel { ChatViewModel(repository, key, settings) }
                    RemoteScreen(key, model, navigator, pushConfigured, pushEnabled, enablePush, timelineVisibility, disablePush)
                }
                entry<RemoteNavKey.Settings>(
                    metadata = metadata {
                        put(NavDisplay.TransitionKey) { settingsTransition() }
                        put(NavDisplay.PopTransitionKey) { settingsTransition() }
                        put(NavDisplay.PredictivePopTransitionKey) { settingsTransition() }
                    },
                ) { key ->
                    val model = viewModel { SettingsViewModel(repository, settings) }
                    RemoteScreen(key, model, navigator, pushConfigured, pushEnabled, enablePush, timelineVisibility, disablePush)
                }
            },
        transitionSpec = { depthTransition(forward = true) },
        popTransitionSpec = { depthTransition(forward = false) },
        predictivePopTransitionSpec = { depthTransition(forward = false) },
    )
}
