package de.joinnoah.pi.remote

import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class RemoteNavigationUiTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private lateinit var repository: UiRemoteRepository
    private lateinit var uiSettings: UiSettingsRepository
    private val storageId = "navigation-test-${UUID.randomUUID()}"
    private var storageDirectory: File? = null

    private fun isolatedDrafts(activity: NavigationTestActivity): DraftStore {
        val directory = File(activity.cacheDir, storageId).apply { mkdirs() }
        storageDirectory = directory
        val context =
            object : ContextWrapper(activity) {
                override fun getNoBackupFilesDir() = directory
            }
        return DraftStore(context, "drafts.enc", storageId)
    }

    private var repositoryFactory: (NavigationTestActivity) -> UiRemoteRepository = {
        UiRemoteRepository(isolatedDrafts(it))
    }
    @get:Rule
    val rules: RuleChain =
        RuleChain.outerRule(
                object : ExternalResource() {
                    override fun before() {
                        NavigationTestActivity.contentFactory = { activity ->
                            val fresh = repositoryFactory(activity)
                            repository = fresh
                            activity.flush = { fresh.flush() }
                            val settings = UiSettingsRepository().also { uiSettings = it }
                            val content: @Composable () -> Unit = {
                                RemoteApp(
                                    fresh,
                                    settings,
                                    false,
                                    {},
                                    activity.notification,
                                    {
                                        if (activity.notification == it)
                                            activity.notification = null
                                    },
                                )
                            }
                            content
                        }
                    }

                    override fun after() {
                        NavigationTestActivity.contentFactory = { {} }
                        storageDirectory?.deleteRecursively()
                        KeyStore.getInstance("AndroidKeyStore").apply {
                            load(null)
                            deleteEntry(storageId)
                        }
                    }
                }
            )
            .around(compose)

    private fun label(id: Int) = compose.activity.getString(id)

    private fun click(id: Int) {
        compose.onNodeWithText(label(id)).performClick()
    }

    private fun back() {
        compose.onNodeWithContentDescription(label(R.string.remote_back)).performClick()
    }

    private fun settings() {
        compose.onNodeWithContentDescription(label(R.string.remote_settings)).performClick()
    }

    private fun openChat(title: String = "Live session") {
        compose.onNodeWithText("Test computer").performClick()
        compose.onNodeWithText("Project one").performClick()
        compose.onNodeWithText(title).performClick()
        compose.waitForIdle()
    }

    @Test
    fun encryptedDraftStoreRestoresOrderedFollowUps() {
        val store = isolatedDrafts(compose.activity)
        val key = DraftKey("host", "live")
        val entries = listOf(
            PendingFollowUp(Wire.random(), "first"),
            PendingFollowUp(Wire.random(), "second", "cancelled"),
        )
        store.save(mapOf(key to StoredDraft(followUps = entries)))
        assertEquals(entries, isolatedDrafts(compose.activity).load().getValue(key).followUps)
    }

    @Test
    fun followUpWarningsCanBeDismissedButPendingCannot() {
        openChat()
        val pending = PendingFollowUp(Wire.random(), "still queued")
        val uncertain = PendingFollowUp(Wire.random(), "check delivery", "uncertain")
        compose.runOnUiThread {
            repository.state.value = repository.state.value.copy(followUps = listOf(pending, uncertain))
        }
        compose.onNodeWithTag("dismissFollowUp-${pending.requestId}").assertDoesNotExist()
        compose.onNodeWithContentDescription(
            compose.activity.getString(R.string.remote_follow_up_dismiss, uncertain.text)
        ).assertIsDisplayed().performClick()
        compose.onNodeWithTag("dismissFollowUp-${uncertain.requestId}").assertDoesNotExist()
        assertEquals(listOf(pending), repository.state.value.followUps)
    }

    @Test
    fun longFollowUpJournalKeepsStopVisible() {
        openChat()
        compose.runOnUiThread {
            repository.state.value = repository.state.value.copy(
                status = "running",
                followUps = (1..64).map { PendingFollowUp(Wire.random(), "Queued $it") },
            )
        }
        compose.onNodeWithTag("followUpJournal").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.remote_stop)).assertIsDisplayed()
    }

    @Test
    fun runningChatOffersFollowUpAlongsideStopOnlyWithHostSupport() {
        openChat()
        compose.runOnUiThread {
            repository.state.value = repository.state.value.copy(
                status = "running", capabilities = setOf(FOLLOW_UP_CAPABILITY), draft = "Next",
                session = Wire.objectOf("id" to "live", "title" to "Live session", "origin" to "rpc"),
            )
        }
        compose.onNodeWithTag("followUp").assertIsDisplayed().performClick()
        assertEquals(1, repository.followUps)
        compose.onNodeWithTag("stopRun").performClick()
        assertEquals(1, repository.aborts)
        compose.runOnUiThread {
            repository.state.value = repository.state.value.copy(capabilities = emptySet())
        }
        compose.onNodeWithTag("followUp").assertDoesNotExist()
        compose.runOnUiThread {
            repository.state.value = repository.state.value.copy(
                capabilities = setOf(FOLLOW_UP_CAPABILITY),
                session = Wire.objectOf("id" to "live", "title" to "Live session", "origin" to "tui"),
            )
        }
        compose.onNodeWithTag("followUp").assertDoesNotExist()
        compose.onNodeWithTag("tuiInfo").assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.remote_tui_info_body)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_tui_info_close)).performClick()
        compose.onNodeWithContentDescription(label(R.string.remote_stop)).assertIsDisplayed()
    }

    @Test
    fun runningChatSwipesBetweenSteerAndFollowUpWithoutStopping() {
        openChat()
        compose.runOnUiThread {
            repository.state.value = repository.state.value.copy(
                status = "running",
                capabilities = setOf(STEER_CAPABILITY, FOLLOW_UP_CAPABILITY),
                draft = "Please continue",
                session = Wire.objectOf("id" to "live", "title" to "Live session", "origin" to "rpc"),
            )
        }
        compose.onNodeWithTag("steer").assertIsDisplayed()
        compose.onNodeWithTag("steer").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("followUp").assertIsDisplayed().performClick()
        assertEquals(1, repository.followUps)
        assertEquals(0, repository.aborts)
        compose.onNodeWithTag("stopRun").assertIsDisplayed()
    }

    private fun longSessions() =
        (1..40).map { index ->
            Wire.objectOf(
                "id" to "scroll-session-$index",
                "title" to "Scroll session %02d".format(index),
                "status" to "idle",
                "origin" to "scroll-fixture",
            )
        }

    private fun longConversation() =
        (1..40).map { index ->
            Wire.objectOf(
                "id" to "scroll-message-$index",
                "role" to "assistant",
                "text" to "Scroll message %02d".format(index),
            )
        }

    private fun swipeUp(
        start: androidx.compose.ui.geometry.Offset,
        end: androidx.compose.ui.geometry.Offset =
            androidx.compose.ui.geometry.Offset(
                start.x,
                start.y - 360 * compose.activity.resources.displayMetrics.density,
            ),
    ) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(
            "Swipe from $start to $end must stay inside the display",
            device.swipe(
                start.x.toInt(),
                start.y.toInt(),
                start.x.toInt(),
                end.y.toInt(),
                30,
            )
        )
        device.waitForIdle()
        compose.waitForIdle()
    }

    private fun quoteMessage(text: String) {
        val action = compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and
                hasAnyDescendant(hasText(text)),
            useUnmergedTree = true,
        ).fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == label(R.string.remote_quote) }
        compose.runOnIdle { assertTrue(action.action()) }
    }

    private fun showEarlyConversationMessages() {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToIndex(3)
        compose.onNodeWithText("Scroll message 06").assertIsDisplayed()
    }

    private fun assertMessageMovedUp(before: androidx.compose.ui.geometry.Rect) {
        val after = compose.onNodeWithText("Scroll message 06").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Vertical drag left message 06 at ${before.top} to ${before.bottom}",
            after.top < before.top,
        )
    }

    private fun conversationViewport() =
        compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().maxBy {
            it.boundsInRoot.height
        }.boundsInRoot

    private fun scrollFromContentGap() {
        val viewport = conversationViewport()
        val density = compose.activity.resources.displayMetrics.density
        val composerTop = compose.onNodeWithTag("floatingComposer")
            .fetchSemanticsNode().boundsInRoot.top
        val visibleBottom = minOf(composerTop, viewport.bottom)
        val visibleHeight = visibleBottom - viewport.top
        assertTrue("No visible list area above the composer", visibleHeight > 48 * density)
        val start =
            androidx.compose.ui.geometry.Offset(
                viewport.center.x,
                visibleBottom - 16 * density,
            )
        val end =
            androidx.compose.ui.geometry.Offset(
                viewport.center.x,
                start.y - minOf(48 * density, visibleHeight - 32 * density),
            )
        swipeUp(start, end)
    }

    private fun latestMessageIsAboveComposer(): Boolean {
        val viewport = conversationViewport()
        val composer = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        return compose.onAllNodesWithText("Scroll message 40").fetchSemanticsNodes().any {
            it.boundsInRoot.width > 0f &&
                it.boundsInRoot.height > 0f &&
                it.boundsInRoot.top >= viewport.top &&
                it.boundsInRoot.bottom <= composer.top
        }
    }

    private fun scrollUntilLatestMessageIsAboveComposer() {
        repeat(60) {
            if (latestMessageIsAboveComposer() &&
                compose.onNodeWithText("Scroll message 40").isDisplayed()) return
            scrollFromContentGap()
        }
    }

    private fun assertLatestMessageIsAboveComposer() {
        val message = compose.onNodeWithText("Scroll message 40").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "The newest message must be fully above the composer",
            message.bottom <= composer.top,
        )
    }

    private fun captureFixture(name: String) {
        compose.waitForIdle()
        // System bar icon transitions are animated outside Compose's test clock.
        Thread.sleep(500)
        val file = File(compose.activity.getExternalFilesDir(null), "fixture-$name.png")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.waitForIdle()
        assertTrue(device.takeScreenshot(file))
        device.executeShellCommand(
            "cp ${file.absolutePath} /sdcard/Download/pocketpi-fixture-$name.png"
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun systemBarIconsFollowResolvedThemeAndManualOverrides() {
        for (theme in listOf("light", "dark", "light", "system", "dark", "system")) {
            compose.runOnIdle { uiSettings.setTheme(theme) }
            compose.waitForIdle()
            compose.runOnIdle {
                val systemDark =
                    compose.activity.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                val dark =
                    when (theme) {
                        "dark" -> true
                        "light" -> false
                        else -> systemDark
                    }
                val controller =
                    androidx.core.view.WindowCompat.getInsetsController(
                        compose.activity.window,
                        compose.activity.window.decorView,
                    )
                assertEquals(
                    "Status bar icon contrast for $theme",
                    !dark,
                    controller.isAppearanceLightStatusBars,
                )
                assertEquals(
                    "Navigation bar icon contrast for $theme",
                    !dark,
                    controller.isAppearanceLightNavigationBars,
                )
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    assertEquals(
                        "Modern navigation bars remain transparent for $theme",
                        android.graphics.Color.TRANSPARENT,
                        compose.activity.window.navigationBarColor,
                    )
                } else {
                    assertEquals(
                        "Legacy navigation bar scrim follows $theme",
                        if (dark) android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B)
                        else android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF),
                        compose.activity.window.navigationBarColor,
                    )
                }
            }
        }
    }

    @Test
    fun chatHeaderUsesFloatingControlsAndAccessibleStatusDot() {
        openChat()
        val longTitle = "A very long session title that must fit beside all the header controls without wrapping"
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(
                    session = Wire.objectOf(
                        "id" to "live",
                        "title" to longTitle,
                        "origin" to "rpc",
                    ),
                    capabilities = setOf(RENAME_CAPABILITY),
                )
        }
        compose.onNodeWithText(longTitle).assertIsDisplayed()
        val dot = { compose.onNodeWithTag("chatStatusDot") }
        dot().assertIsDisplayed().assertContentDescriptionEquals(label(R.string.remote_status_idle))
        compose.onNodeWithText(label(R.string.remote_status_idle)).assertDoesNotExist()
        val back = compose.onNodeWithContentDescription(label(R.string.remote_back))
        val rename = compose.onNodeWithContentDescription(label(R.string.remote_rename_session))
        val refresh = compose.onNodeWithContentDescription(label(R.string.remote_refresh))
        val settings = compose.onNodeWithContentDescription(label(R.string.remote_settings))
        for (action in listOf(back, rename, refresh, settings)) {
            action.assertIsDisplayed().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        }
        val controls = listOf(back, rename, refresh, settings).map {
            it.fetchSemanticsNode().boundsInRoot
        }
        assertTrue(controls.zipWithNext().all { (left, right) -> left.right <= right.left })
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        val statusBarHeight = insets!!.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
        assertTrue(back.fetchSemanticsNode().boundsInRoot.top >= statusBarHeight)

        compose.runOnIdle { repository.state.value = repository.state.value.copy(status = "running") }
        dot().assertContentDescriptionEquals(label(R.string.remote_status_running))
        compose.runOnIdle { repository.state.value = repository.state.value.copy(status = "waiting") }
        dot().assertContentDescriptionEquals(label(R.string.remote_status_waiting))
        compose.runOnIdle { repository.state.value = repository.state.value.copy(status = "unrecognized") }
        dot().assertContentDescriptionEquals(label(R.string.remote_status_unknown))
        compose.runOnIdle { repository.state.value = repository.state.value.copy(connected = false) }
        dot().assertContentDescriptionEquals(label(R.string.remote_status_offline))
        rename.assertDoesNotExist()
        refresh.assertIsDisplayed()
        settings.performClick()
        compose.onNodeWithText(label(R.string.remote_appearance)).assertIsDisplayed()
    }

    @Test
    fun narrowChatHeaderKeepsTitleStatusAndActionsApart() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val originalSize = device.executeShellCommand("wm size")
        val overrideSize = Regex("Override size: (\\d+x\\d+)").find(originalSize)?.groupValues?.get(1)
        val metrics = compose.activity.resources.displayMetrics
        val width = (240 * metrics.density).toInt()
        try {
            device.executeShellCommand("wm size ${width}x${metrics.heightPixels}")
            device.waitForIdle()
            openChat()
            compose.runOnIdle {
                repository.state.value =
                    repository.state.value.copy(
                        session = Wire.objectOf(
                            "id" to "live",
                            "title" to "A long title on a narrow screen",
                            "origin" to "rpc",
                        ),
                        capabilities = setOf(RENAME_CAPABILITY),
                    )
            }
            val back = compose.onNodeWithContentDescription(label(R.string.remote_back))
            val title = compose.onNodeWithText("A long title on a narrow screen")
            val dot = compose.onNodeWithTag("chatStatusDot")
            val rename = compose.onNodeWithContentDescription(label(R.string.remote_rename_session))
            val refresh = compose.onNodeWithContentDescription(label(R.string.remote_refresh))
            val settings = compose.onNodeWithContentDescription(label(R.string.remote_settings))
            for (action in listOf(back, rename, refresh, settings)) {
                action.assertIsDisplayed().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            }
            title.assertIsDisplayed()
            dot.assertIsDisplayed()
            val backBounds = back.fetchSemanticsNode().boundsInRoot
            val titleBounds = title.fetchSemanticsNode().boundsInRoot
            val dotBounds = dot.fetchSemanticsNode().boundsInRoot
            val renameBounds = rename.fetchSemanticsNode().boundsInRoot
            val refreshBounds = refresh.fetchSemanticsNode().boundsInRoot
            val settingsBounds = settings.fetchSemanticsNode().boundsInRoot
            assertTrue("Title has less than 48dp: $titleBounds", titleBounds.width >= 48 * metrics.density)
            assertTrue(backBounds.right <= dotBounds.left)
            assertTrue(dotBounds.right <= titleBounds.left)
            assertTrue("Actions should sit below the title on a narrow screen", titleBounds.bottom <= settingsBounds.top)
            assertTrue(refreshBounds.top >= backBounds.bottom)
            assertTrue(renameBounds.top >= backBounds.bottom)
            assertTrue(renameBounds.right <= refreshBounds.left)
            assertTrue(refreshBounds.right <= settingsBounds.left)
            dot.assertContentDescriptionEquals(label(R.string.remote_status_idle))
            rename.performClick()
            compose.onNodeWithText(label(R.string.remote_cancel)).performClick()
            settings.performClick()
            compose.onNodeWithText(label(R.string.remote_appearance)).assertIsDisplayed()
        } finally {
            device.executeShellCommand(if (overrideSize == null) "wm size reset" else "wm size $overrideSize")
            device.waitForIdle()
        }
    }

    @Test
    fun configurationPickerAndSlashSuggestionsKeepComposerUsable() {
        openChat()
        compose.runOnIdle {
            val first = RemoteModel("provider", "first", "First model", setOf("text"))
            val second = RemoteModel("provider", "second", "Second model", setOf("text", "image"))
            repository.state.value =
                repository.state.value.copy(
                    capabilities = setOf(CONFIGURATION_CAPABILITY, COMMANDS_CAPABILITY),
                    configuration =
                        SessionConfiguration(
                            first,
                            "high",
                            listOf("high", "max"),
                            listOf(first, second),
                            false,
                        ),
                    commands = listOf(RemoteCommand("review", "Review the changes", "skill")),
                )
        }
        compose.onNodeWithText("First model").performClick()
        compose.onNodeWithText("Second model").performClick()
        compose.runOnIdle {
            assertEquals("second", repository.state.value.configuration?.model?.id)
        }
        compose.waitForIdle()
        compose.onNodeWithText("First model").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("/rev  file.kt")
        compose.onNodeWithText("/review").performClick()
        compose.runOnIdle { assertEquals("/review  file.kt", repository.state.value.draft) }
        compose
            .onNodeWithContentDescription(label(R.string.remote_send))
            .assertIsDisplayed()
            .assertIsEnabled()
        compose.onNode(hasSetTextAction()).performTextClearance()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitForIdle()
        quoteMessage("Message for live")
        compose.onNode(hasSetTextAction()).performTextInput("Follow up")
        compose
            .onNodeWithContentDescription(label(R.string.remote_remove_quote))
            .assertIsDisplayed()
            .performClick()
        compose
            .onNodeWithContentDescription(label(R.string.remote_send))
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(1, repository.prompts) }
    }

    @Test
    fun questionsReplaceTheComposerUntilAnswersCloseAndAbortRequiresConfirmation() {
        openChat()
        val attachment =
            LocalAttachment("kept", "kept.txt", "file", "text/plain", 4, "a".repeat(64))
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(
                    draft = "draft kept while answering",
                    quote = MessageQuote("message-live", "assistant", "quoted context"),
                    attachments = listOf(attachment),
                    questions =
                        listOf(
                            Wire.objectOf(
                                "id" to "questionnaire",
                                "kind" to "questionnaire",
                                "questions" to
                                    JsonArray(
                                        listOf(
                                            Wire.objectOf(
                                                "id" to "approach",
                                                "prompt" to "Choose an approach",
                                                "options" to
                                                    JsonArray(
                                                        listOf(
                                                            Wire.objectOf(
                                                                "value" to "safe",
                                                                "label" to "Safe choice",
                                                            )
                                                        )
                                                    ),
                                                "defaults" to JsonArray(emptyList()),
                                                "allowOther" to false,
                                                "multiSelect" to false,
                                                "required" to true,
                                            ),
                                            Wire.objectOf(
                                                "id" to "detail",
                                                "prompt" to "Add details",
                                                "options" to JsonArray(emptyList()),
                                                "defaults" to JsonArray(emptyList()),
                                                "allowOther" to true,
                                                "multiSelect" to false,
                                                "required" to true,
                                            ),
                                        )
                                    ),
                            )
                        ),
                )
        }

        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.remote_stop)).assertDoesNotExist()
        compose.onNodeWithText("Safe choice").performClick()
        compose.onNodeWithText("Safe choice").assertIsSelected()
        compose.onNodeWithText(label(R.string.remote_other)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Keep the current plan")
        compose.onNode(hasSetTextAction()).assertTextContains("Keep the current plan")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.remote_answer))
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(timeoutMillis = 10_000) { repository.answers.size == 1 }
        compose.runOnIdle {
            assertEquals("questionnaire", repository.answers.single().second.text("kind"))
            assertTrue(repository.answers.single().second.toString().contains("Keep the current plan"))
        }

        compose.onNodeWithTag("abortRun").performClick()
        compose.onNodeWithText(label(R.string.remote_abort_run_confirmation)).assertIsDisplayed()
        compose.onNode(hasText(label(R.string.remote_cancel)) and hasAnyAncestor(isDialog()))
            .performClick()
        compose.runOnIdle { assertEquals(0, repository.aborts) }
        compose.onNodeWithTag("abortRun").performClick()
        compose.onNodeWithTag("confirmAbortRun").performClick()
        compose.runOnIdle { assertEquals(1, repository.aborts) }

        compose.runOnIdle { repository.state.value = repository.state.value.copy(questions = emptyList()) }
        compose.onNode(hasSetTextAction()).assertTextContains("draft kept while answering")
        compose.onNodeWithContentDescription(label(R.string.remote_remove_quote)).assertIsDisplayed()
        compose.onNodeWithText("kept.txt").assertIsDisplayed()
    }

    @Test
    fun questionnaireOptionArtShowsProseAndCopyWithoutChangingSelection() {
        openChat()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(
                questions = listOf(
                    Wire.objectOf(
                        "id" to "diagram-question",
                        "kind" to "questionnaire",
                        "questions" to JsonArray(listOf(
                            Wire.objectOf(
                                "id" to "choice",
                                "prompt" to "Choose an approach",
                                "options" to JsonArray(listOf(
                                    Wire.objectOf(
                                        "value" to "drawn",
                                        "label" to "Drawn choice",
                                        "description" to "Before art\n```\n│ A │\n```\nAfter art",
                                    ),
                                    Wire.objectOf(
                                        "value" to "plain",
                                        "label" to "Plain choice",
                                        "description" to "Plain explanation",
                                    ),
                                )),
                                "defaults" to JsonArray(emptyList()),
                                "allowOther" to false,
                                "multiSelect" to false,
                                "required" to true,
                            )
                        )),
                    )
                )
            )
        }
        compose.onNodeWithText("Before art").assertIsDisplayed()
        compose.onNodeWithText("│ A │").assertIsDisplayed()
        compose.onNodeWithText("After art").assertIsDisplayed()
        compose.onNodeWithText("Plain explanation").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_copy_code)).performClick()
        compose.onNodeWithText("Drawn choice").assertIsNotSelected()
        compose.onNodeWithText("Drawn choice").performClick().assertIsSelected()
    }

    @Test
    fun capturesDesignRevisionFixtures() {
        captureFixture("hosts")
        compose.onNodeWithText("Test computer").performClick()
        captureFixture("projects")
        compose.onNodeWithText("Project one").performClick()
        captureFixture("sessions")
        compose
            .onNodeWithContentDescription(label(R.string.remote_design_filter_sessions))
            .performClick()
        captureFixture("sessions-filter")
        compose.onNodeWithText(label(R.string.remote_design_done)).performClick()
        compose.onNodeWithText("Live session").performClick()
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(
                    capabilities = setOf(COMMANDS_CAPABILITY),
                    commands = listOf(RemoteCommand("review", "Review changes", "skill")),
                )
        }
        compose.onNode(hasSetTextAction()).performTextInput("/rev")
        captureFixture("chat-slash")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitForIdle()
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(
                    questions =
                        listOf(
                            Wire.objectOf(
                                "id" to "questions",
                                "kind" to "questionnaire",
                                "questions" to
                                    JsonArray(
                                        listOf(
                                            Wire.objectOf(
                                                "id" to "choice",
                                                "label" to "Approach",
                                                "modelLabel" to "approach",
                                                "prompt" to "Choose an approach",
                                                "options" to
                                                    JsonArray(
                                                        listOf(
                                                            Wire.objectOf(
                                                                "value" to "safe",
                                                                "label" to "Safe choice",
                                                            )
                                                        )
                                                    ),
                                                "defaults" to JsonArray(emptyList()),
                                                "allowOther" to true,
                                                "multiSelect" to false,
                                                "required" to true,
                                            )
                                        )
                                    ),
                            )
                        )
                )
        }
        captureFixture("questionnaire")
        settings()
        captureFixture("settings")
    }

    @Test
    fun outstandingNativePickerSurvivesActivityRecreationAndImportsOnce() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val fixtureName = "pocketpi-picker-fixture.txt"
        val resolver = compose.activity.contentResolver
        val fixtureUri =
            checkNotNull(
                resolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fixtureName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(
                            android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS,
                        )
                    },
                )
            )
        checkNotNull(resolver.openOutputStream(fixtureUri)).use {
            it.write("fixture".toByteArray())
        }
        try {
            openChat()
            val oldActivity = compose.activity
            compose.runOnIdle {
                repository.state.value =
                    repository.state.value.copy(capabilities = setOf(ATTACHMENTS_CAPABILITY))
                compose.activity.clearViewModelsOnDestroy = true
            }
            compose
                .onNodeWithContentDescription(label(R.string.remote_add_attachment))
                .performClick()
            compose.onNodeWithText(label(R.string.remote_pick_files)).performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                device.currentPackageName.endsWith("documentsui")
            }
            val oldRepository = repository
            InstrumentationRegistry.getInstrumentation().runOnMainSync { oldActivity.recreate() }
            compose.waitUntil(timeoutMillis = 10_000) { repository !== oldRepository }
            val pickerPackage = device.currentPackageName
            val drawer =
                device.findObject(By.desc("Show roots"))
                    ?: device.findObject(By.desc("Open navigation drawer"))
            checkNotNull(drawer) { "System picker navigation drawer missing" }.click()
            device.waitForIdle()
            val downloads = device.findObject(UiSelector().text("Downloads"))
            assertTrue(downloads.waitForExists(5_000))
            downloads.click()
            device.waitForIdle()
            val fixture = device.findObject(UiSelector().text(fixtureName))
            if (!fixture.waitForExists(5_000)) {
                val hierarchy = File(compose.activity.getExternalFilesDir(null), "picker.xml")
                device.dumpWindowHierarchy(hierarchy)
                device.executeShellCommand(
                    "cp ${hierarchy.absolutePath} /sdcard/Download/pocketpi-picker-ui.xml"
                )
                fail("Fixture not visible in Downloads")
            }
            fixture.click()
            if (!device.wait(Until.gone(By.pkg(pickerPackage)), 1_000)) {
                val select =
                    device.findObject(By.text("OPEN"))
                        ?: device.findObject(By.text("SELECT"))
                        ?: device.findObject(By.text("Open"))
                checkNotNull(select) { "System picker confirmation missing" }.click()
            }
            compose.waitUntil(timeoutMillis = 10_000) { repository.imports.size == 1 }
            assertNotSame(oldRepository, repository)
            assertEquals(
                RemoteSelection("host", "project", "live"),
                repository.imports.single().first,
            )
            assertEquals(1, repository.imports.single().second.size)
            assertEquals(0, repository.prompts)
            compose.waitForIdle()
            assertEquals(1, repository.imports.size)
        } finally {
            resolver.delete(fixtureUri, null, null)
        }
    }

    @Test
    fun attachmentOnlyComposerAndSystemFilePickerRemainReachable() {
        openChat()
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(
                    capabilities = setOf(ATTACHMENTS_CAPABILITY),
                    attachments =
                        listOf(
                            LocalAttachment(
                                "AAAAAAAAAAAAAAAAAAAAAA",
                                "notes.md",
                                "file",
                                "text/markdown",
                                123,
                                "a".repeat(64),
                            )
                        ),
                )
        }
        compose.onNodeWithContentDescription(label(R.string.remote_send)).assertIsEnabled()
        compose
            .onNodeWithContentDescription(
                compose.activity.getString(R.string.remote_remove_attachment, "notes.md")
            )
            .performClick()
        compose.onNodeWithContentDescription(label(R.string.remote_voice_start)).assertIsEnabled()
        compose.onNodeWithContentDescription(label(R.string.remote_add_attachment)).performClick()
        compose.onNodeWithText(label(R.string.remote_pick_photos)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_pick_files)).performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.waitUntil(timeoutMillis = 10_000) {
            device.currentPackageName.endsWith("documentsui")
        }
        device.pressBack()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertExists()
    }

    @Test
    fun messageQuoteActionShowsAndRemovesComposerReference() {
        openChat()
        quoteMessage("Message for live")
        compose
            .onNodeWithContentDescription(label(R.string.remote_remove_quote))
            .assertIsDisplayed()
            .performClick()
        compose
            .onNodeWithContentDescription(label(R.string.remote_remove_quote))
            .assertDoesNotExist()
        val bounds = compose.onNodeWithText("Message for live").fetchSemanticsNode().boundsInRoot
        val distance = 96 * compose.activity.resources.displayMetrics.density
        compose.onRoot().performTouchInput {
            swipe(
                androidx.compose.ui.geometry.Offset(bounds.right - 4, bounds.center.y),
                androidx.compose.ui.geometry.Offset(bounds.right - 4 - distance, bounds.center.y),
                durationMillis = 250,
            )
        }
        compose
            .onNodeWithContentDescription(label(R.string.remote_remove_quote))
            .assertIsDisplayed()
    }

    @Test
    fun longSessionListScrollsFromFirstToLast() {
        compose.onNodeWithText("Test computer").performClick()
        compose.onNodeWithText("Project one").performClick()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(sessions = longSessions())
        }

        compose.onNodeWithText("Scroll session 01").assertIsDisplayed()
        val session = compose.onNodeWithText("Scroll session 06").fetchSemanticsNode().boundsInRoot
        swipeUp(session.center)
        repeat(20) {
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            swipeUp(androidx.compose.ui.geometry.Offset(root.center.x, root.height * 0.72f))
        }

        compose.onNodeWithText("Scroll session 40").assertIsDisplayed()
        captureFixture("scroll-sessions-last")
    }

    @Test
    fun longConversationScrollsOverBubbleAndPadding() {
        openChat()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(messages = longConversation())
        }

        showEarlyConversationMessages()
        val bubble = compose.onNodeWithText("Scroll message 06").fetchSemanticsNode().boundsInRoot
        swipeUp(
            bubble.center,
            androidx.compose.ui.geometry.Offset(
                bubble.center.x,
                bubble.center.y - 120 * compose.activity.resources.displayMetrics.density,
            ),
        )
        assertMessageMovedUp(bubble)
        scrollUntilLatestMessageIsAboveComposer()

        captureFixture("scroll-chat-after-padding")
        compose.onNodeWithText("Scroll message 40").assertIsDisplayed()
        compose
            .onNodeWithContentDescription(label(R.string.remote_remove_quote))
            .assertDoesNotExist()
        assertLatestMessageIsAboveComposer()
        captureFixture("scroll-chat-last")

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val previousIme =
            device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        require(previousIme in setOf("0", "1", "null"))
        try {
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            compose.onNode(hasSetTextAction()).performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            scrollUntilLatestMessageIsAboveComposer()
            assertLatestMessageIsAboveComposer()
            captureFixture("scroll-chat-ime")
        } finally {
            if (previousIme == "null")
                device.executeShellCommand("settings delete secure show_ime_with_hard_keyboard")
            else
                device.executeShellCommand(
                    "settings put secure show_ime_with_hard_keyboard $previousIme"
                )
        }
    }

    @Test
    fun longConversationFollowsViewportResizeUnlessScrolledUp() {
        openChat()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(messages = longConversation())
        }
        compose.waitForIdle()
        assertLatestMessageIsAboveComposer()

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val previousIme =
            device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        require(previousIme in setOf("0", "1", "null"))
        try {
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            compose.onNode(hasSetTextAction()).performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            compose.waitForIdle()
            assertLatestMessageIsAboveComposer()

            compose.onNode(hasSetTextAction())
                .performTextInput("Line one\nLine two\nLine three\nLine four")
            compose.waitForIdle()
            assertLatestMessageIsAboveComposer()

            val message = compose.onNodeWithText("Scroll message 40").fetchSemanticsNode().boundsInRoot
            val start = message.center
            swipeUp(
                start,
                androidx.compose.ui.geometry.Offset(
                    start.x,
                    start.y + 200 * compose.activity.resources.displayMetrics.density,
                ),
            )
            compose.onNodeWithContentDescription(label(R.string.remote_scroll_to_bottom))
                .assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextInput("\nLine five\nLine six")
            compose.waitForIdle()
            compose.onNodeWithContentDescription(label(R.string.remote_scroll_to_bottom))
                .assertIsDisplayed()
        } finally {
            if (previousIme == "null")
                device.executeShellCommand("settings delete secure show_ime_with_hard_keyboard")
            else
                device.executeShellCommand(
                    "settings put secure show_ime_with_hard_keyboard $previousIme"
                )
        }
    }

    @Test
    fun floatingComposerSideGapStillScrollsConversation() {
        openChat()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(messages = longConversation())
        }

        showEarlyConversationMessages()
        val before = compose.onNodeWithText("Scroll message 06").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        val start = androidx.compose.ui.geometry.Offset(4 * density, composer.center.y)
        swipeUp(start, androidx.compose.ui.geometry.Offset(start.x, start.y - 200 * density))
        assertMessageMovedUp(before)
    }

    @Test
    fun longConversationScrollsThroughContentGap() {
        openChat()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(messages = longConversation())
        }

        showEarlyConversationMessages()
        val message = compose.onNodeWithText("Scroll message 06").fetchSemanticsNode().boundsInRoot
        scrollFromContentGap()
        assertMessageMovedUp(message)
    }

    @Test
    fun centeredCreateActionUsesAccessibleLabelAndExistingSessionCreation() {
        compose.onNodeWithText("Test computer").performClick()
        compose.onNodeWithText("Project one").performClick()
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(
                    sessions =
                        repository.state.value.sessions.mapIndexed { index, session ->
                            JsonObject(
                                session +
                                    mapOf(
                                        "preview" to
                                            kotlinx.serialization.json.JsonPrimitive(
                                                if (index == 0) "Review the session list spacing."
                                                else "Earlier implementation notes."
                                            ),
                                        "updatedAt" to
                                            kotlinx.serialization.json.JsonPrimitive(
                                                System.currentTimeMillis() - index * 86400000L
                                            ),
                                    )
                            )
                        }
                )
        }
        for (theme in listOf("light", "dark")) {
            compose.runOnIdle { uiSettings.setTheme(theme) }
            captureFixture("sessions-$theme")
        }

        compose
            .onNodeWithContentDescription(label(R.string.remote_new_session))
            .assertWidthIsEqualTo(64.dp)
            .assertHeightIsEqualTo(64.dp)
            .performClick()
        compose.onNodeWithText("Message for created").assertIsDisplayed()
        compose.runOnIdle {
            uiSettings.setThinkingDisplay("text")
            repository.state.value =
                repository.state.value.copy(
                    capabilities =
                        setOf(
                            CONFIGURATION_CAPABILITY,
                            COMMANDS_CAPABILITY,
                            ATTACHMENTS_CAPABILITY,
                        ),
                    configuration =
                        SessionConfiguration(
                            RemoteModel(
                                "fixture",
                                "fixture-model",
                                "Fixture model",
                                setOf("text", "image"),
                            ),
                            "high",
                            listOf("high", "max"),
                            emptyList(),
                            false,
                        ),
                    draft = "Review this file with the earlier answer.",
                    quote =
                        MessageQuote(
                            "fixture-assistant",
                            "assistant",
                            "The rows now show availability.",
                            "Fixture model",
                        ),
                    attachments =
                        listOf(
                            LocalAttachment(
                                "AAAAAAAAAAAAAAAAAAAAAA",
                                "session-notes.md",
                                "file",
                                "text/markdown",
                                1248,
                                "a".repeat(64),
                            )
                        ),
                    messages =
                        JsonArray(
                                Wire.json
                                    .parseToJsonElement(
                                        """[
              {"id":"fixture-user","role":"user","text":"Check the session list styling.","state":"complete"},
              {"id":"fixture-assistant","role":"assistant","text":"The rows now show availability and the latest message.","state":"complete","model":{"provider":"fixture","id":"fixture-model","name":"Fixture model"},"parts":[{"type":"thinking","text":"Compare spacing, contrast and the create action."},{"type":"text","text":"The rows now show availability and the latest message."},{"type":"toolCall","id":"fixture-call","name":"read","arguments":"{\"path\":\"SessionList.kt\"}"}]},
              {"id":"fixture-output","role":"tool","text":"SessionList.kt: compact conversation rows","state":"complete","toolCallId":"fixture-call","toolName":"read"}
            ]"""
                                    )
                                    .jsonArray
                            )
                            .map { it.jsonObject },
                )
        }
        for (theme in listOf("light", "dark")) {
            compose.runOnIdle { uiSettings.setTheme(theme) }
            captureFixture("chat-$theme")
        }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val previousIme =
            device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        require(previousIme in setOf("0", "1", "null"))
        try {
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            compose.onNode(hasSetTextAction()).performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            captureFixture("chat-keyboard")
        } finally {
            if (previousIme == "null")
                device.executeShellCommand("settings delete secure show_ime_with_hard_keyboard")
            else
                device.executeShellCommand(
                    "settings put secure show_ime_with_hard_keyboard $previousIme"
                )
        }
    }

    @Test
    fun activeSessionsFilterBadgeScrollsWithTitleWhileHeaderActionsStayVisible() {
        compose.onNodeWithText("Test computer").performClick()
        compose.onNodeWithText("Project one").performClick()
        val filterLabel = label(R.string.remote_design_filter_sessions)
        val activeLabel = compose.activity.getString(R.string.remote_design_filter_active_count, 1)
        compose.onNodeWithTag("sessionsFilterBadge").assertDoesNotExist()
        compose.onNodeWithContentDescription(filterLabel).performClick()
        compose.onNodeWithContentDescription(label(R.string.remote_hide_offline_sessions)).performClick()
        compose.onNodeWithText(label(R.string.remote_design_done)).performClick()

        val activeAction = { compose.onNodeWithContentDescription("$filterLabel, $activeLabel") }
        val assertBadgeInsideViewport = {
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val badges = compose.onAllNodesWithTag("sessionsFilterBadge").fetchSemanticsNodes()
            assertTrue("Active filter must show a badge", badges.isNotEmpty())
            badges.forEach { node ->
                val badge = node.boundsInRoot
                assertTrue("Badge must have bounds: $badge", badge.width > 0f && badge.height > 0f)
                assertTrue("Badge extends past viewport: $badge, $root", badge.right <= root.right)
                assertTrue("Badge extends past viewport: $badge, $root", badge.left >= root.left)
            }
        }
        activeAction().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        assertTrue(
            "Active badge must show the count",
            compose.onAllNodesWithText("1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertBadgeInsideViewport()
        val headerActions = compose.onNodeWithTag("headerActionsPill")
        val headerTop = headerActions.fetchSemanticsNode().boundsInRoot.top
        assertEquals(
            "Filter must end where the header actions pill ends",
            headerActions.fetchSemanticsNode().boundsInRoot.right,
            compose.onNodeWithTag("sessionsFilterPill").fetchSemanticsNode().boundsInRoot.right,
            1f,
        )

        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(sessions = longSessions())
        }
        val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
        swipeUp(
            androidx.compose.ui.geometry.Offset(viewport.center.x, viewport.height * 0.75f),
            androidx.compose.ui.geometry.Offset(viewport.center.x, viewport.height * 0.3f),
        )
        headerActions.assertIsDisplayed()
        assertEquals(headerTop, headerActions.fetchSemanticsNode().boundsInRoot.top)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        activeAction().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        assertBadgeInsideViewport()
        activeAction().performClick()
        compose.onNodeWithContentDescription(label(R.string.remote_hide_offline_sessions)).performClick()
        compose.onNodeWithText(label(R.string.remote_design_done)).performClick()
        compose.onNodeWithTag("sessionsFilterBadge").assertDoesNotExist()
        compose.onNodeWithContentDescription(filterLabel).assertIsDisplayed()
    }

    @Test
    fun narrowSessionsHeaderKeepsPillsAndFilterInsideViewport() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val originalSize = device.executeShellCommand("wm size")
        val overrideSize = Regex("Override size: (\\d+x\\d+)").find(originalSize)?.groupValues?.get(1)
        val metrics = compose.activity.resources.displayMetrics
        val width = (320 * metrics.density).toInt()
        try {
            device.executeShellCommand("wm size ${width}x${metrics.heightPixels}")
            device.waitForIdle()
            compose.onNodeWithText("Test computer").performClick()
            compose.onNodeWithText("Project one").performClick()

            val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val back = compose.onNodeWithContentDescription(label(R.string.remote_back))
            val refresh = compose.onNodeWithContentDescription(label(R.string.remote_refresh))
            val settings = compose.onNodeWithContentDescription(label(R.string.remote_settings))
            val filter =
                compose.onNodeWithContentDescription(label(R.string.remote_design_filter_sessions))
            for (action in listOf(back, refresh, settings, filter)) action.assertIsDisplayed()
            val backBounds = back.fetchSemanticsNode().boundsInRoot
            val refreshBounds = refresh.fetchSemanticsNode().boundsInRoot
            val filterBounds = filter.fetchSemanticsNode().boundsInRoot
            assertTrue(backBounds.right <= refreshBounds.left)
            assertTrue(filterBounds.right <= viewport.right)
            assertTrue(filterBounds.left >= viewport.left)
            assertTrue(filterBounds.top >= backBounds.bottom)
        } finally {
            device.executeShellCommand(if (overrideSize == null) "wm size reset" else "wm size $overrideSize")
            device.waitForIdle()
        }
    }

    @Test
    fun offlineFilterDefaultsToShowingSessionsAndDoesNotActivateOrPrompt() {
        compose.onNodeWithText("Test computer").performClick()
        compose.onNodeWithText("Project one").performClick()
        compose.onNodeWithText("Historical session").assertIsDisplayed()
        compose.onNodeWithText("Live session").assertIsDisplayed()
        val activations = repository.activations.size

        compose
            .onNodeWithContentDescription(label(R.string.remote_design_filter_sessions))
            .performClick()
        compose.onNodeWithContentDescription(label(R.string.remote_hide_offline_sessions)).performClick()
        compose.onNodeWithText(label(R.string.remote_design_done)).performClick()

        compose.onNodeWithText("Historical session").assertDoesNotExist()
        compose.onNodeWithText("Live session").assertIsDisplayed()
        compose
            .onNodeWithContentDescription(label(R.string.remote_new_session))
            .assertIsDisplayed()
        val offlineSessions =
            listOf(
                Wire.objectOf(
                    "id" to "offline",
                    "title" to "Offline session",
                    "status" to "offline",
                    "origin" to "rpc",
                )
            )
        compose.runOnIdle {
            assertEquals(activations, repository.activations.size)
            assertEquals(0, repository.prompts)
            repository.state.value = repository.state.value.copy(sessions = offlineSessions)
        }
        compose.onNodeWithText(label(R.string.remote_offline_sessions_hidden)).assertIsDisplayed()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(connected = false)
        }
        compose.onNodeWithText(label(R.string.remote_offline_sessions_hidden)).assertDoesNotExist()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(connected = true, loading = true)
        }
        compose.onNodeWithText(label(R.string.remote_offline_sessions_hidden)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.remote_empty_sessions)).assertDoesNotExist()
        compose
            .onNodeWithContentDescription(label(R.string.remote_sessions_loading))
            .assertDoesNotExist()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(loading = false)
        }
        compose.onNodeWithText(label(R.string.remote_offline_sessions_hidden)).assertIsDisplayed()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(sessions = emptyList(), loading = true)
        }
        compose
            .onNodeWithContentDescription(label(R.string.remote_sessions_loading))
            .assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_offline_sessions_hidden)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.remote_empty_sessions)).assertDoesNotExist()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(loading = false)
        }
        compose
            .onNodeWithContentDescription(label(R.string.remote_sessions_loading))
            .assertDoesNotExist()
        compose.onNodeWithText(label(R.string.remote_empty_sessions)).assertIsDisplayed()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(sessions = offlineSessions)
            assertEquals(activations, repository.activations.size)
            assertEquals(0, repository.prompts)
        }
        compose.onNodeWithText(label(R.string.remote_offline_sessions_hidden)).assertIsDisplayed()
        compose
            .onNodeWithContentDescription(
                "${label(R.string.remote_design_filter_sessions)}, " +
                    compose.activity.getString(R.string.remote_design_filter_active_count, 1)
            )
            .performClick()
        compose.onNodeWithContentDescription(label(R.string.remote_hide_offline_sessions)).performClick()
        compose.onNodeWithText(label(R.string.remote_design_done)).performClick()
        compose.onNodeWithText("Offline session").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(activations, repository.activations.size)
            assertEquals(0, repository.prompts)
        }
    }

    @Test
    fun hideOfflineSettingDefaultsFalseAndSurvivesRepositoryRecreation() {
        val preferenceName = "settings-${UUID.randomUUID()}"
        val context =
            object : ContextWrapper(compose.activity) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences(preferenceName, mode)
            }
        try {
            assertFalse(DefaultSettingsRepository(context).state.value.hideOfflineSessions)
            DefaultSettingsRepository(context).setHideOfflineSessions(true)
            assertTrue(DefaultSettingsRepository(context).state.value.hideOfflineSessions)
        } finally {
            context
                .getSharedPreferences(preferenceName, android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun settingsSwitchAndThemeCardsExposeOneSelectableTargetEach() {
        settings()
        val thinking = { compose.onNodeWithTag("thinkingSwitch") }
        thinking().assert(hasClickAction()).assertIsOff()
            .assertTextContains(label(R.string.remote_design_show_thinking))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        compose.onAllNodes(
            hasClickAction() and hasAnyAncestor(hasTestTag("thinkingSwitch")),
            useUnmergedTree = true,
        ).assertCountEquals(0)
        thinking().performClick()
        thinking().assertIsOn()
        compose.runOnIdle { assertEquals("text", uiSettings.state.value.thinkingDisplay) }
        thinking().performClick()
        thinking().assertIsOff()
        compose.runOnIdle { assertEquals("status", uiSettings.state.value.thinkingDisplay) }

        val themes = listOf("themeSystem" to "system", "themeLight" to "light", "themeDark" to "dark")
        compose.onNodeWithTag("themeSystem").assertIsSelected()
        for ((tag, value) in themes.drop(1) + themes.first()) {
            compose.onNodeWithTag(tag)
                .assert(hasClickAction())
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .performClick()
            compose.waitForIdle()
            for ((candidate, _) in themes) {
                compose.onNodeWithTag(candidate).let {
                    if (candidate == tag) it.assertIsSelected() else it.assertIsNotSelected()
                }
                compose.onAllNodes(
                    hasClickAction() and hasAnyAncestor(hasTestTag(candidate)),
                    useUnmergedTree = true,
                ).assertCountEquals(0)
            }
            compose.runOnIdle { assertEquals(value, uiSettings.state.value.theme) }
        }
    }

    @Test
    fun thinkingDisplayAndThemePersistAcrossRepositoryRecreation() {
        val preferenceName = "settings-${UUID.randomUUID()}"
        val context =
            object : ContextWrapper(compose.activity) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences(preferenceName, mode)
            }
        val preferences = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        try {
            assertEquals("status", DefaultSettingsRepository(context).state.value.thinkingDisplay)
            preferences.edit().putString("thinking_display", "status").commit()
            assertEquals("status", DefaultSettingsRepository(context).state.value.thinkingDisplay)
            preferences.edit().putString("thinking_display", "unknown").commit()
            assertEquals("status", DefaultSettingsRepository(context).state.value.thinkingDisplay)
            preferences.edit()
                .putString("thinking_display", "text")
                .putBoolean("hide_offline_sessions", true)
                .commit()
            for (theme in listOf("system", "light", "dark")) {
                DefaultSettingsRepository(context).setTheme(theme)
                assertEquals(theme, preferences.getString("theme", null))
                val restored = DefaultSettingsRepository(context).state.value
                assertEquals(theme, restored.theme)
                assertEquals("text", restored.thinkingDisplay)
                assertTrue(restored.hideOfflineSessions)
            }
            val repository = DefaultSettingsRepository(context)
            repository.setThinkingDisplay("status")
            val restored = DefaultSettingsRepository(context).state.value
            assertEquals("status", restored.thinkingDisplay)
            assertEquals("dark", restored.theme)
            assertTrue(restored.hideOfflineSessions)
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun errorCardOffersRetryOnlyForReloadableErrors() {
        compose.onNodeWithText("Test computer").performClick()
        compose.onNodeWithText("Project one").assertIsDisplayed()
        val showError = { error: Int ->
            compose.runOnIdle {
                repository.state.value = repository.state.value.copy(error = error)
            }
        }
        showError(R.string.remote_notification_denied)
        compose.onNodeWithTag("errorCard").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_error_retry)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.remote_error_close)).performClick()
        compose.onNodeWithTag("errorCard").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, repository.errorDismissals) }

        showError(R.string.remote_request_error)
        compose.onNodeWithTag("errorCard").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_error_retry)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_error_close)).performClick()
        compose.onNodeWithTag("errorCard").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, repository.errorDismissals)
            assertEquals(0, repository.refreshes)
        }
        showError(R.string.remote_connection_error)
        compose.onNodeWithText(label(R.string.remote_error_retry)).performClick()
        compose.onNodeWithTag("errorCard").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(3, repository.errorDismissals)
            assertEquals(1, repository.refreshes)
        }
    }

    @Test
    fun sessionsTitleShowsProjectNameWithSessionCount() {
        compose.onNodeWithText("Test computer").performClick()
        compose.onNodeWithText("Project one").performClick()
        compose.onNodeWithTag("sessionsProjectTitle").assertTextEquals("Project one")
        compose
            .onNodeWithText(
                compose.activity.resources.getQuantityString(R.plurals.remote_sessions_count, 2, 2)
            )
            .assertIsDisplayed()
        compose.onAllNodesWithText("Project one").assertCountEquals(1)
    }

    @Test
    fun disconnectAsksForConfirmationFirst() {
        compose.onNodeWithText("Test computer").performClick()
        compose.onNodeWithText("Project one").assertIsDisplayed()
        val title = compose.activity.getString(R.string.remote_disconnect_title, "Test computer")
        click(R.string.remote_disconnect)
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNode(hasText(label(R.string.remote_cancel)) and hasAnyAncestor(isDialog()))
            .performClick()
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.runOnIdle { assertNotNull(repository.state.value.host) }

        click(R.string.remote_disconnect)
        compose.onNodeWithTag("confirmDisconnect").performClick()
        compose.runOnIdle {
            assertEquals(1, repository.disconnects)
            assertNull(repository.state.value.host)
        }
    }

    @Test
    fun swipeSettingsSegmentedButtonsUpdateSettings() {
        settings()
        compose.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasTestTag("swipeStartToEnd-NONE"))
        compose.onNodeWithText(label(R.string.remote_swipe_title)).assertIsDisplayed()
        compose.onNodeWithTag("swipeEndToStart-CLOSE").assertIsSelected()
        compose.onNodeWithTag("swipeStartToEnd-RENAME").assertIsSelected()
        for (action in SwipeAction.entries) {
            compose.onNodeWithTag("swipeEndToStart-${action.name}").performClick()
            compose.runOnIdle { assertEquals(action, uiSettings.state.value.swipeEndToStart) }
            compose.onNodeWithTag("swipeEndToStart-${action.name}").assertIsSelected()
            compose.onNodeWithTag("swipeStartToEnd-${action.name}").performClick()
            compose.runOnIdle { assertEquals(action, uiSettings.state.value.swipeStartToEnd) }
            compose.onNodeWithTag("swipeStartToEnd-${action.name}").assertIsSelected()
        }
    }

    @Test
    fun navigationAndSettingsReturnPreserveChatWithoutReopening() {
        openChat()
        compose.onNodeWithText("Message for live").assertIsDisplayed()
        val activations = repository.activations.size
        settings()
        compose.onNodeWithText(label(R.string.remote_appearance)).assertIsDisplayed()
        back()
        assertEquals(activations, repository.activations.size)
        compose.onNodeWithText("Message for live").assertIsDisplayed()
        back()
        compose.onNodeWithText("Live session").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Project one").assertIsDisplayed()
        back()
        compose.onNodeWithText("Test computer").assertIsDisplayed()
    }

    @Test
    fun canonicalForkReplacesHistoryIdAndStaleOpenCannotUndoBack() {
        openChat("Historical session")
        compose.onNodeWithText("Message for fork").assertIsDisplayed()
        assertEquals("fork", repository.state.value.selection.sessionId)
        back()
        val pending = CompletableDeferred<Unit>()
        compose.runOnIdle { repository.openGate = pending }
        compose.onNodeWithText("Historical session").performClick()
        back()
        compose.runOnIdle { pending.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithText("Historical session").assertIsDisplayed()
        compose.onNodeWithText("Message for fork").assertDoesNotExist()
    }

    @Test
    fun savedActivityStateAndFreshViewModelsRestoreIdsAndEncryptedDraftWithoutPromptReplay() {
        openChat()
        compose
            .onNodeWithText(label(R.string.remote_prompt))
            .performTextInput("Unsent after recreation")
        compose.runOnIdle { assertEquals(0, repository.prompts) }
        val oldRepository = repository
        compose.runOnIdle {
            isolatedDrafts(compose.activity)
                .save(
                    mapOf(
                        DraftKey("host", "live") to
                            StoredDraft(
                                "Unsent after recreation",
                                "unacknowledged-send",
                                "Unsent after recreation",
                            )
                    )
                )
            compose.activity.clearViewModelsOnDestroy = true
        }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertNotSame(oldRepository, repository)
        compose.onNodeWithText("Unsent after recreation").assertIsDisplayed()
        assertEquals(RemoteSelection("host", "project", "live"), repository.state.value.selection)
        assertTrue(repository.activations.all { it.second == ActivationMode.RESTORE })
        assertEquals(0, repository.prompts)
        compose.onNodeWithText(label(R.string.remote_draft_uncertain)).assertIsDisplayed()
    }

    @Test
    fun notificationSupersedesPendingRestoreAndIsConsumedOnce() {
        openChat()
        val gate = CompletableDeferred<Unit>()
        repositoryFactory = { UiRemoteRepository(isolatedDrafts(it)).apply { restoreGate = gate } }
        try {
            compose.runOnIdle { compose.activity.clearViewModelsOnDestroy = true }
            compose.activityRule.scenario.recreate()
            compose.runOnIdle {
                compose.activity.notification = RemoteNotification("host", "notified", 1)
            }
            compose.waitUntil { repository.notifications == 1 }
            compose.onNodeWithText("Message for notified").assertIsDisplayed()
            compose.runOnIdle { gate.complete(Unit) }
            compose.waitForIdle()
            compose.onNodeWithText("Message for notified").assertIsDisplayed()
            settings()
            back()
            assertEquals(1, repository.notifications)
        } finally {
            repositoryFactory = { UiRemoteRepository(isolatedDrafts(it)) }
            gate.complete(Unit)
        }
    }

    @Test
    fun api36EdgeGestureCommitsPredictiveBackThroughNavDisplay() {
        openChat()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val gesturalOverlay = "com.android.internal.systemui.navbar.gestural"
        val gesturalWasEnabled = device.executeShellCommand("cmd overlay list android")
            .lineSequence().any { it.trim() == "[x] $gesturalOverlay" }
        try {
            if (!gesturalWasEnabled) {
                device.executeShellCommand("cmd overlay enable --user 0 $gesturalOverlay")
                device.waitForIdle()
            }
            assertTrue(
                device.executeShellCommand("cmd overlay list android")
                    .lineSequence().any { it.trim() == "[x] $gesturalOverlay" }
            )
            Thread.sleep(1_000)
            device.waitForIdle()
            compose.waitForIdle()
            assertTrue(
                device.swipe(
                    24,
                    device.displayHeight / 2,
                    device.displayWidth * 3 / 4,
                    device.displayHeight / 2,
                    30,
                )
            )
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onNodeWithText("Live session").isDisplayed()
            }
            compose.onNodeWithText("Live session").assertIsDisplayed()
            compose.onNodeWithText("Message for live").assertDoesNotExist()
        } finally {
            if (!gesturalWasEnabled) {
                device.executeShellCommand("cmd overlay disable --user 0 $gesturalOverlay")
                device.waitForIdle()
            }
        }
    }

    private fun openInsightsChat() {
        repository.insights = true
        openChat()
    }

    private fun toolHeaderMatcher(text: String) =
        hasTestTag("toolCardHeader") and hasText(text, substring = true)

    private fun toolHeader(text: String) = compose.onNode(toolHeaderMatcher(text))

    /**
     * Scrolls the chat list until [matcher] is composed and returns it. The list only composes
     * what fits between the header and the composer, and on a small screen the insight cards and
     * the touched-files summary push the first message out of it. Fails if the list does not hold
     * the node at all, or if the chat list is not reachable (for example under the tool detail).
     */
    private fun scrollChatTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        return compose.onNode(matcher).assertExists()
    }

    @Test
    fun toolCardOpensDetailAndCloseOrBackReturnsToChat() {
        openInsightsChat()
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(capabilities = setOf(TOOL_OUTPUT_CAPABILITY))
        }
        toolHeader("src/a.kt").performSemanticsAction(SemanticsActions.OnClick)
        val close = compose.onNodeWithTag("toolDetailClose", useUnmergedTree = true)
        close.assertIsDisplayed().performClick()
        compose.onNodeWithTag("toolDetailClose", useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()

        toolHeader("./gradlew test").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("toolDetailClose", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_tool_detail_load_full)).performClick()
        compose.runOnIdle { assertEquals(listOf("call-bash"), repository.toolOutputLoads) }
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag("toolDetailClose", useUnmergedTree = true).assertDoesNotExist()
        scrollChatTo(hasText("Message for live"))

        scrollChatTo(toolHeaderMatcher("./gradlew test")).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("toolDetailAskFix").performClick()
        compose.onNodeWithTag("toolDetailClose", useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasSetTextAction())
            .assertTextContains(label(R.string.remote_insights_ask_fix_prompt))
        compose.onNodeWithContentDescription(label(R.string.remote_remove_quote)).assertIsDisplayed()
    }

    @Test
    fun touchedFilesSheetJumpsToAndHighlightsTheCard() {
        openChat()
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(messages = insightMessages() + longConversation())
        }
        compose.waitForIdle()
        assertFalse(toolHeader("src/a.kt").isDisplayed())
        compose.onNodeWithTag("touchedFilesSummary").assertIsDisplayed().performClick()
        compose.onNodeWithTag("touchedFilesSheet").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("touchedFileRow").performClick()
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNode(hasTestTag("toolCard") and hasAnyAncestor(hasTestTag("jumpTarget")))
                .assertIsDisplayed()
            toolHeader("src/a.kt").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(2_500)
            compose.onNodeWithTag("jumpTarget").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.onNodeWithTag("touchedFilesSheet").assertDoesNotExist()
    }

    @Test
    fun errorFilterChipShowsOnlyErrorCards() {
        openInsightsChat()
        // Not every card fits the screen at once, so each is scrolled to instead of counted.
        scrollChatTo(hasText("Message for live"))
        scrollChatTo(toolHeaderMatcher("src/a.kt"))
        scrollChatTo(toolHeaderMatcher("./gradlew test"))
        compose.onNodeWithTag("errorFilterChip").assertIsDisplayed().performClick()
        // Filtered, the list holds only the failed run, which fits: nothing else is composed.
        compose.onNodeWithText("Message for live").assertDoesNotExist()
        compose.onAllNodesWithTag("toolCard").assertCountEquals(1)
        toolHeader("./gradlew test").assertExists()
        toolHeader("src/a.kt").assertDoesNotExist()
        compose.onNodeWithTag("errorFilterChip").performClick()
        scrollChatTo(hasText("Message for live"))
        scrollChatTo(toolHeaderMatcher("src/a.kt"))
        scrollChatTo(toolHeaderMatcher("./gradlew test"))
    }

    @Test
    fun compactionBannerFollowsTheSessionState() {
        openChat()
        compose.onNodeWithTag("compactionBanner").assertDoesNotExist()
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(
                    compaction = SessionCompaction("threshold", System.currentTimeMillis())
                )
        }
        compose.onNodeWithTag("compactionBanner").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_panel_compacting)).assertIsDisplayed()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(compaction = null)
        }
        compose.onNodeWithTag("compactionBanner").assertDoesNotExist()
    }

    @Test
    fun subagentStripAbortAsksForConfirmationFirst() {
        openInsightsChat()
        compose.onNodeWithTag("subagentStrip").assertIsDisplayed()
        compose.onNodeWithText(
            compose.activity.resources.getQuantityString(
                R.plurals.remote_panel_subagents_running, 1, 1
            )
        ).performClick()
        compose.onNodeWithTag("subagentStripEntry").assertIsDisplayed()
        val stop = compose.onNodeWithContentDescription(label(R.string.remote_panel_subagent_abort))
        stop.performClick()
        compose.onNodeWithText(label(R.string.remote_insights_abort_child_title)).assertIsDisplayed()
        compose.onNode(hasText(label(R.string.remote_cancel)) and hasAnyAncestor(isDialog()))
            .performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), repository.sessionAborts) }
        stop.performClick()
        compose.onNodeWithTag("confirmAbortChild").performClick()
        compose.runOnIdle {
            assertEquals(listOf("child"), repository.sessionAborts)
            assertEquals(0, repository.aborts)
        }
    }
}

