package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The command palette's prefix modes: the query's first character limits the list to one of them. */
internal enum class PaletteMode(val prefix: Char?) {
    /** Everything but files, with no prefix. */
    ALL(null),

    /** `>` the chat's actions and session controls. */
    ACTIONS('>'),

    /** `/` slash commands. */
    COMMANDS('/'),

    /** `#` sessions across projects and hosts. */
    SESSIONS('#'),

    /** `@` files of the folder Files shows. */
    FILES('@'),
}

/** The mode a query names and the text it matches; a prefix counts only as the first character. */
internal data class PaletteQuery(val mode: PaletteMode, val text: String)

internal fun paletteQuery(query: String): PaletteQuery {
    val mode = PaletteMode.entries.firstOrNull { it.prefix != null && query.startsWith(it.prefix) }
    return if (mode == null) PaletteQuery(PaletteMode.ALL, query.trim())
    else PaletteQuery(mode, query.drop(1).trim())
}

/** What a palette row is, which decides the modes that list it. */
internal enum class PaletteCategory(val modes: Set<PaletteMode>) {
    SESSION(setOf(PaletteMode.ALL, PaletteMode.SESSIONS)),
    PROJECT(setOf(PaletteMode.ALL)),
    HOST(setOf(PaletteMode.ALL)),
    ACTION(setOf(PaletteMode.ALL, PaletteMode.ACTIONS)),
    CONTROL(setOf(PaletteMode.ALL, PaletteMode.ACTIONS)),
    COMMAND(setOf(PaletteMode.ALL, PaletteMode.COMMANDS)),
    FILE(setOf(PaletteMode.FILES)),
}

/** A session control of the chat that has no pill button of its own. */
internal enum class SessionControl {
    MODEL,
    THINKING,
    ADVISOR,
    COMPACT,
}

/** What running a row does inside the open chat; the chat screen carries it out. */
internal sealed interface ChatCommand {
    data class Action(val action: ChatAction) : ChatCommand

    data class Control(val control: SessionControl) : ChatCommand

    data class Local(val command: LocalCommand) : ChatCommand

    data class Host(val command: RemoteCommand) : ChatCommand

    data class File(val path: String) : ChatCommand
}

/** One row of the palette. [live] and [recency] order the rows once the query matches them. */
internal sealed interface PaletteItem {
    val key: String
    val category: PaletteCategory
    val title: String
    val detail: String?

    /** Waiting for an answer or running: such sessions list first in the session mode. */
    val live: Boolean get() = false

    /** How recently the row was opened or used, decayed to now; higher ranks first. */
    val recency: Double get() = 0.0

    /** In the open chat's project: the session mode ranks such rows right after the live ones. */
    val preferred: Boolean get() = false
}

internal data class SessionItem(
    val routeId: String,
    val projectId: String,
    val sessionId: String,
    override val title: String,
    override val detail: String?,
    val status: String,
    override val recency: Double,
    /** The session the chat on screen shows; its row says so rather than leaving it out. */
    val current: Boolean,
    /** The session belongs to the project of the selection. */
    val sameProject: Boolean,
) : PaletteItem {
    override val key get() = "session:$routeId:$sessionId"
    override val category get() = PaletteCategory.SESSION
    override val live get() = status == "waiting" || status == "running"
    override val preferred get() = sameProject
}

internal data class ProjectItem(
    val routeId: String,
    val projectId: String,
    override val title: String,
    override val detail: String?,
) : PaletteItem {
    override val key get() = "project:$routeId:$projectId"
    override val category get() = PaletteCategory.PROJECT
}

internal data class HostItem(
    val routeId: String,
    override val title: String,
    override val detail: String?,
    val current: Boolean,
) : PaletteItem {
    override val key get() = "host:$routeId"
    override val category get() = PaletteCategory.HOST
}

internal data class CommandItem(
    val command: ChatCommand,
    override val title: String,
    override val detail: String?,
    override val category: PaletteCategory,
    override val recency: Double = 0.0,
) : PaletteItem {
    override val key get() = "command:$command"
}

