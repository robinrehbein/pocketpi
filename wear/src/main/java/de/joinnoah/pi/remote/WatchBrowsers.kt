package de.joinnoah.pi.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.*

internal fun WatchListScope.watchFiles(state: RemoteState, model: WatchViewModel, page: Int, changePage: (Int) -> Unit) {
    val files = state.files ?: return
    val repository = model.repository
    val ready = state.connected && !state.loading
    if (files.loading || files.moreLoading || files.file?.loading == true) loading()
    if (files.failure != null || files.moreFailure != null || files.file?.failure != null || files.file?.moreFailure != null) notice(R.string.wear_load_failed)
    val file = files.file
    if (file == null) {
        item { Text(files.path.ifEmpty { "/" }) }
        when (files.listing?.unavailable) {
            FilesUnavailable.GIT_UNAVAILABLE -> notice(R.string.remote_files_git_unavailable)
            FilesUnavailable.NOT_A_REPOSITORY -> notice(R.string.remote_files_not_a_repository)
            else -> Unit
        }
        if (files.listing?.entries?.isEmpty() == true) notice(R.string.remote_files_empty)
        items(files.listing?.entries.orEmpty()) { entry ->
            WatchAction(entry.name, {
                val path = childPath(files.path, entry.name)
                changePage(0)
                if (entry.type == FileEntryType.DIR) repository.openFilesDir(path) else repository.openFilesFile(path)
            }, ready && entry.type in setOf(FileEntryType.DIR, FileEntryType.FILE),
                stringResource(if (entry.type == FileEntryType.DIR) R.string.remote_files_kind_dir else R.string.remote_files_kind_file))
        }
        if (files.listing?.nextAfter != null) action(R.string.remote_files_load_more, ready && !files.moreLoading, repository::loadMoreFiles)
        else if (files.listing?.truncated == true) notice(R.string.remote_files_truncated)
    } else {
        if (file.binary) notice(R.string.remote_files_binary)
        if (file.tooLarge) notice(R.string.remote_files_too_large)
        if (file.reopened) notice(R.string.remote_files_reopened)
        val blocks = watchFileBlocks(file.content)
        val safePage = page.coerceIn(0, watchPageCount(blocks.size) - 1)
        items(watchPage(blocks, safePage)) { block ->
            val selected = file.selection?.let { block.line in it.first..it.last } == true
            WatchCode(block, selected, { repository.selectFileLines(file.selection.tap(block.line)) })
        }
        pager(safePage, blocks.size, changePage)
        if (!file.loading && file.content.isEmpty() && !file.binary && !file.tooLarge && file.failure == null) notice(R.string.remote_files_file_empty)
        if (file.nextOffset != null) action(R.string.remote_files_load_more, ready && !file.moreLoading, repository::loadMoreFiles)
        file.selection?.let { selection ->
            item { Text(stringResource(R.string.remote_files_selection_lines, selection.first, selection.last)) }
            action(R.string.remote_files_clear_selection) { repository.selectFileLines(null) }
            item {
                val context = LocalContext.current
                WatchAction(stringResource(R.string.remote_files_send_lines), {
                    model.appendDraft(filePrompt(context, file, fileLines(file.content), selection, languageFor(file.path)))
                }, ready)
            }
        }
        item {
            val context = LocalContext.current
            WatchAction(stringResource(R.string.remote_files_send_file), {
                model.appendDraft(filePrompt(context, file, fileLines(file.content), null, languageFor(file.path)))
            }, ready)
        }
    }
    action(R.string.remote_files_reload, ready, repository::reloadFiles)
}