/** A tool edit with its patch, a failed bash run and a running subagent with a known child. */
internal fun insightMessages(): List<JsonObject> =
    Wire.json
        .parseToJsonElement(
            """[
  {"id":"insight-assistant","role":"assistant","text":"","state":"complete","parts":[{"type":"toolCall","id":"call-edit","name":"edit","arguments":"{\"path\":\"src/a.kt\",\"edits\":[{\"oldText\":\"val answer = 41\",\"newText\":\"val answer = 42\"}]}"},{"type":"toolCall","id":"call-bash","name":"bash","arguments":"{\"command\":\"./gradlew test\"}"},{"type":"toolCall","id":"call-sub","name":"subagent","arguments":"{\"agent\":\"explorer\",\"task\":\"Map the chat screen\"}"}]},
  {"id":"insight-edit","role":"tool","toolName":"edit","toolCallId":"call-edit","text":"Successfully replaced 1 block in src/a.kt.","state":"complete","toolDetails":{"kind":"edit","patch":"--- src/a.kt\n+++ src/a.kt\n@@ -1,3 +1,3 @@\n fun main() {\n-val answer = 41\n+val answer = 42\n }\n","firstChangedLine":2}},
  {"id":"insight-bash","role":"tool","toolName":"bash","toolCallId":"call-bash","text":"FAILED: 3 tests","state":"error","truncated":true},
  {"id":"insight-subagent","role":"tool","toolName":"subagent","toolCallId":"call-sub","text":"","state":"streaming","subagentProgress":{"mode":"single","agents":[{"agent":"explorer","state":"running","task":"Map the chat screen","sessionId":"child","activity":"reading files"}]}}
]"""
        )
        .jsonArray
        .map { it.jsonObject }

