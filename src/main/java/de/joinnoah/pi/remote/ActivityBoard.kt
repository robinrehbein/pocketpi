package de.joinnoah.pi.remote

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val ELAPSED_TICK_MILLIS = 60_000L

// The activity board: every session of the connected Mac that waits for the user, runs, or
// finished since the user last looked. It reads the app's state only; it never asks the Mac.

/** The board's three groups, in the order they show. */
internal enum class ActivityGroup(
    val availability: SessionAvailability,
    /** The group's heading. */
    val labelRes: Int,
    /** What a card of the group says about its session's status. */
    val cardLabelRes: Int,
) {
    NEEDS_YOU(SessionAvailability.WAITING, R.string.remote_status_needs_you, R.string.remote_status_needs_you),
    WORKING(SessionAvailability.RUNNING, R.string.remote_status_working, R.string.remote_status_working),
    FINISHED(SessionAvailability.IDLE, R.string.remote_status_finished_since_last_look, R.string.remote_status_ready),
}

/** One session on the board, as the app knows it now. */
internal data class ActivityCard(
    val routeId: String,
    val projectId: String,
    val projectName: String,
    val sessionId: String,
    val title: String,
    val preview: String?,
    val status: String,
    val updatedAt: Long?,
    val pinned: Boolean,
    val unread: Boolean,
    /** What the first prompt attached, when [title] is empty because it carried only that. */
    val titleAttachment: TitleAttachment? = null,
)

/**
 * The group a card belongs to: waiting sessions need the user, running ones work, and an idle one
 * that is unread finished since the user last looked. Other sessions are not on the board.
 */
internal fun activityGroup(card: ActivityCard): ActivityGroup? =
    when {
        card.status == "waiting" -> ActivityGroup.NEEDS_YOU
        card.status == "running" -> ActivityGroup.WORKING
        card.status == "idle" && card.unread -> ActivityGroup.FINISHED
        else -> null
    }

/** The cards by group, each group pinned sessions first, then the most recently updated. */
internal fun activityBoard(cards: List<ActivityCard>): Map<ActivityGroup, List<ActivityCard>> {
    val ordered = cards.sortedWith(
        compareByDescending<ActivityCard> { it.pinned }.thenByDescending { it.updatedAt ?: 0L },
    )
    return ActivityGroup.entries.associateWith { group -> ordered.filter { activityGroup(it) == group } }
}

/**
 * The board's cards from [state]: the connected Mac's sessions, from the selected project's list
 * first and then every project's last list. A session counts once. Nothing is listed while no Mac
 * is open.
 */
internal fun activityCards(
    state: RemoteState,
    marks: Map<String, SessionMark>,
    /**
     * The session whose chat is on screen (see [SessionMarksStore.visibleChat]); it is never
     * unread. A chat that is only selected, with the board over it, is not on screen.
     */
    visibleSessionId: String? = null,
): List<ActivityCard> {
    val route = state.host?.routeId ?: return emptyList()
    val open = visibleSessionId
    val seen = mutableSetOf<String>()
    val cards = mutableListOf<ActivityCard>()
    fun add(session: JsonObject, projectId: String, projectName: String) {
        val id = session.optionalText("id") ?: return
        if (!seen.add(id)) return
        val mark = marks[id]
        val updatedAt = session["updatedAt"]?.jsonPrimitive?.longOrNull
        val display = displayTitle(session.text("title"))
        cards +=
            ActivityCard(
                routeId = route,
                projectId = projectId,
                projectName = projectName,
                sessionId = id,
                title = display.text,
                titleAttachment = display.attachment,
                preview = session.optionalText("preview")?.let { displayTitle(it).text }?.takeIf { it.isNotBlank() },
                status = session.text("status"),
                updatedAt = updatedAt,
                pinned = mark?.pinned == true,
                unread = sessionUnread(mark, updatedAt, open = id == open),
            )
    }
    val selectedProject = state.selection.projectId
    if (selectedProject != null) {
        val name = state.project?.optionalText("name").orEmpty()
        state.sessions.forEach { add(it, selectedProject, name) }
    }
    state.projectSessions.values.forEach { project ->
        project.sessions.forEach { add(it, project.projectId, project.projectName) }
    }
    return cards
}

