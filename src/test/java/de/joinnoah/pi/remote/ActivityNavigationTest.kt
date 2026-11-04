package de.joinnoah.pi.remote

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityNavigationTest {
    private val chat: List<NavKey> = RemoteSelection("host", "project", "s1").keys()

    @Test
    fun theBoardOpensOnTopOfTheChatOfItsOwnHostOnly() = runTest {
        val repository = NavigationFakeRepository()
        val stack = chat.toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)

        navigation.openActivity("other")
        assertEquals(chat, stack)

        navigation.openActivity("host")
        navigation.openActivity("host")
        assertEquals(chat + RemoteNavKey.Activity("host"), stack)
    }

    @Test
    fun theBoardBringsACollapsedListBackBesideTheChat() = runTest {
        val repository = NavigationFakeRepository()
        val stack = chat.toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        val twoPane = TwoPaneState(listCollapsed = true)

        navigation.openActivity("other", twoPane)
        assertTrue(twoPane.listCollapsed)

        navigation.openActivity("host", twoPane)
        assertFalse(twoPane.listCollapsed)
        assertEquals(chat + RemoteNavKey.Activity("host"), stack)
    }

    @Test
    fun theChatBesideTheBoardKeepsItsShortcutsInTwoPanesOnly() = runTest {
        val repository = NavigationFakeRepository()
        val stack = chat.toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        val open = chat.last() as RemoteNavKey.Chat
        assertEquals(open, navigation.chatInView(null))

        navigation.openActivity("host")

        assertNull(navigation.topChat)
        // On a phone the board covers the chat; in two panes the chat stays in the detail column.
        assertNull(navigation.chatInView(null))
        assertEquals(open, navigation.chatInView(TwoPaneState()))
    }

    @Test
    fun backFromTheBoardReturnsToTheChatBeneathIt() = runTest {
        val repository = NavigationFakeRepository()
        val stack = chat.toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.openActivity("host")

        navigation.back()
        runCurrent()

        assertEquals(chat, stack)
    }

    @Test
    fun theActivityKeySurvivesASavedBackStack() {
        val keys = (chat + RemoteNavKey.Activity("host")).map { it as RemoteNavKey }
        val serializer = ListSerializer(serializer<RemoteNavKey>())

        val saved = Json.encodeToString(serializer, keys)

        assertEquals(keys, Json.decodeFromString(serializer, saved))
    }

    @Test
    fun aRestoreWithTheBoardOnTopKeepsTheChatAndTheBoard() = runTest {
        val repository = NavigationFakeRepository()
        val stack = (chat + RemoteNavKey.Activity("host")).toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)

        navigation.restore()
        runCurrent()

        assertEquals(
            listOf(RemoteSelection("host", "project", "s1") to ActivationMode.RESTORE),
            repository.activations,
        )
        assertEquals(chat + RemoteNavKey.Activity("host"), stack)
    }

    @Test
    fun aRestoreThatFallsBackKeepsTheBoardOnTopOfWhatIsLeft() = runTest {
        val repository = NavigationFakeRepository()
        repository.activation = { _, _ -> RemoteSelection("host", "project") }
        val stack = (chat + RemoteNavKey.Activity("host")).toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)

        navigation.restore()
        runCurrent()

        assertEquals(
            RemoteSelection("host", "project").keys() + RemoteNavKey.Activity("host"),
            stack,
        )
    }

    @Test
    fun aRestoreThatLosesTheHostDropsTheBoard() = runTest {
        val repository = NavigationFakeRepository()
        repository.activation = { _, _ -> RemoteSelection() }
        val stack = (chat + RemoteNavKey.Activity("host")).toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)

        navigation.restore()
        runCurrent()

        assertEquals(listOf<NavKey>(RemoteNavKey.Hosts), stack)
    }

    @Test
    fun theBoardDoesNotOpenOverSettings() = runTest {
        val repository = NavigationFakeRepository()
        val stack = (chat + RemoteNavKey.Settings).toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)

        navigation.openActivity("host")

        assertEquals(chat + RemoteNavKey.Settings, stack)
    }

    @Test
    fun aCardOpensItsChatInPlaceOfTheBoard() = runTest {
        val repository = NavigationFakeRepository()
        val stack = (chat + RemoteNavKey.Activity("host")).toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)

        navigation.open(RemoteNavKey.Chat("host", "project", "s2"))
        runCurrent()

        assertEquals(RemoteSelection("host", "project", "s2").keys(), stack)
        assertEquals(RemoteSelection("host", "project", "s2"), repository.activations.last().first)
    }

    @Test
    fun theSessionShortcutsFollowTheListOrderWithPinnedSessionsFirst() = runTest {
        val repository = NavigationFakeRepository()
        repository.state.value =
            RemoteState(
                selection = RemoteSelection("host", "project", "s1"),
                connected = true,
                sessions =
                    listOf("s1", "s2", "s3").map {
                        Wire.objectOf("id" to it, "origin" to "rpc", "title" to it, "status" to "idle", "updatedAt" to 10L)
                    },
            )
        val stack = chat.toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        // The list is not on screen (a phone's chat): the shortcut orders the sessions itself.
        val targets = ShortcutTargets().apply { sessionMarks = mapOf(("host" to "s2") to SessionMark(pinned = true)) }

        // Pinned s2 lists first, so the session after the open s1 is s3 (unmarked it would be s2).
        navigation.shortcut(Shortcut.NEXT_SESSION, null, targets)
        runCurrent()

        assertEquals(RemoteNavKey.Chat("host", "project", "s3"), stack.last())
    }
}
