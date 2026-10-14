package de.joinnoah.pi.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the user set for one layout bucket ([PaneLayout.sizeKey]): the session list's and the
 * inspector's widths as fractions of the window's width, and whether they hid the list. Null
 * leaves the layout's default.
 */
@Serializable
internal data class PaneMemo(
    val list: Float? = null,
    val inspector: Float? = null,
    val listCollapsed: Boolean? = null,
) {
    val empty: Boolean
        get() = list == null && inspector == null && listCollapsed == null
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
 * preferences file of its own: only widths and a flag, nothing that identifies a Mac or a chat.
 */
internal class PaneLayoutStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("pane_layout", Context.MODE_PRIVATE))

    fun load(): Map<String, PaneMemo> = decodePaneMemos(preferences.getString(KEY_MEMOS, null))

    fun save(memos: Map<String, PaneMemo>) {
        preferences.edit {
            if (memos.isEmpty()) remove(KEY_MEMOS) else putString(KEY_MEMOS, encodePaneMemos(memos))
        }
    }

    private companion object {
        const val KEY_MEMOS = "memos"
    }
}