/** The number of sessions that wait for the user: what the strip's badge counts. */
internal fun needsYouCount(state: RemoteState, marks: Map<String, SessionMark>): Int =
    activityCards(state, marks).count { activityGroup(it) == ActivityGroup.NEEDS_YOU }

/** The chat on screen as (routeId, sessionId), or null; always null without a store. */
@Composable
internal fun visibleChatState(): State<Pair<String, String>?> {
    val store = LocalSessionMarks.current
    val none = remember { MutableStateFlow<Pair<String, String>?>(null) }
    val flow: StateFlow<Pair<String, String>?> = store?.visibleChat ?: none
    return flow.collectAsStateWithLifecycle()
}

/** The session marks of the app as a state the composables read; empty without a store. */
@Composable
internal fun sessionMarksState(): State<Map<Pair<String, String>, SessionMark>> {
    val store = LocalSessionMarks.current
    val empty = remember { MutableStateFlow<Map<Pair<String, String>, SessionMark>>(emptyMap()) }
    val flow: StateFlow<Map<Pair<String, String>, SessionMark>> = store?.marks ?: empty
    return flow.collectAsStateWithLifecycle()
}

@Composable
internal fun ActivityBoardScreen(
    key: RemoteNavKey.Activity,
    model: ActivityViewModel,
    navigator: RemoteNavigator,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val marksAll by sessionMarksState()
    val marks = remember(marksAll, key.routeId) { sessionMarksById(marksAll, key.routeId) }
    val visibleChat by visibleChatState()
    val visibleSessionId = visibleChat?.takeIf { it.first == key.routeId }?.second
    val cards = remember(state.sessions, state.projectSessions, state.project, state.host, state.selection, marks, visibleSessionId) {
        activityCards(state, marks, visibleSessionId)
    }
    // "x minutes ago" moves on while the board stays open without any state change.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(ELAPSED_TICK_MILLIS)
            value = System.currentTimeMillis()
        }
    }
    // A board beside no chat takes the window's width: a grid where the window is wide enough.
    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val wide = LocalDetailPane.current && windowWidth >= INSPECTOR_MIN_WIDTH
    ActivityBoardContent(
        groups = remember(cards) { activityBoard(cards) },
        lastKnown = !state.connected && cards.isNotEmpty(),
        wide = wide,
        now = now,
        onBack = navigator::back,
        onOpen = { card -> navigator.open(RemoteNavKey.Chat(card.routeId, card.projectId, card.sessionId)) },
    )
}

/** The board as it reads: [groups] in order, a header, and the empty or last-known notes. */
@Composable
internal fun ActivityBoardContent(
    groups: Map<ActivityGroup, List<ActivityCard>>,
    lastKnown: Boolean,
    wide: Boolean,
    onBack: () -> Unit,
    onOpen: (ActivityCard) -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    val empty = groups.values.all { it.isEmpty() }
    val density = LocalDensity.current
    // The board scrolls under its floating header, whose pills blur it, as on the other pages. It
    // starts at the header's usual height, so the board does not jump once it is measured.
    var headerHeight by remember { mutableStateOf(64.dp) }
    // The last cards clear the navigation bar.
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
    val lastKnownNote: @Composable () -> Unit = {
        Text(
            stringResource(R.string.remote_activity_last_known),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // A summary tile jumps to its group: the summary, the note and the groups before come first.
    val jumpTo = { group: ActivityGroup ->
        var index = 1 + (if (lastKnown) 1 else 0)
        for ((g, items) in groups) {
            if (g == group) break
            if (items.isNotEmpty()) index += 1 + items.size
        }
        scope.launch { if (wide) gridState.animateScrollToItem(index) else listState.animateScrollToItem(index) }
        Unit
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        // Composed first, so Tab reaches Back before the cards; zIndex keeps it drawn on top.
        PageHeader(
            title = stringResource(R.string.remote_activity_title),
            titleInPill = true,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter).zIndex(1f)
                .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
        )
        if (empty)
            Column(Modifier.fillMaxSize().padding(top = headerHeight).padding(horizontal = 16.dp)) {
                if (lastKnown) lastKnownNote()
                EmptyState(
                    icon = Icons.Outlined.TaskAlt,
                    title = stringResource(R.string.remote_activity_all_clear),
                    body = stringResource(R.string.remote_activity_empty),
                )
            }
        else if (wide)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize().glassSource(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = headerHeight, bottom = bottom),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "summary", span = { GridItemSpan(maxLineSpan) }) { ActivitySummary(groups, jumpTo) }
                if (lastKnown) item(key = "lastKnown", span = { GridItemSpan(maxLineSpan) }) { lastKnownNote() }
                groups.forEach { (group, items) ->
                    if (items.isNotEmpty()) {
                        item(key = "heading_${group.name}", span = { GridItemSpan(maxLineSpan) }) {
                            ActivityHeading(group, items.size)
                        }
                        items(items, key = { "${it.routeId}:${it.sessionId}" }) { card ->
                            ActivityCardView(card, group, now) { onOpen(card) }
                        }
                    }
                }
            }
        else
            LazyColumn(
                Modifier.fillMaxSize()
                    .glassSource()
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = MAX_PAGE_WIDTH)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = headerHeight, bottom = bottom),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "summary") { ActivitySummary(groups, jumpTo) }
                if (lastKnown) item(key = "lastKnown") { lastKnownNote() }
                groups.forEach { (group, items) ->
                    if (items.isNotEmpty()) {
                        item(key = "heading_${group.name}") { ActivityHeading(group, items.size) }
                        items(items, key = { "${it.routeId}:${it.sessionId}" }) { card ->
                            ActivityCardView(card, group, now) { onOpen(card) }
                        }
                    }
                }
            }
    }
}

