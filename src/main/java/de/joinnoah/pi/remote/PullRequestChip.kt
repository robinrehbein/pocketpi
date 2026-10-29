package de.joinnoah.pi.remote

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The colour of the pull request pill; the words carry the same state, so it reads without colour. */
internal enum class PullRequestTone { ERROR, SUCCESS, NEUTRAL, MUTED }

/** The checks part of the pill; null for a pull request without checks. */
internal sealed interface PullRequestChecksWord {
    data class Failing(val count: Int) : PullRequestChecksWord

    data object Running : PullRequestChecksWord

    data object Passed : PullRequestChecksWord
}

/** The review part of the pill; a draft stands in for the review decision. */
internal enum class PullRequestReviewWord(@StringRes val label: Int) {
    DRAFT(R.string.remote_pr_review_draft),
    APPROVED(R.string.remote_pr_review_approved),
    CHANGES_REQUESTED(R.string.remote_pr_review_changes_requested),
    REVIEW_REQUIRED(R.string.remote_pr_review_required),
}

/** What the session pill says about a pull request, before it is turned into text. */
internal data class PullRequestChipModel(
    val number: Long,
    val checks: PullRequestChecksWord?,
    val review: PullRequestReviewWord?,
    val tone: PullRequestTone,
) {
    /** Failed checks or requested changes need attention, as in the host's footer. */
    val needsAttention: Boolean get() = tone == PullRequestTone.ERROR
}

/**
 * The pill of [pr]. The tone follows pi's footer: a draft is muted, failed checks or requested
 * changes are red, finished checks without an outstanding review are green, anything else is neutral.
 */
internal fun pullRequestChipModel(pr: PullRequestStatus): PullRequestChipModel {
    val checks =
        when {
            pr.failedChecks > 0 -> PullRequestChecksWord.Failing(pr.failedChecks)
            pr.checks == PullRequestChecksState.RUNNING -> PullRequestChecksWord.Running
            pr.checks == PullRequestChecksState.DONE -> PullRequestChecksWord.Passed
            else -> null
        }
    val review =
        when {
            pr.draft -> PullRequestReviewWord.DRAFT
            pr.review == PullRequestReview.APPROVED -> PullRequestReviewWord.APPROVED
            pr.review == PullRequestReview.CHANGES_REQUESTED -> PullRequestReviewWord.CHANGES_REQUESTED
            pr.review == PullRequestReview.REVIEW_REQUIRED -> PullRequestReviewWord.REVIEW_REQUIRED
            else -> null
        }
    val tone =
        when {
            pr.draft -> PullRequestTone.MUTED
            pr.failedChecks > 0 || pr.review == PullRequestReview.CHANGES_REQUESTED -> PullRequestTone.ERROR
            pr.checks != PullRequestChecksState.RUNNING && pr.review != PullRequestReview.REVIEW_REQUIRED ->
                PullRequestTone.SUCCESS
            else -> PullRequestTone.NEUTRAL
        }
    return PullRequestChipModel(pr.number, checks, review, tone)
}

@Composable
private fun pullRequestChipText(model: PullRequestChipModel): String {
    val parts = buildList {
        add(stringResource(R.string.remote_pr_chip_number, model.number))
        when (val checks = model.checks) {
            is PullRequestChecksWord.Failing ->
                add(pluralStringResource(R.plurals.remote_pr_checks_failing, checks.count, checks.count))
            PullRequestChecksWord.Running -> add(stringResource(R.string.remote_pr_checks_running))
            PullRequestChecksWord.Passed -> add(stringResource(R.string.remote_pr_checks_passed))
            null -> Unit
        }
        model.review?.let { add(stringResource(it.label)) }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun pullRequestToneColor(tone: PullRequestTone): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (tone) {
        PullRequestTone.ERROR -> MaterialTheme.colorScheme.error
        PullRequestTone.SUCCESS -> if (dark) IdleDark else IdleLight
        PullRequestTone.NEUTRAL -> if (dark) WaitingDark else WaitingLight
        PullRequestTone.MUTED -> MaterialTheme.colorScheme.outline
    }
}

/**
 * The pull request pill of the insights row: a dot in the tone, `PR #123` and the state in words.
 * A tap calls [onClick] (it opens the Changes pane); null leaves the pill without an action.
 */
@Composable
internal fun PullRequestChip(pr: PullRequestStatus, onClick: (() -> Unit)?) {
    val model = pullRequestChipModel(pr)
    val text = pullRequestChipText(model)
    FloatingSurface(shape = CircleShape) {
        Row(
            Modifier.testTag("pullRequestChip")
                .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
                .minimumInteractiveComponentSize()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(pullRequestToneColor(model.tone), CircleShape))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
