package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
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
class BackgroundJobsSheetUiTest {
    @Composable
    private fun JobsHarness(jobs: JobsState, actions: JobsActions) {
        var open by remember(jobs.sessionId) { mutableStateOf(false) }
        BackgroundJobsChip(jobs, open) { open = true; actions.onRefresh() }
        if (open) JobsInsightSheet(jobs, actions) { open = false }
    }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** A narrow job header keeps Ask in its overflow menu; the menu opens first. */
    private fun askAction() {
        compose.onNodeWithTag("jobsMoreActions").performClick()
        compose.onNodeWithTag("jobAsk").performClick()
    }

    private fun job(id: String) = BackgroundJob(
        id, "echo $id", "/project", false, JobStatus.RUNNING, null, null, 1L, null, 0L, false,
    )

    @Test
    fun overviewUsesInsightSheetAndClosesBeforeOpeningDetails() {
        val opened = mutableListOf<String>()
        var refreshed = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                    JobsHarness(JobsState("session", listOf(job("one"))),
                        JobsActions({ refreshed++ }, { opened += it }, {}, {}, {}))
                }
            }
        }
        compose.onNodeWithTag("jobsInsightSheet").assertDoesNotExist()
        compose.onNodeWithTag("jobsChip").performClick()
        compose.onNodeWithTag("jobsScreen").assertDoesNotExist()
        assertEquals(1, refreshed)
        compose.onNodeWithTag("jobsRefresh").performClick()
        assertEquals(2, refreshed)
        compose.onNodeWithTag("job-one").performClick()
        assertEquals(listOf("one"), opened)
        compose.onNodeWithTag("jobsInsightSheet").assertDoesNotExist()
        compose.onNodeWithTag("jobsChip").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.insight_sheet_close)).performClick()
        compose.onNodeWithTag("jobsInsightSheet").assertDoesNotExist()
        assertEquals(3, refreshed)
    }

    @Test
    fun chipShowsOnlyWhileAJobRunsAndKeepsAnOpenOverview() {
        val current = mutableStateOf(JobsState("session", listOf(job("one").copy(status = JobStatus.EXITED, exitCode = 0))))
        compose.setContent {
            MaterialTheme { JobsHarness(current.value, JobsActions({}, {}, {}, {}, {})) }
        }
        compose.onNodeWithTag("jobsChip").assertDoesNotExist()
        compose.runOnIdle { current.value = JobsState("session", current.value.jobs + job("two")) }
        compose.onNodeWithTag("jobsChip").performClick()
        // The last job ending leaves the open overview, with both jobs, until it closes.
        compose.runOnIdle {
            current.value = JobsState("session", current.value.jobs.map { it.copy(status = JobStatus.EXITED, exitCode = 0) })
        }
        compose.onNodeWithTag("jobsInsightSheet").assertExists()
        compose.onNodeWithTag("job-one").assertExists()
        compose.onNodeWithTag("job-two").assertExists()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.insight_sheet_close)).performClick()
        compose.onNodeWithTag("jobsChip").assertDoesNotExist()
    }

    @Test
    fun switchingSessionDiscardsExpandedStateAndPreviousJobs() {
        val current = mutableStateOf(JobsState("first", listOf(job("one"))))
        compose.setContent {
            MaterialTheme { JobsHarness(current.value, JobsActions({}, {}, {}, {}, {})) }
        }
        compose.onNodeWithTag("jobsChip").performClick()
        compose.onNodeWithTag("job-one").assertExists()
        compose.runOnIdle { current.value = JobsState("second", listOf(job("two"))) }
        compose.onNodeWithTag("jobsInsightSheet").assertDoesNotExist()
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
    fun chatKeepsAnOpenOverviewAfterTheLastJobEndsAndThenHidesTheChip() {
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
        compose.onNodeWithTag("jobsChip").performClick()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(
                jobs = JobsState("session", listOf(job("one").copy(status = JobStatus.EXITED, exitCode = 0))),
            )
        }
        compose.onNodeWithTag("jobsInsightSheet").assertExists()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.insight_sheet_close)).performClick()
        compose.onNodeWithTag("jobsChip").assertDoesNotExist()
        compose.onNodeWithTag("chatInsights").assertDoesNotExist()
    }

    @Test
    fun chatSheetSurvivesEmptyLoadingFailureAndNewJobs() {
        val repository = NavigationFakeRepository()
        val selection = RemoteSelection("host", "project", "session")
        repository.state.value = RemoteState(
            selection = selection, connected = true, status = "idle",
            session = Wire.objectOf("id" to "session", "origin" to "tui", "status" to "idle"),
            sessions = listOf(Wire.objectOf("id" to "session", "title" to "Session", "origin" to "tui", "status" to "idle")),
            capabilities = setOf(BACKGROUND_JOBS_CAPABILITY),
            jobs = JobsState("session", listOf(job("one")), loaded = true),
        )
        repository.notificationLookup = { selection }
        compose.setContent {
            MaterialTheme { RemoteNavigation(repository, FakeSettingsRepository(), false, {}, RemoteNotification("host", "session", 1)) }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("jobsChip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("jobsChip").performClick()
        compose.runOnIdle { repository.state.value = repository.state.value.copy(jobs = JobsState("session", loaded = true)) }
        compose.onNodeWithTag("jobsInsightSheet").assertExists()
        compose.onNodeWithTag("jobsMessage").assertTextEquals(compose.activity.getString(R.string.remote_jobs_empty))
        compose.runOnIdle { repository.state.value = repository.state.value.copy(jobs = JobsState("session", loading = true)) }
        compose.onNodeWithTag("jobsInsightSheet").assertExists()
        compose.onNodeWithTag("jobsRefresh").assertIsNotEnabled()
        compose.runOnIdle { repository.state.value = repository.state.value.copy(jobs = JobsState("session", failure = JobsFailure.OFFLINE)) }
        compose.onNodeWithTag("jobsMessage").assertTextEquals(compose.activity.getString(R.string.remote_jobs_failed))
        compose.runOnIdle { repository.state.value = repository.state.value.copy(jobs = JobsState("session", listOf(job("two")))) }
        compose.onNodeWithTag("jobsInsightSheet").assertExists()
        compose.onNodeWithTag("job-two").performClick()
        assertEquals(listOf("two"), repository.jobOpens)
        compose.onNodeWithTag("jobsInsightSheet").assertDoesNotExist()
    }

    @Test
    fun chatSheetRejectsOldSnapshotsAndClosesOnCapabilityLoss() {
        val repository = NavigationFakeRepository()
        val selection = RemoteSelection("host", "project", "session")
        val otherSelection = RemoteSelection("host", "project", "other")
        val notification = mutableStateOf(RemoteNotification("host", "session", 1))
        val settings = FakeSettingsRepository()
        repository.state.value = RemoteState(
            selection = selection, connected = true, status = "idle",
            session = Wire.objectOf("id" to "session", "origin" to "tui", "status" to "idle"),
            sessions = listOf(Wire.objectOf("id" to "session", "title" to "Session", "origin" to "tui", "status" to "idle")),
            capabilities = setOf(BACKGROUND_JOBS_CAPABILITY),
            jobs = JobsState("session", listOf(job("one"))),
        )
        repository.notificationLookup = {
            if (notification.value.sessionId == "other") {
                repository.state.value = repository.state.value.copy(
                    selection = otherSelection,
                    session = Wire.objectOf("id" to "other", "origin" to "tui", "status" to "idle"),
                    jobs = JobsState("other", listOf(job("other"))),
                )
                otherSelection
            } else selection
        }
        compose.setContent {
            MaterialTheme { RemoteNavigation(repository, settings, false, {}, notification.value) }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("jobsChip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("jobsChip").performClick()
        compose.runOnIdle { repository.state.value = repository.state.value.copy(jobs = JobsState("old", listOf(job("old")))) }
        compose.onNodeWithTag("jobsInsightSheet").assertExists()
        compose.onNodeWithTag("job-old").assertDoesNotExist()
        compose.onNodeWithTag("jobsRefresh").assertIsNotEnabled()
        compose.runOnIdle { repository.state.value = repository.state.value.copy(capabilities = emptySet()) }
        compose.onNodeWithTag("jobsInsightSheet").assertDoesNotExist()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(
                capabilities = setOf(BACKGROUND_JOBS_CAPABILITY), jobs = JobsState("session", listOf(job("two"))),
            )
        }
        compose.onNodeWithTag("jobsChip").assertExists()
        compose.onNodeWithTag("jobsInsightSheet").assertDoesNotExist()
        compose.onNodeWithTag("jobsChip").performClick()
        // A repository selection alone does not navigate: the old chat retains its own snapshot.
        compose.runOnIdle { notification.value = RemoteNotification("host", "other", 2) }
        // Process the notification's recomposition/effect before polling a non-Compose list.
        compose.waitForIdle()
        compose.waitUntil(5_000) { repository.notificationRequests.contains("host" to "other") }
        compose.waitUntil(5_000) { repository.state.value.selection == otherSelection }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("jobsInsightSheet").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("jobsChip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("jobsInsightSheet").assertDoesNotExist()
        compose.onNodeWithTag("jobsChip").performClick()
        compose.onNodeWithTag("job-other").assertExists()
        compose.onNodeWithTag("job-two").assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.insight_sheet_close)).performClick()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(jobs = JobsState("session", listOf(job("late"))))
        }
        compose.onNodeWithTag("jobsInsightSheet").assertDoesNotExist()
        compose.onNodeWithTag("job-late").assertDoesNotExist()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(jobs = JobsState("other", listOf(job("other"))))
        }
        compose.onNodeWithTag("jobsInsightSheet").assertDoesNotExist()
        compose.onNodeWithTag("jobsChip").performClick()
        compose.onNodeWithTag("job-other").assertExists()
        compose.onNodeWithTag("job-late").assertDoesNotExist()
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
    fun openSheetKeepsEmptyAndFailedResultsVisibleAndDisablesRefreshWhileLoading() {
        val current = mutableStateOf(JobsState("session", listOf(job("one"))))
        var refreshes = 0
        compose.setContent {
            MaterialTheme { JobsHarness(current.value, JobsActions({ refreshes++ }, {}, {}, {}, {})) }
        }
        compose.onNodeWithTag("jobsChip").performClick()
        assertEquals(1, refreshes)
        compose.runOnIdle { current.value = JobsState("session", emptyList(), loading = true) }
        compose.onNodeWithTag("jobsInsightSheet").assertExists()
        compose.onNodeWithTag("jobsRefresh").assertIsNotEnabled()
        compose.runOnIdle { current.value = JobsState("session", emptyList(), failure = JobsFailure.OFFLINE) }
        compose.onNodeWithTag("jobsMessage").assertTextEquals(compose.activity.getString(R.string.remote_jobs_failed))
        compose.onNodeWithTag("jobsRefresh").performClick()
        assertEquals(2, refreshes)
        compose.runOnIdle { current.value = JobsState("session", emptyList(), loaded = true) }
        compose.onNodeWithTag("jobsMessage").assertTextEquals(compose.activity.getString(R.string.remote_jobs_empty))
    }

    @Test
    fun longOverviewScrollsAndRetainsFailureMessage() {
        compose.setContent {
            MaterialTheme {
                JobsHarness(JobsState("session", (1..20).map { job("job-$it") }, failure = JobsFailure.OFFLINE),
                    JobsActions({}, {}, {}, {}, {}))
            }
        }
        compose.onNodeWithTag("jobsChip").performClick()
        compose.onNodeWithTag("jobsMessage").assertExists()
        compose.onNodeWithTag("jobsList").performScrollToNode(hasTestTag("job-job-20"))
        compose.onNodeWithTag("job-job-20").assertIsDisplayed()
    }

    @Test
    fun openJobsInTheSheetOpensTheFullList() {
        var listOpens = 0
        compose.setContent {
            MaterialTheme {
                JobsHarness(
                    JobsState("session", listOf(job("one"))),
                    JobsActions({}, {}, {}, {}, {}, onOpenList = { listOpens++ }),
                )
            }
        }
        compose.onNodeWithTag("jobsChip").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.remote_jobs_open_all)).performClick()
        assertEquals(1, listOpens)
        compose.onNodeWithTag("jobsInsightSheet").assertDoesNotExist()
    }

    @Test
    fun withoutAWayToTheListTheSheetOffersNone() {
        compose.setContent {
            MaterialTheme { JobsHarness(JobsState("session", listOf(job("one"))), JobsActions({}, {}, {}, {}, {})) }
        }
        compose.onNodeWithTag("jobsChip").performClick()
        compose.onNodeWithTag("jobsOpenAll").assertDoesNotExist()
    }

    @Test
    fun askingPiAboutTheOutputQuotesItsEndIntoTheComposer() {
        val prompts = mutableListOf<String>()
        var accepted = true
        val view = JobView(
            "one", job("one"), lines = listOf(JobOutputLine.Text("compiling"), JobOutputLine.Text("error: boom")), loaded = true,
        )
        compose.setContent {
            MaterialTheme {
                BackgroundJobsScreen(
                    JobsState("session", listOf(job("one")), view = view),
                    JobsActions({}, {}, {}, {}, {}, onAsk = { prompts += it; accepted }),
                )
            }
        }
        askAction()
        val prompt = prompts.single()
        assertTrue(prompt, prompt.startsWith(compose.activity.getString(R.string.remote_jobs_ask_prompt, "echo one")))
        assertTrue(prompt, prompt.endsWith("```\ncompiling\nerror: boom\n```"))
        compose.onNodeWithTag("jobAskFailed").assertDoesNotExist()
        // A draft too full for it says so; nothing is sent either way.
        accepted = false
        askAction()
        compose.onNodeWithTag("jobAskFailed").assertIsDisplayed()
    }

    @Test
    fun withoutOutputThereIsNothingToAskAbout() {
        compose.setContent {
            MaterialTheme {
                BackgroundJobsScreen(
                    JobsState("session", listOf(job("one")), view = JobView("one", job("one"), loaded = true)),
                    JobsActions({}, {}, {}, {}, {}, onAsk = { true }),
                )
            }
        }
        compose.onNodeWithTag("jobAsk").assertDoesNotExist()
    }
}
