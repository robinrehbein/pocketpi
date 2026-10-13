package de.joinnoah.pi.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MainActivity is exported for the launcher and the watch, so it must not let any app open a
 * session through intent extras: those count only on the app's own non-exported alias.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityIntentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun withExtras(intent: Intent, route: String = "route", session: String = "session") =
        intent
            .setAction(Intent.ACTION_VIEW)
            .putExtra(RemoteNotifications.EXTRA_ROUTE, route)
            .putExtra(RemoteNotifications.EXTRA_TARGET, session)

    private fun exported() = Intent(context, MainActivity::class.java)

    private fun alias() = Intent().setComponent(ComponentName(context.packageName, LaunchIntents.ALIAS))

    private fun watch(route: String = "route", session: String = "session") =
        Intent(Intent.ACTION_VIEW, Uri.parse("pocketpi://session?routeId=$route&sessionId=$session"))

    /** What MainActivity would open after starting with [intent], as it saves it for a restart. */
    private fun pendingAfterStart(intent: Intent): Pair<String, String>? {
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create()
        val state = Bundle()
        controller.saveInstanceState(state)
        val route = state.getString("pendingRouteId") ?: return null
        return route to checkNotNull(state.getString("pendingSessionId"))
    }

    @Test
    fun extrasOnTheExportedActivityAreIgnored() {
        assertNull(LaunchIntents.target(withExtras(exported())))
        assertNull(LaunchIntents.target(withExtras(exported()).setData(Uri.parse("pocketpi://session/route/session"))))
        assertNull(LaunchIntents.target(withExtras(Intent())))
        assertNull(pendingAfterStart(withExtras(exported())))
    }

    @Test
    fun theAppsOwnIntentsGoThroughTheAliasAndAreAccepted() {
        assertEquals(
            NotificationTarget("route", "session"),
            LaunchIntents.target(LaunchIntents.session(context, "route", "session")),
        )
        assertEquals(
            NotificationTarget("route", "session", jobs = true, jobId = "job_1"),
            LaunchIntents.target(
                LaunchIntents.notification(context, NotificationTarget("route", "session", jobs = true, jobId = "job_1"))
            ),
        )
        assertEquals(LaunchIntents.ALIAS, LaunchIntents.app(context).component?.className)
        assertNull(LaunchIntents.target(LaunchIntents.app(context)))
        assertEquals("route" to "session", pendingAfterStart(LaunchIntents.session(context, "route", "session")))
    }

    @Test
    fun anAliasLaunchIntoTheRunningActivityOpensItsSession() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, exported()).create()
        controller.newIntent(LaunchIntents.session(context, "route", "later"))
        val state = Bundle()
        controller.saveInstanceState(state)
        assertEquals("later", state.getString("pendingSessionId"))
    }

    @Test
    fun identifiersThatAreNotOpaqueAreRejected() {
        listOf("", "a b", "line\nbreak", "x".repeat(257)).forEach { bad ->
            assertNull(bad, LaunchIntents.target(withExtras(alias(), route = bad)))
            assertNull(bad, LaunchIntents.target(withExtras(alias(), session = bad)))
        }
        assertNull(LaunchIntents.target(alias().putExtra(RemoteNotifications.EXTRA_ROUTE, "route")))
        // A job ID that is not a strict push ID still opens the session's job list.
        assertEquals(
            NotificationTarget("route", "session", jobs = true),
            LaunchIntents.target(
                withExtras(alias()).putExtra(RemoteNotifications.EXTRA_JOBS, true).putExtra(RemoteNotifications.EXTRA_JOB, "job 1")
            ),
        )
    }

    @Test
    fun aLaunchFromRecentsOpensNoSession() {
        listOf(LaunchIntents.session(context, "route", "session"), watch()).forEach {
            assertNull(LaunchIntents.target(it.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
        }
    }

    @Test
    fun theWatchHandoffStaysOnTheExportedActivity() {
        assertEquals(NotificationTarget("route", "session"), LaunchIntents.target(watch()))
        assertEquals(NotificationTarget("route", "session"), LaunchIntents.target(watch().setComponent(exported().component)))
        assertNull(LaunchIntents.target(watch(session = "a%20b")))
        assertEquals("route" to "session", pendingAfterStart(watch().setComponent(exported().component)))
    }

    @Test
    fun aWatchHandoffOpensAPairedSessionAndFallsBackToHostsForAnUnknownRoute() = runTest {
        val repository = NavigationFakeRepository()
        val paired = RemoteSelection("route", "project", "session")
        // As the real repository: only a paired host's session resolves (RemoteRepositoryTest).
        repository.notificationLookup = {
            paired.takeIf { repository.notificationRequests.last() == ("route" to "session") }
        }
        fun open(intent: Intent, stack: MutableList<NavKey>) {
            val target = checkNotNull(LaunchIntents.target(intent))
            RemoteNavigator(repository, stack, backgroundScope).notification(target.routeId, target.sessionId)
        }

        val opened = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        open(watch(), opened)
        runCurrent()
        assertEquals(paired.keys(), opened)

        val unknown = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        open(watch(route = "unknown"), unknown)
        runCurrent()
        assertEquals(listOf<NavKey>(RemoteNavKey.Hosts), unknown)
        assertEquals(listOf("route" to "session", "unknown" to "session"), repository.notificationRequests)
    }
}
