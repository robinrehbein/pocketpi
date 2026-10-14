package de.joinnoah.pi.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The share sheet's target: a compact picker over the sending app. It offers the open session and
 * the recent ones first, a new session in the open or last used project, and "Choose another
 * session…", which opens the app in its pick mode. Whatever is chosen goes to MainActivity
 * ([LaunchIntents.share]); this activity then finishes.
 *
 * A Direct Share target (a session shortcut picked in the share sheet) skips the picker, but only
 * for a shortcut PocketPi itself published. While the app lock is on and due, the picker would show
 * session titles, so the share goes straight to MainActivity, which holds it behind the lock and
 * asks for the session after unlocking.
 */
class ShareActivity : ComponentActivity() {
    private val app
        get() = application as RemoteApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val received = intent
        setContent {
            val preferences by app.settings.state.collectAsState()
            MaterialTheme(
                colorScheme = remoteColorScheme(isSystemInDarkTheme(), synthwave = preferences.theme == "synthwave"),
                typography = RemoteTypography,
            ) {
                var stage by remember { mutableStateOf<ShareStage>(ShareStage.Loading) }
                LaunchedEffect(Unit) {
                    val content =
                        withContext(Dispatchers.IO) { ShareIntake.parse(received, ContentShareAccess(this@ShareActivity)) }
                    if (content == null || content.empty) {
                        stage = ShareStage.Nothing
                        return@LaunchedEffect
                    }
                    val snapshot = app.home.load()
                    val shortcut = runCatching { received.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID) }.getOrNull()
                    val direct =
                        shortcut?.let { id ->
                            val published =
                                withContext(Dispatchers.IO) {
                                    runCatching { ShortcutManagerCompat.getDynamicShortcuts(this@ShareActivity).map { it.id } }
                                        .getOrDefault(emptyList())
                                }
                            directShareTarget(id, published, snapshot)
                        }
                    app.appLock.check()
                    when {
                        direct != null -> forward(ShareRequest(content, direct))
                        app.appLock.locked.value -> forward(ShareRequest(content, ShareTarget.Pick))
                        else -> stage = ShareStage.Picking(content, shareChoices(app.repository.state.value, snapshot))
                    }
                }
                SharePicker(
                    stage,
                    onChoose = { content, target -> forward(ShareRequest(content, target)) },
                    onCancel = ::finish,
                )
            }
        }
    }

    private fun forward(request: ShareRequest) {
        try {
            startActivity(LaunchIntents.share(this, request))
        } catch (e: Exception) {
            // The sender's grant may not allow passing a URI on; nothing is sent then.
            Diagnostics.caught("share.forward", e)
        }
        finish()
    }
}

internal sealed interface ShareStage {
    data object Loading : ShareStage

    /** The share held nothing PocketPi takes. */
    data object Nothing : ShareStage

    data class Picking(val content: SharedContent, val choices: ShareChoices) : ShareStage
}

/** A recent session the picker offers; [open] is the one open in the app now. */
internal data class ShareSessionChoice(val session: HomeSession, val open: Boolean, val hostName: String?)

/** A project the picker can start a new session in. */
internal data class ShareProject(val routeId: String, val projectId: String, val name: String, val hostName: String?)

/** The picker's rows: recent sessions, and the projects for "New session in", the default first. */
internal data class ShareChoices(val sessions: List<ShareSessionChoice>, val projects: List<ShareProject>)

/** Most recent sessions the picker lists, the open one included. */
internal const val MAX_SHARE_RECENTS = 5

/**
 * The picker's rows from the app's [state] and the home [snapshot]: the open session, then the
 * most recently opened or updated ones; the open session's project, then those of the recent
 * sessions, then the other projects of the open host. Only paired hosts count. Host names are
 * shown only with more than one Mac paired.
 */
internal fun shareChoices(state: RemoteState, snapshot: HomeSnapshot, limit: Int = MAX_SHARE_RECENTS): ShareChoices {
    val routes = if (state.hostsLoaded) state.hosts.map { it.routeId }.toSet() else snapshot.sessions.map { it.routeId }.toSet()
    val hostNames = if (state.hosts.size > 1) state.hosts.associate { it.routeId to it.name } else emptyMap()
    val selection = state.selection
    val route = selection.routeId?.takeIf { it in routes }
    fun projectName(routeId: String, projectId: String): String? =
        state.project?.takeIf { routeId == route && it.optionalText("id") == projectId }?.optionalText("name")
            ?: state.projects.takeIf { routeId == route }?.find { it.optionalText("id") == projectId }?.optionalText("name")
            ?: snapshot.sessions.find { it.routeId == routeId && it.projectId == projectId && it.projectName.isNotBlank() }?.projectName
    val open =
        if (route == null || selection.projectId == null || selection.sessionId == null) null
        else {
            val known = snapshot.sessions.find { it.routeId == route && it.sessionId == selection.sessionId }
            val title = state.session?.takeIf { it.optionalText("id") == selection.sessionId }?.optionalText("title")
            HomeSession(
                route,
                selection.projectId,
                projectName(route, selection.projectId).orEmpty(),
                selection.sessionId,
                title ?: known?.title.orEmpty(),
                known?.status ?: "idle",
            )
        }
    val recents =
        snapshot.sessions
            .filter { it.routeId in routes && !(it.routeId == open?.routeId && it.sessionId == open.sessionId) }
            .sortedWith(compareByDescending<HomeSession> { it.openedAt ?: 0L }.thenByDescending { it.updatedAt ?: 0L })
    val sessions =
        (listOfNotNull(open).map { ShareSessionChoice(it, true, hostNames[it.routeId]) } +
                recents.map { ShareSessionChoice(it, false, hostNames[it.routeId]) })
            .take(limit.coerceAtLeast(0))
    val projectKeys = buildList {
        if (route != null && selection.projectId != null) add(route to selection.projectId)
        recents.forEach { add(it.routeId to it.projectId) }
        if (route != null) state.projects.mapNotNull { it.optionalText("id") }.forEach { add(route to it) }
    }
    val projects =
        projectKeys.distinct().map { (routeId, projectId) ->
            ShareProject(routeId, projectId, projectName(routeId, projectId)?.takeIf { it.isNotBlank() } ?: projectId, hostNames[routeId])
        }
    return ShareChoices(sessions, projects)
}