class UiRemoteRepository(private val drafts: DraftStorage) : RemoteRepository {
    private val host =
        PairedHost("host", "wss://test.invalid", "device", "not-a-real-secret", "Test computer")
    private val project = Wire.objectOf("id" to "project", "name" to "Project one")
    override val state = MutableStateFlow(RemoteState(hosts = listOf(host)))
    val activations = mutableListOf<Pair<RemoteSelection, ActivationMode>>()
    var prompts = 0
    var followUps = 0
    var aborts = 0
    val answers = mutableListOf<Pair<String, JsonObject>>()
    var notifications = 0
    var errorDismissals = 0
    var refreshes = 0
    var disconnects = 0
    var openGate: CompletableDeferred<Unit>? = null
    /** Adds [insightMessages] and a running child session of "live" to every chat. */
    var insights = false
    val sessionAborts = mutableListOf<String>()
    val toolOutputLoads = mutableListOf<String>()
    var restoreGate: CompletableDeferred<Unit>? = null
    private var epoch = 0

    private fun update(selection: RemoteSelection) {
        val stored = selection.sessionId?.let { drafts.load()[DraftKey("host", it)] }
        state.value =
            RemoteState(
                hosts = listOf(host),
                host = if (selection.routeId != null) host else null,
                selection = selection,
                connected = selection.routeId != null,
                connection = R.string.remote_connected,
                projects = listOf(project),
                project = if (selection.projectId != null) project else null,
                sessions =
                    listOf(
                        Wire.objectOf(
                            "id" to "live",
                            "title" to "Live session",
                            "status" to "idle",
                            "origin" to "live",
                        ),
                        Wire.objectOf(
                            "id" to "history",
                            "title" to "Historical session",
                            "status" to "offline",
                            "origin" to "history",
                        ),
                    ) +
                        if (insights)
                            listOf(
                                Wire.objectOf(
                                    "id" to "child",
                                    "title" to "Explorer child",
                                    "status" to "running",
                                    "origin" to "rpc",
                                    "parentSessionId" to "live",
                                )
                            )
                        else emptyList(),
                session =
                    selection.sessionId?.let {
                        Wire.objectOf("id" to it, "title" to "Session $it")
                    },
                messages =
                    selection.sessionId
                        ?.let {
                            listOf(
                                Wire.objectOf(
                                    "id" to "message-$it",
                                    "role" to "assistant",
                                    "text" to "Message for $it",
                                )
                            ) + if (insights) insightMessages() else emptyList()
                        }
                        .orEmpty(),
                draft = stored?.text.orEmpty(),
                uncertain = stored?.mutationId != null,
                status = "idle",
            )
    }

