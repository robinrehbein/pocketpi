package de.joinnoah.pi.remote

import android.content.Context
import android.view.KeyCharacterMap
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key

/** The sections of the shortcut lists (the system's Meta+/ helper, Ctrl+/ and Settings). */
internal enum class ShortcutGroup(@StringRes val titleRes: Int) {
    NAVIGATION(R.string.remote_shortcut_group_navigation),
    CHAT(R.string.remote_shortcut_group_chat),
    COMPOSER(R.string.remote_shortcut_group_composer),
    PANES(R.string.remote_shortcut_group_panes),
}

/**
 * Everything a hardware keyboard can do in PocketPi; [KEYMAP] binds the keys. A shortcut that is
 * not [listed] has a key but no action yet, so the shortcut lists leave it out.
 */
internal enum class Shortcut(val group: ShortcutGroup, val listed: Boolean = true) {
    PALETTE(ShortcutGroup.NAVIGATION),
    SEARCH(ShortcutGroup.NAVIGATION),
    PREVIOUS_SESSION(ShortcutGroup.NAVIGATION),
    NEXT_SESSION(ShortcutGroup.NAVIGATION),
    NEW_SESSION(ShortcutGroup.NAVIGATION),
    TOGGLE_LIST(ShortcutGroup.PANES),
    PREVIOUS_PROJECT(ShortcutGroup.NAVIGATION),
    NEXT_PROJECT(ShortcutGroup.NAVIGATION),
    FOCUS_COMPOSER(ShortcutGroup.CHAT),
    STOP(ShortcutGroup.CHAT),
    CHANGES(ShortcutGroup.PANES),
    FILES(ShortcutGroup.PANES),
    JOBS(ShortcutGroup.PANES),
    HISTORY(ShortcutGroup.PANES),
    SETTINGS(ShortcutGroup.NAVIGATION),
    HELP(ShortcutGroup.NAVIGATION),
    EXPAND_INSPECTOR(ShortcutGroup.PANES),
    TOGGLE_INSPECTOR(ShortcutGroup.PANES),
    REVIEW(ShortcutGroup.PANES),
    OTHER_BUSY_ACTION(ShortcutGroup.COMPOSER),
    SELECT_ALL_LINES(ShortcutGroup.PANES),
    COPY_LINES(ShortcutGroup.PANES),
    ZOOM_IN(ShortcutGroup.PANES),
    ZOOM_OUT(ShortcutGroup.PANES),
    REFRESH(ShortcutGroup.NAVIGATION),
    RENAME(ShortcutGroup.CHAT),
    FORK(ShortcutGroup.CHAT),
    ESCAPE(ShortcutGroup.CHAT),
    NEXT_REGION(ShortcutGroup.NAVIGATION),
    PREVIOUS_REGION(ShortcutGroup.NAVIGATION),
    PAGE_UP(ShortcutGroup.CHAT),
    PAGE_DOWN(ShortcutGroup.CHAT),
    CHAT_START(ShortcutGroup.CHAT),
    CHAT_END(ShortcutGroup.CHAT),
}

/** Where a binding applies. A context also takes the bindings of its [parent]. */
internal enum class KeyContext(val parent: KeyContext?) {
    /** Anywhere in the app's layout. */
    GLOBAL(null),

    /** A chat is the top destination. */
    CHAT(GLOBAL),

    /** Keyboard focus is in the chat's composer. */
    COMPOSER(CHAT),

    /** Keyboard focus is in a code pane (Files or Changes). It acts on the lines there, not on the chat. */
    CODE(GLOBAL);

    fun includes(other: KeyContext): Boolean = generateSequence(this) { it.parent }.any { it == other }
}

/** One key chord for [shortcut]. Ctrl also stands for Meta, which DeX and some keyboards send. */
internal data class KeyBinding(
    val shortcut: Shortcut,
    val key: Key,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val context: KeyContext = KeyContext.GLOBAL,
    @StringRes val labelRes: Int,
)

/**
 * The whole keymap, in the order a shortcut list shows it. Ctrl+K opens the command palette, which
 * also finds sessions of every project; Ctrl+F stays the session list's search. Ctrl+Tab and
 * Ctrl+digits stay free for pinned sessions later. The
 * Book Cover Keyboard needs Fn for F-keys, so each one also has a Ctrl chord. Ctrl+Down stays
 * the next session, so the chat's end is Ctrl+End only.
 */
