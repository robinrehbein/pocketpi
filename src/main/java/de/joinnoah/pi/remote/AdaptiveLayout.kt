package de.joinnoah.pi.remote

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

/*
 * Tablet layout. The window width alone picks the layout, so portrait, landscape, split-screen
 * and DeX windows all follow the same rules, and a resize simply picks again:
 *
 * - below 800 dp: the phone layout, one full-screen destination at a time (unchanged);
 * - from 800 dp: the session list on the left and the selected chat on the right. The list can
 *   be collapsed so the chat gets the whole width;
 * - from 1280 dp: tool details and diffs open in an inspector pane beside the chat instead of
 *   covering it, and the pane closes with them;
 * - from 1600 dp (DeX): the inspector pane stays open, empty until something is inspected.
 *
 * The back stack stays the single source of truth in every layout: the two-pane layout is only a
 * different way to show the same [RemoteNavKey.Sessions] + [RemoteNavKey.Chat] entries, so
 * rotation, split-screen resizes and deep links keep the selected session and its draft.
 */

internal val TWO_PANE_MIN_WIDTH = 800.dp
internal val INSPECTOR_MIN_WIDTH = 1280.dp
internal val PERSISTENT_INSPECTOR_MIN_WIDTH = 1600.dp

/** Widest a chat bubble column and the composer grow; wider chat panes centre them. */
internal val MAX_READING_WIDTH = 840.dp

internal enum class InspectorMode {
    /** Tool details and diffs cover the chat, as on a phone. */
    OVERLAY,
    /** A side pane opens with a tool detail or diff and closes with it. */
    ON_DEMAND,
    /** The side pane is always there. */
    PERSISTENT,
}

internal data class PaneLayout(
    val twoPane: Boolean,
    val inspector: InspectorMode,
    val listWidth: Dp = 0.dp,
    val inspectorWidth: Dp = 0.dp,
) {
    companion object {
        val Phone = PaneLayout(twoPane = false, inspector = InspectorMode.OVERLAY)
    }
}

internal fun paneLayout(width: Dp): PaneLayout =
    when {
        width < TWO_PANE_MIN_WIDTH -> PaneLayout.Phone
        width < INSPECTOR_MIN_WIDTH ->
            PaneLayout(true, InspectorMode.OVERLAY, listWidth = if (width < 1000.dp) 320.dp else 360.dp)
        width < PERSISTENT_INSPECTOR_MIN_WIDTH ->
            PaneLayout(true, InspectorMode.ON_DEMAND, listWidth = 360.dp, inspectorWidth = 400.dp)
        else -> PaneLayout(true, InspectorMode.PERSISTENT, listWidth = 360.dp, inspectorWidth = 480.dp)
    }

/** The layout of the current window; the phone layout wherever nothing provides one. */
internal val LocalPaneLayout = staticCompositionLocalOf { PaneLayout.Phone }

/**
 * Two-pane state that is not part of the back stack. Only the collapsed list survives
 * recreation; the rest is rebuilt by the panes themselves.
 */
@Stable
internal class TwoPaneState(listCollapsed: Boolean = false) {
    var listCollapsed by mutableStateOf(listCollapsed)

    /** Ctrl+K asks the list to focus its search field; the list handles each request once. */
    var searchRequests by mutableIntStateOf(0)
    var searchHandled by mutableIntStateOf(0)

    /** The session ids the list shows, top to bottom, for Ctrl/Alt+Up/Down. */
    var visibleSessionIds by mutableStateOf(emptyList<String>())

    fun requestSearch() {
        listCollapsed = false
        searchRequests++
    }

    companion object {
        val Saver: Saver<TwoPaneState, Boolean> =
            Saver(save = { it.listCollapsed }, restore = { TwoPaneState(it) })
    }
}

/** The two-pane state; null in the phone layout. */
internal val LocalTwoPane = staticCompositionLocalOf<TwoPaneState?> { null }

/** Set around the session list while it is the left pane; [selectedSessionId] is the chat shown. */
internal data class ListPane(val selectedSessionId: String?)

internal val LocalListPane = staticCompositionLocalOf<ListPane?> { null }

/** True around the chat while it is the right pane of the two-pane layout. */
internal val LocalDetailPane = staticCompositionLocalOf { false }

