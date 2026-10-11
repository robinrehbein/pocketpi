package de.joinnoah.pi.remote

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.mutableFloatStateOf
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
 * Tablet layout. The window size alone picks the layout, so portrait, landscape, split-screen
 * and DeX windows all follow the same rules, and a resize simply picks again:
 *
 * - below 720 dp wide or 480 dp high: the phone layout, one full-screen destination at a time
 *   (unchanged), so a phone in landscape keeps it;
 * - from 720 dp wide and 480 dp high: the session list on the left and the selected chat on the right. The list can
 *   be collapsed so the chat gets the whole width;
 * - from 1100 dp: tool details and diffs open in an inspector pane beside the chat instead of
 *   covering it, and the pane closes with them;
 * - from 1600 dp (DeX): the inspector pane stays open, empty until something is inspected.
 *
 * A Galaxy Tab S11 reports about 753-800 dp in portrait and 1205-1280 dp in landscape, depending
 * on the display zoom, so both orientations land in a two-pane bucket.
 *
 * The back stack stays the single source of truth in every layout: the two-pane layout is only a
 * different way to show the same [RemoteNavKey.Sessions] + [RemoteNavKey.Chat] entries, so
 * rotation, split-screen resizes and deep links keep the selected session and its draft.
 */

internal val TWO_PANE_MIN_WIDTH = 720.dp

/**
 * A phone in landscape is wide enough for two panes but only about 330 dp high, too little for a
 * list and a chat with its composer and keyboard, so it keeps the phone layout.
 */
internal val TWO_PANE_MIN_HEIGHT = 480.dp
internal val INSPECTOR_MIN_WIDTH = 1100.dp
internal val PERSISTENT_INSPECTOR_MIN_WIDTH = 1600.dp

/** Widest a chat bubble column and the composer grow; wider chat panes centre them. */
internal val MAX_READING_WIDTH = 840.dp

/**
 * Widest a full-screen page (hosts, projects, folders, providers, settings) grows; wider windows
 * centre it.
 */
internal val MAX_PAGE_WIDTH = 720.dp

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

internal fun paneLayout(width: Dp, height: Dp): PaneLayout =
    when {
        width < TWO_PANE_MIN_WIDTH || height < TWO_PANE_MIN_HEIGHT -> PaneLayout.Phone
        width < 960.dp -> PaneLayout(true, InspectorMode.OVERLAY, listWidth = 300.dp)
        width < INSPECTOR_MIN_WIDTH -> PaneLayout(true, InspectorMode.OVERLAY, listWidth = 360.dp)
        width < 1280.dp ->
            PaneLayout(true, InspectorMode.ON_DEMAND, listWidth = 320.dp, inspectorWidth = 360.dp)
        width < PERSISTENT_INSPECTOR_MIN_WIDTH ->
            PaneLayout(true, InspectorMode.ON_DEMAND, listWidth = 360.dp, inspectorWidth = 400.dp)
        else -> PaneLayout(true, InspectorMode.PERSISTENT, listWidth = 360.dp, inspectorWidth = 480.dp)
    }

/*
 * The dividers beside the session list and the inspector pane can be dragged. A width set that
 * way holds until the layout bucket changes; every clamp leaves the chat at least
 * [CHAT_PANE_MIN_WIDTH] wide, counting the 1 dp divider lines.
 */

internal val LIST_PANE_MIN_WIDTH = 240.dp
internal val LIST_PANE_MAX_WIDTH = 480.dp
internal val INSPECTOR_PANE_MIN_WIDTH = 300.dp
internal val INSPECTOR_PANE_MAX_WIDTH = 720.dp
internal val CHAT_PANE_MIN_WIDTH = 400.dp

/** How far the "wider" and "narrower" accessibility actions move a divider. */
internal val PANE_RESIZE_STEP = 40.dp

private val PANE_DIVIDER_WIDTH = 1.dp
private val PANE_RESIZE_HANDLE_WIDTH = 24.dp

/**
 * The session list's width for [requested] in a [window] wide layout: it leaves room for the
 * chat and, where one can open beside it, the narrowest inspector pane.
 */
