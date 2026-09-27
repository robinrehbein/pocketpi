package de.joinnoah.pi.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The "Open folder" action on a host's Projects screen. */
@Composable
internal fun OpenFolderButton(enabled: Boolean, onClick: () -> Unit) {
    FloatingSurface(
        modifier = Modifier.testTag("openFolderButton"),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
    ) {
        Row(
            Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.remote_folders_open_folder),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
internal fun FolderBrowserScreen(
    key: RemoteNavKey.FolderBrowser,
    model: FolderBrowserViewModel,
    navigator: RemoteNavigator,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val folders = state.folders.takeIf { it.routeId == key.routeId } ?: FolderBrowserState()
    val available = canOpenFolders(state)
    // Loads the listing once the capability is known, also after a reconnect lost a request.
    // Clone events sent while the browser was away may be lost; re-read a running clone.
    LaunchedEffect(available) { if (available) model.refreshClone() }
    LaunchedEffect(available, state.loading, folders.loaded, folders.loading, folders.error) {
        if (available && !state.loading && !folders.loaded && !folders.loading && folders.error == null)
            model.reload()
    }
    FolderBrowserContent(
        folders = folders,
        connected = state.connected,
        available = available,
        connection = state.connection,
        onBack = navigator::back,
        onJump = { navigator.browseFolder(key.routeId, it) },
        onEnter = model::enter,
        onRetry = model::reload,
        onCreateFolder = model::createFolder,
        onClone = model::cloneRepository,
        onOpen = { navigator.openFolder(key.routeId, it) },
        onConfirm = { navigator.openFolder(key.routeId, it.path, it) },
        onCancelTrust = model::cancelTrust,
        onDismissClone = model::dismissClone,
        onDismissNotice = model::dismissNotice,
    )
}

/** "~/Code/app" for [path] "app" below the root label "~/Code". */
internal fun folderDisplayPath(root: String?, path: String): String =
    when {
        root == null -> path
        path.isEmpty() -> root
        root.endsWith("/") -> root + path
        else -> "$root/$path"
    }

/**
 * The folder browser with its own Scaffold (header, breadcrumb, actions). [RemoteFolderList] and
 * [FolderBreadcrumb] do not depend on it; splitting the Scaffold out for a side pane waits for
 * the tablet layout (DEV-1049).
 */
@Composable
internal fun FolderBrowserContent(
    folders: FolderBrowserState,
    connected: Boolean,
    available: Boolean,
    connection: Int,
    onBack: () -> Unit,
    onJump: (String) -> Unit,
    onEnter: (String) -> Unit,
    onRetry: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onClone: (String, String?) -> Unit,
    onOpen: (path: String) -> Unit,
    onConfirm: (FolderTrustPrompt) -> Unit,
    onCancelTrust: () -> Unit,
    onDismissClone: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var cloning by rememberSaveable { mutableStateOf(false) }
    val atRoot = folders.path.isEmpty()
    val actionable = available && !folders.working
    val cloneRunning = folders.clone?.stage == CloneStage.RUNNING
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FloatingSurface(modifier = Modifier.testTag("navigationPill"), shape = CircleShape) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.remote_back))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        stringResource(R.string.remote_folders_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                FolderBreadcrumb(
                    root = folders.root,
                    path = folders.path,
                    onJump = onJump,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { creating = true },
                        enabled = actionable && folders.loaded,
                        modifier = Modifier.weight(1f).testTag("folderNewAction"),
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.remote_folders_new_folder), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { cloning = true },
                        enabled = actionable && folders.loaded && !cloneRunning,
                        modifier = Modifier.weight(1f).testTag("folderCloneAction"),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.remote_folders_clone), maxLines = 1)
                    }
                }
                Button(
                    onClick = { onOpen(folders.path) },
                    enabled = actionable && !atRoot,
                    modifier = Modifier.fillMaxWidth().testTag("folderOpenHere"),
                ) {
                    if (folders.working) {
                        val workingLabel = stringResource(R.string.remote_folders_working)
                        CircularProgressIndicator(
                            Modifier.size(18.dp).semantics { contentDescription = workingLabel },
                            strokeWidth = 2.dp,
                        )
                    }
                    else Text(stringResource(R.string.remote_folders_open_here))
                }
                if (atRoot)
                    Text(
                        stringResource(R.string.remote_folders_root_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("folderRootHint"),
                    )
            }
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            folders.notice?.let { notice ->
                FolderNotice(stringResource(notice), onDismissNotice)
            }
            folders.clone?.let { clone ->
                CloneProgressCard(
                    clone = clone,
                    root = folders.root,
                    canOpen = actionable,
                    onOpen = { onOpen(clone.path) },
                    onDismiss = onDismissClone,
                )
            }
            when {
                !connected ->
                    FolderMessage(stringResource(connection), Modifier.testTag("folderOffline"))
                !available ->
                    FolderMessage(
                        stringResource(R.string.remote_folders_unavailable),
                        Modifier.testTag("folderUnavailable"),
                    )
                else ->
                    RemoteFolderList(
                        folders = folders,
                        enabled = !folders.working,
                        onEnter = onEnter,
                        onRetry = onRetry,
                        modifier = Modifier.weight(1f),
                    )
            }
        }
    }
    if (creating)
        NewFolderDialog(
            onDismiss = { creating = false },
            onCreate = {
                creating = false
                onCreateFolder(it)
            },
        )
    if (cloning)
        CloneDialog(
            onDismiss = { cloning = false },
            onClone = { url, name ->
                cloning = false
                onClone(url, name)
            },
        )
    folders.trust?.let { prompt ->
        TrustDialog(
            prompt = prompt,
            displayPath = folderDisplayPath(folders.root, prompt.path),
            // The dialog confirms exactly the prompt it shows.
            onConfirm = { onConfirm(prompt) },
            onDismiss = onCancelTrust,
        )
    }
}