internal val KEYMAP: List<KeyBinding> =
    listOf(
        KeyBinding(Shortcut.PALETTE, Key.K, ctrl = true, labelRes = R.string.remote_keymap_palette),
        KeyBinding(Shortcut.SEARCH, Key.F, ctrl = true, labelRes = R.string.remote_keymap_search),
        KeyBinding(Shortcut.NEW_SESSION, Key.N, ctrl = true, labelRes = R.string.remote_keymap_new_session),
        KeyBinding(Shortcut.PREVIOUS_SESSION, Key.DirectionUp, ctrl = true, labelRes = R.string.remote_keymap_previous_session),
        KeyBinding(Shortcut.PREVIOUS_SESSION, Key.DirectionUp, alt = true, labelRes = R.string.remote_keymap_previous_session),
        KeyBinding(Shortcut.NEXT_SESSION, Key.DirectionDown, ctrl = true, labelRes = R.string.remote_keymap_next_session),
        KeyBinding(Shortcut.NEXT_SESSION, Key.DirectionDown, alt = true, labelRes = R.string.remote_keymap_next_session),
        KeyBinding(Shortcut.PREVIOUS_PROJECT, Key.PageUp, alt = true, labelRes = R.string.remote_keymap_previous_project),
        KeyBinding(Shortcut.NEXT_PROJECT, Key.PageDown, alt = true, labelRes = R.string.remote_keymap_next_project),
        KeyBinding(Shortcut.TOGGLE_LIST, Key.B, ctrl = true, labelRes = R.string.remote_keymap_toggle_list),
        KeyBinding(Shortcut.REFRESH, Key.R, ctrl = true, labelRes = R.string.remote_keymap_refresh),
        KeyBinding(Shortcut.REFRESH, Key.F5, labelRes = R.string.remote_keymap_refresh),
        KeyBinding(Shortcut.SETTINGS, Key.Comma, ctrl = true, labelRes = R.string.remote_keymap_settings),
        KeyBinding(Shortcut.HELP, Key.Slash, ctrl = true, labelRes = R.string.remote_keymap_help),
        KeyBinding(Shortcut.NEXT_REGION, Key.F6, labelRes = R.string.remote_keymap_next_region),
        KeyBinding(Shortcut.NEXT_REGION, Key.DirectionRight, ctrl = true, alt = true, labelRes = R.string.remote_keymap_next_region),
        KeyBinding(Shortcut.PREVIOUS_REGION, Key.F6, shift = true, labelRes = R.string.remote_keymap_previous_region),
        KeyBinding(Shortcut.PREVIOUS_REGION, Key.DirectionLeft, ctrl = true, alt = true, labelRes = R.string.remote_keymap_previous_region),
        KeyBinding(Shortcut.FOCUS_COMPOSER, Key.L, ctrl = true, context = KeyContext.CHAT, labelRes = R.string.remote_keymap_focus_composer),
        KeyBinding(Shortcut.STOP, Key.Period, ctrl = true, context = KeyContext.CHAT, labelRes = R.string.remote_keymap_stop),
        KeyBinding(Shortcut.ESCAPE, Key.Escape, context = KeyContext.CHAT, labelRes = R.string.remote_keymap_escape),
        KeyBinding(Shortcut.PAGE_UP, Key.PageUp, context = KeyContext.CHAT, labelRes = R.string.remote_keymap_page_up),
        KeyBinding(Shortcut.PAGE_DOWN, Key.PageDown, context = KeyContext.CHAT, labelRes = R.string.remote_keymap_page_down),
        KeyBinding(Shortcut.CHAT_START, Key.MoveHome, ctrl = true, context = KeyContext.CHAT, labelRes = R.string.remote_keymap_chat_start),
        KeyBinding(Shortcut.CHAT_END, Key.MoveEnd, ctrl = true, context = KeyContext.CHAT, labelRes = R.string.remote_keymap_chat_end),
        KeyBinding(Shortcut.CHANGES, Key.G, ctrl = true, shift = true, context = KeyContext.CHAT, labelRes = R.string.remote_keymap_changes),
        KeyBinding(Shortcut.FILES, Key.E, ctrl = true, shift = true, context = KeyContext.CHAT, labelRes = R.string.remote_keymap_files),
        KeyBinding(Shortcut.JOBS, Key.J, ctrl = true, shift = true, context = KeyContext.CHAT, labelRes = R.string.remote_keymap_jobs),
        KeyBinding(Shortcut.HISTORY, Key.H, ctrl = true, context = KeyContext.CHAT, labelRes = R.string.remote_keymap_history),
        KeyBinding(Shortcut.EXPAND_INSPECTOR, Key.B, ctrl = true, shift = true, context = KeyContext.CHAT, labelRes = R.string.remote_keymap_expand_inspector),
        KeyBinding(Shortcut.TOGGLE_INSPECTOR, Key.Backslash, ctrl = true, context = KeyContext.CHAT, labelRes = R.string.remote_keymap_toggle_inspector),
        KeyBinding(Shortcut.REVIEW, Key.D, ctrl = true, shift = true, context = KeyContext.CHAT, labelRes = R.string.remote_keymap_review),
        KeyBinding(Shortcut.RENAME, Key.F2, context = KeyContext.CHAT, labelRes = R.string.remote_keymap_rename),
        KeyBinding(Shortcut.RENAME, Key.R, ctrl = true, shift = true, context = KeyContext.CHAT, labelRes = R.string.remote_keymap_rename),
        KeyBinding(Shortcut.FORK, Key.N, ctrl = true, shift = true, context = KeyContext.CHAT, labelRes = R.string.remote_keymap_fork),
        KeyBinding(Shortcut.OTHER_BUSY_ACTION, Key.Enter, alt = true, context = KeyContext.COMPOSER, labelRes = R.string.remote_keymap_other_busy_action),
        KeyBinding(Shortcut.OTHER_BUSY_ACTION, Key.NumPadEnter, alt = true, context = KeyContext.COMPOSER, labelRes = R.string.remote_keymap_other_busy_action),
        KeyBinding(Shortcut.SELECT_ALL_LINES, Key.A, ctrl = true, context = KeyContext.CODE, labelRes = R.string.remote_code_select_all),
        KeyBinding(Shortcut.COPY_LINES, Key.C, ctrl = true, context = KeyContext.CODE, labelRes = R.string.remote_code_copy_lines),
        KeyBinding(Shortcut.ZOOM_IN, Key.Equals, ctrl = true, context = KeyContext.CODE, labelRes = R.string.remote_code_zoom_in),
        KeyBinding(Shortcut.ZOOM_IN, Key.Equals, ctrl = true, shift = true, context = KeyContext.CODE, labelRes = R.string.remote_code_zoom_in),
        KeyBinding(Shortcut.ZOOM_IN, Key.Plus, ctrl = true, context = KeyContext.CODE, labelRes = R.string.remote_code_zoom_in),
        KeyBinding(Shortcut.ZOOM_IN, Key.Plus, ctrl = true, shift = true, context = KeyContext.CODE, labelRes = R.string.remote_code_zoom_in),
        KeyBinding(Shortcut.ZOOM_IN, Key.NumPadAdd, ctrl = true, context = KeyContext.CODE, labelRes = R.string.remote_code_zoom_in),
        KeyBinding(Shortcut.ZOOM_OUT, Key.Minus, ctrl = true, context = KeyContext.CODE, labelRes = R.string.remote_code_zoom_out),
        KeyBinding(Shortcut.ZOOM_OUT, Key.NumPadSubtract, ctrl = true, context = KeyContext.CODE, labelRes = R.string.remote_code_zoom_out),
    )

