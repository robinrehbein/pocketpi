package de.joinnoah.pi.remote

import android.content.Context
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.center
import androidx.compose.ui.unit.toOffset
import kotlin.math.roundToInt

/**
 * One entry of a pointer menu. [label] is a string resource, [tag] names the entry for tests
 * (`menu_<tag>`), and [onClick] runs the action the element already offers elsewhere.
 */
@Immutable
internal class MenuItem(
    @StringRes val label: Int,
    val tag: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The one menu open in the window: its entries and where it opens, in window coordinates.
 * [owner] is the element that opened it, which closes it again when it leaves the screen.
 */
@Immutable
internal class OpenMenu(val position: Offset, val items: List<MenuItem>, val owner: Any? = null)

/** The window's pointer menu; [PointerMenuHost] shows it, [contextMenu] elements open it. */
@Stable
internal class PointerMenus(
    /** The window's root view. An element in a dialog or popup lies in another window. */
    val view: View?,
) {
    var menu by mutableStateOf<OpenMenu?>(null)
        private set

    fun open(position: Offset, items: List<MenuItem>, owner: Any? = null) {
        if (items.isNotEmpty()) menu = OpenMenu(position, items, owner)
    }

    fun dismiss() {
        menu = null
    }

    /** Closes the menu if [owner] opened it: its entries would act on an element that is gone. */
    fun dismissFrom(owner: Any) {
        if (menu?.owner === owner) menu = null
    }
}

internal val LocalPointerMenus = staticCompositionLocalOf<PointerMenus?> { null }

/**
 * Hosts the window's pointer menu around [content]: a Material dropdown that opens where the
 * pointer was, or on the element the keyboard focused. Without a host, and in dialogs and popups,
 * which are windows of their own, [contextMenu] does nothing.
 */
@Composable
internal fun PointerMenuHost(content: @Composable () -> Unit) {
    val view = LocalView.current
    val menus = remember(view) { PointerMenus(view) }
    val origin = remember { CoordinatesHolder() }
    Box(Modifier.onGloballyPositioned { origin.coordinates = it }) {
        CompositionLocalProvider(LocalPointerMenus provides menus, content = content)
        val open = menus.menu
        val coordinates = origin.coordinates?.takeIf { it.isAttached }
        if (open != null && coordinates != null) {
            val local = coordinates.windowToLocal(open.position)
            // A zero-size anchor at the pointer: the dropdown opens below it, or flips to fit.
            Box(Modifier.offset { IntOffset(local.x.roundToInt(), local.y.roundToInt()) }) {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = menus::dismiss,
                    modifier = Modifier.testTag("pointerMenu"),
                ) {
                    for (item in open.items)
                        DropdownMenuItem(
                            text = { Text(stringResource(item.label)) },
                            onClick = {
                                menus.dismiss()
                                item.onClick()
                            },
                            enabled = item.enabled,
                            modifier = Modifier.testTag("menu_${item.tag}"),
                        )
                }
            }
        }
    }
}

private class CoordinatesHolder {
    var coordinates: LayoutCoordinates? = null
}

/**
 * Lets an element open its [contextMenu] from its own gestures too, such as a long press on a
 * session row. The menu opens where the last press went down, or in the element's centre.
 */
@Stable
internal class MenuAnchor {
    internal var menus: PointerMenus? = null
    internal var coordinates: LayoutCoordinates? = null
    internal var lastPress: Offset? = null
    internal var items: (Offset?) -> List<MenuItem> = { emptyList() }

    /** Opens the element's menu as a keyboard or a long press asks for it; false without one. */
    fun open(): Boolean {
        val menus = menus ?: return false
        val coordinates = coordinates?.takeIf { it.isAttached } ?: return false
        val position = coordinates.localToWindow(lastPress ?: coordinates.size.center.toOffset())
        val items = items(null)
        if (items.isEmpty()) return false
        menus.open(position, items, owner = this)
        return true
    }
}

@Composable
internal fun rememberMenuAnchor(): MenuAnchor = remember { MenuAnchor() }

/** Shift+F10 and the Menu key open the focused element's menu, as on a desktop. */
internal fun opensContextMenu(key: Key, shift: Boolean): Boolean =
    (key == Key.F10 && shift) || (key == Key.Menu && !shift)

/**
 * Opens a menu of [items] on a secondary press, which also covers the S Pen's side button and a
 * touchpad's two-finger tap, and on Shift+F10 or the Menu key while the element or something in
 * it has focus. [items] gets the press in window coordinates, or null for the keyboard and a
 * long press through [anchor]; an empty list leaves the press to the content (a text selection's
 * own menu, for one). The press is taken before the element's children see it, so it never
 * also clicks, quotes or starts a selection.
 */