/** One tile per group with its count, tinted in the group's colour; a tap jumps to the group. */
@Composable
private fun ActivitySummary(groups: Map<ActivityGroup, List<ActivityCard>>, onJump: (ActivityGroup) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActivityGroup.entries.forEach { group ->
            val count = groups[group].orEmpty().size
            val color = sessionStatusColor(group.availability)
            // The heading's own words, shortened where it is long, so a tile names the group it jumps to.
            val label = stringResource(if (group == ActivityGroup.FINISHED) R.string.remote_activity_tile_finished else group.labelRes)
            Surface(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(PAGE_CARD_CORNER))
                    .clickable(enabled = count > 0, role = Role.Button) { onJump(group) }
                    .testTag("activitySummary_${group.name}"),
                shape = RoundedCornerShape(PAGE_CARD_CORNER),
                color = color.copy(alpha = .12f),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        count.toString(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (count > 0) color else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityHeading(group: ActivityGroup, count: Int) {
    SectionHeading(
        stringResource(group.labelRes),
        Modifier.padding(top = 12.dp),
        count = count,
        color = sessionStatusColor(group.availability),
    )
}

@Composable
private fun ActivityCardView(card: ActivityCard, group: ActivityGroup, now: Long, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val title = sessionTitleLabel(card.title, card.titleAttachment)
    val status = stringResource(group.cardLabelRes)
    val pinned = stringResource(R.string.remote_activity_pinned)
    val unread = stringResource(R.string.remote_activity_unread)
    val elapsed =
        card.updatedAt?.let {
            DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString()
        }
    val description =
        listOfNotNull(card.projectName, title, card.preview, status, elapsed,
            pinned.takeIf { card.pinned }, unread.takeIf { card.unread })
            .joinToString(", ")
    val statusColor = sessionStatusColor(group.availability)
    val shape = RoundedCornerShape(PAGE_CARD_CORNER)
    Surface(
        modifier =
            Modifier.fillMaxWidth()
                .clip(shape)
                .semantics {
                    role = Role.Button
                    contentDescription = description
                }
                .clickable(onClick = onOpen),
        shape = shape,
        color = colors.surfaceContainerLow,
        // What waits for the user stands out from what only runs or finished.
        border = if (group == ActivityGroup.NEEDS_YOU) BorderStroke(1.dp, statusColor.copy(alpha = .55f)) else null,
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                GlassAvatar(size = 44.dp) {
                    Text("π", style = MaterialTheme.typography.titleMedium, color = colors.primary)
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).size(12.dp)
                        .background(colors.surfaceContainerLow, CircleShape)
                        .padding(2.dp)
                        .background(statusColor, CircleShape)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        title,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (card.unread) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (card.pinned)
                        Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                    if (card.unread) Box(Modifier.size(8.dp).background(colors.primary, CircleShape))
                }
                Text(
                    listOfNotNull(card.projectName.takeIf { it.isNotBlank() }, elapsed).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                card.preview?.let {
                    Text(
                        it,
                        Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
