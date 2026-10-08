package de.joinnoah.pi.remote

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun MarkdownText(text: String) {
    val linkColor = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        remember(text) { markdownSegments(text) }.forEach { segment ->
            val part = segment.text
            if (segment.code) {
                val code = part.trimEnd()
                // Only assistant text has a source; a diagram elsewhere stays the code it is.
                if (segment.isMermaid && LocalProjectImageSource.current != null) MermaidCard(code) { CodeBlock(it) }
                else CodeBlock(code)
            } else
                remember(part) { markdownBlocks(part) }.forEach { block ->
                    if (block is MarkdownBlock.Table) {
                        MarkdownTable(block)
                        return@forEach
                    }
                    if (block is MarkdownBlock.Image) {
                        val source = LocalProjectImageSource.current
                        if (source != null) ProjectImageCard(block.alt, block.path, source)
                        else Text(
                            remember(block, linkColor) { inlineMarkdown(markdownImageAltText(block), linkColor) },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        return@forEach
                    }
                    if (block is MarkdownBlock.Artifact) {
                        val source = LocalProjectImageSource.current
                        if (source != null) {
                            ProjectArtifactCard(block.path, source)
                            return@forEach
                        }
                    }
                    val line = (block as? MarkdownBlock.Artifact)?.line ?: (block as MarkdownBlock.Line).text
                    val level =
                        line
                            .takeWhile { it == '#' }
                            .length
                            .takeIf { it in 1..6 && line.getOrNull(it) == ' ' }
                    val content =
                        if (level != null) line.drop(level + 1)
                        else line.replace(markdownBulletRegex, "• ")
                    if (line.trim() == "---") HorizontalDivider()
                    else if (content.isNotEmpty())
                        Text(
                            remember(content, linkColor) {
                                inlineMarkdown(content, linkColor)
                            },
                            style =
                                when (level) {
                                    1 -> MaterialTheme.typography.headlineSmall
                                    2 -> MaterialTheme.typography.titleLarge
                                    null -> MaterialTheme.typography.bodyLarge
                                    else -> MaterialTheme.typography.titleMedium
                                },
                            modifier =
                                if (level != null) Modifier.semantics { heading() } else Modifier,
                        )
                }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    val clipboard = LocalClipboardManager.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                code,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Text(stringResource(R.string.remote_copy_code))
            }
        }
    }
}

private val markdownBulletRegex = Regex("^[-*] ")

/** What an image block shows where no image loader exists: its description, else the file name. */
internal fun markdownImageAltText(image: MarkdownBlock.Image): String =
    image.alt.ifBlank { image.path.substringAfterLast('/') }
