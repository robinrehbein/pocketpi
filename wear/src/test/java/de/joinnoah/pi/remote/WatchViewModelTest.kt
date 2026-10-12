package de.joinnoah.pi.remote

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WatchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private fun model(repository: WatchFakeRepository, saved: SavedStateHandle = SavedStateHandle()) = WatchViewModel(repository, saved).also { store.put("watch", it) }
    @Test fun `restoration uses RESTORE and follows the host canonical fallback without opening history`() = runTest(dispatcher) {
        val repository = WatchFakeRepository().apply { canonical = RemoteSelection("r", "p") }
        val saved = SavedStateHandle(mapOf("screen" to "CHAT", "route" to "r", "project" to "p", "session" to "historical"))
        val model = model(repository, saved)
        runCurrent()
        assertEquals(listOf(ActivationMode.RESTORE), repository.activationModes)
        assertEquals(WatchScreen.SESSIONS, model.route.value.screen)
        assertNull(saved.get<String>("session"))
    }
    @Test fun `confirmation cannot be restored into an executable action`() = runTest(dispatcher) {
        val repository = WatchFakeRepository()
        val model = model(repository, SavedStateHandle(mapOf("screen" to "CONFIRM", "route" to "r", "project" to "p", "session" to "s")))
        runCurrent()
        assertEquals(WatchScreen.CHAT, model.route.value.screen)
        assertNull(model.confirmation)
        model.confirm()
        assertEquals(0, repository.aborts)
    }
    @Test fun `rapid navigation is serialized and saves identifiers only`() = runTest(dispatcher) {
        val repository = WatchFakeRepository()
        val saved = SavedStateHandle()
        val model = model(repository, saved)
        runCurrent()
        repository.activationDelay = 100
        model.navigate(RemoteSelection("r", "p", "s"))
        model.navigate(RemoteSelection("other"))
        advanceUntilIdle()
        assertEquals(RemoteSelection("r", "p", "s"), model.route.value.selection)
        assertEquals(setOf("screen", "route", "project", "session", "detail"), saved.keys())
        assertEquals(2, repository.activationModes.size)
    }
    @Test fun `stale confirmation cannot stop a different selected session`() = runTest(dispatcher) {
        val repository = WatchFakeRepository()
        val model = model(repository)
        runCurrent()
        model.navigate(RemoteSelection("r", "p", "s")); runCurrent()
        model.ask(WatchConfirmationKind.STOP)
        repository.state.value = repository.state.value.copy(selection = RemoteSelection("r", "p", "other"))
        model.confirm()
        assertEquals(0, repository.aborts)
        runCurrent()
        assertNull(model.confirmation)
    }
    @Test fun `fork requires confirmation and is never replayed`() = runTest(dispatcher) {
        val repository = WatchFakeRepository()
        val model = model(repository)
        runCurrent()
        model.navigate(RemoteSelection("r", "p", "s")); runCurrent()
        model.open(WatchScreen.MESSAGE, "m")
        model.ask(WatchConfirmationKind.FORK_EDIT, "m")
        assertEquals(0, repository.forks)
        model.confirm(); runCurrent()
        model.confirm(); runCurrent()
        assertEquals(1, repository.forks)
        assertEquals("fork", model.route.value.selection.sessionId)
    }
    @Test fun `close waits for repository confirmation before leaving the session`() = runTest(dispatcher) {
        val repository = WatchFakeRepository()
        val model = model(repository)
        runCurrent()
        model.navigate(RemoteSelection("r", "p", "s")); runCurrent()
        model.open(WatchScreen.CONTROLS)
        model.ask(WatchConfirmationKind.CLOSE)
        model.confirm(); runCurrent()
        assertEquals(1, repository.closeRequests)
        assertEquals(WatchScreen.CONTROLS, model.route.value.screen)
        assertEquals(2, repository.activationModes.size)
        repository.state.value = repository.state.value.copy(selection = RemoteSelection("r", "p"), session = null)
        runCurrent()
        assertEquals(WatchScreen.SESSIONS, model.route.value.screen)
    }
    @Test fun `notification waits for restoration and routes only after resolving the paired host`() = runTest(dispatcher) {
        val repository = WatchFakeRepository()
        val model = model(repository)
        model.openNotification(NotificationTarget("r", "s"))
        advanceUntilIdle()
        assertEquals(ActivationMode.RESTORE, repository.activationModes.first())
        assertEquals(RemoteSelection("r", "p", "s"), model.route.value.selection)
        assertEquals(WatchScreen.CHAT, model.route.value.screen)
    }
    @Test fun `project unshare runs from the host project list not a selected project`() = runTest(dispatcher) {
        val repository = WatchFakeRepository()
        val model = model(repository)
        runCurrent()
        model.navigate(RemoteSelection("r")); runCurrent()
        model.open(WatchScreen.PROJECT_ACTIONS, "p")
        model.ask(WatchConfirmationKind.UNSHARE, "p")
        model.confirm()
        assertEquals("p", repository.unshared)
        assertNull(repository.state.value.selection.projectId)
        assertEquals(WatchScreen.PROJECTS, model.route.value.screen)
    }
    @Test fun `old screen actions do not execute after navigation`() = runTest(dispatcher) {
        val repository = WatchFakeRepository()
        val model = model(repository)
        runCurrent()
        model.navigate(RemoteSelection("r", "p", "s")); runCurrent()
        model.open(WatchScreen.COMPOSE)
        val old = model.route.value
        model.open(WatchScreen.CONTROLS)
        model.execute(old) { it.prompt() }
        assertEquals(0, repository.prompts)
    }
    @Test fun `oversized file quote preserves the existing draft`() = runTest(dispatcher) {
        val repository = WatchFakeRepository()
        val model = model(repository)
        runCurrent()
        model.navigate(RemoteSelection("r", "p", "s")); runCurrent()
        repository.draft("Keep this")
        model.appendDraft("x".repeat(128 * 1024))
        assertEquals("Keep this", repository.state.value.draft)
        assertEquals(R.string.remote_prompt_too_long, repository.error)
    }
    @Test fun `return from comment editor preserves the changes snapshot`() = runTest(dispatcher) {
        val repository = WatchFakeRepository()
        val model = model(repository)
        runCurrent()
        model.navigate(RemoteSelection("r", "p", "s")); runCurrent()
        model.open(WatchScreen.CHANGES)
        model.beginReview(DiffLine(DiffKind.ADDED, "new line", null, 3))
        model.back()
        assertEquals(WatchScreen.CHANGES, model.route.value.screen)
        assertEquals(0, repository.closedChanges)
    }
}
