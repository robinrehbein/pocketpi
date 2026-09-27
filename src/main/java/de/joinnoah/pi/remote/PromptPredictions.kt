package de.joinnoah.pi.remote

import android.content.Context
import androidx.core.content.edit
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface PromptPredictionSource {
    val enabled: StateFlow<Boolean>

    /** The current history; it changes whenever a prompt or a plan approval is recorded. */
    val history: StateFlow<PromptHistory>

    fun setEnabled(enabled: Boolean)

    fun clear()

    fun record(routeId: String, projectId: String, text: String)

    fun recordPlanApproval(routeId: String, sessionId: String)

    /** Forgets every prompt and plan approval of a host that was unpaired. */
    fun forgetRoute(routeId: String) {}
}

internal fun projectKey(routeId: String, projectId: String) = "$routeId/$projectId"

internal fun sessionKey(routeId: String, sessionId: String) = "$routeId/$sessionId"

/**
 * Keeps the user's prompts per project on the device only, encrypted like the drafts and capped in
 * size. Turning predictions off also stops recording.
 */
internal class PromptPredictions(
    context: Context,
    private val scope: CoroutineScope,
    private val pairings: PairingStorage? = null,
) : PromptPredictionSource {
    private val file = EncryptedFileStore(context, "prompt-history.enc", "pi-remote-prompts-v1")
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var loaded = false
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean(ENABLED_KEY, true))
    private val mutableHistory = MutableStateFlow(PromptHistory())
    override val enabled = mutableEnabled.asStateFlow()
    override val history = mutableHistory.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { mutex.withLock { ensureLoaded() } }
    }

    override fun setEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(ENABLED_KEY, enabled) }
        mutableEnabled.value = enabled
    }

    override fun clear() = change { PromptHistory() }

    override fun record(routeId: String, projectId: String, text: String) {
        if (!enabled.value) return
        val now = System.currentTimeMillis()
        change { it.record(projectKey(routeId, projectId), text, now) }
    }

    override fun recordPlanApproval(routeId: String, sessionId: String) {
        if (!enabled.value) return
        val now = System.currentTimeMillis()
        change { it.approvePlan(sessionKey(routeId, sessionId), now) }
    }

    override fun forgetRoute(routeId: String) = change { it.forgetRoute(routeId) }

    /** Loads the history and drops hosts that were unpaired while the app was not running. */
    private fun ensureLoaded() {
        if (loaded) return
        val stored =
            try {
                file.read()?.let { promptHistory(Wire.parse(Wire.utf8(it), 1024 * 1024)) }
            } catch (_: Exception) {
                null
            } ?: PromptHistory()
        val routes =
            try {
                pairings?.load()?.map { it.routeId }?.toSet()
            } catch (_: Exception) {
                null
            }
        val kept = routes?.let { stored.onlyRoutes(it) } ?: stored
        if (kept != stored) {
            try {
                file.write(kept.json().toString().toByteArray())
            } catch (_: Exception) {}
        }
        mutableHistory.value = kept
        loaded = true
    }

    private fun change(update: (PromptHistory) -> PromptHistory) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                ensureLoaded()
                val next = update(mutableHistory.value)
                if (next == mutableHistory.value) return@withLock
                mutableHistory.value = next
                try {
                    file.write(next.json().toString().toByteArray())
                } catch (_: Exception) {
                    // Predictions are a convenience; the in-memory history still works.
                }
            }
        }
    }

    private companion object {
        const val ENABLED_KEY = "prompt_predictions"
    }
}

internal val LocalPromptPredictions = staticCompositionLocalOf<PromptPredictionSource?> { null }

@Composable
internal fun PredictionSettingsSection() {
    val predictions = LocalPromptPredictions.current ?: return
    val enabled by predictions.enabled.collectAsState()
    val history by predictions.history.collectAsState()
    Section(stringResource(R.string.remote_prediction_setting)) {
        Text(
            stringResource(R.string.remote_prediction_setting_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch) { predictions.setEnabled(it) }
                .testTag("predictionSwitch")
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.remote_prediction_show), Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = null)
        }
        OutlinedButton(
            onClick = predictions::clear,
            enabled = history.projects.isNotEmpty() || history.planApprovals.isNotEmpty(),
            modifier = Modifier.testTag("clearPredictionHistory"),
        ) {
            Text(stringResource(R.string.remote_prediction_clear))
        }
    }
}
