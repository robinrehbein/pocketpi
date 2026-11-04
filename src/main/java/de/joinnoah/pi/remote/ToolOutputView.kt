package de.joinnoah.pi.remote

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal enum class OutputFormat { SOURCE, MARKDOWN, TEXT }

/** Explicit tool metadata wins over heuristics: source files must remain source files. */
internal fun outputFormat(name: String?, arguments: String?, text: String): OutputFormat {
    val path = runCatching { (Json.parseToJsonElement(arguments ?: "{}") as? JsonObject)?.get("path")?.jsonPrimitive?.contentOrNull }.getOrNull()
    if (name in setOf("read", "bash", USER_BASH_TOOL, "edit", "write") || path != null) return OutputFormat.SOURCE
    if (text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) return OutputFormat.SOURCE
    if (text.startsWith("diff --git") || text.startsWith("@@") || text.contains("\u001b[")) return OutputFormat.SOURCE
    if (text.contains("```") || Regex("(?m)^#{1,6} |^[-*] |^\\|.*\\|").containsMatchIn(text)) return OutputFormat.MARKDOWN
    return OutputFormat.TEXT
}

/**
 * A tool's output in its card, at most 24 lines; Peek shows all of it in [ToolOutputPeek], or calls
 * [onPeek] where the tool's own tab beside the chat shows it instead.
 */
@Composable
internal fun ToolOutputView(text: String, name: String? = null, arguments: String? = null, onPeek: (() -> Unit)? = null) {
    var peek by remember { mutableStateOf(false) }
    val format = remember(name, arguments, text) { outputFormat(name, arguments, text) }
    val preview = remember(text) { text.lineSequence().take(24).joinToString("\n").take(6000) }
    val showAll = onPeek ?: { peek = true }
    val latestShowAll by rememberUpdatedState(showAll)
    // Only a long press opens the peek: a tap does nothing here, so the preview offers TalkBack no
    // click action of its own. The Peek button below is the accessible route. As the long press
    // opens the output, its code blocks do not start a drag.
    Column(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onLongPress = { latestShowAll() }) }) {
        OutputContent(preview, format, draggable = false)
        TextButton(onClick = showAll, modifier = Modifier.testTag("toolOutputPeek")) {
            Text(stringResource(R.string.remote_output_peek))
        }
    }
    if (peek) ToolOutputPeek(text, name, arguments) { peek = false }
}

@Composable
internal fun ToolOutputPeek(text: String, name: String?, arguments: String?, onDismiss: () -> Unit) {
    val copyLabel = stringResource(R.string.remote_copy_code)
    val copy = rememberCopyToClipboard(copyLabel)
    val format = remember(name, arguments, text) { outputFormat(name, arguments, text) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(16.dp)) {
                Text(stringResource(R.string.remote_tool_output), style = MaterialTheme.typography.titleLarge)
                Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                    SelectionContainer { OutputContent(text, format) }
                }
                Row {
                    TextButton(onClick = { copy(text) }) {
                        Text(copyLabel)
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_output_close)) }
                }
            }
        }
    }
}

@Composable
private fun OutputContent(text: String, format: OutputFormat, draggable: Boolean = true) {
    when (format) {
        OutputFormat.MARKDOWN -> CompositionLocalProvider(LocalCodeBlockDrag provides draggable) { MarkdownText(text) }
        OutputFormat.TEXT -> Text(text, style = MaterialTheme.typography.bodyMedium)
        OutputFormat.SOURCE -> Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp)) {
                // A single selectable value preserves all original line separators. The width
                // ceiling safely wraps pathological lines without modifying copied source.
                Text(text, modifier = Modifier.widthIn(max = 2000.dp),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    softWrap = true)
            }
        }
    }
}