@Composable
private fun FolderMessage(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 16.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Tap-to-jump path from the root label down to the shown folder. */
@Composable
internal fun FolderBreadcrumb(
    root: String?,
    path: String,
    onJump: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rootLabel = root ?: stringResource(R.string.remote_folders_root_unknown)
    val segments = relativePathSegments(path).orEmpty()
    val crumbs =
        listOf(rootLabel to "") +
            segments.mapIndexed { index, name -> name to segments.take(index + 1).joinToString("/") }
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        modifier.fillMaxWidth().horizontalScroll(scroll).testTag("folderBreadcrumb"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, (label, target) ->
            if (index > 0)
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            val current = index == crumbs.lastIndex
            val jumpLabel = stringResource(R.string.remote_folders_jump_to, label)
            TextButton(
                onClick = { onJump(target) },
                enabled = !current,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.testTag("folderCrumb:$target").semantics {
                    if (!current) contentDescription = jumpLabel
                },
            ) {
                Text(
                    label,
                    maxLines = 1,
                    color =
                        if (current) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** The listing of one folder with its loading, empty and error states. */
@Composable
internal fun RemoteFolderList(
    folders: FolderBrowserState,
    enabled: Boolean,
    onEnter: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
) {
    val error = folders.error
    when {
        error != null ->
            Column(
                modifier.fillMaxWidth().padding(top = 16.dp).testTag("folderError"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.remote_folders_retry)) }
            }
        !folders.loaded -> {
            val loadingLabel = stringResource(R.string.remote_folders_loading)
            Box(modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.TopCenter) {
                CircularProgressIndicator(
                    Modifier.testTag("folderLoading").semantics { contentDescription = loadingLabel }
                )
            }
        }
        folders.entries.isEmpty() ->
            FolderMessage(stringResource(R.string.remote_folders_empty), modifier.testTag("folderEmpty"))
        else ->
            LazyColumn(modifier.fillMaxWidth().testTag("folderList"), contentPadding = contentPadding) {
                items(folders.entries, key = { it.name }) { entry ->
                    FolderRow(entry, enabled) { onEnter(entry.name) }
                }
                if (folders.truncated)
                    item(key = "truncated") {
                        Text(
                            stringResource(R.string.remote_folders_truncated),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp).testTag("folderTruncated"),
                        )
                    }
            }
    }
}

@Composable
private fun FolderRow(entry: FolderEntry, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(
                enabled = enabled,
                onClickLabel = stringResource(R.string.remote_folders_enter),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 10.dp)
            .testTag("folderRow:${entry.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            entry.name,
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.git) FolderBadge(stringResource(R.string.remote_folders_badge_git))
        if (entry.shared) FolderBadge(stringResource(R.string.remote_folders_badge_shared))
        if (entry.piConfig)
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = stringResource(R.string.remote_folders_badge_pi_config),
                tint = folderWarningColor(),
                modifier = Modifier.padding(start = 8.dp).size(20.dp).testTag("folderPiConfig:${entry.name}"),
            )
        Spacer(Modifier.weight(0.001f))
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FolderBadge(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        modifier =
            Modifier.padding(start = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun FolderNotice(text: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("folderNotice"),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
    ) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                Modifier.weight(1f).padding(vertical = 12.dp).semantics {
                    liveRegion = LiveRegionMode.Polite
                },
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, stringResource(R.string.remote_folders_dismiss))
            }
        }
    }
}

