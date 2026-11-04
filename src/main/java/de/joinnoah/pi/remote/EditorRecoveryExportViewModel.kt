package de.joinnoah.pi.remote

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class EditorRecoveryExportPhase { IDLE, WRITING, SAVED, FAILED, INTERRUPTED }

internal data class EditorRecoveryExportState(
    val phase: EditorRecoveryExportPhase,
    val uri: Uri? = null,
)

/** Activity-owned IO: neither the text nor destination is put in saved-instance state. */
internal class EditorRecoveryExportViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    private val mutableState = MutableStateFlow(EditorRecoveryExportState(
        if (savedState.get<Boolean>(WRITING) == true) EditorRecoveryExportPhase.INTERRUPTED else EditorRecoveryExportPhase.IDLE,
    ))
    val state = mutableState.asStateFlow()
    private var notified = false

    fun clearOutcome() {
        check(mutableState.value.phase != EditorRecoveryExportPhase.WRITING)
        savedState[WRITING] = false
        mutableState.value = EditorRecoveryExportState(EditorRecoveryExportPhase.IDLE)
        notified = false
    }

    fun write(entry: EditorRecoveryEntry, uri: Uri, open: (Uri) -> OutputStream?) {
        check(mutableState.value.phase != EditorRecoveryExportPhase.WRITING)
        savedState[WRITING] = true
        mutableState.value = EditorRecoveryExportState(EditorRecoveryExportPhase.WRITING)
        notified = false
        viewModelScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    val format = editorRecoveryExportFormat(entry.text)
                    checkNotNull(open(uri)) { "No output stream" }.use { writeEditorRecovery(entry.text, format, it) }
                }
                true
            } catch (e: CancellationException) {
                // Keep the advisory marker: a cancelled synchronous write may have reached the file.
                throw e
            } catch (_: Exception) { false }
            savedState[WRITING] = false
            mutableState.value = EditorRecoveryExportState(
                if (ok) EditorRecoveryExportPhase.SAVED else EditorRecoveryExportPhase.FAILED,
                if (ok) uri else null,
            )
        }
    }

    /** Observation can move between dialog instances without duplicating the saved notification. */
    fun claimSavedNotification(): Uri? {
        val current = mutableState.value
        if (current.phase != EditorRecoveryExportPhase.SAVED || notified) return null
        notified = true
        return current.uri
    }

    companion object { internal const val WRITING = "editorRecoveryExportWriting" }
}
