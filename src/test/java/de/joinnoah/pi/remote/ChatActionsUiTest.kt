package de.joinnoah.pi.remote

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** The chat header's More actions, their snackbar feedback and the status mark, inside the whole app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ChatActionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = NavigationFakeRepository()
    private val settings = FakeSettingsRepository()

    private val session = buildJsonObject {
        put("id", "s1")
        put("title", "First session")
        put("status", "idle")
        put("origin", "rpc")
    }

    init {
        val project = buildJsonObject {
            put("id", "project")
            put("name", "noah-monorepo")
        }
        repository.state.value =
            RemoteState(
                connected = true,
                connection = R.string.remote_connected,
                status = "idle",
                projects = listOf(project),
                project = project,
                sessions = listOf(session),
                session = session,
                capabilities = setOf(SESSION_TREE_CAPABILITY, ARTIFACTS_CAPABILITY, EXPORT_CAPABILITY, RELOAD_CAPABILITY),
                capabilitiesKnown = true,
            )
        val target = RemoteSelection("host", "project", "s1")
        repository.notificationLookup = {
            repository.state.value = repository.state.value.copy(selection = target)
            target
        }
    }

    private fun render() {
        compose.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    keyboard = Configuration.KEYBOARD_NOKEYS
                    hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_YES
                }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme {
                    RemoteNavigation(repository, settings, false, {}, RemoteNotification("host", "s1", 1))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun label(id: Int) = compose.activity.getString(id)

    private fun runFromMenu(action: ChatAction) {
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chatActionMenu_${action.name.lowercase()}").performClick()
        compose.waitForIdle()
    }

    private fun snackbarShows(text: String) =
        compose.onNode(hasText(text).and(hasAnyAncestor(hasTestTag("chatSnackbar"))), useUnmergedTree = true)

    @Test
    fun treeArtifactsExportAndReloadAreInTheMoreActions() {
        render()
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.waitForIdle()
        for (action in listOf(ChatAction.TREE, ChatAction.ARTIFACTS, ChatAction.EXPORT, ChatAction.RELOAD))
            compose.onNode(
                hasText(label(action.label())).and(hasAnyAncestor(hasTestTag("chatActionMenu_${action.name.lowercase()}"))),
                useUnmergedTree = true,
            ).assertIsDisplayed()
    }

    @Test
    fun reloadFeedbackAppearsAsSnackbarsAndItsUseIsRecorded() {
        val answer = CompletableDeferred<ReloadResult>()
        repository.reload = { answer.await() }
        render()
        runFromMenu(ChatAction.RELOAD)
        assertEquals(listOf("s1"), repository.reloads)
        snackbarShows(label(R.string.remote_reload_running)).assertIsDisplayed()
        compose.runOnIdle { answer.complete(ReloadResult.Done) }
        compose.waitForIdle()
        snackbarShows(label(R.string.remote_reload_done)).assertIsDisplayed()
        snackbarShows(label(R.string.remote_reload_running)).assertDoesNotExist()
        assertNull(ShadowToast.getTextOfLatestToast())
        // The palette still ranks its actions by use.
        assertEquals(listOf(ChatAction.RELOAD), settings.recordedActions)
    }

    @Test
    fun aFailedReloadSaysWhyInASnackbar() {
        repository.reload = { ReloadResult.Failed(ReloadFailure.Failed("extension crashed")) }
        render()
        runFromMenu(ChatAction.RELOAD)
        snackbarShows(compose.activity.getString(R.string.remote_reload_failed_detail, "extension crashed")).assertIsDisplayed()
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun theArtifactsHintAppearsAsASnackbar() {
        // A host that answered without artifacts keeps the entry, which explains itself.
        repository.state.value =
            repository.state.value.copy(capabilities = repository.state.value.capabilities - ARTIFACTS_CAPABILITY)
        render()
        runFromMenu(ChatAction.ARTIFACTS)
        snackbarShows(label(R.string.remote_artifacts_host_outdated)).assertIsDisplayed()
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun exportReportsInTheChatWithoutAToast() {
        render()
        runFromMenu(ChatAction.EXPORT)
        // The fake host cannot export: the failure shows in the chat's export status.
        compose.onNode(
            hasText(label(R.string.remote_export_unsupported)).and(hasAnyAncestor(hasTestTag("exportStatus"))),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(listOf(ChatAction.EXPORT), settings.recordedActions)
    }

    @Test
    fun headerStatusHasAShapePerState() {
        render()
        val mark = compose.onNodeWithTag("chatStatusDot")
        fun expect(shape: ChatStatusShape, text: Int) {
            compose.waitForIdle()
            mark.assertContentDescriptionEquals(label(text))
                .assert(SemanticsMatcher.expectValue(ChatStatusShapeKey, shape))
        }
        expect(ChatStatusShape.READY, R.string.remote_status_idle)
        compose.runOnIdle { repository.state.value = repository.state.value.copy(status = "running") }
        expect(ChatStatusShape.RUNNING, R.string.remote_status_running)
        compose.runOnIdle { repository.state.value = repository.state.value.copy(status = "waiting") }
        expect(ChatStatusShape.WAITING, R.string.remote_status_waiting)
        compose.runOnIdle { repository.state.value = repository.state.value.copy(status = "unrecognized") }
        expect(ChatStatusShape.UNKNOWN, R.string.remote_status_unknown)
        compose.runOnIdle { repository.state.value = repository.state.value.copy(connected = false) }
        expect(ChatStatusShape.OFFLINE, R.string.remote_status_offline)
        assertEquals(ChatStatusShape.entries.size, ChatStatusShape.entries.toSet().size)
    }
}
