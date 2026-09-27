package de.joinnoah.pi.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RemoteViewModelsTest {
    @Before
    fun mainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun resetDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun pickerResultWaitsForMatchingRestorationAndIsConsumedOnce() = runTest {
        val repository = NavigationFakeRepository()
        val selection = RemoteSelection("host", "project", "session")
        val model =
            ChatViewModel(
                repository,
                RemoteNavKey.Chat("host", "project", "session"),
                FakeSettingsRepository(),
            )
        repository.state.value = RemoteState(loading = true)
        model.acceptAttachments(selection, listOf("content://fixture/file"), false)
        runCurrent()
        assertTrue(repository.imports.isEmpty())
        repository.state.value = RemoteState(selection = selection, loading = true)
        runCurrent()
        assertTrue(repository.imports.isEmpty())
        repository.state.value = RemoteState(selection = selection)
        runCurrent()
        assertEquals(1, repository.imports.size)
        repository.state.value = repository.state.value.copy(connected = true)
        runCurrent()
        assertEquals(1, repository.imports.size)
        model.acceptAttachments(
            selection.copy(sessionId = "another"),
            listOf("content://fixture/other"),
            false,
        )
        runCurrent()
        assertEquals(1, repository.imports.size)
    }

    @Test
    fun `old chat entry retains its own content and rejects stale actions`() = runTest {
        val repository = NavigationFakeRepository()
        val selection = RemoteSelection("host", "project", "first")
        repository.state.value =
            RemoteState(
                selection = selection,
                draft = "first draft",
                connected = true,
                messages = listOf(buildJsonObject { put("id", "first-message") }),
            )
        val model =
            ChatViewModel(
                repository,
                RemoteNavKey.Chat("host", "project", "first"),
                FakeSettingsRepository(),
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent()
        assertEquals("first draft", model.state.value.draft)
        repository.state.value =
            RemoteState(
                selection = selection.copy(sessionId = "second"),
                draft = "second draft",
                connected = true,
                messages = listOf(buildJsonObject { put("id", "second-message") }),
            )
        runCurrent()
        assertEquals("first draft", model.state.value.draft)
        assertEquals("first-message", model.state.value.messages.single().text("id"))
        assertFalse(model.state.value.connected)
        model.answer("old-question", buildJsonObject {})
        model.prompt()
        model.draft("stale edit")
        assertEquals(0, repository.answers)
        assertEquals(0, repository.prompts)
        assertEquals("second draft", repository.state.value.draft)
    }

    @Test
    fun `non chat destination never exposes draft or transcript`() = runTest {
        val repository = NavigationFakeRepository()
        repository.state.value =
            RemoteState(
                selection = RemoteSelection("host", "project", "session"),
                draft = "secret draft",
                messages = listOf(buildJsonObject { put("id", "message") }),
            )
        val model = ProjectsViewModel(repository, RemoteNavKey.Projects("host"))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent()
        assertEquals("", model.state.value.draft)
        assertTrue(model.state.value.messages.isEmpty())
        assertEquals(0, repository.prompts)
    }

    @Test
    fun `non chat destination hides tool output and compaction`() = runTest {
        val repository = NavigationFakeRepository()
        repository.state.value =
            RemoteState(
                selection = RemoteSelection("host", "project", "session"),
                compaction = SessionCompaction("manual", 1L),
                toolOutput = ToolOutputDownload("call", 3, 3, "abc", false, false, null),
            )
        val sessions = SessionsViewModel(
            repository,
            RemoteNavKey.Sessions("host", "project"),
            FakeSettingsRepository(),
        )
        val chat = ChatViewModel(
            repository,
            RemoteNavKey.Chat("host", "project", "session"),
            FakeSettingsRepository(),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sessions.state.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { chat.state.collect() }
        runCurrent()
        assertNull(sessions.state.value.compaction)
        assertNull(sessions.state.value.toolOutput)
        assertEquals("manual", chat.state.value.compaction?.reason)
        assertEquals("abc", chat.state.value.toolOutput?.text)
    }

    @Test
    fun `ask to fix quotes the message and fills only a blank draft`() = runTest {
        val repository = NavigationFakeRepository()
        val selection = RemoteSelection("host", "project", "session")
        repository.state.value = RemoteState(selection = selection, connected = true)
        val model = ChatViewModel(
            repository,
            RemoteNavKey.Chat("host", "project", "session"),
            FakeSettingsRepository(),
        )
        model.askToFix("failed-tool", "Please fix this error.")
        assertEquals(listOf("failed-tool"), repository.quotes)
        assertEquals("Please fix this error.", repository.state.value.draft)

        repository.state.value = repository.state.value.copy(draft = "my own words")
        model.askToFix("other-tool", "Please fix this error.")
        assertEquals(listOf("failed-tool", "other-tool"), repository.quotes)
        assertEquals("my own words", repository.state.value.draft)

        repository.state.value = repository.state.value.copy(draft = "   ")
        model.askToFix("third-tool", "Fix it.")
        assertEquals("Fix it.", repository.state.value.draft)

        repository.state.value = repository.state.value.copy(loading = true, draft = "")
        model.askToFix("ignored", "Fix it.")
        assertEquals(3, repository.quotes.size)
        assertEquals("", repository.state.value.draft)
    }

    @Test
    fun `chat wrappers forward insight actions only while active`() = runTest {
        val repository = NavigationFakeRepository()
        repository.state.value = RemoteState(selection = RemoteSelection("host", "project", "session"))
        val model = ChatViewModel(
            repository,
            RemoteNavKey.Chat("host", "project", "session"),
            FakeSettingsRepository(),
        )
        model.abortSession("child")
        model.refreshSessions()
        model.loadToolOutput("call")
        assertEquals(listOf("child"), repository.abortedSessions)
        assertEquals(1, repository.sessionRefreshes)
        assertEquals(listOf("call"), repository.toolOutputRequests)
        repository.state.value = RemoteState(selection = RemoteSelection("host", "project", "other"))
        model.abortSession("child")
        model.loadToolOutput("call")
        assertEquals(1, repository.abortedSessions.size)
        assertEquals(1, repository.toolOutputRequests.size)
    }

    @Test
    fun `settings swipe setters update the stored preferences`() = runTest {
        val settings = FakeSettingsRepository()
        val model = SettingsViewModel(NavigationFakeRepository(), settings)
        assertEquals(SwipeAction.CLOSE, model.preferences.value.swipeEndToStart)
        assertEquals(SwipeAction.RENAME, model.preferences.value.swipeStartToEnd)
        model.setSwipeEndToStart(SwipeAction.NONE)
        model.setSwipeStartToEnd(SwipeAction.CLOSE)
        assertEquals(SwipeAction.NONE, settings.state.value.swipeEndToStart)
        assertEquals(SwipeAction.CLOSE, settings.state.value.swipeStartToEnd)
        assertEquals(SwipeAction.NONE, model.preferences.value.swipeEndToStart)
        assertEquals(SwipeAction.CLOSE, model.preferences.value.swipeStartToEnd)
    }
}