/** The shortcuts a focused code pane takes itself: they select, copy and zoom its lines. */
internal val CODE_SHORTCUTS: Set<Shortcut> = setOf(Shortcut.SELECT_ALL_LINES, Shortcut.COPY_LINES, Shortcut.ZOOM_IN, Shortcut.ZOOM_OUT)

/** The code pane shortcut [event] is, or null. Only a code pane with focus asks for these. */
internal fun resolveCodeShortcut(event: KeyEvent): Shortcut? =
    resolveShortcut(event, KeyContext.CODE)?.takeIf { it in CODE_SHORTCUTS }

/**
 * The shortcut [key] with exactly these modifiers runs in [context], or null. Callers pass Meta
 * as [ctrl] (see the [KeyEvent] overload).
 */
internal fun resolveShortcut(key: Key, ctrl: Boolean, alt: Boolean, shift: Boolean, context: KeyContext): Shortcut? =
    KEYMAP.firstOrNull {
        it.key == key && it.ctrl == ctrl && it.alt == alt && it.shift == shift && context.includes(it.context)
    }?.shortcut

internal fun resolveShortcut(event: KeyEvent, context: KeyContext): Shortcut? {
    val ctrl = event.isCtrlPressed || event.isMetaPressed
    return resolveShortcut(event.key, ctrl, event.isAltPressed, event.isShiftPressed, context)
        ?: typedShortcut(typedWithCtrl(event), ctrl, event.isAltPressed)
}

