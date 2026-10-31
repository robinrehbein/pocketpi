package de.joinnoah.pi.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.util.UUID

class LoadingInteractionUiTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private lateinit var repository: UiRemoteRepository
    private val storageId = "loading-interaction-${UUID.randomUUID()}"

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            NavigationTestActivity.contentFactory = { activity ->
                val fresh = UiRemoteRepository(DraftStore(activity, "$storageId.enc", storageId))
                repository = fresh
                activity.flush = { fresh.flush() }
                val settings = UiSettingsRepository()
                val content: @Composable () -> Unit = {
                    RemoteApp(fresh, settings, false, {}, activity.notification, {
                        if (activity.notification == it) activity.notification = null
                    })
                }
                content
            }
        }

        override fun after() {
            NavigationTestActivity.contentFactory = { {} }
            compose.activity.noBackupFilesDir.resolve("$storageId.enc").delete()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {
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
    fun knownSessionCanOpenWhileLoadingButCreationIsDisabled() {
        sessions()
        compose.runOnIdle { repository.state.value = repository.state.value.copy(loading = true) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.remote_new_session))
            .assertIsNotEnabled()
        compose.onNodeWithText("Live session").performClick()
        compose.runOnIdle {
            assertEquals("live", repository.activations.last().first.sessionId)
        }
    }

    @Test
    fun composerAcceptsTextDuringLoadingWhileSendStaysDisabled() {
        sessions()
        compose.runOnIdle { repository.chatLoading = true }
        compose.onNodeWithText("Live session").performClick()
        compose.onNodeWithTag("composerField").assertIsEnabled().performTextInput("Typed while loading")
        compose.onNodeWithTag("composerField").assertTextContains("Typed while loading")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.remote_send))
            .assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals("Typed while loading", repository.state.value.draft)
            assertEquals(0, repository.prompts)
            repository.state.value = repository.state.value.copy(loading = false)
        }
        compose.onNodeWithTag("composerField").assertTextContains("Typed while loading")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.remote_send))
            .assertIsEnabled()
    }
}