/** The words the rows show that come from resources; the screen passes the localized ones. */
internal class PaletteLabels(
    val command: (ChatCommand) -> String,
    val status: (String) -> String,
    val project: String,
    /** Marks the session or host the app shows now. */
    val current: String,
)

/** Everything the palette lists, as the app knows it when the palette opens. */
internal class PaletteInput(
    val state: RemoteState,
    val home: HomeSnapshot,
    /** A chat is on top, so its actions, controls, commands and files are offered. */
    val chat: Boolean,
    val usage: Map<ChatAction, ActionUsage>,
    val now: Long,
    /** A create or other change is in flight, so the header's New session waits too. */
    val mutating: Boolean = false,
)

/**
 * The rows [query] matches, best first. Without text they list the live sessions, then the rest by
 * recency; with text the match score leads, then live rows, then recency. In the session mode the
 * open chat's project ranks right after the live rows, so the switcher offers its siblings first.
 */
internal fun paletteItems(input: PaletteInput, labels: PaletteLabels, query: String): List<PaletteItem> {
    val parsed = paletteQuery(query)
    // A key listed twice would crash the list, so a repeated row (say, a duplicated project) keeps its first.
    val candidates =
        (sessionItems(input, labels) + chatItems(input, labels) + projectItems(input, labels) + hostItems(input, labels))
            .distinctBy { it.key }
            .filter { parsed.mode in it.category.modes }
    fun PaletteItem.preferredNow() = parsed.mode == PaletteMode.SESSIONS && preferred
    if (parsed.text.isEmpty())
        return candidates.sortedWith(
            compareByDescending<PaletteItem> { it.live }
                .thenByDescending { it.preferredNow() }
                .thenByDescending { it.recency },
        )
    return candidates
        .mapNotNull { item -> matchScore(parsed.text, item)?.let { item to it } }
        .sortedWith(
            compareByDescending<Pair<PaletteItem, Int>> { it.second }
                .thenByDescending { it.first.live }
                .thenByDescending { it.first.preferredNow() }
                .thenByDescending { it.first.recency },
        )
        .map { it.first }
}

/** A row matches on its title, or on its detail, which ranks lower. */
private fun matchScore(text: String, item: PaletteItem): Int? {
    val title = fuzzyScore(text, item.title)
    val detail = item.detail?.let { fuzzyScore(text, it) }?.minus(DETAIL_MATCH_PENALTY)
    return listOfNotNull(title, detail).maxOrNull()
}

private const val DETAIL_MATCH_PENALTY = 1500

/**
 * How well [query] matches [text], or null when it does not. Every space-separated word must match
 * on its own; a prefix ranks above a word inside the text, which ranks above letters scattered
 * through it. Case does not count.
 */
internal fun fuzzyScore(query: String, text: String): Int? {
    val words = query.lowercase().split(' ').filter(String::isNotEmpty)
    if (words.isEmpty()) return 0
    val target = text.lowercase()
    var total = 0
    for (word in words) total += wordScore(word, target) ?: return null
    return total
}

private const val WORD_SEPARATORS = " /-_.:·"

private fun wordScore(word: String, target: String): Int? {
    if (target.startsWith(word)) return 4000 - (target.length - word.length).coerceAtMost(900)
    val index = target.indexOf(word)
    if (index > 0) return 2000 - index.coerceAtMost(900) + if (target[index - 1] in WORD_SEPARATORS) 500 else 0
    var position = 0
    var previous = -2
    var score = 1000
    for (char in word) {
        val found = target.indexOf(char, position)
        if (found < 0) return null
        score -= (found - position).coerceAtMost(50)
        if (found == previous + 1) score += 30
        if (found == 0 || target[found - 1] in WORD_SEPARATORS) score += 50
        previous = found
        position = found + 1
    }
    return score.coerceAtLeast(1)
}

private fun recencyOf(openedAt: Long?, updatedAt: Long?, now: Long): Double =
    when {
        openedAt != null -> ActionUsage(1.0, openedAt).decayed(now)
        updatedAt != null -> ActionUsage(0.5, updatedAt).decayed(now)
        else -> 0.0
    }

