package de.joinnoah.pi.remote

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics

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
    cell: (DiffLine?) -> Modifier = { Modifier },
) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        SplitCell(old, old?.oldLine, rowStyle, scroll, wrap, cell(old))
        VerticalDivider(Modifier.fillMaxHeight())
        SplitCell(new, new?.newLine, rowStyle, scroll, wrap, cell(new))
    }
}

@Composable
private fun RowScope.SplitCell(line: DiffLine?, number: Int?, rowStyle: DiffRowStyle, scroll: ScrollState, wrap: Boolean, modifier: Modifier) {
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
            .semantics(mergeDescendants = true) {}
    ) {
        if (rowStyle.numbered) GutterNumber(number, rowStyle.numberWidth, rowStyle.text, rowStyle.muted)
        if (line != null) DiffLineText(line, diffPrefix(line.kind), content, rowStyle, scroll, wrap)
    }
}

/** The empty side of an unpaired line: a faint tint, so the eye sees the gap. */
private const val EMPTY_SIDE_ALPHA = 0.06f
