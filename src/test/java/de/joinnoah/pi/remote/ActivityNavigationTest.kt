package de.joinnoah.pi.remote

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
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
}