private class SessionRow(
    val routeId: String,
    val projectId: String,
    val projectName: String,
    val sessionId: String,
    val title: String?,
    val status: String,
    val updatedAt: Long?,
)

/**
 * Sessions of every paired host: the current host's whole lists first, then the sessions of the
 * home snapshot the lists do not show. A session counts once, by its host and ID.
 */
private fun sessionItems(input: PaletteInput, labels: PaletteLabels): List<SessionItem> {
    val state = input.state
    val routes = buildSet {
        state.hosts.forEach { add(it.routeId) }
        state.host?.let { add(it.routeId) }
        state.selection.routeId?.let { add(it) }
        if (!state.hostsLoaded) input.home.sessions.forEach { add(it.routeId) }
    }
    val hostNames = if (state.hosts.size > 1) state.hosts.associate { it.routeId to it.name } else emptyMap()
    val openedAt = input.home.sessions.associate { (it.routeId to it.sessionId) to it.openedAt }
    val rows = LinkedHashMap<Pair<String, String>, SessionItem>()
    fun add(row: SessionRow?) {
        if (row == null || row.routeId !in routes) return
        val key = row.routeId to row.sessionId
        if (key in rows) return
        val opened = openedAt[key]
        val current = row.routeId == state.selection.routeId && row.sessionId == state.selection.sessionId
        rows[key] =
            SessionItem(
                routeId = row.routeId,
                projectId = row.projectId,
                sessionId = row.sessionId,
                title = row.title ?: row.sessionId,
                detail =
                    listOfNotNull(
                        labels.current.takeIf { current },
                        labels.status(row.status),
                        row.projectName.takeIf(String::isNotBlank),
                        hostNames[row.routeId],
                    ).joinToString(" · "),
                status = row.status,
                recency = recencyOf(opened, row.updatedAt, input.now),
                current = current,
                sameProject =
                    state.selection.projectId != null && row.projectId == state.selection.projectId &&
                        row.routeId == (state.selection.routeId ?: state.host?.routeId),
            )
    }
    val currentRoute = state.host?.routeId ?: state.selection.routeId
    if (currentRoute != null) {
        state.projectSessions.values.forEach { project ->
            project.sessions.forEach { add(sessionRow(it, currentRoute, project.projectId, project.projectName)) }
        }
        val projectId = state.selection.projectId
        if (projectId != null)
            state.sessions.forEach { add(sessionRow(it, currentRoute, projectId, state.project?.optionalText("name").orEmpty())) }
    }
    input.home.sessions.forEach { home ->
        // A list the Mac answered in full is the truth for its project: a session it lacks is gone.
        val verified = if (home.routeId == currentRoute) state.projectSessions[home.projectId] else null
        if (verified != null && verified.verified && !verified.truncated && verified.sessions.none { it.optionalText("id") == home.sessionId })
            return@forEach
        add(
            SessionRow(
                home.routeId,
                home.projectId,
                home.projectName,
                home.sessionId,
                home.title.takeIf(String::isNotBlank),
                home.status,
                home.updatedAt,
            )
        )
    }
    return rows.values.toList()
}

private fun sessionRow(row: JsonObject, routeId: String, projectId: String, projectName: String): SessionRow? {
    val id = row.optionalText("id") ?: return null
    return SessionRow(
        routeId = routeId,
        projectId = projectId,
        projectName = projectName,
        sessionId = id,
        title = row.optionalText("title")?.takeIf(String::isNotBlank),
        status = row.optionalText("status") ?: "idle",
        updatedAt = row["updatedAt"]?.jsonPrimitive?.longOrNull,
    )
}

private fun projectItems(input: PaletteInput, labels: PaletteLabels): List<ProjectItem> {
    val state = input.state
    val routeId = state.host?.routeId ?: return emptyList()
    val hostName = if (state.hosts.size > 1) state.host?.name else null
    return state.projects.mapNotNull { project ->
        val id = project.optionalText("id") ?: return@mapNotNull null
        ProjectItem(
            routeId = routeId,
            projectId = id,
            title = project.optionalText("name")?.takeIf(String::isNotBlank) ?: id,
            detail = listOfNotNull(labels.project, hostName).joinToString(" · "),
        )
    }
}

