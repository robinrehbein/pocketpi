package de.joinnoah.pi.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The list key of a list page's large title, the first item after its connection chip. */
internal const val PAGE_TITLE_KEY = "page-title"

/**
 * Whether the large title of a list page has scrolled under the header. The title then lives in
 * the back pill, so it stays in view. The header and the sticky connection item cover the top of
 * the list; the title counts as gone once its bottom is above that cover. [headerPx] is the
 * header's height in pixels.
 */
internal fun pageTitleScrolledAway(info: LazyListLayoutInfo, headerPx: Int): Boolean {
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return false
    // Above the top of the list: the title is no longer among the visible items.
    val title = visible.firstOrNull { it.key == PAGE_TITLE_KEY } ?: return true
    val covered = maxOf(visible.firstOrNull { it.key == CONNECTION_STATUS_KEY }?.size ?: 0, headerPx)
    return title.offset + title.size <= covered
}

/**
 * The header every page shares: a back pill, the page title and an actions pill. The title is
 * large in the page's list; with [titleInPill] it sits in the back pill instead, once the large
 * title has scrolled out of view. [onClose] adds a Close button to the back pill, for a page that
 * leaves a whole stack of its own at once. [connection] is the connection chip, shown under the
 * pills and kept by key while its text changes; a list page shows it as its sticky first item.
 */
@Composable
internal fun PageHeader(
    title: String,
    titleInPill: Boolean,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    connection: Int? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The back pill takes what the actions pill leaves, so a long title cuts off instead of
            // pushing the actions out.
            // The slot fills the row up to the actions pill, so the actions stay at the end.
            if (onBack != null)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    FloatingSurface(Modifier.testTag("navigationPill"), shape = CircleShape) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack, Modifier.size(48.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.remote_back))
                            }
                            AnimatedVisibility(
                                visible = titleInPill,
                                enter = fadeIn() + expandHorizontally(),
                                exit = fadeOut() + shrinkHorizontally(),
                            ) {
                                Text(
                                    title,
                                    Modifier.padding(end = if (onClose == null) 16.dp else 0.dp)
                                        .semantics { heading() }
                                        .testTag("pageHeaderTitle"),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (onClose != null)
                                IconButton(onClick = onClose, Modifier.size(48.dp).testTag("pageHeaderClose")) {
                                    Icon(Icons.Default.Close, stringResource(R.string.remote_page_close))
                                }
                        }
                    }
                }
            else Spacer(Modifier.weight(1f))
            if (actions != null)
                FloatingSurface(Modifier.testTag("headerActionsPill"), shape = CircleShape) {
                    Row(verticalAlignment = Alignment.CenterVertically, content = actions)
                }
        }
        if (connection != null)
            key(CONNECTION_STATUS_KEY) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp)) { ConnectionStatusChip(connection) }
            }
    }
}