internal fun WatchListScope.watchChanges(state: RemoteState, model: WatchViewModel, page: Int, changePage: (Int) -> Unit) {
    val changes = state.changes ?: return
    val repository = model.repository
    val ready = state.connected && !state.loading
    if (changes.loading || changes.diffLoading || changes.logLoading) loading()
    if (changes.failure != null || changes.diffFailure != null || changes.logFailure != null) notice(R.string.wear_load_failed)
    if (changes.sessionUnavailable != null) notice(R.string.remote_changes_session_fallback)
    if (changes.file == null) {
        GitBase.entries.forEach { base -> item {
            WatchAction(stringResource(when (base) {
                GitBase.SESSION -> R.string.remote_changes_base_session
                GitBase.HEAD -> R.string.remote_changes_base_head
                GitBase.DEV -> R.string.remote_changes_base_dev
            }), { changePage(0); repository.selectChangesBase(base) }, ready && !changes.loading, selected = changes.base == base)
        } }
        changes.status?.unavailable?.let { reason -> notice(when (reason) {
            GitUnavailable.GIT_UNAVAILABLE -> R.string.remote_changes_unavailable_git
            GitUnavailable.NOT_A_REPOSITORY -> R.string.remote_changes_unavailable_repository
            GitUnavailable.BASE_UNAVAILABLE -> R.string.remote_changes_unavailable_base
            GitUnavailable.SESSION_UNSUPPORTED -> R.string.remote_changes_unavailable_session
        }) }
        if (changes.status?.files?.isEmpty() == true && changes.status.available) notice(R.string.remote_changes_empty)
        items(changes.status?.files.orEmpty()) { file ->
            WatchAction(file.path, { changePage(0); repository.openChangesFile(file.path) }, ready && !changes.loading,
                if (file.binary) stringResource(R.string.remote_changes_binary) else "+${file.additions ?: 0} / −${file.deletions ?: 0}")
        }
        if (changes.status?.truncated == true) notice(R.string.remote_changes_truncated)
        changes.log?.let { log ->
            notice(R.string.remote_changes_commits)
            items(log.commits) { commit -> Text(commit.subject) }
            if (log.truncated) notice(R.string.remote_changes_commits_truncated)
        }
    } else {
        changes.diff?.let { diff ->
            if (diff.binary) notice(R.string.remote_changes_diff_binary)
            if (diff.omitted != null) notice(R.string.remote_changes_diff_too_large)
            if (diff.truncated) notice(R.string.remote_changes_diff_truncated)
            val blocks = watchDiffBlocks(diff.patch)
            val safePage = page.coerceIn(0, watchPageCount(blocks.size) - 1)
            if (blocks.isEmpty() && !diff.binary && diff.omitted == null) notice(R.string.remote_changes_diff_empty)
            items(watchPage(blocks, safePage)) { block ->
                WatchCode(block, false, if (block.line > 0 && ready) ({ block.diff?.let(model::beginReview) }) else null)
            }
            pager(safePage, blocks.size, changePage)
            if (blocks.isNotEmpty()) notice(R.string.wear_tap_comment)
        }
    }
    if (changes.comments.isNotEmpty()) {
        items(changes.comments) { comment ->
            WatchAction(comment.path + ":" + (comment.newLine ?: comment.oldLine), { repository.removeReviewComment(comment) }, secondary = comment.text)
        }
        item {
            val context = LocalContext.current
            WatchAction(stringResource(R.string.wear_review_to_draft), { model.appendDraft(reviewPrompt(context, changes.comments)) }, ready)
        }
        notice(R.string.wear_comment_remove_hint)
    }
    action(R.string.remote_changes_reload, ready, repository::reloadChanges)
}

@Composable
private fun WatchCode(block: WatchCodeBlock, selected: Boolean, click: (() -> Unit)?) {
    val color = when (block.diff?.kind) {
        DiffKind.ADDED -> Color(0xFF9DE1AD)
        DiffKind.REMOVED -> Color(0xFFFFB4AB)
        else -> MaterialTheme.colors.onSurface
    }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (selected) MaterialTheme.colors.primary.copy(alpha = .2f) else MaterialTheme.colors.surface, RoundedCornerShape(12.dp))
            .then(if (click != null) Modifier.clickable(role = Role.Button, onClick = click) else Modifier)
            .padding(8.dp),
    ) {
        if (block.line > 0) Text((when (block.diff?.kind) { DiffKind.ADDED -> "+"; DiffKind.REMOVED -> "−"; else -> "" }) + block.line, color = color, style = MaterialTheme.typography.caption2)
        Text(block.text, style = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace), color = color)
    }
}

internal fun WatchListScope.watchJobs(state: RemoteState, model: WatchViewModel, page: Int, changePage: (Int) -> Unit) {
    val jobs = state.jobs ?: return
    val repository = model.repository
    val ready = state.connected && !state.loading
    if (jobs.loading) loading()
    if (jobs.failure != null || jobs.view?.failure != null) notice(R.string.wear_load_failed)
    if (jobs.unsupported) notice(R.string.wear_unsupported)
    val view = jobs.view
    if (view == null) {
        items(jobs.jobs, key = { it.id }) { job ->
            WatchAction(job.command, { changePage(0); repository.openJob(job.id) }, ready,
                stringResource(if (job.status == JobStatus.RUNNING) R.string.wear_running else R.string.wear_job_finished))
        }
    } else {
        if (view.vanished) notice(R.string.wear_no_longer_available)
        val text = view.lines.joinToString("\n") { line -> when (line) {
            is JobOutputLine.Text -> line.text
            is JobOutputLine.Skipped -> "…"
        } }
        reader(text, page, changePage, true)
        if (view.job?.status == JobStatus.RUNNING) action(R.string.wear_stop_job, ready && !view.stopping) { model.ask(WatchConfirmationKind.KILL_JOB, view.jobId) }
    }
    action(R.string.wear_refresh, ready, repository::refreshJobs)
}