private fun hostItems(input: PaletteInput, labels: PaletteLabels): List<HostItem> {
    val state = input.state
    if (!state.hostsLoaded) return emptyList()
    return state.hosts.map { host ->
        val current = host.routeId == state.host?.routeId
        HostItem(host.routeId, host.name, detail = labels.current.takeIf { current }, current = current)
    }
}

/**
 * Whether the chat's header action [action] is offered now, as the chat header decides it. Close is
 * never offered: the header confirms it in a dialog of its own, which a palette row would skip.
 */
internal fun chatActionAvailable(action: ChatAction, state: RemoteState, mutating: Boolean = false): Boolean =
    when (action) {
        ChatAction.CHANGES -> canViewChanges(state) && state.connected
        ChatAction.FILES -> canBrowseFiles(state) && state.connected
        ChatAction.JOBS -> visibleJobs(state) != null
        ChatAction.RENAME -> canRenameSession(state)
        ChatAction.NEW_SESSION -> state.connected && !state.loading && !mutating
        ChatAction.SESSION_SETTINGS -> state.connected && !state.loading && sessionSettingsAvailable(state)
        ChatAction.REFRESH, ChatAction.SETTINGS, ChatAction.HISTORY -> true
        ChatAction.CLOSE -> false
    }

private fun chatItems(input: PaletteInput, labels: PaletteLabels): List<PaletteItem> {
    if (!input.chat) return emptyList()
    val state = input.state
    val actions =
        DEFAULT_CHAT_ACTIONS.filter { chatActionAvailable(it, state, input.mutating) }.map { action ->
            val command = ChatCommand.Action(action)
            CommandItem(command, labels.command(command), null, PaletteCategory.ACTION, input.usage[action]?.decayed(input.now) ?: 0.0)
        }
    val controls =
        buildList {
            if (
                state.connected && !state.loading && !state.configurationChanging &&
                configurationControlsAvailable(state.capabilities, state.unavailableCapabilities)
            ) add(SessionControl.MODEL)
            if (state.connected && !state.configurationChanging && thinkingControlAvailable(state.configuration))
                add(SessionControl.THINKING)
            if (state.connected && advisorControlAvailable(state)) add(SessionControl.ADVISOR)
            if (compactAvailable(state) && canCompact(state, input.now)) add(SessionControl.COMPACT)
        }.map { control ->
            val command = ChatCommand.Control(control)
            CommandItem(command, labels.command(command), null, PaletteCategory.CONTROL)
        }
    val hostCommands =
        if (COMMANDS_CAPABILITY in state.capabilities && COMMANDS_CAPABILITY !in state.unavailableCapabilities) state.commands
        else emptyList()
    val (local, host) = mergeCommandSuggestions(availableLocalCommands(state, inChat = true, nowMillis = input.now), hostCommands, "")
    val commands =
        local.map { command ->
            val chat = ChatCommand.Local(command)
            CommandItem(chat, "/${command.commandName}", labels.command(chat), PaletteCategory.COMMAND)
        } + host.map { command ->
            val chat = ChatCommand.Host(command)
            CommandItem(chat, "/${command.name}", command.description?.takeIf(String::isNotBlank), PaletteCategory.COMMAND)
        }
    // An open editor keeps the pane, and opening a file would drop its text; its own prompt guards that.
    val shown = state.files?.takeIf { it.sessionId == state.selection.sessionId && it.editor == null }
    val files =
        shown?.listing?.entries.orEmpty()
            .filter { it.type == FileEntryType.FILE }
            .map { entry ->
                val folder = shown?.listing?.path.orEmpty()
                val path = if (folder.isEmpty()) entry.name else "$folder/${entry.name}"
                val command = ChatCommand.File(path)
                CommandItem(command, path, null, PaletteCategory.FILE)
            }
    return actions + controls + commands + files
}