internal fun Modifier.contextMenu(
    anchor: MenuAnchor? = null,
    enabled: Boolean = true,
    items: (position: Offset?) -> List<MenuItem>,
): Modifier = composed {
    val menus = LocalPointerMenus.current
    val latest by rememberUpdatedState(items)
    val own = anchor ?: rememberMenuAnchor()
    own.menus = menus
    own.items = { latest(it) }
    // A row scrolled away or a screen left behind takes its open menu along.
    if (menus != null) DisposableEffect(menus, own) { onDispose { menus.dismissFrom(own) } }
    // A dialog or popup is a window of its own, which the host's coordinates do not reach.
    if (menus == null || !enabled || LocalView.current !== menus.view) return@composed Modifier
    Modifier.onGloballyPositioned { own.coordinates = it }
        .pointerInput(own) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Press) continue
                    val change = event.changes.firstOrNull() ?: continue
                    own.lastPress = change.position
                    if (!event.buttons.isSecondaryPressed || change.isConsumed) continue
                    val coordinates = own.coordinates?.takeIf { it.isAttached } ?: continue
                    val position = coordinates.localToWindow(change.position)
                    val entries = latest(position)
                    if (entries.isEmpty()) continue
                    change.consume()
                    menus.open(position, entries, owner = own)
                }
            }
        }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || !opensContextMenu(event.key, event.isShiftPressed)) false
            else {
                own.lastPress = null
                own.open()
            }
        }
}

/** The entries of a session row's menu. Pin and Mark read are phone-local (SessionMarksStore). */
internal enum class SessionMenuAction(@StringRes val label: Int) {
    OPEN(R.string.remote_menu_open),
    RENAME(R.string.remote_rename_session),
    /** Opens the chat and its rewind menu on the latest message, as Ctrl+Shift+N does there. */
    FORK_LATEST(R.string.remote_keymap_fork),
    PIN(R.string.remote_menu_pin),
    UNPIN(R.string.remote_menu_unpin),
    MARK_READ(R.string.remote_menu_mark_read),
    CLOSE(R.string.remote_session_close),
    COPY_ID(R.string.remote_menu_copy_session_id),
}

/**
 * The entries of a session row's menu. [pinned] names the pin entry: Pin, or Unpin when it is
 * set; [canMarkRead] offers Mark read on a row that is unread.
 */
internal fun sessionMenuActions(
    canRename: Boolean,
    canClose: Boolean,
    canFork: Boolean = false,
    canPin: Boolean = false,
    pinned: Boolean = false,
    canMarkRead: Boolean = false,
): List<SessionMenuAction> =
    buildList {
        add(SessionMenuAction.OPEN)
        if (canRename) add(SessionMenuAction.RENAME)
        if (canFork) add(SessionMenuAction.FORK_LATEST)
        if (canPin) add(if (pinned) SessionMenuAction.UNPIN else SessionMenuAction.PIN)
        if (canMarkRead) add(SessionMenuAction.MARK_READ)
        if (canClose) add(SessionMenuAction.CLOSE)
        add(SessionMenuAction.COPY_ID)
    }

/** The entries of a message bubble's menu. */
internal enum class MessageMenuAction(@StringRes val label: Int) {
    QUOTE(R.string.remote_quote),
    COPY(R.string.remote_menu_copy),
    COPY_MARKDOWN(R.string.remote_menu_copy_markdown),
    FORK(R.string.remote_fork_action),
    ASK_TO_FIX(R.string.remote_tool_detail_ask_fix),
    EDIT_RETRY(R.string.remote_menu_edit_retry),
}

/**
 * The menu of a bubble of [role] showing [text]. [forkable] is the first bubble of a user message
 * in a chat that can be rewound; [canAskToFix] says the chat takes a prompt now. A streaming
 * text is still changing, so it is quoted but not copied. "Copy as Markdown" shows only where
 * the Markdown differs from what the bubble shows.
 */
internal fun messageMenuActions(
    role: String,
    text: String,
    streaming: Boolean,
    forkable: Boolean,
    canAskToFix: Boolean,
): List<MessageMenuAction> =
    buildList {
        val hasText = text.isNotBlank()
        if (hasText) add(MessageMenuAction.QUOTE)
        val copyable = hasText && !streaming
        if (copyable) add(MessageMenuAction.COPY)
        if (copyable && markdownPlainText(text) != text) add(MessageMenuAction.COPY_MARKDOWN)
        val userFork = forkable && role == "user"
        if (userFork) add(MessageMenuAction.FORK)
        if (canAskToFix && role == "assistant" && copyable) add(MessageMenuAction.ASK_TO_FIX)
        if (userFork) add(MessageMenuAction.EDIT_RETRY)
    }

