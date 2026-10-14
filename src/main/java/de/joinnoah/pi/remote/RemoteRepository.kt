package de.joinnoah.pi.remote

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

internal const val FOLLOW_UP_CAPABILITY = "session.follow_up.v1"
internal const val STEER_CAPABILITY = "session.steer.v1"
internal const val PROJECT_UNSHARE_CAPABILITY = "project.unshare.v1"
internal const val TOOL_OUTPUT_CAPABILITY = "session.tool_output.v1"
internal const val SESSION_FORK_CAPABILITY = "session.fork.v1"

/** How `session.fork` continues: resend the message, or put it back into the composer. */
enum class ForkMode(val wire: String) {
    RETRY("retry"),
    EDIT("edit"),
}

/** Why a full tool output download stopped without text. */
enum class ToolOutputFailure {
    UNSUPPORTED,
    NOT_FOUND,
    OFFLINE,
    FAILED,
}

/**
 * Progress of the one full tool output download for the selected session. [text] is non-null
 * only once every chunk arrived; [failure] is set when the download stopped early.
 */
data class ToolOutputDownload(
    val toolCallId: String,
    val loadedBytes: Long,
    val totalBytes: Long?,
    val text: String?,
    val truncated: Boolean,
    val isError: Boolean,
    val failure: ToolOutputFailure?,
)

internal fun canFollowUp(state: RemoteState): Boolean =
    (FOLLOW_UP_CAPABILITY in state.capabilities && state.session != null &&
        state.session.optionalText("origin") == "rpc") || childControlsAvailable(state)

internal fun canSteer(state: RemoteState): Boolean =
    (STEER_CAPABILITY in state.capabilities && state.session != null &&
        state.session.optionalText("origin") == "rpc") || childControlsAvailable(state)

data class RemoteSelection(
    val routeId: String? = null,
    val projectId: String? = null,
    val sessionId: String? = null,
)

/**
 * What a chat showed when it was last open, read while it opens again: a read-only preview, never
 * the chat's selection. [messages] may be empty for a session known only from its list row.
 */
data class ChatPreview(
    val session: JsonObject?,
    val messages: List<JsonObject>,
    val draft: String,
)

enum class ActivationMode {
    RESTORE,
    USER_OPEN,
}

/** Where a plain prompt stands between tapping send and the host's timeline having it. */
enum class PendingSendState {
    /** Journaled and on its way; the host has not answered yet. */
    SENDING,

    /** The host acknowledged it; the timeline may not show it yet. */
    CONFIRMED,

    /** The send failed or may not have left the phone. */
    NOT_CONFIRMED,
}

/**
 * A plain prompt the chat shows at its tail until the host's timeline has it. It lives in memory
 * for this run only; after a restart the unresolved send is back in the composer instead.
 * [afterMessageId] is the newest message the chat showed when the prompt was sent: only messages
 * after it can be the host's copy of this prompt, so an identical earlier prompt never matches.
 */
data class PendingSend(
    val mutationId: String,
    val text: String,
    val quote: MessageQuote?,
    val attachments: List<LocalAttachment>,
    val state: PendingSendState,
    val afterMessageId: String? = null,
)

