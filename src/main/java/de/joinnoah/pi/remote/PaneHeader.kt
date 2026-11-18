package de.joinnoah.pi.remote

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One prompt or choice in a [PaneAction]'s menu. A non-null [selected] makes it one of several
 * choices (such as a browser tab), checked while selected; [close] adds a close button at its end.
 */
internal class PaneMenuItem(
    val label: String,
    val tag: String,
    val selected: Boolean? = null,
    val close: PaneMenuClose? = null,
    val onClick: () -> Unit,
)

/** The close button of a [PaneMenuItem]: [label] is its spoken name, [tag] its test tag. */
internal class PaneMenuClose(val label: String, val tag: String, val onClick: () -> Unit)

/**
 * A button in a [PaneHeader] beside its refresh button. With [checked] it is a toggle; with
 * [items] it opens a menu of them instead of running [onClick]. Where the header is too narrow
 * for every button, they move into one "more" menu, the toggles with a check mark.
 */
internal class PaneAction(
    val label: String,
    val icon: ImageVector,
    val tag: String,
    val checked: Boolean? = null,
    val shortcut: Shortcut? = null,
    val items: List<PaneMenuItem> = emptyList(),
    val enabled: Boolean = true,
    val onClick: () -> Unit = {},
)

/** Room the title keeps before the header's [PaneAction]s move into the "more" menu. */
private val PANE_TITLE_MIN_ROOM = 120.dp
private val PANE_PILL_GAP = 12.dp

/**
 * Wrap the title's content, reserving room for the separate refresh pill even on narrow panes.
 * [actions] join the refresh button in its pill.
 */