internal fun listPaneWidth(requested: Dp, window: Dp, inspector: InspectorMode): Dp {
    val inspectorReserve =
        if (inspector == InspectorMode.OVERLAY) 0.dp else INSPECTOR_PANE_MIN_WIDTH + PANE_DIVIDER_WIDTH
    val reserve = CHAT_PANE_MIN_WIDTH + PANE_DIVIDER_WIDTH + inspectorReserve
    return requested.coerceIn(LIST_PANE_MIN_WIDTH, maxOf(LIST_PANE_MIN_WIDTH, minOf(LIST_PANE_MAX_WIDTH, window - reserve)))
}

/** The inspector pane's width for [requested] beside a chat with [available] room for both. */
internal fun inspectorPaneWidth(requested: Dp, available: Dp): Dp =
    requested.coerceIn(
        INSPECTOR_PANE_MIN_WIDTH,
        maxOf(
            INSPECTOR_PANE_MIN_WIDTH,
            minOf(INSPECTOR_PANE_MAX_WIDTH, available - CHAT_PANE_MIN_WIDTH - PANE_DIVIDER_WIDTH),
        ),
    )

/** Identifies a layout bucket for [TwoPaneState]; a dragged width belongs to one bucket. */
internal fun PaneLayout.sizeKey(): String = "${inspector.name}:${listWidth.value}:${inspectorWidth.value}"

/**
 * The invisible, [PANE_RESIZE_HANDLE_WIDTH] wide strip over a divider line. A drag calls
 * [onDragStart], then [onDrag] with the whole distance moved since the start towards the layout's
 * end side, so right-to-left layouts need no sign of their own and a pane held at a limit lets go
 * as soon as the finger comes back. Double tap resets; TalkBack offers [label] with wider,
 * narrower ([onStep] moves by a delta) and reset actions.
 */
@Composable
internal fun PaneResizeHandle(
    label: String,
    onDragStart: () -> Unit,
    onDrag: (Dp) -> Unit,
    onStep: (Dp) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentReset by rememberUpdatedState(onReset)
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    var dragged by remember { mutableFloatStateOf(0f) }
    val wider = stringResource(R.string.remote_pane_wider)
    val narrower = stringResource(R.string.remote_pane_narrower)
    val reset = stringResource(R.string.remote_pane_reset_width)
    val context = LocalContext.current
    val hoverIcon =
        remember(context) {
            PointerIcon(android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
        }
    Box(
        modifier
            .width(PANE_RESIZE_HANDLE_WIDTH)
            .fillMaxHeight()
            .pointerHoverIcon(hoverIcon)
            .draggable(
                orientation = Orientation.Horizontal,
                state =
                    rememberDraggableState { px ->
                        dragged += if (rtl) -px else px
                        with(density) { currentDrag(dragged.toDp()) }
                    },
                onDragStarted = {
                    dragged = 0f
                    currentDragStart()
                },
            )
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { currentReset() }) }
            .semantics {
                contentDescription = label
                customActions =
                    listOf(
                        CustomAccessibilityAction(wider) {
                            onStep(PANE_RESIZE_STEP)
                            true
                        },
                        CustomAccessibilityAction(narrower) {
                            onStep(-PANE_RESIZE_STEP)
                            true
                        },
                        CustomAccessibilityAction(reset) {
                            onReset()
                            true
                        },
                    )
            }
    )
}

/**
 * Hosts a full-screen page no wider than [MAX_PAGE_WIDTH], centred in wider windows. The page
 * background fills the gutters so transitions never show the window behind them.
 */
@Composable
internal fun CenteredPage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.widthIn(max = MAX_PAGE_WIDTH).fillMaxWidth().fillMaxHeight().testTag("pageColumn")) {
                content()
            }
        }
    }
}

/** The layout of the current window; the phone layout wherever nothing provides one. */
internal val LocalPaneLayout = staticCompositionLocalOf { PaneLayout.Phone }

/**
 * Two-pane state that is not part of the back stack. The collapsed list and the dragged pane
 * widths survive recreation; the rest is rebuilt by the panes themselves.
 */
@Stable
internal class TwoPaneState(listCollapsed: Boolean = false) {
    var listCollapsed by mutableStateOf(listCollapsed)

    /** Dragged widths, null for the layout's default; they belong to the bucket [sizedFor]. */
    private var listOverride by mutableStateOf<Dp?>(null)
    private var inspectorOverride by mutableStateOf<Dp?>(null)
    private var sizedFor by mutableStateOf<String?>(null)

