package de.joinnoah.pi.remote

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Rows wider than this are cut on screen; the soft split in [outputLines] keeps output below it.
 * It also bounds the row width: 2000 glyphs of one em each at 12sp, font scale 2 and density 3.5
 * come to 168000 px, below [MAX_CODE_CONTENT_WIDTH_PX].
 */
internal const val MAX_CODE_LINE_CHARS = 2000

/**
 * The widest a code row may be laid out. Compose constraints cannot encode much more than 262142
 * px, and a fixed width above that throws, so the content width is clamped well below it.
 */
internal const val MAX_CODE_CONTENT_WIDTH_PX = 200_000

/** [widthPx] limited to [MAX_CODE_CONTENT_WIDTH_PX], never negative. */
internal fun clampCodeContentWidthPx(widthPx: Float): Float = widthPx.coerceIn(0f, MAX_CODE_CONTENT_WIDTH_PX.toFloat())

@Composable
internal fun monoTextStyle(): TextStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

/** The advance of one monospace character in [style]. */
@Composable
internal fun rememberMonoCharWidth(style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, style, density) {
        with(density) { (measurer.measure("0000000000", style).size.width / 10f).toDp() }
    }
}

/**
 * One width for every row of a code block, so rows that share a horizontal [ScrollState] also
 * share its maximum. Proportional fallback glyphs (CJK, emoji) can be wider than the monospace
 * advance, so the longest row is measured as well.
 */
@Composable
internal fun rememberCodeContentWidth(texts: List<String>, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charWidth = rememberMonoCharWidth(style)
    return remember(texts, measurer, style, density, charWidth) {
        var longest = ""
        for (text in texts) if (text.length > longest.length) longest = text
        val clipped = longest.take(MAX_CODE_LINE_CHARS)
        val measured =
            if (clipped.isEmpty()) 0.dp
            else with(density) { measurer.measure(clipped, style, softWrap = false, maxLines = 1).size.width.toDp() }
        val width = maxOf(charWidth * clipped.length, measured) + 8.dp
        with(density) { clampCodeContentWidthPx(width.toPx()).toDp() }
    }
}

private fun digits(value: Int): Int = value.coerceAtLeast(1).toString().length

/** Measurements and colors every row of one diff shares, so rows line up in a plain or lazy list. */
@Immutable
internal class DiffRowStyle(
    val text: TextStyle,
    val charWidth: Dp,
    val numberWidth: Dp,
    val contentWidth: Dp,
    val numbered: Boolean,
    val muted: Color,
    val addedContainer: Color,
    val addedContent: Color,
    val removedContainer: Color,
    val removedContent: Color,
)

@Composable
internal fun rememberDiffRowStyle(lines: List<DiffLine>, showLineNumbers: Boolean = true): DiffRowStyle {
    val style = monoTextStyle()
    val texts = remember(lines) { lines.map { it.text } }
    val contentWidth = rememberCodeContentWidth(texts, style)
    val charWidth = rememberMonoCharWidth(style)
    val numbered = showLineNumbers && lines.any { it.oldLine != null || it.newLine != null }
    val numberWidth =
        remember(lines, charWidth) {
            charWidth * digits(lines.maxOfOrNull { maxOf(it.oldLine ?: 0, it.newLine ?: 0) } ?: 0)
        }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val addedContainer = diffAddedContainer()
    val addedContent = diffAddedContent()
    val removedContainer = diffRemovedContainer()
    val removedContent = diffRemovedContent()
    return remember(
        style, charWidth, numberWidth, contentWidth, numbered, muted,
        addedContainer, addedContent, removedContainer, removedContent,
    ) {
        DiffRowStyle(
            style,
            charWidth,
            numberWidth,
            contentWidth,
            numbered,
            muted,
            addedContainer,
            addedContent,
            removedContainer,
            removedContent,
        )
    }
}

/**
 * One diff line: old and new line numbers, a '+', '-' or ' ' prefix, and the text. Rows of one
 * diff share [scroll] so they move sideways together. An empty hunk line is a divider.
 */
@Composable
internal fun DiffRow(
    line: DiffLine,
    rowStyle: DiffRowStyle,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
) {
    if (line.kind == DiffKind.HUNK && line.text.isEmpty()) {
        HorizontalDivider(modifier.padding(vertical = 4.dp))
        return
    }
    val (container, content) =
        when (line.kind) {
            DiffKind.ADDED -> (rowStyle.addedContainer as Color?) to rowStyle.addedContent
            DiffKind.REMOVED -> rowStyle.removedContainer to rowStyle.removedContent
            DiffKind.HUNK -> null to rowStyle.muted
            DiffKind.CONTEXT -> null to LocalContentColor.current
        }
    val prefix =
        when (line.kind) {
            DiffKind.ADDED -> "+"
            DiffKind.REMOVED -> "-"
            DiffKind.CONTEXT -> " "
            DiffKind.HUNK -> ""
        }
    Row(modifier.fillMaxWidth().then(if (container != null) Modifier.background(container) else Modifier).semantics(mergeDescendants = true) {}) {
        if (rowStyle.numbered) {
            GutterNumber(line.oldLine, rowStyle.numberWidth, rowStyle.text, rowStyle.muted)
            GutterNumber(line.newLine, rowStyle.numberWidth, rowStyle.text, rowStyle.muted)
        }
        Text(
            prefix,
            Modifier.width(rowStyle.charWidth + 8.dp).padding(start = 4.dp),
            color = content,
            style = rowStyle.text,
            maxLines = 1,
        )
        Box(Modifier.weight(1f).horizontalScroll(scroll)) {
            Text(
                line.text.take(MAX_CODE_LINE_CHARS),
                Modifier.width(rowStyle.contentWidth),
                color = content,
                style = rowStyle.text,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/**
 * A unified diff. The gutter shows old and new line numbers and a '+', '-' or ' ' prefix, so the
 * meaning never rests on color alone. It renders plain rows (no lazy list), so it can sit inside
 * an item of a vertical LazyColumn; [maxLines] bounds the work for long diffs. A diff of its own
 * screen uses [DiffRow] in a lazy list instead.
 */
@Composable
internal fun DiffView(
    lines: List<DiffLine>,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    showLineNumbers: Boolean = true,
) {
    val shown = remember(lines, maxLines) { if (lines.size > maxLines) lines.take(maxLines.coerceAtLeast(0)) else lines }
    val hidden = lines.size - shown.size
    val rowStyle = rememberDiffRowStyle(shown, showLineNumbers)
    val scroll = rememberScrollState()
    Column(modifier.testTag("diffView")) {
        for (line in shown) DiffRow(line, rowStyle, scroll)
        if (hidden > 0)
            Text(
                pluralStringResource(R.plurals.remote_tool_detail_more_lines, hidden, hidden),
                Modifier.padding(top = 4.dp, start = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = rowStyle.muted,
            )
    }
}

@Composable
private fun GutterNumber(number: Int?, width: Dp, style: TextStyle, color: Color) {
    Text(
        number?.toString().orEmpty(),
        Modifier.width(width + 8.dp).padding(start = 4.dp, end = 4.dp),
        color = color,
        style = style,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}
