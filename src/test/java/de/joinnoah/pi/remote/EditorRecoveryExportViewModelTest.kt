package de.joinnoah.pi.remote

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorRecoveryExportViewModelTest {
    @Test fun emptySavedStateStartsIdle() {
        val model = EditorRecoveryExportViewModel(SavedStateHandle())
        assertEquals(EditorRecoveryExportPhase.IDLE, model.state.value.phase)
        assertNull(model.state.value.uri)
        assertNull(model.claimSavedNotification())
    }

    @Test fun restoredWritingMarkerIsInterruptedAndCanBeCleared() {
        val savedState = SavedStateHandle(mapOf(EditorRecoveryExportViewModel.WRITING to true))
        val model = EditorRecoveryExportViewModel(savedState)
        assertEquals(EditorRecoveryExportPhase.INTERRUPTED, model.state.value.phase)
        assertNull(model.state.value.uri)
        assertNull(model.claimSavedNotification())
        assertEquals(setOf(EditorRecoveryExportViewModel.WRITING), savedState.keys())

        model.clearOutcome()
        assertEquals(EditorRecoveryExportPhase.IDLE, model.state.value.phase)
        assertNull(model.state.value.uri)
        assertNull(model.claimSavedNotification())
        assertEquals(false, savedState.get<Boolean>(EditorRecoveryExportViewModel.WRITING))
        assertEquals(setOf(EditorRecoveryExportViewModel.WRITING), savedState.keys())
    }
}
