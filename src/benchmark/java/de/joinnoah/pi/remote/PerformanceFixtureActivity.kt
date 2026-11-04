package de.joinnoah.pi.remote

import android.os.Bundle
import android.os.Trace
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Synthetic, relay-free Markdown workload. Included only in the isolated benchmark APK. */
class PerformanceFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { PerformanceFixture() } }
    }
}

private val fixtureTranscripts = List(2) { session ->
    List(180) { message ->
        """
        ## Session $session — message $message
        A deterministic **Markdown** response with *emphasis* and a [link](https://example.com).

        | File | Change |
        | --- | --- |
        | example.kt | +12 / -4 |
        | README.md | +3 / -1 |

        ```kotlin
        fun example(value: Int): Int = value * 2
        ```
        """.trimIndent()
    }
}

@Composable
private fun PerformanceFixture() {
    var session by remember { mutableIntStateOf(0) }
    var streaming by remember { mutableStateOf(false) }
    var streamCompleted by remember { mutableStateOf(false) }
    var streamedText by remember { mutableStateOf("## Streaming response\n") }
    LaunchedEffect(streaming) {
        if (!streaming) return@LaunchedEffect
        repeat(120) { chunk ->
            delay(16)
            Trace.beginSection("PocketPi.fixture.streamAppend")
            try {
                streamedText += "Chunk $chunk with **bold**, `code`, and [a link](https://example.com).\n"
            } finally {
                Trace.endSection()
            }
        }
        streaming = false
        streamCompleted = true
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.performance_fixture_title))
            Row {
                Button(
                    onClick = { session = 1 - session },
                    enabled = !streaming,
                    modifier = Modifier.semantics { contentDescription = "fixture-switch" },
                ) { Text(stringResource(R.string.performance_fixture_switch)) }
                Button(
                    onClick = {
                        streamedText = "## Streaming response\n"
                        streamCompleted = false
                        streaming = true
                    },
                    enabled = !streaming,
                    modifier = Modifier.semantics { contentDescription = "fixture-stream" },
                ) { Text(stringResource(R.string.performance_fixture_stream)) }
            }
            Text(
                stringResource(R.string.performance_fixture_session, session),
                modifier = Modifier.semantics {
                    contentDescription = "fixture-session-$session"
                },
            )
            LazyColumn(
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = when {
                        streaming -> "fixture-streaming"
                        streamCompleted -> "fixture-stream-complete"
                        else -> "fixture-idle"
                    }
                },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "stream") { MarkdownText(streamedText) }
                items(180, key = { "$session-$it" }) { index ->
                    MarkdownText(fixtureTranscripts[session][index])
                }
            }
        }
    }
}
