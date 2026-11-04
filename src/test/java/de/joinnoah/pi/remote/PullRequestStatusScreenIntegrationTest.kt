package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Repository wire events drive the real navigation, chat chip and Changes screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class PullRequestStatusScreenIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val target = RemoteSelection("host", "project", "session")
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
    private val project = Wire.objectOf("id" to "project", "name" to "Fixture")
    private val session = Wire.objectOf("id" to "session", "title" to "Fixture session", "origin" to "rpc", "status" to "idle", "projectId" to "project")
    private val pairings = object : PairingStorage {
        override fun load() = listOf(host)
        override fun save(hosts: List<PairedHost>) = Unit
    }
    private val drafts = object : DraftStorage {
        override fun load() = emptyMap<DraftKey, StoredDraft>()
        override fun save(drafts: Map<DraftKey, StoredDraft>) = Unit
    }

    private inner class Transport : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        var connects = 0
        var snapshotRevision = 1
        var snapshotPr: JsonObject? = null
        override fun connect(host: PairedHost) { connects++; listener.ready(emptySet()) }
        override fun pair(value: JsonObject) = Unit
        override fun close() = Unit
        fun deliver(value: JsonObject) { listener.inbound(); listener.message(value) }
        override fun send(payload: JsonObject) {
            val data = when (payload.text("type")) {
                "projects.list" -> Wire.objectOf("kind" to "projects", "items" to JsonArray(listOf(project)),
                    "capabilities" to JsonArray(listOf(JsonPrimitive(GIT_CAPABILITY))))
                "sessions.list" -> Wire.objectOf("kind" to "sessions", "items" to JsonArray(listOf(session)))
                "sessions.open" -> Wire.objectOf("kind" to "session", "session" to session)
                "session.snapshot" -> Wire.objectOf("kind" to "snapshot", "sessionId" to "session", "revision" to snapshotRevision,
                    "status" to "idle", "messages" to JsonArray(emptyList()), "pendingQuestions" to JsonArray(emptyList()),
                    "pullRequest" to snapshotPr)
                "session.git" -> Wire.objectOf("kind" to "git.status", "sessionId" to "session", "base" to payload.text("base"),
                    "available" to false, "reason" to "not_a_repository")
                else -> Wire.objectOf("kind" to "accepted")
            }
            deliver(Wire.objectOf("type" to "result", "requestId" to payload.text("requestId"), "ok" to true, "data" to data))
        }
        fun event(revision: Int, pr: JsonObject?) = deliver(Wire.objectOf(
            "type" to "event", "sessionId" to "session", "revision" to revision,
            "kind" to "session.pull_request", "pullRequest" to pr,
        ))
    }
    private val transport = Transport()
    private val repository = DefaultRemoteRepository(
        pairings, drafts, transport, scope, Dispatchers.Main.immediate,
        projectHint = { _, _ -> "project" }, jitter = { 0.5 },
    )

    @After fun tearDown() { scope.cancel() }

    private fun pr(checks: String, failed: Int = 0, names: List<String> = emptyList()): JsonObject {
        val value = Wire.objectOf(
            "number" to 42, "url" to "https://github.com/fixture/repo/pull/42", "draft" to false,
            "review" to null, "checks" to Wire.objectOf("state" to checks, "failed" to failed),
        )
        return if (names.isEmpty()) value else JsonObject(value + ("failingChecks" to JsonArray(names.map(::JsonPrimitive))))
    }

    private fun awaitTag(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitNoTag(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
    }

    @Test fun wireStatusUpdatesWholeScreenAndRestoresAfterReconnect() {
        transport.snapshotPr = pr("running")
        compose.setContent {
            MaterialTheme {
                RemoteNavigation(repository, FakeSettingsRepository(), false, {}, RemoteNotification("host", "session", 1))
            }
        }
        awaitTag("pullRequestChip")
        compose.onNodeWithTag("pullRequestChip").assertIsDisplayed().assertTextContains("PR #42", substring = true)
        compose.runOnIdle { transport.event(2, pr("done", 1, listOf("fixture-build"))) }
        compose.waitUntil(5_000) { repository.state.value.pullRequest?.failedChecks == 1 }
        compose.onNodeWithTag("pullRequestChip").assertTextContains("1 failing", substring = true).performClick()
        awaitTag("changesPullRequest")
        compose.onNodeWithTag("changesPullRequestFailing", useUnmergedTree = true).assertTextContains("fixture-build", substring = true)
        compose.onNodeWithTag("changesPullRequestOpen").assertIsDisplayed()
        compose.onNodeWithTag("changesPullRequestChecks").assertIsDisplayed()
        compose.runOnIdle { transport.event(3, pr("done")) }
        compose.waitUntil(5_000) { repository.state.value.pullRequest?.failedChecks == 0 }
        awaitNoTag("changesPullRequestFailing")
        compose.runOnIdle { transport.event(4, null) }
        awaitNoTag("changesPullRequest")
        compose.runOnIdle {
            transport.snapshotRevision = 5
            transport.snapshotPr = pr("running")
            transport.listener.failed(true, R.string.remote_connection_error)
            scope.launch { repository.activate(target, ActivationMode.USER_OPEN) }
        }
        compose.waitUntil(5_000) { transport.connects >= 2 && repository.state.value.pullRequest?.checks == PullRequestChecksState.RUNNING }
        awaitTag("changesPullRequest")
        assertEquals(42L, repository.state.value.pullRequest?.number)
    }
}