/** Metadata key under which each Sessions and Chat entry carries its [RemoteNavKey]. */
internal const val NAV_KEY_METADATA = "de.joinnoah.pi.remote.navKey"

internal fun navKeyMetadata(key: RemoteNavKey): Map<String, Any> = mapOf(NAV_KEY_METADATA to key)

/** Which back stack positions the two panes show: a Sessions list and, optionally, a chat. */
internal data class ListDetailPanes(val list: Int, val detail: Int?)

/**
 * The two-pane split of [keys], or null when the top destination is not part of it (hosts,
 * projects, folders, settings: those stay full screen). A chat pairs with the Sessions entry of
 * its project below it, skipping the parent chats a child chat is stacked on.
 */
internal fun listDetailPanes(keys: List<Any?>): ListDetailPanes? {
    val lastIndex = keys.lastIndex
    return when (val top = keys.lastOrNull()) {
        is RemoteNavKey.Sessions -> ListDetailPanes(lastIndex, null)
        is RemoteNavKey.Chat -> {
            var index = lastIndex - 1
            while (index >= 0) {
                when (val below = keys[index]) {
                    is RemoteNavKey.Chat ->
                        if (below.routeId != top.routeId || below.projectId != top.projectId) return null
                    is RemoteNavKey.Sessions ->
                        return if (below.routeId == top.routeId && below.projectId == top.projectId)
                            ListDetailPanes(index, lastIndex)
                        else null
                    else -> return null
                }
                index--
            }
            null
        }
        else -> null
    }
}

private data class ListDetailSceneKey(val routeId: String, val projectId: String)

/**
 * Shows [RemoteNavKey.Sessions] and the chat above it side by side. Selecting another session
 * replaces the chat entry, so the scene key stays and only the right pane changes.
 */
internal class ListDetailSceneStrategy(
    private val layout: PaneLayout,
    private val state: TwoPaneState,
) : SceneStrategy<NavKey> {
    override fun SceneStrategyScope<NavKey>.calculateScene(
        entries: List<NavEntry<NavKey>>,
    ): Scene<NavKey>? {
        if (!layout.twoPane) return null
        val panes = listDetailPanes(entries.map { it.metadata[NAV_KEY_METADATA] }) ?: return null
        val sessions = entries[panes.list].metadata[NAV_KEY_METADATA] as RemoteNavKey.Sessions
        return ListDetailScene(
            key = ListDetailSceneKey(sessions.routeId, sessions.projectId),
            list = entries[panes.list],
            detail = panes.detail?.let(entries::get),
            previousEntries = entries.dropLast(1),
            layout = layout,
            state = state,
        )
    }
}

private data class ListDetailScene(
    override val key: Any,
    private val list: NavEntry<NavKey>,
    private val detail: NavEntry<NavKey>?,
    override val previousEntries: List<NavEntry<NavKey>>,
    private val layout: PaneLayout,
    private val state: TwoPaneState,
) : Scene<NavKey> {
    override val entries: List<NavEntry<NavKey>> = listOfNotNull(list, detail)

    private val selectedSessionId =
        (detail?.metadata?.get(NAV_KEY_METADATA) as? RemoteNavKey.Chat)?.sessionId

    override val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // Only a shown chat can take the list's room; without one the list is all there is.
            if (detail == null || !state.listCollapsed) {
                Box(Modifier.width(layout.listWidth).fillMaxHeight().testTag("sessionListPane")) {
                    CompositionLocalProvider(LocalListPane provides ListPane(selectedSessionId)) {
                        list.Content()
                    }
                }
                VerticalDivider()
            }
            Box(Modifier.weight(1f).fillMaxHeight().testTag("chatPane")) {
                if (detail == null) DetailPlaceholder()
                else
                    key(detail.contentKey) {
                        CompositionLocalProvider(LocalDetailPane provides true) { detail.Content() }
                    }
            }
        }
    }
}

