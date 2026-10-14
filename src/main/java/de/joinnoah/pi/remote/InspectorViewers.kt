package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How an open artifact learns that a newer version exists. */
internal sealed interface ArtifactWatch {
    /** A project file, at [path] as the message named it: the chat's finished edits change it. */
    data class File(val path: String) : ArtifactWatch

    /** A session artifact opened at [version]: the gallery may list a newer one. */
    data class Listed(val id: String, val version: Int) : ArtifactWatch
}

/** What opens in the inspector's artifact tab beside the chat instead of a full-screen dialog. */
internal sealed interface InspectorViewer {
    /** Names the content: showing the same again switches to the tab and keeps what it shows. */
    val key: String

    /** An HTML page or a Mermaid diagram; [reload] and [watch] as [ArtifactViewer] takes them. */
    class Artifact(
        val kind: ArtifactKind,
        val path: String,
        val title: String,
        val text: String,
        val reload: (suspend () -> String?)?,
        val watch: ArtifactWatch? = null,
    ) : InspectorViewer {
        override val key: String =
            when (watch) {
                is ArtifactWatch.File -> "file:${watch.path}"
                is ArtifactWatch.Listed -> "listed:${watch.id}"
                null -> "text:${kind.name}:$path:${sha256Hex(text)}"
            }
    }

    /** An agent image, as [ImageViewer] takes it. */
    class Image(val path: String, val sha256: String, val mimeType: String, val source: ProjectImageSource) : InspectorViewer {
        override val key: String = "image:${source.sessionId}:$path:$sha256"
    }
}

/**
 * Where artifact, image and gallery viewers open: a chat whose layout has the inspector beside it
 * provides one, and a phone none, so its viewers stay dialogs. [listed] takes the session artifacts
 * a gallery listed, so an open one learns of its newer versions.
 */
@Stable
internal class InspectorViewers(
    val show: (InspectorViewer) -> Unit,
    val listed: (List<SessionArtifact>) -> Unit = {},
)

internal val LocalInspectorViewers = compositionLocalOf<InspectorViewers?> { null }

/** The last finished edit or write of [path] in [items]; another one means the file changed since. */
internal fun artifactChange(items: List<ConversationItem>, path: String): String? =
    items.lastOrNull { item ->
        item is ConversationItem.Activity &&
            (item.name == "edit" || item.name == "write") &&
            item.state !in UNFINISHED_TOOL_STATES &&
            toolPath(item)?.let { samePath(it, path) } == true
    }?.id

private val UNFINISHED_TOOL_STATES = setOf("error", "streaming", "unavailable")

/** A message and a tool name the same file in different ways: relative, absolute or with `./`. */
internal fun samePath(a: String, b: String): Boolean {
    val first = a.removePrefix("./").trimEnd('/')
    val second = b.removePrefix("./").trimEnd('/')
    if (first.isEmpty() || second.isEmpty()) return false
    return first == second || first.endsWith("/$second") || second.endsWith("/$first")
}

/**
 * The inspector's artifact tab: the open [viewer], the page it shows (shared with the dialog it
 * pops out to) and the versions it knows of. A change reported through [changed] that the page
 * does not show yet is [newVersion]; the user takes it with [reload], or [autoRefresh] does at once.
 */
@Stable
internal class InspectorViewerSlot(private val scope: CoroutineScope) {
    var viewer by mutableStateOf<InspectorViewer?>(null)
        private set

    /** The open artifact's page; null for an image. */
    var artifact by mutableStateOf<ArtifactViewerState?>(null)
        private set

    /** The viewer shows in its full-screen dialog; the tab keeps no second view meanwhile. */
    var poppedOut by mutableStateOf(false)

    /** New versions load as they come, until another artifact opens or the tab closes. */
    var autoRefresh by mutableStateOf(false)
        private set

    private var shown by mutableStateOf<String?>(null)
    private var latest by mutableStateOf<String?>(null)

    val newVersion: Boolean
        get() = latest != shown && (viewer as? InspectorViewer.Artifact)?.reload != null

