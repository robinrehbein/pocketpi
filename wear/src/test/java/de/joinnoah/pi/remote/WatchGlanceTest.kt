package de.joinnoah.pi.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WatchGlanceTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources
    private val now = 10 * 3_600_000L
    private fun row(id: String, status: String, updatedAt: Long = 0L, route: String = "route", project: String = "p") =
        HomeSession(route, project, "Project", id, "Title $id", status, updatedAt)
    private fun pr(number: Long = 189, checks: PullRequestChecksState = PullRequestChecksState.RUNNING, failed: Int = 0) =
        PullRequestStatus(number, "https://github.com/acme/app/pull/$number", false, PullRequestReview.NONE, checks, failed, emptyList())
    private fun glance(vararg rows: HomeSession) = WatchGlance(HomeSnapshot(true, rows.toList(), now - 60_000L))
    private fun session(id: String, status: String) = Wire.objectOf("id" to id, "origin" to "rpc", "title" to "Title $id", "status" to status)

    @Test fun `rows show at most two active sessions with waiting ones first`() {
        val rows = watchTileRows(glance(row("a", "running", 3), row("b", "idle", 9), row("c", "waiting", 1), row("d", "running", 5)), now)
        assertEquals(listOf("c", "d"), rows.map { it.sessionId })
        assertTrue(rows.first().waiting)
        assertFalse(rows.last().waiting)
    }
    @Test fun `rows carry a pull request seen for the same session within a day`() {
        val base = glance(row("a", "running"), row("b", "running")).withPullRequest("route", "a", pr(), now - 300_000L)
            .withPullRequest("other", "b", pr(7), now).withPullRequest("route", "b", pr(8), now - WATCH_PULL_REQUEST_MAX_AGE_MILLIS - 1)
        val rows = watchTileRows(base, now).associateBy { it.sessionId }
        assertEquals(189L, rows.getValue("a").pullRequest?.number)
        assertEquals(now - 300_000L, rows.getValue("a").pullRequestSeenAt)
        assertNull(rows.getValue("b").pullRequest)
    }
    @Test fun `row detail names status pull request checks and their age`() {
        val waiting = WatchTileRow("route", "a", "Title", true, pullRequestChipModel(pr(failed = 2, checks = PullRequestChecksState.DONE)), now - 5 * 60_000L)
        assertEquals("needs you · PR #189 · 2 failing · 5 min", watchTileRowDetail(waiting, resources, now))
        val working = WatchTileRow("route", "a", "Title", false, null, null)
        assertEquals("working", watchTileRowDetail(working, resources, now))
        val fresh = working.copy(pullRequest = pullRequestChipModel(pr()), pullRequestSeenAt = now - 10_000L)
        assertEquals("working · PR #189 · checks running · now", watchTileRowDetail(fresh, resources, now))
        assertEquals("working · PR #189 · checks running · 1 min", watchTileRowDetail(fresh.copy(pullRequestSeenAt = now - 60_000L), resources, now))
        val old = fresh.copy(pullRequestSeenAt = now - 2 * 3_600_000L - 1)
        assertEquals("working · PR #189 · checks running · 2 h", watchTileRowDetail(old, resources, now))
    }
    @Test fun `a cleared pull request is forgotten and a new one replaces the old`() {
        val seen = glance(row("a", "running")).withPullRequest("route", "a", pr(1), now - 1).withPullRequest("route", "a", pr(2), now)
        assertEquals(listOf(2L), seen.pullRequests.map { it.pullRequest.number })
        assertTrue(seen.withPullRequest("route", "a", null, now).pullRequests.isEmpty())
    }
    @Test fun `pull requests of sessions no longer shown are dropped with the rows`() {
        val seen = glance(row("a", "running"), row("b", "running")).withPullRequest("route", "a", pr(), now).withPullRequest("route", "b", pr(), now)
        val next = seen.withHome(seen.home.copy(sessions = listOf(row("a", "running"))))
        assertEquals(listOf("a"), next.pullRequests.map { it.sessionId })
        assertTrue(seen.withoutRoute("route").let { it.home.sessions.isEmpty() && it.pullRequests.isEmpty() })
    }
    @Test fun `json round trips and tolerates a file without or with malformed pull requests`() {
        val seen = glance(row("a", "waiting", 4)).withPullRequest("route", "a", pr(failed = 1, checks = PullRequestChecksState.DONE), now)
        assertEquals(seen, watchGlance(seen.json()))
        val bare = JsonObject(seen.json() - "pullRequests")
        assertEquals(seen.copy(pullRequests = emptyList()), watchGlance(bare))
        val broken = JsonObject(seen.json() + ("pullRequests" to JsonArray(listOf(Wire.objectOf("routeId" to "route", "sessionId" to "a", "seenAt" to now, "pullRequest" to Wire.objectOf("number" to 0))))))
        assertTrue(watchGlance(broken).pullRequests.isEmpty())
    }
    @Test fun `a read replaces only its own Mac's rows and pull requests`() {
        val current = glance(row("a", "running"), row("x", "waiting", route = "elsewhere")).withPullRequest("elsewhere", "x", pr(5), now)
            .withPullRequest("route", "a", pr(1), now - 1)
        val read = WatchGlance(HomeSnapshot(true, listOf(row("b", "waiting"), row("x", "idle", route = "elsewhere")), now)).withPullRequest("route", "b", pr(2), now)
        val next = current.withRoute("route", read, now - 1)
        assertEquals(listOf("b", "x"), next.home.sessions.map { it.sessionId })
        assertEquals("waiting", next.home.sessions.single { it.sessionId == "x" }.status)
        assertEquals(now, next.home.refreshedAt)
        assertEquals(setOf(2L, 5L), next.pullRequests.map { it.pullRequest.number }.toSet())
    }
    @Test fun `a pull request the app saw while a read ran is kept`() {
        val read = WatchGlance(HomeSnapshot(true, listOf(row("a", "running"), row("b", "running")), now))
        val current = glance(row("a", "running"), row("b", "running")).withPullRequest("route", "a", pr(3), now - 500).withPullRequest("route", "b", pr(4), now - 5_000)
        assertEquals(listOf(3L), current.withRoute("route", read, now - 1_000).pullRequests.map { it.pullRequest.number })
    }
    @Test fun `a push marks a known session waiting or idle`() {
        val base = glance(row("a", "running"))
        assertEquals("waiting", base.withPush(PushPayload("route", "a", "e1", PushEvent.QUESTION)).home.sessions.single().status)
        assertEquals("idle", base.withPush(PushPayload("route", "a", "e2", PushEvent.COMPLETE)).home.sessions.single().status)
        // A push confirms no other row, so the time stays and a swipe right after it still refreshes.
        assertEquals(base.home.refreshedAt, base.withPush(PushPayload("route", "a", "e3", PushEvent.COMPLETE)).home.refreshedAt)
        assertEquals(base, base.withPush(PushPayload("route", "x", "e4", PushEvent.QUESTION)))
        assertEquals(base, base.withPush(PushPayload("route", "a", "e5", PushEvent.JOB_DONE)))
    }
    @Test fun `refresh is due only when the snapshot is old and none runs`() {
        val base = glance(row("a", "running"))
        assertTrue(watchGlanceNeedsRefresh(base.copy(home = base.home.copy(refreshedAt = null)), now, false))
        assertTrue(watchGlanceNeedsRefresh(base, now, false))
        assertFalse(watchGlanceNeedsRefresh(base, now, true))
        assertFalse(watchGlanceNeedsRefresh(base.copy(home = base.home.copy(refreshedAt = now - 5_000L)), now, false))
        assertFalse(watchGlanceNeedsRefresh(WatchGlance(), now, false))
    }
    @Test fun `the open session's pull request is remembered only from a settled live chat`() {
        val live = RemoteState(connected = true, selection = RemoteSelection("route", "p", "a"), session = session("a", "running"), pullRequest = pr())
        val base = glance(row("a", "running"))
        assertEquals(189L, base.withOpenSession(live, now).pullRequests.single().pullRequest.number)
        assertEquals(base, base.withOpenSession(live.copy(connected = false), now))
        assertEquals(base, base.withOpenSession(live.copy(stale = true), now))
        assertEquals(base, base.withOpenSession(live.copy(preview = true), now))
        assertEquals(base, base.withOpenSession(live.copy(loading = true), now))
        assertEquals(base, base.withOpenSession(live.copy(session = session("b", "running")), now))
        val seen = base.withOpenSession(live, now)
        assertTrue(seen.withOpenSession(live.copy(pullRequest = null), now).pullRequests.isEmpty())
        // The same status seen again keeps its time, so a connected app does not rewrite the file.
        assertSame(seen, seen.withOpenSession(live, now + 1_000L))
    }
    @Test fun `a refresh reads each project's sessions and the top session's pull request`() = runBlocking {
        val requests = mutableListOf<String>()
        val commands = RemoteCommands { type, fields ->
            val args = fields.toMap()
            requests += type + (args["projectId"] ?: args["sessionId"] ?: "")
            when (type) {
                "projects.list" -> Wire.objectOf("items" to JsonArray(listOf(Wire.objectOf("id" to "p", "name" to "Pocket"), Wire.objectOf("id" to "q", "name" to "Noah"))))
                "sessions.list" -> Wire.objectOf("items" to JsonArray(if (args["projectId"] == "p") listOf(session("a", "running"), session("b", "idle")) else listOf(session("c", "waiting"))))
                "session.snapshot" -> Wire.objectOf("kind" to "snapshot", "sessionId" to args["sessionId"], "pullRequest" to pullRequestJson(pr(42)))
                else -> error(type)
            }
        }
        val previous = glance(row("gone", "running", project = "removed"), row("other", "running", route = "elsewhere"))
        val next = commands.readWatchGlance("route", setOf("route", "elsewhere"), previous, now)
        assertEquals(setOf("a", "b", "c", "other"), next.home.sessions.map { it.sessionId }.toSet())
        assertEquals("Noah", next.home.sessions.single { it.sessionId == "c" }.projectName)
        assertEquals(now, next.home.refreshedAt)
        assertEquals(listOf("c"), next.pullRequests.map { it.sessionId })
        assertEquals(42L, next.pullRequests.single().pullRequest.number)
        assertEquals(setOf("projects.list", "sessions.listp", "sessions.listq", "session.snapshotc"), requests.toSet())
        assertEquals(4, requests.size)
    }
    @Test fun `a Mac without projects still counts as refreshed`() = runBlocking {
        val commands = RemoteCommands { type, _ -> if (type == "projects.list") Wire.objectOf("items" to JsonArray(emptyList())) else error(type) }
        val next = commands.readWatchGlance("route", setOf("route"), glance(row("a", "running")), now)
        assertEquals(now, next.home.refreshedAt)
        assertTrue(next.home.sessions.isEmpty())
    }
    @Test fun `a refresh without a pull request clears the top session's memory`() = runBlocking {
        val commands = RemoteCommands { type, fields ->
            val args = fields.toMap()
            when (type) {
                "projects.list" -> Wire.objectOf("items" to JsonArray(listOf(Wire.objectOf("id" to "p", "name" to "Pocket"))))
                "sessions.list" -> Wire.objectOf("items" to JsonArray(listOf(session("a", "running"))))
                "session.snapshot" -> Wire.objectOf("kind" to "snapshot", "sessionId" to args["sessionId"])
                else -> error(type)
            }
        }
        val previous = glance(row("a", "running")).withPullRequest("route", "a", pr(), now - 60_000L)
        assertTrue(commands.readWatchGlance("route", setOf("route"), previous, now).pullRequests.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WatchTileTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val device = androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters.Builder()
        .setScreenWidthDp(240).setScreenHeightDp(240).setScreenDensity(2f).setFontScale(1f)
        .setScreenShape(androidx.wear.protolayout.DeviceParametersBuilders.SCREEN_SHAPE_ROUND).build()
    private val now = 10 * 3_600_000L
    private fun content(glance: WatchGlance) = watchTileContent(context, glance, now).also { watchTileLayout(context, it, device) }

    @Test fun `tile names the pairing hint, the empty state or the session rows`() {
        assertEquals(context.getString(R.string.remote_widget_pair_hint), content(WatchGlance()).hint)
        val empty = content(WatchGlance(HomeSnapshot(true, emptyList(), now)))
        assertEquals(context.getString(R.string.remote_widget_empty), empty.hint)
        assertEquals(context.getString(R.string.remote_widget_none_waiting), empty.title)
        assertNotNull(empty.updated)
        val rows = WatchGlance(HomeSnapshot(true, listOf(HomeSession("route", "p", "P", "a", "Fix the tile", "waiting"), HomeSession("route", "p", "P", "b", "", "running")), now))
            .withPullRequest("route", "a", PullRequestStatus(7, "https://github.com/a/b/pull/7", false, PullRequestReview.NONE, PullRequestChecksState.RUNNING, 0, emptyList()), now)
        val tile = content(rows)
        assertNull(tile.hint)
        assertEquals("1 session needs you", tile.title)
        assertEquals(listOf("Fix the tile", context.getString(R.string.remote_widget_untitled)), tile.rows.map { it.title })
        assertEquals(listOf("needs you · PR #7 · checks running · now", "working"), tile.rows.map { it.detail })
    }
    @Test fun `a tile tap opens a validated session or else the app`() {
        val extras = android.os.Bundle().apply { WatchTileTarget.intentExtras(WatchTileRow("route", "a", "", false, null, null)).forEach(::putString) }
        assertEquals(NotificationTarget("route", "a"), WatchTileTarget.target(extras))
        assertEquals(NotificationTarget("route", "a"), WearNotifications.target(WatchTileTarget.forward(context, extras)))
        assertNull(WatchTileTarget.target(android.os.Bundle().apply { putString("tileRouteId", "bad id"); putString("tileSessionId", "a") }))
        assertNull(WearNotifications.target(WatchTileTarget.forward(context, null)))
        assertEquals(WearActivity::class.java.name, WatchTileTarget.forward(context, null).component?.className)
        val flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(flags, WatchTileTarget.forward(context, extras).flags and flags)
        assertEquals(flags, WatchTileTarget.forward(context, null).flags and flags)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WatchGlanceStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val host = PairedHost("route", "wss://example.invalid", "device", "not-a-real-key", "Mac")
    private class Memory(var bytes: ByteArray?) : WatchGlanceFile {
        override fun read() = bytes
        override fun write(bytes: ByteArray) { this.bytes = bytes }
    }
    private class Pairings(var hosts: List<PairedHost>) : PairingStorage {
        override fun load() = hosts
        override fun save(hosts: List<PairedHost>) { this.hosts = hosts }
    }
    private val stored = WatchGlance(HomeSnapshot(true, listOf(HomeSession("route", "p", "P", "a", "Title", "running")), 1L))
    private fun store(scope: kotlinx.coroutines.CoroutineScope, pairings: PairingStorage) =
        WatchGlanceStore(context, scope, WatchFakeRepository(), pairings, Memory(stored.json().toString().toByteArray()), redraw = {})
    private suspend fun settle() = kotlinx.coroutines.delay(HOME_UPDATE_INTERVAL_MS * 3)

    @Test fun `before the app has read its pairings the stored ones keep the rows`() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val store = store(scope, Pairings(listOf(host)))
            store.observe(kotlinx.coroutines.flow.MutableStateFlow(RemoteState()))
            settle()
            assertEquals(stored, store.load())
        } finally { scope.cancel() }
    }
    @Test fun `pairing a forgotten Mac again makes it paired again`() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val state = kotlinx.coroutines.flow.MutableStateFlow(RemoteState())
            val store = store(scope, Pairings(listOf(host)))
            store.observe(state)
            store.forgetRoute("route")
            settle()
            assertFalse(store.load().home.paired)
            assertTrue(store.load().home.sessions.isEmpty())
            state.value = RemoteState(hosts = listOf(host), hostsLoaded = true)
            settle()
            assertTrue(store.load().home.paired)
        } finally { scope.cancel() }
    }
}
