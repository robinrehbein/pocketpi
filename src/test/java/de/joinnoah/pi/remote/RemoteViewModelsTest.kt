package de.joinnoah.pi.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
    fun batchWaitsForItsChatAndIsDoneOnceWhenRefusedReplacedOrClosed() = runTest {
        val repository = NavigationFakeRepository().apply { holdBatches = true }
        val selection = RemoteSelection("host", "project", "session")
        val store = ViewModelStore()
        val model =
            ViewModelProvider(
                store,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        ChatViewModel(repository, RemoteNavKey.Chat("host", "project", "session"), FakeSettingsRepository()) as T
                },
            )[ChatViewModel::class.java]
        val done = mutableListOf<String>()
        val shot = Picked("content://fixture/shot", photo = true)
        val pdf = Picked("content://fixture/spec.pdf", photo = false)
        repository.state.value = RemoteState(selection = selection, loading = true)

        // Another chat's batch never waits.
        model.acceptAttachments(selection.copy(sessionId = "other"), listOf(shot)) { done += "other" }
        model.acceptAttachments(selection, emptyList()) { done += "empty" }
        assertEquals(listOf("other", "empty"), done)

        // A newer batch replaces one that has not started; the older one is given back.
        model.acceptAttachments(selection, listOf(shot)) { done += "first" }
        model.acceptAttachments(selection, listOf(shot, pdf)) { done += "second" }
        runCurrent()
        assertEquals(listOf("other", "empty", "first"), done)
        assertTrue(repository.batches.isEmpty())

        repository.state.value = RemoteState(selection = selection)
        runCurrent()
        val (target, items, onDone) = repository.batches.single()
        assertEquals(selection, target)
        assertEquals(listOf(shot, pdf), items)
        assertEquals(listOf("other", "empty", "first"), done)
        onDone()
        assertEquals(listOf("other", "empty", "first", "second"), done)

        // A batch still waiting when the chat closes is given back too.
        repository.state.value = RemoteState(selection = selection, loading = true)
        model.acceptAttachments(selection, listOf(pdf)) { done += "closed" }
        runCurrent()
        store.clear()
        assertEquals("closed", done.last())
        assertEquals(1, repository.batches.size)
        model.acceptAttachments(selection, listOf(pdf)) { done += "after" }
        assertEquals("after", done.last())
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
    fun `a late composer write lands only in the chat it was typed in`() = runTest {
        val repository = NavigationFakeRepository()
        val first = RemoteSelection("host", "project", "first")
        val second = first.copy(sessionId = "second")
        repository.state.value = RemoteState(selection = first)
        val firstModel = ChatViewModel(repository, RemoteNavKey.Chat("host", "project", "first"), FakeSettingsRepository())
        val secondModel =
            ChatViewModel(repository, RemoteNavKey.Chat("host", "project", "second"), FakeSettingsRepository())
        firstModel.draft(first, "typed")
        assertEquals("typed", repository.state.value.draft)
        repository.state.value = RemoteState(selection = second, draft = "second draft")
        // The flush on the way out runs after the selection moved on: it is filed under its chat.
        firstModel.draft(first, "late")
        assertEquals("late", repository.filedDrafts[first])
        // The write names its chat, so another destination does not take it.
        secondModel.draft(first, "other")
        assertEquals("late", repository.filedDrafts[first])
        assertEquals("second draft", repository.state.value.draft)
        // Nor is it held back while the chat loads.
        repository.state.value = repository.state.value.copy(selection = first, loading = true)
        firstModel.draft(first, "while loading")
        assertEquals("while loading", repository.state.value.draft)
        repository.state.value = RemoteState(selection = second, draft = "second draft")
        secondModel.draft(second, "new")
        assertEquals("new", repository.state.value.draft)
    }

    @Test
    fun `typing and quoting reach the repository while the chat loads`() = runTest {
        val repository = NavigationFakeRepository()
        val selection = RemoteSelection("host", "project", "first")
        val model = ChatViewModel(repository, RemoteNavKey.Chat("host", "project", "first"), FakeSettingsRepository())
        repository.state.value = RemoteState(selection = selection, loading = true)
        model.draft("typed while loading")
        model.quote("message")
        assertEquals("typed while loading", repository.state.value.draft)
        assertEquals(listOf("message"), repository.quotes)
        // A host action still waits for the load.
        model.prompt()
        assertEquals(0, repository.prompts)

        // Another chat loading is not this one: nothing reaches its draft.
        repository.state.value = RemoteState(selection = selection.copy(sessionId = "second"), draft = "second", loading = true)
        model.draft("stale")
        model.quote("other")
        assertEquals("second", repository.state.value.draft)
        assertEquals(listOf("message"), repository.quotes)
    }

    @Test
    fun `an opening chat previews its cached content read-only until it is selected`() = runTest {
        val repository = NavigationFakeRepository()
        val chat = RemoteSelection("host", "project", "session")
        val row = buildJsonObject {
            put("id", "session")
            put("origin", "rpc")
            put("status", "idle")
        }
        val cached = listOf(buildJsonObject { put("id", "cached-message") })
        repository.cachedChats[chat] = ChatPreview(row, cached, "stored draft")
        // The open still runs: the session list is selected, connected.
        repository.state.value =
            RemoteState(
                selection = chat.copy(sessionId = null),
                connected = true,
                capabilities = setOf(SESSION_FORK_CAPABILITY),
                draft = "list draft",
            )
        val model = ChatViewModel(repository, RemoteNavKey.Chat("host", "project", "session"), FakeSettingsRepository())
        // The first value already carries the cache, before anything is collected.
        val first = model.state.value
        assertTrue(first.preview)
        assertTrue(first.loading)
        assertEquals(listOf("cached-message"), first.messages.map { it.text("id") })
        assertEquals("stored draft", first.draft)
        assertEquals("session", first.session?.text("id"))
        // Never the selection: the composer takes no edits, and nothing can fork or send.
        assertEquals(RemoteSelection(), first.selection)
        assertFalse(first.connected)
        assertFalse(canFork(first))

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent()
        assertTrue(model.state.value.preview)
        assertEquals(cached, model.state.value.messages)
        model.prompt()
        model.answer("question", buildJsonObject {})
        model.draft("typed into the preview")
        model.quote("cached-message")
        assertEquals(0, repository.prompts)
        assertEquals(0, repository.answers)
        assertTrue(repository.quotes.isEmpty())
        assertEquals("list draft", repository.state.value.draft)

        // Selected: the repository's state replaces the preview.
        val fresh = listOf(buildJsonObject { put("id", "fresh-message") })
        repository.state.value =
            RemoteState(selection = chat, connected = true, messages = fresh, draft = "stored draft", session = row)
        runCurrent()
        assertFalse(model.state.value.preview)
        assertEquals(fresh, model.state.value.messages)
        model.prompt()
        assertEquals(1, repository.prompts)
    }

    @Test
    fun `a chat without a cache opens without a preview`() = runTest {
        val repository = NavigationFakeRepository()
        repository.state.value = RemoteState(selection = RemoteSelection("host", "project"))
        val model = ChatViewModel(repository, RemoteNavKey.Chat("host", "project", "session"), FakeSettingsRepository())
        assertFalse(model.state.value.preview)
        assertTrue(model.state.value.loading)
        assertTrue(model.state.value.messages.isEmpty())
        // A non-chat destination never previews a chat.
        repository.cachedChats[RemoteSelection("host", "project", "session")] =
            ChatPreview(null, listOf(buildJsonObject { put("id", "m") }), "draft")
        val sessions = SessionsViewModel(repository, RemoteNavKey.Sessions("host", "other"), FakeSettingsRepository())
        assertFalse(sessions.state.value.preview)
        assertTrue(sessions.state.value.messages.isEmpty())
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

    @Test
    fun `child controls reach only the chat they belong to`() = runTest {
        val repository = NavigationFakeRepository()
        repository.state.value =
            RemoteState(selection = RemoteSelection("host", "project", "child"), draft = "Go on")
        val chat = ChatViewModel(repository, RemoteNavKey.Chat("host", "project", "child"), FakeSettingsRepository())
        val other = ChatViewModel(repository, RemoteNavKey.Chat("host", "project", "other"), FakeSettingsRepository())
        other.stopChild()
        other.resumeChild()
        assertEquals(0, repository.childStops)
        assertTrue(repository.childResumes.isEmpty())
        chat.stopChild()
        chat.resumeChild()
        assertEquals(1, repository.childStops)
        assertEquals(listOf("Go on"), repository.childResumes)
    }
    // ---- Asking about lines at once ---------------------------------------------------------

    private val askSelection = RemoteSelection("host", "project", "session")

    /** A chat that takes a prompt now: connected, idle, nothing typed, quoted or attached. */
    private fun askChat(state: RemoteState.() -> RemoteState = { this }): Pair<NavigationFakeRepository, ChatViewModel> {
        val repository = NavigationFakeRepository().apply { sendsPrompts = true }
        repository.state.value =
            RemoteState(selection = askSelection, connected = true, status = "idle", files = FilesState("session", loading = false)).state()
        return repository to ChatViewModel(repository, RemoteNavKey.Chat("host", "project", "session"), FakeSettingsRepository())
    }

    @Test
    fun askNowSendsThePromptAsItIsWhenTheComposerIsEmpty() {
        val (repository, model) = askChat()
        assertEquals(PromptHandoff.SENT, model.askNow("Lines\n\nWhy?"))
        assertEquals(listOf("Lines\n\nWhy?"), repository.sentPrompts)
    }

    @Test
    fun askNowPrefillsWhereSendingWouldTakeSomethingAlongOrCannotBeDoneNow() {
        val quote = MessageQuote("m", "assistant", "earlier")
        val attachment = LocalAttachment("a".repeat(22), "x.png", "image", "image/png", 1, "0".repeat(64))
        val question = buildJsonObject { put("id", "q") }
        val cases: List<Pair<String, RemoteState.() -> RemoteState>> =
            listOf(
                "typed" to { copy(draft = "my own words") },
                "quote" to { copy(quote = quote) },
                "attachment" to { copy(attachments = listOf(attachment)) },
                "running" to { copy(status = "running") },
                "questions" to { copy(questions = listOf(question)) },
                "offline" to { copy(connected = false) },
                "sending" to { copy(sending = true) },
            )
        for ((name, state) in cases) {
            val (repository, model) = askChat(state)
            val draft = repository.state.value.draft
            assertEquals(name, PromptHandoff.PREFILLED, model.askNow("Why?"))
            assertEquals(name, emptyList<String>(), repository.sentPrompts)
            assertEquals(name, 0, repository.prompts)
            assertEquals(name, prefilledDraft(draft, "Why?"), repository.state.value.draft)
        }
    }

    @Test
    fun aPromptTheRepositoryWouldNotSendStaysInTheComposer() {
        val (repository, model) = askChat()
        repository.sendsPrompts = false
        assertEquals(PromptHandoff.PREFILLED, model.askNow("Why?"))
        assertEquals(1, repository.prompts)
        assertEquals("Why?", repository.state.value.draft)
    }

    @Test
    fun aPromptTooLargeForTheComposerGoesNowhere() {
        val (repository, model) = askChat()
        assertEquals(PromptHandoff.FAILED, model.askNow("x".repeat(128 * 1024 + 1)))
        assertEquals(0, repository.prompts)
        assertEquals("", repository.state.value.draft)
    }

    @Test
    fun aQuestionSentAtOnceKeepsTheFileBesideTheChatAndClosesItOverTheChat() {
        var focused = 0
        val (beside, besideModel) = askChat()
        assertTrue(filesActions(besideModel, besideChat = { true }) { focused++ }.onAsk("Why?"))
        assertNotNull(beside.state.value.files)
        val (over, overModel) = askChat()
        assertTrue(filesActions(overModel, besideChat = { false }) { focused++ }.onAsk("Why?"))
        assertNull(over.state.value.files)
        assertEquals(0, focused)
        // A prefilled question closes Files and moves to the composer, wherever Files shows.
        val (typed, typedModel) = askChat { copy(draft = "mine") }
        assertTrue(filesActions(typedModel, besideChat = { true }) { focused++ }.onAsk("Why?"))
        assertNull(typed.state.value.files)
        assertEquals(1, focused)
        // Nothing fits: Files stays for the user to shorten the range.
        val (full, fullModel) = askChat()
        assertFalse(filesActions(fullModel, besideChat = { false }) { focused++ }.onAsk("x".repeat(128 * 1024 + 1)))
        assertNotNull(full.state.value.files)
    }
}
