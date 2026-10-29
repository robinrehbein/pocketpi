package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PullRequestChipTest {
    private val resources get() = RuntimeEnvironment.getApplication().resources

    private fun pr(
        draft: Boolean = false,
        review: PullRequestReview = PullRequestReview.NONE,
        checks: PullRequestChecksState = PullRequestChecksState.NONE,
        failed: Int = 0,
    ) = PullRequestStatus(123, "https://github.com/o/r/pull/123", draft, review, checks, failed, emptyList())

    @Test
    fun failingChecksAreRedAndLeadTheCompactRow() {
        val model = pullRequestChipModel(pr(checks = PullRequestChecksState.DONE, failed = 2))
        assertEquals(123L, model.number)
        assertEquals(PullRequestChecksWord.Failing(2), model.checks)
        assertEquals(PullRequestTone.ERROR, model.tone)
        assertTrue(model.needsAttention)
    }

    @Test
    fun failureWinsOverStillRunningChecks() {
        val model = pullRequestChipModel(pr(checks = PullRequestChecksState.RUNNING, failed = 1))
        assertEquals(PullRequestChecksWord.Failing(1), model.checks)
        assertEquals(PullRequestTone.ERROR, model.tone)
    }

    @Test
    fun runningChecksAreNeutral() {
        val model = pullRequestChipModel(pr(checks = PullRequestChecksState.RUNNING))
        assertEquals(PullRequestChecksWord.Running, model.checks)
        assertNull(model.review)
        assertEquals(PullRequestTone.NEUTRAL, model.tone)
        assertFalse(model.needsAttention)
    }

    @Test
    fun passedChecksAreGreenAndApprovalIsSaidInWords() {
        val passed = pullRequestChipModel(pr(checks = PullRequestChecksState.DONE))
        assertEquals(PullRequestChecksWord.Passed, passed.checks)
        assertEquals(PullRequestTone.SUCCESS, passed.tone)
        val approved = pullRequestChipModel(pr(review = PullRequestReview.APPROVED, checks = PullRequestChecksState.DONE))
        assertEquals(PullRequestReviewWord.APPROVED, approved.review)
        assertEquals(PullRequestTone.SUCCESS, approved.tone)
    }

    @Test
    fun noChecksHaveNoWordAndAreGreenUnlessAReviewIsOutstanding() {
        val none = pullRequestChipModel(pr())
        assertNull(none.checks)
        assertNull(none.review)
        assertEquals(PullRequestTone.SUCCESS, none.tone)
        val required = pullRequestChipModel(pr(review = PullRequestReview.REVIEW_REQUIRED))
        assertEquals(PullRequestReviewWord.REVIEW_REQUIRED, required.review)
        assertEquals(PullRequestTone.NEUTRAL, required.tone)
    }

    @Test
    fun draftIsMutedAndSaidInWordsEvenWithFailingChecks() {
        val model =
            pullRequestChipModel(
                pr(draft = true, review = PullRequestReview.APPROVED, checks = PullRequestChecksState.DONE, failed = 1)
            )
        assertEquals(PullRequestReviewWord.DRAFT, model.review)
        assertEquals(PullRequestChecksWord.Failing(1), model.checks)
        assertEquals(PullRequestTone.MUTED, model.tone)
        // The tone stays muted, but failing checks still move the pill first in the compact row.
        assertTrue(model.needsAttention)
    }

    @Test
    fun aQuietDraftNeedsNoAttention() {
        val model = pullRequestChipModel(pr(draft = true, checks = PullRequestChecksState.DONE))
        assertEquals(PullRequestTone.MUTED, model.tone)
        assertFalse(model.needsAttention)
    }

    @Test
    fun aDraftWithRequestedChangesNeedsAttention() {
        val model = pullRequestChipModel(pr(draft = true, review = PullRequestReview.CHANGES_REQUESTED))
        assertEquals(PullRequestReviewWord.DRAFT, model.review)
        assertTrue(model.needsAttention)
    }

    @Test
    fun runningChecksStayNeutralWhenApprovedOrWaitingForReview() {
        val approved = pullRequestChipModel(pr(review = PullRequestReview.APPROVED, checks = PullRequestChecksState.RUNNING))
        assertEquals(PullRequestTone.NEUTRAL, approved.tone)
        assertFalse(approved.needsAttention)
        val required = pullRequestChipModel(pr(review = PullRequestReview.REVIEW_REQUIRED, checks = PullRequestChecksState.RUNNING))
        assertEquals(PullRequestTone.NEUTRAL, required.tone)
        assertFalse(required.needsAttention)
    }

    @Test
    fun theTextListsNumberChecksAndReviewInThatOrderSeparatedByMiddleDots() {
        val text = pullRequestChipText(
            pullRequestChipModel(pr(review = PullRequestReview.CHANGES_REQUESTED, checks = PullRequestChecksState.DONE, failed = 2)),
            resources,
        )
        assertEquals("PR #123 · 2 failing · changes requested", text)
        val running = pullRequestChipText(
            pullRequestChipModel(pr(review = PullRequestReview.APPROVED, checks = PullRequestChecksState.RUNNING)),
            resources,
        )
        assertEquals("PR #123 · checks running · approved", running)
        assertEquals("PR #123", pullRequestChipText(pullRequestChipModel(pr()), resources))
        assertEquals(
            "PR #123 · checks passed · review required",
            pullRequestChipText(
                pullRequestChipModel(pr(review = PullRequestReview.REVIEW_REQUIRED, checks = PullRequestChecksState.DONE)),
                resources,
            ),
        )
    }

    @Test
    fun theDescriptionIsSpokenAndPluralAware() {
        fun description(failed: Int, draft: Boolean = false) =
            pullRequestChipDescription(pullRequestChipModel(pr(draft = draft, checks = PullRequestChecksState.DONE, failed = failed)), resources)
        assertEquals("Pull request 123, 1 check failing", description(1))
        assertEquals("Pull request 123, 2 checks failing, draft", description(2, draft = true))
        assertEquals("PR #123 · 1 failing", pullRequestChipText(pullRequestChipModel(pr(checks = PullRequestChecksState.DONE, failed = 1)), resources))
    }

    @Test
    fun changesRequestedAreRedWithoutAnyCheckWord() {
        val model = pullRequestChipModel(pr(review = PullRequestReview.CHANGES_REQUESTED))
        assertNull(model.checks)
        assertEquals(PullRequestReviewWord.CHANGES_REQUESTED, model.review)
        assertEquals(PullRequestTone.ERROR, model.tone)
        assertTrue(model.needsAttention)
    }
}
