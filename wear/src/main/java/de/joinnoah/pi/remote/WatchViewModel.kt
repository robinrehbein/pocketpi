package de.joinnoah.pi.remote

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class WatchViewModel(val repository: RemoteRepository, private val saved: SavedStateHandle) : ViewModel() {
    private fun restored(): WatchRoute {
        val screen = runCatching { WatchScreen.valueOf(saved["screen"] ?: "HOSTS") }.getOrDefault(WatchScreen.HOSTS)
        val selection = RemoteSelection(saved["route"], saved["project"], saved["session"])
        // Editing and confirmation screens have memory-only content; never restore their actions.
        val safeScreen = if (screen in setOf(WatchScreen.CONFIRM, WatchScreen.REVIEW, WatchScreen.ADVISOR_LEVELS, WatchScreen.RENAME))
            canonicalScreen(selection) else screen
        return WatchRoute(safeScreen, selection, saved["detail"])
    }
    private val current = MutableStateFlow(restored())
    val route = current.asStateFlow()
    private val working = MutableStateFlow(false)
    val busy = working.asStateFlow()
    var confirmation: WatchConfirmation? = null
        private set
    var advisorChoice: AdvisorChoice? = null
        private set
    var reviewLine: DiffLine? = null
        private set

    init {
        viewModelScope.launch {
            working.value = true
            try {
                val restored = current.value
                val selection = repository.activate(restored.selection, ActivationMode.RESTORE)
                setRoute(if (selection == restored.selection) restored else WatchRoute(canonicalScreen(selection), selection))
                openPanel(current.value.screen)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { repository.reportError(R.string.remote_request_error) }
            finally { working.value = false }
            repository.state.collect { state ->
                // Refresh/revocation may invalidate a route. Never leave controls bound to stale IDs.
                if (!working.value && !state.loading && state.selection != current.value.selection &&
                    current.value.screen !in setOf(WatchScreen.HOSTS, WatchScreen.PAIR, WatchScreen.SETTINGS)) {
                    confirmation = null
                    setRoute(WatchRoute(canonicalScreen(state.selection), state.selection))
                }
            }
        }
    }

    private fun setRoute(route: WatchRoute) {
        current.value = route
        saved["screen"] = route.screen.name
        saved["route"] = route.selection.routeId
        saved["project"] = route.selection.projectId
        saved["session"] = route.selection.sessionId
        saved["detail"] = route.detailId
    }

    fun open(screen: WatchScreen, detail: String? = null) {
        if (working.value) return
        if (screen != current.value.screen) closePanel(current.value.screen)
        setRoute(current.value.copy(screen = screen, detailId = detail))
        openPanel(screen)
    }
    private fun openPanel(screen: WatchScreen) {
        when (screen) {
            WatchScreen.FILES -> repository.openFiles()
            WatchScreen.CHANGES -> repository.openChanges()
            WatchScreen.JOBS -> repository.openJobs()
            WatchScreen.CONTROLS -> { repository.refreshConfiguration(); repository.refreshContextUsage(); repository.refreshAdvisor(); repository.refreshCommands() }
            WatchScreen.FOLDERS -> current.value.selection.routeId?.let { repository.browseFolder(it, repository.state.value.folders.path) }
            else -> Unit
        }
    }
    private fun closePanel(screen: WatchScreen) {
        when (screen) {
            WatchScreen.FILES -> repository.closeFiles()
            WatchScreen.CHANGES -> repository.closeChanges()
            WatchScreen.JOBS -> repository.closeJobs()
            WatchScreen.MESSAGE -> repository.cancelToolOutput()
            else -> Unit
        }
    }
    private fun run(block: suspend () -> Unit) {
        if (working.value) return
        working.value = true
        viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { repository.reportError(R.string.remote_request_error) }
            finally { working.value = false }
        }
    }
    fun navigate(selection: RemoteSelection, screen: WatchScreen = canonicalScreen(selection)) = run {
        closePanel(current.value.screen)
        val canonical = repository.activate(selection, ActivationMode.USER_OPEN)
        setRoute(WatchRoute(if (canonical == selection) screen else canonicalScreen(canonical), canonical))
        openPanel(current.value.screen)
    }
    private var notificationGeneration = 0L
    fun openNotification(target: NotificationTarget) {
        val generation = ++notificationGeneration
        viewModelScope.launch {
            working.first { !it }
            if (generation != notificationGeneration) return@launch
            run {
                val selection = repository.openNotification(target.routeId, target.sessionId) ?: return@run
                closePanel(current.value.screen)
                val jobs = target.jobs && canUseBackgroundJobs(repository.state.value)
                setRoute(WatchRoute(if (jobs) WatchScreen.JOBS else canonicalScreen(selection), selection))
                if (jobs) {
                    repository.openJobs()
                    target.jobId?.let(repository::openJob)
                }
            }
        }
    }
    fun execute(expected: WatchRoute, action: (RemoteRepository) -> Unit) {
        if (!working.value && current.value == expected && repository.state.value.selection == expected.selection && !repository.state.value.loading)
            action(repository)
    }
    fun pair(code: String) = run {
        repository.pair(code)?.let { selection -> setRoute(WatchRoute(canonicalScreen(selection), selection)) }
    }
    fun createSession() {
        val selection = current.value.selection
        val route = selection.routeId ?: return
        val project = selection.projectId ?: return
        run {
            val canonical = repository.createSession(route, project)
            setRoute(WatchRoute(canonicalScreen(canonical), canonical))
        }
    }
    fun back() {
        if (working.value) return
        val route = current.value
        when (route.screen) {
            WatchScreen.HOSTS -> Unit
            WatchScreen.PAIR, WatchScreen.SETTINGS -> open(WatchScreen.HOSTS)
            WatchScreen.PROJECTS -> navigate(RemoteSelection())
            WatchScreen.PROJECT_ACTIONS -> open(WatchScreen.PROJECTS)
            WatchScreen.SESSIONS -> navigate(RemoteSelection(route.selection.routeId))
            WatchScreen.CHAT -> navigate(route.selection.copy(sessionId = null))
            WatchScreen.MESSAGE, WatchScreen.COMPOSE, WatchScreen.CONTROLS, WatchScreen.QUESTIONS, WatchScreen.JOURNAL -> open(WatchScreen.CHAT)
            WatchScreen.QUESTION -> open(WatchScreen.QUESTIONS)
            WatchScreen.MODELS, WatchScreen.THINKING, WatchScreen.ADVISOR, WatchScreen.COMMANDS, WatchScreen.RENAME -> open(WatchScreen.CONTROLS)
            WatchScreen.ADVISOR_LEVELS -> open(WatchScreen.ADVISOR)
            WatchScreen.FILES -> {
                val files = repository.state.value.files
                when {
                    files?.file != null -> repository.openFilesFile(null)
                    !files?.path.isNullOrEmpty() -> repository.openFilesDir(parentPath(files.path))
                    else -> open(WatchScreen.CONTROLS)
                }
            }
            WatchScreen.CHANGES -> if (repository.state.value.changes?.file != null) repository.openChangesFile(null) else open(WatchScreen.CONTROLS)
            WatchScreen.REVIEW -> {
                // Keep the existing changes snapshot; re-opening it would invalidate the line.
                setRoute(route.copy(screen = WatchScreen.CHANGES, detailId = null)); reviewLine = null
            }
            WatchScreen.JOBS -> if (repository.state.value.jobs?.view != null) repository.closeJob() else open(WatchScreen.CONTROLS)
            WatchScreen.FOLDERS -> if (repository.state.value.folders.path.isNotEmpty())
                route.selection.routeId?.let { repository.browseFolder(it, parentFolderPath(repository.state.value.folders.path)) }
                else open(WatchScreen.PROJECTS)
            WatchScreen.FOLDER_ACTIONS -> open(WatchScreen.FOLDERS)
            WatchScreen.NEW_FOLDER, WatchScreen.CLONE -> open(WatchScreen.FOLDER_ACTIONS)
            WatchScreen.CONFIRM -> {
                val source = confirmation?.source ?: WatchRoute(canonicalScreen(route.selection), route.selection)
                confirmation = null
                setRoute(source)
            }
        }
    }
    fun ask(kind: WatchConfirmationKind, target: String? = null, trust: FolderTrustPrompt? = null) {
        if (working.value || current.value.screen == WatchScreen.CONFIRM) return
        confirmation = WatchConfirmation(kind, current.value, target, trust)
        setRoute(current.value.copy(screen = WatchScreen.CONFIRM))
    }
    fun confirm() {
        val request = confirmation ?: return
        if (repository.state.value.selection != request.source.selection || working.value) return
        confirmation = null
        setRoute(request.source)
        when (request.kind) {
            WatchConfirmationKind.STOP -> if (childControlsAvailable(repository.state.value)) repository.stopChild() else repository.abort()
            WatchConfirmationKind.UNPAIR -> request.source.selection.routeId?.let { repository.remove(it); navigate(RemoteSelection()) }
            WatchConfirmationKind.UNSHARE -> request.target?.let { repository.unshareProject(it); open(WatchScreen.PROJECTS) }
            // The repository refreshes and falls back to Sessions only after the close was accepted.
            // Navigating here would invalidate the asynchronous close request's selection epoch.
            WatchConfirmationKind.CLOSE -> request.source.selection.sessionId?.let(repository::closeSession)
            WatchConfirmationKind.KILL_JOB -> request.target?.let(repository::stopJob)
            WatchConfirmationKind.DISMISS_SEND -> request.target?.let(repository::dismissFollowUp)
            WatchConfirmationKind.FORK_RETRY, WatchConfirmationKind.FORK_EDIT -> {
                val id = request.target ?: return
                run {
                    val canonical = repository.forkSession(request.source.selection, id,
                        if (request.kind == WatchConfirmationKind.FORK_RETRY) ForkMode.RETRY else ForkMode.EDIT)
                    setRoute(WatchRoute(canonicalScreen(canonical), canonical))
                }
            }
            WatchConfirmationKind.TRUST -> request.trust?.let { openFolder(it.path, it) }
        }
    }
    fun openFolder(path: String, trust: FolderTrustPrompt? = null) {
        val route = current.value.selection.routeId ?: return
        val id = repository.beginOpenFolder(route, path, trust) ?: return
        run {
            try {
                repository.openFolder(route, path, id)?.let { selection ->
                    setRoute(WatchRoute(canonicalScreen(selection), selection))
                }
            } finally { repository.endOpenFolder(id) }
        }
    }
    fun chooseAdvisor(choice: AdvisorChoice) { advisorChoice = choice; open(WatchScreen.ADVISOR_LEVELS) }
    fun beginReview(line: DiffLine) {
        if ((line.newLine ?: line.oldLine ?: 0) <= 0) return
        reviewLine = line
        setRoute(current.value.copy(screen = WatchScreen.REVIEW))
    }
    fun saveReview(text: String): Boolean {
        val changes = repository.state.value.changes ?: return false
        val path = changes.file ?: return false
        val line = reviewLine ?: return false
        val saved = repository.setReviewComment(ReviewComment(path, line.oldLine, line.newLine,
            line.text.take(MAX_REVIEW_QUOTE_CHARS), text, changes.status?.base ?: changes.base))
        if (saved) back()
        return saved
    }
    fun appendDraft(text: String) {
        val state = repository.state.value
        val draft = listOf(state.draft, text).filter(String::isNotBlank).joinToString("\n\n")
        if (draft.toByteArray(Charsets.UTF_8).size > 128 * 1024) {
            repository.reportError(R.string.remote_prompt_too_long)
            return
        }
        repository.draft(draft)
        open(WatchScreen.COMPOSE)
    }
    companion object {
        fun canonicalScreen(selection: RemoteSelection) = when {
            selection.routeId == null -> WatchScreen.HOSTS
            selection.projectId == null -> WatchScreen.PROJECTS
            selection.sessionId == null -> WatchScreen.SESSIONS
            else -> WatchScreen.CHAT
        }
    }
}
