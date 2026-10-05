package de.joinnoah.pi.remote

import android.app.Application
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class FullChatFixtureRepositoryTest {
    @Test
    fun historyExercisesProductionConversationAndToolProjection() {
        val repository = FullChatFixtureRepository()
        val state = repository.state.value
        assertEquals(193, state.messages.size)
        assertEquals(FULL_CHAT_READY, state.messages.last().text("text"))
        val projected = conversationItems(state.messages)
        assertEquals(48, projected.filterIsInstance<ConversationItem.Activity>().size)
        assertEquals(48, touchedFiles(projected).total)
        assertEquals(TouchedLineCounts(20, 20), touchedLineCounts(projected))
        assertEquals(4, projected.count(::isErrorItem))
        val markers = timelineMarkers(projected)
        assertEquals(20, markers.count { it.kind == TimelineMarkerKind.EDIT })
        assertEquals(4, markers.count { it.kind == TimelineMarkerKind.ERROR })
        assertEquals(fullChatKey.selection(), state.selection)
        assertEquals("ready", repository.progress.value)
    }

    @Test
    fun cancellationAtCheckpointDoesNotPublishFalseCompletion() = runTest {
        val repository = FullChatFixtureRepository()
        val streaming = async { repository.stream() }
        advanceUntilIdle()
        assertEquals("checkpoint", repository.progress.value)
        streaming.cancelAndJoin()
        assertFalse(repository.state.value.messages.any { it.text("text") == FULL_CHAT_COMPLETE })
        assertNotEquals("complete", repository.progress.value)
    }

    @Test
    fun streamingPublishesCompleteTimelineWithoutChangingHistoricalMessages() = runTest {
        val repository = FullChatFixtureRepository()
        val history = repository.state.value.messages
        val streaming = async { repository.stream() }
        advanceUntilIdle()
        assertEquals("checkpoint", repository.progress.value)
        assertEquals("running", repository.state.value.status)
        assertEquals(FULL_CHAT_CHECKPOINT, repository.state.value.messages.last().text("text"))
        repository.continueStream()
        streaming.await()
        val state = repository.state.value
        assertEquals(history, state.messages.take(history.size))
        assertEquals(196, state.messages.size)
        val response = state.messages.first { it.text("id") == "stream" }
        assertTrue(response.text("text").contains("Chunk 119:"))
        assertEquals("complete", response.text("state"))
        assertEquals(FULL_CHAT_COMPLETE, state.messages.last().text("text"))
        assertEquals("idle", state.status)
        assertEquals("complete", repository.progress.value)
    }
}
