package de.joinnoah.pi.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Parts the detail pages (tool detail, activity, changes, background jobs) share, so they read as
// one family: an overview card at the top, headings with counts, status pills and grouped rows.

/** The corner of a page's cards and grouped rows. */
internal val PAGE_CARD_CORNER = 20.dp

/** The shape of a card inside another card or a message: a plan, an image, an artifact preview. */
internal val INLINE_CARD_SHAPE = RoundedCornerShape(16.dp)

/**
 * What a page is about at a glance: a glass avatar with [icon], then [content]. A glass card in
 * the list, so it takes no blur of its own ([FloatingSurface] with `blur = false`).
 */
@Composable
internal fun OverviewCard(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    FloatingSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), blur = false) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassAvatar(size = 48.dp) { Icon(icon, null, tint = iconTint) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}

/** A status as a tinted pill with a dot in [content]'s colour. */
@Composable
internal fun StatusPill(label: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = CircleShape, color = container, contentColor = content) {
        Row(
            Modifier.padding(start = 10.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(content, CircleShape))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/**
 * A section heading with an optional [count] in a small pill. One heading node, so heading
 * navigation reads the count with its title.
 */
@Composable
internal fun SectionHeading(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    color: Color = MaterialTheme.colorScheme.onSurface,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 32.dp).semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
        count?.let {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(
                    it.toString(),
                    Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * The shape of one row of a group that reads as one card: the [first] row rounds the top, the
 * [last] the bottom.
 */
internal fun groupedRowShape(first: Boolean, last: Boolean, corner: Dp = PAGE_CARD_CORNER): Shape =
    RoundedCornerShape(
        topStart = if (first) corner else 0.dp,
        topEnd = if (first) corner else 0.dp,
        bottomStart = if (last) corner else 0.dp,
        bottomEnd = if (last) corner else 0.dp,
    )

/**
 * A card of a page in the floating-glass design, in place of Material's Card: a glass surface with
 * the page cards' rounder corners. [tint] keeps a notice's colour (an error or hint container) as
 * an opaque card; neutral cards get the glass tint. Inside the content it would blur, so it takes no
 * blur of its own. [onClick] makes the whole card one button.
 */
@Composable
internal fun GlassCard(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = contentColorFor(tint),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    FloatingSurface(modifier, shape = RoundedCornerShape(24.dp), color = tint, contentColor = contentColor, blur = false) {
        Column(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier, content = content)
    }
}
