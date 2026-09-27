package de.joinnoah.pi.remote

import android.content.Context
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

private val WidgetColors =
    ColorProviders(light = remoteColorScheme(false), dark = remoteColorScheme(true))

private val WaitingTone = ColorProvider(day = WaitingLight, night = WaitingDark)

// Responsive buckets: the launcher picks the largest one that fits, so rows never clip. They cover
// 2x1 to 4x3 phone cells and the wide, tall cells of a tablet home screen.
private val SIZES =
    setOf(
        DpSize(180.dp, 80.dp),
        DpSize(180.dp, 130.dp),
        DpSize(180.dp, 175.dp),
        DpSize(260.dp, 130.dp),
        DpSize(260.dp, 175.dp),
        DpSize(400.dp, 130.dp),
        DpSize(400.dp, 175.dp),
        DpSize(520.dp, 240.dp),
    )

/**
 * "N sessions waiting for you" plus the sessions that need attention. It renders the app's stored
 * home snapshot and never touches the network; the app updates it from its state and from pushes.
 */
class PocketPiWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(SIZES)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val home = (context.applicationContext as RemoteApplication).home
        home.load()
        provideContent {
            val snapshot by home.snapshot.collectAsState()
            GlanceTheme(colors = WidgetColors) { WidgetContent(snapshot) }
        }
    }
}

class PocketPiWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PocketPiWidget()
}

@Composable
private fun WidgetContent(snapshot: HomeSnapshot) {
    val context = RemoteNotifications.localized(LocalContext.current)
    val size = LocalSize.current
    val (columns, rows) =
        widgetLayout(size.width.value, size.height.value, context.resources.configuration.fontScale)
    Column(
        GlanceModifier.fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(20.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .clickable(actionStartIntent(appIntent(LocalContext.current))),
    ) {
        if (!snapshot.paired) {
            Title(context.getString(R.string.remote_widget_not_paired))
            Spacer(GlanceModifier.height(4.dp))
            Detail(context.getString(R.string.remote_widget_pair_hint), maxLines = 3)
            return@Column
        }
        val waiting = snapshot.waitingCount()
        Title(
            if (waiting == 0) context.getString(R.string.remote_widget_none_waiting)
            else context.resources.getQuantityString(R.plurals.remote_widget_waiting, waiting, waiting)
        )
        Spacer(GlanceModifier.height(6.dp))
        val sessions = widgetSessions(snapshot, rows * columns)
        if (sessions.isEmpty()) {
            Detail(context.getString(R.string.remote_widget_empty), maxLines = 2)
            return@Column
        }
        if (columns == 1) {
            sessions.forEach { SessionRow(context, it, GlanceModifier.fillMaxWidth()) }
        } else {
            Row(GlanceModifier.fillMaxWidth()) {
                sessions.chunked(rows).take(2).forEachIndexed { index, column ->
                    if (index > 0) Spacer(GlanceModifier.width(12.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        column.forEach { SessionRow(context, it, GlanceModifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text,
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
    Row(
        modifier
            .padding(vertical = 2.dp)
            .clickable(
                actionStartIntent(sessionIntent(context, session.routeId, session.sessionId))
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
