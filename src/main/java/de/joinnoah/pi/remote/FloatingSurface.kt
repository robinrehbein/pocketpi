package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

@Composable
internal fun FloatingSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .dropShadow(
                shape,
                Shadow(
                    radius = 24.dp,
                    color = Color.Black.copy(alpha = 0.10f),
                    offset = DpOffset(0.dp, 10.dp),
                ),
            )
            .dropShadow(
                shape,
                Shadow(
                    radius = 8.dp,
                    color = Color.Black.copy(alpha = 0.07f),
                    offset = DpOffset(0.dp, 3.dp),
                ),
            )
    ) {
        Surface(shape = shape, color = color, shadowElevation = 0.dp, content = content)
    }
}
