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
        store.chatShown("host", "s2")
        runCurrent()

        // s1 ran in the background and ended; s2 ended while its chat was on screen.
        state.value = stateWith(status = "idle", open = "s2")
        runCurrent()

        assertEquals(SessionMark(finishedAt = 500), store.mark("host", "s1"))
        assertNull(store.mark("host", "s2"))
    }

    @Test
    fun aSelectedChatThatIsCoveredOrInTheBackgroundDoesNotCountAsOpen() = runTest {
        val store = SessionMarksStore(context)
        val state = MutableStateFlow(stateWith(status = "running", open = "s2"))
        store.observe(state, backgroundScope, now = { 500L })
        store.chatShown("host", "s2")
        runCurrent()
        // The board opens over the chat (or the app pauses): the selection stays, the chat leaves.
        store.chatHidden("host", "s2")

        state.value = stateWith(status = "idle", open = "s2")
        runCurrent()

        assertEquals(SessionMark(finishedAt = 500), store.mark("host", "s2"))
    }

    @Test
    fun aHiddenChatDoesNotHideTheChatThatTookItsPlace() {
        val store = SessionMarksStore(context)
        store.chatShown("host", "s1")
        store.chatShown("host", "s2")
        store.chatHidden("host", "s1")

        assertEquals("host" to "s2", store.visibleChat.value)
    }

    @Test
    fun seeingASessionAgainAtTheSameUpdateWritesNothing() {
        val store = SessionMarksStore(context)
        store.markSeen("host", "s1", updatedAt = 100, now = 200)
        val first = store.marks.value

        store.markSeen("host", "s1", updatedAt = 100, now = 900)

        assertTrue(first === store.marks.value)
        store.markSeen("host", "s1", updatedAt = 150, now = 950)
        assertEquals(SessionMark(seenAt = 200, seenUpdatedAt = 150), store.mark("host", "s1"))
    }

    @Test
    fun theMarksOfOneHostAreCappedAndKeepPinnedAndFinishedSessions() {
        val store = SessionMarksStore(context)
        store.setPinned("host", "pinned", true)
        store.markFinished("host", "finished", now = 1)
        repeat(SessionMarksStore.MAX_PLAIN_MARKS_PER_ROUTE + 20) { index ->
            store.markSeen("host", "s$index", updatedAt = index + 10L, now = index + 10L)
        }
        store.markSeen("other", "o1", updatedAt = 1, now = 1)

        val host = store.marks.value.keys.filter { it.first == "host" }
        assertEquals(SessionMarksStore.MAX_PLAIN_MARKS_PER_ROUTE + 2, host.size)
        assertNull(store.mark("host", "s0"))
        assertTrue(store.mark("host", "s${SessionMarksStore.MAX_PLAIN_MARKS_PER_ROUTE + 19}") != null)
        assertTrue(store.mark("host", "pinned")?.pinned == true)
        assertTrue(store.mark("host", "finished") != null)
        assertTrue(store.mark("other", "o1") != null)
    }

    @Test
    fun theFirstObservationOfAHostMarksNothing() = runTest {
        val store = SessionMarksStore(context)
        val state = MutableStateFlow(stateWith(status = "idle", open = null))
        store.observe(state, backgroundScope, now = { 500L })
        runCurrent()

        assertTrue(store.marks.value.isEmpty())
    }

    @Test
    fun aChatOpenedFromAPreviewIsSelectedOnlyOnceTheRepositoryConfirmedIt() {
        val cached = Wire.objectOf("id" to "s1", "title" to "One", "status" to "idle", "updatedAt" to 10L)
        val preview = RemoteState(loading = true, preview = true, session = cached)
        assertFalse(chatSelected(preview, "s1"))

        val selected = preview.copy(selection = RemoteSelection(host.routeId, "project", "s1"))
        assertTrue(chatSelected(selected, "s1"))
        assertFalse(chatSelected(selected, "s2"))
    }

    @Test
    fun theChatsUpdateTimeComesFromTheLiveListRow() {
        val snapshot = Wire.objectOf("id" to "s1", "title" to "One", "status" to "running", "updatedAt" to 10L)
        val row = Wire.objectOf("id" to "s1", "title" to "One", "status" to "running", "updatedAt" to 40L)
        val state =
            RemoteState(
                selection = RemoteSelection(host.routeId, "project", "s1"),
                session = snapshot,
                sessions = listOf(row),
            )

        assertEquals(40L, liveUpdatedAt(state, "s1"))
        assertEquals(10L, liveUpdatedAt(state.copy(sessions = emptyList()), "s1"))
        assertNull(liveUpdatedAt(state.copy(sessions = emptyList(), session = null), "s1"))
    }

    @Test
    fun theBoardTreatsOnlyTheChatOnScreenAsOpen() {
        val row = Wire.objectOf("id" to "s1", "title" to "One", "status" to "idle", "updatedAt" to 50L)
        val state =
            stateWith(status = "idle", open = "s1").copy(sessions = listOf(row))
        val marks = mapOf("s1" to SessionMark(seenAt = 1, seenUpdatedAt = 10))

        // Selected under the board but not on screen: the change since the last look shows.
        assertTrue(activityCards(state, marks, visibleSessionId = null).first { it.sessionId == "s1" }.unread)
        assertFalse(activityCards(state, marks, visibleSessionId = "s1").first { it.sessionId == "s1" }.unread)
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

    @Test
    fun hidingSetsTheHideAndSeenFieldsAndClearsAFinish() {
        val store = SessionMarksStore(context)
        store.markFinished("host", "s1", now = 50)

        val previous = store.hide("host", "s1", updatedAt = 100, now = 200)

        assertEquals(SessionMark(finishedAt = 50), previous)
        assertEquals(
            SessionMark(seenAt = 200, seenUpdatedAt = 100, hiddenUpdatedAt = 100, hiddenAt = 200),
            store.mark("host", "s1"),
        )
        store.unhide("host", "s1")
        assertEquals(SessionMark(seenAt = 200, seenUpdatedAt = 100), store.mark("host", "s1"))
    }

    @Test
    fun aRowWithoutUpdatedAtHidesAtZero() {
        val store = SessionMarksStore(context)
        store.hide("host", "s1", updatedAt = null, now = 200)
        assertEquals(0L, store.mark("host", "s1")?.hiddenUpdatedAt)
    }

    @Test
    fun undoingAHideRestoresTheSeenAndFinishedFields() {
        val store = SessionMarksStore(context)
        store.markSeen("host", "s1", updatedAt = 50, now = 60)
        store.markFinished("host", "s1", now = 70)
        val before = store.mark("host", "s1")
        val previous = store.hide("host", "s1", updatedAt = 100, now = 200)
        val applied = store.mark("host", "s1")

        store.undoHide("host", "s1", previous, applied)

        assertEquals(before, store.mark("host", "s1"))
    }

    @Test
    fun undoingAHideKeepsWhatHappenedAfterIt() {
        val store = SessionMarksStore(context)
        val previous = store.hide("host", "s1", updatedAt = 100, now = 200)
        val applied = store.mark("host", "s1")
        store.markFinished("host", "s1", now = 300)
        store.markSeen("host", "s2", updatedAt = 1, now = 1)

        store.undoHide("host", "s1", previous, applied)

        assertEquals(SessionMark(seenAt = 200, seenUpdatedAt = 100, finishedAt = 300), store.mark("host", "s1"))
    }

    @Test
    fun undoingAHideAfterANewerLookKeepsTheLook() {
        val store = SessionMarksStore(context)
        val previous = store.hide("host", "s1", updatedAt = 100, now = 200)
        val applied = store.mark("host", "s1")
        store.markSeen("host", "s1", updatedAt = 150, now = 250)

        store.undoHide("host", "s1", previous, applied)

        assertEquals(SessionMark(seenAt = 200, seenUpdatedAt = 150), store.mark("host", "s1"))
    }

    @Test
    fun pinningShowsAHiddenSessionAgain() {
        val store = SessionMarksStore(context)
        store.hide("host", "s1", updatedAt = 100, now = 200)

        store.setPinned("host", "s1", true)

        val mark = store.mark("host", "s1")
        assertTrue(mark?.pinned == true)
        assertNull(mark?.hiddenUpdatedAt)
        assertNull(mark?.hiddenAt)
    }

    @Test
    fun marksWithFourOrSixFieldsLoadAndOnlyHiddenOnesWriteSix() {
        val prefs = context.getSharedPreferences(SessionMarksStore.PREFS_NAME, Context.MODE_PRIVATE)
        val sep = "\u001F"
        prefs.edit().putString("host" + sep + "old", "1,5,6,").putString("host" + sep + "bad", "1,2,3,4,5").commit()

        val store = SessionMarksStore(context)
        assertEquals(SessionMark(pinned = true, seenAt = 5, seenUpdatedAt = 6), store.mark("host", "old"))
        assertNull(store.mark("host", "bad"))

        store.hide("host", "h", updatedAt = 100, now = 200)
        store.markSeen("host", "plain", updatedAt = 1, now = 2)
        assertEquals("0,200,100,,100,200", prefs.getString("host" + sep + "h", null))
        assertEquals("0,2,1,", prefs.getString("host" + sep + "plain", null))
        assertEquals(store.mark("host", "h"), SessionMarksStore(context).mark("host", "h"))
    }

    @Test
    fun aFreshHideOfAnOldSessionSurvivesTheCap() {
        val store = SessionMarksStore(context)
        store.markSeen("host", "old", updatedAt = 1, now = 1)
        repeat(SessionMarksStore.MAX_PLAIN_MARKS_PER_ROUTE - 1) { index ->
            store.markSeen("host", "s$index", updatedAt = index + 10L, now = index + 10L)
        }
        store.hide("host", "old", updatedAt = 1, now = System.currentTimeMillis())
        store.markSeen("host", "extra", updatedAt = 5000, now = 5000)

        assertTrue(store.mark("host", "old")?.hiddenUpdatedAt != null)
        assertNull(store.mark("host", "s0"))
    }

    @Test
    fun hidingAnExistingMarkEvictsNothing() {
        val store = SessionMarksStore(context)
        repeat(SessionMarksStore.MAX_PLAIN_MARKS_PER_ROUTE) { index ->
            store.markSeen("host", "s$index", updatedAt = index + 10L, now = index + 10L)
        }
        store.hide("host", "s0", updatedAt = 10, now = 999)

        assertEquals(SessionMarksStore.MAX_PLAIN_MARKS_PER_ROUTE, store.marks.value.keys.count { it.first == "host" })
    }

    @Test
    fun aHiddenRowObservedRunningIsShownAgainAndStaysSoWhenItIsIdleAtTheSameUpdate() = runTest {
        val store = SessionMarksStore(context)
        store.hide("host", "s1", updatedAt = 10, now = 200)
        val state = MutableStateFlow(stateWith(status = "idle", open = null))
        store.observe(state, backgroundScope, now = { 500L })
        runCurrent()
        assertTrue(store.mark("host", "s1")?.hiddenUpdatedAt != null)

        state.value = stateWith(status = "running", open = null)
        runCurrent()
        assertNull(store.mark("host", "s1")?.hiddenUpdatedAt)

        state.value = stateWith(status = "idle", open = null)
        runCurrent()
        assertNull(store.mark("host", "s1")?.hiddenUpdatedAt)
        assertFalse(sessionHidden(store.mark("host", "s1"), "idle", 10))
    }
}