@Composable
internal fun CloneProgressCard(
    clone: CloneProgress,
    root: String?,
    canOpen: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val where = folderDisplayPath(root, clone.path)
    OutlinedCard(Modifier.fillMaxWidth().testTag("cloneProgress")) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (clone.stage) {
                CloneStage.RUNNING -> {
                    Text(
                        stringResource(R.string.remote_folders_clone_running, where),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Only the phase is announced; every percent step would flood TalkBack.
                        Text(
                            stringResource(clonePhaseLabel(clone.phase)),
                            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        clone.percent?.let {
                            Text(
                                stringResource(R.string.remote_folders_clone_percent, it),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.testTag("clonePercent"),
                            )
                        }
                    }
                    val percent = clone.percent
                    if (percent != null)
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    // The clone goes on on the Mac; this only stops following it here.
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("cloneHide")) {
                        Text(stringResource(R.string.remote_folders_clone_hide))
                    }
                }
                CloneStage.SUCCEEDED -> {
                    Text(
                        stringResource(R.string.remote_folders_clone_succeeded, where),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpen, enabled = canOpen, modifier = Modifier.testTag("cloneOpen")) {
                            Text(stringResource(R.string.remote_folders_clone_open))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.remote_folders_dismiss))
                        }
                    }
                }
                CloneStage.FAILED -> {
                    Text(
                        stringResource(cloneFailureMessage(clone.failure ?: CloneFailure.FAILED)),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.remote_folders_dismiss))
                    }
                }
            }
        }
    }
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val problem = folderNameProblem(name)
    val message = problem?.let(::folderNameProblemMessage)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_folders_new_folder)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.remote_folders_name)) },
                isError = message != null,
                supportingText = message?.let { { Text(stringResource(it)) } },
                singleLine = true,
                modifier = Modifier.testTag("newFolderName"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = problem == null,
                modifier = Modifier.testTag("newFolderCreate"),
            ) {
                Text(stringResource(R.string.remote_folders_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_cancel)) } },
    )
}

@Composable
private fun CloneDialog(onDismiss: () -> Unit, onClone: (String, String?) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val urlValid = validCloneUrl(url)
    val derived = if (urlValid) cloneNameFromUrl(url) else null
    val effective = name.ifEmpty { derived.orEmpty() }
    val nameProblem = if (name.isEmpty() && !urlValid) null else folderNameProblem(effective)
    val nameMessage =
        when {
            nameProblem == null -> null
            name.isEmpty() -> R.string.remote_folders_clone_name_needed
            else -> folderNameProblemMessage(nameProblem)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_folders_clone_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.remote_folders_clone_url)) },
                    isError = url.isNotEmpty() && !urlValid,
                    supportingText = {
                        Text(
                            stringResource(
                                if (url.isNotEmpty() && !urlValid) R.string.remote_folders_clone_url_invalid
                                else R.string.remote_folders_clone_url_help
                            )
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.testTag("cloneUrl"),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.remote_folders_clone_name)) },
                    placeholder = derived?.let { { Text(it) } },
                    isError = nameMessage != null,
                    supportingText = nameMessage?.let { { Text(stringResource(it)) } },
                    singleLine = true,
                    modifier = Modifier.testTag("cloneName"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onClone(url, name.ifEmpty { null }) },
                enabled = urlValid && nameProblem == null,
                modifier = Modifier.testTag("cloneStart"),
            ) {
                Text(stringResource(R.string.remote_folders_clone_start))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_cancel)) } },
    )
}

@Composable
private fun TrustDialog(
    prompt: FolderTrustPrompt,
    displayPath: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("trustDialog"),
        title = { Text(stringResource(R.string.remote_folders_trust_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.remote_folders_trust_body, displayPath))
                if (prompt.piConfig)
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(12.dp)
                            .testTag("trustPiConfigWarning"),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.remote_folders_trust_pi_config),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("trustConfirm"),
                colors =
                    if (prompt.piConfig)
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    else ButtonDefaults.textButtonColors(),
            ) {
                Text(stringResource(R.string.remote_folders_trust_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_cancel)) } },
    )
}
