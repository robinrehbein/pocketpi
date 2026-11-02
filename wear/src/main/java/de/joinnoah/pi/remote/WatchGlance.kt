package de.joinnoah.pi.remote

import android.content.res.Resources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

// What the status tile shows: the phone widget's session snapshot, plus the pull request each
// session had when the watch last saw it. The host lists no pull requests, so a remembered one
// always carries its age; a swipe to the tile refreshes the top session's.

internal const val WATCH_TILE_ROWS = 2
/** A remembered pull request older than this is not shown. */
internal const val WATCH_PULL_REQUEST_MAX_AGE_MILLIS = 24 * 3_600_000L
/** A tile request refreshes a snapshot older than this. */
internal const val WATCH_GLANCE_REFRESH_AFTER_MILLIS = 30_000L
/** The whole refresh of one Mac is bounded by this. */
internal const val WATCH_GLANCE_REFRESH_TIMEOUT_MILLIS = 10_000L
/** At most this many projects are read per refresh, [WATCH_GLANCE_PARALLELISM] at a time. */
internal const val WATCH_GLANCE_MAX_PROJECTS = 24
internal const val WATCH_GLANCE_PARALLELISM = 4

internal data class SeenPullRequest(val routeId: String, val sessionId: String, val pullRequest: PullRequestStatus, val seenAt: Long)

internal data class WatchGlance(val home: HomeSnapshot = HomeSnapshot(), val pullRequests: List<SeenPullRequest> = emptyList())

internal data class WatchTileRow(
    val routeId: String, val sessionId: String, val title: String, val waiting: Boolean,
    val pullRequest: PullRequestChipModel?, val pullRequestSeenAt: Long?,
)

internal fun watchTileRows(glance: WatchGlance, now: Long, limit: Int = WATCH_TILE_ROWS): List<WatchTileRow> =
    widgetSessions(glance.home, limit).map { session ->
        val seen = glance.pullRequests.find { it.routeId == session.routeId && it.sessionId == session.sessionId }
            ?.takeIf { now - it.seenAt in 0..WATCH_PULL_REQUEST_MAX_AGE_MILLIS }
        WatchTileRow(session.routeId, session.sessionId, session.title, session.status == "waiting",
            seen?.let { pullRequestChipModel(it.pullRequest) }, seen?.seenAt)
    }

/** `needs you · PR #189 · 2 failing · 5 min`: the status, then the checks without the review, and their age. */
internal fun watchTileRowDetail(row: WatchTileRow, resources: Resources, now: Long): String = buildList {
    add(resources.getString(if (row.waiting) R.string.wear_tile_needs_you else R.string.wear_tile_working))
    val pr = row.pullRequest ?: return@buildList
    add(resources.getString(R.string.remote_pr_chip_number, pr.number))
    when (val checks = pr.checks) {
        is PullRequestChecksWord.Failing -> add(resources.getQuantityString(R.plurals.remote_pr_checks_failing, checks.count, checks.count))
        PullRequestChecksWord.Running -> add(resources.getString(R.string.remote_pr_checks_running))
        PullRequestChecksWord.Passed -> add(resources.getString(R.string.remote_pr_checks_passed))
        null -> Unit
    }
    row.pullRequestSeenAt?.let { add(watchAge(resources, now - it)) }
}.joinToString(" · ")

private fun watchAge(resources: Resources, millis: Long): String = when {
    millis < 60_000L -> resources.getString(R.string.wear_tile_age_now)
    millis < 3_600_000L -> (millis / 60_000L).toInt().let { resources.getQuantityString(R.plurals.wear_tile_age_minutes, it, it) }
    else -> (millis / 3_600_000L).toInt().let { resources.getQuantityString(R.plurals.wear_tile_age_hours, it, it) }
}

