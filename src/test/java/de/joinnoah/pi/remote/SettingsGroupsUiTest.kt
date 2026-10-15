package de.joinnoah.pi.remote

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings in groups by what people change: General, Chat, Session list, Devices, Privacy, About. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SettingsGroupsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = NavigationFakeRepository()
    private val settings = FakeSettingsRepository()
    private val studio = PairedHost("studio", "wss://relay.test", "device-studio", "secret", "Studio")
    private val laptop = PairedHost("laptop", "wss://relay.test", "device-laptop", "secret", "Laptop")

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun label(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    @Before
    fun twoMacsAndNoLastOne() {
        // Two Macs and no last one: the app starts on the hosts page.
        context().getSharedPreferences("last_route", Context.MODE_PRIVATE).edit().clear().commit()
        repository.state.value = RemoteState(hosts = listOf(studio, laptop), hostsLoaded = true)
        val lock = AppLockStore(context())
        compose.setContent {
            CompositionLocalProvider(LocalAppLockStore provides lock) { RemoteApp(repository, settings, false, {}) }
        }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription(label(R.string.remote_settings)).onFirst().performClick()
        compose.waitForIdle()
    }

    /** The settings page's list: the lazy list that knows the General heading's key. */
    private val settingsList =
        SemanticsMatcher("the settings list") { node ->
            SemanticsProperties.IndexForKey in node.config &&
                node.config[SemanticsProperties.IndexForKey](SETTINGS_GROUP_KEY_PREFIX + "general") >= 0
        }

    private fun scrollTo(tag: String) {
        compose.onNode(settingsList).performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun theGroupsAndTheirCardsComeInOrder() {
        val indexForKey = compose.onNode(settingsList).fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
        val order =
            listOf(
                SETTINGS_GROUP_KEY_PREFIX + "general", "settings-notifications", "settings-appearance",
                SETTINGS_GROUP_KEY_PREFIX + "chat", "settings-thinking", "settings-predictions",
                SETTINGS_GROUP_KEY_PREFIX + "session-list", "settings-swipe", "settings-offline",
                SETTINGS_GROUP_KEY_PREFIX + "devices", "settings-devices",
                SETTINGS_GROUP_KEY_PREFIX + "privacy", "settings-app-lock",
                SETTINGS_GROUP_KEY_PREFIX + "about", "settings-about", "settings-diagnostics",
            )
        val indices = order.map { key -> indexForKey(key).also { assertTrue("$key is missing", it >= 0) } }
        assertEquals(order.toString(), indices.sorted(), indices)
        // Group names are headings, so TalkBack can jump between them.
        compose.onNodeWithTag(SETTINGS_GROUP_KEY_PREFIX + "general")
            .assert(hasText(label(R.string.remote_settings_general)))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun theLiveRunSwitchSitsWithTheNotifications() {
        scrollTo("liveRunSwitch")
        compose.onNode(hasTestTag("liveRunSwitch") and hasAnyAncestor(hasTestTag("settingsNotifications"))).assertExists()
    }

    @Test
    fun theAppLockHasThePrivacyGroup() {
        scrollTo("appLockSwitch")
        compose.onNode(hasTestTag("appLockSwitch") and hasAnyAncestor(hasTestTag("settingsAppLock"))).assertExists()
    }

    @Test
    fun aboutShowsTheVersionThePrivacyPolicyAndDiagnostics() {
        scrollTo("settingsVersion")
        val version = compose.activity.getString(R.string.remote_settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        compose.onNodeWithTag("settingsVersion").assert(hasText(version))
        compose.onNode(hasTestTag("openPrivacyPolicy") and hasAnyAncestor(hasTestTag("settingsAbout"))).assertExists()
        scrollTo("openDiagnostics")
        compose.onNode(hasTestTag("openDiagnostics") and hasAnyAncestor(hasTestTag("settingsDiagnostics"))).assertExists()
    }

    @Test
    fun hideOfflineSessionsMirrorsTheSessionFilter() {
        scrollTo("hideOfflineSwitch")
        compose.onNodeWithTag("hideOfflineSwitch").performClick()
        compose.runOnIdle { assertTrue(settings.state.value.hideOfflineSessions) }
    }

    @Test
    fun devicesListThePairedMacsAndExplainTheWatch() {
        scrollTo("settingsWatch")
        compose.onNodeWithTag("settingsWatch").assert(hasText(label(R.string.remote_settings_watch_help, label(R.string.wear_pair))))
        val paired = compose.activity.resources.getQuantityString(R.plurals.remote_settings_paired_count, 2, 2)
        compose.onNodeWithTag("settingsPairedMacs").assert(hasText(paired)).performClick()
        compose.waitForIdle()
        // The hosts list replaces Settings.
        compose.onNode(settingsList).assertDoesNotExist()
        compose.onNodeWithText("Studio").assertExists()
    }
}
