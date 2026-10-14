package de.joinnoah.pi.remote

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.window.Dialog
import java.time.Duration
import org.robolectric.shadows.ShadowSystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var now = 1_000_000L
    private var secure = true

    @Before fun secureDevice() {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
    }

    private fun store(name: String = "app_lock_test") =
        AppLockStore(context.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() })

    private fun lock(store: AppLockStore = store()) = AppLock(store, deviceSecure = { secure }, clock = { now })

    private val target = NotificationTarget("route", "session")

    @Test fun theLockIsOffByDefault() {
        val store = store()
        assertEquals(AppLockSettings(enabled = false, timeoutMinutes = 5), store.state.value)
        val lock = lock(store)
        lock.check()
        assertFalse(lock.locked.value)
        assertEquals(target, lock.admit(target))
    }

    @Test fun theSettingIsKeptInItsOwnFile() {
        store("app_lock_kept").apply {
            setEnabled(true)
            setTimeoutMinutes(15)
        }
        val reread = AppLockStore(context.getSharedPreferences("app_lock_kept", Context.MODE_PRIVATE))
        assertEquals(AppLockSettings(enabled = true, timeoutMinutes = 15), reread.state.value)
        // An unknown stored timeout falls back to the default.
        context.getSharedPreferences("app_lock_odd", Context.MODE_PRIVATE).edit().putInt("timeout_minutes", 7).commit()
        assertEquals(5, AppLockStore(context.getSharedPreferences("app_lock_odd", Context.MODE_PRIVATE)).state.value.timeoutMinutes)
        // The app's own store is the "app_lock" file, apart from "settings".
        AppLockStore(context).setEnabled(true)
        assertTrue(context.getSharedPreferences("app_lock", Context.MODE_PRIVATE).getBoolean("enabled", false))
        assertFalse(context.getSharedPreferences("settings", Context.MODE_PRIVATE).contains("enabled"))
    }

    @Test fun aColdStartIsLockedAndALaterReturnOnlyAfterTheTimeout() {
        val store = store().apply {
            setEnabled(true)
            setTimeoutMinutes(5)
        }
        val lock = lock(store)
        lock.check()
        assertTrue("a new process asks", lock.locked.value)
        assertNull(lock.unlock())
        assertFalse(lock.locked.value)

        lock.onBackground()
        now += 5 * 60_000L - 1
        lock.check()
        assertFalse("not due before five minutes", lock.locked.value)

        lock.onBackground()
        now += 5 * 60_000L
        lock.check()
        assertTrue("due after five minutes", lock.locked.value)
        // Time spent locked does not count as time in front.
        lock.onBackground()
        lock.unlock()
        now += 60_000L
        lock.check()
        assertFalse(lock.locked.value)
    }

    @Test fun nothingTimesOutWhileTheAppIsInFront() {
        val store = store().apply {
            setEnabled(true)
            setTimeoutMinutes(5)
        }
        val lock = lock(store)
        lock.check()
        lock.onForeground()
        lock.unlock()
        // Six minutes of use, then a tap on a notification while still in front.
        now += 6 * 60_000L
        assertEquals(target, lock.admit(target))
        assertFalse(lock.locked.value)

        // Switched on in a process that was never in the background: a tap in front opens at once.
        val fresh = lock(store().apply { setEnabled(false) })
        fresh.onForeground()
        fresh.store.setEnabled(true)
        assertEquals(target, fresh.admit(target))
        assertFalse(fresh.locked.value)
        // Away for longer than the timeout: the tap that brings it back is held.
        fresh.onBackground()
        now += 5 * 60_000L
        assertNull(fresh.admit(target))
        assertTrue(fresh.locked.value)
    }

    @Test fun withoutAScreenLockNothingIsAsked() {
        val store = store().apply { setEnabled(true) }
        secure = false
        val lock = lock(store)
        lock.check()
        assertFalse(lock.locked.value)
        assertFalse(lock.active())
    }

    @Test fun aHeldTargetOpensAfterUnlockAndIsDroppedOnCancel() {
        val store = store().apply { setEnabled(true) }
        val lock = lock(store)
        assertNull("held while locked", lock.admit(target))
        val newer = NotificationTarget("route", "newer", jobs = true)
        assertNull(lock.admit(newer))
        assertEquals("the newest target opens", newer, lock.unlock())
        assertNull("and only once", lock.unlock())

        lock.onBackground()
        now += 60 * 60_000L
        assertNull(lock.admit(target))
        lock.cancel()
        assertTrue(lock.locked.value)
        assertNull("a cancelled target is gone", lock.unlock())
    }

    // MainActivity: a notification tap while locked opens its session only after unlock.

    private class FakePrompt : AppLockPrompt {
        var shown = 0

        override fun show() {
            shown++
        }

        override fun cancel() {}
    }

    private val app get() = ApplicationProvider.getApplicationContext<RemoteApplication>()

    private fun pending(controller: org.robolectric.android.controller.ActivityController<MainActivity>): String? =
        Bundle().also { controller.saveInstanceState(it) }.getString("pendingSessionId")

    private fun lockedActivity(intent: android.content.Intent): Pair<org.robolectric.android.controller.ActivityController<MainActivity>, FakePrompt> {
        app.appLock.store.setEnabled(true)
        val prompt = FakePrompt()
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        controller.get().appLockPrompt = prompt
        controller.create().start().resume()
        return controller to prompt
    }

    @Test fun aNotificationTapWhileLockedOpensTheSessionOnlyAfterUnlock() {
        val (controller, prompt) = lockedActivity(LaunchIntents.session(context, "route", "session"))
        assertTrue(app.appLock.locked.value)
        assertEquals("the lock asks by itself", 1, prompt.shown)
        assertNull("nothing opens behind the lock", pending(controller))
        controller.get().onAppLockResult(true)
        assertFalse(app.appLock.locked.value)
        assertEquals("session", pending(controller))
    }

    @Test fun aCancelledUnlockDropsTheTapAndALaterTapAsksAgain() {
        val (controller, prompt) = lockedActivity(LaunchIntents.session(context, "route", "first"))
        controller.get().onAppLockResult(false)
        assertTrue(app.appLock.locked.value)
        assertNull(pending(controller))
        // A second tap while still locked is held and asks again.
        controller.pause()
        controller.newIntent(LaunchIntents.session(context, "route", "second"))
        controller.resume()
        assertEquals(2, prompt.shown)
        assertNull(pending(controller))
        controller.get().onAppLockResult(true)
        assertEquals("second", pending(controller))
    }

    @Test fun aTapInFrontAfterTheTimeoutOpensAtOnce() {
        val (controller, _) = lockedActivity(LaunchIntents.app(context))
        controller.get().onAppLockResult(true)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
        // A tap on a heads-up notification while the app is in front: no onStop on the way.
        controller.pause()
        controller.newIntent(LaunchIntents.session(context, "route", "later"))
        controller.resume()
        assertFalse(app.appLock.locked.value)
        assertEquals("later", pending(controller))
    }

    @Test fun aHeldTargetSurvivesTheActivityBeingRecreated() {
        val (controller, prompt) = lockedActivity(LaunchIntents.session(context, "route", "held"))
        val saved = Bundle().also { controller.pause().stop().saveInstanceState(it) }
        assertEquals("held", saved.getString("heldSessionId"))
        controller.destroy()
        // The process died: a new one starts locked and has forgotten the target.
        app.appLock.unlock()
        app.appLock.onBackground()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
        val restored = Robolectric.buildActivity(MainActivity::class.java, LaunchIntents.app(context))
        restored.get().appLockPrompt = prompt
        restored.create(saved).start().resume()
        assertTrue(app.appLock.locked.value)
        assertEquals("held", app.appLock.heldTarget?.sessionId)
        restored.get().onAppLockResult(true)
        assertEquals("held", pending(restored))
    }

    @Test fun anUnlockedAppOpensATapAtOnce() {
        val (controller, _) = lockedActivity(LaunchIntents.app(context))
        controller.get().onAppLockResult(true)
        controller.newIntent(LaunchIntents.session(context, "route", "now"))
        assertEquals("now", pending(controller))
    }

    // A share from ShareActivity is held behind the lock like a tap.

    private fun shared() = LaunchIntents.share(context, ShareRequest(SharedContent("Shared"), ShareTarget.Pick))

    private fun saved(controller: org.robolectric.android.controller.ActivityController<MainActivity>) =
        Bundle().also { controller.saveInstanceState(it) }

    @Test fun aShareWhileLockedIsHeldUntilUnlock() {
        val (controller, prompt) = lockedActivity(shared())
        assertEquals("the lock asks by itself", 1, prompt.shown)
        assertNull("nothing reaches the app behind the lock", saved(controller).share("pendingShare"))
        assertEquals("Shared", saved(controller).share("heldShare")?.content?.text)
        controller.get().onAppLockResult(true)
        assertEquals("Shared", saved(controller).share("pendingShare")?.content?.text)
        assertNull(saved(controller).share("heldShare"))
    }

    @Test fun aShareWhileLockedIsDroppedOnCancel() {
        val (controller, _) = lockedActivity(shared())
        controller.get().onAppLockResult(false)
        assertTrue(app.appLock.locked.value)
        assertNull(saved(controller).share("heldShare"))
        assertNull(saved(controller).share("pendingShare"))
        // A later unlock brings nothing back.
        controller.get().onAppLockResult(true)
        assertNull(saved(controller).share("pendingShare"))
    }

    @Test fun aNewerLaunchWhileLockedReplacesAHeldShare() {
        val (controller, _) = lockedActivity(shared())
        controller.pause()
        controller.newIntent(LaunchIntents.session(context, "route", "tapped"))
        controller.resume()
        controller.get().onAppLockResult(true)
        assertNull(saved(controller).share("pendingShare"))
        assertEquals("tapped", pending(controller))
    }

    // The settings row.

    @Test fun theSettingRowSwitchesTheLockAndOffersTheTimeoutOnceOn() {
        val store = store()
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalAppLockStore provides store) { AppLockSettingRow() } }
        }
        compose.onNodeWithTag("appLockSwitch").assertIsOff().assertIsEnabled()
        compose.onNodeWithTag("appLockTimeout-5").assertDoesNotExist()
        compose.onNodeWithTag("appLockSwitch").performClick()
        compose.onNodeWithTag("appLockSwitch").assertIsOn()
        assertTrue(store.state.value.enabled)
        compose.onNodeWithTag("appLockTimeout-15").assertIsDisplayed().performClick()
        assertEquals(15, store.state.value.timeoutMinutes)
    }

    @Test fun withoutAScreenLockTheRowIsDisabled() {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(false)
        val store = store()
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalAppLockStore provides store) { AppLockSettingRow() } }
        }
        compose.onNodeWithTag("appLockSwitch").assertIsOff().assertIsNotEnabled()
    }

    @Test fun theGateReplacesTheAppWhileLocked() {
        var locked by mutableStateOf(true)
        var unlockTaps = 0
        compose.setContent {
            AppLockGate(locked, onUnlock = { unlockTaps++ }, colors = remoteColorScheme(dark = false)) {
                Text("secret chat", Modifier.testTag("behind"))
            }
        }
        compose.onNodeWithTag("appLockScreen").assertIsDisplayed()
        compose.onNodeWithTag("behind").assertDoesNotExist()
        compose.onNodeWithTag("appLockUnlock").performClick()
        assertEquals(1, unlockTaps)
        locked = false
        compose.onNodeWithTag("appLockScreen").assertDoesNotExist()
        compose.onNodeWithTag("behind").assertIsDisplayed()
    }

    @Test fun aDialogOrFieldOfTheAppIsGoneWhileLockedAndItsSavedStateComesBack() {
        var locked by mutableStateOf(false)
        compose.setContent {
            AppLockGate(locked, onUnlock = {}, colors = remoteColorScheme(dark = false)) {
                var draft by rememberSaveable { mutableStateOf("") }
                Column {
                    TextField(draft, { draft = it }, Modifier.testTag("composer"))
                    // A dialog is a window of its own, above anything drawn in the activity's window.
                    Dialog(onDismissRequest = {}) { Text("artifact", Modifier.testTag("dialog")) }
                }
            }
        }
        compose.onNodeWithTag("composer").performTextInput("draft")
        compose.onNodeWithTag("dialog").assertIsDisplayed()
        locked = true
        compose.onNodeWithTag("appLockScreen").assertIsDisplayed()
        compose.onNodeWithTag("dialog").assertDoesNotExist()
        // Nothing of the app is left that a hardware keyboard's Tab could move focus into.
        compose.onNodeWithTag("composer").assertDoesNotExist()
        compose.onAllNodes(isFocusable()).assertCountEquals(1)
        compose.onNodeWithTag("appLockUnlock").assert(isFocusable())
        locked = false
        compose.onNodeWithTag("composer").assertTextEquals("draft")
    }

    @Test fun theLockScreenIsAnnouncedWithAHeading() {
        compose.setContent { AppLockGate(true, onUnlock = {}, colors = remoteColorScheme(dark = false)) {} }
        val title = context.getString(R.string.remote_app_lock_locked_title)
        compose.onNodeWithTag("appLockScreen").assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title))
        compose.onNode(isHeading()).assertTextEquals(title)
    }
}