/**
 * A shortcut named after the character its key types rather than the key: Ctrl with whatever
 * types "/" opens the help, also where "/" is Shift+7 (German) and not the slash key.
 */
internal fun typedShortcut(typed: Int, ctrl: Boolean, alt: Boolean): Shortcut? =
    if (ctrl && !alt && typed == '/'.code) Shortcut.HELP else null

/** The character [event]'s key types with its modifiers but Ctrl and Meta; 0 for none. */
private fun typedWithCtrl(event: KeyEvent): Int {
    val native = event.nativeKeyEvent
    val ctrlMeta = android.view.KeyEvent.META_CTRL_MASK or android.view.KeyEvent.META_META_MASK
    return runCatching { native.getUnicodeChar(native.metaState and ctrlMeta.inv()) }.getOrDefault(0)
}

/**
 * The keys of review mode ([TwoPaneState.reviewing]). They are single letters, so unlike [KEYMAP]
 * they act only while review mode is on and no text field has focus; then they never reach the
 * composer.
 */
internal enum class ReviewKey {
    NEXT_FILE,
    PREVIOUS_FILE,
    NEXT_HUNK,
    PREVIOUS_HUNK,
    COMMENT,
    USE_REVIEW,
}

/** The review key [key] is without any modifier: n/p files, j/k changes, c comment, Enter. */
internal fun reviewKey(key: Key, ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean = false): ReviewKey? {
    if (ctrl || alt || shift || meta) return null
    return when (key) {
        Key.N -> ReviewKey.NEXT_FILE
        Key.P -> ReviewKey.PREVIOUS_FILE
        Key.J -> ReviewKey.NEXT_HUNK
        Key.K -> ReviewKey.PREVIOUS_HUNK
        Key.C -> ReviewKey.COMMENT
        Key.Enter, Key.NumPadEnter -> ReviewKey.USE_REVIEW
        else -> null
    }
}

internal fun reviewKey(event: KeyEvent): ReviewKey? =
    reviewKey(event.key, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, event.isMetaPressed)

/** How long a first Esc waits for the second one that stops pi. */
internal const val ESCAPE_STOP_WINDOW_MILLIS = 1500L

/** What one Esc press does to a running chat. */
internal enum class EscAction {
    /** The first press: show "Esc again to stop". */
    HINT,

    /** The second press within [ESCAPE_STOP_WINDOW_MILLIS]: stop pi. */
    STOP,

    /** Nothing runs. */
    NONE,
}

/** Esc, Esc stops a run as in the pi TUI; one press alone never does. */
internal class EscapeGesture(private val windowMillis: Long = ESCAPE_STOP_WINDOW_MILLIS) {
    private var firstPress: Long? = null

    fun press(nowMillis: Long, running: Boolean): EscAction {
        val first = firstPress
        if (!running) {
            firstPress = null
            return EscAction.NONE
        }
        if (first != null && nowMillis - first in 0..windowMillis) {
            firstPress = null
            return EscAction.STOP
        }
        firstPress = nowMillis
        return EscAction.HINT
    }

    /** Another key or step came between: the next Esc starts over. */
    fun reset() {
        firstPress = null
    }
}

/** The one thing an Esc in a chat does, closest first. */
internal enum class EscapeStep {
    /** Review mode ends and the panes get their widths back, before anything else. */
    LEAVE_REVIEW,

    /** Slash suggestions or the prompt prediction. Menus and dialogs are windows of their own. */
    CLOSE_TRANSIENT,

    /** A text selection in the chat or a line selection in Files. */
    CLEAR_SELECTION,

    /**
     * The topmost pane, as Back closes it: the inspector's active tab beside the chat, or the
     * tool detail, Files, Changes or jobs over it.
     */
    CLOSE_PANE,

    /** Leaves the composer; the draft stays. */
    BLUR_COMPOSER,

    /** While pi runs: [EscapeGesture]. */
    STOP_GESTURE,

