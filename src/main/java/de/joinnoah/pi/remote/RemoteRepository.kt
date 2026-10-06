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

enum class ActivationMode {
    RESTORE,
    USER_OPEN,
}

data class RemoteState(
    val hosts: List<PairedHost> = emptyList(),
    val host: PairedHost? = null,
    val selection: RemoteSelection = RemoteSelection(),
    val loading: Boolean = false,
    val connection: Int = R.string.remote_offline,
    val connected: Boolean = false,
    val error: Int? = null,
    val projects: List<JsonObject> = emptyList(),
    val projectChats: List<ProjectChatSummary> = emptyList(),
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

interface RemoteRepository {
    val state: StateFlow<RemoteState>

    suspend fun activate(
        selection: RemoteSelection,
        mode: ActivationMode = ActivationMode.RESTORE,
    ): RemoteSelection

    suspend fun createSession(routeId: String, projectId: String): RemoteSelection

    suspend fun openNotification(routeId: String, sessionId: String): RemoteSelection?

    /**
     * Starts a new session that continues from just before the user message [messageId] of the
     * selected chat [source], through `session.fork` (only with `session.fork.v1`). Returns the
     * fork's selection, or [source] again when the host refused. An edit fork puts the message
     * into the new chat's composer.
     */
    suspend fun forkSession(source: RemoteSelection, messageId: String, mode: ForkMode): RemoteSelection =
        source

    fun cancelSelection()

    suspend fun pair(text: String): RemoteSelection?

    fun remove(routeId: String)

    fun disconnect()

    fun setForeground(foreground: Boolean) {}

    fun setValidatedNetwork(identity: String?) {}

    fun refresh()

    fun renameSession(sessionId: String, title: String) {}

    fun closeSession(sessionId: String) {}

    fun unshareProject(projectId: String) {}

    fun older()

    fun draft(text: String)

    fun quote(messageId: String?)

    fun importAttachments(selection: RemoteSelection, uris: List<String>, photo: Boolean) {}

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
        throw RemoteRequestException("unsupported")

    /** `session.git.diff` for a path of the snapshot's list. Throws [RemoteRequestException]. */
    suspend fun gitDiff(sessionId: String, snapshotId: String, path: String): GitDiff =
        throw RemoteRequestException("unsupported")

    /** `session.git.log` for the snapshot's base. Throws [RemoteRequestException]. */
    suspend fun gitLog(sessionId: String, snapshotId: String): GitLog =
        throw RemoteRequestException("unsupported")

    /** Opens [RemoteState.changes] for the selected session, starting at session start. */
    fun openChanges() {}

    fun closeChanges() {}

    fun selectChangesBase(base: GitBase) {}

    fun reloadChanges() {}

    /** Shows the diff of [path], or the file list again for null. */
    fun openChangesFile(path: String?) {}

    /** `session.files.list`; only with [FILES_CAPABILITY]. Throws [RemoteRequestException]. */
    suspend fun filesList(sessionId: String, path: String, after: String?): FileListing =
        throw RemoteRequestException("unsupported")

    /** `session.files.read` of one page; a later page carries the first page's version. */
    suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?): FileChunk =
        throw RemoteRequestException("unsupported")

    /**
     * The bytes of an image this device sent in [sessionId]'s chat, from memory or read from the
     * host in chunks (`session.attachments.get`, only with [ATTACHMENT_READ_CAPABILITY]).
     */
    suspend fun readAttachment(sessionId: String, attachment: RemoteAttachment): AttachmentReadResult =
        AttachmentReadResult.Unsupported

    /** Opens [RemoteState.files] for the selected session at its folder. */
    fun openFiles() {}

    fun closeFiles() {}

    /** Shows the folder [path], `""` being the session folder. */
    fun openFilesDir(path: String) {}

    /** Shows the file [path], or the folder again for null. */
    fun openFilesFile(path: String?) {}

    /** The next page of the open file, or of the shown folder. */
    fun loadMoreFiles() {}

    fun reloadFiles() {}

    fun requestFilesPreview(path: String) {}

    fun showFilesPeek(path: String, type: FileEntryType) {}

    fun dismissFilesPeek() {}

    fun selectFileLines(selection: LineSelection?) {}

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

    fun dismissError()

    fun reportError(resource: Int)

    fun setPushToken(token: String?)

    suspend fun flush() {}
}
