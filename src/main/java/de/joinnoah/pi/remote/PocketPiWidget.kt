package de.joinnoah.pi.remote

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity as actionStartIntent
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as GlanceColor
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val WidgetColors =
    ColorProviders(light = remoteColorScheme(false), dark = remoteColorScheme(true))

private val WaitingTone = ColorProvider(day = WaitingLight, night = WaitingDark)

// Responsive buckets: the launcher picks the largest one that fits, so rows never clip. They cover
// 2x1 to 4x3 phone cells and the wide, tall cells of a tablet home screen, where a widget of four
// or more cells gets three columns.
internal val SIZES =
    setOf(
        DpSize(180.dp, 80.dp),
        DpSize(180.dp, 130.dp),
        DpSize(180.dp, 175.dp),
        DpSize(260.dp, 130.dp),
        DpSize(260.dp, 175.dp),
        DpSize(400.dp, 130.dp),
        DpSize(400.dp, 175.dp),
        DpSize(520.dp, 240.dp),
        DpSize(600.dp, 240.dp),
        DpSize(600.dp, 400.dp),
    )

/** From this width the freshness label says "Updated"; a narrower widget shows only the time. */
private const val WIDGET_FRESHNESS_LABEL_DP = 260

/**
 * "N sessions waiting for you" plus the sessions that need attention. It renders the app's stored
 * home snapshot and never touches the network; the app updates it from its state and from pushes.
 */
class PocketPiWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(SIZES)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val home = (context.applicationContext as RemoteApplication).home
        home.load()
        WidgetNewDay.schedule(context)
        provideContent {
            val snapshot by home.snapshot.collectAsState()
            // Read so that a running widget recomposes when a new day begins.
            val newDay by WidgetNewDay.began.collectAsState()
            GlanceTheme(colors = WidgetColors) {
                WidgetContent(snapshot, now = maxOf(newDay, System.currentTimeMillis()))
            }
        }
    }
}

class PocketPiWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PocketPiWidget()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetNewDay.ACTION) return super.onReceive(context, intent)
        WidgetNewDay.began.value = System.currentTimeMillis()
        WidgetNewDay.schedule(context)
        val pending: PendingResult? = goAsync()
        scope.launch {
            try {
                glanceAppWidget.updateAll(context)
            } catch (swallowed: Exception) {
                Diagnostics.caught("widget.newDay", swallowed)
            } finally {
                pending?.finish()
            }
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetNewDay.cancel(context)
    }

    private companion object {
        val scope =
            CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> }
            )
    }
}

/**
 * Redraws the widget at each local midnight, when a refresh time from earlier today becomes
 * yesterday's and the freshness line must show its date instead. Nothing else may redraw the
 * widget for days, and "Updated 14:02" would then read as today's.
 */
internal object WidgetNewDay {
    const val ACTION = "de.joinnoah.pi.remote.action.WIDGET_NEW_DAY"

    /** Up to ten minutes late is fine, and a window that long needs no exact-alarm permission. */
    private const val WINDOW_MS = 10 * 60_000L

    /** When the last new day began in this process; the widget content reads it to recompose. */
    val began = MutableStateFlow(0L)

    /**
     * Sets the alarm for the next midnight, replacing an earlier one. It does not wake the phone:
     * a sleeping phone shows no widget, and the alarm fires once the screen is on again.
     */
    fun schedule(context: Context, now: Long = System.currentTimeMillis()) {
        try {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            alarms.setWindow(AlarmManager.RTC, nextLocalMidnight(now), WINDOW_MS, intent(context))
        } catch (swallowed: Exception) {
            Diagnostics.caught("widget.scheduleNewDay", swallowed)
        }
    }

    /** The last widget was removed. */
    fun cancel(context: Context) {
        try {
            context.getSystemService(AlarmManager::class.java)?.cancel(intent(context))
        } catch (_: Exception) {}
    }

