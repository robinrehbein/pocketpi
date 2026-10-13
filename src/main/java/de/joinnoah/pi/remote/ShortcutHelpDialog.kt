package de.joinnoah.pi.remote

import android.content.Context
import android.content.res.Resources
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A key with its modifiers, as the shortcut lists show it. Ctrl also stands for Meta. */
internal data class KeyChord(val key: Key, val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false)

internal val KeyBinding.chord: KeyChord get() = KeyChord(key, ctrl, alt, shift)

/** One line of a shortcut list: what [labelRes] says, its keys, and where it applies. */
internal data class ShortcutEntry(
    @StringRes val labelRes: Int,
    val group: ShortcutGroup,
    val context: KeyContext,
    val chords: List<KeyChord>,
)

/**
 * Keys that act inside the composer or a file list rather than through [KEYMAP] (see
 * [composerEnter], [composerKey], [openOnRight] and [parentOnLeft]); the lists show them too.
 */
private val FIELD_KEYS: List<ShortcutEntry> =
    listOf(
        ShortcutEntry(R.string.remote_shortcut_enter_sends, ShortcutGroup.COMPOSER, KeyContext.COMPOSER, listOf(KeyChord(Key.Enter))),
        ShortcutEntry(R.string.remote_shortcut_send, ShortcutGroup.COMPOSER, KeyContext.COMPOSER, listOf(KeyChord(Key.Enter, ctrl = true))),
        ShortcutEntry(R.string.remote_shortcut_new_line, ShortcutGroup.COMPOSER, KeyContext.COMPOSER, listOf(KeyChord(Key.Enter, shift = true))),
        ShortcutEntry(
            R.string.remote_shortcut_suggestions,
            ShortcutGroup.COMPOSER,
            KeyContext.COMPOSER,
            listOf(KeyChord(Key.DirectionUp), KeyChord(Key.DirectionDown)),
        ),
        ShortcutEntry(R.string.remote_shortcut_complete, ShortcutGroup.COMPOSER, KeyContext.COMPOSER, listOf(KeyChord(Key.Tab))),
        ShortcutEntry(R.string.remote_shortcut_take_prediction, ShortcutGroup.COMPOSER, KeyContext.COMPOSER, listOf(KeyChord(Key.DirectionRight))),
        ShortcutEntry(R.string.remote_shortcut_open_file, ShortcutGroup.PANES, KeyContext.CHAT, listOf(KeyChord(Key.DirectionRight))),
        ShortcutEntry(
            R.string.remote_shortcut_parent,
            ShortcutGroup.PANES,
            KeyContext.CHAT,
            listOf(KeyChord(Key.Backspace), KeyChord(Key.DirectionLeft)),
        ),
    )

/**
 * What the shortcut lists show for [context], one entry per action in [KEYMAP] order followed by
 * the field keys; null shows every context. The chat's list uses [KeyContext.COMPOSER], so it also
 * names the composer's keys.
 */
internal fun shortcutEntries(context: KeyContext?): List<ShortcutEntry> {
    val bound =
        KEYMAP.filter { it.shortcut.listed && (context == null || context.includes(it.context)) }
            .groupBy { it.shortcut }
            .map { (shortcut, bindings) ->
                val first = bindings.first()
                ShortcutEntry(first.labelRes, shortcut.group, first.context, bindings.map { it.chord })
            }
    return bound + FIELD_KEYS.filter { context == null || context.includes(it.context) }
}

/** [shortcutEntries] in their groups, in [ShortcutGroup] order, leaving out empty groups. */
internal fun shortcutGroups(context: KeyContext?): List<Pair<ShortcutGroup, List<ShortcutEntry>>> {
    val entries = shortcutEntries(context)
    return ShortcutGroup.entries.mapNotNull { group -> entries.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { group to it } }
}