internal fun WatchListScope.watchFolders(state: RemoteState, model: WatchViewModel, screen: WatchScreen, text: String, changed: (String) -> Unit) {
    val folders = state.folders
    val repository = model.repository
    val routeId = state.selection.routeId ?: return
    val ready = state.connected && !state.loading && !folders.working
    if (folders.loading || folders.working) loading()
    folders.error?.let(::notice)
    folders.notice?.let { resource -> notice(resource); action(R.string.wear_dismiss, action = repository::dismissFolderNotice) }
    item { Text(listOfNotNull(folders.root, folders.path).filter(String::isNotEmpty).joinToString("/")) }
    folders.trust?.let { trust ->
        action(R.string.remote_folders_trust_title, ready) { model.ask(WatchConfirmationKind.TRUST, trust.path, trust) }
        action(R.string.remote_cancel, action = repository::cancelFolderTrust)
    }
    folders.clone?.let { clone ->
        item { Text(clone.path) }
        item { Text(clone.percent?.let { "$it%" } ?: stringResource(R.string.wear_clone_progress)) }
        if (clone.stage != CloneStage.RUNNING) action(R.string.wear_dismiss, action = repository::dismissClone)
        else action(R.string.wear_refresh, ready, repository::refreshClone)
        if (clone.stage == CloneStage.FAILED) notice(R.string.wear_clone_failed)
    }
    when (screen) {
        WatchScreen.FOLDERS -> {
            items(folders.entries) { entry -> WatchAction(entry.name, { repository.browseFolder(routeId, joinFolderPath(folders.path, entry.name)) }, ready) }
            if (folders.truncated) notice(R.string.wear_truncated)
            action(R.string.wear_folder_actions, ready) { model.open(WatchScreen.FOLDER_ACTIONS) }
            action(R.string.wear_refresh, ready) { repository.browseFolder(routeId, folders.path) }
        }
        WatchScreen.FOLDER_ACTIONS -> {
            if (folders.path.isNotEmpty()) action(R.string.wear_open_folder, ready) { model.openFolder(folders.path) }
            else notice(R.string.remote_folders_root_hint)
            action(R.string.wear_new_folder, ready) { model.open(WatchScreen.NEW_FOLDER) }
            action(R.string.wear_clone, ready && folders.clone?.stage != CloneStage.RUNNING) { model.open(WatchScreen.CLONE) }
        }
        WatchScreen.NEW_FOLDER, WatchScreen.CLONE -> {
            item { WatchEditor(text, changed, stringResource(if (screen == WatchScreen.NEW_FOLDER) R.string.wear_folder_name else R.string.wear_clone_url), ready, 2048,
                dictationError = { repository.reportError(R.string.wear_dictation_unavailable) }) }
            action(R.string.wear_save, ready && if (screen == WatchScreen.NEW_FOLDER) validFolderName(text) else validCloneUrl(text)) {
                if (screen == WatchScreen.NEW_FOLDER) repository.createFolder(text) else repository.cloneRepository(text, null)
                model.open(WatchScreen.FOLDERS)
            }
        }
        else -> Unit
    }
}

internal fun WatchListScope.watchConfirmation(model: WatchViewModel, ready: Boolean) {
    val confirmation = model.confirmation ?: return
    val kind = confirmation.kind
    val body = when (kind) {
        WatchConfirmationKind.STOP -> R.string.wear_confirm_stop
        WatchConfirmationKind.UNPAIR -> R.string.wear_confirm_unpair
        WatchConfirmationKind.UNSHARE -> R.string.wear_confirm_unshare
        WatchConfirmationKind.CLOSE -> R.string.wear_confirm_close
        WatchConfirmationKind.KILL_JOB -> R.string.wear_confirm_kill
        WatchConfirmationKind.FORK_RETRY -> R.string.wear_confirm_fork_retry
        WatchConfirmationKind.FORK_EDIT -> R.string.wear_confirm_fork_edit
        WatchConfirmationKind.DISMISS_SEND -> R.string.wear_confirm_dismiss_send
        WatchConfirmationKind.TRUST -> R.string.wear_confirm_trust
    }
    notice(body)
    val state = model.repository.state.value
    val targetLabel = when (kind) {
        WatchConfirmationKind.UNPAIR -> state.host?.name
        WatchConfirmationKind.UNSHARE -> state.projects.find { it.text("id") == confirmation.target }?.optionalText("name")
        WatchConfirmationKind.KILL_JOB -> state.jobs?.jobs?.find { it.id == confirmation.target }?.command
        WatchConfirmationKind.TRUST -> confirmation.target
        else -> state.session?.optionalText("title")
    }
    targetLabel?.let { paragraphs(it) }
    if (kind == WatchConfirmationKind.TRUST && confirmation.trust?.piConfig == true) notice(R.string.remote_folders_trust_pi_config)
    action(R.string.wear_confirm, ready || kind in setOf(WatchConfirmationKind.UNPAIR, WatchConfirmationKind.DISMISS_SEND), model::confirm)
    action(R.string.remote_cancel, action = model::back)
}
