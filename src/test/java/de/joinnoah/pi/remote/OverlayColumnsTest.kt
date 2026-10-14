package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayColumnsTest {
    private val chat = RemoteSelection("host", "project", "s1").keys()

    @Test
    fun providersFoldersAndSettingsTakeTheDetailColumnBesideTheProjects() {
        for (page in listOf<RemoteNavKey>(RemoteNavKey.Providers("host"), RemoteNavKey.FolderBrowser("host"), RemoteNavKey.Settings)) {
            // Over a chat: the projects and the session list stay, the page takes the chat's column.
            assertEquals(ColumnPanes(projects = 1, list = 2, detail = 4), columnPanes(chat + page))
            // Over the open project: the page takes the sessions' column beside the projects.
            assertEquals(ColumnPanes(projects = 1, list = 2, detail = 3), columnPanes(RemoteSelection("host", "project").keys() + page))
            // Over the host's projects only.
            assertEquals(ColumnPanes(projects = 1, list = null, detail = 2), columnPanes(RemoteSelection("host").keys() + page))
        }
    }

    @Test
    fun theHostsListKeepsItsFullScreenPages() {
        assertNull(columnPanes(listOf(RemoteNavKey.Hosts, RemoteNavKey.Settings)))
        assertNull(columnPanes(listOf(RemoteNavKey.Hosts, RemoteNavKey.Providers("host"))))
    }

    @Test
    fun settingsOverAPageOfProvidersKeepsTheColumnsBeneath() {
        val keys = RemoteSelection("host", "project", "s1").keys() + RemoteNavKey.Providers("host") + RemoteNavKey.Settings
        assertEquals(ColumnPanes(projects = 1, list = 2, detail = 5), columnPanes(keys))
    }
}