    /**
     * Opens [next], whose newest known change is [change]. The open one again stays as it is,
     * unless it comes with another text than the tab shows (a newer version from the gallery).
     */
    fun show(next: InspectorViewer, change: String?) {
        poppedOut = false
        val same = viewer?.key == next.key
        if (same && (next !is InspectorViewer.Artifact || artifact?.text == next.text)) return
        viewer = next
        artifact = (next as? InspectorViewer.Artifact)?.let { ArtifactViewerState(it.text) }
        if (!same) autoRefresh = false
        shown = change
        latest = change
    }

    fun close() {
        viewer = null
        artifact = null
        poppedOut = false
        autoRefresh = false
        shown = null
        latest = null
    }

    /** The open artifact's newest known [change]; auto-refresh loads it, otherwise it waits. */
    fun changed(change: String?) {
        if (viewer == null || change == null || change == latest) return
        latest = change
        if (autoRefresh) reload()
    }

    /** Loads the newest version; should it fail, the notice stays and offers it again. */
    fun reload() {
        val open = viewer as? InspectorViewer.Artifact ?: return
        val fetch = open.reload ?: return
        val page = artifact ?: return
        val previous = shown
        val target = latest
        shown = target
        scope.launch { if (!page.reload(fetch) && shown == target && artifact === page) shown = previous }
    }

    /** Loads this and every later version as it comes. */
    fun reloadAlways() {
        autoRefresh = true
        reload()
    }

    /** The user reloaded the page by hand, which fetched the newest version too. */
    fun reloaded() {
        shown = latest
    }
}

/** The artifact tab's content: the open viewer, apart from while it shows popped out. */
@Composable
internal fun InspectorViewerPane(slot: InspectorViewerSlot, onClose: () -> Unit) {
    PaneBackHandler(onBack = onClose)
    val viewer = slot.viewer ?: return
    Box(Modifier.fillMaxSize().testTag("inspectorViewer")) {
        // One sandboxed view at a time: the dialog shows it until it closes.
        if (slot.poppedOut) return@Box
        val insets = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
        when (viewer) {
            is InspectorViewer.Artifact -> {
                val page = slot.artifact ?: return@Box
                ArtifactViewerBody(
                    page, viewer.kind, viewer.path, viewer.title, viewer.reload,
                    modifier = Modifier.testTag("artifactTab"),
                    onPopOut = { slot.poppedOut = true },
                    onReloaded = slot::reloaded,
                    insets = insets,
                    notice = if (slot.newVersion) ({ ArtifactChangedNotice(slot::reload, slot::reloadAlways) }) else null,
                )
            }
            is InspectorViewer.Image ->
                ImageViewerBody(
                    viewer.path, viewer.sha256, viewer.mimeType, viewer.source,
                    modifier = Modifier.testTag("imageTab"),
                    onPopOut = { slot.poppedOut = true },
                )
        }
    }
}

/** The open viewer in the full-screen dialog a phone shows: popped out, or where no pane fits. */
@Composable
internal fun InspectorViewerDialog(slot: InspectorViewerSlot, onDismiss: () -> Unit) {
    when (val viewer = slot.viewer) {
        is InspectorViewer.Artifact -> {
            val page = slot.artifact ?: return
            ArtifactViewer(
                viewer.kind, viewer.path, viewer.title, viewer.text, viewer.reload, onDismiss,
                state = page,
                onReloaded = slot::reloaded,
            )
        }
        is InspectorViewer.Image -> ImageViewer(viewer.path, viewer.sha256, viewer.mimeType, viewer.source, onDismiss)
        null -> Unit
    }
}

/** A newer version of the page is on the Mac: load it now, or this and every later one. */
@Composable
private fun ArtifactChangedNotice(onReload: () -> Unit, onAlways: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("artifactChanged"),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp)) {
            Text(
                stringResource(R.string.remote_artifact_changed),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAlways, modifier = Modifier.testTag("artifactChangedAlways")) {
                    Text(stringResource(R.string.remote_artifact_changed_always))
                }
                TextButton(onClick = onReload, modifier = Modifier.testTag("artifactChangedReload")) {
                    Text(stringResource(R.string.remote_artifact_changed_reload))
                }
            }
        }
    }
}
