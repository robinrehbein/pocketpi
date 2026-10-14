package de.joinnoah.pi.remote

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/*
 * A split diff: the old side on the left and the new side on the right, built from the same patch
 * as the unified diff. Within a hunk, a run of removed lines and the run of added lines right after
 * it pair up row by row; the longer run leaves the other side empty. Context lines and hunk
 * headers fill both sides. A patch the pairing cannot show faithfully stays unified.
 */

/**
 * One row of a split diff as indices into the unified lines: [old] for the left side, [new] for
 * the right. A context line or a hunk header is the same index on both sides; null leaves a side
 * empty.
 */
internal data class SplitRow(val old: Int?, val new: Int?)

/** A longer patch stays unified: its pairing and two measured columns per row cost too much. */
internal const val MAX_SPLIT_DIFF_LINES = 20_000

/**
 * Headers of patches whose two sides do not line up: binary content, and renames and copies,
 * whose old side belongs to another path. Hunk lines start with ' ', '+', '-' or '\', so none of
 * these can be part of the content.
 */
private val UNSPLITTABLE_HEADERS = listOf("Binary files ", "GIT binary patch", "rename from ", "copy from ", "similarity index ")

/**
 * The split rows of [lines], parsed from [patch] by [patchDiff], or null where the diff stays
 * unified: binary content, a rename or copy, nothing to show, or more than [MAX_SPLIT_DIFF_LINES].
 */
internal fun splitDiff(patch: String, lines: List<DiffLine>): List<SplitRow>? {
    if (lines.isEmpty() || lines.size > MAX_SPLIT_DIFF_LINES) return null
    if (patch.lineSequence().any { line -> UNSPLITTABLE_HEADERS.any(line::startsWith) }) return null
    return splitDiffRows(lines)
}

/**
 * Pairs each run of removed lines with the run of added lines that follows it. An added run with
 * no removed run before it, or a removed run with nothing after it, fills one side only. "No
 * newline at end of file" markers never reach [lines], so they do not split a pair.
 */
internal fun splitDiffRows(lines: List<DiffLine>): List<SplitRow> {
    val rows = ArrayList<SplitRow>(lines.size)
    val removed = ArrayList<Int>()
    val added = ArrayList<Int>()
    fun flush() {
        for (k in 0 until maxOf(removed.size, added.size)) rows += SplitRow(removed.getOrNull(k), added.getOrNull(k))
        removed.clear()
        added.clear()
    }
    for ((index, line) in lines.withIndex()) {
        when (line.kind) {
            // A removed line after added ones starts the next pair.
            DiffKind.REMOVED -> {
                if (added.isNotEmpty()) flush()
                removed += index
            }
            DiffKind.ADDED -> added += index
            DiffKind.CONTEXT,
            DiffKind.HUNK -> {
                flush()
                rows += SplitRow(index, index)
            }
        }
    }
    flush()
    return rows
}

/**
 * One item of a split diff: a hunk header across both sides, or an old and a new line side by
 * side. [cell] decorates each line's cell or the header with the line's index into [lines], for
 * example to make it commentable; [below] adds what goes under a line, such as its comment.
 *
 * The code columns share [scroll], so both sides move sideways together. A hunk header is wider
 * than either column: sharing [scroll] would let its measurement lower the shared maximum and
 * snap the columns back, leaving the end of long lines out of reach. It scrolls on its own.
 */
@Composable
internal fun SplitDiffItem(
    row: SplitRow,
    lines: List<DiffLine>,
    rowStyle: DiffRowStyle,
    scroll: ScrollState,
    wrap: Boolean,
    cell: (DiffLine, Int) -> Modifier = { _, _ -> Modifier },
    leading: @Composable (DiffLine, Int) -> Unit = { _, _ -> },
    below: @Composable (DiffLine) -> Unit = {},
) {
    val old = row.old?.let(lines::get)
    val new = row.new?.let(lines::get)
    Column(Modifier.testTag("changesSplitRow")) {
        if (old != null && old.kind == DiffKind.HUNK) DiffRow(old, rowStyle, rememberScrollState(), cell(old, checkNotNull(row.old)), wrap)
        else {
            SplitDiffRow(
                old,
                new,
                rowStyle,
                scroll,
                wrap,
                leading = { line ->
                    val index = if (line === old) row.old else row.new
                    if (index != null) leading(line, index)
                },
            ) { line ->
                val index = if (line === old) row.old else row.new
                if (line == null || index == null) Modifier else cell(line, index)
            }
            old?.let { below(it) }
            new?.takeIf { it !== old }?.let { below(it) }
        }
    }
}

/** "Before" over the old side and "After" over the new one, aligned with [SplitDiffRow]'s halves. */
@Composable
internal fun SplitDiffHeading(modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("changesSplitHeading")) {
        Text(stringResource(R.string.remote_review_split_old), Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp).semantics { heading() }, color = color, style = style)
        VerticalDivider(Modifier.fillMaxHeight())
        Text(stringResource(R.string.remote_review_split_new), Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp).semantics { heading() }, color = color, style = style)
    }
}

/**
 * One row of a split diff: the old line with its old number on the left, the new line with its
 * new number on the right, divided by a line. [cell] decorates each side's cell, for example to
 * make it commentable; it gets the side's line, or null for an empty side.
 */
@Composable
internal fun SplitDiffRow(
    old: DiffLine?,
    new: DiffLine?,
    rowStyle: DiffRowStyle,
    scroll: ScrollState,
    wrap: Boolean,
    modifier: Modifier = Modifier,
    leading: @Composable (DiffLine) -> Unit = {},
    cell: (DiffLine?) -> Modifier = { Modifier },
) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        SplitCell(old, old?.oldLine, rowStyle, scroll, wrap, cell(old), leading)
        VerticalDivider(Modifier.fillMaxHeight())
        SplitCell(new, new?.newLine, rowStyle, scroll, wrap, cell(new), leading)
    }
}

@Composable
private fun RowScope.SplitCell(
    line: DiffLine?,
    number: Int?,
    rowStyle: DiffRowStyle,
    scroll: ScrollState,
    wrap: Boolean,
    modifier: Modifier,
    leading: @Composable (DiffLine) -> Unit,
) {
    val (container, content) =
        when (line?.kind) {
            DiffKind.ADDED -> (rowStyle.addedContainer as Color?) to rowStyle.addedContent
            DiffKind.REMOVED -> rowStyle.removedContainer to rowStyle.removedContent
            null -> rowStyle.muted.copy(alpha = EMPTY_SIDE_ALPHA) to rowStyle.muted
            else -> null to LocalContentColor.current
        }
    Row(
        Modifier.weight(1f)
            .fillMaxHeight()
            .then(if (container != null) Modifier.background(container) else Modifier)
            .then(modifier)
            // TalkBack reads whether the line was added or removed, with this side's number.
            .semantics(mergeDescendants = true) {
                line?.let { diffLineDescription(it, rowStyle.labels, number) }?.let { contentDescription = it }
            }
    ) {
        if (line != null) leading(line)
        if (rowStyle.numbered) GutterNumber(number, rowStyle.numberWidth, rowStyle.text, rowStyle.muted)
        if (line != null) DiffLineText(line, diffPrefix(line.kind), content, rowStyle, scroll, wrap)
    }
}

/** The empty side of an unpaired line: a faint tint, so the eye sees the gap. */
private const val EMPTY_SIDE_ALPHA = 0.06f
