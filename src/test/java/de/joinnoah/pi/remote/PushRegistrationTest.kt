package de.joinnoah.pi.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRegistrationTest {
    @Test
    fun startupDoesNotFetchWhenFirebaseIsNotConfigured() = runTest {
        val requester = FakeTokenRequester()
        val registration =
            PushRegistration(
                scope = backgroundScope,
                configured = { false },
                enabled = { true },
                requester = requester,
                register = {},
            )

        registration.onStartup()

        assertTrue(requester.requests.isEmpty())
    }

    @Test
    fun enabledSignalDoesNotFetchWhenPushIsDisabled() = runTest {
        val requester = FakeTokenRequester()
        val registration =
            PushRegistration(
                scope = backgroundScope,
                configured = { true },
                enabled = { false },
                requester = requester,
                register = {},
            )

        registration.onEnabled()

        assertTrue(requester.requests.isEmpty())
    }

    @Test
    fun startupEnableAndRecoveryShareOnePendingFetch() = runTest {
        val requester = FakeTokenRequester()
        val registration = registration(requester, scope = backgroundScope)

        registration.onStartup()
        registration.onEnabled()
        registration.onRecovery()

        assertEquals(1, requester.requests.size)
    }

    @Test
    fun recoveryRetriesAFailedFetch() = runTest {
        val requester = FakeTokenRequester()
        val tokens = mutableListOf<String>()
        val registration = registration(requester, tokens, backgroundScope)

        registration.onStartup()
        requester.requests.single().failure()
        registration.onRecovery()
        requester.requests.last().success("fresh-token")

        assertEquals(listOf("fresh-token"), tokens)
        assertEquals(2, requester.requests.size)
    }

    @Test
    fun failedAndEmptyFetchesDoNotReplaceTheLastRegisteredToken() = runTest {
        val requester = FakeTokenRequester()
        val tokens = mutableListOf<String>()
        val registration = registration(requester, tokens, backgroundScope)

        registration.onStartup()
        requester.requests.single().success("first-token")
        registration.onRecovery()
        requester.requests.last().failure()
        registration.onRecovery()
        requester.requests.last().success("")

        assertEquals(listOf("first-token"), tokens)
    }

    @Test
    fun rotatedTokenFencesAnOlderFetchResult() = runTest {
        val requester = FakeTokenRequester()
        val tokens = mutableListOf<String>()
        val registration = registration(requester, tokens, backgroundScope)

        registration.onStartup()
        val olderFetch = requester.requests.single()
        registration.onTokenRotated("new-token")
        olderFetch.success("old-token")

        assertEquals(listOf("new-token"), tokens)
    }

    @Test
    fun disableThenEnableFencesTheEarlierFetchResult() = runTest {
        var enabled = true
        val requester = FakeTokenRequester()
        val tokens = mutableListOf<String>()
        val registration =
            PushRegistration(
                scope = backgroundScope,
                configured = { true },
                enabled = { enabled },
                requester = requester,
                register = tokens::add,
        )

        registration.onStartup()
        val olderFetch = requester.requests.single()
        enabled = false
        registration.onDisabled()
        enabled = true
        registration.onEnabled()
        olderFetch.success("late-token")
        requester.requests.last().success("fresh-token")

        assertEquals(listOf("fresh-token"), tokens)
    }

    @Test
    fun timeoutLetsRecoveryStartAFreshFetchAndFencesTheTimedOutResult() = runTest {
        val requester = FakeTokenRequester()
        val tokens = mutableListOf<String>()
        val registration = registration(requester, tokens, backgroundScope, timeoutMillis = 100)

        registration.onStartup()
        val timedOutFetch = requester.requests.single()
        advanceTimeBy(100)
        runCurrent()
        registration.onRecovery()
        timedOutFetch.success("stale-token")
        requester.requests.last().success("retried-token")

        assertEquals(listOf("retried-token"), tokens)
        assertEquals(2, requester.requests.size)
    }

    private fun registration(
        requester: FakeTokenRequester,
        tokens: MutableList<String> = mutableListOf(),
        scope: CoroutineScope,
        timeoutMillis: Long = 1_000,
    ) =
        PushRegistration(
            scope = scope,
            configured = { true },
            enabled = { true },
            requester = requester,
            register = tokens::add,
            timeoutMillis = timeoutMillis,
        )

    private class FakeTokenRequester : PushTokenRequester {
        val requests = mutableListOf<Request>()

        override fun request(success: (String) -> Unit, failure: () -> Unit) {
            requests += Request(success, failure)
        }

        data class Request(val success: (String) -> Unit, val failure: () -> Unit)
    }
}
