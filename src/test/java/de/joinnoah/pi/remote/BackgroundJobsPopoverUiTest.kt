package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
class BackgroundJobsPopoverUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun job(id: String) = BackgroundJob(
        id, "echo $id", "/project", false, JobStatus.RUNNING, null, null, 1L, null, 0L, false,
    )

    @Test
    fun overviewUsesAnchoredPopoverAndClosesBeforeOpeningDetails() {
        val opened = mutableListOf<String>()
        var refreshed = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                    BackgroundJobsChip(JobsState("session", listOf(job("one"))),
                        JobsActions({ refreshed++ }, { opened += it }, {}, {}, {}))
                }
            }
        }
        compose.onNodeWithTag("jobsPopover").assertDoesNotExist()
        compose.onNodeWithTag("jobsChip").performClick()
        val panel = compose.onNodeWithTag("jobsPopover").fetchSemanticsNode().boundsInWindow
        val chip = compose.onNodeWithTag("jobsChip").fetchSemanticsNode().boundsInWindow
        assertTrue(panel.bottom < chip.top)
        assertTrue(panel.width <= 320 * compose.activity.resources.displayMetrics.density)
        compose.onNodeWithTag("jobsScreen").assertDoesNotExist()
        assertEquals(1, refreshed)
        compose.onNodeWithTag("jobsRefresh").performClick()
        assertEquals(2, refreshed)
        compose.onNodeWithTag("job-one").performClick()
        assertEquals(listOf("one"), opened)
        compose.onNodeWithTag("jobsPopover").assertDoesNotExist()
        compose.onNodeWithTag("jobsChip").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.remote_jobs_close)).performClick()
        compose.onNodeWithTag("jobsPopover").assertDoesNotExist()
        assertEquals(3, refreshed)
    }

    @Test
    fun chipTogglesExpandedOverview() {
        var refreshed = 0
        compose.setContent {
            MaterialTheme {
                BackgroundJobsChip(JobsState("session", listOf(job("one"))), JobsActions({ refreshed++ }, {}, {}, {}, {}))
            }
        }
        compose.onNodeWithTag("jobsChip").performClick()
        compose.onNodeWithTag("jobsPopover").assertExists()
        compose.onNodeWithTag("jobsChip").performClick()
        compose.onNodeWithTag("jobsPopover").assertDoesNotExist()
        assertEquals(1, refreshed)
    }

    @Test
    fun switchingSessionDiscardsExpandedStateAndPreviousJobs() {
        val current = mutableStateOf(JobsState("first", listOf(job("one"))))
        compose.setContent {
            MaterialTheme { BackgroundJobsChip(current.value, JobsActions({}, {}, {}, {}, {})) }
        }
        compose.onNodeWithTag("jobsChip").performClick()
        compose.onNodeWithTag("job-one").assertExists()
        compose.runOnIdle { current.value = JobsState("second", listOf(job("two"))) }
        compose.onNodeWithTag("jobsPopover").assertDoesNotExist()
        compose.onNodeWithTag("job-one").assertDoesNotExist()
        compose.onNodeWithTag("jobsChip").performClick()
        compose.onNodeWithTag("job-two").assertExists()
        compose.onNodeWithTag("job-one").assertDoesNotExist()
    }

    @Test
    fun chatPlacesChipInStatusAreaAndNavigatesOnlyToJobDetails() {
        val repository = NavigationFakeRepository()
        val selection = RemoteSelection("host", "project", "session")
        repository.state.value = RemoteState(
            selection = selection,
            connected = true,
            status = "idle",
            session = Wire.objectOf("id" to "session", "origin" to "tui", "status" to "idle"),
            sessions = listOf(Wire.objectOf("id" to "session", "title" to "Session", "origin" to "tui", "status" to "idle")),
            capabilities = setOf(BACKGROUND_JOBS_CAPABILITY),
            jobs = JobsState("session", listOf(job("one"))),
        )
        repository.notificationLookup = {
            repository.state.value = repository.state.value.copy(selection = selection)
            selection
        }
        compose.setContent {
            MaterialTheme {
                RemoteNavigation(repository, FakeSettingsRepository(), false, {}, RemoteNotification("host", "session", 1))
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("jobsChip").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("jobsChip").assertIsDisplayed()
        compose.onNode(hasTestTag("jobsChip") and hasAnyAncestor(hasTestTag("chatInsights"))).assertExists()
        compose.onNodeWithTag("jobsChip").performClick()
        compose.onNodeWithTag("jobsScreen").assertDoesNotExist()
        assertTrue(repository.jobOpens.isEmpty())
        compose.onNodeWithTag("job-one").performClick()
        assertEquals(listOf("one"), repository.jobOpens)
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(jobs = repository.state.value.jobs!!.copy(view = JobView("one", job("one"))))
        }
        compose.onNodeWithTag("jobsScreen").assertIsDisplayed()
        compose.onNodeWithTag("jobStop").assertExists()
        compose.runOnIdle { repository.state.value = repository.state.value.copy(jobs = JobsState("other", listOf(job("two")))) }
        compose.onNodeWithTag("jobsScreen").assertDoesNotExist()
        compose.onNodeWithTag("jobsChip").assertDoesNotExist()
    }

    @Test
    fun refreshesJobsAfterSessionFinishesLoadingWithCapabilitiesAlreadyKnown() {
        val repository = NavigationFakeRepository()
        val selection = RemoteSelection("host", "project", "session")
        repository.state.value = RemoteState(
            selection = selection,
            connected = true,
            loading = true,
            status = "idle",
            session = Wire.objectOf("id" to "session", "title" to "Session", "origin" to "tui", "status" to "idle"),
            capabilities = setOf(BACKGROUND_JOBS_CAPABILITY, BACKGROUND_JOBS_LIST_LEASE_CAPABILITY),
        )
        repository.notificationLookup = {
            repository.state.value = repository.state.value.copy(selection = selection)
            selection
        }
        compose.setContent {
            MaterialTheme {
                RemoteNavigation(repository, FakeSettingsRepository(), false, {}, RemoteNotification("host", "session", 1))
            }
        }
        compose.waitForIdle()
        assertEquals(0, repository.jobRefreshes)
        compose.runOnIdle { repository.state.value = repository.state.value.copy(loading = false) }
        compose.waitForIdle()
        assertEquals(1, repository.jobRefreshes)
        // A list response establishes the lease and makes jobs discoverable without opening an overview.
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(jobs = JobsState("session", listOf(job("one"))))
        }
        compose.onNodeWithTag("jobsChip").assertIsDisplayed()
        assertEquals(1, repository.jobRefreshes)
        compose.runOnIdle { repository.state.value = repository.state.value.copy(loading = true) }
        compose.waitForIdle()
        assertEquals(1, repository.jobRefreshes)
        compose.runOnIdle { repository.state.value = repository.state.value.copy(loading = false) }
        compose.waitForIdle()
        assertEquals(2, repository.jobRefreshes)
    }

    @Test
    fun longOverviewScrollsAndRetainsFailureMessage() {
        compose.setContent {
            MaterialTheme {
                BackgroundJobsChip(JobsState("session", (1..20).map { job("job-$it") }, failure = JobsFailure.OFFLINE),
                    JobsActions({}, {}, {}, {}, {}))
            }
        }
        compose.onNodeWithTag("jobsChip").performClick()
        compose.onNodeWithTag("jobsMessage").assertExists()
        compose.onNodeWithTag("jobsList").performScrollToNode(hasTestTag("job-job-20"))
        compose.onNodeWithTag("job-job-20").assertIsDisplayed()
    }
}