    /** The session list's requested width in [layout], before [listPaneWidth] clamps it. */
    fun listWidth(layout: PaneLayout): Dp =
        listOverride?.takeIf { sizedFor == layout.sizeKey() } ?: layout.listWidth

    /** The inspector pane's requested width in [layout], before [inspectorPaneWidth] clamps it. */
    fun inspectorWidth(layout: PaneLayout): Dp =
        inspectorOverride?.takeIf { sizedFor == layout.sizeKey() } ?: layout.inspectorWidth

    /** Sets the session list's width in [layout]; null goes back to its default. */
    fun resizeList(layout: PaneLayout, width: Dp?) {
        adopt(layout)
        listOverride = width
    }

    /** Sets the inspector pane's width in [layout]; null goes back to its default. */
    fun resizeInspector(layout: PaneLayout, width: Dp?) {
        adopt(layout)
        inspectorOverride = width
    }

    /**
     * Drops the dragged widths once the layout bucket changes. The same bucket, also right after
     * recreation, keeps them.
     */
    fun adopt(layout: PaneLayout) {
        val key = layout.sizeKey()
        if (sizedFor == key) return
        if (sizedFor != null) {
            listOverride = null
            inspectorOverride = null
        }
        sizedFor = key
    }

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
        val Saver: Saver<TwoPaneState, Any> =
            listSaver(
                save = {
                    listOf(
                        it.listCollapsed,
                        it.listOverride?.value ?: -1f,
                        it.inspectorOverride?.value ?: -1f,
                        it.sizedFor ?: "",
                    )
                },
                restore = {
                    TwoPaneState(it[0] as Boolean).apply {
                        listOverride = (it[1] as Float).takeIf { width -> width >= 0f }?.dp
                        inspectorOverride = (it[2] as Float).takeIf { width -> width >= 0f }?.dp
                        sizedFor = (it[3] as String).ifEmpty { null }
                    }
                },
            )
    }
}

/** The two-pane state; null in the phone layout. */
internal val LocalTwoPane = staticCompositionLocalOf<TwoPaneState?> { null }

/** Set around the session list while it is the left pane; [selectedSessionId] is the chat shown. */
internal data class ListPane(val selectedSessionId: String?)

internal val LocalListPane = staticCompositionLocalOf<ListPane?> { null }