/** The name of [key] on a keyboard key cap, such as "G", "F6", "Esc" or (German) "Bild auf". */
internal fun keyName(key: Key, resources: Resources): String {
    val code = key.nativeKeyCode
    return when {
        code in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z ->
            ('A' + (code - android.view.KeyEvent.KEYCODE_A)).toString()
        code in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 ->
            ('0' + (code - android.view.KeyEvent.KEYCODE_0)).toString()
        code in android.view.KeyEvent.KEYCODE_F1..android.view.KeyEvent.KEYCODE_F12 ->
            "F" + (code - android.view.KeyEvent.KEYCODE_F1 + 1)
        else ->
            when (key) {
                Key.Comma -> ","
                Key.Period -> "."
                Key.Slash -> "/"
                Key.Backslash -> "\\"
                Key.DirectionUp -> "↑"
                Key.DirectionDown -> "↓"
                Key.DirectionLeft -> "←"
                Key.DirectionRight -> "→"
                Key.Enter, Key.NumPadEnter -> resources.getString(R.string.remote_key_enter)
                Key.Escape -> resources.getString(R.string.remote_key_escape)
                Key.Tab -> resources.getString(R.string.remote_key_tab)
                Key.Backspace -> resources.getString(R.string.remote_key_backspace)
                Key.PageUp -> resources.getString(R.string.remote_key_page_up)
                Key.PageDown -> resources.getString(R.string.remote_key_page_down)
                Key.MoveHome -> resources.getString(R.string.remote_key_home)
                Key.MoveEnd -> resources.getString(R.string.remote_key_end)
                else -> android.view.KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
            }
    }
}

/** [chord] as the keyboard's labels name it, such as "Ctrl+Shift+G" or (German) "Strg+Umschalt+G". */
internal fun chordText(chord: KeyChord, resources: Resources): String =
    buildList {
        if (chord.ctrl) add(resources.getString(R.string.remote_key_ctrl))
        if (chord.alt) add(resources.getString(R.string.remote_key_alt))
        if (chord.shift) add(resources.getString(R.string.remote_key_shift))
        add(keyName(chord.key, resources))
    }.joinToString("+")

/** The chord a tooltip names for [shortcut]: a Ctrl chord where there is one, as F-keys need Fn. */
internal fun tooltipChord(shortcut: Shortcut): KeyChord? {
    val bindings = KEYMAP.filter { it.shortcut == shortcut }
    return (bindings.firstOrNull { it.ctrl } ?: bindings.firstOrNull())?.chord
}

/**
 * Every listed shortcut for the system's keyboard shortcut helper (Meta+/), in the groups the
 * in-app lists use, with labels in [context]'s language.
 */
internal fun keyboardShortcutGroups(context: Context): List<KeyboardShortcutGroup> =
    shortcutGroups(null).map { (group, entries) ->
        KeyboardShortcutGroup(
            context.getString(group.titleRes),
            entries.flatMap { entry ->
                val label = context.getString(entry.labelRes)
                entry.chords.map { KeyboardShortcutInfo(label, it.key.nativeKeyCode, it.metaState()) }
            },
        )
    }

private fun KeyChord.metaState(): Int =
    (if (ctrl) android.view.KeyEvent.META_CTRL_ON else 0) or
        (if (alt) android.view.KeyEvent.META_ALT_ON else 0) or
        (if (shift) android.view.KeyEvent.META_SHIFT_ON else 0)

/** The shortcuts of [context] (null: all of them) in their groups; Ctrl+/ and Settings show it. */
@Composable
internal fun ShortcutList(context: KeyContext?, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((group, entries) in shortcutGroups(context)) {
            Text(
                stringResource(group.titleRes),
                Modifier.padding(top = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
            )
            for (entry in entries)
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        entry.chords.joinToString(", ") { chordText(it, resources) },
                        Modifier.width(132.dp).padding(end = 12.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(entry.labelRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}

/** Ctrl+/: the keys that work where the user is, the chat's or the lists' ones. */
@Composable
internal fun ShortcutHelpDialog(context: KeyContext, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_shortcut_help_title)) },
        text = {
            ShortcutList(
                // The chat's list includes the composer's keys.
                if (context == KeyContext.GLOBAL) KeyContext.GLOBAL else KeyContext.COMPOSER,
                Modifier.verticalScroll(rememberScrollState()).testTag("shortcutHelp"),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("shortcutHelpClose")) {
                Text(stringResource(R.string.remote_shortcut_help_close))
            }
        },
    )
}

/**
 * A tooltip on [content] (an icon button) that names it on hover, S Pen hover or long press: its
 * [label], and with a hardware keyboard the [chord] that does the same (by default the one of
 * [shortcut]), as "Changes · Ctrl+Shift+G".
 */
@Composable
internal fun ShortcutTooltip(
    label: String,
    shortcut: Shortcut? = null,
    chord: KeyChord? = shortcut?.let(::tooltipChord),
    content: @Composable () -> Unit,
) {
    val resources = LocalResources.current
    val keys = chord?.takeIf { LocalHardwareKeyboard.current }?.let { chordText(it, resources) }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = {
            PlainTooltip {
                Text(if (keys == null) label else stringResource(R.string.remote_shortcut_tooltip, label, keys))
            }
        },
        state = rememberTooltipState(),
        content = content,
    )
}