@Composable
internal fun PaneHeader(
    title: String,
    subtitle: String?,
    nested: Boolean,
    busy: Boolean,
    tag: String,
    backLabel: String,
    closeLabel: String,
    reloadLabel: String,
    onBack: () -> Unit,
    onClose: () -> Unit,
    /** Null leaves the refresh button out, for a page that has nothing to reload. */
    onReload: (() -> Unit)?,
    actions: List<PaneAction> = emptyList(),
    /** Takes the title's focus, so TalkBack lands on it when the header opens. */
    titleFocus: FocusRequester? = null,
    /** False greys out the back or close button, for a step that must not be left halfway. */
    leaveEnabled: Boolean = true,
    /**
     * A short pane: the header is exactly one 48dp row, the pills without padding above and
     * below. The subtitle shares the title's line, both cut off with an ellipsis, and actions that
     * do not fit go into the "more" menu as they always do.
     */
    compact: Boolean = false,
    /** False where Esc does not leave the pane: the back or close button's tooltip then names no key. */
    escapeLeaves: Boolean = true,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 0.dp else 8.dp)) {
        val hasTrailingPill = onReload != null || actions.isNotEmpty()
        val reloadWidth = if (onReload == null) 0.dp else 48.dp
        // Leading padding, back/close button, title gap and trailing padding: 8 + 48 + 12 + 20.
        val reserved = 88.dp + reloadWidth + (if (nested) 48.dp else 0.dp) +
            (if (hasTrailingPill) PANE_PILL_GAP else 0.dp)
        // Every action as a button of its own, or all of them behind one "more" button.
        val inline = actions.isNotEmpty() && maxWidth - reserved - 48.dp * actions.size >= PANE_TITLE_MIN_ROOM
        val actionsWidth = if (actions.isEmpty()) 0.dp else 48.dp * (if (inline) actions.size else 1)
        val textWidth = (maxWidth - reserved - actionsWidth).coerceAtLeast(0.dp)
        Row(
            Modifier.fillMaxWidth().then(if (compact) Modifier.height(48.dp) else Modifier),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FloatingSurface(Modifier.weight(1f, fill = false).testTag("${tag}HeaderPill"), shape = CircleShape) {
                Row(Modifier.padding(start = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Esc runs the same step as this button (see PaneBackHandler), where it leaves the pane.
                    ShortcutTooltip(if (nested) backLabel else closeLabel, Shortcut.ESCAPE.takeIf { escapeLeaves }) {
                        IconButton(onClick = if (nested) onBack else onClose, enabled = leaveEnabled, modifier = Modifier.size(48.dp).testTag(if (nested) "${tag}Back" else "${tag}Close")) {
                            Icon(if (nested) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close, if (nested) backLabel else closeLabel)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    val titleText: @Composable (Modifier) -> Unit = { widthModifier ->
                        Text(
                            title,
                            widthModifier
                                .then(if (titleFocus == null) Modifier else Modifier.focusRequester(titleFocus).focusable())
                                .testTag("${tag}Title").semantics { heading() },
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val subtitleText: @Composable (String, Modifier) -> Unit = { text, widthModifier ->
                        Text(text, widthModifier.testTag("${tag}Subtitle"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = if (nested) FontFamily.Monospace else null,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (compact && subtitle != null)
                        // One line: the title keeps at most 60% of it, the subtitle takes the rest.
                        Row(Modifier.widthIn(max = textWidth), verticalAlignment = Alignment.CenterVertically) {
                            val titleMin = minOf(48.dp, textWidth)
                            titleText(Modifier.widthIn(min = titleMin, max = maxOf(titleMin, textWidth * 0.6f)))
                            Spacer(Modifier.width(8.dp))
                            subtitleText(subtitle, Modifier.weight(1f, fill = false))
                        }
                    else
                        Column(Modifier.widthIn(max = textWidth)) {
                            titleText(Modifier.widthIn(min = minOf(48.dp, textWidth)))
                            if (subtitle != null) subtitleText(subtitle, Modifier)
                        }
                    if (nested)
                        ShortcutTooltip(closeLabel) {
                            IconButton(onClick = onClose, modifier = Modifier.size(48.dp).testTag("${tag}Close"), enabled = leaveEnabled) {
                                Icon(Icons.Default.Close, closeLabel)
                            }
                        }
                }
            }
            if (hasTrailingPill) Spacer(Modifier.width(PANE_PILL_GAP))
            if (hasTrailingPill)
            FloatingSurface(Modifier.testTag("${tag}ReloadPill"), shape = CircleShape) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (inline) actions.forEach { InlineAction(it) }
                    else if (actions.isNotEmpty()) OverflowActions(actions, tag)
                    if (onReload != null)
                        ShortcutTooltip(reloadLabel) {
                            IconButton(onClick = onReload, enabled = !busy, modifier = Modifier.size(48.dp).testTag("${tag}Reload")) {
                                Icon(Icons.Default.Refresh, reloadLabel)
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun InlineAction(action: PaneAction) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ShortcutTooltip(action.label, action.shortcut) {
            val checked = action.checked
            if (checked != null)
                IconToggleButton(checked, { action.onClick() }, Modifier.size(48.dp).testTag(action.tag)) {
                    Icon(action.icon, action.label)
                }
            else
                IconButton(
                    onClick = { if (action.items.isNotEmpty()) menu = true else action.onClick() },
                    enabled = action.enabled,
                    modifier = Modifier.size(48.dp).testTag(action.tag),
                ) {
                    Icon(action.icon, action.label)
                }
        }
        if (action.items.isNotEmpty())
            GlassDropdownMenu(menu, onDismissRequest = { menu = false }) {
                action.items.forEach { item -> MenuEntry(item) { menu = false } }
            }
    }
}

@Composable
private fun MenuEntry(item: PaneMenuItem, dismiss: () -> Unit) {
    val selected = item.selected
    val close = item.close
    DropdownMenuItem(
        text = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = {
            dismiss()
            item.onClick()
        },
        modifier = Modifier.testTag(item.tag).then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier),
        leadingIcon = if (selected != null) { { if (selected) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) } } else null,
        trailingIcon = if (close != null) {
            {
                IconButton(
                    onClick = {
                        dismiss()
                        close.onClick()
                    },
                    modifier = Modifier.size(48.dp).testTag(close.tag),
                ) {
                    Icon(Icons.Default.Close, close.label, Modifier.size(18.dp))
                }
            }
        } else null,
    )
}

/** Every action in one menu; an action with items of its own shows them in the menu's place. */
@Composable
private fun OverflowActions(actions: List<PaneAction>, tag: String) {
    var open by remember { mutableStateOf(false) }
    var submenu by remember { mutableStateOf<PaneAction?>(null) }
    val label = stringResource(R.string.remote_review_more_actions)
    fun dismiss() {
        open = false
        submenu = null
    }
    Box {
        ShortcutTooltip(label) {
            IconButton(onClick = { open = true }, Modifier.size(48.dp).testTag("${tag}MoreActions")) {
                Icon(Icons.Default.MoreVert, label)
            }
        }
        GlassDropdownMenu(open, onDismissRequest = ::dismiss) {
            val shown = submenu
            if (shown != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remote_review_menu_back)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
                    onClick = { submenu = null },
                    modifier = Modifier.testTag("${tag}MoreActionsBack"),
                )
                HorizontalDivider()
                shown.items.forEach { item -> MenuEntry(item, ::dismiss) }
            } else
                actions.forEach { action ->
                    val checked = action.checked
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        leadingIcon = { Icon(action.icon, null) },
                        trailingIcon = if (checked == true) { { Icon(Icons.Default.Check, null) } } else null,
                        enabled = action.enabled,
                        onClick = {
                            if (action.items.isNotEmpty()) submenu = action
                            else {
                                dismiss()
                                action.onClick()
                            }
                        },
                        modifier =
                            Modifier.testTag(action.tag)
                                .then(
                                    if (checked != null)
                                        Modifier.semantics {
                                            role = Role.Checkbox
                                            toggleableState = ToggleableState(checked)
                                        }
                                    else Modifier
                                ),
                    )
                }
        }
    }
}