data class RemoteState(
    val hosts: List<PairedHost> = emptyList(),
    /**
     * [hosts] were read from storage. Until then an empty list says nothing, so screens show a
     * placeholder instead of the first-run text, and nothing prunes state of Macs it lacks. Stays
     * false when the read failed.
     */
    val hostsLoaded: Boolean = false,
    /** Reading [hosts] from storage failed: no list is coming, though [hostsLoaded] stays false. */
    val hostsUnreadable: Boolean = false,
    val host: PairedHost? = null,
    val selection: RemoteSelection = RemoteSelection(),
    val loading: Boolean = false,
    /**
     * A chat destination shows [ChatPreview] content while its open runs: read-only, and only on
     * that destination's own state, never the repository's.
     */
    val preview: Boolean = false,
    val connection: Int = R.string.remote_offline,
    val connected: Boolean = false,
    /**
     * The shown questions, configuration, advisor, commands, status and capabilities are the
     * last-known ones of a lost connection: kept on screen while the same Mac reconnects, until
     * the activation after the reconnect has read them again. Actions stay gated on [connected].
     */
    val stale: Boolean = false,
    /** Epoch millis of the loss that made the state [stale]; null while it is not. */
    val staleSince: Long? = null,
    val error: Int? = null,
    /** Epoch millis of the next automatic reconnect attempt, or null when none is scheduled. */
    val reconnectAt: Long? = null,
    /** The host whose device was denied (paused or revoked) by the current [error], if any. */
    val deniedRouteId: String? = null,
    /**
     * When the pairing waiting for approval on the Mac expires, in
     * [android.os.SystemClock.elapsedRealtime] millis (see [Diagnostics.now]); null unless the Mac
     * is asking the user right now.
     */
    val pairingDeadline: Long? = null,
    val projects: List<JsonObject> = emptyList(),
    val projectChats: List<ProjectChatSummary> = emptyList(),
    /**
     * Each project's sessions by project ID, for views across projects: every list the Mac sent
     * for [host] on this or an earlier connection, kept fresh by live status events. Unlike
     * [projectChats] it is not cut to the featured few.
     */
    val projectSessions: Map<String, ProjectSessions> = emptyMap(),
    val project: JsonObject? = null,
    val sessions: List<JsonObject> = emptyList(),
    /** [sessions] came from a sessions.list on the current connection, not from a cache. */
    val sessionsFresh: Boolean = false,
    val session: JsonObject? = null,
    val messages: List<JsonObject> = emptyList(),
    val questions: List<JsonObject> = emptyList(),
    val status: String = "offline",
    val nextCursor: String? = null,
    val draft: String = "",
    val quote: MessageQuote? = null,
    val attachments: List<LocalAttachment> = emptyList(),
    val importingAttachments: Boolean = false,
    val attachmentProgress: Float? = null,
    val sending: Boolean = false,
    val uncertain: Boolean = false,
    val followUps: List<PendingFollowUp> = emptyList(),
    /** The plain prompt the chat shows at its tail until the timeline has it; null when none. */
    val pendingSend: PendingSend? = null,
    /**
     * Phone stops and resumes of subagent children, by child session ID. Memory only on purpose:
     * "stopped by you" describes this app run; after a restart the host's status is the truth.
     */
    val childControls: Map<String, ChildControl> = emptyMap(),
    /**
     * Children whose parent answered `unsupported` to a phone control: its extension predates
     * subagent control, so these fall back to the plain abort until the selection or connection
     * changes.
     */
    val childControlUnsupported: Set<String> = emptySet(),
    val answering: Set<String> = emptySet(),
    /** Questions the user cannot answer from here right now, by question ID. */
    val questionBlocks: Map<String, QuestionBlock> = emptyMap(),
    val capabilities: Set<String> = emptySet(),
    /** True once the connection's `projects.list` reply has added its route capabilities. */
    val capabilitiesKnown: Boolean = false,
    val unavailableCapabilities: Set<String> = emptySet(),
    val configuration: SessionConfiguration? = null,
    val configurationLoading: Boolean = false,
    val configurationChanging: Boolean = false,
    val contextUsage: SessionContextUsage? = null,
    val contextLoading: Boolean = false,
    val compactionRequesting: Boolean = false,
    val advisor: SessionAdvisor? = null,
    val advisorLoading: Boolean = false,
    val advisorChanging: Boolean = false,
    val commands: List<RemoteCommand> = emptyList(),
    val commandsLoading: Boolean = false,
    val commandsTruncated: Boolean = false,
    val commandNotice: Int? = null,
    /** The latest extension notice of the selected session, until the chat has shown it. */
    val notice: SessionNotice? = null,
    val compaction: SessionCompaction? = null,
    val toolOutput: ToolOutputDownload? = null,
    /** The changes view of the selected session; null while it is closed. */
    val changes: ChangesState? = null,
    /** The file browser of the selected session; null while it is closed. */
    val files: FilesState? = null,
    /** Background jobs of the selected session (`session.background_jobs.v1`); null until listed. */
    val jobs: JobsState? = null,
    val folders: FolderBrowserState = FolderBrowserState(),
    /** The provider list and login flow of the host shown on the Providers screen. */
    val providerAuth: ProviderAuthState = ProviderAuthState(),
)