@Composable
private fun DetailPlaceholder() {
    Box(Modifier.fillMaxSize().padding(32.dp).testTag("chatPlaceholder"), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.remote_two_pane_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * What Back does in the two-pane layout before the regular navigation runs. Tool details, diffs
 * and jobs close first through their own back handlers, in every layout. After that:
 *
 * 1. Settings on top closes (regular navigation).
 * 2. A chat shown with the session list collapsed brings the list back.
 * 3. Everything else is the phone's back stack: a child chat returns to its parent, a chat to
 *    its project's sessions (list and an empty chat pane), the sessions to the projects.
 */
internal enum class BackStep { EXPAND_LIST, NAVIGATE }

internal fun twoPaneBackStep(layout: PaneLayout, listCollapsed: Boolean, top: NavKey?): BackStep =
    if (layout.twoPane && listCollapsed && top is RemoteNavKey.Chat) BackStep.EXPAND_LIST
    else BackStep.NAVIGATE

/** A connected hardware keyboard, such as a Book Cover Keyboard; its keys only count then. */
@Composable
internal fun hardwareKeyboardAttached(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.keyboard != Configuration.KEYBOARD_NOKEYS &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
}

internal enum class KeyboardShortcut { SEARCH, PREVIOUS_SESSION, NEXT_SESSION }

/** Ctrl+K or Ctrl+F search the sessions; Ctrl or Alt with Up/Down switch to the adjacent one. */
internal fun keyboardShortcut(key: Key, ctrl: Boolean, alt: Boolean, shift: Boolean): KeyboardShortcut? {
    if (shift) return null
    return when {
        ctrl && !alt && (key == Key.K || key == Key.F) -> KeyboardShortcut.SEARCH
        (ctrl != alt) && key == Key.DirectionUp -> KeyboardShortcut.PREVIOUS_SESSION
        (ctrl != alt) && key == Key.DirectionDown -> KeyboardShortcut.NEXT_SESSION
        else -> null
    }
}

/** The session [step] rows away from [current] in [ids]; from no selection, the first or last. */
internal fun adjacentSession(ids: List<String>, current: String?, step: Int): String? {
    if (ids.isEmpty()) return null
    val index = ids.indexOf(current)
    if (index < 0) return if (step > 0) ids.first() else ids.last()
    return ids.getOrNull(index + step)
}

/** What a hardware Enter does in the composer. */
internal enum class ComposerEnter {
    /** The text field handles the key as before (a newline, or nothing). */
    DEFAULT,
    SEND,
    NEWLINE,
}

/**
 * Enter sends and Shift+Enter inserts a newline while [enterSends] is on; with it off, Enter keeps
 * inserting a newline. Ctrl+Enter sends either way. Only hardware keys count: an on-screen
 * keyboard sends its newline through the input connection, not as a key press.
 */
internal fun composerEnter(
    key: Key,
    keyDown: Boolean,
    ctrl: Boolean,
    shift: Boolean,
    alt: Boolean,
    hardwareKeyboard: Boolean,
    enterSends: Boolean,
): ComposerEnter {
    if (!hardwareKeyboard || !keyDown || (key != Key.Enter && key != Key.NumPadEnter)) return ComposerEnter.DEFAULT
    return when {
        alt -> ComposerEnter.DEFAULT
        ctrl -> ComposerEnter.SEND
        !enterSends -> ComposerEnter.DEFAULT
        shift -> ComposerEnter.NEWLINE
        else -> ComposerEnter.SEND
    }
}

/** Case-insensitive title or preview match; a blank query keeps every row. */
internal fun filterSessions(items: List<SessionListItem>, query: String): List<SessionListItem> {
    val needle = query.trim()
    if (needle.isEmpty()) return items
    return items.filter {
        it.title.contains(needle, ignoreCase = true) || it.preview?.contains(needle, ignoreCase = true) == true
    }
}

/**
 * The chat with its inspector pane beside it while [shown]. The chat keeps its place in the
 * composition either way, so opening or closing the pane (or a resize across 1280 dp) keeps its
 * scroll position and open tool.
 */
@Composable
internal fun InspectorRow(
    shown: Boolean,
    width: Dp,
    inspector: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        if (shown) {
            VerticalDivider()
            Box(Modifier.width(width).fillMaxHeight().testTag("inspectorPane")) {
                if (inspector != null) inspector()
                else
                    Box(
                        Modifier.fillMaxSize().padding(32.dp).testTag("inspectorPlaceholder"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.remote_inspector_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
            }
        }
    }
}

@Composable
internal fun SessionSearchField(
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(stringResource(R.string.remote_session_search)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier =
            Modifier.fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag("sessionSearch"),
    )
}
