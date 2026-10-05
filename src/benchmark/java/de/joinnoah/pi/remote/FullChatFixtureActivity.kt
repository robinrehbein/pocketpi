package de.joinnoah.pi.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Benchmark-only full production chat screen; no live repository or transport is attached. */
class FullChatFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = FullChatFixtureRepository()
        val settings = FixtureSettings()
        setContent {
            MaterialTheme(colorScheme = remoteColorScheme(false), typography = RemoteTypography) {
                val progress by repository.progress.collectAsState()
                val scope = rememberCoroutineScope()
                val stack = remember { mutableStateListOf<NavKey>().apply { addAll(fullChatKey.selection().keys()) } }
                val navigator = remember { RemoteNavigator(repository, stack, scope) }
                val model = viewModel { ChatViewModel(repository, fullChatKey, settings) }
                val visibility = remember { TimelineVisibility() }
                Column(Modifier.fillMaxSize().statusBarsPadding().semantics { testTagsAsResourceId = true }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            stringResource(R.string.performance_full_chat_title),
                            modifier = Modifier.semantics { contentDescription = "full-chat-$progress" },
                        )
                        Button(
                            onClick = {
                                if (progress == "checkpoint") repository.continueStream()
                                else lifecycleScope.launch { repository.stream() }
                            },
                            enabled = progress == "ready" || progress == "checkpoint",
                            modifier = Modifier.semantics {
                                contentDescription = if (progress == "checkpoint") "full-chat-continue" else "full-chat-stream"
                            },
                        ) {
                            Text(stringResource(if (progress == "checkpoint")
                                R.string.performance_full_chat_continue else R.string.performance_fixture_stream))
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        RemoteScreen(fullChatKey, model, navigator, false, false, {}, visibility)
                    }
                }
            }
        }
    }
}

private class FixtureSettings : SettingsRepository {
    override val state = MutableStateFlow(RemoteSettings(theme = "light", thinkingDisplay = "text"))
    override fun setTheme(theme: String) {}
    override fun setPushEnabled(enabled: Boolean) {}
    override fun setThinkingDisplay(display: String) {}
    override fun setHideOfflineSessions(hide: Boolean) {}
    override fun setSwipeEndToStart(action: SwipeAction) {}
    override fun setSwipeStartToEnd(action: SwipeAction) {}
    override fun setEnterSends(enabled: Boolean) {}
}