/** The app's connection counts as healthy for a notification's request while a frame arrived this recently. */
internal const val HEALTHY_CONNECTION_MILLIS = 25_000L

/** How long a notification's request waits on the app's connection before it uses its own. */
internal const val HEALTHY_REQUEST_TIMEOUT_MILLIS = 3_000L

/** One content URI to attach; [photo] imports it as an image (scaled JPEG), otherwise as a file. */
data class Picked(val uri: String, val photo: Boolean)

interface RemoteRepository {
    val state: StateFlow<RemoteState>

    suspend fun activate(
        selection: RemoteSelection,
        mode: ActivationMode = ActivationMode.RESTORE,
    ): RemoteSelection

    suspend fun createSession(routeId: String, projectId: String): RemoteSelection

    suspend fun openNotification(routeId: String, sessionId: String): RemoteSelection?

    /**
     * The project of [sessionId] on the paired [routeId] as the app already knows it, from its
     * caches and stores, without asking the Mac; null when only the Mac knows. A tapped
     * notification shows its chat at once when this knows the project.
     */
    suspend fun notificationProject(routeId: String, sessionId: String): String? = null

    /**
     * Runs [block] over the app's own connection when it is connected to [routeId] and heard from
     * the Mac within [HEALTHY_CONNECTION_MILLIS]; each request on it gives up after
     * [HEALTHY_REQUEST_TIMEOUT_MILLIS]. Returns null without running [block] when the connection is
     * not known to be healthy, so the caller opens a connection of its own. A host error passes
     * through as a [RemoteCommandException]. A plain [RemoteCommands.request] that was not sent,
     * timed out or was lost also ends the call with null; a [MutationCommands.mutate] under the
     * caller's mutation ID instead throws [MutationNotSentException] when nothing was sent, and the
     * timeout or loss otherwise, so the caller decides whether to send it again. Any thread may
     * call it. [onSent] runs as each request goes out, so a caller that falls back after a timeout
     * knows the Mac may already have it.
     */
    suspend fun <T> withHealthyConnection(
        routeId: String,
        onSent: () -> Unit = {},
        block: suspend (MutationCommands) -> T,
    ): T? = null

    /**
     * Starts a new session that continues from just before the user message [messageId] of the
     * selected chat [source], through `session.fork` (only with `session.fork.v1`). Returns the
     * fork's selection, or [source] again when the host refused. An edit fork puts the message
     * into the new chat's composer.
     */
    suspend fun forkSession(source: RemoteSelection, messageId: String, mode: ForkMode): RemoteSelection =
        source

    /**
     * Starts a new session that continues from the tree node [nodeId] of the selected chat
     * [source], through `session.tree.fork` (only with `session.tree.v1`). Returns the fork's
     * selection, or [source] again when the host refused. A fork from a user message puts the
     * message into the new chat's composer; any other node opens the fork as it is.
     */
    suspend fun forkSessionAtNode(source: RemoteSelection, nodeId: String): RemoteSelection = source

    fun cancelSelection()

    /** The cached content of the chat [selection] for a preview while it opens, or null. */
    fun peekCachedChat(selection: RemoteSelection): ChatPreview? = null

    suspend fun pair(text: String): RemoteSelection?

    /** Gives up the pairing that is running, so [pair] returns without a host and loading ends. */
    fun cancelPairing() {}

