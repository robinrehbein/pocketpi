package de.joinnoah.pi.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toPixelMap
import org.junit.Assert.assertTrue
import kotlin.math.abs
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
        var previewMessages = listOf(Wire.objectOf("id" to "cached", "role" to "assistant", "text" to "Cached message"))
        override fun peekCachedChat(selection: RemoteSelection): ChatPreview? =
            if (selection.sessionId == "live") ChatPreview(
                Wire.objectOf("id" to "live", "title" to "Live session", "origin" to "rpc"),
                previewMessages,
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
            ChatRenderingDiagnostics.update(ChatRenderingDiagnostics.Options())
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
            ChatRenderingDiagnostics.update(ChatRenderingDiagnostics.Options())
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
                // Close needs a status this connection confirmed, not a last-known row.
                sessionsFresh = true,
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
    fun chatLayoutCaptureIsOptInAndOpensAnExplicitShareSheet() {
        sessions()
        compose.onNodeWithText("Live session").performClick()
        compose.onNodeWithTag("conversationList").assertIsDisplayed()
        compose.onNodeWithTag("captureChatRender").assertDoesNotExist()
        compose.runOnIdle { ChatRenderingDiagnostics.update(ChatRenderingDiagnostics.Options(recording = true)) }
        compose.waitForIdle()
        compose.onNodeWithTag("captureChatRender").assertIsDisplayed().performClick()
        compose.onNodeWithTag("shareRenderSnapshot").assertIsDisplayed()
        compose.onNodeWithTag("copyRenderSnapshot").assertIsDisplayed()
    }

    @Test
    fun cachedUserBubbleHasOnlyOneDrawingAfterLiveCapabilitiesArrive() {
        sessions()
        val prompt = Wire.objectOf(
            "id" to "cached-user", "role" to "user",
            "text" to "kann ich die Session hier also ohne Probleme schließen",
            "timestamp" to 1_793_779_460_000L,
        )
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle {
            repository.previewMessages = listOf(prompt)
            base.openGate = gate
        }
        compose.onNodeWithText("Live session").performClick()
        compose.waitForIdle()
        val tag = "messageBubble-cached-user"
        compose.onNodeWithTag(tag).assertIsDisplayed()
        val previewBounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("forkControl").assertDoesNotExist()
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            base.state.value = base.state.value.copy(
                loading = false, messages = listOf(prompt),
                capabilities = setOf(SESSION_FORK_CAPABILITY),
                session = Wire.objectOf("id" to "live", "title" to "Live session", "origin" to "rpc"),
            )
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag(tag).assertCountEquals(1)
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val fork = compose.onNodeWithTag("forkControl").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot()
        val origin = root.fetchSemanticsNode().boundsInRoot
        val pixels = root.captureToImage().toPixelMap()
        val pink = remoteColorScheme(false).primaryContainer
        var currentPixels = 0
        for (y in (bounds.top - origin.top).toInt().coerceAtLeast(0) until (bounds.bottom - origin.top).toInt().coerceAtMost(pixels.height)) {
            for (x in (fork.right - origin.left).toInt().coerceAtLeast(0) until (bounds.right - origin.left).toInt().coerceAtMost(pixels.width)) {
                val pixel = pixels[x, y]
                if (abs(pixel.red - pink.red) < .02f && abs(pixel.green - pink.green) < .02f && abs(pixel.blue - pink.blue) < .02f) currentPixels++
            }
        }
        assertTrue("Fixture must contain the expected current bubble fill", currentPixels > 100)
        var ghostPixels = 0
        val scanTop = (minOf(previewBounds.top, bounds.top) - origin.top).toInt().coerceAtLeast(0)
        val scanBottom = (maxOf(previewBounds.bottom, bounds.bottom) - origin.top).toInt().coerceAtMost(pixels.height)
        val scanRight = (maxOf(previewBounds.right, bounds.right) - origin.left).toInt().coerceAtMost(pixels.width)
        for (y in scanTop until scanBottom) {
            for (x in 0 until scanRight) {
                val rootX = x + origin.left
                val rootY = y + origin.top
                if (rootX >= fork.right - 4 && rootY >= bounds.top - 4 && rootY <= bounds.bottom + 4) continue
                val pixel = pixels[x, y]
                if (abs(pixel.red - pink.red) < .02f && abs(pixel.green - pink.green) < .02f && abs(pixel.blue - pink.blue) < .02f) ghostPixels++
            }
        }
        assertTrue("Old wide user bubble must not remain behind live fork layout: $ghostPixels pixels", ghostPixels < 4)
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