    private fun intent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, PocketPiWidgetReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** The start of the local day after [now]. */
internal fun nextLocalMidnight(now: Long): Long =
    Calendar.getInstance()
        .apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        .timeInMillis

@Composable
internal fun WidgetContent(snapshot: HomeSnapshot, now: Long = System.currentTimeMillis()) {
    val context = RemoteNotifications.localized(LocalContext.current)
    val size = LocalSize.current
    val (columns, rows) =
        widgetLayout(size.width.value, size.height.value, context.resources.configuration.fontScale)
    // The rows' own vertical padding spaces them from the header and from the bottom edge.
    Column(
        GlanceModifier.fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .launcherCorners()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp)
            .clickable(actionStartIntent(LaunchIntents.app(LocalContext.current))),
    ) {
        if (!snapshot.paired) {
            Title(context.getString(R.string.remote_widget_not_paired))
            Spacer(GlanceModifier.height(4.dp))
            Detail(context.getString(R.string.remote_widget_pair_hint), maxLines = 3)
            return@Column
        }
        val waiting = snapshot.waitingCount()
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Title(
                if (waiting == 0) context.getString(R.string.remote_widget_none_waiting)
                else context.resources.getQuantityString(R.plurals.remote_widget_waiting, waiting, waiting),
                GlanceModifier.defaultWeight(),
            )
            snapshot.refreshedAt?.let { refreshedAt ->
                val label = widgetFreshness(context, refreshedAt, now)
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    if (size.width.value >= WIDGET_FRESHNESS_LABEL_DP) label
                    else widgetFreshness(context, refreshedAt, now, short = true),
                    modifier = GlanceModifier.semantics { contentDescription = label },
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                )
            }
        }
        val sessions = widgetSessions(snapshot, rows * columns)
        if (sessions.isEmpty()) {
            Spacer(GlanceModifier.height(6.dp))
            Detail(context.getString(R.string.remote_widget_empty), maxLines = 2)
            return@Column
        }
        if (columns == 1) {
            sessions.forEach { SessionRow(context, it, GlanceModifier.fillMaxWidth()) }
        } else {
            Row(GlanceModifier.fillMaxWidth()) {
                sessions.chunked(rows).take(columns).forEachIndexed { index, column ->
                    if (index > 0) Spacer(GlanceModifier.width(12.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        column.forEach { SessionRow(context, it, GlanceModifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }
}

/**
 * When the widget's rows were last confirmed by the host: "Updated 14:02", or the date once that
 * was before today. [short] leaves out the word, for a narrow widget. The choice depends on [now],
 * so [WidgetNewDay] redraws the widget when the day changes.
 */
internal fun widgetFreshness(context: Context, refreshedAt: Long, now: Long, short: Boolean = false): String {
    val format =
        if (sameDay(refreshedAt, now)) DateFormat.getTimeFormat(context)
        else DateFormat.getDateFormat(context)
    val time = format.format(Date(refreshedAt))
    return if (short) time else context.getString(R.string.remote_widget_updated, time)
}

private fun sameDay(first: Long, second: Long): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = first }
    val b = Calendar.getInstance().apply { timeInMillis = second }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

/** The launcher's own widget corner radius; Glance rounds corners only from Android 12 on. */
private fun GlanceModifier.launcherCorners(): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        cornerRadius(android.R.dimen.system_app_widget_background_radius)
    else this

@Composable
private fun Title(text: String, modifier: GlanceModifier = GlanceModifier) {
    Text(
        text,
        modifier = modifier,
        maxLines = 1,
        style =
            TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
    )
}

@Composable
private fun Detail(text: String, maxLines: Int = 1, color: GlanceColor? = null) {
    Text(
        text,
        maxLines = maxLines,
        style =
            TextStyle(
                color = color ?: GlanceTheme.colors.onSurfaceVariant,
                fontSize = 12.sp,
            ),
    )
}

@Composable
private fun SessionRow(context: Context, session: HomeSession, modifier: GlanceModifier) {
    val title = session.title.ifBlank { context.getString(R.string.remote_widget_untitled) }
    val waiting = session.status == "waiting"
    val state =
        context.getString(
            if (waiting) R.string.remote_widget_state_waiting
            else R.string.remote_widget_state_running
        )
    val detail =
        if (session.projectName.isBlank()) state
        else context.getString(R.string.remote_widget_row_detail, session.projectName, state)
    // The vertical padding makes the row a 48 dp tap target (see widgetRowDp).
    Row(
        modifier
            .padding(vertical = WIDGET_ROW_PADDING_DP.dp)
            .clickable(
                actionStartIntent(LaunchIntents.session(context, session.routeId, session.sessionId))
            )
            .semantics { contentDescription = "$title, $detail" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            GlanceModifier.size(8.dp)
                .cornerRadius(4.dp)
                .background(if (waiting) WaitingTone else GlanceTheme.colors.primary),
        ) {}
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(
                title,
                maxLines = 1,
                style =
                    TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
            )
            Detail(detail, color = if (waiting) WaitingTone else null)
        }
    }
}
