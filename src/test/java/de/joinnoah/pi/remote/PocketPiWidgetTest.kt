package de.joinnoah.pi.remote

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import androidx.test.core.app.ApplicationProvider
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The widget's freshness line, its large tablet layout and the picker preview. The widget content
 * is composed into RemoteViews and inflated, as the launcher would, without the app's stored
 * snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class PocketPiWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun use24HourClock() {
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
    }

    private fun at(day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance()
            .apply {
                clear()
                set(2026, Calendar.OCTOBER, day, hour, minute)
            }
            .timeInMillis

    @Test
    fun theFreshnessLineShowsTheTimeOfTheLastUpdate() {
        val refreshedAt = at(13, 14, 2)
        assertEquals("Updated 14:02", widgetFreshness(context, refreshedAt, now = at(13, 18, 30)))
        assertEquals("14:02", widgetFreshness(context, refreshedAt, now = at(13, 18, 30), short = true))
        val german =
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocale(Locale.GERMAN) }
            )
        assertEquals("Stand: 14:02", widgetFreshness(german, refreshedAt, now = at(13, 18, 30)))
    }

    @Test
    fun anUpdateBeforeTodayShowsItsDate() {
        val refreshedAt = at(12, 14, 2)
        val label = widgetFreshness(context, refreshedAt, now = at(13, 9, 0))
        assertEquals(
            "Updated " + DateFormat.getDateFormat(context).format(Date(refreshedAt)),
            label,
        )
        assertFalse(label.contains("14:02"))
    }

    private class Widget(private val snapshot: HomeSnapshot, private val now: Long) : GlanceAppWidget() {
        override suspend fun provideGlance(context: Context, id: GlanceId) {
            provideContent { GlanceTheme { WidgetContent(snapshot, now) } }
        }
    }

    private fun render(snapshot: HomeSnapshot, size: DpSize, now: Long): List<String> {
        val views = runBlocking { Widget(snapshot, now).compose(context, size = size) }
        val root = views.apply(context, FrameLayout(context))
        return texts(root)
    }

    private fun texts(view: View): List<String> =
        when (view) {
            is TextView -> listOf(view.text.toString()).filter { it.isNotEmpty() }
            is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else -> emptyList()
        }

    private val running =
        HomeSnapshot(
            paired = true,
            sessions =
                (1..20).map {
                    HomeSession("route", "p$it", "Project $it", "s$it", "Session $it", "running", it.toLong())
                },
            refreshedAt = at(13, 14, 2),
        )

    @Test
    fun theLargeTabletSizeShowsThreeColumnsWithStatusAndProject() {
        assertTrue(DpSize(600.dp, 400.dp) in SIZES)
        val shown = render(running, DpSize(600.dp, 400.dp), now = at(13, 15, 0))
        assertTrue(shown.contains("Updated 14:02"))
        // Three columns of six rows: the 18 most recent of the 20 running sessions.
        val titles = shown.filter { it.startsWith("Session ") }
        assertEquals((20 downTo 3).map { "Session $it" }.toSet(), titles.toSet())
        assertTrue(shown.contains("Project 20 · Running"))
        assertTrue(shown.contains("Project 3 · Running"))
    }

    @Test
    fun aPhoneSizedWidgetShowsFewerSessionsAndOnlyTheTime() {
        val shown = render(running, DpSize(180.dp, 175.dp), now = at(13, 15, 0))
        assertEquals(3, shown.count { it.startsWith("Session ") })
        assertTrue(shown.contains("14:02"))
        assertFalse(shown.contains("Updated 14:02"))
    }

    @Test
    fun thePreviewLayoutInflatesAsRemoteViews() {
        // RemoteViews refuses views it does not allow, as the widget picker would.
        val preview =
            RemoteViews(context.packageName, R.layout.pocketpi_widget_preview)
                .apply(context, FrameLayout(context))
        val shown = texts(preview)
        assertTrue(shown.contains(context.getString(R.string.remote_widget_preview_header)))
        assertTrue(shown.contains(context.getString(R.string.remote_widget_preview_waiting_title)))
        assertEquals(R.layout.pocketpi_widget_preview, providerAttribute("previewLayout"))
    }

    @Test
    fun aPhonePlacesTheWidgetThreeCellsWide() {
        assertEquals(3, providerAttribute("targetCellWidth"))
    }

    @Test
    @Config(qualifiers = "en-sw600dp")
    fun aTabletPlacesTheWidgetFourCellsWide() {
        assertEquals(4, providerAttribute("targetCellWidth"))
        assertEquals(R.layout.pocketpi_widget_preview, providerAttribute("previewLayout"))
    }

    /** An attribute of the widget provider as resolved for the current configuration. */
    private fun providerAttribute(name: String): Int {
        val parser = context.resources.getXml(R.xml.pocketpi_widget_info)
        while (parser.next() != XmlPullParser.START_TAG) Unit
        val namespace = "http://schemas.android.com/apk/res/android"
        val resource = parser.getAttributeResourceValue(namespace, name, 0)
        return if (resource != 0) resource else parser.getAttributeIntValue(namespace, name, -1)
    }
}
