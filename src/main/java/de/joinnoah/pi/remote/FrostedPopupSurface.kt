package de.joinnoah.pi.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Popup glass uses an Activity snapshot: Haze sources cannot cross window boundaries. */
@Composable
internal fun FrostedPopupSurface(
    backdrop: AppBackdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = contentColorFor(color.copy(alpha = 1f)),
    shadowElevation: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // Keep FloatingSurface's outer Box and inner Surface measurement contract.
    Box(
        modifier.shadow(
            elevation = shadowElevation,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.18f),
            spotColor = Color.Black.copy(alpha = 0.18f),
        ).clip(shape).snapshotBackdrop(
            backdrop,
            tint = glassTint(color, scheme, blur = true),
            fallback = color.copy(alpha = 1f),
        ),
    ) {
        Surface(
            shape = shape,
            color = Color.Transparent,
            contentColor = contentColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(GLASS_RIM_WIDTH, glassRimColor(scheme)),
            content = content,
        )
    }
}
