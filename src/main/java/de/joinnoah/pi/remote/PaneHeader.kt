package de.joinnoah.pi.remote

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One prompt or choice in a [PaneAction]'s menu. */
internal class PaneMenuItem(val label: String, val tag: String, val onClick: () -> Unit)

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
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val reloadWidth = if (onReload == null) 0.dp else 60.dp
        val reserved = 56.dp + 28.dp + reloadWidth + if (nested) 48.dp else 0.dp
        // Every action as a button of its own, or all of them behind one "more" button.
        val inline = actions.isNotEmpty() && maxWidth - reserved - 48.dp * actions.size >= PANE_TITLE_MIN_ROOM
        val actionsWidth = if (actions.isEmpty()) 0.dp else 48.dp * (if (inline) actions.size else 1)
        val textWidth = (maxWidth - reserved - actionsWidth).coerceAtLeast(0.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FloatingSurface(Modifier.testTag("${tag}HeaderPill"), shape = CircleShape) {
                Row(Modifier.padding(start = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Esc runs the same step as this button (see PaneBackHandler).
                    ShortcutTooltip(if (nested) backLabel else closeLabel, Shortcut.ESCAPE) {
                        IconButton(onClick = if (nested) onBack else onClose, enabled = leaveEnabled, modifier = Modifier.size(48.dp).testTag(if (nested) "${tag}Back" else "${tag}Close")) {
                            Icon(if (nested) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close, if (nested) backLabel else closeLabel)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.widthIn(max = textWidth)) {
                        Text(
                            title,
                            Modifier.widthIn(min = minOf(48.dp, textWidth))
                                .then(if (titleFocus == null) Modifier else Modifier.focusRequester(titleFocus).focusable())
                                .testTag("${tag}Title"),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null)
                            Text(subtitle, Modifier.testTag("${tag}Subtitle"), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = if (nested) FontFamily.Monospace else null,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (nested)
                        ShortcutTooltip(closeLabel) {
                            IconButton(onClick = onClose, Modifier.size(48.dp).testTag("${tag}Close")) {
                                Icon(Icons.Default.Close, closeLabel)
                            }
                        }
                }
            }
            if (onReload != null || actions.isNotEmpty())
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
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                action.items.forEach { item -> MenuEntry(item) { menu = false } }
            }
    }
}

@Composable
private fun MenuEntry(item: PaneMenuItem, dismiss: () -> Unit) {
    DropdownMenuItem(
        text = { Text(item.label) },
        onClick = {
            dismiss()
            item.onClick()
        },
        modifier = Modifier.testTag(item.tag),
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
        DropdownMenu(open, onDismissRequest = ::dismiss) {
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