    override suspend fun activate(
        selection: RemoteSelection,
        mode: ActivationMode,
    ): RemoteSelection {
        val generation = epoch
        activations += selection to mode
        val gate = if (mode == ActivationMode.RESTORE) restoreGate else openGate
        gate?.let { withContext(NonCancellable) { it.await() } }
        val canonical =
            if (selection.sessionId == "history" && mode == ActivationMode.USER_OPEN)
                selection.copy(sessionId = "fork")
            else selection
        if (generation == epoch) update(canonical)
        return canonical
    }

    override suspend fun createSession(routeId: String, projectId: String) =
        RemoteSelection(routeId, projectId, "created").also(::update)

    override suspend fun openNotification(routeId: String, sessionId: String): RemoteSelection? {
        notifications++
        if (routeId != "host") return null
        return RemoteSelection(routeId, "project", sessionId).also(::update)
    }

    override fun cancelSelection() {
        epoch++
    }

    override suspend fun pair(text: String): RemoteSelection? = null

    override fun remove(routeId: String) {}

    override fun disconnect() {
        disconnects++
        update(RemoteSelection())
    }

    override fun refresh() {
        refreshes++
    }

    override fun older() {}

    override fun draft(text: String) {
        state.value = state.value.copy(draft = text)
        val id = state.value.selection.sessionId ?: return
        drafts.save(drafts.load() + (DraftKey("host", id) to StoredDraft(text)))
    }