internal fun WatchGlance.withPullRequest(routeId: String, sessionId: String, pullRequest: PullRequestStatus?, now: Long): WatchGlance {
    val others = pullRequests.filter { it.routeId != routeId || it.sessionId != sessionId }
    return copy(pullRequests = if (pullRequest == null) others else listOf(SeenPullRequest(routeId, sessionId, pullRequest, now)) + others.take(MAX_HOME_SESSIONS - 1))
}

/** [home] replaces the rows; pull requests of sessions without a row go with them. */
internal fun WatchGlance.withHome(home: HomeSnapshot): WatchGlance {
    if (home == this.home) return this
    val rows = home.sessions.map { it.routeId to it.sessionId }.toSet()
    return WatchGlance(home, pullRequests.filter { (it.routeId to it.sessionId) in rows })
}

internal fun WatchGlance.withoutRoute(routeId: String): WatchGlance = withHome(
    home.copy(sessions = home.sessions.filter { it.routeId != routeId }, notified = home.notified.filter { it.routeId != routeId }),
)

/**
 * [read]'s rows and pull requests of [routeId] in place of this glance's, with its time. Other Macs'
 * rows stay as they are, and so does a pull request of [routeId] the app saw at or after [since],
 * when the read started, for a session the read found none for: the app's is the newer news.
 */
internal fun WatchGlance.withRoute(routeId: String, read: WatchGlance, since: Long): WatchGlance {
    val fromRead = read.pullRequests.filter { it.routeId == routeId }
    val readSessions = fromRead.map { it.sessionId }.toSet()
    val sessions = (read.home.sessions.filter { it.routeId == routeId } + home.sessions.filter { it.routeId != routeId }).take(MAX_HOME_SESSIONS)
    val rows = sessions.map { it.routeId to it.sessionId }.toSet()
    return WatchGlance(
        home.copy(sessions = sessions, refreshedAt = read.home.refreshedAt ?: home.refreshedAt),
        (fromRead + pullRequests.filter { it.routeId != routeId || (it.seenAt >= since && it.sessionId !in readSessions) })
            .filter { (it.routeId to it.sessionId) in rows },
    )
}

/** A question makes its session wait for the user, a completion makes it idle; other pushes say nothing about the session. */
internal fun WatchGlance.withPush(payload: PushPayload): WatchGlance = when (payload.event) {
    // The rows' time stays: a push is no confirmation of the other rows, and must not hold off a refresh.
    PushEvent.QUESTION -> withHome(home.withStatus(payload.routeId, payload.sessionId, "waiting"))
    PushEvent.COMPLETE -> withHome(home.withStatus(payload.routeId, payload.sessionId, "idle"))
    else -> this
}

internal fun watchGlanceNeedsRefresh(glance: WatchGlance, now: Long, running: Boolean): Boolean =
    !running && glance.home.paired && glance.home.refreshedAt.let { it == null || now - it !in 0 until WATCH_GLANCE_REFRESH_AFTER_MILLIS }

/** Remembers the open chat's pull request, but only from a live, settled chat of the selected session. */
internal fun WatchGlance.withOpenSession(state: RemoteState, now: Long): WatchGlance {
    val routeId = state.selection.routeId ?: return this
    val sessionId = state.selection.sessionId ?: return this
    if (!state.connected || state.stale || state.preview || state.loading || state.session?.optionalText("id") != sessionId) return this
    val known = pullRequests.find { it.routeId == routeId && it.sessionId == sessionId }
    if (known?.pullRequest == state.pullRequest) return this
    return withPullRequest(routeId, sessionId, state.pullRequest, now)
}

/**
 * Reads [routeId]'s projects and their sessions into [previous], then the top session's pull
 * request. Projects missing from a complete project list lose their rows. A failed project read
 * keeps its rows; a failed snapshot keeps the remembered pull request.
 */
