package de.joinnoah.pi.remote

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        cards +=
            ActivityCard(
                routeId = route,
                projectId = projectId,
                projectName = projectName,
                sessionId = id,
                title = session.text("title"),
                preview = session.optionalText("preview")?.takeIf { it.isNotBlank() },
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
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.remote_back))
            }
            Text(
                stringResource(R.string.remote_activity_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (lastKnown)
            Text(
                stringResource(R.string.remote_activity_last_known),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        if (empty)
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.remote_activity_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        else if (wide)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = MAX_PAGE_WIDTH)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                groups.forEach { (group, items) ->
                    if (items.isNotEmpty()) {
                        item(key = "heading_${group.name}") { ActivityHeading(group, items.size) }
                        items(items, key = { "${it.routeId}:${it.sessionId}" }) { card ->
                            ActivityCardView(card, group, now) { onOpen(card) }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
    }
}

@Composable
private fun ActivityHeading(group: ActivityGroup, count: Int) {
    Row(
        // One heading node, "Waiting 3", so heading navigation reads the count with its group.
        Modifier.fillMaxWidth().padding(top = 12.dp).semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(group.labelRes),
            style = MaterialTheme.typography.titleSmall,
            color = sessionStatusColor(group.availability),
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActivityCardView(card: ActivityCard, group: ActivityGroup, now: Long, onOpen: () -> Unit) {
    val title = card.title.ifBlank { stringResource(R.string.remote_new_session) }
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
    Surface(
        modifier =
            Modifier.fillMaxWidth()
                .semantics {
                    role = Role.Button
                    contentDescription = description
                }
                .clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    card.projectName,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (card.pinned)
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                if (card.unread)
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            card.preview?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    status,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = sessionStatusColor(group.availability),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                elapsed?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