/**
 * The session of a Direct Share target, or null to show the picker. The shortcut ID arrives as an
 * extra any app could set, so only a shortcut PocketPi published counts ([published]).
 */
internal fun directShareTarget(shortcutId: String, published: Collection<String>, snapshot: HomeSnapshot): ShareTarget.Session? {
    if (shortcutId !in published) return null
    val (route, session) = shortcutTarget(shortcutId) ?: return null
    val project = snapshot.sessions.find { it.routeId == route && it.sessionId == session }?.projectId
    return ShareTarget.Session(route, project, session)
}

@Composable
internal fun SharePicker(stage: ShareStage, onChoose: (SharedContent, ShareTarget) -> Unit, onCancel: () -> Unit) {
    val title = stringResource(R.string.remote_share_title)
    Box(Modifier.fillMaxSize()) {
        // The sending app stays visible behind the scrim; a tap there cancels, like outside a dialog.
        // Back cancels too; the scrim itself is nothing to focus.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).pointerInput(onCancel) { detectTapGestures { onCancel() } })
        Box(
            Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.asPaddingValues()).padding(12.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // A Surface takes the taps on it, so they never reach the scrim beneath.
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().semantics { paneTitle = title }.testTag("sharePicker"),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() },
                    )
                    when (stage) {
                        ShareStage.Loading -> {
                            val loading = stringResource(R.string.remote_share_loading)
                            CircularProgressIndicator(
                                Modifier.padding(24.dp).align(Alignment.CenterHorizontally).semantics { contentDescription = loading }
                            )
                        }
                        ShareStage.Nothing -> {
                            Text(
                                stringResource(R.string.remote_share_nothing),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("shareNothing"),
                            )
                        }
                        is ShareStage.Picking -> ShareChoiceRows(stage, onChoose)
                    }
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp).testTag("shareCancel")) {
                        Text(stringResource(R.string.remote_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareChoiceRows(stage: ShareStage.Picking, onChoose: (SharedContent, ShareTarget) -> Unit) {
    val content = stage.content
    val context = LocalContext.current
    Text(
        shareSummary(context, content),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
    if (content.skipped > 0)
        Text(
            stringResource(R.string.remote_share_skipped),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag("shareSkipped"),
        )
    if (stage.choices.sessions.isNotEmpty()) {
        Text(
            stringResource(R.string.remote_share_recent),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
        )
        val untitled = stringResource(R.string.remote_widget_untitled)
        val openNow = stringResource(R.string.remote_share_open_now)
        stage.choices.sessions.forEach { choice ->
            val session = choice.session
            val place = listOfNotNull(session.projectName.takeIf { it.isNotBlank() }, choice.hostName).joinToString(" · ")
            ListItem(
                headlineContent = { Text(session.title.ifBlank { untitled }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent =
                    listOfNotNull(openNow.takeIf { choice.open }, place.takeIf { it.isNotBlank() })
                        .joinToString(" · ")
                        .takeIf { it.isNotBlank() }
                        ?.let { text -> { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier =
                    Modifier.clickable {
                        onChoose(content, ShareTarget.Session(session.routeId, session.projectId, session.sessionId))
                    }
                        .padding(horizontal = 8.dp)
                        .testTag("shareSession:${session.sessionId}"),
            )
        }
    }
    var project by remember(stage) { mutableStateOf(stage.choices.projects.firstOrNull()) }
    project?.let { chosen ->
        NewSessionRow(chosen, stage.choices.projects, onSelect = { project = it }) {
            onChoose(content, ShareTarget.NewSession(chosen.routeId, chosen.projectId))
        }
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.remote_share_choose_other)) },
        leadingContent = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChoose(content, ShareTarget.Pick) }.padding(horizontal = 8.dp).testTag("shareChooseOther"),
    )
}

@Composable
private fun NewSessionRow(project: ShareProject, projects: List<ShareProject>, onSelect: (ShareProject) -> Unit, onCreate: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    fun label(project: ShareProject) = listOfNotNull(project.name, project.hostName).joinToString(" · ")
    ListItem(
        headlineContent = {
            Text(stringResource(R.string.remote_share_new_session, label(project)), maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
        trailingContent =
            if (projects.size < 2) null
            else {
                {
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("shareChangeProject")) {
                            Icon(Icons.Default.ArrowDropDown, stringResource(R.string.remote_share_change_project))
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            projects.forEach { candidate ->
                                DropdownMenuItem(
                                    text = { Text(label(candidate)) },
                                    onClick = {
                                        menu = false
                                        onSelect(candidate)
                                    },
                                    modifier = Modifier.testTag("shareProject:${candidate.projectId}"),
                                )
                            }
                        }
                    }
                }
            },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onCreate).padding(horizontal = 8.dp).testTag("shareNewSession"),
    )
}
