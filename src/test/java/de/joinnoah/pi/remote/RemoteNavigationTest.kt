package de.joinnoah.pi.remote

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class RemoteNavigationTest {
    @Test
    fun `explicit open replaces requested history ID with canonical live ID`() = runTest {
        val repository = NavigationFakeRepository()
        repository.activation = { selection, mode ->
            if (mode == ActivationMode.USER_OPEN) selection.copy(sessionId = "fork") else selection
        }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.open(RemoteNavKey.Chat("host", "project", "history"))
        runCurrent()
        assertEquals(RemoteNavKey.Chat("host", "project", "fork"), stack.last())
        assertEquals(4, stack.size)
    }

    @Test
    fun `back invalidates even a non cooperative late open result`() = runTest {
        val reply = CompletableDeferred<RemoteSelection>()
        val repository = NavigationFakeRepository()
        repository.activation = { selection, mode ->
            if (mode == ActivationMode.USER_OPEN) withContext(NonCancellable) { reply.await() }
            else selection
        }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.open(RemoteNavKey.Chat("host", "project", "history"))
        runCurrent()
        navigation.back()
        reply.complete(RemoteSelection("host", "project", "fork"))
        runCurrent()
        assertEquals(RemoteNavKey.Sessions("host", "project"), stack.last())
    }

    @Test
    fun `notification supersedes restore while invalid target leaves stack intact`() = runTest {
        val restore = CompletableDeferred<RemoteSelection>()
        val repository = NavigationFakeRepository()
        repository.activation = { _, _ -> withContext(NonCancellable) { restore.await() } }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("old"))
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.restore()
        runCurrent()
        repository.notification = RemoteSelection("new", "project", "live")
        navigation.notification("new", "live")
        runCurrent()
        restore.complete(RemoteSelection("old"))
        runCurrent()
        assertEquals(RemoteNavKey.Chat("new", "project", "live"), stack.last())
        repository.notification = null
        repository.activation = { selection, _ -> selection }
        navigation.notification("unknown", "missing")
        runCurrent()
        assertEquals(RemoteNavKey.Chat("new", "project", "live"), stack.last())
    }

    @Test
    fun `back consumes pending notification once before late lookup completes`() = runTest {
        val repository = NavigationFakeRepository()
        val lookup = CompletableDeferred<RemoteSelection?>()
        repository.notificationLookup = { withContext(NonCancellable) { lookup.await() } }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"))
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        var consumed = 0
        navigation.notification("host", "live") { consumed++ }
        runCurrent()
        navigation.back()
        assertEquals(1, consumed)
        lookup.complete(RemoteSelection("host", "project", "live"))
        runCurrent()
        assertEquals(1, consumed)
        assertEquals(listOf(RemoteNavKey.Hosts), stack)
    }

    @Test
    fun `a notification whose project is known shows its chat before the repository finishes`() = runTest {
        val repository = NavigationFakeRepository()
        repository.knownProjects["host" to "live"] = "project"
        val lookup = CompletableDeferred<RemoteSelection?>()
        repository.notificationLookup = { lookup.await() }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("other"), RemoteNavKey.Settings)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.notification("host", "live")
        runCurrent()
        assertEquals(RemoteSelection("host", "project", "live").keys(), stack)
        assertNull(navigation.openingNotification)
        // A history session opens as its fork: the canonical chat replaces the pushed one.
        lookup.complete(RemoteSelection("host", "project", "fork"))
        runCurrent()
        assertEquals(RemoteSelection("host", "project", "fork").keys(), stack)
        assertTrue(repository.activations.none { it.second == ActivationMode.RESTORE })
    }

    @Test
    fun `a notification whose project is unknown says it is opening until the Mac answered`() = runTest {
        val repository = NavigationFakeRepository()
        val lookup = CompletableDeferred<RemoteSelection?>()
        repository.notificationLookup = { lookup.await() }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("other"))
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.notification("host", "live")
        runCurrent()
        assertEquals("host" to "live", navigation.openingNotification)
        assertEquals(listOf(RemoteNavKey.Hosts, RemoteNavKey.Projects("other")), stack)
        lookup.complete(RemoteSelection("host", "project", "live"))
        runCurrent()
        assertNull(navigation.openingNotification)
        assertEquals(RemoteSelection("host", "project", "live").keys(), stack)
    }

    @Test
    fun `back while a notification opens ends the opening state`() = runTest {
        val repository = NavigationFakeRepository()
        repository.notificationLookup = { CompletableDeferred<RemoteSelection?>().await() }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"))
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.notification("host", "live")
        runCurrent()
        assertNotNull(navigation.openingNotification)
        navigation.back()
        assertNull(navigation.openingNotification)
    }

    @Test
    fun `a failed open after the early chat returns to the previous screen and restores it`() = runTest {
        val repository = NavigationFakeRepository()
        repository.knownProjects["host" to "gone"] = "project"
        repository.notification = null
        val before = listOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("other"), RemoteNavKey.Sessions("other", "p"))
        val stack = before.toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.notification("host", "gone")
        runCurrent()
        assertEquals(before, stack)
        assertEquals(RemoteSelection("other", "p") to ActivationMode.RESTORE, repository.activations.last())
    }

    @Test
    fun `reconnect fallback trims only to ancestor and preserves settings`() = runTest {
        val repository = NavigationFakeRepository()
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.open(RemoteNavKey.Chat("host", "project", "live"))
        runCurrent()
        navigation.settings()
        navigation.reconcile(RemoteState(selection = RemoteSelection("another-host")))
        assertEquals(5, stack.size)
        navigation.reconcile(
            RemoteState(selection = RemoteSelection("host", "project"), loading = true)
        )
        assertEquals(5, stack.size)
        navigation.reconcile(RemoteState(selection = RemoteSelection("host", "project")))
        assertEquals(
            listOf(
                RemoteNavKey.Hosts,
                RemoteNavKey.Projects("host"),
                RemoteNavKey.Sessions("host", "project"),
                RemoteNavKey.Settings,
            ),
            stack,
        )
    }

    @Test
    fun `repository fallback cannot trim a pending explicit navigation`() = runTest {
        val gate = CompletableDeferred<RemoteSelection>()
        val repository = NavigationFakeRepository()
        repository.activation = { _, _ -> gate.await() }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.open(RemoteNavKey.Chat("host", "project", "live"))
        runCurrent()
        navigation.reconcile(RemoteState())
        assertEquals(RemoteNavKey.Chat("host", "project", "live"), stack.last())
        gate.complete(RemoteSelection("host", "project", "live"))
        runCurrent()
    }

    @Test
    fun `settings preserves selection and does not reactivate on return`() = runTest {
        val repository = NavigationFakeRepository()
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.open(RemoteNavKey.Chat("host", "project", "live"))
        runCurrent()
        val count = repository.activations.size
        navigation.settings()
        navigation.back()
        runCurrent()
        assertEquals(count, repository.activations.size)
        assertEquals(RemoteNavKey.Chat("host", "project", "live"), stack.last())
    }

    private val parent = RemoteNavKey.Chat("host", "project", "parent")
    private val child = RemoteNavKey.Chat("host", "project", "child")
    private val base =
        listOf(
            RemoteNavKey.Hosts,
            RemoteNavKey.Projects("host"),
            RemoteNavKey.Sessions("host", "project"),
        )

    private fun kotlinx.coroutines.test.TestScope.onParent(
        repository: NavigationFakeRepository
    ): Pair<MutableList<NavKey>, RemoteNavigator> {
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.open(parent)
        runCurrent()
        return stack to navigation
    }

    @Test
    fun `openChild stacks the child on top of its parent chat`() = runTest {
        val repository = NavigationFakeRepository()
        val (stack, navigation) = onParent(repository)
        navigation.settings()
        navigation.openChild(parent, child)
        assertEquals(base + parent + child, stack)
        runCurrent()
        assertEquals(base + parent + child, stack)
        assertEquals(child.selection() to ActivationMode.USER_OPEN, repository.activations.last())
    }

    @Test
    fun `openChild replaces a forked child id and keeps the parent`() = runTest {
        val repository = NavigationFakeRepository()
        val (stack, navigation) = onParent(repository)
        repository.activation = { selection, mode ->
            if (mode == ActivationMode.USER_OPEN) selection.copy(sessionId = "fork") else selection
        }
        navigation.openChild(parent, child)
        runCurrent()
        assertEquals(base + parent + RemoteNavKey.Chat("host", "project", "fork"), stack)
    }

    @Test
    fun `openChild collapses onto the parent when the canonical selection is the parent`() =
        runTest {
            val repository = NavigationFakeRepository()
            val (stack, navigation) = onParent(repository)
            repository.activation = { _, _ -> parent.selection() }
            navigation.openChild(parent, child)
            runCurrent()
            assertEquals(base + parent, stack)
        }

    @Test
    fun `openChild stays on the parent and reports when the child is unknown`() = runTest {
        val repository = NavigationFakeRepository()
        val (stack, navigation) = onParent(repository)
        repository.activation = { selection, mode ->
            if (selection == child.selection()) RemoteSelection("host", "project") else selection
        }
        navigation.openChild(parent, child)
        runCurrent()
        assertEquals(base + parent, stack)
        assertEquals(parent.selection() to ActivationMode.RESTORE, repository.activations.last())
        assertEquals(listOf(R.string.remote_insights_child_not_found), repository.errors)
    }

    @Test
    fun `openChild falls back to the canonical selection when it leaves the project`() = runTest {
        val repository = NavigationFakeRepository()
        val (stack, navigation) = onParent(repository)
        repository.activation = { _, _ -> RemoteSelection("host") }
        navigation.openChild(parent, child)
        runCurrent()
        assertEquals(listOf(RemoteNavKey.Hosts, RemoteNavKey.Projects("host")), stack)
    }

    @Test
    fun `back from a child restores the parent chat`() = runTest {
        val repository = NavigationFakeRepository()
        val (stack, navigation) = onParent(repository)
        navigation.openChild(parent, child)
        runCurrent()
        navigation.back()
        runCurrent()
        assertEquals(base + parent, stack)
        assertEquals(parent.selection() to ActivationMode.RESTORE, repository.activations.last())
    }

    @Test
    fun `restore on a child keeps the parent beneath it`() = runTest {
        val repository = NavigationFakeRepository()
        val (stack, navigation) = onParent(repository)
        navigation.openChild(parent, child)
        runCurrent()
        navigation.restore()
        runCurrent()
        assertEquals(child.selection() to ActivationMode.RESTORE, repository.activations.last())
        assertEquals(base + parent + child, stack)
    }

    @Test
    fun `restore on an offline child falls back to its live parent chat`() = runTest {
        val repository = NavigationFakeRepository()
        val (stack, navigation) = onParent(repository)
        navigation.openChild(parent, child)
        runCurrent()
        // The child's session went offline: the host drops it back to the project's session list.
        // The parent is still live and comes back unchanged when restored.
        repository.activation = { selection, mode ->
            if (mode == ActivationMode.RESTORE && selection == child.selection()) RemoteSelection("host", "project")
            else selection
        }
        navigation.restore()
        runCurrent()
        assertEquals(base + parent, stack)
        // The parent was actually re-activated through the repository, not just placed on the nav
        // stack: its own selection is what the repository (and the parent's ChatViewModel) now see.
        assertEquals(parent.selection(), repository.state.value.selection)
        assertEquals(
            listOf(child.selection(), parent.selection()),
            repository.activations.takeLast(2).map { it.first },
        )
        assertEquals(ActivationMode.RESTORE, repository.activations.last().second)
        // reconcile() sees the parent's own (unshortened) selection now, so it does not collapse
        // the stack back to the sessions list on the next state emission.
        navigation.reconcile(repository.state.value)
        assertEquals(base + parent, stack)
    }

    @Test
    fun `back into an offline parent falls back further to its own live parent`() = runTest {
        val repository = NavigationFakeRepository()
        val (stack, navigation) = onParent(repository)
        val grandparent = RemoteNavKey.Chat("host", "project", "grandparent")
        navigation.openChild(grandparent, parent)
        runCurrent()
        navigation.openChild(parent, child)
        runCurrent()
        assertEquals(base + grandparent + parent + child, stack)
        // The parent (what Back lands on) is itself offline; the grandparent is still live.
        repository.activation = { selection, mode ->
            if (mode == ActivationMode.RESTORE && selection == parent.selection()) RemoteSelection("host", "project")
            else selection
        }
        navigation.back()
        runCurrent()
        assertEquals(base + grandparent, stack)
        assertEquals(grandparent.selection(), repository.state.value.selection)
    }

    @Test
    fun `restore on two consecutive offline chats falls back to the nearest live ancestor`() = runTest {
        val repository = NavigationFakeRepository()
        val (stack, navigation) = onParent(repository)
        val grandparent = RemoteNavKey.Chat("host", "project", "grandparent")
        navigation.openChild(grandparent, parent)
        runCurrent()
        navigation.openChild(parent, child)
        runCurrent()
        assertEquals(base + grandparent + parent + child, stack)
        // Both the child and its immediate parent are offline; only the grandparent is live.
        val reduced = RemoteSelection("host", "project")
        repository.activation = { selection, mode ->
            when {
                mode != ActivationMode.RESTORE -> selection
                selection == child.selection() || selection == parent.selection() -> reduced
                else -> selection
            }
        }
        navigation.restore()
        runCurrent()
        assertEquals(base + grandparent, stack)
        assertEquals(grandparent.selection(), repository.state.value.selection)
        // Both offline ancestors were actually tried, closest first, before landing on the
        // grandparent - never assumed live without asking the repository.
        assertEquals(
            listOf(child.selection(), parent.selection(), grandparent.selection()),
            repository.activations.takeLast(3).map { it.first },
        )
        assertTrue(repository.activations.takeLast(3).all { it.second == ActivationMode.RESTORE })
        navigation.reconcile(repository.state.value)
        assertEquals(base + grandparent, stack)
    }

    @Test
    fun `back through two consecutive offline chats falls back to the nearest live ancestor`() = runTest {
        val repository = NavigationFakeRepository()
        val (stack, navigation) = onParent(repository)
        val grandparent = RemoteNavKey.Chat("host", "project", "grandparent")
        val extra = RemoteNavKey.Chat("host", "project", "extra")
        navigation.openChild(grandparent, parent)
        runCurrent()
        navigation.openChild(parent, child)
        runCurrent()
        navigation.openChild(child, extra)
        runCurrent()
        assertEquals(base + grandparent + parent + child + extra, stack)
        // Back lands on the child, which is offline, and so is its own parent; the grandparent
        // beneath both is still live.
        val reduced = RemoteSelection("host", "project")
        repository.activation = { selection, mode ->
            when {
                mode != ActivationMode.RESTORE -> selection
                selection == child.selection() || selection == parent.selection() -> reduced
                else -> selection
            }
        }
        navigation.back()
        runCurrent()
        assertEquals(base + grandparent, stack)
        assertEquals(grandparent.selection(), repository.state.value.selection)
    }

    @Test
    fun `open from the session list drops stacked chats`() = runTest {
        val repository = NavigationFakeRepository()
        val (stack, navigation) = onParent(repository)
        navigation.openChild(parent, child)
        runCurrent()
        navigation.back()
        runCurrent()
        navigation.back()
        runCurrent()
        assertEquals(base, stack)
        val other = RemoteNavKey.Chat("host", "project", "other")
        navigation.open(other)
        runCurrent()
        assertEquals(base + other, stack)
        navigation.openChild(other, child)
        runCurrent()
        navigation.open(child)
        runCurrent()
        assertEquals(base + child, stack)
    }

    @Test
    fun `openChild across projects or onto itself behaves like open`() = runTest {
        val repository = NavigationFakeRepository()
        val (stack, navigation) = onParent(repository)
        val elsewhere = RemoteNavKey.Chat("host", "other", "child")
        navigation.openChild(parent, elsewhere)
        runCurrent()
        assertEquals(
            listOf(
                RemoteNavKey.Hosts,
                RemoteNavKey.Projects("host"),
                RemoteNavKey.Sessions("host", "other"),
                elsewhere,
            ),
            stack,
        )
        navigation.open(parent)
        runCurrent()
        navigation.openChild(parent, parent)
        runCurrent()
        assertEquals(base + parent, stack)
        assertEquals(parent.selection() to ActivationMode.USER_OPEN, repository.activations.last())
    }

    @Test
    fun `reconcile with a shortened selection drops parent and child`() = runTest {
        val repository = NavigationFakeRepository()
        val (stack, navigation) = onParent(repository)
        navigation.openChild(parent, child)
        runCurrent()
        navigation.reconcile(RemoteState(selection = RemoteSelection("host", "project")))
        assertEquals(base, stack)
    }

    @Test
    fun `at most four chats stay stacked`() = runTest {
        val repository = NavigationFakeRepository()
        val (stack, navigation) = onParent(repository)
        val chats = (1..4).map { RemoteNavKey.Chat("host", "project", "c$it") }
        var top = parent
        for (chat in chats) {
            navigation.openChild(top, chat)
            runCurrent()
            top = chat
        }
        assertEquals(base + chats, stack)
        navigation.restore()
        runCurrent()
        assertEquals(base + chats, stack)
        navigation.back()
        runCurrent()
        assertEquals(base + chats.take(3), stack)
    }

    @Test
    fun `fork replaces the source chat and its stacked parents so back leads to the sessions`() =
        runTest {
            val repository = NavigationFakeRepository()
            val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
            val navigation = RemoteNavigator(repository, stack, backgroundScope)
            val parent = RemoteNavKey.Chat("host", "project", "parent")
            val child = RemoteNavKey.Chat("host", "project", "child")
            navigation.open(parent)
            runCurrent()
            navigation.openChild(parent, child)
            runCurrent()
            navigation.forkSession(parent, "user-1", ForkMode.EDIT)
            runCurrent()
            assertTrue(repository.forks.isEmpty())
            navigation.forkSession(child, "user-2", ForkMode.RETRY)
            runCurrent()
            assertEquals(
                listOf(Triple(child.selection(), "user-2", ForkMode.RETRY)),
                repository.forks,
            )
            assertEquals(
                listOf(
                    RemoteNavKey.Hosts,
                    RemoteNavKey.Projects("host"),
                    RemoteNavKey.Sessions("host", "project"),
                    RemoteNavKey.Chat("host", "project", "fork"),
                ),
                stack,
            )
        }

    @Test
    fun `refused fork keeps the source chat on top`() = runTest {
        val repository = NavigationFakeRepository()
        repository.fork = { it }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        val chat = RemoteNavKey.Chat("host", "project", "session")
        navigation.open(chat)
        runCurrent()
        val before = stack.toList()
        navigation.forkSession(chat, "user-1", ForkMode.EDIT)
        runCurrent()
        assertEquals(before, stack)
    }

    @Test
    fun `rapid createSession calls reach the host once and do not cancel the first`() = runTest {
        val reply = CompletableDeferred<RemoteSelection>()
        var cancelled = false
        val repository = NavigationFakeRepository()
        repository.create = { _, _ ->
            try {
                reply.await()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.createSession("host", "project")
        runCurrent()
        navigation.createSession("host", "project")
        runCurrent()
        assertEquals(1, repository.createdSessions)
        assertFalse(cancelled)
        assertTrue(navigation.mutating)
        reply.complete(RemoteSelection("host", "project", "created"))
        runCurrent()
        assertEquals(RemoteNavKey.Chat("host", "project", "created"), stack.last())
        assertFalse(navigation.mutating)
    }

    @Test
    fun `createSession works again after the previous one completed`() = runTest {
        val repository = NavigationFakeRepository()
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.createSession("host", "project")
        runCurrent()
        navigation.createSession("host", "project")
        runCurrent()
        assertEquals(2, repository.createdSessions)
    }

    @Test
    fun `a create cancelled by other navigation releases the guard`() = runTest {
        val reply = CompletableDeferred<RemoteSelection>()
        val repository = NavigationFakeRepository()
        repository.create = { _, _ -> reply.await() }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.createSession("host", "project")
        runCurrent()
        navigation.open(RemoteNavKey.Projects("host"))
        runCurrent()
        assertFalse(navigation.mutating)
        repository.create = { routeId, projectId -> RemoteSelection(routeId, projectId, "second") }
        navigation.createSession("host", "project")
        runCurrent()
        assertEquals(2, repository.createdSessions)
        assertEquals(RemoteNavKey.Chat("host", "project", "second"), stack.last())
    }

    @Test
    fun `a fork in flight blocks a second fork and a create`() = runTest {
        val reply = CompletableDeferred<RemoteSelection>()
        val repository = NavigationFakeRepository()
        repository.fork = { reply.await() }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        val chat = RemoteNavKey.Chat("host", "project", "session")
        navigation.open(chat)
        runCurrent()
        navigation.forkSession(chat, "user-1", ForkMode.EDIT)
        runCurrent()
        navigation.forkSession(chat, "user-2", ForkMode.EDIT)
        navigation.createSession("host", "project")
        runCurrent()
        assertEquals(1, repository.forks.size)
        assertEquals(0, repository.createdSessions)
    }

    @Test
    fun `a create that ends without a session releases the guard`() = runTest {
        val repository = NavigationFakeRepository()
        repository.create = { _, _ -> throw CancellationException("connection dropped") }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.createSession("host", "project")
        runCurrent()
        assertFalse(navigation.mutating)
        assertEquals(listOf<NavKey>(RemoteNavKey.Hosts), stack)
        repository.create = { routeId, projectId -> RemoteSelection(routeId, projectId, "second") }
        navigation.createSession("host", "project")
        runCurrent()
        assertEquals(2, repository.createdSessions)
        assertEquals(RemoteNavKey.Chat("host", "project", "second"), stack.last())
    }

    @Test
    fun `a created session is fresh once and forks and reopened chats never are`() = runTest {
        val repository = NavigationFakeRepository()
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        repository.create = { routeId, projectId -> RemoteSelection(routeId, projectId, "created") }
        navigation.createSession("host", "project")
        runCurrent()
        val created = RemoteNavKey.Chat("host", "project", "created")
        assertEquals(created, navigation.freshSession)
        assertFalse(navigation.takeFreshSession(RemoteNavKey.Chat("host", "project", "other")))
        assertTrue(navigation.takeFreshSession(created))
        assertFalse(navigation.takeFreshSession(created))
        assertNull(navigation.freshSession)
        // Reopening the same chat later is not a new session.
        navigation.open(RemoteNavKey.Sessions("host", "project"))
        runCurrent()
        navigation.open(created)
        runCurrent()
        assertNull(navigation.freshSession)
        navigation.forkSession(created, "user-1", ForkMode.EDIT)
        runCurrent()
        assertEquals(RemoteNavKey.Chat("host", "project", "fork"), stack.last())
        assertNull(navigation.freshSession)
    }

    @Test
    fun `a create that lands without a session is not fresh`() = runTest {
        val repository = NavigationFakeRepository()
        repository.create = { routeId, projectId -> RemoteSelection(routeId, projectId) }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.createSession("host", "project")
        runCurrent()
        assertNull(navigation.freshSession)
    }

    // Share pick mode: "Choose another session…" in ShareActivity. Shares with files, which need
    // Uri, are in ShareIntakeTest.

    private fun pick(text: String = "Shared") = ShareRequest(SharedContent(text), ShareTarget.Pick)

    @Test
    fun `pick mode completes on a session tap`() = runTest {
        val repository = NavigationFakeRepository()
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"), RemoteNavKey.Sessions("host", "project"))
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        var consumed = 0
        navigation.share(pick()) { consumed++ }
        assertNotNull(navigation.sharePick)
        assertEquals("the share waits for a choice", 0, consumed)
        assertFalse("Back walks up while the stack has somewhere to go", navigation.backCancelsShare)
        navigation.open(RemoteNavKey.Chat("host", "project", "chosen"))
        runCurrent()
        assertNull(navigation.sharePick)
        assertEquals(1, consumed)
        assertEquals(RemoteNavKey.Chat("host", "project", "chosen"), stack.last())
        assertEquals(RemoteSelection("host", "project", "chosen"), repository.state.value.selection)
        assertEquals("Shared", repository.state.value.draft)
        // Pick mode is over: the next tap only opens.
        repository.draft("")
        navigation.open(RemoteNavKey.Chat("host", "project", "next"))
        runCurrent()
        assertEquals("", repository.state.value.draft)
        assertEquals(1, consumed)
    }

    @Test
    fun `pick mode completes on New session in a project`() = runTest {
        val repository = NavigationFakeRepository()
        repository.create = { routeId, projectId ->
            RemoteSelection(routeId, projectId, "created").also { repository.state.value = repository.state.value.copy(selection = it) }
        }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"), RemoteNavKey.Sessions("host", "project"))
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        var consumed = 0
        navigation.share(pick()) { consumed++ }
        navigation.createSession("host", "project")
        runCurrent()
        assertEquals(1, repository.createdSessions)
        assertEquals(1, consumed)
        assertNull(navigation.sharePick)
        assertEquals(RemoteNavKey.Chat("host", "project", "created"), stack.last())
        assertEquals("Shared", repository.state.value.draft)
    }

    @Test
    fun `back cancels the share where it would leave the app`() = runTest {
        val repository = NavigationFakeRepository()
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        var consumed = 0
        navigation.share(pick()) { consumed++ }
        assertTrue(navigation.backCancelsShare)
        navigation.cancelShare()
        assertNull(navigation.sharePick)
        assertFalse(navigation.backCancelsShare)
        assertEquals(1, consumed)
        navigation.open(RemoteNavKey.Chat("host", "project", "later"))
        runCurrent()
        assertEquals("a cancelled share reaches no chat", "", repository.state.value.draft)
        assertEquals(1, consumed)
    }

    @Test
    fun `pick mode starts from the sessions of the chat on top`() = runTest {
        val repository = NavigationFakeRepository()
        val stack = RemoteSelection("host", "project", "open").keys().toMutableList<NavKey>()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.share(pick())
        runCurrent()
        assertEquals(RemoteNavKey.Sessions("host", "project"), stack.last())
        assertNotNull(navigation.sharePick)
    }

    @Test
    fun `a Direct Share target without its project is looked up like a notification`() = runTest {
        val repository = NavigationFakeRepository()
        repository.notification = RemoteSelection("host", "project", "session")
        repository.notificationLookup = {
            repository.notification.also { selection -> repository.state.value = repository.state.value.copy(selection = checkNotNull(selection)) }
        }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        var consumed = 0
        navigation.share(ShareRequest(SharedContent("Shared"), ShareTarget.Session("host", null, "session"))) { consumed++ }
        assertEquals("taken at once", 1, consumed)
        runCurrent()
        assertEquals(listOf("host" to "session"), repository.notificationRequests)
        assertEquals(RemoteNavKey.Chat("host", "project", "session"), stack.last())
        assertEquals("Shared", repository.state.value.draft)

        // A session that is gone says so.
        repository.notification = null
        repository.notificationLookup = { null }
        navigation.share(ShareRequest(SharedContent("Again"), ShareTarget.Session("host", null, "gone")))
        runCurrent()
        assertEquals(listOf(R.string.remote_share_failed), repository.errors)
        assertEquals("Shared", repository.state.value.draft)
    }

    @Test
    fun `a share the editor's Keep holds back says it was dropped`() = runTest {
        val repository = NavigationFakeRepository()
        val editing = RemoteSelection("host", "project", "a")
        repository.state.value =
            RemoteState(
                selection = editing,
                files = FilesState("a", loading = false, editor = FileEditor("src/Main.kt", "v1", "old\n", "new\n")),
            )
        val stack = editing.keys().toMutableList<NavKey>()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.share(ShareRequest(SharedContent("Shared"), ShareTarget.Session("host", "project", "b")))
        runCurrent()
        assertNotNull("the unsaved edit asks first", navigation.editorLeave)
        navigation.stayInEditor()
        assertNull(navigation.editorLeave)
        assertEquals(listOf(R.string.remote_share_editor_kept), repository.errors)
        assertEquals(RemoteNavKey.Chat("host", "project", "a"), stack.last())
        assertEquals("", repository.state.value.draft)
    }
}
