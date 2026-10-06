package de.joinnoah.pi.remote

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPromptTest {
    private fun offer(settings: SettingsRepository, sdk: Int = 34, granted: Boolean = false) =
        offerNotificationPermission(sdk, granted, pushConfigured = true, settings)

    @Test
    fun theFirstPairingOffersThePermissionOnce() {
        val settings = FakeSettingsRepository()
        assertTrue(offer(settings))
        assertTrue(settings.state.value.notificationPromptShown)
        assertFalse("A second pairing doesn't ask again", offer(settings))
    }

    @Test
    fun aDenialNeverPromptsAgain() {
        val settings = FakeSettingsRepository()
        assertTrue(offer(settings))
        // The user denied: push stays off, the permission stays missing.
        assertFalse(settings.state.value.pushEnabled)
        repeat(3) { assertFalse(offer(settings)) }
    }

    @Test
    fun noPromptBelowAndroid13OnceGrantedOrWithoutPush() {
        assertFalse(offer(FakeSettingsRepository(), sdk = 32))
        assertFalse(offer(FakeSettingsRepository(), granted = true))
        assertFalse(offerNotificationPermission(34, false, pushConfigured = false, FakeSettingsRepository()))
        val enabled = FakeSettingsRepository().apply { setPushEnabled(true) }
        assertFalse(offer(enabled))
        assertFalse("Nothing was recorded for a prompt that never showed", enabled.state.value.notificationPromptShown)
    }

    @Test
    fun theNavigatorReportsOnlyASuccessfulPairing() = runTest {
        val repository = NavigationFakeRepository()
        var paired = 0
        val navigation =
            RemoteNavigator(repository, mutableListOf(RemoteNavKey.Hosts), backgroundScope) { paired++ }

        navigation.pair("bad")
        runCurrent()
        assertEquals(0, paired)

        repository.pairResult = RemoteSelection("host")
        navigation.pair("good")
        runCurrent()
        assertEquals(1, paired)
    }
}