    /** The key is used up, so Android never turns it into Back. */
    NOTHING,
}

/**
 * The step for an Esc in a chat. A focused composer with nothing typed stays focused while pi
 * runs, so Esc, Esc stops right after a chat opened (which focuses the composer).
 */
internal fun escapeStep(
    transientOpen: Boolean,
    selectionActive: Boolean,
    paneOpen: Boolean,
    composerFocused: Boolean,
    composerBlank: Boolean,
    running: Boolean,
    reviewing: Boolean = false,
): EscapeStep =
    when {
        reviewing -> EscapeStep.LEAVE_REVIEW
        transientOpen -> EscapeStep.CLOSE_TRANSIENT
        selectionActive -> EscapeStep.CLEAR_SELECTION
        paneOpen -> EscapeStep.CLOSE_PANE
        composerFocused && (!running || !composerBlank) -> EscapeStep.BLUR_COMPOSER
        running -> EscapeStep.STOP_GESTURE
        else -> EscapeStep.NOTHING
    }

/**
 * The text a key types when nothing took it: a plain printable character, or null for keys with
 * Ctrl, Alt or Meta, control characters and dead keys (whose accent waits for the next key).
 * [altGr] is a right Alt alone, which types @, { or € on layouts such as German QWERTZ.
 */
internal fun typedText(unicodeChar: Int, ctrl: Boolean, alt: Boolean, meta: Boolean, altGr: Boolean = false): String? {
    if (ctrl || (alt && !altGr) || meta || unicodeChar == 0) return null
    if (unicodeChar and KeyCharacterMap.COMBINING_ACCENT != 0) return null
    if (Character.isISOControl(unicodeChar) || !Character.isValidCodePoint(unicodeChar)) return null
    return String(Character.toChars(unicodeChar))
}

/** The meta state holds the right Alt (AltGr) without the left one. */
internal fun altGrOnly(metaState: Int): Boolean =
    metaState and android.view.KeyEvent.META_ALT_RIGHT_ON != 0 &&
        metaState and android.view.KeyEvent.META_ALT_LEFT_ON == 0

/** TalkBack or another touch exploration service runs; focus then only moves where the user puts it. */
internal fun touchExplorationEnabled(context: Context): Boolean =
    context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

/**
 * A chat's side of the keyboard: the chat screen and its composer fill it in, and the layout's
 * key handlers run Esc, typing and the chat's shortcuts through it ([LocalChatKeyTarget]).
 */
@Stable
internal class ChatKeyTarget {
    /** pi runs or waits for an answer here, and the stop the composer offers is enabled. */
    var running = false

    /** The stop the composer's button runs, including the child-stop confirmation. */
    var stop: () -> Unit = {}

    /** The first Esc of [EscapeGesture] showed "Esc again to stop"; the composer shows it. */
    var stopHint by mutableStateOf(false)

    val escape = EscapeGesture()

    var composerFocused = false
    var composerBlank = true

    /**
     * The question's free-text field that has focus ([rememberQuestionFieldKeys]); Esc leaves it
     * like the composer, and [questionFieldBlank] says whether it holds an answer.
     */
    private var questionField: Any? = null
    var questionFieldBlank = true
        private set
    val questionFieldFocused: Boolean get() = questionField != null

    /** The question field [owner] gained or lost focus, or its text changed while focused. */
    fun questionFieldFocus(owner: Any, focused: Boolean, blank: Boolean) {
        if (focused) {
            questionField = owner
            questionFieldBlank = blank
        } else if (questionField === owner) {
            questionField = null
            questionFieldBlank = true
        }
    }

    /**
     * A text field with text has focus, the composer or a question's field: text-editing chords
     * such as Ctrl+Home and Ctrl+End are its own.
     */
    val editingText: Boolean get() = (composerFocused && !composerBlank) || (questionFieldFocused && !questionFieldBlank)

    /**
     * Files, Changes, a tool detail or the jobs lie over the chat, hiding the composer: typed
     * keys must not land in it.
     */
    var composerCovered = false

    /** The slash suggestions under the composer show; [SessionControls] reports it. */
    var slashShown = false

    /** The highlighted slash suggestion, which Tab or Enter runs ([composerKey]). */
    var slashHighlight by mutableIntStateOf(0)

    /** The slash suggestions' command names in list order; [SessionControls] reports them. */
    var slashNames: List<String> = emptyList()

