package de.joinnoah.pi.remote

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FolderNavigationTest {
    private val projects = listOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"))

    @Test
    fun `open folders pushes the browser at the root on top of projects`() = runTest {
        val repository = NavigationFakeRepository()
        val stack = projects.toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.openFolders("other")
        assertEquals(projects, stack)
        navigation.openFolders("host")
        assertEquals(projects + RemoteNavKey.FolderBrowser("host"), stack)
        assertEquals(listOf("host" to ""), repository.browsed)
        assertEquals(2, repository.folderPromptsCleared)
    }

    @Test
    fun `back goes up one level before leaving the browser`() = runTest {
        val repository = NavigationFakeRepository()
        val stack = projects.toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.openFolders("host")
        navigation.browseFolder("host", "Code/app")
        navigation.back()
        assertEquals("Code", repository.state.value.folders.path)
        navigation.back()
        assertEquals("", repository.state.value.folders.path)
        assertEquals(RemoteNavKey.FolderBrowser("host"), stack.last())
        navigation.back()
        runCurrent()
        assertEquals(projects, stack)
    }

    @Test
    fun `restore keeps the browser on top of its projects`() = runTest {
        val repository = NavigationFakeRepository()
        val stack = (projects + RemoteNavKey.FolderBrowser("host")).toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.restore()
        runCurrent()
        assertEquals(projects + RemoteNavKey.FolderBrowser("host"), stack)
        assertEquals(RemoteSelection("host"), repository.activations.single().first)
    }

    @Test
    fun `folders opened while the projects still activate stay open`() = runTest {
        val repository = NavigationFakeRepository()
        val gate = CompletableDeferred<Unit>()
        repository.activation = { selection, _ ->
            gate.await()
            selection
        }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.open(RemoteNavKey.Projects("host"))
        runCurrent()
        navigation.openFolders("host")
        assertEquals(projects + RemoteNavKey.FolderBrowser("host"), stack)

        gate.complete(Unit)
        runCurrent()

        assertEquals(projects + RemoteNavKey.FolderBrowser("host"), stack)
    }

    @Test
    fun `providers opened while the projects still activate stay open`() = runTest {
        val repository = NavigationFakeRepository()
        val gate = CompletableDeferred<Unit>()
        repository.activation = { selection, _ ->
            gate.await()
            selection
        }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.open(RemoteNavKey.Projects("host"))
        runCurrent()
        navigation.openProviders("host")

        gate.complete(Unit)
        runCurrent()

        assertEquals(projects + RemoteNavKey.Providers("host"), stack)
    }

    @Test
    fun `restore keeps the browser on top of stacked parent chats`() = runTest {
        val repository = NavigationFakeRepository()
        val child = RemoteNavKey.Chat("host", "project", "child")
        val parent = RemoteNavKey.Chat("host", "project", "parent")
        val chats = child.selection().keys().dropLast(1) + parent + child
        val stack = (chats + RemoteNavKey.FolderBrowser("host")).toMutableList<NavKey>()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)

        navigation.restore()
        runCurrent()

        assertEquals(chats + RemoteNavKey.FolderBrowser("host"), stack)
    }

    @Test
    fun `restore keeps the board and the browser above it`() = runTest {
        val repository = NavigationFakeRepository()
        val open = RemoteSelection("host", "project", "s1").keys()
        val overlays = listOf(RemoteNavKey.Activity("host"), RemoteNavKey.FolderBrowser("host"))
        val stack = (open + overlays).toMutableList<NavKey>()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)

        navigation.restore()
        runCurrent()

        assertEquals(open + overlays, stack)
    }

    @Test
    fun `opened folder goes straight into the new chat`() = runTest {
        val repository = NavigationFakeRepository()
        val stack = projects.toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.openFolders("host")
        navigation.openFolder("host", "Code/app", FolderTrustPrompt("host", "Code/app", piConfig = false))
        runCurrent()
        assertEquals(Triple("host", "Code/app", true), repository.folderOpens.single())
        assertEquals(
            listOf(
                RemoteNavKey.Hosts,
                RemoteNavKey.Projects("host"),
                RemoteNavKey.Sessions("host", "opened"),
                RemoteNavKey.Chat("host", "opened", "fresh"),
            ),
            stack,
        )
    }

    @Test
    fun `a folder waiting for trust keeps the browser open`() = runTest {
        val repository = NavigationFakeRepository()
        repository.folderOpen = { _, _, _ -> null }
        val stack = projects.toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.openFolders("host")
        navigation.openFolder("host", "wild")
        runCurrent()
        assertEquals(projects + RemoteNavKey.FolderBrowser("host"), stack)
        // A refused begin never invalidates the current screen or reaches openFolder.
        repository.beginResult = false
        navigation.openFolder("host", "app")
        runCurrent()
        assertEquals(1, repository.folderOpens.size)
    }

    @Test
    fun `beside the projects column the browser opens over the open chat and back returns to it`() = runTest {
        for (open in listOf(RemoteSelection("host", "project"), RemoteSelection("host", "project", "s1"))) {
            val repository = NavigationFakeRepository()
            val stack = open.keys().toMutableList<NavKey>()
            val navigation = RemoteNavigator(repository, stack, backgroundScope)
            navigation.openFolders("other")
            assertEquals(open.keys(), stack)
            navigation.openFolders("host")
            runCurrent()
            // The open project or chat stays beneath the browser; its selection is not re-activated.
            assertEquals(open.keys() + RemoteNavKey.FolderBrowser("host"), stack)
            assertEquals(emptyList<Pair<RemoteSelection, ActivationMode>>(), repository.activations)
            assertEquals(listOf("host" to ""), repository.browsed)
            // Back from the browser returns to the chat or project it was opened over.
            navigation.back()
            runCurrent()
            assertEquals(open.keys(), stack)
            assertEquals(open to ActivationMode.RESTORE, repository.activations.last())
        }
    }

    @Test
    fun `close leaves the browser in one step whatever folder it shows`() = runTest {
        val open = RemoteSelection("host", "project", "s1")
        val repository = NavigationFakeRepository()
        val stack = (open.keys() + RemoteNavKey.FolderBrowser("host")).toMutableList<NavKey>()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.browseFolder("host", "a/b")
        navigation.closeFolders("host")
        runCurrent()
        assertEquals(open.keys(), stack)
        assertEquals(open to ActivationMode.RESTORE, repository.activations.last())
        // A close for a browser that is not open does nothing.
        navigation.closeFolders("host")
        runCurrent()
        assertEquals(open.keys(), stack)
    }

    @Test
    fun `a host lost while the folder browser is open leaves the page`() = runTest {
        val repository = NavigationFakeRepository()
        repository.activation = { _, _ -> RemoteSelection() }
        val stack = RemoteSelection("host", "project", "s1").keys().toMutableList<NavKey>()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.openFolders("host")
        runCurrent()
        navigation.restore()
        runCurrent()
        assertEquals(listOf<NavKey>(RemoteNavKey.Hosts), stack)
    }

    @Test
    fun `a failed restore beside the projects column keeps the browser open`() = runTest {
        val open = RemoteSelection("host", "project", "s1")
        val repository = NavigationFakeRepository()
        // The repository answers a failed restore with the selection it still holds.
        repository.activation = { _, _ -> open }
        val stack = open.keys().toMutableList<NavKey>()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.openFolders("host")
        runCurrent()
        navigation.restore()
        runCurrent()
        assertEquals(open.keys() + RemoteNavKey.FolderBrowser("host"), stack)
    }
}
