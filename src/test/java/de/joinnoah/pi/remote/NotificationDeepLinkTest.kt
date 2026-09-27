package de.joinnoah.pi.remote

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationDeepLinkTest {
    @Test
    fun notificationOpensItsSessionEvenWhenAnotherProjectAndSettingsAreOpen() = runTest {
        val repository = NavigationFakeRepository()
        repository.notification = RemoteSelection("host", "other", "target")
        val stack =
            mutableListOf<NavKey>(
                RemoteNavKey.Hosts,
                RemoteNavKey.Projects("host"),
                RemoteNavKey.Sessions("host", "current"),
                RemoteNavKey.Chat("host", "current", "open"),
                RemoteNavKey.Settings,
            )
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        var consumed = 0
        navigation.notification("host", "target") { consumed++ }
        runCurrent()
        assertEquals(
            listOf(
                RemoteNavKey.Hosts,
                RemoteNavKey.Projects("host"),
                RemoteNavKey.Sessions("host", "other"),
                RemoteNavKey.Chat("host", "other", "target"),
            ),
            stack,
        )
        assertEquals(1, consumed)
    }

    @Test
    fun notificationFromAnotherHostBuildsTheWholeStackOnAColdStart() = runTest {
        val repository = NavigationFakeRepository()
        repository.notification = RemoteSelection("second", "project", "target")
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.restore()
        navigation.notification("second", "target")
        runCurrent()
        assertEquals(RemoteSelection("second", "project", "target").keys(), stack)
    }
}