    /** Runs the suggestion at an index of [slashNames], as a tap on it does. */
    var runSlash: (Int) -> Unit = {}

    /** Slash suggestions or the prompt prediction show; [closeTransient] closes them. */
    var transientOpen: () -> Boolean = { false }
    var closeTransient: () -> Unit = {}

    /** A text selection in the chat or a line selection in Files; [clearSelection] clears it. */
    var selectionActive: () -> Boolean = { false }
    var clearSelection: () -> Unit = {}

    var focusComposer: () -> Unit = {}
    var blurComposer: () -> Unit = {}

    /** Focuses the composer and types [text] at its cursor; never while [composerCovered]. */
    var type: (String) -> Unit = {}

    /**
     * Text went into the composer's draft from outside the field (a drop): dictation stops, so
     * it cannot overwrite that text.
     */
    var draftEdited: () -> Unit = {}

    /** Runs a chat shortcut ([KeyContext.CHAT]); false when it does not apply now. */
    var shortcut: (Shortcut) -> Boolean = { false }

    /** Runs a row the command palette chose in this chat, as the chat's own control would. */
    var perform: (ChatCommand) -> Unit = {}

    /** Review mode is on ([TwoPaneState.reviewing]): Esc leaves it first, and [ReviewKey]s act. */
    var reviewing: () -> Boolean = { false }
    var leaveReview: () -> Unit = {}

    /**
     * A printable key review mode does not use: review mode ends, so the chat comes back from
     * its strip, and the composer gets focus. False where that does not uncover the composer.
     */
    var leaveReviewToType: () -> Boolean = { false }

    /** The Changes pane's handler of review keys while it shows; false when a key did nothing. */
    private var reviewHandler: ((ReviewKey) -> Boolean)? = null

    fun addReviewHandler(handler: (ReviewKey) -> Boolean) {
        reviewHandler = handler
    }

    fun removeReviewHandler(handler: (ReviewKey) -> Boolean) {
        if (reviewHandler === handler) reviewHandler = null
    }

    /**
     * A review key nothing on screen took: used up by review mode, also where it did nothing, so
     * it never types into the composer. False outside review mode or with a text field focused.
     */
    fun reviewKey(key: ReviewKey): Boolean {
        if (!reviewing() || composerFocused || questionFieldFocused) return false
        reviewHandler?.invoke(key)
        return true
    }

    /** The open panes' Back actions, oldest first, as [PaneBackHandler] registered them. */
    private val panes = mutableListOf<() -> Unit>()

    fun addPane(close: () -> Unit) {
        panes += close
    }

    fun removePane(close: () -> Unit) {
        panes.remove(close)
    }

    val paneOpen: Boolean get() = panes.isNotEmpty()

    /** Runs the newest pane's Back action, as Back would; false without an open pane. */
    fun closeTopPane(): Boolean {
        val close = panes.lastOrNull() ?: return false
        close()
        return true
    }

    /** One Esc: the first [EscapeStep] that applies. Always used up, so it never becomes Back. */
    fun escape(nowMillis: Long) {
        val fieldFocused = composerFocused || questionFieldFocused
        val fieldBlank = if (questionFieldFocused) questionFieldBlank else composerBlank
        val step = escapeStep(transientOpen(), selectionActive(), paneOpen, fieldFocused, fieldBlank, running, reviewing())
        if (step != EscapeStep.STOP_GESTURE) {
            escape.reset()
            stopHint = false
        }
        when (step) {
            EscapeStep.LEAVE_REVIEW -> leaveReview()
            EscapeStep.CLOSE_TRANSIENT -> closeTransient()
            EscapeStep.CLEAR_SELECTION -> clearSelection()
            EscapeStep.CLOSE_PANE -> closeTopPane()
            EscapeStep.BLUR_COMPOSER -> blurComposer()
            EscapeStep.STOP_GESTURE ->
                when (escape.press(nowMillis, running)) {
                    EscAction.HINT -> stopHint = true
                    EscAction.STOP -> {
                        stopHint = false
                        stop()
                    }
                    EscAction.NONE -> stopHint = false
                }
            EscapeStep.NOTHING -> Unit
        }
    }

    /** Ctrl+.: stops at once. */
    fun stopNow(): Boolean {
        escape.reset()
        stopHint = false
        if (!running) return false
        stop()
        return true
    }
}

