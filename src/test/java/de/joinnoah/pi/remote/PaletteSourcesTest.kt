package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteSourcesTest {
    private val labels =
        PaletteLabels(
            command = { command -> command.toString() },
            status = { status -> "status:$status" },
            project = "Project",
            current = "Current",
        )

    private fun host(id: String, name: String) = PairedHost(id, "relay", "device", "secret", name)

    private fun row(id: String, title: String, status: String = "idle", updatedAt: Long? = null) =
        Wire.objectOf("id" to id, "title" to title, "status" to status, "updatedAt" to updatedAt)

    private fun input(
        state: RemoteState,
        home: HomeSnapshot = HomeSnapshot(),
        chat: Boolean = false,
        usage: Map<ChatAction, ActionUsage> = emptyMap(),
        mutating: Boolean = false,
    ) = PaletteInput(state, home, chat, usage, now = NOW, mutating = mutating)

    private fun items(input: PaletteInput, query: String) = paletteItems(input, labels, query)

    private fun sessions(input: PaletteInput, query: String) = items(input, query).filterIsInstance<SessionItem>()

    private val paired =
        RemoteState(
            hosts = listOf(host("mac-a", "Studio"), host("mac-b", "Laptop")),
            hostsLoaded = true,
            host = host("mac-a", "Studio"),
            connected = true,
            selection = RemoteSelection("mac-a", "p1", "s1"),
            projects = listOf(Wire.objectOf("id" to "p1", "name" to "Alpha"), Wire.objectOf("id" to "p2", "name" to "Beta")),
            sessions = listOf(row("s1", "Fix the login", status = "idle")),
            projectSessions =
                mapOf(
                    "p1" to projectSessions("p1", "Alpha", listOf(row("s1", "Fix the login"), row("s2", "Refactor parser", "running"), row("s3", "Waiting one", "waiting")), verified = true),
                    "p2" to projectSessions("p2", "Beta", listOf(row("s4", "Beta notes")), verified = true),
                ),
        )

    @Test
    fun fuzzyMatchesPrefixBeforeWordBeforeScatteredLetters() {
        val prefix = fuzzyScore("log", "Login flow")!!
        val word = fuzzyScore("flow", "Login flow")!!
        val scattered = fuzzyScore("lgf", "Login flow")!!
        assertTrue(prefix > word)
        assertTrue(word > scattered)
        assertNull(fuzzyScore("zzz", "Login flow"))
        assertEquals(fuzzyScore("LOGIN", "login flow"), fuzzyScore("login", "Login flow"))
    }

    @Test
    fun everyWordOfTheQueryMustMatch() {
        assertTrue(fuzzyScore("login fix", "Fix the login") != null)
        assertNull(fuzzyScore("login parser", "Fix the login"))
        assertEquals(0, fuzzyScore("   ", "anything"))
    }

    @Test
    fun aPrefixCountsOnlyAsTheFirstCharacter() {
        assertEquals(PaletteQuery(PaletteMode.SESSIONS, "login"), paletteQuery("# login"))
        assertEquals(PaletteQuery(PaletteMode.ACTIONS, "compact"), paletteQuery(">compact"))
        assertEquals(PaletteQuery(PaletteMode.COMMANDS, ""), paletteQuery("/"))
        assertEquals(PaletteQuery(PaletteMode.FILES, "main"), paletteQuery("@main"))
        assertEquals(PaletteQuery(PaletteMode.ALL, "a#b"), paletteQuery("a#b"))
    }

    @Test
    fun sessionModeListsWaitingAndRunningFirst() {
        val listed = sessions(input(paired, chat = true), "#")
        assertEquals(setOf("s2", "s3"), listed.take(2).map { it.sessionId }.toSet())
        assertTrue(listed.take(2).all { it.live })
        assertFalse(listed.drop(2).any { it.live })
    }

    @Test
    fun sessionsBeyondTwelvePerProjectAreFound() {
        val many = (1..30).map { row("n$it", "Session $it") }
        val state =
            paired.copy(
                sessions = emptyList(),
                projectSessions = mapOf("p1" to projectSessions("p1", "Alpha", many, verified = true)),
            )
        assertEquals(30, sessions(input(state), "#").size)
        assertEquals("n29", sessions(input(state), "# session 29").first().sessionId)
    }

    @Test
    fun sessionsOfOtherHostsComeFromTheHomeSnapshotOnce() {
        val home =
            HomeSnapshot(
                sessions =
                    listOf(
                        HomeSession("mac-b", "q1", "Quiet", "x1", "Remote work", "running", openedAt = 10L),
                        // The same session as the open host lists, so it must not appear twice.
                        HomeSession("mac-a", "p2", "Beta", "s4", "Beta notes", "idle"),
                    ),
            )
        val listed = sessions(input(paired, home), "#")
        assertEquals(1, listed.count { it.routeId == "mac-b" })
        assertEquals(1, listed.count { it.sessionId == "s4" })
        val remote = listed.first { it.sessionId == "x1" }
        assertEquals("Laptop", remote.detail?.split(" · ")?.last())
        assertTrue(remote.live)
    }

    @Test
    fun hostsAndProjectsAreListedInTheAllMode() {
        val all = items(input(paired), "")
        assertEquals(listOf("Studio", "Laptop"), all.filterIsInstance<HostItem>().map { it.title })
        assertEquals(listOf("Alpha", "Beta"), all.filterIsInstance<ProjectItem>().map { it.title })
        assertTrue(all.filterIsInstance<HostItem>().first().current)
        assertTrue(items(input(paired), ">").none { it is HostItem || it is ProjectItem })
    }

    @Test
    fun aQueryFindsAProjectByName() {
        assertEquals(listOf("Beta"), items(input(paired), "bet").filterIsInstance<ProjectItem>().map { it.title })
    }

    @Test
    fun chatActionsAndControlsNeedAChat() {
        val without = items(input(paired, chat = false), ">")
        assertTrue(without.none { it is CommandItem })
        val with = items(input(paired, chat = true), ">")
        val actions = with.filterIsInstance<CommandItem>().map { it.command }
        assertTrue(ChatCommand.Action(ChatAction.HISTORY) in actions)
        assertTrue(ChatCommand.Action(ChatAction.SETTINGS) in actions)
        assertFalse(ChatCommand.Action(ChatAction.CLOSE) in actions)
    }

    @Test
    fun usedActionsRankFirst() {
        val usage = mapOf(ChatAction.HISTORY to ActionUsage(5.0, NOW), ChatAction.SETTINGS to ActionUsage(1.0, NOW - DAY_MILLIS * 60))
        val actions =
            items(input(paired, chat = true, usage = usage), ">")
                .filter { it.category == PaletteCategory.ACTION }
                .map { (it as CommandItem).command }
        assertEquals(ChatCommand.Action(ChatAction.HISTORY), actions.first())
    }

    @Test
    fun sessionControlsFollowTheirCapabilities() {
        val state =
            paired.copy(
                capabilities = setOf(CONFIGURATION_CAPABILITY, ADVISOR_CAPABILITY, COMPACT_CAPABILITY),
                configuration = null,
            )
        val controls = items(input(state, chat = true), ">").filter { it.category == PaletteCategory.CONTROL }
        val commands = controls.map { (it as CommandItem).command }
        assertTrue(ChatCommand.Control(SessionControl.MODEL) in commands)
        assertTrue(ChatCommand.Control(SessionControl.ADVISOR) in commands)
        assertFalse(ChatCommand.Control(SessionControl.THINKING) in commands)
    }

    @Test
    fun hostSlashCommandsAppearOnlyWithTheCommandsCapability() {
        val host = listOf(RemoteCommand("review", "Review the diff", "extension"))
        val without = items(input(paired.copy(commands = host), chat = true), "/")
        assertTrue(without.none { it.title == "/review" })
        val with = items(input(paired.copy(commands = host, capabilities = setOf(COMMANDS_CAPABILITY)), chat = true), "/")
        val review = with.first { it.title == "/review" }
        assertEquals("Review the diff", review.detail)
    }

    @Test
    fun filesAreOnlyListedInTheFileMode() {
        val listing =
            FileListing(
                path = "src",
                entries =
                    listOf(
                        FileEntry("Main.kt", FileEntryType.FILE),
                        FileEntry("lib", FileEntryType.DIR),
                    ),
            )
        val state = paired.copy(files = FilesState("s1", path = "src", loading = false, listing = listing))
        val files = items(input(state, chat = true), "@")
        assertEquals(listOf("src/Main.kt"), files.map { it.title })
        assertTrue(items(input(state, chat = true), "").none { it.category == PaletteCategory.FILE })
    }

    @Test
    fun recentSessionsRankAboveOlderOnesWithoutText() {
        val home =
            HomeSnapshot(
                sessions =
                    listOf(
                        HomeSession("mac-a", "p1", "Alpha", "s2", "Refactor parser", "idle", openedAt = NOW - DAY_MILLIS),
                        HomeSession("mac-a", "p1", "Alpha", "s3", "Waiting one", "idle", openedAt = NOW - DAY_MILLIS * 40),
                    ),
            )
        val plain = paired.copy(projectSessions = emptyMap(), sessions = emptyList())
        val listed = sessions(input(plain, home), "#")
        assertEquals(listOf("s2", "s3"), listed.map { it.sessionId })
    }

    @Test
    fun aMatchOnTheDetailRanksBelowATitleMatch() {
        val state =
            paired.copy(
                projectSessions =
                    mapOf(
                        "p1" to projectSessions("p1", "Alpha", listOf(row("a", "Alpha work"), row("b", "Notes")), verified = true),
                    ),
            )
        val listed = sessions(input(state), "# alpha")
        assertEquals("a", listed.first().sessionId)
    }

    @Test
    fun theSelectedSessionIsMarkedCurrent() {
        val listed = sessions(input(paired), "#")
        assertEquals("s1", listed.single { it.current }.sessionId)
    }

    @Test
    fun sessionModeRanksTheOpenProjectAfterTheLiveRows() {
        // p2's session is the most recently opened, but the chat is in p1.
        val home =
            HomeSnapshot(
                sessions =
                    listOf(
                        HomeSession("mac-a", "p2", "Beta", "s4", "Beta notes", "idle", openedAt = NOW - 1_000L),
                        HomeSession("mac-a", "p1", "Alpha", "s1", "Fix the login", "idle", openedAt = NOW - DAY_MILLIS * 20),
                    ),
            )
        val listed = sessions(input(paired, home, chat = true), "#")
        // Waiting and running first, then the open project's, then the other projects'.
        assertEquals(listOf("s2", "s3"), listed.take(2).map { it.sessionId }.sorted())
        assertEquals(listOf("s1", "s4"), listed.drop(2).map { it.sessionId })
        assertTrue(listed.drop(2).first().sameProject)
        assertFalse(listed.last().sameProject)
    }

    @Test
    fun theOpenProjectDoesNotLeadTheAllMode() {
        val home =
            HomeSnapshot(
                sessions = listOf(HomeSession("mac-a", "p2", "Beta", "s4", "Beta notes", "idle", openedAt = NOW - 1_000L)),
            )
        val first = sessions(input(paired, home), "").filter { !it.live }.first()
        assertEquals("s4", first.sessionId)
    }

    @Test
    fun theCurrentSessionAndHostSaySoInTheirDetail() {
        val all = items(input(paired), "")
        assertTrue(all.filterIsInstance<SessionItem>().single { it.current }.detail!!.startsWith("Current"))
        val hosts = all.filterIsInstance<HostItem>()
        assertEquals("Current", hosts.single { it.current }.detail)
        assertNull(hosts.single { !it.current }.detail)
    }

    @Test
    fun aHomeRowThatTheVerifiedListLacksIsGone() {
        val home =
            HomeSnapshot(
                sessions = listOf(HomeSession("mac-a", "p1", "Alpha", "gone", "Deleted one", "running")),
            )
        assertTrue(sessions(input(paired, home), "#").none { it.sessionId == "gone" })
        val unverified =
            paired.copy(projectSessions = paired.projectSessions + ("p1" to projectSessions("p1", "Alpha", listOf(row("s1", "Fix the login")), verified = false)))
        assertTrue(sessions(input(unverified, home), "#").any { it.sessionId == "gone" })
    }

    @Test
    fun newSessionWaitsWhileAnotherChangeRuns() {
        val idle = items(input(paired, chat = true), ">").map { (it as CommandItem).command }
        assertTrue(ChatCommand.Action(ChatAction.NEW_SESSION) in idle)
        val busy = items(input(paired, chat = true, mutating = true), ">").map { (it as CommandItem).command }
        assertFalse(ChatCommand.Action(ChatAction.NEW_SESSION) in busy)
    }

    @Test
    fun filesOfAnotherSessionOrWithAnOpenEditorAreNotOffered() {
        val listing = FileListing(path = "", entries = listOf(FileEntry("Main.kt", FileEntryType.FILE)))
        val shown = FilesState("s1", loading = false, listing = listing)
        assertEquals(listOf("Main.kt"), items(input(paired.copy(files = shown), chat = true), "@").map { it.title })
        val other = paired.copy(files = shown.copy(sessionId = "s9"))
        assertTrue(items(input(other, chat = true), "@").isEmpty())
        // Opening a file would drop the editor's text, so no file row is offered while it is open.
        val editing = paired.copy(files = shown.copy(editor = FileEditor("Main.kt", "v1", "a", text = "b")))
        assertTrue(items(input(editing, chat = true), "@").isEmpty())
    }

    @Test
    fun aHostCommandKeepsTheDraftBehindIt() {
        assertEquals("/review fix the bug", paletteCommandDraft("fix the bug", "review"))
        assertEquals("/review hello", paletteCommandDraft("hello", "review"))
        assertEquals("/review ", paletteCommandDraft("", "review"))
        assertEquals("/review the diff", paletteCommandDraft("/re the diff", "review"))
        assertEquals("/review", paletteCommandDraft("/re", "review"))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }

    @Test
    fun pinnedSessionsRankFirstInTheSessionMode() {
        val host = PairedHost("host", "wss://relay.test", "device", "secret", "Studio Mac")
        val rows =
            listOf(
                Wire.objectOf("id" to "fresh", "title" to "Fresh", "status" to "idle", "updatedAt" to 900L),
                Wire.objectOf("id" to "pinned", "title" to "Pinned", "status" to "idle", "updatedAt" to 100L),
            )
        val state =
            RemoteState(
                hosts = listOf(host),
                hostsLoaded = true,
                host = host,
                selection = RemoteSelection("host", "project"),
                connected = true,
                projectSessions = mapOf("project" to projectSessions("project", "Project", rows, verified = true)),
            )
        val input =
            PaletteInput(
                state = state,
                home = HomeSnapshot(),
                chat = false,
                usage = emptyMap(),
                now = 1_000L,
                marks = mapOf(("host" to "pinned") to SessionMark(pinned = true)),
            )

        val keys = paletteItems(input, labels, "#").map { it.key }

        assertEquals(listOf("session:host:pinned", "session:host:fresh"), keys)
    }
}
