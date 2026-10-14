package de.joinnoah.pi.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionMarksStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val host = PairedHost("host", "wss://relay.test", "device", "secret", "Studio Mac")

    @Before
    fun clearPrefs() {
        context.getSharedPreferences(SessionMarksStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun clearPrefsAfter() = clearPrefs()

    @Test
    fun pinsAndSeenMarksSurviveARestart() {
        val first = SessionMarksStore(context)
        first.setPinned("host", "s1", true)
        first.markSeen("host", "s1", updatedAt = 100, now = 200)
        first.markFinished("host", "s2", now = 300)

        val restored = SessionMarksStore(context)

        assertEquals(
            SessionMark(pinned = true, seenAt = 200, seenUpdatedAt = 100),
            restored.mark("host", "s1"),
        )
        assertEquals(SessionMark(finishedAt = 300), restored.mark("host", "s2"))
    }

    @Test
    fun aMarkThatIsNoLongerSetIsForgotten() {
        val store = SessionMarksStore(context)
        store.setPinned("host", "s1", true)
        store.setPinned("host", "s1", false)

        assertNull(store.mark("host", "s1"))
        assertTrue(context.getSharedPreferences(SessionMarksStore.PREFS_NAME, Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun markingASessionReadClearsItsFinishedMark() {
        val store = SessionMarksStore(context)
        store.markFinished("host", "s1", now = 100)
        store.markSeen("host", "s1", updatedAt = 90, now = 150)

        assertEquals(SessionMark(seenAt = 150, seenUpdatedAt = 90), store.mark("host", "s1"))
    }

    @Test
    fun unreadMeansChangedOrFinishedSinceTheLastLookAndNeverWhileOpen() {
        val seen = SessionMark(seenAt = 150, seenUpdatedAt = 100)
        assertFalse(sessionUnread(null, updatedAt = 200, open = false))
        assertFalse(sessionUnread(SessionMark(pinned = true), updatedAt = 200, open = false))
        assertFalse(sessionUnread(seen, updatedAt = 100, open = false))
        assertTrue(sessionUnread(seen, updatedAt = 200, open = false))
        assertFalse(sessionUnread(seen, updatedAt = 200, open = true))
        assertTrue(sessionUnread(SessionMark(finishedAt = 160), updatedAt = null, open = false))
        assertFalse(sessionUnread(SessionMark(finishedAt = 160), updatedAt = null, open = true))
    }

    @Test
    fun pruningWaitsForLoadedHostsAndDropsRoutesNoLongerPaired() {
        val store = SessionMarksStore(context)
        store.setPinned("gone", "s1", true)
        store.setPinned("host", "s2", true)

        store.prune(hostRoutes = emptySet(), hostsLoaded = false)
        assertEquals(2, store.marks.value.size)

        store.prune(hostRoutes = setOf("host"), hostsLoaded = true)
        assertNull(store.mark("gone", "s1"))
        assertTrue(store.mark("host", "s2")?.pinned == true)
        assertEquals(1, SessionMarksStore(context).marks.value.size)
    }

    @Test
    fun aRunThatEndsWhileTheChatIsNotOpenIsMarkedFinished() = runTest {
        val store = SessionMarksStore(context)
        val state = MutableStateFlow(stateWith(status = "running", open = "s2"))
        store.observe(state, backgroundScope, now = { 500L })
        runCurrent()

        // s1 ran in the background and ended; s2 ended while its chat was open.
        state.value = stateWith(status = "idle", open = "s2")
        runCurrent()

        assertEquals(SessionMark(finishedAt = 500), store.mark("host", "s1"))
        assertNull(store.mark("host", "s2"))
    }

    @Test
    fun theFirstObservationOfAHostMarksNothing() = runTest {
        val store = SessionMarksStore(context)
        val state = MutableStateFlow(stateWith(status = "idle", open = null))
        store.observe(state, backgroundScope, now = { 500L })
        runCurrent()

        assertTrue(store.marks.value.isEmpty())
    }

    private fun stateWith(status: String, open: String?): RemoteState {
        val session = Wire.objectOf("id" to "s1", "title" to "One", "status" to status, "updatedAt" to 10L)
        val other = Wire.objectOf("id" to "s2", "title" to "Two", "status" to status, "updatedAt" to 10L)
        val rows: List<JsonObject> = listOf(session, other)
        return RemoteState(
            hosts = listOf(host),
            hostsLoaded = true,
            host = host,
            selection = RemoteSelection(host.routeId, "project", open),
            connected = true,
            session = open?.let { session },
            projectSessions = mapOf("project" to projectSessions("project", "Project", rows, verified = true)),
        )
    }
}