    override fun quote(messageId: String?) {
        state.value =
            state.value.copy(
                quote =
                    messageId?.let { id ->
                        state.value.messages.find { it.text("id") == id }?.let(::quoteFromMessage)
                    }
            )
    }

    val imports = mutableListOf<Pair<RemoteSelection, List<String>>>()

    override fun importAttachments(selection: RemoteSelection, uris: List<String>, photo: Boolean) {
        imports += selection to uris
    }

    override fun removeAttachment(id: String) {
        state.value =
            state.value.copy(attachments = state.value.attachments.filterNot { it.id == id })
    }

    override fun setModel(provider: String, id: String) {
        val confirmed = state.value.configuration ?: return
        val model = confirmed.models.find { it.provider == provider && it.id == id } ?: return
        state.value = state.value.copy(configuration = confirmed.copy(model = model))
    }

    override fun setThinkingLevel(level: String) {
        state.value =
            state.value.copy(configuration = state.value.configuration?.copy(thinkingLevel = level))
    }

    override fun selectCommand(command: RemoteCommand) {
        draft(selectCommand(state.value.draft, command))
    }

    override fun prompt() {
        prompts++
    }

    override fun followUp() {
        followUps++
    }

    override fun dismissFollowUp(requestId: String) {
        state.value = state.value.copy(followUps = state.value.followUps.filterNot {
            it.requestId == requestId && it.status != "pending"
        })
    }

