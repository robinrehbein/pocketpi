package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The composer keeps its text locally and hands it to the repository late, but never loses it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposerStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val first = RemoteSelection("host", "project", "first")
    private val second = RemoteSelection("host", "project", "second")

    // ---- ComposerDraft --------------------------------------------------------------------

    private class Pushes {
        val all = mutableListOf<Pair<RemoteSelection, String>>()
        val texts get() = all.map { it.second }
    }

    private fun draft(initial: String = "", pushes: Pushes = Pushes()) =
        ComposerDraft(first, initial) { selection, text -> pushes.all += selection to text }

    private fun ComposerDraft.type(text: String) = edit(TextFieldValue(text, TextRange(text.length)))

    @Test
    fun typingStaysLocalUntilThePauseAndThenPushesOnceForItsChat() {
        val pushes = Pushes()
        val composer = draft(pushes = pushes)
        composer.type("H")
        composer.type("He")
        composer.type("Hello")
        assertTrue(pushes.all.isEmpty())
        composer.pushPending()
        composer.pushPending()
        assertEquals(listOf(first to "Hello"), pushes.all)
    }

    @Test
    fun aLateEchoOfAnEarlierPushNeverUndoesNewerTyping() {
        val pushes = Pushes()
        val composer = draft(pushes = pushes)
        composer.type("ab")
        composer.pushPending()
        composer.type("abc")
        composer.pushPending()
        composer.type("abcd")
        // The repository's state arrives a frame late: first the older push, then the newer.
        composer.sync("ab")
        assertEquals("abcd", composer.text)
        composer.sync("abc")
        assertEquals("abcd", composer.text)
        composer.pushPending()
        assertEquals(listOf("ab", "abc", "abcd"), pushes.texts)
    }

    @Test
    fun aChangeMadeElsewhereReplacesTheTextWithTheCursorAtItsEnd() {
        val composer = draft("old")
        composer.sync("Please fix this error.")
        assertEquals("Please fix this error.", composer.text)
        assertEquals(TextRange("Please fix this error.".length), composer.value.selection)
    }

    @Test
    fun theClearAfterASendReplacesTheSentText() {
        val pushes = Pushes()
        val composer = draft(pushes = pushes)
        composer.type("Deploy")
        composer.flush(force = true)
        composer.sync("Deploy")
        composer.sync("")
        assertEquals("", composer.text)
        // A forced flush pushes the shown text even when the repository already holds it.
        composer.type("Again")
        composer.pushPending()
        composer.sync("Again")
        composer.flush(force = true)
        composer.sync("")
        assertEquals("", composer.text)
        assertEquals(listOf("Deploy", "Again", "Again"), pushes.texts)
    }

    @Test
    fun typingTheRepositoryHasNotSeenWinsOverAChangeMadeElsewhere() {
        val pushes = Pushes()
        val composer = draft("Deploy", pushes)
        composer.type("Deploy now")
        // The send's clear arrives while the newest typing is still local.
        composer.sync("")
        assertEquals("Deploy now", composer.text)
        composer.pushPending()
        assertEquals(listOf("Deploy now"), pushes.texts)
    }

    @Test
    fun flushPushesOnlyWhatTheRepositoryDoesNotHold() {
        val pushes = Pushes()
        val composer = draft("same", pushes)
        composer.flush()
        assertTrue(pushes.all.isEmpty())
        composer.type("changed")
        composer.flush()
        composer.flush()
        // The echo has not arrived yet, so the second flush cannot tell; pushing twice is harmless.
        assertEquals(listOf("changed", "changed"), pushes.texts)
        composer.sync("changed")
        composer.flush()
        assertEquals(2, pushes.all.size)
    }

    @Test
    fun aDroppedPushIsRetriedOnTheNextFlush() {
        val pushes = Pushes()
        val composer = draft("", pushes)
        composer.type("while loading")
        // The repository refused it (still loading), so no echo ever comes.
        composer.pushPending()
        composer.flush()
        assertEquals(listOf("while loading", "while loading"), pushes.texts)
    }

    @Test
    fun anEditOverTheDraftLimitIsRefused() {
        val composer = draft("short")
        composer.type("x".repeat(128 * 1024 + 1))
        assertEquals("short", composer.text)
        composer.type("x".repeat(128 * 1024))
        assertEquals(128 * 1024, composer.text.length)
    }

    // ---- ChatComposer -----------------------------------------------------------------------

    private class Harness(initial: RemoteState) {
        var state by mutableStateOf(initial)
        val events = mutableListOf<String>()
        val pushes = mutableListOf<Pair<RemoteSelection, String>>()
    }

    private fun show(
        initial: RemoteState = RemoteState(connected = true, status = "idle", selection = first),
    ): Harness {
        val harness = Harness(initial)
        compose.setContent {
            MaterialTheme {
                ChatComposer(
                    state = harness.state,
                    onDraft = { error("A chat pushes through onDraftFor") },
                    onDraftFor = { selection, text ->
                        harness.pushes += selection to text
                        harness.events += "draft:$text"
                        // Like the repository: a write for another chat is dropped.
                        if (selection == harness.state.selection) harness.state = harness.state.copy(draft = text)
                    },
                    onSend = { harness.events += "send:${harness.state.draft}" },
                    onStop = {},
                    onRemoveQuote = {},
                    voicePermissionGrantedOverride = false,
                )
            }
        }
        compose.waitForIdle()
        return harness
    }

    private fun field() = compose.onNodeWithTag("composerField")

    private fun shownText(): String =
        field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun typeSlowly(text: String) {
        text.forEach {
            field().performTextInput(it.toString())
            compose.mainClock.advanceTimeBy(50)
        }
    }

    private fun label(id: Int) = compose.activity.getString(id)

    @Test
    fun typingReachesTheRepositoryOnlyAfterAPause() {
        val harness = show()
        compose.mainClock.autoAdvance = false
        typeSlowly("Hello")
        assertEquals("Hello", shownText())
        assertTrue(harness.pushes.isEmpty())
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS + 100)
        assertEquals(listOf(first to "Hello"), harness.pushes)
    }

    @Test
    fun sendIsEnabledFromTheLocalTextAndFlushesItFirst() {
        val harness = show()
        compose.mainClock.autoAdvance = false
        typeSlowly("Deploy")
        assertTrue(harness.pushes.isEmpty())
        compose.onNodeWithContentDescription(label(R.string.remote_send)).assertIsEnabled().performClick()
        assertEquals(listOf("draft:Deploy", "send:Deploy"), harness.events)
    }

    @Test
    fun stoppingTheActivityFlushesTheText() {
        val harness = show()
        compose.mainClock.autoAdvance = false
        typeSlowly("Half a thought")
        assertTrue(harness.pushes.isEmpty())
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(listOf(first to "Half a thought"), harness.pushes)
    }

    @Test
    fun switchingTheChatFlushesTheTextToTheChatItWasTypedIn() {
        val harness = show()
        compose.mainClock.autoAdvance = false
        typeSlowly("For the first chat")
        harness.state = harness.state.copy(selection = second, draft = "Second draft")
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(listOf(first to "For the first chat"), harness.pushes)
        // The harness drops it like the repository: the second chat keeps its own draft.
        assertEquals("Second draft", harness.state.draft)
        assertEquals("Second draft", shownText())
    }

    @Test
    fun changesMadeOutsideTheComposerShowInTheField() {
        val harness = show()
        // A quote's "ask to fix" prompt or a prefill from the Changes or Files pane.
        compose.runOnIdle { harness.state = harness.state.copy(draft = "Please fix this error.") }
        compose.waitForIdle()
        assertEquals("Please fix this error.", shownText())
        val selection = field().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(TextRange("Please fix this error.".length), selection)
        // An uncertain send's draft restored by the next snapshot.
        compose.runOnIdle { harness.state = harness.state.copy(draft = "Restored", uncertain = true) }
        compose.waitForIdle()
        assertEquals("Restored", shownText())
        // The clear after a send.
        compose.onNodeWithContentDescription(label(R.string.remote_send)).performClick()
        compose.runOnIdle { harness.state = harness.state.copy(draft = "") }
        compose.waitForIdle()
        assertEquals("", shownText())
        assertEquals(listOf("draft:Restored", "send:Restored"), harness.events)
    }

    @Test
    fun typingDuringASendSurvivesItsClear() {
        val harness = show()
        compose.mainClock.autoAdvance = false
        typeSlowly("One")
        compose.onNodeWithContentDescription(label(R.string.remote_send)).performClick()
        typeSlowly(" more")
        // The acknowledgement clears the sent draft before the pause pushed the new typing.
        harness.state = harness.state.copy(draft = "")
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        assertEquals("One more", shownText())
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS + 100)
        assertEquals("One more", harness.state.draft)
    }

    @Test
    fun anAcceptedPredictionFillsTheFieldAndIsSentAsShown() {
        val source = FakePredictions()
        val messages =
            listOf(
                Wire.objectOf("id" to "u", "role" to "user", "text" to "Change it", "state" to "complete"),
                Wire.objectOf(
                    "id" to "a",
                    "role" to "assistant",
                    "text" to "",
                    "state" to "complete",
                    "parts" to JsonArray(
                        listOf(Wire.objectOf("type" to "toolCall", "id" to "c", "name" to "edit", "arguments" to "{}"))
                    ),
                ),
                Wire.objectOf(
                    "id" to "t",
                    "role" to "tool",
                    "text" to "ok",
                    "state" to "complete",
                    "toolName" to "edit",
                    "toolCallId" to "c",
                ),
            )
        val harness =
            Harness(RemoteState(connected = true, status = "idle", selection = first, messages = messages))
        compose.setContent {
            CompositionLocalProvider(LocalPromptPredictions provides source) {
                MaterialTheme {
                    ChatComposer(
                        state = harness.state,
                        onDraft = {},
                        onDraftFor = { _, text -> harness.state = harness.state.copy(draft = text) },
                        onSend = { harness.events += "send:${harness.state.draft}" },
                        onStop = {},
                        onRemoveQuote = {},
                        voicePermissionGrantedOverride = false,
                    )
                }
            }
        }
        compose.waitForIdle()
        val suggestion = label(R.string.remote_prediction_run_tests)
        val accept = field().fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { accept.action() }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(suggestion, shownText())
        assertEquals("", harness.state.draft)
        compose.onNodeWithContentDescription(label(R.string.remote_send)).performClick()
        assertEquals(listOf("send:$suggestion"), harness.events)
        assertEquals(listOf(projectKey("host", "project") to suggestion), source.recorded)
    }

    private class FakePredictions : PromptPredictionSource {
        override val enabled = MutableStateFlow(true)
        override val history = MutableStateFlow(PromptHistory())
        val recorded = mutableListOf<Pair<String, String>>()

        override fun setEnabled(enabled: Boolean) {
            this.enabled.value = enabled
        }

        override fun clear() {
            history.value = PromptHistory()
        }

        override fun record(routeId: String, projectId: String, text: String) {
            recorded += projectKey(routeId, projectId) to text
        }

        override fun recordPlanApproval(routeId: String, sessionId: String) {}
    }

    @Test
    fun voiceInputWritesIntoTheLocalText() {
        val engine = FakeEngine()
        val harness = Harness(RemoteState(connected = true, status = "idle", selection = first))
        compose.setContent {
            MaterialTheme {
                ChatComposer(
                    state = harness.state,
                    onDraft = {},
                    onDraftFor = { _, text -> harness.state = harness.state.copy(draft = text) },
                    onSend = { harness.events += "send:${harness.state.draft}" },
                    onStop = {},
                    onRemoveQuote = {},
                    voiceEngine = engine,
                    voicePermissionGrantedOverride = true,
                )
            }
        }
        compose.onNodeWithContentDescription(label(R.string.remote_voice_start)).performClick()
        compose.runOnIdle { engine.listener.onPartial("spoken words") }
        compose.waitForIdle()
        assertEquals("spoken words", shownText())
        compose.runOnIdle { engine.listener.onFinal("spoken words, final") }
        compose.waitForIdle()
        field().assertTextEquals("spoken words, final", includeEditableText = true)
        compose.onNodeWithContentDescription(label(R.string.remote_send)).performClick()
        assertEquals(listOf("send:spoken words, final"), harness.events)
    }

    private class FakeEngine : VoiceRecognitionEngine {
        override val systemAvailable = true
        override val onDeviceAvailable = true
        lateinit var listener: VoiceRecognitionListener

        override fun start(start: VoiceInputStart, listener: VoiceRecognitionListener) {
            this.listener = listener
        }

        override fun stop() = Unit

        override fun cancel() = Unit

        override fun destroy() = Unit
    }
}
