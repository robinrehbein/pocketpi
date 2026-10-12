package de.joinnoah.pi.remote

import kotlinx.serialization.json.*

internal enum class WatchScreen {
    HOSTS, PAIR, PROJECTS, PROJECT_ACTIONS, SESSIONS, CHAT, MESSAGE, COMPOSE, CONTROLS,
    MODELS, THINKING, ADVISOR, ADVISOR_LEVELS, COMMANDS, QUESTIONS, QUESTION,
    FILES, CHANGES, REVIEW, JOBS, JOURNAL, FOLDERS, FOLDER_ACTIONS, NEW_FOLDER,
    CLONE, RENAME, SETTINGS, CONFIRM,
}

internal data class WatchRoute(
    val screen: WatchScreen = WatchScreen.HOSTS,
    val selection: RemoteSelection = RemoteSelection(),
    val detailId: String? = null,
)

internal enum class WatchConfirmationKind { STOP, UNPAIR, UNSHARE, CLOSE, KILL_JOB, FORK_RETRY, FORK_EDIT, TRUST, DISMISS_SEND }
internal data class WatchConfirmation(
    val kind: WatchConfirmationKind,
    val source: WatchRoute,
    val target: String? = null,
    val trust: FolderTrustPrompt? = null,
)

/** Mirror the phone's idle/running gates instead of exposing actions the host will reject. */
internal data class WatchSendActions(val send: Boolean, val followUp: Boolean, val steer: Boolean, val resume: Boolean)
internal fun watchSendActions(state: RemoteState): WatchSendActions {
    val busyChild = childControl(state)?.phase in setOf(ChildControlPhase.STOPPING, ChildControlPhase.RESUMING)
    val ready = state.connected && state.session != null && !state.loading && !state.sending &&
        !state.configurationChanging && !state.importingAttachments && state.questions.isEmpty() &&
        state.draft.isNotBlank() && !state.uncertain && !busyChild
    val ordinary = !state.draft.trimStart().startsWith("/")
    val queue = ready && ordinary && state.followUps.size < 64 &&
        !(childControlsAvailable(state) && state.attachments.isNotEmpty())
    val resume = ready && offersChildResume(state) && ordinary && state.quote == null && state.attachments.isEmpty()
    return WatchSendActions(
        ready && state.status == "idle" && !offersChildResume(state),
        queue && state.status == "running" && canFollowUp(state),
        queue && state.status == "running" && canSteer(state),
        resume,
    )
}

/** Small, lossless blocks: no scaled list item needs to be as tall as a complete document. */
internal fun watchTextBlocks(text: String, limit: Int = 100): List<String> {
    require(limit >= 2)
    if (text.isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    var offset = 0
    while (offset < text.length) {
        var end = minOf(offset + limit, text.length)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        var lineBreaks = 0
        for (index in offset until end) {
            if (text[index] == '\n' || (text[index] == '\r' && text.getOrNull(index + 1) != '\n')) {
                lineBreaks++
                if (lineBreaks == 3) { end = index + 1; break }
            }
        }
        result += text.substring(offset, end)
        offset = end
    }
    return result
}

internal fun watchInputText(text: String, limit: Int): String {
    require(limit > 0)
    var end = minOf(text.length, limit)
    if (end < text.length && end > 0 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return text.substring(0, end)
}

internal const val WATCH_PAGE_BLOCKS = 10
internal fun watchPageCount(size: Int): Int = maxOf(1, (size + WATCH_PAGE_BLOCKS - 1) / WATCH_PAGE_BLOCKS)
internal fun <T> watchPage(values: List<T>, page: Int): List<T> {
    val safePage = page.coerceIn(0, watchPageCount(values.size) - 1)
    return values.drop(safePage * WATCH_PAGE_BLOCKS).take(WATCH_PAGE_BLOCKS)
}

internal data class WatchCodeBlock(val line: Int, val text: String, val diff: DiffLine? = null)
internal fun watchFileBlocks(content: String): List<WatchCodeBlock> =
    fileLines(content).flatMapIndexed { index, line ->
        watchTextBlocks(line.ifEmpty { " " }).map { WatchCodeBlock(index + 1, it) }
    }
internal fun watchDiffBlocks(patch: String): List<WatchCodeBlock> =
    patchDiff(patch).flatMap { line ->
        watchTextBlocks(line.text.ifEmpty { " " }).map { WatchCodeBlock(line.newLine ?: line.oldLine ?: 0, it, line) }
    }

/** Memory only. Unsubmitted answers never enter Android saved state. */
internal data class WatchQuestionAnswers(
    val selected: Map<String, Set<String>>,
    val custom: Map<String, List<String>>,
) {
    fun choose(question: JsonObject, value: String): WatchQuestionAnswers {
        val id = question.text("id")
        val choices = selected[id].orEmpty()
        return if (question.flag("multiSelect"))
            copy(selected = selected + (id to if (value in choices) choices - value else choices + value))
        else copy(selected = selected + (id to setOf(value)), custom = custom - id)
    }
    fun other(question: JsonObject, text: String): WatchQuestionAnswers {
        val id = question.text("id")
        return copy(
            selected = if (question.flag("multiSelect")) selected else selected + (id to emptySet()),
            custom = custom + (id to listOf(watchInputText(text, 4096)).filter(String::isNotBlank)),
        )
    }
    fun editOther(question: JsonObject, text: String, previous: String): WatchQuestionAnswers {
        if (!question.flag("multiSelect")) return other(question, text)
        val id = question.text("id")
        val values = custom[id].orEmpty().filterNot { previous.isNotBlank() && it == previous } +
            listOf(watchInputText(text, 4096)).filter(String::isNotBlank)
        return copy(custom = custom + (id to values.distinct()))
    }
    fun skip(id: String) = copy(selected = selected - id, custom = custom - id)
    fun valid(questions: List<JsonObject>) = questions.all { q ->
        !q.flag("required") || selected[q.text("id")].orEmpty().isNotEmpty() || custom[q.text("id")].orEmpty().any(String::isNotBlank)
    }
    fun wire(questions: List<JsonObject>): JsonObject {
        require(valid(questions))
        return Wire.objectOf(
            "kind" to "questionnaire", "cancelled" to false,
            "answers" to JsonArray(questions.map { q ->
                val id = q.text("id")
                val selections = q.array("options").mapIndexedNotNull { index, option ->
                    if (option.text("value") in selected[id].orEmpty())
                        Wire.objectOf("value" to option.text("value"), "label" to option.text("label"), "index" to index + 1, "wasCustom" to false)
                    else null
                } + custom[id].orEmpty().filter(String::isNotBlank).map {
                    Wire.objectOf("value" to it, "label" to it, "wasCustom" to true)
                }
                Wire.objectOf("id" to id, "skipped" to selections.isEmpty(), "selections" to JsonArray(selections))
            }),
        )
    }
    companion object {
        fun defaults(questions: List<JsonObject>) = WatchQuestionAnswers(
            questions.associate { it.text("id") to questionDefaults(it).first },
            questions.associate { it.text("id") to questionDefaults(it).second },
        )
    }
}