    override fun abort() {
        aborts++
    }

    override fun abortSession(sessionId: String) {
        sessionAborts += sessionId
    }

    override fun loadToolOutput(toolCallId: String) {
        toolOutputLoads += toolCallId
    }

    override fun answer(questionId: String, answer: JsonObject) {
        answers += questionId to answer
    }

    override fun dismissError() {
        errorDismissals++
        state.value = state.value.copy(error = null)
    }

    override fun reportError(resource: Int) {}

    override fun setPushToken(token: String?) {}
}

private class UiSettingsRepository : SettingsRepository {
    override val state = MutableStateFlow(RemoteSettings())

    override fun setTheme(theme: String) {
        state.value = state.value.copy(theme = theme)
    }

    override fun setPushEnabled(enabled: Boolean) {
        state.value = state.value.copy(pushEnabled = enabled)
    }

    override fun setThinkingDisplay(display: String) {
        state.value = state.value.copy(thinkingDisplay = display)
    }

    override fun setHideOfflineSessions(hide: Boolean) {
        state.value = state.value.copy(hideOfflineSessions = hide)
    }

    override fun setSwipeEndToStart(action: SwipeAction) {
        state.value = state.value.copy(swipeEndToStart = action)
    }

    override fun setSwipeStartToEnd(action: SwipeAction) {
        state.value = state.value.copy(swipeStartToEnd = action)
    }
}