/** A hardware keyboard is attached; read once by [RemoteNavigation] and passed down. */
internal val LocalHardwareKeyboard = staticCompositionLocalOf { false }

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
    /** A new strategy per value, so the scene and its back preview follow the list. */
    private val listCollapsed: Boolean = state.listCollapsed,
) : SceneStrategy<NavKey> {
    override fun SceneStrategyScope<NavKey>.calculateScene(
        entries: List<NavEntry<NavKey>>,
    ): Scene<NavKey>? {
        if (!layout.twoPane) return null
        val panes = listDetailPanes(entries.map { it.metadata[NAV_KEY_METADATA] }) ?: return null
        val sessions = entries[panes.list].metadata[NAV_KEY_METADATA] as RemoteNavKey.Sessions
        val detail = panes.detail?.let(entries::get)
        val collapsed = detail != null && listCollapsed
        return ListDetailScene(
            key = ListDetailSceneKey(sessions.routeId, sessions.projectId),
            list = entries[panes.list],
            detail = detail,
            // Back with the list collapsed only expands it (see twoPaneBackStep): the chat stays.
            // Navigation 3 can only preview a shorter stack, so this state offers no predictive
            // preview at all; the scene's own back handler expands the list instead.
            previousEntries = if (collapsed) emptyList() else entries.dropLast(1),
            layout = layout,
            state = state,
            collapsed = collapsed,
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
    private val collapsed: Boolean,
) : Scene<NavKey> {
    override val entries: List<NavEntry<NavKey>> = if (collapsed) listOfNotNull(detail) else listOfNotNull(list, detail)

    private val selectedSessionId =
        (detail?.metadata?.get(NAV_KEY_METADATA) as? RemoteNavKey.Chat)?.sessionId

    override val content: @Composable () -> Unit = {
        // Registered before the chat's own handlers, so an open tool detail or diff still closes
        // first; without a predictive preview (see previousEntries).
        BackHandler(enabled = collapsed) { state.listCollapsed = false }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val window by rememberUpdatedState(maxWidth)
            val listWidth = listPaneWidth(state.listWidth(layout), maxWidth, layout.inspector)
            Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                // Only a shown chat can take the list's room; without one the list is all there is.
                if (!collapsed) {
                    Box(Modifier.width(listWidth).fillMaxHeight().testTag("sessionListPane")) {
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
            if (!collapsed) {
                // Reads the state on every call: several drag events can arrive between frames.
                fun currentWidth() = listPaneWidth(state.listWidth(layout), window, layout.inspector)
                fun resizeTo(width: Dp) = state.resizeList(layout, listPaneWidth(width, window, layout.inspector))
                // A drag sets start + distance, so the divider follows the finger again after a clamp.
                var dragStart by remember { mutableStateOf(0.dp) }
                PaneResizeHandle(
                    label = stringResource(R.string.remote_pane_resize_list),
                    onDragStart = { dragStart = currentWidth() },
                    onDrag = { resizeTo(dragStart + it) },
                    onStep = { resizeTo(currentWidth() + it) },
                    onReset = { state.resizeList(layout, null) },
                    modifier =
                        Modifier.align(Alignment.CenterStart)
                            .offset(x = listWidth + PANE_DIVIDER_WIDTH / 2 - PANE_RESIZE_HANDLE_WIDTH / 2)
                            .testTag("sessionListDivider"),
                )
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

internal enum class KeyboardShortcut { SEARCH, PREVIOUS_SESSION, NEXT_SESSION, NEW_SESSION, TOGGLE_LIST }

/**
 * Ctrl+K or Ctrl+F search the sessions; Ctrl or Alt with Up/Down switch to the adjacent one; Ctrl+N
 * starts a new session in the project and Ctrl+B collapses or expands the session list.
 */
internal fun keyboardShortcut(key: Key, ctrl: Boolean, alt: Boolean, shift: Boolean): KeyboardShortcut? {
    if (shift) return null
    return when {
        ctrl && !alt && (key == Key.K || key == Key.F) -> KeyboardShortcut.SEARCH
        ctrl && !alt && key == Key.N -> KeyboardShortcut.NEW_SESSION
        ctrl && !alt && key == Key.B -> KeyboardShortcut.TOGGLE_LIST
        (ctrl != alt) && key == Key.DirectionUp -> KeyboardShortcut.PREVIOUS_SESSION
        (ctrl != alt) && key == Key.DirectionDown -> KeyboardShortcut.NEXT_SESSION
        else -> null
    }
}

/** Ctrl+N's condition, the same as the "new session" button: connected, not loading, in this project. */
internal fun canCreateSession(state: RemoteState, sessions: RemoteNavKey.Sessions): Boolean =
    state.connected &&
        !state.loading &&
        state.selection.routeId == sessions.routeId &&
        state.selection.projectId == sessions.projectId

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
 * composition either way, so opening or closing the pane (or a resize across 1100 dp) keeps its
 * scroll position and open tool.
 *
 * [width] is the requested pane width, read again on every drag event; with [onResize] the
 * divider can be dragged, and null resets the width.
 */
@Composable
internal fun InspectorRow(
    shown: Boolean,
    width: () -> Dp,
    onResize: ((Dp?) -> Unit)?,
    inspector: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val available by rememberUpdatedState(maxWidth)
        val paneWidth = inspectorPaneWidth(width(), maxWidth)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            if (shown) {
                VerticalDivider()
                Box(Modifier.width(paneWidth).fillMaxHeight().testTag("inspectorPane")) {
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
        if (shown && onResize != null) {
            // The pane sits at the end side, so moving the divider towards the end narrows it.
            fun currentWidth() = inspectorPaneWidth(width(), available)
            fun resizeTo(width: Dp) = onResize(inspectorPaneWidth(width, available))
            var dragStart by remember { mutableStateOf(0.dp) }
            PaneResizeHandle(
                label = stringResource(R.string.remote_pane_resize_inspector),
                onDragStart = { dragStart = currentWidth() },
                onDrag = { resizeTo(dragStart - it) },
                onStep = { resizeTo(currentWidth() + it) },
                onReset = { onResize(null) },
                modifier =
                    Modifier.align(Alignment.CenterEnd)
                        .offset(x = -(paneWidth + PANE_DIVIDER_WIDTH / 2 - PANE_RESIZE_HANDLE_WIDTH / 2))
                        .testTag("inspectorDivider"),
            )
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
