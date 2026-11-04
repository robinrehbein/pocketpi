package de.joinnoah.pi.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the user set for one layout bucket ([PaneLayout.sizeKey]): the session list's and the
 * inspector's widths as fractions of the window's width, whether they hid the list and whether
 * they keep the full projects column beside a chat. Null leaves the layout's default.
 */
@Serializable
internal data class PaneMemo(
    val list: Float? = null,
    val inspector: Float? = null,
    val listCollapsed: Boolean? = null,
    val projectsExpanded: Boolean? = null,
) {
    val empty: Boolean
        get() = list == null && inspector == null && listCollapsed == null && projectsExpanded == null
}

/** How long the widths wait after the last change, the end of a drag, before they are written. */
internal const val PANE_LAYOUT_SAVE_DELAY_MILLIS = 500L

private val paneLayoutJson = Json { ignoreUnknownKeys = true }

internal fun encodePaneMemos(memos: Map<String, PaneMemo>): String = paneLayoutJson.encodeToString(memos)

/** The memos in [text]; nothing for unreadable text, and no width outside (0, 1). */
internal fun decodePaneMemos(text: String?): Map<String, PaneMemo> {
    if (text.isNullOrEmpty()) return emptyMap()
    val decoded = runCatching { paneLayoutJson.decodeFromString<Map<String, PaneMemo>>(text) }.getOrNull() ?: return emptyMap()
    fun Float?.fraction() = this?.takeIf { it.isFinite() && it > 0f && it < 1f }
    return decoded
        .mapValues { (_, memo) -> memo.copy(list = memo.list.fraction(), inspector = memo.inspector.fraction()) }
        .filterValues { !it.empty }
}

/**
 * Keeps the dragged pane widths and the hidden list per layout bucket across restarts, in a
 * preferences file of its own: only widths and flags, nothing that identifies a Mac or a chat.
 * The "Wrap lines" choice of diffs and files lives here too.
 */
internal class PaneLayoutStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("pane_layout", Context.MODE_PRIVATE))

    fun load(): Map<String, PaneMemo> = decodePaneMemos(preferences.getString(KEY_MEMOS, null))

    fun save(memos: Map<String, PaneMemo>) {
        preferences.edit {
            if (memos.isEmpty()) remove(KEY_MEMOS) else putString(KEY_MEMOS, encodePaneMemos(memos))
        }
    }

    /** Diffs and files break long lines instead of scrolling them sideways; off by default. */
    fun loadWrapLines(): Boolean = preferences.getBoolean(KEY_WRAP_LINES, false)

    fun saveWrapLines(wrap: Boolean) {
        preferences.edit { if (wrap) putBoolean(KEY_WRAP_LINES, true) else remove(KEY_WRAP_LINES) }
    }

    private companion object {
        const val KEY_MEMOS = "memos"
        const val KEY_WRAP_LINES = "wrap_lines"
    }
}

/**
 * The "Wrap lines" toggle that Changes and Files share ([PaneHeader]); a change is written to
 * [store] at once, so it holds across panes and restarts.
 */
@Stable
internal class CodeWrapState(private val store: PaneLayoutStore) {
    var wrap by mutableStateOf(store.loadWrapLines())
        private set

    fun toggle() {
        wrap = !wrap
        store.saveWrapLines(wrap)
    }
}

/** The app's [CodeWrapState]; [RemoteNavigation] provides it. */
internal val LocalCodeWrap = staticCompositionLocalOf<CodeWrapState?> { null }

/** [LocalCodeWrap], or one of its own on the same preferences where nothing provides it. */
@Composable
internal fun rememberCodeWrap(): CodeWrapState {
    val provided = LocalCodeWrap.current
    val context = LocalContext.current
    return provided ?: remember(context) { CodeWrapState(PaneLayoutStore(context.applicationContext)) }
}
