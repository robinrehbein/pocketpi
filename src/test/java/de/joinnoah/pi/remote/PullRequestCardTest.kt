package de.joinnoah.pi.remote

import android.app.Activity
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PullRequestCardTest {
    @get:Rule val compose = createComposeRule()
    private val context: android.content.Context = ApplicationProvider.getApplicationContext()

    private fun pr(
        url: String = "https://github.com/o/r/pull/123",
        failed: Int = 2,
        names: List<String> = listOf("build", "lint"),
        number: Long = 123,
    ) = PullRequestStatus(number, url, false, PullRequestReview.NONE, PullRequestChecksState.DONE, failed, names)

    @Test
    fun theChecksPageIsOnlyForAPlainPullRequestAddress() {
        assertEquals("https://github.com/o/r/pull/123/checks", pullRequestChecksUrl(pr()))
        assertEquals("https://ghe.example/a/b/c/pull/9/checks", pullRequestChecksUrl(pr(url = "https://ghe.example/a/b/c/pull/9", number = 9)))
        listOf(
            "https://github.com/o/r/pull/123?x=1",
            "https://github.com/o/r/pull/123#files",
            "https://github.com/o/r/pull/123/files",
            "https://github.com/o/r/pull/123/",
            "https://github.com/o/r/pull/abc",
            "https://github.com/o/r/issues/123",
            "http://github.com/o/r/pull/123",
            "javascript:alert(1)//o/r/pull/1",
            "https://github.com/o/r/pull/1 2",
        ).forEach { assertNull(it, pullRequestChecksUrl(pr(url = it))) }
    }

    @Test
    fun theChecksPageNeedsTheAddressToNameThePullRequest() {
        assertNull(pullRequestChecksUrl(pr(url = "https://github.com/o/r/pull/999")))
        // Numbers compare as numbers, so leading zeros still match.
        assertEquals("https://github.com/o/r/pull/0123/checks", pullRequestChecksUrl(pr(url = "https://github.com/o/r/pull/0123")))
        assertNull(pullRequestChecksUrl(pr(url = "https://github.com/o/r/pull/99999999999999999999999")))
    }

    @Test
    fun theChecksAddressMustItselfBeOpenable() {
        val prefix = "https://github.com/o/"
        val suffix = "/pull/123"
        fun url(total: Int) = prefix + "a".repeat(total - prefix.length - suffix.length) + suffix
        // The card's own address fits 2048 bytes; with "/checks" it would not.
        assertEquals(2048, url(2048).length)
        assertTrue(validHttpsUrl(url(2048)))
        assertNull(pullRequestChecksUrl(pr(url = url(2048))))
        assertNull(pullRequestChecksUrl(pr(url = url(2042))))
        assertEquals(url(2041) + "/checks", pullRequestChecksUrl(pr(url = url(2041))))
    }

    @Test
    fun theHostShowsUnlessItIsExactlyGithubCom() {
        assertNull(pullRequestHost("https://github.com/o/r/pull/123"))
        assertNull(pullRequestHost("https://GitHub.com/o/r/pull/123"))
        assertEquals("git.example.corp", pullRequestHost("https://Git.Example.Corp/o/r/pull/123"))
        assertEquals("xn--gthub-zsa.com", pullRequestHost("https://gíthub.com/o/r/pull/123"))
        // A Cyrillic "і" looks like a Latin "i"; the ASCII form makes the difference visible.
        assertEquals("xn--gthub-n2e.com", pullRequestHost("https://gіthub.com/o/r/pull/123"))
        assertEquals("github.com.evil.example", pullRequestHost("https://github.com.evil.example/o/r/pull/123"))
    }

    @Test
    fun theCardCountsTheFailingChecksItCannotName() {
        assertEquals(0, pullRequestMoreFailing(pr()))
        assertEquals(3, pullRequestMoreFailing(pr(failed = 5)))
        assertEquals(0, pullRequestMoreFailing(pr(failed = 0, names = emptyList())))
    }

    @Test
    fun theCardShowsNamesAndBothButtons() {
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { PullRequestCard(pr(failed = 7, names = listOf("build", "[x](https://evil.example) **b**"))) { opened += it } }
        }
        compose.onAllNodesWithTag("changesPullRequestFailing", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("build", useUnmergedTree = true).assertExists()
        // A name is plain text, never markdown or a link.
        compose.onNodeWithText("[x](https://evil.example) **b**", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag("changesPullRequestHost", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("+5 more").assertExists()
        compose.onNodeWithTag("changesPullRequestOpen").performClick()
        compose.onNodeWithTag("changesPullRequestChecks").performClick()
        assertEquals(listOf("https://github.com/o/r/pull/123", "https://github.com/o/r/pull/123/checks"), opened)
    }

    @Test
    fun failingNamesAreOneNodeThatSaysWhatTheyAre() {
        compose.setContent { MaterialTheme { PullRequestCard(pr()) {} } }
        val label = context.getString(R.string.remote_pr_failing_checks)
        compose.onNode(hasContentDescription("$label: build, lint")).assertExists()
    }

    @Test
    fun aHostOtherThanGithubComIsShownAndSpokenAloud() {
        compose.setContent { MaterialTheme { PullRequestCard(pr(url = "https://git.example.corp/o/r/pull/123")) {} } }
        val line = context.getString(R.string.remote_pr_on_host, "git.example.corp")
        compose.onNodeWithTag("changesPullRequestHost", useUnmergedTree = true).assertTextEquals(line)
        val model = pullRequestChipModel(pr())
        val spoken = pullRequestChipDescription(model, context.resources) + ", " + line
        compose.onNode(hasContentDescription(spoken)).assertExists()
    }

    @Test
    fun aLookalikeHostIsShown() {
        compose.setContent { MaterialTheme { PullRequestCard(pr(url = "https://gíthub.com/o/r/pull/123")) {} } }
        compose.onNodeWithTag("changesPullRequestHost", useUnmergedTree = true)
            .assertTextEquals(context.getString(R.string.remote_pr_on_host, "xn--gthub-zsa.com"))
    }

    @Test
    fun githubComShowsNoHost() {
        compose.setContent { MaterialTheme { PullRequestCard(pr()) {} } }
        compose.onAllNodesWithTag("changesPullRequestHost", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun noMoreLineWhenEveryFailingCheckIsListed() {
        compose.setContent { MaterialTheme { PullRequestCard(pr()) {} } }
        compose.onAllNodesWithTag("changesPullRequestMore").assertCountEquals(0)
    }

    @Test
    fun aNonHttpsAddressShowsNoOpenAction() {
        compose.setContent { MaterialTheme { PullRequestCard(pr(url = "http://github.com/o/r/pull/123")) {} } }
        compose.onAllNodesWithTag("changesPullRequestOpen").assertCountEquals(0)
        compose.onAllNodesWithTag("changesPullRequestChecks").assertCountEquals(0)
    }

    @Test
    fun anAddressWithAQueryHasNoChecksButton() {
        compose.setContent { MaterialTheme { PullRequestCard(pr(url = "https://github.com/o/r/pull/123?x=1")) {} } }
        compose.onAllNodesWithTag("changesPullRequestOpen").assertCountEquals(1)
        compose.onAllNodesWithTag("changesPullRequestChecks").assertCountEquals(0)
    }

    @Test
    fun theChangesOverviewShowsTheCardOnlyWithAPullRequest() {
        val actions = ChangesActions({}, {}, {}, {}, { false }, {}, { false }, { false })
        val state = ChangesState("s", loading = false, status = GitStatus(GitBase.SESSION, "Q2hhbmdlc1NuYXBzaG90MQ", emptyList()))
        var shown by mutableStateOf<PullRequestStatus?>(null)
        compose.setContent { MaterialTheme { ChangesPane(state, actions, pullRequest = shown) } }
        compose.onAllNodesWithTag("changesPullRequest").assertCountEquals(0)
        compose.runOnIdle { shown = pr() }
        compose.onNodeWithTag("changesPullRequest").assertExists()
        compose.runOnIdle { shown = null }
        compose.onAllNodesWithTag("changesPullRequest").assertCountEquals(0)
    }

    @Test
    fun theOpenLinkFiresAViewIntentForHttpsOnly() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        assertTrue(openHttpsLink(activity, "https://github.com/o/r/pull/123"))
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://github.com/o/r/pull/123", started.data.toString())
        assertFalse(openHttpsLink(activity, "http://github.com/o/r/pull/123"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
