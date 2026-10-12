package de.joinnoah.pi.remote

import android.os.Bundle
import android.content.Intent
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

class WearActivity : ComponentActivity() {
    private val app get() = application as WearApplication
    private val model: WatchViewModel by viewModels {
        viewModelFactory { initializer { WatchViewModel(app.repository, createSavedStateHandle()) } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { MaterialTheme { WearApp(model, app.settings) { selection -> openOnPhone(this, selection, app.repository::reportError) } } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
    private fun handleIntent(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        WearNotifications.target(intent)?.let(model::openNotification)
    }
    override fun onStop() {
        lifecycleScope.launch {
            try { app.repository.flush() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { app.repository.reportError(R.string.remote_storage_error) }
        }
        super.onStop()
    }
}

@Composable
internal fun WearApp(model: WatchViewModel, settings: SettingsRepository, phone: (RemoteSelection) -> Unit = {}) {
    val raw by model.repository.state.collectAsStateWithLifecycle()
    val route by model.route.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val preferences by settings.state.collectAsStateWithLifecycle()
    val repository = model.repository
    val context = LocalContext.current
    val app = context.applicationContext as? WearApplication
    val pairCode by (app?.pairCode ?: remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }).collectAsStateWithLifecycle()
    val pairWaiting by (app?.pairWaiting ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) app?.setPushEnabled(true)
    }
    fun togglePush() {
        if (preferences.pushEnabled) app?.setPushEnabled(false)
        else if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            app?.setPushEnabled(true)
        else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    DisposableEffect(route.screen) { onDispose { if (route.screen == WatchScreen.PAIR) app?.cancelPairRequest() } }
    LaunchedEffect(pairCode, route.screen, busy) {
        if (route.screen == WatchScreen.PAIR && pairCode != null && !busy) {
            val code = pairCode ?: return@LaunchedEffect
            app?.consumePairCode()
            model.pair(code)
        }
    }
    val state = if (route.selection == raw.selection || route.screen in setOf(WatchScreen.HOSTS, WatchScreen.PAIR, WatchScreen.SETTINGS)) raw
        else RemoteState(hosts = raw.hosts, loading = true)
    fun active(action: () -> Unit) = model.execute(route) { action() }
    val pairInstructions = stringResource(R.string.wear_pair_instructions)
    val ready = state.connected && !state.loading && !busy
    val messages = remember(state.messages, state.status, preferences.thinkingDisplay) {
        conversationItems(state.messages, state.status == "running")
            .filter { conversationItemVisible(it, preferences.thinkingDisplay, state.status == "running") }
    }
    var page by remember(route, state.files?.file?.path, state.files?.file?.version, state.changes?.file, state.changes?.diff?.snapshotId, state.jobs?.view?.jobId) { mutableIntStateOf(0) }
    var chatLimit by remember(route.selection.sessionId) { mutableIntStateOf(20) }
    var editText by remember(route) { mutableStateOf(if (route.screen == WatchScreen.RENAME) state.session?.optionalText("title").orEmpty() else "") }
    BackHandler(route.screen != WatchScreen.HOSTS) { model.back() }
    val question = state.questions.find { it.text("id") == route.detailId }
    LaunchedEffect(route, state.questions, state.loading) {
        if (route.screen == WatchScreen.QUESTION && question == null && !state.loading && !busy) model.open(WatchScreen.QUESTIONS)
    }
    if (route.screen == WatchScreen.QUESTION && question != null) {
        WatchQuestionScreen(model, state, question)
        return
    }
    val title = when (route.screen) {
        WatchScreen.HOSTS -> stringResource(R.string.remote_app_name)
        WatchScreen.PROJECTS -> state.host?.name ?: stringResource(R.string.wear_projects)
        WatchScreen.SESSIONS -> state.project?.optionalText("name") ?: stringResource(R.string.wear_sessions)
        WatchScreen.CHAT -> state.session?.optionalText("title") ?: stringResource(R.string.wear_chat)
        WatchScreen.FILES -> state.files?.file?.path?.substringAfterLast('/') ?: stringResource(R.string.remote_files_title)
        WatchScreen.CHANGES -> state.changes?.file?.substringAfterLast('/') ?: stringResource(R.string.remote_changes_title)
        else -> stringResource(watchTitle(route.screen))
    }
    WatchList(title, listOf(route, page), route.screen != WatchScreen.HOSTS, model::back) {
        if (busy || state.loading || (route.screen == WatchScreen.MODELS && state.configurationLoading) ||
            (route.screen == WatchScreen.ADVISOR && state.advisorLoading) ||
            (route.screen == WatchScreen.COMMANDS && state.commandsLoading)) loading()
        state.error?.let { error ->
            notice(error)
            action(R.string.wear_dismiss, action = repository::dismissError)
        }
        if (route.selection.routeId != null) item { Text(stringResource(state.connection)) }
        when (route.screen) {
            WatchScreen.HOSTS -> {
                items(state.hosts, key = { it.routeId }) { host ->
                    WatchAction(host.name, { model.navigate(RemoteSelection(host.routeId)) }, !busy)
                }
                action(R.string.wear_pair, !busy) { model.open(WatchScreen.PAIR) }
                action(R.string.wear_settings, !busy) { model.open(WatchScreen.SETTINGS) }
            }
            WatchScreen.PAIR -> {
                paragraphs(pairInstructions)
                action(R.string.wear_pair_phone, !busy && !pairWaiting) {
                    requestPhonePairing(context, { model.route.value.screen == WatchScreen.PAIR }, repository::reportError)
                }
                if (pairWaiting) {
                    notice(R.string.wear_pair_phone_wait)
                    action(R.string.remote_cancel) { app?.cancelPairRequest() }
                }
                item { WatchEditor(editText, { editText = it }, stringResource(R.string.wear_pair_code), !busy, 8192,
                    dictationError = { repository.reportError(R.string.wear_dictation_unavailable) }) }
                action(R.string.wear_pair, !busy && editText.isNotBlank()) { model.pair(editText) }
            }
            WatchScreen.PROJECTS -> {
                items(state.projects, key = { it.text("id") }) { project ->
                    WatchAction(project.text("name"), { model.open(WatchScreen.PROJECT_ACTIONS, project.text("id")) }, ready,
                        project.optionalText("path"))
                }
                if (canOpenFolders(state)) action(R.string.wear_folders, ready) { model.open(WatchScreen.FOLDERS) }
                action(R.string.wear_refresh, !busy, repository::refresh)
                action(R.string.wear_remove_host, !busy) { model.ask(WatchConfirmationKind.UNPAIR) }
            }
            WatchScreen.PROJECT_ACTIONS -> {
                val project = state.projects.find { it.text("id") == route.detailId }
                if (project != null) {
                    paragraphs(project.text("name"))
                    project.optionalText("path")?.let { paragraphs(it) }
                    action(R.string.wear_sessions, ready) { model.navigate(route.selection.copy(projectId = project.text("id"))) }
                    if (PROJECT_UNSHARE_CAPABILITY in state.capabilities) action(R.string.wear_unshare, ready) { model.ask(WatchConfirmationKind.UNSHARE, project.text("id")) }
                } else notice(R.string.wear_no_longer_available)
            }
            WatchScreen.SESSIONS -> {
                action(R.string.wear_new_session, ready, model::createSession)
                items(visibleSessionListItems(state.sessions, state.connected, state.loading, preferences.hideOfflineSessions), key = { it.id }) { session ->
                    WatchAction(session.title, { model.navigate(route.selection.copy(sessionId = session.id)) }, ready,
                        stringResource(watchStatus(session.availability.name.lowercase())) + if (session.parentSessionId != null) " · " + stringResource(R.string.wear_subagent) else "")
                }
                action(R.string.wear_refresh, !busy, repository::refresh)
            }
            WatchScreen.CHAT -> {
                item { Text(stringResource(watchStatus(state.status))) }
                if (state.questions.isNotEmpty()) action(R.string.wear_questions, ready) { model.open(WatchScreen.QUESTIONS) }
                action(R.string.wear_write, !busy) { model.open(WatchScreen.COMPOSE) }
                action(R.string.wear_controls, !busy) { model.open(WatchScreen.CONTROLS) }
                items(messages.takeLast(chatLimit).asReversed(), key = { it.id }) { message ->
                    WatchAction(watchMessageLabel(message), { model.open(WatchScreen.MESSAGE, message.id) }, !busy,
                        if (message is ConversationItem.Thinking && preferences.thinkingDisplay != "text") stringResource(R.string.wear_thinking_status) else watchMessageText(message).take(160))
                }
                if (messages.size > chatLimit) action(R.string.wear_older) { chatLimit += 20 }
                else if (state.nextCursor != null) action(R.string.wear_older, ready) { repository.older(); chatLimit += 20 }
                action(R.string.wear_refresh, !busy, repository::refresh)
            }
            WatchScreen.MESSAGE -> {
                val message = messages.find { it.id == route.detailId }
                if (message != null) {
                    val content = if (message is ConversationItem.Thinking && preferences.thinkingDisplay != "text") ""
                        else if (message is ConversationItem.Activity) state.toolOutput?.takeIf { it.toolCallId == message.toolCallId }?.text ?: watchMessageText(message)
                        else watchMessageText(message)
                    val truncated = when (message) {
                        is ConversationItem.Bubble -> message.truncated
                        is ConversationItem.Activity -> message.truncated || message.argumentsTruncated
                        is ConversationItem.Subagent -> message.truncated
                        else -> false
                    }
                    if (truncated) notice(R.string.wear_truncated)
                    reader(content, page, { page = it }, message is ConversationItem.Activity)
                    if (message is ConversationItem.Bubble) {
                        message.timestamp?.let { timestamp -> item { Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(timestamp))) } }
                        message.attachments.forEach { attachment -> item { Text(attachment.name) } }
                        action(R.string.wear_quote, !busy) { repository.quote(message.sourceId); model.open(WatchScreen.COMPOSE) }
                        if (message.id in forkableBubbleIds(messages) && canFork(state)) {
                            action(R.string.wear_fork_edit, ready && forkStopped(state)) { model.ask(WatchConfirmationKind.FORK_EDIT, message.sourceId) }
                            action(R.string.wear_fork_retry, ready && forkStopped(state)) { model.ask(WatchConfirmationKind.FORK_RETRY, message.sourceId) }
                        }
                    }
                    if (message is ConversationItem.Activity && message.toolCallId != null && TOOL_OUTPUT_CAPABILITY in state.capabilities) {
                        action(R.string.wear_full_output, ready) { page = 0; active { repository.loadToolOutput(message.toolCallId) } }
                        state.toolOutput?.takeIf { it.toolCallId == message.toolCallId }?.let { output ->
                            if (output.text == null && output.failure == null) loading()
                            if (output.failure != null) notice(R.string.wear_load_failed)
                            if (output.truncated) notice(R.string.wear_truncated)
                        }
                    }
                    if (message is ConversationItem.Subagent) message.agents.forEach { agent ->
                        val child = agent.sessionId?.takeIf { id -> state.sessions.any { it.text("id") == id } }
                        if (child != null) item { WatchAction(agent.name, { model.navigate(route.selection.copy(sessionId = child)) }, ready, agent.preview) }
                    }
                } else notice(R.string.wear_no_longer_available)
            }
            WatchScreen.COMPOSE -> {
                state.quote?.let { quote ->
                    paragraphs(quote.excerpt)
                    action(R.string.wear_remove_quote) { repository.quote(null) }
                }
                item { WatchEditor(state.draft, { text -> active { repository.draft(text) } }, stringResource(R.string.wear_input), !busy && !state.sending, 128 * 1024,
                    dictationError = { repository.reportError(R.string.wear_dictation_unavailable) },
                    tooLong = { repository.reportError(R.string.remote_prompt_too_long) }) }
                if (state.uncertain) notice(R.string.wear_uncertain)
                val send = watchSendActions(state)
                action(R.string.wear_send, send.send && !busy) { active(repository::prompt) }
                if (canFollowUp(state)) action(R.string.wear_follow_up, send.followUp && !busy) { active(repository::followUp) }
                if (canSteer(state)) action(R.string.wear_steer, send.steer && !busy) { active(repository::steer) }
                if (offersChildResume(state)) action(R.string.wear_resume_child, send.resume && !busy) { active { repository.resumeChild(state.draft) } }
                if (state.followUps.isNotEmpty()) action(R.string.wear_journal) { model.open(WatchScreen.JOURNAL) }
                if (state.questions.isNotEmpty()) action(R.string.wear_questions) { model.open(WatchScreen.QUESTIONS) }
                action(R.string.wear_phone_attachments, ready) { phone(route.selection) }
                notice(R.string.wear_draft_local)
            }
            WatchScreen.CONTROLS -> {
                if (state.status in setOf("running", "waiting") || childControlsAvailable(state)) action(R.string.wear_stop, ready) { model.ask(WatchConfirmationKind.STOP) }
                childControl(state)?.phase?.let { notice(R.string.wear_child_control_notice) }
                if (configurationControlsAvailable(state.capabilities, state.unavailableCapabilities)) {
                    item { Text(state.configuration?.model?.name.orEmpty()) }
                    action(R.string.wear_model, ready) { model.open(WatchScreen.MODELS) }
                    action(R.string.wear_thinking, ready) { model.open(WatchScreen.THINKING) }
                }
                state.contextUsage?.let { context ->
                    item { Text(stringResource(R.string.remote_context_window, context.contextWindow)) }
                    context.usedTokens?.let { used -> item { Text(stringResource(R.string.wear_tokens_used, used)) } }
                }
                if (ADVISOR_CAPABILITY in state.capabilities) action(R.string.wear_advisor, ready) { model.open(WatchScreen.ADVISOR) }
                if (COMMANDS_CAPABILITY in state.capabilities) action(R.string.wear_commands, ready) { model.open(WatchScreen.COMMANDS) }
                if (canBrowseFiles(state)) action(R.string.remote_files_open, ready) { model.open(WatchScreen.FILES) }
                if (canViewChanges(state)) action(R.string.remote_changes_open, ready) { model.open(WatchScreen.CHANGES) }
                if (canUseBackgroundJobs(state)) action(R.string.wear_jobs, ready) { model.open(WatchScreen.JOBS) }
                if (RENAME_CAPABILITY in state.capabilities) action(R.string.wear_rename, ready) { model.open(WatchScreen.RENAME) }
                state.session?.let { session ->
                    if (canCloseSession(sessionListItem(session, state.connected, state.loading), state.connected, state.loading, state.capabilities))
                        action(R.string.wear_close_session, ready) { model.ask(WatchConfirmationKind.CLOSE) }
                }
                if (state.followUps.isNotEmpty()) action(R.string.wear_journal) { model.open(WatchScreen.JOURNAL) }
                action(R.string.wear_open_phone, ready) { phone(route.selection) }
                action(R.string.wear_refresh, !busy, repository::refresh)
            }
            WatchScreen.MODELS -> {
                items(state.configuration?.models.orEmpty(), key = { it.provider + "/" + it.id }) { modelChoice ->
                    WatchAction(modelChoice.name, { active { repository.setModel(modelChoice.provider, modelChoice.id) } }, ready && state.status == "idle" && !state.configurationChanging,
                        modelChoice.provider, state.configuration?.model?.let { it.provider == modelChoice.provider && it.id == modelChoice.id } == true)
                }
                if (state.configuration?.modelsTruncated == true) notice(R.string.wear_truncated)
            }
            WatchScreen.THINKING -> items(state.configuration?.thinkingLevels.orEmpty()) { level ->
                WatchAction(level, { active { repository.setThinkingLevel(level) } }, ready && state.status == "idle" && !state.configurationChanging, selected = state.configuration?.thinkingLevel == level)
            }
            WatchScreen.ADVISOR -> {
                state.advisor?.let { advisor ->
                    item { Text(if (advisor.enabled) advisor.model.orEmpty() else stringResource(R.string.remote_advisor_off)) }
                    item { Text(stringResource(R.string.remote_advisor_remaining, advisor.remainingAttempts, advisor.maxAttempts)) }
                    items(advisor.choices) { choice -> WatchAction(choice.name, { model.chooseAdvisor(choice) }, ready && state.status == "idle" && !state.advisorChanging) }
                    action(R.string.remote_advisor_off, ready && state.status == "idle" && !state.advisorChanging) { active { repository.setAdvisor(null) } }
                    if (advisor.enabled && state.commands.any { it.name == "advisor" }) action(R.string.wear_advisor_share, ready && state.status == "idle" && state.draft.isBlank() && state.quote == null) {
                        active { repository.draft("/advisor share"); model.open(WatchScreen.COMPOSE) }
                    }
                    notice(R.string.remote_advisor_context_notice)
                    notice(R.string.wear_advisor_context_command)
                } ?: notice(R.string.remote_advisor_unavailable)
            }
            WatchScreen.ADVISOR_LEVELS -> model.advisorChoice?.let { choice ->
                items(choice.levels) { level -> WatchAction(level, { active { repository.setAdvisor(choice.provider, choice.id, level); model.open(WatchScreen.ADVISOR) } }, ready && state.status == "idle" && !state.advisorChanging) }
            }
            WatchScreen.COMMANDS -> {
                items(state.commands, key = { it.name }) { command -> WatchAction("/${command.name}", { active { repository.selectCommand(command); model.open(WatchScreen.COMPOSE) } }, ready, command.description) }
                if (state.commandsTruncated) notice(R.string.wear_truncated)
            }
            WatchScreen.QUESTIONS -> items(state.questions, key = { it.text("id") }) { q ->
                WatchAction(q.optionalText("title") ?: stringResource(R.string.remote_questions), { model.open(WatchScreen.QUESTION, q.text("id")) }, !busy)
            }
            WatchScreen.JOURNAL -> {
                notice(R.string.wear_journal_notice)
                items(state.followUps, key = { it.requestId }) { entry ->
                    WatchAction(entry.text, { model.ask(WatchConfirmationKind.DISMISS_SEND, entry.requestId) }, !busy, stringResource(watchReceipt(entry.status)))
                }
            }
            WatchScreen.FILES -> watchFiles(state, model, page, { page = it })
            WatchScreen.CHANGES -> watchChanges(state, model, page, { page = it })
            WatchScreen.REVIEW -> {
                model.reviewLine?.let { paragraphs(it.text) }
                item { WatchEditor(editText, { editText = it }, stringResource(R.string.wear_review_comment), !busy, 1000,
                    dictationError = { repository.reportError(R.string.wear_dictation_unavailable) }) }
                action(R.string.wear_save, editText.isNotBlank() && !busy) { if (!model.saveReview(editText)) repository.reportError(R.string.remote_storage_error) }
            }
            WatchScreen.JOBS -> watchJobs(state, model, page, { page = it })
            WatchScreen.FOLDERS, WatchScreen.FOLDER_ACTIONS, WatchScreen.NEW_FOLDER, WatchScreen.CLONE -> watchFolders(state, model, route.screen, editText, { editText = it })
            WatchScreen.RENAME -> {
                item { WatchEditor(editText, { editText = it }, stringResource(R.string.wear_rename), ready, 200,
                    dictationError = { repository.reportError(R.string.wear_dictation_unavailable) }) }
                action(R.string.wear_save, ready && editText.isNotBlank()) { route.selection.sessionId?.let { repository.renameSession(it, editText.trim()); model.back() } }
            }
            WatchScreen.SETTINGS -> {
                item { WatchAction(stringResource(R.string.wear_notifications), ::togglePush, app?.pushConfigured == true, selected = preferences.pushEnabled) }
                if (app?.pushConfigured != true) notice(R.string.wear_push_unconfigured)
                item { WatchAction(stringResource(R.string.wear_show_thinking), { settings.setThinkingDisplay(if (preferences.thinkingDisplay == "text") "status" else "text") }, selected = preferences.thinkingDisplay == "text") }
                item { WatchAction(stringResource(R.string.wear_hide_offline), { settings.setHideOfflineSessions(!preferences.hideOfflineSessions) }, selected = preferences.hideOfflineSessions) }
                notice(R.string.wear_background_notice)
            }
            WatchScreen.CONFIRM -> watchConfirmation(model, ready)
            WatchScreen.QUESTION -> Unit
        }
    }
}

private fun watchTitle(screen: WatchScreen): Int = when (screen) {
    WatchScreen.PROJECT_ACTIONS -> R.string.wear_projects
    WatchScreen.PAIR -> R.string.wear_pair
    WatchScreen.MESSAGE -> R.string.wear_message
    WatchScreen.COMPOSE -> R.string.wear_write
    WatchScreen.CONTROLS -> R.string.wear_controls
    WatchScreen.MODELS -> R.string.wear_model
    WatchScreen.THINKING -> R.string.wear_thinking
    WatchScreen.ADVISOR, WatchScreen.ADVISOR_LEVELS -> R.string.wear_advisor
    WatchScreen.COMMANDS -> R.string.wear_commands
    WatchScreen.QUESTIONS, WatchScreen.QUESTION -> R.string.wear_questions
    WatchScreen.JOBS -> R.string.wear_jobs
    WatchScreen.JOURNAL -> R.string.wear_journal
    WatchScreen.REVIEW -> R.string.wear_review_comment
    WatchScreen.RENAME -> R.string.wear_rename
    WatchScreen.SETTINGS -> R.string.wear_settings
    WatchScreen.CONFIRM -> R.string.wear_confirm
    else -> R.string.wear_folders
}
internal fun watchStatus(status: String): Int = when (status) {
    "idle" -> R.string.wear_idle
    "running" -> R.string.wear_running
    "waiting" -> R.string.wear_waiting
    else -> R.string.remote_offline
}
private fun watchReceipt(status: String): Int = when (status) {
    "queued" -> R.string.wear_queued
    "applied" -> R.string.wear_applied
    "pending" -> R.string.wear_pending
    else -> R.string.wear_uncertain
}
@Composable
private fun watchMessageLabel(item: ConversationItem): String = when (item) {
    is ConversationItem.Bubble -> item.author ?: stringResource(if (item.role == "user") R.string.wear_you else R.string.wear_assistant)
    is ConversationItem.Activity -> item.name ?: stringResource(R.string.wear_tool)
    is ConversationItem.Subagent -> stringResource(R.string.wear_subagent)
    is ConversationItem.Thinking -> stringResource(R.string.wear_thinking_status)
}
private fun watchMessageText(item: ConversationItem): String = when (item) {
    is ConversationItem.Bubble -> item.text
    is ConversationItem.Activity -> listOfNotNull(item.arguments, item.output).joinToString("\n\n")
    is ConversationItem.Subagent -> item.output ?: item.agents.joinToString("\n") { it.name + ": " + (it.preview ?: it.state) }
    is ConversationItem.Thinking -> item.text
}
