package de.joinnoah.pi.remote

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What the inspector beside a chat can show; each is a tab of its own. */
internal enum class InspectorTab(@StringRes val labelRes: Int) {
    CHANGES(R.string.remote_inspector_tab_changes),
    FILES(R.string.remote_inspector_tab_files),
    JOBS(R.string.remote_inspector_tab_jobs),
    TOOL(R.string.remote_inspector_tab_tool),
    HISTORY(R.string.remote_inspector_tab_history),
    ARTIFACT(R.string.remote_inspector_tab_artifact),
}

/**
 * The inspector's tabs of one chat: [open] in the order they opened, the [active] one shown and
 * whether the user [hidden] the pane (Ctrl+\) without closing them. The repository's state backs
 * each tab (the changes, the files, the jobs view) or the chat's own (the open tool, the
 * history): [reconcile] drops a tab whose state is gone and adds one that opened elsewhere.
 */
@Immutable
internal data class InspectorTabs(
    val open: List<InspectorTab> = emptyList(),
    val active: InspectorTab? = null,
    val hidden: Boolean = false,
) {
    /** Opens [tab], or switches to it, and shows the pane. */
    fun open(tab: InspectorTab): InspectorTabs =
        InspectorTabs(if (tab in open) open else open + tab, tab, hidden = false)

    /** Closes [tab]; when it was the active one, the tab before it becomes active. */
    fun close(tab: InspectorTab): InspectorTabs {
        if (tab !in open) return this
        val remaining = open - tab
        return InspectorTabs(remaining, if (active == tab) neighbour(tab, remaining) else active, hidden)
    }

    /**
     * Matches the tabs to what is [available] now, in its order for tabs that are new: a tab whose
     * state is gone closes as [close] would, and a tab that opened elsewhere (a notification, the
     * jobs chip) joins and becomes active.
     */
    fun reconcile(available: Collection<InspectorTab>): InspectorTabs {
        var tabs = this
        for (tab in open) if (tab !in available) tabs = tabs.close(tab)
        for (tab in available) if (tab !in tabs.open) tabs = tabs.open(tab)
        return tabs
    }

    /** The tab before [tab] in [open] that is still among [remaining], else the one after it. */
    private fun neighbour(tab: InspectorTab, remaining: List<InspectorTab>): InspectorTab? {
        val index = open.indexOf(tab)
        return open.subList(0, index).lastOrNull { it in remaining }
            ?: open.subList(index + 1, open.size).firstOrNull { it in remaining }
    }

    companion object {
        val Saver: Saver<InspectorTabs, Any> =
            Saver(
                save = { tabs ->
                    arrayListOf(tabs.open.joinToString(",") { it.name }, tabs.active?.name.orEmpty(), tabs.hidden)
                },
                restore = { saved ->
                    val values = saved as List<*>
                    fun tab(name: String) = InspectorTab.entries.firstOrNull { it.name == name }
                    val open = (values[0] as String).split(',').mapNotNull(::tab)
                    InspectorTabs(open, tab(values[1] as String)?.takeIf { it in open }, values[2] as Boolean)
                },
            )
    }
}

/**
 * The inspector pane with its tabs: a compact tab row with a close button per tab and the
 * expand action, and below it only the active tab's [content]. Each tab keeps its own saveable
 * state (a scroll position, an expanded row) while another one shows.
 */
@Composable
internal fun InspectorHost(
    tabs: InspectorTabs,
    content: (InspectorTab) -> (@Composable () -> Unit)?,
    onSelect: (InspectorTab) -> Unit,
    onClose: (InspectorTab) -> Unit,
    expanded: Boolean,
    onExpand: () -> Unit,
    closable: (InspectorTab) -> Boolean = { true },
    modifier: Modifier = Modifier,
) {
    val holder = rememberSaveableStateHolder()
    val known = remember { mutableSetOf<InspectorTab>() }
    LaunchedEffect(tabs.open) {
        // A closed tab starts fresh when it opens again.
        (known - tabs.open.toSet()).forEach { holder.removeState(it.name) }
        known.retainAll(tabs.open.toSet())
        known.addAll(tabs.open)
    }
    val active = tabs.active ?: tabs.open.firstOrNull()
    Surface(modifier.fillMaxSize().testTag("inspectorHost")) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = tabs.open.indexOf(active).coerceAtLeast(0),
                    modifier = Modifier.weight(1f).testTag("inspectorTabs"),
                    edgePadding = 0.dp,
                    minTabWidth = 0.dp,
                    divider = {},
                ) {
                    for (tab in tabs.open) {
                        val label = stringResource(tab.labelRes)
                        Tab(
                            selected = tab == active,
                            onClick = { onSelect(tab) },
                            modifier = Modifier.testTag("inspectorTab_${tab.name.lowercase()}"),
                        ) {
                            Row(
                                Modifier.padding(start = 16.dp, end = if (closable(tab)) 0.dp else 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                if (closable(tab)) {
                                    Spacer(Modifier.width(4.dp))
                                    val closeLabel = stringResource(R.string.remote_inspector_tab_close, label)
                                    ShortcutTooltip(closeLabel) {
                                        IconButton(
                                            onClick = { onClose(tab) },
                                            modifier = Modifier.size(40.dp).testTag("inspectorTabClose_${tab.name.lowercase()}"),
                                        ) {
                                            Icon(Icons.Default.Close, closeLabel, Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val expandLabel =
                    stringResource(if (expanded) R.string.remote_inspector_narrow else R.string.remote_inspector_widen)
                ShortcutTooltip(expandLabel, Shortcut.EXPAND_INSPECTOR) {
                    IconButton(onClick = onExpand, modifier = Modifier.size(48.dp).testTag("inspectorExpand")) {
                        Icon(if (expanded) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull, expandLabel)
                    }
                }
            }
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val tab = active ?: return@Box
                val body = content(tab) ?: return@Box
                holder.SaveableStateProvider(tab.name) { body() }
            }
        }
    }
}

/**
 * What a widened inspector leaves of the chat where the window is narrow: whether pi runs, and
 * the way back to the chat beside the pane.
 */
@Composable
internal fun ChatStrip(statusColor: Color, statusLabel: String, onShowChat: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val label = stringResource(R.string.remote_chat_strip_show)
            ShortcutTooltip(label, Shortcut.EXPAND_INSPECTOR) {
                IconButton(onClick = onShowChat, modifier = Modifier.size(48.dp).testTag("chatStripShow")) {
                    Icon(Icons.AutoMirrored.Outlined.Chat, label)
                }
            }
            Box(
                Modifier.size(8.dp)
                    .background(statusColor, CircleShape)
                    .semantics {
                        contentDescription = statusLabel
                        liveRegion = LiveRegionMode.Polite
                    }
                    .testTag("chatStripStatus")
            )
        }
    }
}
