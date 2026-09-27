package de.joinnoah.pi.remote

import androidx.navigation3.runtime.NavKey
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
}
