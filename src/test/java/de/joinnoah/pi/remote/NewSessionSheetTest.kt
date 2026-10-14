package de.joinnoah.pi.remote

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewSessionSheetTest {
    private val model = RemoteModel(provider = "anthropic", id = "sonnet", name = "Sonnet", input = setOf("text"))
    private val created = RemoteSelection("route", "project", "session")
    private val elsewhere = RemoteSelection("route", "other", "session")

    /**
     * Records the steps in order. The model change is asynchronous like the repository's: [setModel]
     * ends only after the host answered, and [modelResult] says whether it applied or was dropped.
     */
    private class FakeSteps(
        private val created: RemoteSelection,
        private val elsewhere: RemoteSelection,
        private val modelsArrive: Boolean = true,
        private val navigateAway: Boolean = false,
        private val modelResult: Boolean = true,
        private val typedMeanwhile: String = "",
    ) : NewSessionSteps {
        val events = mutableListOf<String>()
        private var current: RemoteSelection = created

        override fun selected(): RemoteSelection = current

        override suspend fun awaitModels(selection: RemoteSelection): Boolean {
            events += "models"
            if (navigateAway) current = elsewhere
            return modelsArrive
        }

        override suspend fun setModel(model: RemoteModel): Boolean {
            events += "model:${model.provider}/${model.id}:start"
            // The host answers later; a send before this point is dropped by the repository.
            kotlinx.coroutines.yield()
            events += "model:done"
            return modelResult
        }

        override fun draftText(selection: RemoteSelection): String = typedMeanwhile

        override fun draft(selection: RemoteSelection, text: String) {
            events += "draft:$text"
        }

        override fun send() {
            events += "send"
        }

        /** What [modelNotSet] told the user, if anything: whether the prompt was kept. */
        var modelNotSet: Boolean? = null

        override fun modelNotSet(promptKept: Boolean) {
            modelNotSet = promptKept
        }
    }

    private fun run(choice: NewSessionChoice, steps: FakeSteps) =
        runBlocking { finishNewSession(created, choice, steps) }

    private fun steps(
        modelsArrive: Boolean = true,
        navigateAway: Boolean = false,
        modelResult: Boolean = true,
        typedMeanwhile: String = "",
    ) = FakeSteps(created, elsewhere, modelsArrive, navigateAway, modelResult, typedMeanwhile)

    @Test
    fun sendsOnlyAfterTheModelChangeCompleted() {
        val steps = steps()
        run(NewSessionChoice(model, "Hello"), steps)
        assertEquals(
            listOf("models", "model:anthropic/sonnet:start", "model:done", "draft:Hello", "send"),
            steps.events,
        )
    }

    @Test
    fun aDroppedOrFailedModelChangeKeepsThePromptInTheDraft() {
        val steps = steps(modelResult = false)
        run(NewSessionChoice(model, "Hi"), steps)
        assertEquals(listOf("models", "model:anthropic/sonnet:start", "model:done", "draft:Hi"), steps.events)
        // The user learns why nothing went out.
        assertEquals(true, steps.modelNotSet)
    }

    @Test
    fun aFailedModelWithoutAPromptIsStillReported() {
        val steps = steps(modelResult = false)
        run(NewSessionChoice(model, ""), steps)
        assertEquals(false, steps.modelNotSet)
    }

    @Test
    fun withoutAModelOnlyThePromptIsSent() {
        val steps = steps()
        run(NewSessionChoice(null, "Hi"), steps)
        assertEquals(listOf("draft:Hi", "send"), steps.events)
    }

    @Test
    fun aBlankPromptSendsNothing() {
        val steps = steps()
        run(NewSessionChoice(model, "  "), steps)
        assertEquals(listOf("models", "model:anthropic/sonnet:start", "model:done"), steps.events)
    }

    @Test
    fun aModelThatDoesNotArriveKeepsThePromptInTheDraft() {
        val steps = steps(modelsArrive = false)
        run(NewSessionChoice(model, "Hi"), steps)
        assertEquals(listOf("models", "draft:Hi"), steps.events)
        assertEquals(true, steps.modelNotSet)
    }

    @Test
    fun navigatingAwayStopsTheRemainingSteps() {
        val steps = steps(navigateAway = true)
        run(NewSessionChoice(model, "Hi"), steps)
        assertEquals(listOf("models"), steps.events)
        // Another chat is on screen: nothing is reported there.
        assertEquals(null, steps.modelNotSet)
    }

    @Test
    fun aMessageTypedMeanwhileIsKeptAndNotSentWithThePrompt() {
        val steps = steps(typedMeanwhile = "wait")
        run(NewSessionChoice(null, "Hi"), steps)
        assertEquals(listOf("draft:Hi\n\nwait"), steps.events)
    }

    @Test
    fun aCreateWithoutASessionDoesNothing() {
        val steps = steps()
        runBlocking { finishNewSession(RemoteSelection("route", "project"), NewSessionChoice(model, "Hi"), steps) }
        assertTrue(steps.events.isEmpty())
    }

    @Test
    fun promptIssuesAreCommandsAndOversizedTexts() {
        assertNull(newSessionPromptIssue("Hello"))
        assertEquals(NewSessionPromptIssue.COMMAND, newSessionPromptIssue("  /compact"))
        assertEquals(NewSessionPromptIssue.TOO_LONG, newSessionPromptIssue("x".repeat(MAX_DRAFT_BYTES + 1)))
        assertNull(newSessionPromptIssue("x".repeat(MAX_DRAFT_BYTES)))
    }

    private fun stateWith(
        model: RemoteModel?,
        changing: Boolean,
        selection: RemoteSelection = created,
    ) = RemoteState(
        selection = selection,
        configuration = SessionConfiguration(model, "off", emptyList(), listOf(this.model), false),
        configurationChanging = changing,
    )

    private val other = RemoteModel(provider = "anthropic", id = "haiku", name = "Haiku", input = setOf("text"))

    @Test
    fun modelAppliedWhenTheHostConfirmsIt() {
        val state = MutableStateFlow(stateWith(other, changing = true))
        val result = runBlocking {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                awaitModelApplied(state, created, model)
            }
            state.value = stateWith(model, changing = false)
            waiting.await()
        }
        assertTrue(result)
    }

    @Test
    fun modelNotAppliedWhenTheChangeFailedOrWasDropped() {
        // Failed: the change ended with the old model.
        val failing = MutableStateFlow(stateWith(other, changing = true))
        val failed = runBlocking {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                awaitModelApplied(failing, created, model)
            }
            failing.value = stateWith(other, changing = false)
            waiting.await()
        }
        assertFalse(failed)
        // Dropped: nothing ever started, so the old model stays.
        assertFalse(runBlocking { awaitModelApplied(MutableStateFlow(stateWith(other, changing = false)), created, model) })
        // Already the model: nothing to wait for.
        assertTrue(runBlocking { awaitModelApplied(MutableStateFlow(stateWith(model, changing = false)), created, model) })
        // Another chat selected meanwhile.
        assertFalse(runBlocking { awaitModelApplied(MutableStateFlow(stateWith(model, false, elsewhere)), created, model) })
    }
}
