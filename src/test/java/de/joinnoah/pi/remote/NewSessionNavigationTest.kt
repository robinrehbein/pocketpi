package de.joinnoah.pi.remote

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The navigator's start-and-send against a repository: create, then the model, then the prompt. */
class NewSessionNavigationTest {
    private val default = RemoteModel("openai", "gpt", "GPT", setOf("text"))
    private val chosen = RemoteModel("anthropic", "sonnet", "Sonnet", setOf("text"))

    private fun repository(): NavigationFakeRepository {
        val repository = NavigationFakeRepository()
        repository.state.value = RemoteState(selection = RemoteSelection("route", "project"), connected = true)
        // The new chat opens with its models known and the default model set.
        repository.create = { routeId, projectId ->
            RemoteSelection(routeId, projectId, "created").also { created ->
                repository.state.value =
                    repository.state.value.copy(
                        selection = created,
                        configuration = SessionConfiguration(default, "off", emptyList(), listOf(default, chosen), false),
                    )
            }
        }
        return repository
    }

    @Test
    fun startAndSendCreatesThenSetsTheModelThenPrompts() = runTest {
        val repository = repository()
        val stack = RemoteSelection("route", "project").keys().toMutableList<androidx.navigation3.runtime.NavKey>()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)

        navigation.startSession("route", "project", NewSessionChoice(chosen, "Hello"))
        runCurrent()

        assertEquals(listOf("create", "model:anthropic/sonnet", "prompt"), repository.sessionSteps)
        assertEquals("Hello", repository.state.value.draft)
        assertEquals(RemoteNavKey.Chat("route", "project", "created"), stack.last())
        assertEquals(emptyList<Int>(), repository.errors)
    }

    @Test
    fun aModelThatIsNotSetKeepsThePromptAndSaysSo() = runTest {
        val repository = repository().apply { appliesModels = false }
        val stack = RemoteSelection("route", "project").keys().toMutableList<androidx.navigation3.runtime.NavKey>()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)

        navigation.startSession("route", "project", NewSessionChoice(chosen, "Hello"))
        runCurrent()

        assertEquals(listOf("create", "model:anthropic/sonnet"), repository.sessionSteps)
        assertEquals("Hello", repository.state.value.draft)
        assertEquals(listOf(R.string.remote_new_session_model_failed_kept), repository.errors)
    }
}
