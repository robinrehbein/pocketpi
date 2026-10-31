package de.joinnoah.pi.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class LoadingPreviewSafetyUiTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private val storageId = "loading-preview-${UUID.randomUUID()}"
    private lateinit var base: UiRemoteRepository
    private lateinit var repository: PreviewRepository

    private class PreviewRepository(private val base: UiRemoteRepository) : RemoteRepository by base {
        val drafts = mutableMapOf<RemoteSelection, String>()
        override fun peekCachedChat(selection: RemoteSelection): ChatPreview? =
            if (selection.sessionId == "live") ChatPreview(
                Wire.objectOf("id" to "live", "title" to "Live session", "origin" to "rpc"),
                listOf(Wire.objectOf("id" to "cached", "role" to "assistant", "text" to "Cached message")),
                drafts[selection].orEmpty(),
            ) else null

        override fun peekDraftText(selection: RemoteSelection): String? =
            if (selection.sessionId == "live") drafts[selection].orEmpty() else base.peekDraftText(selection)

        override fun draft(selection: RemoteSelection, text: String) {
            drafts[selection] = text
            if (selection == state.value.selection) base.draft(text)
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            NavigationTestActivity.contentFactory = { activity ->
                base = UiRemoteRepository(DraftStore(activity, "$storageId.enc", storageId))
                repository = PreviewRepository(base)
                activity.flush = { base.flush() }
                val settings = UiSettingsRepository()
                val content: @Composable () -> Unit = {
                    RemoteApp(repository, settings, false, {}, activity.notification, {
                        if (activity.notification == it) activity.notification = null
                    })
                }
                content
            }
        }

        override fun after() {
            NavigationTestActivity.contentFactory = { {} }
            InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir.resolve("$storageId.enc").delete()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(storageId)
            }
        }
    }).around(compose)

    private fun sessions() {
        compose.onNodeWithText("Test computer").performClick()
        compose.onNodeWithText("Project one").performClick()
    }

    @Test
    fun loadingRowOffersOpenButNotRenameCloseForkOrNewSession() {
        sessions()
        compose.runOnIdle {
            base.state.value = base.state.value.copy(
                capabilities = setOf(RENAME_CAPABILITY, SESSION_CLOSE_CAPABILITY, SESSION_FORK_CAPABILITY),
                sessions = listOf(Wire.objectOf(
                    "id" to "live", "title" to "Live session", "origin" to "rpc", "status" to "idle",
                )),
            )
        }
        // Establish that this fixture really offers the destructive actions when ready.
        compose.onNodeWithText("Live session").performTouchInput { longClick() }
        compose.onNodeWithTag("menu_session_rename").assertExists()
        compose.onNodeWithTag("menu_session_close").assertExists()
        compose.onNodeWithTag("menu_session_fork_latest").assertExists()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitForIdle()

        compose.runOnIdle { base.state.value = base.state.value.copy(loading = true) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.remote_new_session))
            .assertIsNotEnabled()
        compose.onNodeWithText("Live session").performTouchInput { longClick() }
        compose.onNodeWithTag("menu_session_rename").assertDoesNotExist()
        compose.onNodeWithTag("menu_session_close").assertDoesNotExist()
        compose.onNodeWithTag("menu_session_fork_latest").assertDoesNotExist()
        compose.onNodeWithTag("menu_session_open").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("live", base.activations.last().first.sessionId) }
    }

    @Test
    fun cachedDestinationPreviewAcceptsTextWithoutSendingOrWritingTheOldSelection() {
        sessions()
        val destination = RemoteSelection("host", "project", "live")
        compose.runOnIdle {
            repository.drafts[destination] = "Stored B"
            base.state.value = base.state.value.copy(draft = "Old selection draft")
            base.openGate = CompletableDeferred()
        }
        compose.onNodeWithText("Live session").performClick()
        compose.onNodeWithText("Cached message").assertIsDisplayed()
        compose.onNodeWithTag("composerField").assertIsEnabled().assertTextContains("Stored B")
            .performTextReplacement("New B text")
        compose.onNodeWithTag("composerField").assertTextContains("New B text")
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS + 100)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.remote_send))
            .assertIsNotEnabled()
        compose.runOnIdle {
            assertNotEquals(destination, base.state.value.selection)
            assertEquals("Old selection draft", base.state.value.draft)
            assertEquals("New B text", repository.drafts[destination])
            assertEquals(0, base.prompts)
        }
    }
}