    fun remove(routeId: String)

    fun disconnect()

    fun setForeground(foreground: Boolean) {}

    /** A validated default network, or none: [setNetwork] for callers that only see validation. */
    fun setValidatedNetwork(identity: String?) = setNetwork(identity, validated = identity != null)

    /**
     * The default network once it can carry traffic, or null when there is none. [validated] says
     * whether Android validated it; one that never validates still allows reconnects. [path]
     * fingerprints its route, so a route that moves on the same network is probed, not torn down.
     */
    fun setNetwork(identity: String?, validated: Boolean, path: String? = null) {}

    fun refresh()

    fun renameSession(sessionId: String, title: String) {}

    fun closeSession(sessionId: String) {}

    fun unshareProject(projectId: String) {}

    fun older()

    fun draft(text: String)

    /**
     * Sets the draft typed in [selection]. A composer pushes its text late (after a pause, on
     * stop, when the chat changes), so a write for a chat that is no longer selected never lands
     * in the current one: a repository that keeps drafts per chat files it under [selection],
     * and this default drops it.
     */
    fun draft(selection: RemoteSelection, text: String) {
        if (state.value.selection == selection) draft(text)
    }

    fun quote(messageId: String?)

    fun importAttachments(selection: RemoteSelection, uris: List<String>, photo: Boolean) {}

    /**
     * Imports a mixed [batch] of images and files into [selection]'s draft in one run, with the
     * same limits as [importAttachments]. [onDone] runs exactly once, after the import read every
     * URI or refused the batch, so a caller can then give back the URIs' read grants (a drop's
     * permissions). This default suits a repository whose single import reads synchronously.
     */
    fun importAttachments(selection: RemoteSelection, batch: List<Picked>, onDone: () -> Unit) {
        try {
            batch.groupBy { it.photo }.forEach { (photo, items) -> importAttachments(selection, items.map { it.uri }, photo) }
        } finally {
            onDone()
        }
    }

    fun removeAttachment(id: String) {}

    fun cancelAttachmentWork() {}

    fun refreshConfiguration() {}

    fun refreshContextUsage() {}

    fun compactContext() {}

    fun refreshAdvisor() {}

    fun setAdvisor(provider: String?, id: String? = null, level: String? = null) {}


    fun setModel(provider: String, id: String) {}

    fun setThinkingLevel(level: String) {}

    /** Changes the given session settings; null leaves a setting as it is. */
    fun changeSettings(autoCompaction: Boolean? = null, steeringMode: String? = null, followUpMode: String? = null) {}

    fun refreshCommands() {}

    fun selectCommand(command: RemoteCommand) {}

    fun prompt()

    fun followUp() {}

    fun steer() {}

    fun dismissFollowUp(requestId: String) {}

    /** `session.subagent.stop` for the selected running child; only with [SUBAGENT_CONTROL_CAPABILITY]. */
    fun stopChild() {}

    /** `session.subagent.resume` of the selected child with [message]; only with [SUBAGENT_CONTROL_CAPABILITY]. */
    fun resumeChild(message: String) {}

    fun abort()

    fun abortSession(sessionId: String) {}

    fun refreshSessions() {}

    /**
     * Reads the lists on screen again behind what they show, for a pull to refresh: the projects,
     * and the selected project's sessions or every project's featured chats. Without a connection
     * it asks for one, as [refresh] does.
     */
    fun revalidateLists() {}

    fun loadToolOutput(toolCallId: String) {}

    fun cancelToolOutput() {}

    /** Lists the selected session's background jobs; only with [BACKGROUND_JOBS_CAPABILITY]. */
    fun refreshJobs() {}

    fun openJobs() {}

    fun closeJobs() {}

    /** Shows [jobId]'s live output, watching it again at least every 30 seconds while open. */
    fun openJob(jobId: String) {}

    fun closeJob() {}