/**
 * A question's free-text field holding [text]: tells the chat's keys when it has focus, so Esc
 * leaves it first, as it leaves the composer, before Esc, Esc stops pi.
 */
@Composable
internal fun rememberQuestionFieldKeys(text: String): Modifier {
    val target = LocalChatKeyTarget.current ?: return Modifier
    val owner = remember { QuestionFieldOwner() }
    val blank = text.isBlank()
    SideEffect { if (owner.focused) target.questionFieldFocus(owner, focused = true, blank = blank) }
    DisposableEffect(target, owner) { onDispose { target.questionFieldFocus(owner, focused = false, blank = true) } }
    return Modifier.onFocusChanged {
        owner.focused = it.isFocused
        target.questionFieldFocus(owner, it.isFocused, blank)
    }
}

private class QuestionFieldOwner {
    var focused = false
}

/** The chat's [ChatKeyTarget] around the chat screen; null elsewhere. */
internal val LocalChatKeyTarget = staticCompositionLocalOf<ChatKeyTarget?> { null }

/**
 * Back for a pane over or beside the chat (a tool detail, Files, Changes, jobs, history). Esc
 * runs the same action through [LocalChatKeyTarget], newest pane first like Back.
 */
@Composable
internal fun PaneBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
    val target = LocalChatKeyTarget.current ?: return
    val latest by rememberUpdatedState(onBack)
    DisposableEffect(target, enabled) {
        val close = { latest() }
        if (enabled) target.addPane(close)
        onDispose { target.removePane(close) }
    }
}

/**
 * What the layout's shortcuts reach in either layout: the session list's search field and order,
 * and the chats on screen. The screens fill it in; [RemoteNavigation] provides it.
 */
internal class ShortcutTargets {
    /** The panes F6 and Ctrl+Alt+Left/Right move between. */
    val regions = FocusRegions()

    /** The Ctrl+/ list shows, with the keys of this context; null while it is closed. */
    var help by mutableStateOf<KeyContext?>(null)

    /** The command palette's mode while it is open; null while it is closed. */
    var palette by mutableStateOf<PaletteMode?>(null)

    fun openPalette(mode: PaletteMode) {
        palette = mode
    }

    /** Ctrl+F asks the session list to focus its search field; the list handles each request once. */
    var searchRequests by mutableIntStateOf(0)
    var searchHandled by mutableIntStateOf(0)

    fun requestSearch() {
        searchRequests++
    }

    /**
     * The sessions the list of [listedFor] shows, top to bottom, for Ctrl/Alt+Up/Down, while that
     * list is on screen. Without it the shortcuts use the project's sessions as the list orders them.
     */
    var listedFor: RemoteNavKey.Sessions? = null
        private set
    var listedSessionIds: List<String> = emptyList()
        private set

    fun listed(sessions: RemoteNavKey.Sessions, ids: List<String>) {
        listedFor = sessions
        listedSessionIds = ids
    }

    /** The list of [sessions] left the screen; its rows may change without it. */
    fun unlisted(sessions: RemoteNavKey.Sessions) {
        if (listedFor != sessions) return
        listedFor = null
        listedSessionIds = emptyList()
    }

    /** The "hide offline sessions" setting, which the session list applies. */
    var hideOfflineSessions = false

    private val refreshers = mutableMapOf<RemoteNavKey, () -> Unit>()

    /** What Ctrl+R and F5 refresh while [key] is on top. */
    fun refresher(key: RemoteNavKey): (() -> Unit)? = refreshers[key]

    fun registerRefresh(key: RemoteNavKey, refresh: () -> Unit) {
        refreshers[key] = refresh
    }

    fun unregisterRefresh(key: RemoteNavKey, refresh: () -> Unit) {
        if (refreshers[key] === refresh) refreshers.remove(key)
    }

    private val chats = mutableMapOf<RemoteNavKey.Chat, ChatKeyTarget>()

    fun chat(key: RemoteNavKey.Chat): ChatKeyTarget? = chats[key]

    fun register(key: RemoteNavKey.Chat, target: ChatKeyTarget) {
        chats[key] = target
    }

    fun unregister(key: RemoteNavKey.Chat, target: ChatKeyTarget) {
        if (chats[key] === target) chats.remove(key)
    }
}

internal val LocalShortcutTargets = staticCompositionLocalOf<ShortcutTargets?> { null }
