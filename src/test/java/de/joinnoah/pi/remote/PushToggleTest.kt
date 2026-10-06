package de.joinnoah.pi.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PushToggleTest {
    /** Wires settings, registration and the host like [RemoteApplication] does. */
    private class Harness(scope: CoroutineScope, val settings: SettingsRepository) {
        /** What the host was told, in order; null is a `push.register` with a null token. */
        val registered = mutableListOf<String?>()
        val requests = mutableListOf<(String) -> Unit>()
        val registration =
            PushRegistration(
                scope = scope,
                configured = { true },
                enabled = { settings.state.value.pushEnabled },
                requester = PushTokenRequester { success, _ -> requests += success },
                register = { registered += it },
                unregister = { registered += null },
                optedOut = { settings.state.value.pushOptedOut },
            )

        fun enable() {
            settings.setPushEnabled(true)
            registration.onEnabled()
        }

        fun disable() {
            settings.setPushEnabled(false)
            registration.onDisabled()
        }
    }

    private fun settings() = FakeSettingsRepository()

    @Test
    fun turningPushOffSendsANullTokenAndStopsPosting() = runTest {
        val harness = Harness(backgroundScope, settings())
        harness.enable()
        harness.requests.single()("token")
        assertEquals(listOf<String?>("token"), harness.registered)
        assertTrue(postsPushes(harness.settings.state.value))

        harness.disable()

        assertEquals(listOf<String?>("token", null), harness.registered)
        assertFalse(postsPushes(harness.settings.state.value))
    }

    @Test
    fun turningPushOnRegistersTheCurrentTokenAgain() = runTest {
        val harness = Harness(backgroundScope, settings())
        harness.enable()
        harness.requests.single()("token")
        harness.disable()

        harness.enable()
        assertEquals("A new token fetch starts", 2, harness.requests.size)
        harness.requests.last()("token")

        assertEquals(listOf<String?>("token", null, "token"), harness.registered)
        assertTrue(postsPushes(harness.settings.state.value))
    }

    @Test
    fun aTokenFetchThatFinishesAfterTurningOffIsNotRegistered() = runTest {
        val harness = Harness(backgroundScope, settings())
        harness.enable()
        harness.disable()

        harness.requests.single()("late")

        assertEquals(listOf<String?>(null), harness.registered)
    }

    @Test
    fun aRotatedTokenIsIgnoredWhilePushIsOff() = runTest {
        val harness = Harness(backgroundScope, settings())
        harness.enable()
        harness.disable()

        harness.registration.onTokenRotated("rotated")
        harness.registration.onRecovery()

        assertEquals(listOf<String?>(null), harness.registered)
        assertEquals(1, harness.requests.size)
    }

    @Test
    fun aRotatedTokenIsRegisteredWhilePushIsOn() = runTest {
        val harness = Harness(backgroundScope, settings())
        harness.enable()

        harness.registration.onTokenRotated("rotated")

        assertEquals(listOf<String?>("rotated"), harness.registered)
    }

    @Test
    fun aRestartAfterSwitchingOffTellsTheHostAgain() = runTest {
        val harness = Harness(backgroundScope, settings())
        harness.enable()
        harness.disable()
        harness.registered.clear()

        harness.registration.onStartup()

        assertEquals(listOf<String?>(null), harness.registered)
        assertEquals("No token is fetched", 1, harness.requests.size)
    }

    @Test
    fun pushThatWasNeverSwitchedOnSendsNothingOnStartup() = runTest {
        val harness = Harness(backgroundScope, settings())

        harness.registration.onStartup()

        assertTrue(harness.registered.isEmpty())
        assertTrue(harness.requests.isEmpty())
    }

    @Test
    fun aRestartWithPushOnRegistersTheToken() = runTest {
        val harness = Harness(backgroundScope, settings())
        harness.settings.setPushEnabled(true)

        harness.registration.onStartup()
        harness.requests.single()("token")

        assertEquals(listOf<String?>("token"), harness.registered)
    }

    @Test
    fun theSettingSurvivesARestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = DefaultSettingsRepository(context)
        assertFalse(first.state.value.pushOptedOut)

        first.setPushEnabled(true)
        assertTrue(DefaultSettingsRepository(context).state.value.pushEnabled)
        assertFalse(DefaultSettingsRepository(context).state.value.pushOptedOut)

        first.setPushEnabled(false)
        val restarted = DefaultSettingsRepository(context).state.value
        assertFalse(restarted.pushEnabled)
        assertTrue(restarted.pushOptedOut)

        first.setPushEnabled(true)
        assertFalse(DefaultSettingsRepository(context).state.value.pushOptedOut)
    }
}