internal suspend fun RemoteCommands.readWatchGlance(routeId: String, pairedRoutes: Set<String>, previous: WatchGlance, now: Long): WatchGlance {
    val projects = request("projects.list").array("items").filter { it.optionalText("id") != null }
    val read = projects.take(WATCH_GLANCE_MAX_PROJECTS)
    val permits = Semaphore(WATCH_GLANCE_PARALLELISM)
    val lists = coroutineScope {
        read.map { project ->
            async {
                permits.withPermit {
                    try {
                        project to request("sessions.list", "projectId" to project.text("id")).array("items")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (swallowed: Exception) {
                        Diagnostics.caught("watchGlance.sessions", swallowed)
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }
    var home = previous.home
    lists.forEach { (project, sessions) ->
        home = mergeHomeSnapshot(home, HomeInputs(pairedRoutes.toList(), RemoteSelection(routeId, project.text("id")), true, project, sessions, true), true, pairedRoutes, now)
    }
    if (projects.size <= WATCH_GLANCE_MAX_PROJECTS) {
        val listed = projects.map { it.text("id") }.toSet()
        home = home.copy(sessions = home.sessions.filter { it.routeId != routeId || it.projectId in listed })
    }
    // The Mac answered, so a refresh is done even when it has no projects or every list failed.
    home = home.copy(refreshedAt = now)
    var next = previous.withHome(home)
    val top = watchTileRows(next, now).firstOrNull { it.routeId == routeId } ?: return next
    try {
        val data = request("session.snapshot", "sessionId" to top.sessionId)
        require(data.text("kind") == "snapshot" && data.text("sessionId") == top.sessionId)
        // Absent means none, as in the app's own snapshot; a malformed value is dropped too.
        next = next.withPullRequest(routeId, top.sessionId, pullRequestStatus(data["pullRequest"]), now)
    } catch (e: CancellationException) {
        throw e
    } catch (swallowed: Exception) {
        Diagnostics.caught("watchGlance.snapshot", swallowed)
    }
    return next
}

/** [pr] in the host's wire shape, so a stored one is read back through [pullRequestStatus]. */
internal fun pullRequestJson(pr: PullRequestStatus): JsonObject = JsonObject(buildMap {
    put("number", JsonPrimitive(pr.number))
    put("url", JsonPrimitive(pr.url))
    put("draft", JsonPrimitive(pr.draft))
    put("review", when (pr.review) {
        PullRequestReview.APPROVED -> JsonPrimitive("approved")
        PullRequestReview.CHANGES_REQUESTED -> JsonPrimitive("changes_requested")
        PullRequestReview.REVIEW_REQUIRED -> JsonPrimitive("review_required")
        PullRequestReview.NONE -> JsonNull
    })
    put("checks", Wire.objectOf("state" to pr.checks.name.lowercase(), "failed" to pr.failedChecks))
    if (pr.failingChecks.isNotEmpty()) put("failingChecks", JsonArray(pr.failingChecks.map(::JsonPrimitive)))
})

internal fun WatchGlance.json(): JsonObject = Wire.objectOf(
    "version" to 1,
    "home" to home.json(),
    "pullRequests" to JsonArray(pullRequests.map {
        Wire.objectOf("routeId" to it.routeId, "sessionId" to it.sessionId, "seenAt" to it.seenAt, "pullRequest" to pullRequestJson(it.pullRequest))
    }),
)

/** A stored glance; unreadable pull request entries are dropped rather than failing the rows. */
internal fun watchGlance(value: JsonObject): WatchGlance {
    require(value.long("version") == 1L)
    return WatchGlance(
        homeSnapshot(value.getValue("home").jsonObject),
        (value["pullRequests"] as? JsonArray).orEmpty().take(MAX_HOME_SESSIONS).mapNotNull { element ->
            try {
                val item = element.jsonObject
                SeenPullRequest(item.text("routeId"), item.text("sessionId"), pullRequestStatus(item["pullRequest"]) ?: return@mapNotNull null, item.long("seenAt"))
            } catch (_: Exception) {
                null
            }
        },
    )
}