    /** Stops [jobId] through `session.jobs.kill`; the caller confirmed it with the user. */
    fun stopJob(jobId: String) {}

    fun answer(questionId: String, answer: JsonObject)

    /** `session.git.status`; only with [GIT_CAPABILITY]. Throws [RemoteRequestException]. */
    suspend fun gitStatus(sessionId: String, base: GitBase): GitStatus =
        throw RemoteRequestException.unsupported()

    /** `session.git.diff` for a path of the snapshot's list. Throws [RemoteRequestException]. */
    suspend fun gitDiff(sessionId: String, snapshotId: String, path: String): GitDiff =
        throw RemoteRequestException.unsupported()

    /** `session.git.log` for the snapshot's base. Throws [RemoteRequestException]. */
    suspend fun gitLog(sessionId: String, snapshotId: String): GitLog =
        throw RemoteRequestException.unsupported()

    /** Opens [RemoteState.changes] for the selected session, starting at session start. */
    fun openChanges() {}

    /**
     * Like [openChanges]; with [exclusive] false the files stay open beside the changes, as tabs
     * of a side inspector do. Exclusive, as the phone shows one at a time, it closes them.
     */
    fun openChanges(exclusive: Boolean) = openChanges()

    fun closeChanges() {}

    fun selectChangesBase(base: GitBase) {}

    fun reloadChanges() {}

    /** Shows the diff of [path], or the file list again for null. */
    fun openChangesFile(path: String?) {}

    /** Shows the diff of [path] from the changes of the subagent session [sessionId]. */
    fun openChangesSubagentFile(sessionId: String, path: String) {}

    fun requestChangesPreview(path: String) {}

    /** `session.files.list`; only with [FILES_CAPABILITY]. Throws [RemoteRequestException]. */
    suspend fun filesList(sessionId: String, path: String, after: String?): FileListing =
        throw RemoteRequestException.unsupported()

