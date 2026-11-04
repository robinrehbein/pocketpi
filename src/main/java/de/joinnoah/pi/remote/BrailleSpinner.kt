package de.joinnoah.pi.remote

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.delay

private const val BRAILLE_FRAMES = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"

internal fun brailleSpinnerGlyph(frame: Int): String = BRAILLE_FRAMES[frame].toString()

/** One clock per list; its value is read only by the glyph, never by the screen. */
@Composable
internal fun rememberBrailleSpinnerFrame(active: Boolean): State<Int> =
    produceState(0, active) {
        value = 0
        if (!active) return@produceState
        var start: Long? = null
        while (true) {
            val motionDisabled = coroutineContext[MotionDurationScale]?.scaleFactor == 0f ||
                (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())
            if (motionDisabled) {
                value = 0
                start = null
                delay(1_000L)
            } else {
                withFrameMillis { time ->
                    val origin = start ?: time.also { start = it }
                    value = ((time - origin) / 80L % BRAILLE_FRAMES.length).toInt()
                }
            }
        }
    }

@Composable
internal fun BrailleSpinner(frame: State<Int>?, color: Color) {
    val style = MaterialTheme.typography.labelMedium
    val width = with(LocalDensity.current) { style.fontSize.toDp() * 1.2f }
    Box(
        Modifier.width(width).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            brailleSpinnerGlyph(frame?.value ?: 0),
            style = style,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}
