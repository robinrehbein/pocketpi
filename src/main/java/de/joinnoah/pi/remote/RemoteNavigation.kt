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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
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

    /**
     * The activity board of one host: its sessions that wait, run or finished since they were last
     * looked at (see [ActivityBoardScreen]). It opens on top of the open project or chat, which stay
     * beneath it, so the wide layout can show the board where the session list is.
     */
    @Serializable data class Activity(val routeId: String) : RemoteNavKey
}

internal fun RemoteNavKey.selection(): RemoteSelection =
    when (this) {
        RemoteNavKey.Hosts,
        RemoteNavKey.Settings -> RemoteSelection()
        is RemoteNavKey.Projects -> RemoteSelection(routeId)
        is RemoteNavKey.FolderBrowser -> RemoteSelection(routeId)
        is RemoteNavKey.Providers -> RemoteSelection(routeId)
        is RemoteNavKey.Activity -> RemoteSelection(routeId)
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

/**
 * The Mac a cold launch opens: the stored one while it is still paired, else the only paired Mac,
 * else null, which keeps the hosts list. [hostRoutes] are the paired Macs' route IDs.
 */
internal fun coldStartRoute(hostRoutes: List<String>, stored: String?): String? =
    when {
        stored != null && stored in hostRoutes -> stored
        hostRoutes.size == 1 -> hostRoutes.single()
        else -> null
    }

internal class RemoteNavigator(
    private val repository: RemoteRepository,
    private val stack: MutableList<NavKey>,
    private val scope: CoroutineScope,
    /** Remembers the Mac of each page opened, for [coldStart]; null remembers nothing. */
    private val lastRoute: LastRouteStore? = null,
    /** Called after a pairing succeeded and its selection was opened. */
    private val onPaired: () -> Unit = {},
) {
    private var request = 0L
    private var activation: Job? = null
    private var mutation: Job? = null

    /** True while a create or fork request is in flight; further ones are ignored meanwhile. */
    var mutating by mutableStateOf(false)
        private set
    private var consumePendingNotification: (() -> Unit)? = null

    private fun invalidate(): Long {
        // Another navigation began before the created chat's screen took it.
        freshSession = null
        openingNotification = null
        forkLatestRequest = null
        consumePendingNotification?.invoke()
        consumePendingNotification = null
        // A share waiting for its chat goes with the chat it was meant for.
        shareDelivery?.cancel()
        shareDelivery = null
        activation?.cancel()
        repository.cancelSelection()
        return ++request
    }

    private fun setStack(
        target: List<RemoteNavKey>,
        keepSettings: Boolean,
        board: RemoteNavKey.Activity? = null,
    ) {
        val settings = keepSettings && stack.lastOrNull() == RemoteNavKey.Settings
        val keys = target + listOfNotNull(board) + if (settings) listOf(RemoteNavKey.Settings) else emptyList()
        // Keep equal entries so their ViewModel stores and saved UI state survive.
        val common = stack.zip(keys).takeWhile { (old, new) -> old == new }.size
        while (stack.size > common) stack.removeAt(stack.lastIndex)
        stack.addAll(keys.drop(common))
    }

    /** The chat on top of the stack, or null when another destination is (Settings included). */
    val topChat: RemoteNavKey.Chat? get() = stack.lastOrNull() as? RemoteNavKey.Chat

    private fun topKey(): RemoteNavKey? =
        stack.lastOrNull { it != RemoteNavKey.Settings } as? RemoteNavKey

    /**
     * The pages that open on top of the open project, session or chat without being part of its
     * selection: the activity board, Settings, and the folder browser and providers of a host.
     */
    private fun NavKey.isOverlay(): Boolean =
        this == RemoteNavKey.Settings ||
            this is RemoteNavKey.Activity ||
            this is RemoteNavKey.FolderBrowser ||
            this is RemoteNavKey.Providers

    /** The destination beneath the overlays, which the selection stays on. */
    private fun contentKey(): RemoteNavKey? = stack.lastOrNull { !it.isOverlay() } as? RemoteNavKey

    /** Chat entries stacked directly beneath the top chat in the same project, oldest first. */
    private fun chatsBeneathTop(): List<RemoteNavKey.Chat> {
        val entries = stack.filter { !it.isOverlay() }
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
        keepActivity: Boolean = false,
        keepFolders: Boolean = false,
    ) {
        val keys = selection.keys()
        // The activity board stays on top of the content it was opened over, across a restore and
        // a reconcile, while the same host is open. A navigation to a destination closes it.
        val board = (topKey() as? RemoteNavKey.Activity)?.takeIf { keepActivity && it.routeId == selection.routeId }
        // The folder browser and the providers stay on top of the host's content across a restore
        // and a reconcile, so an OAuth return or a reconnect does not close them.
        val folders = topKey()?.takeIf { it is RemoteNavKey.FolderBrowser || it is RemoteNavKey.Providers }
        if (keepFolders && folders != null && selection.routeId == folders.selection().routeId) {
            setStack(keys + folders, keepSettings)
            return
        }
        val chat = keys.lastOrNull() as? RemoteNavKey.Chat
        // A child chat opened on top of its parent keeps the parent beneath it across
        // restores and reconnects.
        val beneath =
            if (keepStackedChats && chat != null && contentKey() == chat) chatsBeneathTop()
            else emptyList()
        if (chat == null || beneath.isEmpty()) {
            setStack(keys, keepSettings, board)
        } else {
            setStack(sessionKeys(chat) + stacked(beneath + chat), keepSettings, board)
        }
    }

    private fun activate(key: RemoteNavKey, mode: ActivationMode) {
        val current = invalidate()
        activation = scope.launch {
            val canonical = repository.activate(key.selection(), mode)
            if (current != request) return@launch
            // The Mac of a page the user is on is the one a cold launch opens again (see coldStart).
            if (key is RemoteNavKey.Projects || key is RemoteNavKey.Sessions || key is RemoteNavKey.Chat)
                canonical.routeId?.let { lastRoute?.save(it, used = mode == ActivationMode.USER_OPEN) }
            val chat = canonical.keys().lastOrNull() as? RemoteNavKey.Chat
            // The restored chat's own session is gone (offline, or otherwise dropped by the host).
            // Walk the stacked ancestors closest first, actually re-activating each through the
            // repository directly (never through this function, which would reset invalidate()'s
            // request and race a later navigation) so its own selection and loaded state match,
            // and land on the first one that is still live. An ancestor that is itself gone is
            // skipped in favor of the next one further out; only when none of them resolve does
            // the whole stack collapse to the sessions list underneath it.
            if (mode == ActivationMode.RESTORE && chat == null && key is RemoteNavKey.Chat && contentKey() == key) {
                val ancestors = chatsBeneathTop()
                val board = topKey() as? RemoteNavKey.Activity
                val folders = topKey()?.takeIf { it is RemoteNavKey.FolderBrowser || it is RemoteNavKey.Providers }
                for (index in ancestors.indices.reversed()) {
                    val candidate = ancestors[index]
                    val restored = repository.activate(candidate.selection(), ActivationMode.RESTORE)
                    if (current != request) return@launch
                    if (restored == candidate.selection()) {
                        setStack(
                            sessionKeys(candidate) + stacked(ancestors.subList(0, index + 1)) +
                                listOfNotNull(folders?.takeIf { it.selection().routeId == candidate.routeId }),
                            keepSettings = true,
                            board = board?.takeIf { it.routeId == candidate.routeId },
                        )
                        return@launch
                    }
                }
            }
            replace(canonical, keepActivity = mode == ActivationMode.RESTORE, keepFolders = mode == ActivationMode.RESTORE)
        }
    }

    fun reconcile(state: RemoteState) {
        // A prompt is about the editor of the session it was asked in; another session ends it.
        editorLeave?.let { held ->
            if (held.routeId != state.selection.routeId || held.sessionId != state.selection.sessionId) hold(null)
        }
        if (state.loading || activation?.isActive == true) return
        val current = contentKey() ?: return
        val keys = current.selection().keys()
        val validated = state.selection.keys()
        if (validated.size < keys.size && keys.take(validated.size) == validated) {
            replace(state.selection, keepActivity = true, keepFolders = true)
        }
    }

    fun restore() {
        // The board is an overlay: the content beneath it is what gets restored, and the board
        // goes back on top of it.
        activate(contentKey() ?: RemoteNavKey.Hosts, ActivationMode.RESTORE)
    }

    /**
     * Starts a cold launch once the paired Macs are read ([RemoteState.hostsLoaded]): the stack
     * opens the Mac [coldStartRoute] picks on its Projects, above Hosts, so back still reaches the
     * hosts list. A stored Mac that is no longer paired is forgotten, and only a read list says so.
     * Returns false while the Macs are not read, so the caller asks again with the next state, and
     * true once the start is decided, also when the user has navigated away from the hosts list.
     */
    fun coldStart(state: RemoteState): Boolean {
        if (!state.hostsLoaded) return false
        forgetGoneHosts(state)
        if (stack.singleOrNull() != RemoteNavKey.Hosts) return true
        val routes = state.hosts.map { it.routeId }
        val stored = lastRoute?.load()
        val route = coldStartRoute(routes, stored) ?: return true
        replace(RemoteSelection(route))
        activate(RemoteNavKey.Projects(route), ActivationMode.RESTORE)
        return true
    }

    /** Forgets [routeId] as the Mac a cold launch opens, once its pairing is removed. */
    fun forgetLastRoute(routeId: String) {
        lastRoute?.forget(routeId)
    }

    /** The paired Macs the list was last checked against, so each new list is checked once. */
    private var checkedRoutes: List<String>? = null

    /**
     * Forgets what is kept for Macs whose pairing is gone (see [LastRouteStore.prune]). Only a read list
     * says so, so nothing happens until [RemoteState.hostsLoaded] is true.
     */
    fun forgetGoneHosts(state: RemoteState) {
        if (!state.hostsLoaded) return
        val routes = state.hosts.map { it.routeId }
        if (routes == checkedRoutes) return
        checkedRoutes = routes
        lastRoute?.prune(routes)
    }

    /** When the Mac was last opened, for its host card; null when it never was. */
    fun lastUsedAt(routeId: String): Long? = lastRoute?.lastUsedAt(routeId)

    /** Whether "Not now" was pressed on the Mac's notification card. */
    fun notificationCardDismissed(routeId: String): Boolean = lastRoute?.notificationCardDismissed(routeId) ?: false

    fun dismissNotificationCard(routeId: String) {
        lastRoute?.dismissNotificationCard(routeId)
    }

    fun open(key: RemoteNavKey) {
        // While a share waits for its session, the chat tapped is that session.
        if (key is RemoteNavKey.Chat && completeSharePick(ShareTarget.Session(key.routeId, key.projectId, key.sessionId))) return
        leavingEditor(key.selection()) { openNow(key) }
    }

    private fun openNow(key: RemoteNavKey) {
        replace(key.selection(), keepSettings = false, keepStackedChats = false)
        activate(key, ActivationMode.USER_OPEN)
    }

    /**
     * Opens the chat [key] like [open], and its rewind menu on the latest message once the chat
     * has loaded: a session row's "Rewind from the latest message".
     */
    fun openAndForkLatest(key: RemoteNavKey.Chat) =
        leavingEditor(key.selection()) {
            replace(key.selection(), keepSettings = false, keepStackedChats = false)
            activate(key, ActivationMode.USER_OPEN)
            forkLatestRequest = key
        }

    /** The chat [openAndForkLatest] opened, until its screen took it ([takeForkLatest]). */
    var forkLatestRequest: RemoteNavKey.Chat? by mutableStateOf(null)
        private set

    /** True once, for the chat whose rewind menu [openAndForkLatest] asked for. */
    fun takeForkLatest(key: RemoteNavKey.Chat): Boolean {
        if (forkLatestRequest != key) return false
        forkLatestRequest = null
        return true
    }

    /** Where keyboard focus is relative to the code editor; the editor reports it. */
    val editorFocus = EditorFocus()

    /**
     * Keyboard focus is in the session's code editor, whose keys then win over the layout's
     * shortcuts. With focus elsewhere (the composer, the session list) the shortcuts work, and
     * those that navigate ask before they drop an unsaved edit.
     */
    val editing: Boolean
        get() = editorFocus.focused && repository.state.value.let { state ->
            state.files?.takeIf { it.sessionId == state.selection.sessionId }?.editor != null
        }

    /**
     * A navigation held back because it would drop the editor's unsaved edit; the layout asks,
     * then [leaveEditor] runs it or [stayInEditor] drops it.
     */
    var editorLeave: HeldNavigation? by mutableStateOf(null)
        private set

    /**
     * Shows [held]'s prompt; a navigation that was waiting and is not [run] was dropped and says
     * so, [kept] when the user chose to stay in the editor.
     */
    private fun hold(held: HeldNavigation?, run: Boolean = false, kept: Boolean = false) {
        val previous = editorLeave
        editorLeave = held
        editorFocus.asking = held != null
        if (!run && previous !== held) previous?.onDropped?.invoke(kept)
    }

    /**
     * Runs [action] now, or holds it for the discard prompt when it moves to another session than
     * [target] while the editor has unsaved changes. Navigation nobody chose here (a
     * notification, a restore) does not ask; the repository parks the edit for its session then.
     */
    private fun leavingEditor(target: RemoteSelection, onDropped: ((kept: Boolean) -> Unit)? = null, action: () -> Unit) {
        val state = repository.state.value
        val editor = state.files?.takeIf { it.sessionId == state.selection.sessionId }?.editor
        val leaves = target.routeId != state.selection.routeId || target.sessionId != state.selection.sessionId
        if (editor != null && leaves && editor.changed)
            hold(HeldNavigation(checkNotNull(state.selection.routeId), editor.path, state.selection.sessionId, action, onDropped))
        else action()
    }

    /**
     * Discards the unsaved edit and runs the navigation that waited for it. When the editor the
     * prompt was about is no longer the one shown, nothing is discarded and the navigation is dropped.
     */
    fun leaveEditor() {
        val held = editorLeave ?: return
        hold(null, run = true)
        val state = repository.state.value
        val editor = state.files?.takeIf { it.sessionId == state.selection.sessionId }?.editor
        if (state.selection.routeId != held.routeId || state.selection.sessionId != held.sessionId || editor?.path != held.path) {
            held.onDropped?.invoke(false)
            return
        }
        repository.discardFileEditor()
        held.action()
    }

    fun stayInEditor() {
        hold(null, kept = true)
    }

    /**
     * Opens [child] on top of [parent] so back returns to the parent chat. Only the selection
     * survives process death, so a restored child comes back without its parent.
     */
    fun openChild(parent: RemoteNavKey.Chat, child: RemoteNavKey.Chat) = leavingEditor(child.selection()) { openChildNow(parent, child) }

    private fun openChildNow(parent: RemoteNavKey.Chat, child: RemoteNavKey.Chat) {
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

    /**
     * Runs a create or fork on the host. A second request while one is in flight is ignored:
     * invalidate() would cancel the first coroutine after the host may already have created its
     * session, leaving an orphan. Other navigation still cancels it, which also releases the guard.
     */
    private fun mutate(fresh: Boolean = false, call: suspend () -> RemoteSelection) {
        if (mutation?.isActive == true) return
        val current = invalidate()
        val job = scope.launch {
            val selection = call()
            if (current == request) {
                replace(selection, keepSettings = false)
                if (fresh) freshSession = selection.keys().lastOrNull() as? RemoteNavKey.Chat
            }
        }
        activation = job
        mutation = job
        mutating = true
        job.invokeOnCompletion { if (mutation === job) mutating = false }
    }

    fun createSession(routeId: String, projectId: String) {
        if (completeSharePick(ShareTarget.NewSession(routeId, projectId))) return
        leavingEditor(RemoteSelection(routeId, projectId)) {
            mutate(fresh = true) { repository.createSession(routeId, projectId) }
        }
    }

    /**
     * The chat [createSession] just opened, until its screen took it ([takeFreshSession]) to focus
     * the composer once. Forks and reopened chats never set it.
     */
    var freshSession: RemoteNavKey.Chat? by mutableStateOf(null)
        private set

    /** True once, for the chat [key] a create just opened. */
    fun takeFreshSession(key: RemoteNavKey.Chat): Boolean {
        if (freshSession != key) return false
        freshSession = null
        return true
    }

    /**
     * Forks the chat [source] at the user message [messageId]. The fork replaces [source] on the
     * stack, so Back leads to the session list, as after [createSession].
     */
    fun forkSession(source: RemoteNavKey.Chat, messageId: String, mode: ForkMode) {
        if (topKey() != source) return
        leavingEditor(RemoteSelection(source.routeId, source.projectId)) {
            mutate { repository.forkSession(source.selection(), messageId, mode) }
        }
    }

    /**
     * Forks the chat [source] at the tree node [nodeId]. Like [forkSession], the fork replaces
     * [source] on the stack.
     */
    fun forkSessionAtNode(source: RemoteNavKey.Chat, nodeId: String) {
        if (topKey() != source) return
        leavingEditor(RemoteSelection(source.routeId, source.projectId)) {
            mutate { repository.forkSessionAtNode(source.selection(), nodeId) }
        }
    }

    /**
     * Pushes [page], a folder browser or the providers of [routeId], on top of the open project,
     * session or chat of that host. Those stay beneath it, so Back returns to them, and their
     * selection is not touched. False when no destination of [routeId] is open, Settings is on top
     * or [page] is open already.
     */
    private fun pushOverHost(routeId: String, page: RemoteNavKey): Boolean {
        if (stack.lastOrNull() == RemoteNavKey.Settings) return false
        val top = topKey() ?: return false
        if (top is RemoteNavKey.FolderBrowser || top is RemoteNavKey.Providers) return false
        if (top.selection().routeId != routeId) return false
        stack.add(page)
        return true
    }

    /** Opens the folder browser at the browse root, on top of the host's open destination. */
    fun openFolders(routeId: String) {
        if (!pushOverHost(routeId, RemoteNavKey.FolderBrowser(routeId))) return
        repository.cancelFolderTrust()
        repository.dismissFolderNotice()
        repository.browseFolder(routeId, "")
    }

    /**
     * Leaves the folder browser of [routeId] in one step, whatever folder it shows: the page beneath
     * it comes back, the open chat or project with it.
     */
    fun closeFolders(routeId: String) {
        val index = stack.indexOf(RemoteNavKey.FolderBrowser(routeId))
        if (index < 0) return
        repository.cancelFolderTrust()
        repository.dismissFolderNotice()
        while (stack.size > index) stack.removeAt(stack.lastIndex)
        activate(stack.last() as RemoteNavKey, ActivationMode.RESTORE)
    }

    /** Opens the providers of [routeId] on top of the host's open destination. */
    fun openProviders(routeId: String) {
        if (!pushOverHost(routeId, RemoteNavKey.Providers(routeId))) return
        repository.dismissProviderNotice()
        repository.browseProviders(routeId)
    }

    /**
     * Opens the activity board of [routeId] on top of the open project, session or chat, which stay
     * beneath it. Nothing happens for another host or on the Hosts page. A card opens its chat with
     * [open], which replaces the board.
     */
    fun openActivity(routeId: String) {
        // Settings stays on top of whatever is beneath it, like for the other pages.
        if (stack.lastOrNull() == RemoteNavKey.Settings) return
        val top = topKey()
        if (top == RemoteNavKey.Activity(routeId) || top?.selection()?.routeId != routeId) return
        stack.add(RemoteNavKey.Activity(routeId))
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
     * The session a tapped notification is opening while its project is still unknown here; the
     * layout shows that it is opening over the previous screen until the Mac said where it is.
     */
    var openingNotification: Pair<String, String>? by mutableStateOf(null)
        private set

    /**
     * Opens the session of a tapped notification. With [jobs] it then opens that session's
     * background jobs, and [jobId]'s output on top when given. When the app already knows the
     * session's project, its chat is on screen at once (cached content, read-only, while it opens);
     * otherwise [openingNotification] says so until the Mac has been asked.
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
            // What was on screen before the chat was pushed early; a failed open returns to it.
            var before: List<RemoteNavKey>? = null
            val projectId = repository.notificationProject(routeId, sessionId)
            if (current != request) return@launch
            if (projectId != null) {
                before = stack.filterIsInstance<RemoteNavKey>()
                replace(RemoteSelection(routeId, projectId, sessionId), keepSettings = false, keepStackedChats = false)
            } else openingNotification = routeId to sessionId
            val selection = repository.openNotification(routeId, sessionId)
            if (current == request) {
                openingNotification = null
                if (selection != null) replace(selection, keepSettings = false)
                else {
                    before?.let { setStack(it, keepSettings = false) }
                    restore()
                }
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
     * A share from another app waiting for the user to choose its session ([ShareTarget.Pick]).
     * The layout shows a banner meanwhile; the next chat opened or session created takes it.
     */
    var sharePick: ShareRequest? by mutableStateOf(null)
        private set

    /** Consumes the waiting [sharePick] in the activity, once it went somewhere or was cancelled. */
    private var shareDone: (() -> Unit)? = null

    /** Hands a share to its chat once that is open ([deliverShare]); a navigation cancels it. */
    private var shareDelivery: Job? = null

    /**
     * Takes a share from another app (ShareActivity) to its target: opens the session, or creates
     * one, and puts the content into its draft once the chat can take it. [ShareTarget.Pick]
     * waits for the user's choice in [sharePick]. [consumed] runs once the share no longer needs
     * the activity to keep it: at once for a session, and when the choice was made or cancelled.
     */
    fun share(request: ShareRequest, consumed: () -> Unit = {}) {
        endShare()
        if (request.target == ShareTarget.Pick) {
            sharePick = request
            shareDone = consumed
            // A chat on top would hide the sessions to choose from.
            topChat?.let { open(RemoteNavKey.Sessions(it.routeId, it.projectId)) }
            return
        }
        consumed()
        deliver(request.content, request.target)
    }

    /** Drops the share waiting for a choice; the banner's Cancel, or Back where it would leave the app. */
    fun cancelShare() = endShare()

    /** Back would leave the app; with a share waiting for its session it cancels the share instead. */
    val backCancelsShare: Boolean
        get() = sharePick != null && stack.size <= 1

    private fun endShare() {
        shareDelivery?.cancel()
        shareDelivery = null
        sharePick = null
        shareDone?.also { shareDone = null }?.invoke()
    }

    private fun completeSharePick(target: ShareTarget): Boolean {
        val request = sharePick ?: return false
        sharePick = null
        shareDone?.also { shareDone = null }?.invoke()
        deliver(request.content, target)
        return true
    }

    /** A share that waited for the editor's discard prompt and was dropped there says so. */
    private val shareDropped: (Boolean) -> Unit = { kept ->
        repository.reportError(if (kept) R.string.remote_share_editor_kept else R.string.remote_share_dropped)
    }

    private fun deliver(content: SharedContent, target: ShareTarget) {
        when (target) {
            is ShareTarget.Session ->
                leavingEditor(RemoteSelection(target.routeId, target.projectId, target.sessionId), shareDropped) {
                    val project = target.projectId
                    if (project != null) {
                        openNow(RemoteNavKey.Chat(target.routeId, project, target.sessionId))
                        follow(content) { it.routeId == target.routeId && it.projectId == project }
                    } else shareByLookup(content, target.routeId, target.sessionId)
                }
            is ShareTarget.NewSession ->
                leavingEditor(RemoteSelection(target.routeId, target.projectId), shareDropped) {
                    if (mutation?.isActive == true) {
                        repository.reportError(R.string.remote_share_failed)
                        return@leavingEditor
                    }
                    mutate(fresh = true) { repository.createSession(target.routeId, target.projectId) }
                    follow(content) { it.routeId == target.routeId && it.projectId == target.projectId }
                }
            ShareTarget.Pick -> Unit
        }
    }

    /**
     * Only the route and session are known (a Direct Share target): finds the session's project
     * like [notification] does, then hands [content] to it. A session that is gone says so and
     * leaves the app where it was.
     */
    private fun shareByLookup(content: SharedContent, routeId: String, sessionId: String) {
        val current = invalidate()
        activation = scope.launch {
            val selection =
                try {
                    repository.openNotification(routeId, sessionId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    repository.reportError(R.string.remote_share_dropped)
                    throw e
                }
            if (current != request) return@launch
            if (selection == null) {
                repository.reportError(R.string.remote_share_failed)
                restore()
                return@launch
            }
            replace(selection, keepSettings = false)
            if (selection.routeId != routeId || selection.sessionId != sessionId) {
                repository.reportError(R.string.remote_share_failed)
                return@launch
            }
            // Its own job, so the open is over for reconcile() while the chat connects.
            shareDelivery = scope.launch {
                if (!deliverShare(repository, content, selection)) repository.reportError(R.string.remote_share_failed)
            }
        }
    }

    /** After the navigation just started: hands [content] to the chat it lands on, if that [matches]. */
    private fun follow(content: SharedContent, matches: (RemoteSelection) -> Boolean) {
        val opening = activation
        shareDelivery = scope.launch {
            try {
                opening?.join()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Another navigation came first; nothing of the share reached its chat.
                repository.reportError(R.string.remote_share_dropped)
                throw e
            }
            val chat = repository.state.value.selection.takeIf { it.sessionId != null && matches(it) }
            if (chat == null || !deliverShare(repository, content, chat)) repository.reportError(R.string.remote_share_failed)
        }
    }

    /**
     * Runs a keyboard shortcut and reports whether it was handled, in either layout. The list and
     * project shortcuts work on the columns' destinations (a host's projects, a project's sessions
     * and a chat), the chat's own ones on the chat on top; hosts, folders and settings leave those
     * keys alone. [twoPane] is null in the phone layout. A [repeat] of a key that toggles, creates
     * or switches the project is swallowed only where its first press would apply. [unhandled]
     * is a key the focused element saw and left alone.
     */
    fun shortcut(
        shortcut: Shortcut,
        twoPane: TwoPaneState?,
        targets: ShortcutTargets,
        repeat: Boolean = false,
        unhandled: Boolean = false,
    ): Boolean {
        val top = stack.lastOrNull()
        when (shortcut) {
            Shortcut.SETTINGS -> {
                settings()
                return true
            }
            Shortcut.REFRESH -> {
                val refresh = (top as? RemoteNavKey)?.let(targets::refresher) ?: return false
                // Holding the key must not send a refresh per repeat.
                if (!repeat) refresh()
                return true
            }
            Shortcut.HELP -> {
                if (!repeat) targets.help = if (top is RemoteNavKey.Chat) KeyContext.CHAT else KeyContext.GLOBAL
                return true
            }
            Shortcut.ACTIVITY -> {
                val route = repository.state.value.selection.routeId ?: return false
                if (!repeat) openActivity(route)
                return true
            }
            // Holding the key must not reopen the palette over the one it opened.
            Shortcut.PALETTE -> {
                if (!repeat) targets.openPalette(PaletteMode.ALL)
                return true
            }
            Shortcut.NEXT_REGION,
            Shortcut.PREVIOUS_REGION -> {
                if (!repeat) targets.regions.cycle(if (shortcut == Shortcut.NEXT_REGION) 1 else -1)
                return true
            }
            // Held, the page keys page on. With focus in a list or the inspector, or a pane over
            // the chat, they stay there, which scrolls itself. A text field holding text gets
            // Ctrl+Home and Ctrl+End first, for its cursor; the chat takes them only once the
            // field left them [unhandled] (Compose's fields on Android do today).
            Shortcut.PAGE_UP,
            Shortcut.PAGE_DOWN,
            Shortcut.CHAT_START,
            Shortcut.CHAT_END -> {
                val target = (top as? RemoteNavKey.Chat)?.let(targets::chat) ?: return false
                val region = targets.regions.current
                if (target.composerCovered || (region != null && region != FocusRegion.CHAT)) return false
                if (shortcut == Shortcut.CHAT_START || shortcut == Shortcut.CHAT_END) {
                    if (target.editingText && !unhandled) return false
                    // Held, Ctrl+Home must not ask for one older page per repeat.
                    if (repeat) return true
                }
                return target.shortcut(shortcut)
            }
            Shortcut.FOCUS_COMPOSER,
            Shortcut.STOP,
            Shortcut.CHANGES,
            Shortcut.FILES,
            Shortcut.JOBS,
            Shortcut.HISTORY,
            Shortcut.EXPAND_INSPECTOR,
            Shortcut.TOGGLE_INSPECTOR,
            Shortcut.REVIEW,
            Shortcut.RENAME,
            Shortcut.FORK -> {
                val target = (top as? RemoteNavKey.Chat)?.let(targets::chat) ?: return false
                if (repeat) return true
                if (shortcut != Shortcut.STOP) return target.shortcut(shortcut)
                target.stopNow()
                return true
            }
            // Esc runs in the key handlers, Alt+Enter in the composer.
            Shortcut.ESCAPE,
            Shortcut.OTHER_BUSY_ACTION -> return false
            else -> Unit
        }
        val panes = columnPanes(stack.toList()) ?: return false
        if (shortcut == Shortcut.PREVIOUS_PROJECT || shortcut == Shortcut.NEXT_PROJECT) {
            val projects = panes.projects?.let { stack[it] as? RemoteNavKey.Projects } ?: return false
            // Holding the key must not run through every project.
            if (repeat) return true
            val state = repository.state.value
            if (state.selection.routeId != projects.routeId) return true
            val current = panes.list?.let { (stack[it] as? RemoteNavKey.Sessions)?.projectId }
            val step = if (shortcut == Shortcut.NEXT_PROJECT) 1 else -1
            adjacentId(state.projects.map { it.text("id") }, current, step)
                ?.takeIf { it != current }
                ?.let {
                    twoPane?.revealList()
                    open(RemoteNavKey.Sessions(projects.routeId, it))
                }
            return true
        }
        val sessions = panes.list?.let { stack[it] as? RemoteNavKey.Sessions } ?: return false
        return when (shortcut) {
            Shortcut.SEARCH -> {
                if (twoPane != null) twoPane.revealList()
                // The phone shows one destination: the sessions come to the top for their search.
                // A request waits for the list, so it is made only once the list is on top, never
                // for a navigation held for an unsaved edit.
                else if (top != sessions) {
                    if (repeat) return true
                    open(sessions)
                    if (stack.lastOrNull() != sessions) return true
                }
                targets.requestSearch()
                true
            }
            Shortcut.PREVIOUS_SESSION,
            Shortcut.NEXT_SESSION -> {
                val step = if (shortcut == Shortcut.NEXT_SESSION) 1 else -1
                val current = (top as? RemoteNavKey.Chat)?.sessionId
                adjacentId(listedSessionIds(sessions, targets), current, step)
                    ?.takeIf { it != current }
                    ?.let { open(RemoteNavKey.Chat(sessions.routeId, sessions.projectId, it)) }
                true
            }
            Shortcut.NEW_SESSION -> {
                if (!canCreateSession(repository.state.value, sessions)) return false
                // Holding the key must not create a session per repeat.
                if (repeat) return true
                createSession(sessions.routeId, sessions.projectId)
                true
            }
            Shortcut.TOGGLE_LIST -> {
                // The phone has no list beside the chat to toggle.
                if (top !is RemoteNavKey.Chat || twoPane == null) return false
                // Holding the key must not flicker the list.
                if (repeat) return true
                twoPane.listCollapsed = !twoPane.listCollapsed
                true
            }
            else -> false
        }
    }

    /**
     * The sessions of [sessions] in the list's order and filter. While the list is on screen that
     * is what it shows; without it (a phone's chat, a collapsed list) the project's current
     * sessions in list order, without offline ones when the setting hides them.
     */
    private fun listedSessionIds(sessions: RemoteNavKey.Sessions, targets: ShortcutTargets): List<String> {
        if (targets.listedFor == sessions) return targets.listedSessionIds
        val state = repository.state.value
        if (state.selection.routeId != sessions.routeId || state.selection.projectId != sessions.projectId) return emptyList()
        return visibleSessionListItems(
            state.sessions,
            state.connected,
            state.loading,
            hideOffline = hidesOfflineSessions(targets.hideOfflineSessions, state.connected, state.stale),
            stale = state.stale,
            marks = sessionMarksById(targets.sessionMarks, sessions.routeId),
            openSessionId = state.selection.sessionId,
        ).map { it.id }
    }

    fun disconnect() {
        invalidate()
        repository.disconnect()
        replace(RemoteSelection(), keepSettings = false)
    }
}

/**
 * A navigation waiting for the discard prompt of the editor of [path] in the session [sessionId];
 * [onDropped] runs when it is dropped instead of run: kept (the user chose Keep), or not (another
 * session or navigation took its place).
 */
internal class HeldNavigation(
    val routeId: String,
    val path: String,
    val sessionId: String?,
    val action: () -> Unit,
    val onDropped: ((kept: Boolean) -> Unit)? = null,
) {
    val fileName: String get() = path.substringAfterLast('/')
}

/**
 * The code editor's side of the layout's keys and prompts: the editor reports whether keyboard
 * focus is inside it, and learns whether the layout is asking to drop its edit.
 */
internal class EditorFocus {
    /** Focus is on the editor's text, its find field or one of its buttons. */
    var focused by mutableStateOf(false)

    /** The layout's discard prompt for a held navigation shows ([RemoteNavigator.editorLeave]). */
    var asking by mutableStateOf(false)
}

/** The layout's [EditorFocus]; null outside [RemoteNavigation], as in a pane shown on its own. */
internal val LocalEditorFocus = staticCompositionLocalOf<EditorFocus?> { null }

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
    share: PendingShare? = null,
    consumeShare: (PendingShare) -> Unit = {},
    /** The home snapshot the palette lists the sessions of the other hosts from. */
    home: StateFlow<HomeSnapshot> = remember { MutableStateFlow(HomeSnapshot()) },
) {
    val pushSettings by settings.state.collectAsStateWithLifecycle()
    val stack = rememberNavBackStack(RemoteNavKey.Hosts)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentEnablePush by rememberUpdatedState(enablePush)
    var askForNotifications by rememberSaveable { mutableStateOf(false) }
    val lastRoute = remember(context) { LastRouteStore(context) }
    // A cold launch opens the last Mac; a recreated activity keeps the stack it was saved with.
    var coldLaunch by rememberSaveable { mutableStateOf(true) }
    val launchTargeted by rememberUpdatedState(notification != null || share != null)
    val navigator =
        remember(repository, stack, lastRoute) {
            RemoteNavigator(repository, stack, scope, lastRoute) {
                val granted =
                    Build.VERSION.SDK_INT < 33 ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                when (
                    offerNotificationPermission(
                        Build.VERSION.SDK_INT,
                        granted,
                        pushConfigured,
                        settings,
                    )
                ) {
                    PairingNotificationStep.ASK -> askForNotifications = true
                    PairingNotificationStep.ENABLE -> currentEnablePush()
                    PairingNotificationStep.NOTHING -> {}
                }
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
    val chatViewports = rememberSaveable(saver = ChatViewportPositions.Saver) { ChatViewportPositions() }
    LaunchedEffect(navigator) {
        // A tapped notification opens its own session; restoring the previous screen first would
        // only connect and load what the tap replaces. Its open restores when it fails.
        if (notification == null) navigator.restore()
        repository.state.collect { state ->
            navigator.reconcile(state)
            navigator.forgetGoneHosts(state)
            // A launch with a target (notification, widget, shortcut, share) opens that instead.
            if (coldLaunch && (launchTargeted || navigator.coldStart(state))) coldLaunch = false
        }
    }
    LaunchedEffect(notification, navigator) {
        notification?.let {
            navigator.notification(it.routeId, it.sessionId, it.jobs, it.jobId) {
                consumeNotification(it)
            }
        }
    }
    LaunchedEffect(share, navigator) {
        share?.let { navigator.share(it.request) { consumeShare(it) } }
    }
    // NavDisplay takes Back only above the root; there, Back would leave the app with the share.
    BackHandler(enabled = navigator.backCancelsShare) { navigator.cancelShare() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Recomputed on every size change: rotation, split-screen resizes and DeX windows.
        val layout = paneLayout(maxWidth, maxHeight)
        val paneStore = remember(context) { PaneLayoutStore(context.applicationContext) }
        val codeWrap = remember(paneStore) { CodeWrapState(paneStore) }
        // A cold start shows the list as the user left it in this bucket from the first frame on.
        val twoPane =
            rememberSaveable(saver = TwoPaneState.Saver) { TwoPaneState(memos = paneStore.load()).apply { adopt(layout) } }
        // Set before the panes below read it: the stored widths are shares of the window.
        twoPane.window = maxWidth
        // Keyed on the bucket, so resize ticks never get here; each bucket keeps its own widths.
        LaunchedEffect(twoPane, layout) { twoPane.adopt(layout) }
        // Written once a drag or a toggle has settled, not on every step of it.
        LaunchedEffect(twoPane, paneStore) {
            var saved = twoPane.memos
            try {
                snapshotFlow { twoPane.memos }.collectLatest { memos ->
                    if (memos == saved) return@collectLatest
                    delay(PANE_LAYOUT_SAVE_DELAY_MILLIS)
                    paneStore.save(memos)
                    saved = memos
                }
            } finally {
                // Leaving before the pause ran out still keeps the last drag.
                if (twoPane.memos != saved) paneStore.save(twoPane.memos)
            }
        }
        val hardwareKeyboard = hardwareKeyboardAttached()
        // At every window size: a split screen, a pop-up window or a phone with a Bluetooth
        // keyboard keeps the keys of the wide layout.
        val shortcuts = hardwareKeyboard
        val targets = remember { ShortcutTargets() }
        val hideOfflineSessions = pushSettings.hideOfflineSessions
        val sessionMarks by sessionMarksState()
        SideEffect {
            targets.hideOfflineSessions = hideOfflineSessions
            targets.sessionMarks = sessionMarks
        }
        val activeTwoPane by rememberUpdatedState(twoPane.takeIf { layout.twoPane })
        val collapsed = twoPane.listCollapsed
        val sceneStrategies =
            remember(layout, twoPane, collapsed) {
                if (layout.twoPane)
                    listOf<SceneStrategy<NavKey>>(
                        ProjectColumnsSceneStrategy(layout, twoPane, collapsed),
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
            DisposableEffect(view, navigator, targets) {
                val listener =
                    ViewCompat.OnUnhandledKeyEventListenerCompat { _, event ->
                        navigator.key(KeyEvent(event), activeTwoPane, targets, unhandled = true)
                    }
                ViewCompat.addOnUnhandledKeyEventListener(view, listener)
                onDispose { ViewCompat.removeOnUnhandledKeyEventListener(view, listener) }
            }
        CompositionLocalProvider(
            LocalChatViewportPositions provides chatViewports,
            LocalPaneLayout provides layout,
            LocalCodeWrap provides codeWrap,
            LocalTwoPane provides twoPane.takeIf { layout.twoPane },
            LocalHardwareKeyboard provides hardwareKeyboard,
            LocalEditorFocus provides navigator.editorFocus,
            LocalShortcutTargets provides targets,
            LocalFocusRingColor provides MaterialTheme.colorScheme.primary,
        ) {
            Column(
                // While something in the layout has focus (the composer, the search field), the
                // shortcuts run here, before that field sees the keys.
                (if (shortcuts)
                    Modifier.fillMaxSize().onPreviewKeyEvent { navigator.key(it, activeTwoPane, targets, unhandled = false) }
                else Modifier.fillMaxSize())
                    // The opening notification covers the screen beneath; TalkBack must not reach it either.
                    .then(if (navigator.openingNotification != null) Modifier.clearAndSetSemantics {} else Modifier)
            ) {
                // The banner takes the status bar; the screens below it no longer pad for it.
                val pickInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
                if (navigator.sharePick != null) SharePickBanner(navigator::cancelShare)
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .then(if (navigator.sharePick != null) Modifier.consumeWindowInsets(pickInsets) else Modifier)
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
                        disablePush,
                        pushSettings.notificationsDecided,
                        { settings.markNotificationPromptShown() },
                    )
                }
            }
            if (navigator.openingNotification != null) NotificationOpening()
            targets.help?.let { context ->
                ShortcutHelpDialog(context) { targets.help = null }
            }
            targets.palette?.let { mode ->
                CommandPaletteHost(mode, repository, navigator, settings, home, targets, sheet = !layout.twoPane)
            }
            navigator.editorLeave?.let { held ->
                EditorDiscardDialog(
                    fileName = held.fileName,
                    onDiscard = navigator::leaveEditor,
                    onKeep = navigator::stayInEditor,
                )
            }
        }
    }
}

/**
 * Runs a hardware key in the layout; false leaves it alone. [unhandled] is a key nothing on
 * screen took: nothing has focus, or the focused element ignored it. In a chat, Esc never gets
 * past here (Android would turn it into Back), and a printable key nothing took goes into the
 * composer. With focus in the code editor its keys are its own; an Esc it leaves comes back.
 */
private fun RemoteNavigator.key(event: KeyEvent, twoPane: TwoPaneState?, targets: ShortcutTargets, unhandled: Boolean): Boolean {
    val chat = topChat?.let(targets::chat)
    val native = event.nativeKeyEvent
    if (event.key == Key.Escape) {
        if (chat == null || (editing && !unhandled)) return false
        if (event.type == KeyEventType.KeyDown && native.repeatCount == 0) chat.escape(native.eventTime)
        // Up and down alike, so no half of the key becomes Back.
        return true
    }
    if (event.type != KeyEventType.KeyDown) return false
    val shortcut = resolveShortcut(event, if (chat != null) KeyContext.CHAT else KeyContext.GLOBAL)
    // The editor keeps its keys, apart from F6 and its alias, which leave it for the next pane.
    if (editing && shortcut != Shortcut.NEXT_REGION && shortcut != Shortcut.PREVIOUS_REGION) return false
    if (shortcut != null) return shortcut(shortcut, twoPane, targets, repeat = native.repeatCount > 0, unhandled = unhandled)
    if (!unhandled || chat == null) return false
    // In review mode its single-letter keys are review's own and never type into the composer;
    // a text field with focus takes them before they get here.
    reviewKey(event)?.let { key -> if (chat.reviewKey(key)) return true }
    // Over Files, Changes, a tool detail or the jobs the composer is hidden: no typing into it.
    // Review mode only strips the chat; any other key there ends it and types into the composer.
    if (chat.composerCovered && !chat.reviewing()) return false
    val text =
        typedText(
            native.unicodeChar,
            native.isCtrlPressed,
            native.isAltPressed,
            native.isMetaPressed,
            altGr = altGrOnly(native.metaState),
        ) ?: return false
    if (chat.composerCovered && !chat.leaveReviewToType()) return false
    chat.type(text)
    return true
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
    disablePush: () -> Unit,
    pushDecided: Boolean,
    onNotificationDenied: () -> Unit,
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
                    CenteredPage {
                        RemoteScreen(key, model, navigator, pushConfigured, pushEnabled, enablePush, disablePush, pushDecided, onNotificationDenied)
                    }
                }
                entry<RemoteNavKey.Projects>(metadata = { key -> navKeyMetadata(key) }) { key ->
                    val model = viewModel { ProjectsViewModel(repository, key) }
                    val column = LocalProjectsColumn.current
                    if (column == null)
                        CenteredPage {
                            RemoteScreen(key, model, navigator, pushConfigured, pushEnabled, enablePush, disablePush, pushDecided, onNotificationDenied)
                        }
                    else {
                        // Keeps the full column's scroll position while the strip stands in for it.
                        val holder = rememberSaveableStateHolder()
                        if (column.strip)
                            holder.SaveableStateProvider("strip") {
                                ProjectStrip(key, model, navigator, column.selectedProjectId, column.canExpand)
                            }
                        else
                            holder.SaveableStateProvider("column") {
                                RemoteScreen(key, model, navigator, pushConfigured, pushEnabled, enablePush, disablePush, pushDecided, onNotificationDenied)
                            }
                    }
                }
                entry<RemoteNavKey.Sessions>(metadata = { key -> navKeyMetadata(key) }) { key ->
                    val model = viewModel { SessionsViewModel(repository, key, settings) }
                    RemoteScreen(key, model, navigator, pushConfigured, pushEnabled, enablePush, disablePush, pushDecided, onNotificationDenied)
                }
                entry<RemoteNavKey.FolderBrowser>(metadata = { key -> navKeyMetadata(key) }) { key ->
                    val model = viewModel { FolderBrowserViewModel(repository, key) }
                    CenteredPage { FolderBrowserScreen(key, model, navigator) }
                }
                entry<RemoteNavKey.Providers>(metadata = { key -> navKeyMetadata(key) }) { key ->
                    val model = viewModel { ProvidersViewModel(repository, key) }
                    CenteredPage { ProvidersScreen(key, model, navigator) }
                }
                entry<RemoteNavKey.Activity>(metadata = { key -> navKeyMetadata(key) }) { key ->
                    val model = viewModel { ActivityViewModel(repository, key) }
                    // A column of the wide layout fills its pane; a phone page keeps the centred width.
                    if (LocalTwoPane.current == null) CenteredPage { ActivityBoardScreen(key, model, navigator) }
                    else ActivityBoardScreen(key, model, navigator)
                }
                entry<RemoteNavKey.Chat>(metadata = { key -> navKeyMetadata(key) }) { key ->
                    val model = viewModel { ChatViewModel(repository, key, settings) }
                    RemoteScreen(key, model, navigator, pushConfigured, pushEnabled, enablePush, disablePush, pushDecided, onNotificationDenied)
                }
                entry<RemoteNavKey.Settings>(
                    metadata =
                        navKeyMetadata(RemoteNavKey.Settings) +
                            metadata {
                                put(NavDisplay.TransitionKey) { settingsTransition() }
                                put(NavDisplay.PopTransitionKey) { settingsTransition() }
                                put(NavDisplay.PredictivePopTransitionKey) { settingsTransition() }
                            },
                ) { key ->
                    val model = viewModel { SettingsViewModel(repository, settings) }
                    CenteredPage {
                        RemoteScreen(key, model, navigator, pushConfigured, pushEnabled, enablePush, disablePush, pushDecided, onNotificationDenied)
                    }
                }
            },
        transitionSpec = { depthTransition(forward = true) },
        popTransitionSpec = { depthTransition(forward = false) },
        predictivePopTransitionSpec = { depthTransition(forward = false) },
    )
}
