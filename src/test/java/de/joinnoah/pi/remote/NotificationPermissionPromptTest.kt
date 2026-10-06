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
        assertEquals(PairingNotificationStep.ASK, offer(settings))
        assertTrue(settings.state.value.notificationPromptShown)
        assertEquals("A second pairing doesn't ask again", PairingNotificationStep.NOTHING, offer(settings))
    }

    @Test
    fun aDenialNeverPromptsAgain() {
        val settings = FakeSettingsRepository()
        assertEquals(PairingNotificationStep.ASK, offer(settings))
        // The user denied: push stays off, the permission stays missing.
        assertFalse(settings.state.value.pushEnabled)
        repeat(3) { assertEquals(PairingNotificationStep.NOTHING, offer(settings)) }
    }

    @Test
    fun belowAndroid13PushIsSwitchedOnDirectlyOnce() {
        val settings = FakeSettingsRepository()
        assertEquals(PairingNotificationStep.ENABLE, offer(settings, sdk = 31))
        assertTrue(settings.state.value.notificationPromptShown)
        assertEquals(PairingNotificationStep.NOTHING, offer(settings, sdk = 31))
    }

    @Test
    fun nothingHappensOnceGrantedWithoutPushOrAfterSwitchingOff() {
        assertEquals(PairingNotificationStep.NOTHING, offer(FakeSettingsRepository(), granted = true))
        assertEquals(
            PairingNotificationStep.NOTHING,
            offerNotificationPermission(34, false, pushConfigured = false, FakeSettingsRepository()),
        )
        val enabled = FakeSettingsRepository().apply { setPushEnabled(true) }
        assertEquals(PairingNotificationStep.NOTHING, offer(enabled))
        assertFalse("Nothing was recorded for a prompt that never showed", enabled.state.value.notificationPromptShown)
        val optedOut = FakeSettingsRepository().apply { setPushEnabled(true); setPushEnabled(false) }
        assertEquals(PairingNotificationStep.NOTHING, offer(optedOut))
        assertEquals(PairingNotificationStep.NOTHING, offer(optedOut, sdk = 31))
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