/** The entries of a tool card's menu. */
internal enum class ToolMenuAction(@StringRes val label: Int) {
    OPEN(R.string.remote_card_open_tool),
    PEEK(R.string.remote_output_peek),
    COPY_COMMAND(R.string.remote_menu_copy_command),
    COPY_OUTPUT(R.string.remote_menu_copy_output),
}

internal fun toolMenuActions(canOpen: Boolean, hasOutput: Boolean, hasCommand: Boolean): List<ToolMenuAction> =
    buildList {
        if (canOpen) add(ToolMenuAction.OPEN)
        if (hasOutput) add(ToolMenuAction.PEEK)
        if (hasCommand) add(ToolMenuAction.COPY_COMMAND)
        if (hasOutput) add(ToolMenuAction.COPY_OUTPUT)
    }

/** The whole command of a `bash` call, which the card's header shortens to its first line. */
internal fun toolCommand(name: String?, arguments: String?): String? {
    if (name != "bash" || arguments == null) return null
    val command = parsedToolArguments(arguments)?.get("command") as? kotlinx.serialization.json.JsonPrimitive
    return command?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
}

/** The entries of a file tile's menu. */
internal enum class FileMenuAction(@StringRes val label: Int) {
    OPEN(R.string.remote_menu_open),
    SEND(R.string.remote_files_send_file),
    COPY_PATH(R.string.remote_menu_copy_path),
    COPY_CONTENTS(R.string.remote_menu_copy_contents),
}

/** [contentsLoaded]: the tile holds the whole text of the file, not just its first lines. */
internal fun fileMenuActions(openable: Boolean, canSend: Boolean, contentsLoaded: Boolean): List<FileMenuAction> =
    buildList {
        if (openable) add(FileMenuAction.OPEN)
        if (canSend) add(FileMenuAction.SEND)
        add(FileMenuAction.COPY_PATH)
        if (contentsLoaded) add(FileMenuAction.COPY_CONTENTS)
    }

/** The entries of a diff line's menu in Changes. */
internal enum class DiffLineMenuAction(@StringRes val label: Int) {
    COMMENT(R.string.remote_changes_comment_add),
    COPY_LINE(R.string.remote_menu_copy_line),
    COPY_WITH_PREFIX(R.string.remote_code_copy_with_prefix),
    COPY_HUNK(R.string.remote_menu_copy_hunk),
    OPEN_FILE(R.string.remote_open_files_open_line),
}

/**
 * [openable]: the line has a place in the file as it is now, and Files can show it. [copyWithPrefix]:
 * the lines copy with their +, - or space prefixes, for a patch; a pane that cannot give them leaves it out.
 */
internal fun diffLineMenuActions(
    commentable: Boolean,
    openable: Boolean = false,
    copyWithPrefix: Boolean = false,
): List<DiffLineMenuAction> =
    buildList {
        if (commentable) add(DiffLineMenuAction.COMMENT)
        add(DiffLineMenuAction.COPY_LINE)
        if (copyWithPrefix) add(DiffLineMenuAction.COPY_WITH_PREFIX)
        add(DiffLineMenuAction.COPY_HUNK)
        if (openable) add(DiffLineMenuAction.OPEN_FILE)
    }

/**
 * The entries of a project row's or project avatar's menu. "Open Files" and "Copy path" wait for
 * the host: Files belong to a session, not a project, and `projects.list` sends no folder path.
 * Unshare repeats the row's swipe, behind the same confirmation.
 */
internal enum class ProjectMenuAction(@StringRes val label: Int) {
    OPEN(R.string.remote_menu_open),
    NEW_SESSION(R.string.remote_new_session),
    UNSHARE(R.string.remote_project_unshare),
}

internal fun projectMenuActions(canCreateSession: Boolean, canUnshare: Boolean): List<ProjectMenuAction> =
    buildList {
        add(ProjectMenuAction.OPEN)
        if (canCreateSession) add(ProjectMenuAction.NEW_SESSION)
        if (canUnshare) add(ProjectMenuAction.UNSHARE)
    }

/** The entries of a link's menu in chat Markdown. */
internal enum class LinkMenuAction(@StringRes val label: Int) {
    OPEN_ADJACENT(R.string.remote_menu_link_adjacent),
    /** A plain view intent: the browser opens where the system puts it, often in PocketPi's window. */
    OPEN_IN_WINDOW(R.string.remote_menu_link_in_window),
    COPY(R.string.remote_menu_link_copy),
}