    /** `session.files.read` of one page; a later page carries the first page's version. */
    suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?): FileChunk =
        throw RemoteRequestException.unsupported()

    /**
     * `session.files.write`: replaces [path] with [content] when its version is still
     * [baseVersion]; only with [FILES_WRITE_CAPABILITY]. Throws [RemoteRequestException], with
     * code `conflict` when the file changed. A [requestId] sent before gets the host's first
     * answer again, so a retry after a lost reply cannot write twice.
     */
    suspend fun filesWrite(
        sessionId: String,
        path: String,
        baseVersion: String,
        content: String,
        requestId: String = Wire.random(),
    ): FileWritten = throw RemoteRequestException.unsupported()

    /**
     * The bytes of an image this device sent in [sessionId]'s chat, from memory or read from the
     * host in chunks (`session.attachments.get`, only with [ATTACHMENT_READ_CAPABILITY]).
     */
    suspend fun readAttachment(sessionId: String, attachment: RemoteAttachment): AttachmentReadResult =
        AttachmentReadResult.Unsupported

    /**
     * An image the agent mentioned, by [path] (relative to the session folder or POSIX absolute),
     * read from the host in chunks (`session.files.media`, only with [FILES_MEDIA_CAPABILITY]). The
     * first-loaded bytes stay in memory; [fresh] reads the file again and replaces them, so a share or save and the next view agree.
     */
    suspend fun readProjectImage(sessionId: String, path: String, fresh: Boolean = false): ProjectImageResult =
        ProjectImageResult.Unsupported

    /**
     * An HTML file the agent mentioned, by [path] (relative to the session folder or POSIX absolute),
     * read like [readProjectImage] but only with [FILES_ARTIFACT_CAPABILITY]. The document stays in
     * memory only; [fresh] reads the file again.
     */
    suspend fun readProjectArtifact(sessionId: String, path: String, fresh: Boolean = false): ProjectArtifactResult =
        ProjectArtifactResult.UnsupportedHost

    /**
     * The HTML, SVG and Mermaid artifacts recorded for [sessionId] (`session.artifacts.list`, only
     * with [ARTIFACTS_CAPABILITY]), newest first.
     */
    suspend fun listSessionArtifacts(sessionId: String): SessionArtifactListResult =
        SessionArtifactListResult.Unsupported

    /**
     * One artifact of [sessionId] (`session.artifacts.open`, then `session.files.media.get`, only
     * with [ARTIFACTS_CAPABILITY]). [version] defaults to the latest; a version asked for is an
     * immutable snapshot and stays in memory, the latest is read each time. [type] is the listed
     * type: an answer of another type is [SessionArtifactOpenResult.NotAnArtifact] before any byte is
     * read. [sha256] is the listed digest of that version: a kept copy with another digest is not used
     * and is replaced.
     */
    suspend fun openSessionArtifact(
        sessionId: String,
        artifactId: String,
        version: Int? = null,
        type: ArtifactType? = null,
        sha256: String? = null,
    ): SessionArtifactOpenResult =
        SessionArtifactOpenResult.Unsupported

    /**
     * Renders [sessionId] as one HTML file and reads it from the host in chunks (`session.export`,
     * `session.export.get`, only with [EXPORT_CAPABILITY] and for host-owned sessions).
     */
    suspend fun exportSession(sessionId: String): ExportResult =
        ExportResult.Failed(ExportFailure.UNSUPPORTED)

    /**
     * Reloads pi's extensions, skills, prompt templates, themes and context files in [sessionId]
     * (`session.reload`, only with [RELOAD_CAPABILITY] and for host-owned sessions), then refreshes
     * the chat and the command catalog.
     */
    suspend fun reloadSession(sessionId: String): ReloadResult =
        ReloadResult.Failed(ReloadFailure.Unsupported)

    /** Reads the tree of [sessionId] (`session.tree`, only with [SESSION_TREE_CAPABILITY]). */
    suspend fun loadSessionTree(sessionId: String): TreeLoadResult =
        TreeLoadResult.Failed(TreeFailure.UNSUPPORTED)

    /**
     * Moves the leaf of [sessionId] to [nodeId] in place (`session.tree.navigate`). On success a
     * returned text is already in the composer and the chat has been refreshed; on an unknown
     * result the chat is refreshed too.
     */
    suspend fun navigateSessionTree(sessionId: String, nodeId: String, summarize: Boolean): TreeNavigateResult =
        TreeNavigateResult.Failed(TreeFailure.UNSUPPORTED)

    /** Opens [RemoteState.files] for the selected session at its folder. */
    fun openFiles() {}

    /** Like [openFiles]; with [exclusive] false the changes stay open beside the files. */
    fun openFiles(exclusive: Boolean) = openFiles()

    fun closeFiles() {}

    /** Shows the folder [path], `""` being the session folder. */
    fun openFilesDir(path: String) {}

    /** Shows the file [path], or the folder again for null. */
    fun openFilesFile(path: String?) {}

    /**
     * Opens Files at the folder of [path] (relative to the session folder) with the file open and
     * [lines] picked and in view; [exclusive] as for [openFiles]. False, with nothing opened, for a
     * path outside the session folder or while an open editor keeps the pane.
     */
    fun openFileAt(path: String, lines: LineSelection?, exclusive: Boolean = true): Boolean {
        if (!isSessionPath(path)) return false
        openFiles(exclusive)
        openFilesDir(parentPath(path))
        openFilesFile(path)
        selectFileLines(lines)
        return true
    }

    /** The next page of the open file, or of the shown folder. */
    fun loadMoreFiles() {}

    fun reloadFiles() {}

    fun requestFilesPreview(path: String) {}

    fun showFilesPeek(path: String, type: FileEntryType) {}

    fun dismissFilesPeek() {}

    fun selectFileLines(selection: LineSelection?) {}

    /** Opens the open file in the editor when [canEditFile] allows it. */
    fun startFileEditing() {}

    fun updateFileEditorText(text: String) {}

    /** Saves the editor's text over the version it was read or last saved as. */
    fun saveFileEditor() {}

    /** Leaves the editor and drops its text; a save still on its way is ignored. */
    fun discardFileEditor() {}

    /** After a conflict: drops the edit and reads the file again. */
    fun reloadFileAfterConflict() {}

    /** Discards the unsaved edit and runs the action that waited for it ([FileEditor.leaveRequest]). */
    fun confirmFileEditorLeave() {}

    /** Keeps editing; the action that waited for the discard prompt is dropped. */
    fun cancelFileEditorLeave() {}

    /**
     * The composer text that hands the edit to the agent as a diff, with [intro] (default
     * [editorPromptIntro]) above it; null when nothing changed or no editor is open.
     */
    fun fileEditorPrompt(intro: String? = null): EditorPrompt? = null

    /**
     * Adds [comment], replacing one on the same line, and keeps it in the session's draft. Returns
     * false when it was not saved: invalid, over [MAX_REVIEW_COMMENTS], or no draft storage.
     */
    fun setReviewComment(comment: ReviewComment): Boolean = false

    fun removeReviewComment(comment: ReviewComment) {}

    fun clearReviewComments() {}

    /** Shows [path] of [routeId]'s browse root; sends `fs.browse` only with `project.open.v1`. */
    fun browseFolder(routeId: String, path: String) {}

    fun createFolder(name: String) {}

    fun cloneRepository(url: String, name: String?) {}

    fun dismissClone() {}

    fun dismissFolderNotice() {}

    fun cancelFolderTrust() {}

    /** Re-reads the shown host's running clone, whose events may have been lost offline. */
    fun refreshClone() {}

    /**
     * Starts opening [path] synchronously, so a second tap is ignored. Returns the id for
     * [openFolder] and [endOpenFolder], or null when the open may not proceed. [confirmed] is the
     * prompt the user accepted; only it may add `trust` to the request.
     */
    fun beginOpenFolder(routeId: String, path: String, confirmed: FolderTrustPrompt? = null): Long? = null

    /** Ends the open [openId] if it is still the current one; a stale id changes nothing. */
    fun endOpenFolder(openId: Long) {}

    /**
     * Sends `project.open` for an open begun with [beginOpenFolder]. The host decides; when it asks
     * for confirmation, [FolderBrowserState.trust] holds the prompt. Returns the new chat's
     * selection, or null when it asked or failed.
     */
    suspend fun openFolder(routeId: String, path: String, openId: Long): RemoteSelection? = null

    /** Shows [routeId]'s providers; sends `provider.auth.list` only with [PROVIDER_AUTH_CAPABILITY]. */
    fun browseProviders(routeId: String) {}

    /** Re-reads the provider list, which also recovers the login flow after a reconnect. */
    fun refreshProviders() {}

    /**
     * Starts a login for [providerId]. [replace] is set only after the user confirmed replacing a
     * stored credential.
     */
    fun startLogin(providerId: String, method: ProviderAuthMethod, replace: Boolean) {}

    /**
     * Answers the pending prompt [promptId] with [value]. The value may be a secret: it is sent and
     * dropped, never stored or logged. Returns whether the answer was sent; on false the caller
     * keeps what the user typed.
     */
    fun answerLogin(promptId: String, value: String): Boolean = false

    fun cancelLogin() {}

    /** Removes the stored credential of [providerId]; the caller confirmed it with the user. */
    fun logoutProvider(providerId: String) {}

    /** Hides the finished login's result. */
    fun dismissLogin() {}

    fun dismissProviderNotice() {}

    /** Forgets the [SessionNotice] with [id] once the chat has shown it. */
    fun dismissNotice(id: Long) {}

    fun dismissError()

    fun reportError(resource: Int)

    fun setPushToken(token: String?)

    suspend fun flush() {}
}
