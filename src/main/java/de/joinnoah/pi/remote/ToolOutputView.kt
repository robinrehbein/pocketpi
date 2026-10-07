package de.joinnoah.pi.remote

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
    if (name in setOf("read", "bash", "edit", "write") || path != null) return OutputFormat.SOURCE
    if (text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) return OutputFormat.SOURCE
    if (text.startsWith("diff --git") || text.startsWith("@@") || text.contains("\u001b[")) return OutputFormat.SOURCE
    if (text.contains("```") || Regex("(?m)^#{1,6} |^[-*] |^\\|.*\\|").containsMatchIn(text)) return OutputFormat.MARKDOWN
    return OutputFormat.TEXT
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ToolOutputView(text: String, name: String? = null, arguments: String? = null) {
    var peek by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val format = remember(name, arguments, text) { outputFormat(name, arguments, text) }
    val preview = remember(text) { text.lineSequence().take(24).joinToString("\n").take(6000) }
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { peek = true })) {
        OutputContent(preview, format)
        TextButton(onClick = { peek = true }) { Text(stringResource(R.string.remote_output_peek)) }
    }
    if (peek) Dialog(onDismissRequest = { peek = false }) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(16.dp)) {
                Text(stringResource(R.string.remote_tool_output), style = MaterialTheme.typography.titleLarge)
                Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                    SelectionContainer { OutputContent(text, format) }
                }
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                        Text(stringResource(R.string.remote_copy_code))
                    }
                    TextButton(onClick = { peek = false }) { Text(stringResource(R.string.remote_output_close)) }
                }
            }
        }
    }
}

@Composable
private fun OutputContent(text: String, format: OutputFormat) {
    when (format) {
        OutputFormat.MARKDOWN -> MarkdownText(text)
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