/** How the link menu opens a link: [adjacent] beside PocketPi, [inWindow] in its window. */
internal class LinkOpeners(val adjacent: UriHandler, val inWindow: UriHandler)

/** Replaces the link menu's openers, for tests; null opens through [AdjacentUriHandler]. */
internal val LocalLinkOpeners = staticCompositionLocalOf<LinkOpeners?> { null }

private fun defaultLinkOpeners(context: Context) =
    LinkOpeners(AdjacentUriHandler(context) { true }, AdjacentUriHandler(context) { false })

/** The link menu's entries for a URL. */
@Composable
internal fun rememberLinkMenu(): (String) -> List<MenuItem> {
    val context = LocalContext.current
    val openers = LocalLinkOpeners.current ?: remember(context) { defaultLinkOpeners(context) }
    val copy = rememberCopyToClipboard(stringResource(R.string.remote_menu_link_copy))
    return { url ->
        LinkMenuAction.entries.map { action ->
            MenuItem(action.label, "link_${action.name.lowercase()}") {
                // Like a tapped link, a link no app opens is dropped silently.
                when (action) {
                    LinkMenuAction.OPEN_ADJACENT -> runCatching { openers.adjacent.openUri(url) }
                    LinkMenuAction.OPEN_IN_WINDOW -> runCatching { openers.inWindow.openUri(url) }
                    LinkMenuAction.COPY -> copy(url)
                }
            }
        }
    }
}

/**
 * The links of the texts inside an element with a menu of its own, such as a message bubble:
 * the element asks [urlAt] first and opens the link menu for a press on a link.
 */
internal class LinkTargets {
    private val targets = mutableListOf<(Offset) -> String?>()

    fun add(target: (Offset) -> String?) {
        targets += target
    }

    fun remove(target: (Offset) -> String?) {
        targets.remove(target)
    }

    /** The URL of the link at [position] in window coordinates, if any. */
    fun urlAt(position: Offset): String? = targets.firstNotNullOfOrNull { it(position) }
}

internal val LocalLinkTargets = staticCompositionLocalOf<LinkTargets?> { null }

/** Finds the link under a window position in one laid out text. */
private class LinkHitTest {
    var text = AnnotatedString("")
    var layout: TextLayoutResult? = null
    var coordinates: LayoutCoordinates? = null

    fun urlAt(position: Offset): String? {
        val coordinates = coordinates?.takeIf { it.isAttached } ?: return null
        val layout = layout ?: return null
        val local = coordinates.windowToLocal(position)
        if (!Rect(Offset.Zero, coordinates.size.toSize()).contains(local)) return null
        // Only a press on a line's text counts, not the space beside a short line.
        val line = layout.getLineForVerticalPosition(local.y)
        if (local.x < layout.getLineLeft(line) || local.x > layout.getLineRight(line)) return null
        // The nearest caret position: the character before it lies on its left (right in RTL).
        val caret = layout.getOffsetForPosition(local)
        val before =
            caret > 0 &&
                layout.getHorizontalPosition(caret, usePrimaryDirection = true).let { edge ->
                    if (layout.getBidiRunDirection(caret - 1) == ResolvedTextDirection.Rtl) local.x > edge else local.x < edge
                }
        val index = if (before) caret - 1 else caret
        return linkAt(text, index)
    }
}

private fun androidx.compose.ui.unit.IntSize.toSize() =
    androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat())

/**
 * [text] with a menu on its links. Inside an element with [LocalLinkTargets] that element opens
 * the link menu; elsewhere the text opens it itself. Links already show the hand pointer.
 */
@Composable
internal fun LinkMenuText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier) {
    if (!text.hasLinkAnnotations(0, text.length)) {
        Text(text, modifier, style = style)
        return
    }
    val hit = remember { LinkHitTest() }
    hit.text = text
    val targets = LocalLinkTargets.current
    if (targets != null)
        DisposableEffect(targets, hit) {
            val target: (Offset) -> String? = hit::urlAt
            targets.add(target)
            onDispose { targets.remove(target) }
        }
    val linkMenu = rememberLinkMenu()
    Text(
        text,
        modifier
            .onGloballyPositioned { hit.coordinates = it }
            .then(
                if (targets != null) Modifier
                else Modifier.contextMenu { position -> position?.let(hit::urlAt)?.let(linkMenu).orEmpty() }
            ),
        style = style,
        onTextLayout = { hit.layout = it },
    )
}
