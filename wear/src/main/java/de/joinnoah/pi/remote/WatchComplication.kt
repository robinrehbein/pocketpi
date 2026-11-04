package de.joinnoah.pi.remote

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

// The watch-face complication: the status tile's answer in one or two lines. It reads the tile's
// stored snapshot only and never asks the Mac itself, because watch faces draw it far more often
// than the system asks for the tile; the app, pushes and tile refreshes keep that snapshot current.
// The manifest's five-minute period only re-reads the stored snapshot, so ages and expiry move on.

/** What the complication says, before it becomes [ComplicationData]. [target] is the session a tap opens, or null for the app. */
internal data class WatchComplicationContent(
    val shortText: String, val shortTitle: String, val longText: String, val longTitle: String?,
    val description: String, val target: WatchTileRow?,
)

internal fun watchComplicationContent(glance: WatchGlance, resources: Resources, now: Long): WatchComplicationContent {
    if (!glance.home.paired) {
        val notPaired = resources.getString(R.string.remote_widget_not_paired)
        return WatchComplicationContent(resources.getString(R.string.wear_complication_pair), resources.getString(R.string.remote_app_name),
            notPaired, null, "$notPaired. ${resources.getString(R.string.remote_widget_pair_hint)}", null)
    }
    val waiting = glance.home.waitingCount()
    val working = glance.home.sessions.count { it.status == "running" }
    val summary = if (waiting == 0) resources.getString(R.string.remote_widget_none_waiting)
        else resources.getQuantityString(R.plurals.remote_widget_waiting, waiting, waiting)
    // TalkBack leads with what the short text shows.
    val spoken = if (waiting == 0 && working > 0) resources.getQuantityString(R.plurals.wear_complication_working_description, working, working) else summary
    // Waiting sessions come first: a count of them is the one thing worth a glance.
    val (count, title) = if (waiting == 0 && working > 0) working to resources.getQuantityString(R.plurals.wear_complication_working, working)
        else waiting to resources.getQuantityString(R.plurals.wear_complication_waiting, waiting)
    val top = watchTileRows(glance, now, 1).firstOrNull()
    val untitled = resources.getString(R.string.remote_widget_untitled)
    val longText = top?.let { it.title.ifBlank { untitled } } ?: summary
    val longTitle = top?.let { watchTileRowDetail(it, resources, now) }
    return WatchComplicationContent(count.toString(), title, longText, longTitle,
        listOfNotNull(spoken, top?.let { "${it.title.ifBlank { untitled }}, $longTitle" }).joinToString(". "), top)
}

internal fun watchComplicationData(type: ComplicationType, content: WatchComplicationContent, tap: PendingIntent?): ComplicationData? {
    val description = PlainComplicationText.Builder(content.description).build()
    return when (type) {
        ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(PlainComplicationText.Builder(content.shortText).build(), description)
            .setTitle(PlainComplicationText.Builder(content.shortTitle).build()).setTapAction(tap).build()
        ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(PlainComplicationText.Builder(content.longText).build(), description)
            .apply { content.longTitle?.let { setTitle(PlainComplicationText.Builder(it).build()) } }.setTapAction(tap).build()
        else -> null
    }
}

/** Opens [target] through the tile's trampoline, which the app validates like a notification. */
internal fun watchComplicationTap(context: Context, target: WatchTileRow?): PendingIntent {
    val intent = Intent(context, WatchTileActivity::class.java)
    WatchTileTarget.intentExtras(target).forEach { (key, value) -> intent.putExtra(key, value) }
    // One request code per target, so a tap never carries another session's stale extras.
    val code = target?.let { "${it.routeId}:${it.sessionId}".hashCode() } ?: 0
    return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** Asks every watch face showing the complication to read it again. */
internal fun requestWatchComplicationUpdate(context: Context) {
    ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, WatchComplicationService::class.java)).requestUpdateAll()
}

class WatchComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val glance = (application as WearApplication).glance.load()
        val content = watchComplicationContent(glance, RemoteNotifications.localized(this).resources, System.currentTimeMillis())
        return watchComplicationData(request.complicationType, content, watchComplicationTap(this, content.target))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val resources = RemoteNotifications.localized(this).resources
        val preview = WatchComplicationContent("1", resources.getQuantityString(R.plurals.wear_complication_waiting, 1),
            resources.getString(R.string.wear_complication_preview_title), resources.getString(R.string.wear_tile_needs_you),
            resources.getQuantityString(R.plurals.remote_widget_waiting, 1, 1), null)
        return watchComplicationData(type, preview, null)
    }
}
