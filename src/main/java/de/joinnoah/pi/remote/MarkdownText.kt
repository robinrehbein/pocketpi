package de.joinnoah.pi.remote

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The colour of links in the message being drawn. Outgoing bubbles set theirs; others use primary. */
internal val LocalMarkdownLinkColor = compositionLocalOf<Color?> { null }

/**
 * Whether a code block drags out as text. A tool card turns it off, because there a long press
 * opens the output instead of starting a drag.
 */
internal val LocalCodeBlockDrag = compositionLocalOf { true }

/** Code blocks longer than this many lines stay plain, so a long streamed block is not re-highlighted each update. */
private const val MAX_CHAT_HIGHLIGHT_LINES = 1000

/** Nesting step and hanging indent of list items, in sp so they scale with the text beside them. */
private const val LIST_STEP_SP = 20

@Composable
fun MarkdownText(text: String) {
    val linkColor = LocalMarkdownLinkColor.current ?: MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    // In a chat, a `path:line` opens the file in Files.
    val files = LocalFileOpener.current
    val artifactCards = LocalProjectImageSource.current != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        remember(text) { markdownSegments(text) }.forEach { segment ->
            val part = segment.text
            if (segment.code) {
                val code = part.trimEnd()
                // Only assistant text has a source; a diagram elsewhere stays the code it is.
                if (segment.isMermaid && artifactCards) MermaidCard(code) { CodeBlock(it, segment.language) }
                else CodeBlock(code, segment.language, segment.closed)
            } else
                remember(part, artifactCards) { markdownRuns(markdownBlocks(part), artifactCards) }.forEach { run ->
                    MarkdownRunView(run, linkColor, codeBackground, files)
                }
        }
    }
}

@Composable
private fun MarkdownRunView(run: MarkdownRun, linkColor: Color, codeBackground: Color, files: FileOpener?) {
    when (run) {
        is MarkdownRun.Block ->
            when (val block = run.block) {
                is MarkdownBlock.Table -> MarkdownTable(block)
                is MarkdownBlock.Image -> {
                    val source = LocalProjectImageSource.current
                    if (source != null) ProjectImageCard(block.alt, block.path, source)
                    else Text(
                        remember(block, linkColor) { inlineMarkdown(markdownImageAltText(block), linkColor) },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                is MarkdownBlock.Artifact -> LocalProjectImageSource.current?.let { ProjectArtifactCard(block.path, it) }
                is MarkdownBlock.Line -> Unit
            }
        is MarkdownRun.Heading ->
            LinkMenuText(
                remember(run.text, linkColor, files, codeBackground) { inlineMarkdown(run.text, linkColor, files, codeBackground) },
                style =
                    when (run.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                modifier = Modifier.widthIn(max = PROSE_READING_WIDTH).semantics { heading() },
            )
        MarkdownRun.Rule -> HorizontalDivider()
        is MarkdownRun.Paragraph ->
            LinkMenuText(
                remember(run.lines, linkColor, files, codeBackground) { linesText(run.lines, linkColor, files, codeBackground) },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.widthIn(max = PROSE_READING_WIDTH),
            )
        is MarkdownRun.Items -> MarkdownListItems(run.items, linkColor, files, codeBackground)
        is MarkdownRun.Quote ->
            Row(Modifier.widthIn(max = PROSE_READING_WIDTH).height(IntrinsicSize.Min)) {
                Box(
                    Modifier.width(3.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(12.dp))
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    LinkMenuText(
                        remember(run.lines, linkColor, files, codeBackground) { linesText(run.lines, linkColor, files, codeBackground) },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
    }
}

/** [lines] as one text, each line styled on its own and kept on its own line. */
private fun linesText(lines: List<String>, linkColor: Color, files: FileOpener?, codeBackground: Color): AnnotatedString =
    buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            if (index > 0) append('\n')
            append(inlineMarkdown(line, linkColor, files, codeBackground))
        }
    }

/**
 * List items, one row each: the bullet or task marker in a column one [LIST_STEP_SP] wide, then the
 * item's text, so wrapped lines hang under the text and not under the bullet. A nested item steps in
 * by its depth. Rows are not paragraph styles: a styled paragraph here added a blank line after each
 * item. Each row is also its own accessibility node, so TalkBack moves from item to item.
 */
@Composable
private fun MarkdownListItems(items: List<MarkdownListItem>, linkColor: Color, files: FileOpener?, codeBackground: Color) {
    val step = with(LocalDensity.current) { LIST_STEP_SP.sp.toDp() }
    Column(Modifier.widthIn(max = PROSE_READING_WIDTH)) {
        items.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(start = step * item.depth)) {
                Text(item.marker, Modifier.width(step), style = MaterialTheme.typography.bodyLarge)
                LinkMenuText(
                    remember(item.text, linkColor, files, codeBackground) { inlineMarkdown(item.text, linkColor, files, codeBackground) },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, language: String? = null, closed: Boolean = true) {
    val copyLabel = stringResource(R.string.remote_copy_code)
    val copy = rememberCopyToClipboard(copyLabel)
    val syntax = fenceLanguage(language)
    val colors = syntaxColors()
    // Kept across the updates of a streamed block, so each one styles only the lines that arrived since the last.
    val highlighter = remember(syntax, colors) { syntax?.let { IncrementalHighlighter(it, colors) } }
    val styled = remember(code, highlighter, closed) {
        val lines = code.lines()
        // A line the highlighter would cut stays plain, so what is shown is always the whole code.
        if (highlighter == null || lines.size > MAX_CHAT_HIGHLIGHT_LINES || lines.any { it.length > MAX_HIGHLIGHT_LINE_CHARS })
            AnnotatedString(code)
        else {
            // The last line of an open block is still arriving, so it stays plain until the next line starts.
            val complete = if (closed) lines else lines.dropLast(1)
            buildAnnotatedString {
                highlighter.update(complete).forEachIndexed { index, line ->
                    if (index > 0) append('\n')
                    append(line)
                }
                if (complete.size < lines.size) {
                    if (complete.isNotEmpty()) append('\n')
                    append(lines.last())
                }
            }
        }
    }
    val label = language?.trim()?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.remote_code_block)
    val dragLabel = stringResource(R.string.remote_drag_code)
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("codeBlock"),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Only this handle drags the block out as plain text. The code beside it has no drag source,
                // so a long press there selects text as it does everywhere else in the message.
                if (LocalCodeBlockDrag.current)
                    Box(
                        Modifier.size(48.dp).dragAndDropSource { _ -> codeDragTransfer(code, label) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.DragIndicator,
                            dragLabel,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                IconButton(onClick = { copy(code) }) {
                    Icon(Icons.Outlined.ContentCopy, copyLabel, Modifier.size(18.dp))
                }
            }
            Text(
                styled,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * What dragging a code block carries: its text, labelled with the language. The text is cut as a
 * copy is, so a very large block cannot overflow the drag's transfer.
 */
internal fun codeDragTransfer(code: String, label: String): DragAndDropTransferData =
    DragAndDropTransferData(ClipData.newPlainText(label, clipboardSafeText(code).first))

/** What an image block shows where no image loader exists: its description, else the file name. */
internal fun markdownImageAltText(image: MarkdownBlock.Image): String =
    image.alt.ifBlank { image.path.substringAfterLast('/') }
