package de.joinnoah.pi.remote

import android.app.Application
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WatchComplicationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val resources get() = context.resources
    private val now = 10 * 3_600_000L
    private fun row(id: String, status: String, title: String = "Title $id") = HomeSession("route", "p", "Project", id, title, status, 0L)
    private fun glance(vararg rows: HomeSession) = WatchGlance(HomeSnapshot(true, rows.toList(), now - 60_000L))
    private fun pr() = PullRequestStatus(189, "https://github.com/acme/app/pull/189", false, PullRequestReview.NONE, PullRequestChecksState.DONE, 2, emptyList())

    @Test fun `an unpaired watch asks to pair and opens the app`() {
        val content = watchComplicationContent(WatchGlance(), resources, now)
        assertEquals("Pair", content.shortText)
        assertEquals("Not paired", content.longText)
        assertNull(content.target)
        assertTrue(content.description.contains("pair", ignoreCase = true))
    }
    @Test fun `waiting sessions are counted and the top one is named with its checks`() {
        val base = glance(row("a", "running"), row("b", "waiting"), row("c", "waiting")).withPullRequest("route", "b", pr(), now - 5 * 60_000L)
        val content = watchComplicationContent(base, resources, now)
        assertEquals("2", content.shortText)
        assertEquals("waiting", content.shortTitle)
        // Equal ages keep the listed order, so the first waiting session leads.
        assertEquals("b", content.target?.sessionId)
        assertEquals("Title b", content.longText)
        assertEquals("needs you · PR #189 · 2 failing · 5 min", content.longTitle)
        assertTrue(content.description.startsWith(resources.getQuantityString(R.plurals.remote_widget_waiting, 2, 2)))
    }
    @Test fun `with nothing waiting the working sessions are counted`() {
        val content = watchComplicationContent(glance(row("a", "running", title = ""), row("b", "idle")), resources, now)
        assertEquals("1", content.shortText)
        assertEquals("working", content.shortTitle)
        assertEquals(resources.getString(R.string.remote_widget_untitled), content.longText)
        assertEquals("working", content.longTitle)
        assertEquals("a", content.target?.sessionId)
        assertTrue(content.description.startsWith("1 session is working"))
    }
    @Test fun `an idle Mac shows zero waiting and opens the app`() {
        val content = watchComplicationContent(glance(row("a", "idle")), resources, now)
        assertEquals("0", content.shortText)
        assertEquals("waiting", content.shortTitle)
        assertEquals("Nothing needs you", content.longText)
        assertNull(content.longTitle)
        assertNull(content.target)
    }
    @Test fun `short and long text become complication data and other types none`() {
        val content = watchComplicationContent(glance(row("a", "waiting")), resources, now)
        val short = watchComplicationData(ComplicationType.SHORT_TEXT, content, null) as ShortTextComplicationData
        assertEquals("1", short.text.getTextAt(resources, java.time.Instant.EPOCH).toString())
        assertEquals("waiting", short.title?.getTextAt(resources, java.time.Instant.EPOCH).toString())
        val long = watchComplicationData(ComplicationType.LONG_TEXT, content, null) as LongTextComplicationData
        assertEquals("Title a", long.text.getTextAt(resources, java.time.Instant.EPOCH).toString())
        assertEquals("needs you", long.title?.getTextAt(resources, java.time.Instant.EPOCH).toString())
        assertNull(watchComplicationData(ComplicationType.RANGED_VALUE, content, null))
    }
    @Test fun `preview data exists for both supported types`() {
        val service = org.robolectric.Robolectric.buildService(WatchComplicationService::class.java).get()
        assertTrue(service.getPreviewData(ComplicationType.SHORT_TEXT) is ShortTextComplicationData)
        assertTrue(service.getPreviewData(ComplicationType.LONG_TEXT) is LongTextComplicationData)
        assertNull(service.getPreviewData(ComplicationType.SMALL_IMAGE))
    }
    @Test fun `a tap opens the session through the tile trampoline`() {
        val target = watchComplicationContent(glance(row("a", "waiting")), resources, now).target
        val intent = shadowOf(watchComplicationTap(context, target)).savedIntent
        assertEquals(ComponentName(context, WatchTileActivity::class.java), intent.component)
        assertEquals(NotificationTarget("route", "a"), WatchTileTarget.target(intent.extras))
        val app = shadowOf(watchComplicationTap(context, null)).savedIntent
        assertNull(WatchTileTarget.target(app.extras))
    }
    @Test fun `requesting an update reaches the system without failing`() {
        requestWatchComplicationUpdate(context)
    }
    @Test fun `chat pull request summary leaves out status and age`() {
        assertEquals("PR #189 · 2 failing", watchPullRequestSummary(pullRequestChipModel(pr()), resources))
    }
}
